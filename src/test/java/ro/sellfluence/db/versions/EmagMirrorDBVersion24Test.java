package ro.sellfluence.db.versions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and rolls back its isolated schema. */
class EmagMirrorDBVersion24Test {
    private Connection db;

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
        var schema = "version24_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
    }

    @AfterEach
    void rollBackIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                connection.rollback();
            }
        }
    }

    @Test
    void preservesThePrimaryKeyCreatedByDatabaseBootstrap() throws SQLException {
        createVersionTable("CONSTRAINT existing_version_info_pkey PRIMARY KEY");

        EmagMirrorDBVersion24.version24(db);

        assertMigrationResult("existing_version_info_pkey");
    }

    @Test
    void addsTheMissingPrimaryKeyForLegacyDatabases() throws SQLException {
        createVersionTable("");

        EmagMirrorDBVersion24.version24(db);

        assertMigrationResult("version_info_pkey");
    }

    private void createVersionTable(String primaryKey) throws SQLException {
        execute("CREATE TABLE version_info (version INTEGER " + primaryKey + ", date TIMESTAMPTZ)");
        execute("INSERT INTO version_info VALUES (23, TIMESTAMPTZ '2026-10-28 10:00:00+00')");
    }

    private void assertMigrationResult(String expectedPrimaryKey) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'version_info'::regclass AND contype = 'p'
                """); var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(expectedPrimaryKey, result.getString("conname"));
            assertFalse(result.next());
        }
        try (var statement = db.prepareStatement("SELECT version, date FROM version_info");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(23, result.getInt("version"));
            assertEquals(Instant.parse("2026-10-28T10:00:00Z"), result.getTimestamp("date").toInstant());
            assertFalse(result.next());
        }
        try (var statement = db.prepareStatement("SELECT to_regclass('storno')");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertNotNull(result.getString(1));
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
