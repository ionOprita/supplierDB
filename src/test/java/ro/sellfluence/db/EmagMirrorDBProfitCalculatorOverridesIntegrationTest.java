package ro.sellfluence.db;

import ch.claudio.db.DB;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.api.ProfitCalculatorOverrides.Request;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideConflictException;
import ro.sellfluence.db.versions.EmagMirrorDBVersion50;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the real API, mirror, and pooled transaction wrapper using a disposable schema. */
class EmagMirrorDBProfitCalculatorOverridesIntegrationTest {
    private static final List<String> PRODUCT_CODES = List.of("S. 51", "S. 53", "S. 55");
    private String url;
    private String schema;
    private final Properties connectionProperties = new Properties();
    private HikariDataSource pool;
    private API api;

    @BeforeEach
    void createCommittedFixtureAndRealTransactionWrapper() throws SQLException {
        url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) connectionProperties.setProperty("user", user);
        if (password != null) connectionProperties.setProperty("password", password);
        schema = "profit_save_wrapper_test_" + UUID.randomUUID().toString().replace("-", "");

        try (var db = DriverManager.getConnection(url, connectionProperties)) {
            db.setAutoCommit(false);
            try (var statement = db.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE product (product_code TEXT PRIMARY KEY)");
            }
            try (var statement = db.prepareStatement("INSERT INTO product (product_code) VALUES (?)")) {
                for (var code : PRODUCT_CODES) {
                    statement.setString(1, code);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            EmagMirrorDBVersion50.version50(db);
            // The pool must see the fixture from independent connections.
            db.commit();
        }

        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDataSourceProperties(connectionProperties);
        // Set schema during connection startup, before DB.writeTX sets transaction isolation.
        config.addDataSourceProperty("options", "-c search_path=" + schema);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        pool = new HikariDataSource(config);
        api = new API(new EmagMirrorDB(new DB(pool)));
    }

    @AfterEach
    void closePoolBeforeDroppingFixture() throws SQLException {
        if (pool != null) pool.close();
        if (schema != null) {
            try (var db = DriverManager.getConnection(url, connectionProperties);
                 var statement = db.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void savesThreeFourteenPercentCommissionsThroughTheRealWriteTransaction() throws SQLException {
        api.saveProfitCalculatorOverrides(request(PRODUCT_CODES.stream()
                .map(code -> change(code, 0, "0.14"))
                .toList()));

        assertCommittedCommissions("0.14", 1);
    }

    @Test
    void staleRevisionRollsBackEarlierWritesInTheSameRealTransaction() throws SQLException {
        api.saveProfitCalculatorOverrides(request(PRODUCT_CODES.stream()
                .map(code -> change(code, 0, "0.14"))
                .toList()));

        assertThrows(OverrideConflictException.class, () -> api.saveProfitCalculatorOverrides(request(List.of(
                change("S. 51", 1, "0.15"),
                change("S. 53", 0, "0.16"),
                change("S. 55", 1, "0.17")
        ))));

        assertCommittedCommissions("0.14", 1);
    }

    private void assertCommittedCommissions(String rate, long revision) throws SQLException {
        // A fresh, non-pooled connection proves commit visibility, not just in-transaction writes.
        try (var db = DriverManager.getConnection(url, connectionProperties)) {
            db.setSchema(schema);
            try (var statement = db.prepareStatement("""
                    SELECT product_code, commission_rate, revision
                    FROM profit_calculator_override ORDER BY product_code
                    """);
                 var rows = statement.executeQuery()) {
                var index = 0;
                while (rows.next()) {
                    assertEquals(PRODUCT_CODES.get(index++), rows.getString("product_code"));
                    assertEquals(0, new BigDecimal(rate).compareTo(rows.getBigDecimal("commission_rate")));
                    assertEquals(revision, rows.getLong("revision"));
                }
                assertEquals(PRODUCT_CODES.size(), index);
            }
        }
    }

    private static Request request(List<OverrideChange> changes) {
        return new Request("S", "Trimmers", changes);
    }

    private static OverrideChange change(String code, long expectedRevision, String rate) {
        return new OverrideChange(code, expectedRevision, Map.of("commissionRate", rate));
    }
}
