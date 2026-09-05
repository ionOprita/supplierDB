package ro.sellfluence.db.versions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Run against a disposable PostgreSQL database using ADS_TEST_DB_URL and optional USER/PASSWORD variables. */
class EmagMirrorDBVersion39Test {
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("ads_campaign", "report_date, campaign_id, name",
                    "DATE '2026-08-31', 101, 'Original campaign'"),
            new Fixture("ads_adset", "report_date, campaign_id, adset_id, name",
                    "DATE '2026-08-31', 101, 202, 'Original adset'"),
            new Fixture("ads_keyword", "report_date, campaign_id, adset_id, keyword_id, keyword",
                    "DATE '2026-08-31', 101, 202, 303, 'Original keyword'"),
            new Fixture("ads_search_phrase",
                    "report_date, campaign_id, adset_id, search_phrase, search_phrase_hash, is_aggregated",
                    "DATE '2026-08-31', 101, 202, 'Original phrase', repeat('a', 64), false"),
            new Fixture("ads_targeted_product", "report_date, campaign_id, adset_id, doc_id, product_name",
                    "DATE '2026-08-31', 101, 202, 404, 'Original product'")
    );

    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedVersion38Schema() throws SQLException {
        String url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        addEnvironmentProperty(properties, "user", "ADS_TEST_DB_USER");
        addEnvironmentProperty(properties, "password", "ADS_TEST_DB_PASSWORD");
        db = DriverManager.getConnection(url, properties);
        schema = "ads_v39_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_name TEXT, account VARCHAR(255))");
        EmagMirrorDBVersion36.version36(db);
        EmagMirrorDBVersion37.version37(db);
        EmagMirrorDBVersion38.version38(db);
        db.setAutoCommit(false);
    }

    @AfterEach
    void removeIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
                connection.setSchema("public");
                execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"22b69642-3ad1-4b49-a748-4472ab786bce", "96b7dd68-802c-4c91-a957-ff003c455df7"})
    void backfillsEveryTableUsingTheLocalAccountIdAndPreservesData(String localId) throws SQLException {
        UUID vendorId = UUID.fromString(localId);
        insertVendor(vendorId, "sellfusion", "Name unrelated to account");
        insertVendor(UUID.randomUUID(), "another-account", "sellfusion");
        insertFixtures(null);
        long phraseId = count("SELECT id FROM ads_search_phrase");

        EmagMirrorDBVersion39.version39(db);

        for (Fixture fixture : FIXTURES) {
            try (var statement = db.prepareStatement("SELECT vendor_id FROM " + fixture.table());
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next(), fixture.table());
                assertEquals(vendorId, rows.getObject(1, UUID.class), fixture.table());
                assertFalse(rows.next(), fixture.table());
            }
        }
        assertEquals(phraseId, count("SELECT id FROM ads_search_phrase"));
        assertEquals(1, count("SELECT count(*) FROM ads_campaign WHERE name = 'Original campaign'"));
        assertEquals(1, count("SELECT count(*) FROM ads_adset WHERE name = 'Original adset'"));
        assertEquals(1, count("SELECT count(*) FROM ads_keyword WHERE keyword = 'Original keyword'"));
        assertEquals(1, count("SELECT count(*) FROM ads_search_phrase WHERE search_phrase = 'Original phrase'"));
        assertEquals(1, count("SELECT count(*) FROM ads_targeted_product WHERE product_name = 'Original product'"));
        assertEquals(0, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = 'vendor_id'
                    AND (is_nullable <> 'NO' OR column_default IS NOT NULL)
                """));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void missingOrAmbiguousAccountRollsBackPopulatedMigration(int matchingAccounts) throws SQLException {
        for (int i = 0; i < matchingAccounts; i++) {
            insertVendor(UUID.randomUUID(), "sellfusion", "Vendor " + i);
        }
        insertFixtures(null);
        db.commit();

        SQLException error = assertThrows(SQLException.class, () -> EmagMirrorDBVersion39.version39(db));

        assertTrue(error.getMessage().contains("sellfusion"));
        assertTrue(error.getMessage().contains(matchingAccounts == 0 ? "no vendor" : "multiple vendors"));
        db.rollback();
        assertEquals(0, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = 'vendor_id'
                """));
        for (Fixture fixture : FIXTURES) {
            assertEquals(1, count("SELECT count(*) FROM " + fixture.table()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void emptyTablesDoNotRequireAnUnambiguousSellfusionAccount(int matchingAccounts) throws SQLException {
        for (int i = 0; i < matchingAccounts; i++) {
            insertVendor(UUID.randomUUID(), "sellfusion", "Vendor " + i);
        }

        EmagMirrorDBVersion39.version39(db);

        UUID newVendor = UUID.randomUUID();
        insertVendor(newVendor, "new-account", "New vendor");
        insertFixtures(newVendor);
        for (Fixture fixture : FIXTURES) {
            assertEquals(1, count("SELECT count(*) FROM " + fixture.table()));
        }
    }

    @Test
    void identicalIdsAreIndependentAcrossVendorsAndUniqueWithinEachVendor() throws SQLException {
        UUID firstVendor = UUID.randomUUID();
        UUID secondVendor = UUID.randomUUID();
        insertVendor(firstVendor, "sellfusion", "First vendor");
        insertVendor(secondVendor, "second-account", "Second vendor");
        insertFixtures(null);
        EmagMirrorDBVersion39.version39(db);

        insertFixtures(secondVendor);

        for (Fixture fixture : FIXTURES) {
            assertEquals(2, count("SELECT count(*) FROM " + fixture.table()), fixture.table());
            assertRejected("23505", () -> insertFixture(fixture, firstVendor));
        }
        assertEquals(2, count("SELECT count(DISTINCT id) FROM ads_search_phrase"));

        try (var statement = db.prepareStatement("DELETE FROM ads_campaign WHERE vendor_id = ?")) {
            statement.setObject(1, firstVendor);
            assertEquals(1, statement.executeUpdate());
        }
        for (Fixture fixture : FIXTURES) {
            try (var statement = db.prepareStatement("SELECT vendor_id FROM " + fixture.table());
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next(), fixture.table());
                assertEquals(secondVendor, rows.getObject(1, UUID.class), fixture.table());
                assertFalse(rows.next(), fixture.table());
            }
        }
    }

    @Test
    void everyTableRequiresAValidVendorAndVendorDeletionDoesNotCascade() throws SQLException {
        UUID vendorId = UUID.randomUUID();
        insertVendor(vendorId, "sellfusion", "First vendor");
        insertFixtures(null);
        EmagMirrorDBVersion39.version39(db);

        for (Fixture fixture : FIXTURES) {
            assertRejected("23502", () -> execute("UPDATE " + fixture.table() + " SET vendor_id = NULL"));
            assertRejected("23503", () -> {
                try (var statement = db.prepareStatement("UPDATE " + fixture.table() + " SET vendor_id = ?")) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.executeUpdate();
                }
            });
        }
        assertRejected("23503", () -> execute("DELETE FROM vendor"));
        assertEquals(1, count("SELECT count(*) FROM vendor"));
    }

    @Test
    void childrenCannotUseAnotherVendorsMatchingCampaignOrAdset() throws SQLException {
        UUID firstVendor = UUID.randomUUID();
        UUID secondVendor = UUID.randomUUID();
        insertVendor(firstVendor, "sellfusion", "First vendor");
        insertVendor(secondVendor, "second-account", "Second vendor");
        insertFixtures(null);
        EmagMirrorDBVersion39.version39(db);

        for (Fixture fixture : FIXTURES.subList(1, FIXTURES.size())) {
            assertRejected("23503", () -> insertFixture(fixture, secondVendor));
        }
    }

    @Test
    void reportingAndDirectIdIndexesStartWithVendor() throws SQLException {
        EmagMirrorDBVersion39.version39(db);

        var expectedColumns = Map.of(
                "ads_campaign_campaign_id_idx", "vendor_id, campaign_id",
                "ads_adset_adset_id_idx", "vendor_id, adset_id",
                "ads_campaign_campaign_id_report_date_idx", "vendor_id, campaign_id, report_date",
                "ads_adset_campaign_id_report_date_idx", "vendor_id, campaign_id, report_date, adset_id",
                "ads_keyword_campaign_adset_report_date_idx", "vendor_id, campaign_id, adset_id, report_date",
                "ads_search_phrase_campaign_adset_report_date_idx", "vendor_id, campaign_id, adset_id, report_date",
                "ads_targeted_product_campaign_adset_report_date_idx", "vendor_id, campaign_id, adset_id, report_date"
        );
        try (var statement = db.prepareStatement("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?
                """)) {
            for (var entry : expectedColumns.entrySet()) {
                statement.setString(1, entry.getKey());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next(), entry.getKey());
                    assertTrue(rows.getString(1).contains("(" + entry.getValue() + ")"), rows.getString(1));
                }
            }
        }
        assertEquals(3, count("""
                SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()
                    AND indexname IN ('ads_targeted_product_pnk_idx', 'ads_targeted_product_brand_id_idx',
                                      'ads_targeted_product_category_id_idx')
                """));
    }

    private static void addEnvironmentProperty(Properties properties, String property, String environment) {
        String value = System.getenv(environment);
        if (value != null) {
            properties.setProperty(property, value);
        }
    }

    private void insertVendor(UUID id, String account, String name) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO vendor (id, account, vendor_name) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, account);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private void insertFixtures(UUID vendorId) throws SQLException {
        for (Fixture fixture : FIXTURES) {
            insertFixture(fixture, vendorId);
        }
    }

    private void insertFixture(Fixture fixture, UUID vendorId) throws SQLException {
        String columns = (vendorId == null ? "" : "vendor_id, ") + fixture.columns();
        String values = (vendorId == null ? "" : "?, ") + fixture.values();
        try (var statement = db.prepareStatement("INSERT INTO " + fixture.table()
                + " (" + columns + ") VALUES (" + values + ")")) {
            if (vendorId != null) {
                statement.setObject(1, vendorId);
            }
            statement.executeUpdate();
        }
    }

    private void assertRejected(String sqlState, SqlAction action) throws SQLException {
        var savepoint = db.setSavepoint();
        SQLException error = assertThrows(SQLException.class, action::run);
        assertEquals(sqlState, error.getSQLState());
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

    private record Fixture(String table, String columns, String values) {}

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
