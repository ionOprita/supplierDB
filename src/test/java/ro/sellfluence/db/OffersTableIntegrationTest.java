package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.emagdashboard.Measurements;
import ro.sellfluence.emagdashboard.Offer;
import ro.sellfluence.emagdashboard.Offers;
import ro.sellfluence.emagdashboard.OffersData;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and an isolated schema. */
class OffersTableIntegrationTest {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 9, 23);
    private static final LocalDate SECOND_DAY = FIRST_DAY.plusDays(1);
    private static final List<String> CHILD_TABLES = List.of(
            "offers_stock", "offers_price", "offers_links", "offers_supply_recommendation", "offers_measurement"
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UUID firstVendor = UUID.randomUUID();
    private final UUID secondVendor = UUID.randomUUID();
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
        schema = "offers_storage_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY)");
        applyMigration();
        try (var statement = db.prepareStatement("INSERT INTO vendor (id) VALUES (?), (?)")) {
            statement.setObject(1, firstVendor);
            statement.setObject(2, secondVendor);
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
    void storesEveryRecordComponentWithoutLosingPrecisionOrListOrder() throws Exception {
        var offer = fullOffer("offer-1");

        assertEquals(1, OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(offer)));

        assertEquals(1, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertStoredRecord("offers_offer", offer);
        assertStoredRecord("offers_stock", offer.offerStock());
        assertStoredRecord("offers_price", offer.offerPrice());
        assertStoredRecord("offers_links", offer.links());
        assertStoredRecord("offers_supply_recommendation", offer.supplyRecommendationData());
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT * FROM offers_measurement ORDER BY position")) {
            for (int position = 0; position < offer.measurements().size(); position++) {
                assertTrue(rows.next());
                assertEquals(position, rows.getInt("position"));
                assertRecordColumns(rows, offer.measurements().get(position));
            }
            assertFalse(rows.next());
        }
        assertEquals(7, count("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name LIKE 'offers_%'
                """));
        assertEquals(7, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name LIKE 'offers_%'
                    AND column_name = 'fetch_date' AND data_type = 'date'
                """));
        assertEquals(3, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'offers_snapshot'
                """), "Snapshot metadata contains only vendor, date and count");
        assertEquals(0, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name LIKE 'offers_%'
                    AND data_type = 'timestamp with time zone'
                """));
    }

    @Test
    void sameDayReplacementRemovesAbsentOffersAndOptionalChildren() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(fullOffer("kept"), fullOffer("removed")));
        var revised = offer("""
                {"id":"kept", "sellerName":"Updated seller", "extSalePrice":19.87654321,
                 "measurements":[], "offerStock":{"propertyTypes":[]}}
                """);

        assertEquals(1, OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(revised)));

        assertEquals(1, count("SELECT count(*) FROM offers_offer"));
        assertEquals(1, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertStoredRecord("offers_offer", revised);
        assertStoredRecord("offers_stock", revised.offerStock());
        for (var table : CHILD_TABLES.subList(1, CHILD_TABLES.size())) {
            assertEquals(0, count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void matchingOfferIdsRemainIndependentAcrossVendorsAndDatesIncludingEmptySnapshots() throws Exception {
        var full = snapshot(fullOffer("shared-id"));
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, full);
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, full);
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY, full);

        assertEquals(0, OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot()));

        assertEquals(3, count("SELECT count(*) FROM offers_snapshot"));
        assertEquals(0, snapshotCount(firstVendor, FIRST_DAY));
        assertEquals(1, snapshotCount(firstVendor, SECOND_DAY));
        assertEquals(1, snapshotCount(secondVendor, FIRST_DAY));
        assertEquals(2, count("SELECT count(*) FROM offers_offer"));
        for (var table : CHILD_TABLES) {
            assertEquals(table.equals("offers_measurement") ? 4 : 2,
                    count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void preservesNullAndEmptyCollectionsAndAbsentVersusPresentEmptyRecords() throws SQLException {
        var missing = offer("{\"id\":\"missing\"}");
        var empty = offer("""
                {"id":"empty", "eans":[], "inactivationCriticalities":[], "offerProperties":[],
                 "measurements":[], "offerStock":{"propertyTypes":[]}, "offerPrice":{},
                 "links":{}, "supplyRecommendationData":{}}
                """);
        var values = offer("""
                {"id":"values", "eans":["001",null,"","001"], "offerStock":{}}
                """);

        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(missing, empty, values));

        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT * FROM offers_offer ORDER BY offer_id")) {
            assertTrue(rows.next());
            assertEquals("empty", rows.getString("offer_id"));
            assertTrue(rows.getBoolean("measurements_present"));
            for (var column : List.of("eans", "inactivation_criticalities", "offer_properties")) {
                assertEquals(List.of(), arrayValues(rows, column));
            }
            assertTrue(rows.next());
            assertEquals("missing", rows.getString("offer_id"));
            assertFalse(rows.getBoolean("measurements_present"));
            for (var column : List.of("eans", "inactivation_criticalities", "offer_properties")) {
                assertNull(rows.getArray(column));
            }
            assertTrue(rows.next());
            assertEquals(Arrays.asList("001", null, "", "001"), arrayValues(rows, "eans"));
            assertFalse(rows.next());
        }
        assertEquals(0, count("SELECT count(*) FROM offers_measurement"));
        assertEquals(2, count("SELECT count(*) FROM offers_stock"));
        assertEquals(1, count("SELECT count(*) FROM offers_stock WHERE property_types IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM offers_stock WHERE cardinality(property_types) = 0"));
        for (var table : List.of("offers_price", "offers_links", "offers_supply_recommendation")) {
            assertEquals(1, count("SELECT count(*) FROM " + table), table);
        }
        assertEquals(1, count("SELECT count(*) FROM offers_price WHERE status_details IS NULL"));
    }

    @Test
    void rejectsMalformedAndPartialSnapshotsBeforeChangingTheExistingSnapshot() throws Exception {
        var original = fullOffer("original");
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(original));
        for (var total : Arrays.asList((Integer) null, -1, 0, 2)) {
            assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                    new OffersData(new Offers(List.of(original), total))));
        }
        assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(original, original)));
        for (var json : List.of("{}", "{\"id\":\"\"}", "{\"id\":\"  \"}")) {
            assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                    snapshot(offer(json))));
        }
        assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                new OffersData(new Offers(Arrays.asList((Offer) null), 1))));
        var nullMeasurement = sampleRecord(Offer.class, 1,
                Map.of("id", "original", "measurements", Arrays.asList((Measurements) null)));
        assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(nullMeasurement)));
        assertThrows(NullPointerException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, null));
        assertThrows(NullPointerException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                new OffersData(null)));
        assertThrows(NullPointerException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                new OffersData(new Offers(null, 0))));
        assertEquals(1, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertStoredRecord("offers_offer", original);
        assertStoredRecord("offers_links", original.links());
    }

    @Test
    void databaseFailureRollsBackParentReplacementAndEveryChildTable() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(fullOffer("original-1"), fullOffer("original-2")));
        execute("ALTER TABLE offers_measurement ADD CHECK (weight >= 0)");
        db.commit();
        var failingOffer = sampleRecord(Offer.class, 2, Map.of(
                "id", "replacement", "measurements", List.of(new Measurements(BigDecimal.valueOf(-1), null, null, null))));

        assertThrows(SQLException.class,
                () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(failingOffer)));
        db.rollback();

        assertEquals(2, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertEquals(2, count("SELECT count(*) FROM offers_offer WHERE offer_id LIKE 'original-%'"));
        assertEquals(0, count("SELECT count(*) FROM offers_offer WHERE offer_id = 'replacement'"));
        for (var table : CHILD_TABLES) {
            assertEquals(table.equals("offers_measurement") ? 4 : 2,
                    count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void foreignKeysEnforceVendorDateAndOfferOwnershipAndCascadeSnapshotDeletion() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(fullOffer("shared-id")));
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY, snapshot());
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot());
        assertRejected("23503", () -> OffersTable.storeSnapshot(db, UUID.randomUUID(), FIRST_DAY, snapshot()));
        assertRejected("23503", () -> execute("DELETE FROM vendor WHERE id = '" + firstVendor + "'"));
        for (var table : CHILD_TABLES) {
            var positionColumn = table.equals("offers_measurement") ? ", position" : "";
            var positionValue = table.equals("offers_measurement") ? ", 0" : "";
            String insert = "INSERT INTO " + table + " (vendor_id, fetch_date, offer_id" + positionColumn + ") VALUES ";
            assertRejected("23503", () -> execute(insert + "('" + secondVendor + "', DATE '" + FIRST_DAY
                    + "', 'shared-id'" + positionValue + ")"));
            assertRejected("23503", () -> execute(insert + "('" + firstVendor + "', DATE '" + SECOND_DAY
                    + "', 'shared-id'" + positionValue + ")"));
        }
        assertRejected("23505", () -> execute("""
                INSERT INTO offers_offer (vendor_id, fetch_date, offer_id, measurements_present)
                SELECT vendor_id, fetch_date, offer_id, measurements_present FROM offers_offer
                """));
        assertRejected("23502", () -> execute("UPDATE offers_snapshot SET fetch_date = NULL"));
        assertRejected("23502", () -> execute("UPDATE offers_snapshot SET vendor_id = NULL"));

        execute("DELETE FROM offers_snapshot");

        assertEquals(0, count("SELECT count(*) FROM offers_offer"));
        for (var table : CHILD_TABLES) assertEquals(0, count("SELECT count(*) FROM " + table), table);
        assertEquals(2, count("SELECT count(*) FROM vendor"));
    }

    @Test
    void refusesAutocommitSoCallersCannotPartiallyReplaceASnapshot() throws SQLException {
        db.setAutoCommit(true);
        try {
            assertThrows(SQLException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot()));
            assertEquals(0, count("SELECT count(*) FROM offers_snapshot"));
        } finally {
            db.setAutoCommit(false);
        }
    }

    private static OffersData snapshot(Offer... offers) {
        return new OffersData(new Offers(List.of(offers), offers.length));
    }

    private static Offer offer(String json) {
        return JSON.readValue(json, Offer.class);
    }

    private static Offer fullOffer(String id) throws ReflectiveOperationException {
        return sampleRecord(Offer.class, 1, Map.of("id", id));
    }

    /** Distinct values for every field catch missing columns and mismatched statement positions. */
    private static <T extends Record> T sampleRecord(Class<T> type, int seed, Map<String, Object> overrides)
            throws ReflectiveOperationException {
        var fields = type.getRecordComponents();
        var values = new Object[fields.length];
        var types = new Class<?>[fields.length];
        for (int index = 0; index < fields.length; index++) {
            var field = fields[index];
            var fieldType = field.getType();
            types[index] = fieldType;
            int value = seed * 100 + index;
            if (overrides.containsKey(field.getName())) values[index] = overrides.get(field.getName());
            else if (fieldType == String.class) values[index] = field.getName() + "-" + value;
            else if (fieldType == Integer.class) values[index] = value;
            else if (fieldType == Boolean.class) values[index] = index % 2 == 0;
            else if (fieldType == BigDecimal.class) values[index] = new BigDecimal(value + ".123456789");
            else if (fieldType == LocalDate.class) values[index] = FIRST_DAY.plusDays(index);
            else if (fieldType == LocalDateTime.class) {
                values[index] = FIRST_DAY.atTime(13, 27, 41, 123456000).plusDays(index);
            } else if (fieldType == List.class) {
                var elementType = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                if (elementType == String.class) {
                    values[index] = List.of(field.getName(), "quote \" and backslash \\", "", "last");
                } else if (elementType == Measurements.class) {
                    values[index] = List.of(sampleRecord(Measurements.class, 2, Map.of()),
                            sampleRecord(Measurements.class, 3, Map.of()));
                } else throw new AssertionError("Unsupported list type: " + field);
            } else if (fieldType.isRecord()) {
                values[index] = sampleRecord(fieldType.asSubclass(Record.class), seed + 1, Map.of());
            } else throw new AssertionError("Unsupported record component: " + field);
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }

    private void assertStoredRecord(String table, Record expected) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
            assertTrue(rows.next(), table);
            assertEquals(firstVendor, rows.getObject("vendor_id", UUID.class));
            assertEquals(FIRST_DAY, rows.getObject("fetch_date", LocalDate.class));
            assertRecordColumns(rows, expected);
            assertFalse(rows.next(), table);
        }
    }

    private static void assertRecordColumns(ResultSet rows, Record expected) throws Exception {
        for (var field : expected.getClass().getRecordComponents()) {
            if (field.getType().isRecord() || field.getName().equals("measurements")) continue;
            String column = field.getName().equals("id") ? "offer_id"
                    : field.getName().replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
            Object value = field.getAccessor().invoke(expected);
            if (value instanceof BigDecimal decimal) {
                var actual = rows.getBigDecimal(column);
                assertNotNull(actual, column);
                assertEquals(0, decimal.compareTo(actual), column);
            } else if (value instanceof List<?>) assertEquals(value, arrayValues(rows, column), column);
            else if (value instanceof LocalDate) assertEquals(value, rows.getObject(column, LocalDate.class), column);
            else if (value instanceof LocalDateTime) assertEquals(value, rows.getObject(column, LocalDateTime.class), column);
            else assertEquals(value, rows.getObject(column), column);
        }
    }

    private static List<String> arrayValues(ResultSet rows, String column) throws SQLException {
        var array = rows.getArray(column);
        if (array == null) return null;
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private int snapshotCount(UUID vendor, LocalDate date) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT total_number_of_items FROM offers_snapshot WHERE vendor_id = ? AND fetch_date = ?
                """)) {
            statement.setObject(1, vendor);
            statement.setObject(2, date);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private void assertRejected(String sqlState, SqlAction action) throws SQLException {
        var savepoint = db.setSavepoint();
        var exception = assertThrows(SQLException.class, action::run);
        assertEquals(sqlState, exception.getSQLState());
        db.rollback(savepoint);
        db.releaseSavepoint(savepoint);
    }

    private long count(String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private void applyMigration() throws Exception {
        var type = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion41");
        var method = type.getDeclaredMethod("version41", Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(null, db);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception failure) throw failure;
            throw exception;
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
