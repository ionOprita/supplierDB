package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideConflictException;
import ro.sellfluence.db.versions.EmagMirrorDBVersion50;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only the explicitly configured disposable database, never the application's database. */
class ProfitCalculatorTableIntegrationTest {
    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "profit_calculator_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute("""
                CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_group TEXT, company_name TEXT);
                CREATE TABLE product (
                    product_code TEXT PRIMARY KEY, name TEXT, emag_pnk TEXT,
                    vendor UUID REFERENCES vendor(id), category TEXT,
                    retracted BOOLEAN NOT NULL, continue_to_sell BOOLEAN NOT NULL,
                    emag_sale_price_ron NUMERIC, income_profit_tax TEXT, vat_payer BOOLEAN
                );
                """);
        EmagMirrorDBVersion50.version50(db);
    }

    @AfterEach
    void discardIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void filtersByGroupAndCategoryAcrossVendorsAndIncludesRetractedProducts() throws SQLException {
        var first = addVendor("S");
        var second = addVendor("\tS\u2003");
        var foreign = addVendor("J");
        addProduct("active", "S. 1 - Active", "PNK1", first, "Trimmers", false, true);
        addProduct("retracted", null, null, second, "\u2003Trimmers\t", true, false);
        addProduct("other-category", "Other", "PNK3", first, "Epilators", false, true);
        addProduct("other-group", "Other group", "PNK4", foreign, "Trimmers", false, true);

        var products = ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers");

        assertEquals(List.of("active", "retracted"), products.stream()
                .map(ProfitCalculatorTable.CategoryProduct::productCode).sorted().toList());
        var retracted = products.stream().filter(product -> product.productCode().equals("retracted"))
                .findFirst().orElseThrow();
        assertTrue(retracted.retracted());
        assertNull(retracted.name());
        assertNull(retracted.pnk());
        assertTrue(ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers' OR TRUE --").isEmpty());
        assertTrue(ProfitCalculatorTable.getCategoryProducts(db, "Z", "Trimmers").isEmpty());
    }

    @Test
    void readsNullableReferenceValuesAndPreservesZeroPriceAndFalseVatStatus() throws SQLException {
        var vendor = addVendor("S");
        addProduct("reference", "S. 1 - Reference", "PNK1", vendor, "Trimmers", false, true);
        addProduct("zero", "S. 2 - Zero", "PNK2", vendor, "Trimmers", false, true);
        addProduct("missing", "S. 3 - Missing", "PNK3", vendor, "Trimmers", false, true);
        setReferenceValues("reference", new BigDecimal("169.99"), "Profit", true);
        setReferenceValues("zero", BigDecimal.ZERO, "Venit", false);

        var products = ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers");
        var reference = products.stream().filter(product -> product.productCode().equals("reference"))
                .findFirst().orElseThrow();
        var zero = products.stream().filter(product -> product.productCode().equals("zero"))
                .findFirst().orElseThrow();
        var missing = products.stream().filter(product -> product.productCode().equals("missing"))
                .findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("169.99").compareTo(reference.emagSalePriceRon()));
        assertEquals("Profit", reference.incomeProfitTax());
        assertEquals(Boolean.TRUE, reference.vatPayer());
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.emagSalePriceRon()));
        assertEquals("Venit", zero.incomeProfitTax());
        assertEquals(Boolean.FALSE, zero.vatPayer());
        assertNull(missing.emagSalePriceRon());
        assertNull(missing.incomeProfitTax());
        assertNull(missing.vatPayer());
    }

    @Test
    void savesAndReloadsSharedOverridesWithoutChangingProductMasterData() throws SQLException {
        var vendor = addVendor("S");
        execute("UPDATE vendor SET company_name = 'Sellfusion SRL'");
        addProduct("reference", "S. 1 - Reference", "PNK1", vendor, "Trimmers", false, true);
        setReferenceValues("reference", new BigDecimal("169.99"), "Profit", true);

        ProfitCalculatorTable.saveOverrides(db, List.of(new OverrideChange("reference", 0, Map.of(
                "salePriceRon", "149.99", "commissionRate", "0.14", "purchasePriceUsd", "10.25",
                "transportPriceUsd", "0", "usdRonRate", "4.5555", "fulfillmentSource", "eMAG"))));
        var saved = product("reference");
        assertEquals("Sellfusion SRL", saved.companyName());
        assertEquals(1, saved.overrideRevision());
        assertEquals(0, new BigDecimal("149.99").compareTo(saved.overrides().salePriceRon()));
        assertEquals(0, new BigDecimal("0.14").compareTo(saved.overrides().commissionRate()));
        assertEquals(0, new BigDecimal("10.25").compareTo(saved.overrides().purchasePriceUsd()));
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.overrides().transportPriceUsd()));
        assertEquals(0, new BigDecimal("4.5555").compareTo(saved.overrides().usdRonRate()));
        assertEquals("eMAG", saved.overrides().fulfillmentSource());
        assertEquals(0, new BigDecimal("169.99").compareTo(saved.emagSalePriceRon()));
        assertEquals("Profit", saved.incomeProfitTax());
        assertTrue(saved.vatPayer());

        var changed = new LinkedHashMap<String, String>();
        changed.put("salePriceRon", null);
        changed.put("commissionRate", "0");
        ProfitCalculatorTable.saveOverrides(db, List.of(new OverrideChange("reference", 1, changed)));
        var cleared = product("reference");
        assertEquals(2, cleared.overrideRevision());
        assertNull(cleared.overrides().salePriceRon());
        assertEquals(0, BigDecimal.ZERO.compareTo(cleared.overrides().commissionRate()));
        assertEquals(0, new BigDecimal("10.25").compareTo(cleared.overrides().purchasePriceUsd()));
        assertEquals("eMAG", cleared.overrides().fulfillmentSource());
    }

    @Test
    void staleRevisionsRollbackTheWholeBatchIncludingEarlierInserts() throws SQLException {
        var vendor = addVendor("S");
        addProduct("a-first", "S. 1 - First", "PNK1", vendor, "Trimmers", false, true);
        addProduct("z-conflict", "S. 2 - Conflict", "PNK2", vendor, "Trimmers", false, true);
        ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("z-conflict", 0, Map.of("salePriceRon", "100"))));

        var conflict = assertThrows(OverrideConflictException.class, () -> ProfitCalculatorTable.saveOverrides(db, List.of(
                new OverrideChange("a-first", 0, Map.of("salePriceRon", "20")),
                new OverrideChange("z-conflict", 0, Map.of("salePriceRon", "200")))));
        assertEquals("z-conflict", conflict.productCode());
        assertEquals(0, product("a-first").overrideRevision());
        assertNull(product("a-first").overrides().salePriceRon());
        assertEquals(0, new BigDecimal("100").compareTo(product("z-conflict").overrides().salePriceRon()));

        ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("z-conflict", 1, Map.of("salePriceRon", "120"))));
        assertThrows(OverrideConflictException.class, () -> ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("z-conflict", 1, Map.of("commissionRate", "0.2")))));
        assertEquals(2, product("z-conflict").overrideRevision());
        assertNull(product("z-conflict").overrides().commissionRate());
    }

    @Test
    void clearingEveryFieldRetainsItsRevisionAndOverridesCanBeStoredForIneligibleProducts() throws SQLException {
        var vendor = addVendor("S");
        addProduct("retracted", "S. 1 - Retracted", null, vendor, "Trimmers", true, false);
        ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("retracted", 0, Map.of("salePriceRon", "0"))));
        var clearing = new LinkedHashMap<String, String>();
        clearing.put("salePriceRon", null);
        ProfitCalculatorTable.saveOverrides(db, List.of(new OverrideChange("retracted", 1, clearing)));

        var saved = product("retracted");
        assertEquals(2, saved.overrideRevision());
        assertEquals(ProfitCalculatorTable.Overrides.empty(), saved.overrides());
        assertTrue(saved.retracted());
        assertNull(saved.pnk());
        assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("not-a-product", 0, Map.of("salePriceRon", "1")))));
        assertEquals(2, product("retracted").overrideRevision());
    }

    @Test
    void concurrentCreationAndUpdateRejectTheOtherWritersStaleRevision() throws Exception {
        var vendor = addVendor("S");
        addProduct("shared", "S. 1 - Shared", "SHARED", vendor, "Trimmers", false, true);
        db.commit();
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        try (var other = DriverManager.getConnection(System.getenv("ADS_TEST_DB_URL"), properties)) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("SET lock_timeout TO '5s'");
            }
            assertConcurrentConflict(other, new OverrideChange("shared", 0, Map.of("salePriceRon", "100")),
                    new OverrideChange("shared", 0, Map.of("salePriceRon", "200")));
            assertEquals(1, product("shared").overrideRevision());
            assertConcurrentConflict(other, new OverrideChange("shared", 1, Map.of("salePriceRon", "110")),
                    new OverrideChange("shared", 1, Map.of("commissionRate", "0.2")));
            assertEquals(2, product("shared").overrideRevision());
            assertEquals(0, new BigDecimal("110").compareTo(product("shared").overrides().salePriceRon()));
            assertNull(product("shared").overrides().commissionRate());
            other.rollback();
        }
    }

    private void assertConcurrentConflict(Connection other, OverrideChange first, OverrideChange second) throws Exception {
        ProfitCalculatorTable.saveOverrides(db, List.of(first));
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var conflict = executor.submit(() -> {
                started.countDown();
                try {
                    ProfitCalculatorTable.saveOverrides(other, List.of(second));
                    return null;
                } catch (OverrideConflictException error) {
                    return error;
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            db.commit();
            assertNotNull(conflict.get(5, TimeUnit.SECONDS));
        }
    }

    private ProfitCalculatorTable.CategoryProduct product(String code) throws SQLException {
        return ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers").stream()
                .filter(product -> product.productCode().equals(code)).findFirst().orElseThrow();
    }

    private void setReferenceValues(String code, BigDecimal price, String taxRegime, Boolean vatPayer)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                UPDATE product SET emag_sale_price_ron = ?, income_profit_tax = ?, vat_payer = ?
                WHERE product_code = ?
                """)) {
            statement.setBigDecimal(1, price);
            statement.setString(2, taxRegime);
            statement.setObject(3, vatPayer);
            statement.setString(4, code);
            statement.executeUpdate();
        }
    }

    private UUID addVendor(String group) throws SQLException {
        var id = UUID.randomUUID();
        try (var statement = db.prepareStatement("INSERT INTO vendor (id, vendor_group) VALUES (?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, group);
            statement.executeUpdate();
        }
        return id;
    }

    private void addProduct(String code, String name, String pnk, UUID vendor, String category,
                            boolean retracted, boolean continueToSell) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO product (product_code, name, emag_pnk, vendor, category, retracted, continue_to_sell)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, pnk);
            statement.setObject(4, vendor);
            statement.setString(5, category);
            statement.setBoolean(6, retracted);
            statement.setBoolean(7, continueToSell);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
