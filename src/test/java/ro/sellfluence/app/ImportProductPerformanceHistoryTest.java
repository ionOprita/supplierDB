package ro.sellfluence.app;

import ch.claudio.db.DBPass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Result;
import ro.sellfluence.db.ProductPerformanceHistoryTable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImportProductPerformanceHistoryTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void localHostsRemainAllowedWithoutRemoteOptIn() {
        for (var host : List.of("localhost", "127.0.0.1", "[::1]")) {
            var target = configuration(host);
            assertDoesNotThrow(() -> ImportProductPerformanceHistory.validateWriteTarget(target, false), host);
            assertDoesNotThrow(() -> ImportProductPerformanceHistory.validateWriteTarget(target, true), host);
        }
    }

    @Test
    void remoteTargetRequiresExplicitOptInBeforeAnyConnectionIsOpened() {
        var target = configuration("production.example.invalid");

        assertThrows(IllegalArgumentException.class,
                () -> ImportProductPerformanceHistory.validateWriteTarget(target, false));
        assertDoesNotThrow(() -> ImportProductPerformanceHistory.validateWriteTarget(target, true));
    }

    @Test
    void aCompleteReviewedResultCanBeAppliedOnlyWithItsExactHash() {
        var complete = result(true);

        assertDoesNotThrow(() -> ImportProductPerformanceHistory.validateApply(complete, HASH));
        assertThrows(IllegalStateException.class,
                () -> ImportProductPerformanceHistory.validateApply(complete, "b".repeat(64)));
        assertThrows(IllegalStateException.class,
                () -> ImportProductPerformanceHistory.validateApply(complete, " " + HASH));
    }

    @Test
    void incompleteOrUnreviewedResultsCannotBeApplied() {
        assertThrows(IllegalStateException.class,
                () -> ImportProductPerformanceHistory.validateApply(result(false), HASH));
        assertThrows(IllegalStateException.class,
                () -> ImportProductPerformanceHistory.validateApply(result(true), null));
        for (var expected : List.of("", " ", "\t\n")) {
            assertThrows(IllegalStateException.class,
                    () -> ImportProductPerformanceHistory.validateApply(result(true), expected));
        }
    }

    private static Result result(boolean complete) {
        return new Result(List.of(), List.of(), "{}", HASH, complete);
    }

    private static DBPass configuration(String host) {
        return new DBPass("fixture", "jdbc:postgresql://" + host + ":5432/history_test", "fixture", "fixture");
    }

    /** These cases use only an explicitly configured disposable PostgreSQL database and a unique schema. */
    @Nested
    class ApplySchema {
        private Connection db;
        private String schema;

        @BeforeEach
        void createIsolatedSchema() throws Exception {
            var url = System.getenv("ADS_TEST_DB_URL");
            assumeTrue(url != null && !url.isBlank(),
                    "ADS_TEST_DB_URL must refer to a disposable PostgreSQL database");
            var properties = new Properties();
            var user = System.getenv("ADS_TEST_DB_USER");
            var password = System.getenv("ADS_TEST_DB_PASSWORD");
            if (user != null) properties.setProperty("user", user);
            if (password != null) properties.setProperty("password", password);
            db = DriverManager.getConnection(url, properties);
            db.setAutoCommit(false);
            schema = "history_apply_test_" + UUID.randomUUID().toString().replace("-", "");
            execute("CREATE SCHEMA " + schema);
            db.setSchema(schema);
            execute("CREATE TABLE version_info (version INTEGER PRIMARY KEY, date TIMESTAMPTZ)");
            execute("INSERT INTO version_info VALUES (48, CURRENT_TIMESTAMP)");
            execute("CREATE TABLE vendor (id UUID PRIMARY KEY)");
            execute("CREATE TABLE product (product_code TEXT PRIMARY KEY, vendor UUID REFERENCES vendor (id))");
            db.commit();
        }

        @AfterEach
        void dropIsolatedSchema() throws SQLException {
            if (db == null) return;
            try (var connection = db) {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setReadOnly(false);
                connection.setAutoCommit(true);
                connection.setSchema("public");
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }

        @Test
        void remoteVersion48IsRejectedWithoutChangingTheSchemaOrVersion() throws Exception {
            var objectsBefore = schemaObjects();

            assertThrows(SQLException.class, () -> ImportProductPerformanceHistory.prepareApplySchema(db, true));

            assertEquals(objectsBefore, schemaObjects());
            assertEquals(48, version());
            assertEquals(1, versionCount());
        }

        @Test
        void remoteVersion49IsAcceptedWithoutDdlEvenOnAReadOnlyTransaction() throws Exception {
            assertTrue(ProductPerformanceHistoryTable.prepareSchema(db));
            db.commit();
            db.setReadOnly(true);
            var objectsBefore = schemaObjects();

            assertFalse(ImportProductPerformanceHistory.prepareApplySchema(db, true));

            assertEquals(objectsBefore, schemaObjects());
            assertEquals(49, version());
            assertEquals(2, versionCount());
        }

        @Test
        void remoteFutureSchemaIsRejectedWithoutDdl() throws Exception {
            execute("INSERT INTO version_info VALUES (50, CURRENT_TIMESTAMP)");
            var objectsBefore = schemaObjects();

            assertThrows(SQLException.class, () -> ImportProductPerformanceHistory.prepareApplySchema(db, true));

            assertEquals(objectsBefore, schemaObjects());
            assertEquals(50, version());
            assertEquals(2, versionCount());
        }

        @Test
        void localApplyRetainsTheExistingTransactionalMigrationFrom48To49() throws Exception {
            assertTrue(ImportProductPerformanceHistory.prepareApplySchema(db, false));
            assertEquals(49, version());
            assertTrue(schemaObjects().contains("product_performance_history_batch:r"));
            assertTrue(schemaObjects().contains("product_performance_history_observation:r"));
            assertFalse(ImportProductPerformanceHistory.prepareApplySchema(db, false));

            db.rollback();

            assertEquals(48, version());
            assertFalse(schemaObjects().contains("product_performance_history_batch:r"));
            assertFalse(schemaObjects().contains("product_performance_history_observation:r"));
        }

        @Test
        void explicitTransactionIsRequiredForLocalAndRemoteApply() throws Exception {
            var objectsBefore = schemaObjects();
            db.commit();
            db.setAutoCommit(true);

            assertThrows(SQLException.class, () -> ImportProductPerformanceHistory.prepareApplySchema(db, true));
            assertThrows(SQLException.class, () -> ImportProductPerformanceHistory.prepareApplySchema(db, false));

            assertEquals(objectsBefore, schemaObjects());
            assertEquals(48, version());
        }

        private List<String> schemaObjects() throws SQLException {
            var result = new ArrayList<String>();
            try (var statement = db.createStatement(); var rows = statement.executeQuery("""
                    SELECT c.relname, c.relkind
                    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema() ORDER BY c.relname, c.relkind
                    """)) {
                while (rows.next()) result.add(rows.getString(1) + ":" + rows.getString(2));
            }
            return result;
        }

        private int version() throws SQLException {
            return number("SELECT MAX(version) FROM version_info");
        }

        private int versionCount() throws SQLException {
            return number("SELECT COUNT(*) FROM version_info");
        }

        private int number(String sql) throws SQLException {
            try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }

        private void execute(String sql) throws SQLException {
            try (var statement = db.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
