package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductPerformanceHistoryTable.ImportBatch;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real PostgreSQL JSON/transaction/selection checks in an isolated disposable schema only. */
class ProductPerformanceHistoryTableIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 5, 11);
    private final UUID firstVendor = UUID.randomUUID();
    private final UUID secondVendor = UUID.randomUUID();
    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL must refer to a disposable PostgreSQL database");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "product_history_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("CREATE TABLE version_info (version INTEGER PRIMARY KEY, date TIMESTAMPTZ)");
        execute("INSERT INTO version_info VALUES (48, CURRENT_TIMESTAMP)");
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY)");
        execute("CREATE TABLE product (product_code TEXT PRIMARY KEY, vendor UUID REFERENCES vendor (id))");
        execute("CREATE TABLE ads_campaign (vendor_id UUID, report_date DATE, campaign_id INTEGER)");
        assertTrue(ProductPerformanceHistoryTable.prepareSchema(db));
        try (var statement = db.prepareStatement("INSERT INTO vendor VALUES (?), (?)")) {
            statement.setObject(1, firstVendor);
            statement.setObject(2, secondVendor);
            statement.executeUpdate();
        }
        try (var statement = db.prepareStatement("INSERT INTO product VALUES ('P1', ?), ('P2', ?)")) {
            statement.setObject(1, firstVendor);
            statement.setObject(2, secondVendor);
            statement.executeUpdate();
        }
        db.commit();
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (db == null) return;
        try (var connection = db) {
            connection.rollback();
            connection.setAutoCommit(true);
            connection.setSchema("public");
            if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void preservesRawEvidenceNullsZerosAndDecimalPrecisionAndMakesRepeatImportsIdempotent() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("clicks", 0L);
        values.put("spend", new BigDecimal("123.123456789123456789"));
        values.put("missing", null);
        values.put("label", "unknown classification");
        var original = observation(firstVendor, "P1", DAY, "ads", values, "source-1");
        var batch = new ImportBatch(hash('a'), """
                {"raw":"original, untranslated text", "unmapped":[{"row":9,"error":"Unknown account"}]}
                """, List.of(original));

        var first = ProductPerformanceHistoryTable.storeBatch(db, batch);
        var repeated = ProductPerformanceHistoryTable.storeBatch(db, batch);

        assertTrue(first.inserted());
        assertFalse(repeated.inserted());
        assertEquals(first.batchId(), repeated.batchId());
        assertEquals(1, repeated.observationCount());
        assertEquals(1, count("SELECT COUNT(*) FROM product_performance_history_batch"));
        assertEquals(1, count("SELECT COUNT(*) FROM product_performance_history_observation"));
        var rows = ProductPerformanceHistoryTable.getProductPerformanceHistory(db, firstVendor, "P1");
        assertEquals(1, rows.size());
        var stored = rows.getFirst();
        assertEquals(BigInteger.ZERO, stored.values().get("clicks"));
        assertEquals(new BigDecimal("123.123456789123456789"), stored.values().get("spend"));
        assertTrue(stored.values().containsKey("missing"));
        assertNull(stored.values().get("missing"));
        assertEquals("unknown classification", stored.values().get("label"));
        assertEquals(original.sourceRange(), stored.sourceRange());
        assertEquals(DAY, stored.observationDate());
        try (var statement = db.createStatement(); var result = statement.executeQuery("""
                SELECT source_payload ->> 'raw' AS original, source_payload -> 'unmapped' AS unmapped
                FROM product_performance_history_batch
                """)) {
            assertTrue(result.next());
            assertEquals("original, untranslated text", result.getString("original"));
            assertNotNull(result.getString("unmapped"));
        }
    }

    @Test
    void returnsLatestRevisionAcrossFilesWithoutDeletingEarlierEvidenceOrOtherDatesAndDomains() throws Exception {
        ProductPerformanceHistoryTable.storeBatch(db, new ImportBatch(hash('a'), "{}", List.of(
                observation(firstVendor, "P1", DAY.minusDays(7), "organic", Map.of("clicks", 20), "source-1"),
                observation(firstVendor, "P1", DAY, "organic", Map.of("clicks", 30), "source-1"),
                observation(firstVendor, "P1", DAY, "rating", Map.of("rating", new BigDecimal("4.5")), "source-1"),
                observation(secondVendor, "P2", DAY, "organic", Map.of("clicks", 999), "source-1"))));
        ProductPerformanceHistoryTable.storeBatch(db, new ImportBatch(hash('b'), "{\"parserVersion\":2}", List.of(
                observation(firstVendor, "P1", DAY, "organic", Map.of("clicks", 40), "corrected-source"))));

        var rows = ProductPerformanceHistoryTable.getProductPerformanceHistory(db, firstVendor, "P1");
        assertEquals(3, rows.size());
        var corrected = rows.stream().filter(row -> row.observationDate().equals(DAY)
                && row.domain().equals("organic")).findFirst().orElseThrow();
        assertEquals(BigInteger.valueOf(40), corrected.values().get("clicks"));
        assertEquals("corrected-source", corrected.sourceSpreadsheetId());
        assertEquals(5, count("SELECT COUNT(*) FROM product_performance_history_observation"));
        assertEquals(2, count("SELECT COUNT(*) FROM product_performance_history_batch"));
        assertTrue(ProductPerformanceHistoryTable.getProductPerformanceHistory(db, secondVendor, "P1").isEmpty());
        assertEquals(1, ProductPerformanceHistoryTable.getProductPerformanceHistory(db, secondVendor, "P2").size());
    }

    @Test
    void refusesWrongAccountAndRollsBackTheBatchWithTheCallerTransaction() throws Exception {
        var batch = new ImportBatch(hash('c'), "{}", List.of(
                observation(firstVendor, "P1", DAY, "organic", Map.of("clicks", 20), "source"),
                observation(firstVendor, "P2", DAY, "organic", Map.of("clicks", 30), "source")));

        assertThrows(SQLException.class, () -> ProductPerformanceHistoryTable.storeBatch(db, batch));
        db.rollback();

        assertEquals(0, count("SELECT COUNT(*) FROM product_performance_history_batch"));
        assertEquals(0, count("SELECT COUNT(*) FROM product_performance_history_observation"));
        db.setAutoCommit(true);
        assertThrows(SQLException.class, () -> ProductPerformanceHistoryTable.storeBatch(db, batch));
        assertThrows(SQLException.class, () -> ProductPerformanceHistoryTable.prepareSchema(db));
        db.setAutoCommit(false);
    }

    @Test
    void permitsRawOnlyArchiveBatchesWithoutFabricatingMappedMetrics() throws Exception {
        var stored = ProductPerformanceHistoryTable.storeBatch(db,
                new ImportBatch(hash('d'), "{\"unmapped\":[1,2,3]}", List.of()));

        assertTrue(stored.inserted());
        assertEquals(0, stored.observationCount());
        assertEquals(1, count("SELECT COUNT(*) FROM product_performance_history_batch"));
        assertTrue(ProductPerformanceHistoryTable.getProductPerformanceHistory(db, firstVendor, "P1").isEmpty());
    }

    @Test
    void returnsDistinctAccountReportDatesWithinInclusiveBoundsWithoutFillingMissingDays() throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO ads_campaign VALUES (?, ?, ?)")) {
            for (int index = 0; index < 5; index++) {
                statement.setObject(1, index == 4 ? secondVendor : firstVendor);
                statement.setObject(2, index < 2 ? DAY : index == 2 ? DAY.plusDays(2)
                        : index == 3 ? DAY.minusDays(1) : DAY.plusDays(1));
                statement.setInt(3, index);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        assertEquals(Set.of(DAY, DAY.plusDays(2)),
                ProductPerformanceHistoryTable.getProductPerformanceAdsReportDates(db, firstVendor, DAY, DAY.plusDays(2)));
        assertEquals(Set.of(DAY.plusDays(1)),
                ProductPerformanceHistoryTable.getProductPerformanceAdsReportDates(db, secondVendor, DAY, DAY.plusDays(2)));
        assertThrows(IllegalArgumentException.class, () ->
                ProductPerformanceHistoryTable.getProductPerformanceAdsReportDates(db, firstVendor, DAY, DAY.minusDays(1)));
    }

    @Test
    void migrationIsIdempotentAndRefusesUnrelatedDatabaseVersions() throws Exception {
        assertFalse(ProductPerformanceHistoryTable.prepareSchema(db));
        assertEquals(1, count("SELECT COUNT(*) FROM version_info WHERE version = 49"));
        execute("DELETE FROM version_info");
        execute("INSERT INTO version_info VALUES (47, CURRENT_TIMESTAMP)");
        assertThrows(SQLException.class, () -> ProductPerformanceHistoryTable.prepareSchema(db));
        assertEquals(47, count("SELECT MAX(version) FROM version_info"));
    }

    private Observation observation(UUID vendor, String product, LocalDate date, String domain,
                                    Map<String, Object> values, String spreadsheet) {
        return new Observation(vendor, product, "PNK-" + product, date, domain, values,
                spreadsheet, 12, "Product's history", "A7:CE7");
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
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
}
