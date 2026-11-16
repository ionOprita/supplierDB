package ro.sellfluence.db;

import ch.claudio.db.DB;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.api.ProfitCalculatorFbeTariffs;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Change;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.ConflictException;
import ro.sellfluence.db.versions.EmagMirrorDBVersion51;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies commits and rollbacks through the real API, mirror, and pooled transaction wrapper. */
class EmagMirrorDBProfitCalculatorFbeTariffsIntegrationTest {
    private String url;
    private String schema;
    private final Properties properties = new Properties();
    private HikariDataSource pool;
    private API api;

    @BeforeEach
    void createCommittedSeedAndPooledApi() throws SQLException {
        url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        schema = "fbe_save_wrapper_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(url, properties)) {
            db.setAutoCommit(false);
            try (var statement = db.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET search_path TO " + schema);
            }
            EmagMirrorDBVersion51.version51(db);
            db.commit();
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDataSourceProperties(properties);
        config.addDataSourceProperty("options", "-c search_path=" + schema);
        config.setAutoCommit(false);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        pool = new HikariDataSource(config);
        api = new API(new EmagMirrorDB(new DB(pool)));
    }

    @AfterEach
    void closePoolAndDropDisposableSchema() throws SQLException {
        if (pool != null) pool.close();
        if (schema != null) {
            try (var db = DriverManager.getConnection(url, properties);
                 var statement = db.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void savesExactDecimalsAndNullableFieldsVisibleFromAnIndependentConnection() throws SQLException {
        var exact = "4.12345678901234567890123456789";
        var first = new LinkedHashMap<String, String>();
        first.put("orderProcessingFeeRon", exact);
        first.put("weightRangeKg", null);
        first.put("extraOrderFeeRon", null);
        api.saveProfitCalculatorFbeTariffs(new ProfitCalculatorFbeTariffs.Request(List.of(
                new Change(1, 1, first), new Change(2, 1, Map.of("returnProcessingFeeRon", "0")))));

        try (var db = DriverManager.getConnection(url, properties)) {
            db.setSchema(schema);
            try (var statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT * FROM profit_calculator_fbe_tariff WHERE id IN (1, 2) ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(0, new BigDecimal(exact).compareTo(rows.getBigDecimal("order_processing_fee_ron")));
                assertNull(rows.getBigDecimal("weight_range_kg"));
                assertNull(rows.getBigDecimal("extra_order_fee_ron"));
                assertEquals(2, rows.getLong("revision"));
                assertTrue(rows.next());
                assertEquals(0, BigDecimal.ZERO.compareTo(rows.getBigDecimal("return_processing_fee_ron")));
                assertEquals(2, rows.getLong("revision"));
            }
        }
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(api.getProfitCalculatorFbeTariffs())).get("tariffs");
        assertEquals(12, json.size());
        assertTrue(json.get(0).get("orderProcessingFeeRon").isTextual());
        assertEquals(exact, json.get(0).get("orderProcessingFeeRon").asString());
        assertTrue(json.get(0).get("weightRangeKg").isNull());
        assertEquals("0", json.get(1).get("returnProcessingFeeRon").asString());
    }

    @Test
    void staleSecondRowRollsBackTheFirstUpdateThroughTheRealWrapper() throws SQLException {
        assertThrows(ConflictException.class, () -> api.saveProfitCalculatorFbeTariffs(
                new ProfitCalculatorFbeTariffs.Request(List.of(
                        new Change(1, 1, Map.of("orderProcessingFeeRon", "99")),
                        new Change(2, 2, Map.of("orderProcessingFeeRon", "88"))))));

        try (var db = DriverManager.getConnection(url, properties)) {
            db.setSchema(schema);
            try (var statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT order_processing_fee_ron, revision FROM profit_calculator_fbe_tariff WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals(0, new BigDecimal("3.5").compareTo(rows.getBigDecimal("order_processing_fee_ron")));
                assertEquals(1, rows.getLong("revision"));
            }
        }
    }
}
