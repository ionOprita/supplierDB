package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.emagapi.OrderResult;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

/** Uses only an explicitly supplied disposable PostgreSQL database and an isolated schema. */
class EmagOrderProformsIntegrationTest {
    private static final String ORDER_ID = "534972746";
    private static final String VENDOR_NAME = "Example Vendor";
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new SimpleModule().addDeserializer(LocalDateTime.class, new ValueDeserializer<>() {
                @Override
                public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) {
                    return toLocalDateTime(parser.getString());
                }
            }))
            .build();

    private final UUID vendorId = UUID.randomUUID();
    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "order_proforms_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        createOrderTables();
        try (var statement = db.prepareStatement("INSERT INTO vendor (id, vendor_name) VALUES (?, ?)")) {
            statement.setObject(1, vendorId);
            statement.setString(2, VENDOR_NAME);
            statement.executeUpdate();
        }
        db.commit();
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                db.setSchema("public");
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void insertsStructuredProformsAndReadsThemThroughBulkAndFullOrderReconstruction() throws Exception {
        var sample = sampleProform();
        var order = order("[" + sample + "," + sample.replace("3428567", "3428568") + "]");

        EmagOrder.addOrderResult(order, db, vendorId, VENDOR_NAME);

        var stored = storedJson();
        assertTrue(JSON.readTree(stored).isArray(), "The text column stores a complete JSON array");
        assertEquals(2, JSON.readTree(stored).size());
        assertEquals(order.proforms(), bulkOrder().proforms());
        assertEquals(2, bulkOrder().proforms().getFirst().products().size());

        // An unchanged fetch exercises the complete reconstruction used by the update path.
        EmagOrder.addOrderResult(order, db, vendorId, VENDOR_NAME);

        assertEquals(stored, storedJson());
        assertEquals(order.proforms(), bulkOrder().proforms());
    }

    @Test
    void updatesOnlyProformsIncludingEmptyToNonemptyAndNonemptyToEmpty() throws Exception {
        var empty = order("[]");
        var populated = order("[" + sampleProform() + "]");
        EmagOrder.addOrderResult(empty, db, vendorId, VENDOR_NAME);
        assertEquals("[]", storedJson());

        EmagOrder.addOrderResult(populated, db, vendorId, VENDOR_NAME);

        assertEquals(populated.proforms(), bulkOrder().proforms());
        EmagOrder.addOrderResult(empty, db, vendorId, VENDOR_NAME);
        assertEquals("[]", storedJson());
        assertEquals(List.of(), bulkOrder().proforms());
    }

    @Test
    void readsLegacyNullAndBlankColumnsAndUpdatesThemToStructuredJson() throws Exception {
        var empty = order("[]");
        var populated = order("[" + sampleProform() + "]");
        EmagOrder.addOrderResult(empty, db, vendorId, VENDOR_NAME);
        for (var legacy : new String[]{null, "", " \n\t "}) {
            try (var statement = db.prepareStatement("UPDATE emag_order SET proforms = ? WHERE id = ?")) {
                statement.setString(1, legacy);
                statement.setString(2, ORDER_ID);
                statement.executeUpdate();
            }
            assertEquals(List.of(), bulkOrder().proforms());

            EmagOrder.addOrderResult(populated, db, vendorId, VENDOR_NAME);

            assertEquals(populated.proforms(), bulkOrder().proforms());
            assertTrue(JSON.readTree(storedJson()).isArray());
        }
    }

    private static OrderResult order(String proforms) {
        return JSON.readValue("""
                {"vendor_name":"%s", "id":"%s", "status":1, "is_complete":1,
                 "type":1, "payment_mode_id":1, "details":{"locker_delivery_eligible":0},
                 "payment_status":0, "late_shipment":0, "has_editable_products":0,
                 "emag_club":0, "weekend_delivery":0,
                 "shipping_tax_voucher_split":[], "proforms":%s}
                """.formatted(VENDOR_NAME, ORDER_ID, proforms), OrderResult.class);
    }

    private static String sampleProform() throws Exception {
        try (var input = EmagOrderProformsIntegrationTest.class.getResourceAsStream(
                "/ro/sellfluence/emagapi/proform.json")) {
            assertNotNull(input, "Shared proforma regression fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private OrderResult bulkOrder() throws SQLException {
        var matching = EmagOrder.selectAllOrders(db, Map.of(), Map.of(vendorId, VENDOR_NAME)).get(ORDER_ID);
        assertNotNull(matching);
        assertEquals(1, matching.size(), "Updates preserve the existing order row");
        return matching.getFirst().order();
    }

    private String storedJson() throws SQLException {
        try (var statement = db.prepareStatement("SELECT proforms FROM emag_order WHERE id = ?")) {
            statement.setString(1, ORDER_ID);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Minimal current tables for orders with no dependents; migrations hard-code the public schema. */
    private void createOrderTables() throws SQLException {
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_name TEXT)");
        execute("""
                CREATE TABLE emag_order (
                    surrogate_id SERIAL PRIMARY KEY,
                    vendor_id UUID REFERENCES vendor(id), id TEXT, status INTEGER,
                    is_complete INTEGER, type INTEGER, payment_mode TEXT, payment_mode_id INTEGER,
                    delivery_payment_mode TEXT, delivery_mode TEXT, observation TEXT, details_id TEXT,
                    date TIMESTAMP, payment_status INTEGER, cashed_co NUMERIC(10,2), cashed_cod NUMERIC(10,2),
                    shipping_tax NUMERIC(10,2), customer_id INTEGER, is_storno BOOLEAN,
                    cancellation_reason INTEGER, cancellation_reason_text TEXT, refunded_amount NUMERIC(10,2),
                    refund_status TEXT, maximum_date_for_shipment TIMESTAMP, finalization_date TIMESTAMP,
                    parent_id TEXT, detailed_payment_method TEXT, proforms TEXT, cancellation_request TEXT,
                    has_editable_products INTEGER, late_shipment INTEGER, emag_club INTEGER,
                    weekend_delivery INTEGER, created TIMESTAMP, modified TIMESTAMP,
                    UNIQUE (id, vendor_id, status)
                )
                """);
        execute("CREATE TABLE customer (id INTEGER PRIMARY KEY)");
        execute("CREATE TABLE product_in_order (id INTEGER, emag_order_surrogate_id INTEGER)");
        execute("""
                CREATE TABLE order_voucher_split (emag_order_surrogate_id INTEGER, voucher_id INTEGER,
                    value NUMERIC, vat_value NUMERIC, vat TEXT, offered_by TEXT, voucher_name TEXT)
                """);
        execute("""
                CREATE TABLE attachment (emag_order_surrogate_id INTEGER, name TEXT, url TEXT,
                    type INTEGER, force_download INTEGER, visibility TEXT)
                """);
        execute("""
                CREATE TABLE voucher (emag_order_surrogate_id INTEGER, voucher_id INTEGER,
                    modified TEXT, created TEXT, status INTEGER, sale_price_vat NUMERIC,
                    sale_price NUMERIC, voucher_name TEXT, vat NUMERIC, issue_date TEXT, id TEXT)
                """);
        execute("CREATE TABLE enforced_vendor_courier_account (emag_order_surrogate_id INTEGER, courier TEXT)");
        execute("CREATE TABLE flag (emag_order_surrogate_id INTEGER, flag TEXT, value TEXT)");
    }
}
