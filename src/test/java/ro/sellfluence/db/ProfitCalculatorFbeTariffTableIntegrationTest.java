package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Change;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.ConflictException;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;
import ro.sellfluence.db.versions.EmagMirrorDBVersion51;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only against an explicitly configured disposable database, using an isolated committed schema. */
class ProfitCalculatorFbeTariffTableIntegrationTest {
    private static final String EXTRA_LARGE = "Oversize Extra-Large / Supradimensionat extra-mare";
    private static final List<String> EDITABLE_FIELDS = List.of("sizeCategory", "maxLengthMm", "maxWidthMm",
            "maxHeightMm", "maxGirthMm", "weightBandKg", "orderProcessingFeeRon", "returnProcessingFeeRon",
            "removalFeeRon", "disposalFeeRon", "weightRangeKg", "extraOrderFeeRon", "extraReturnFeeRon",
            "extraRemovalFeeRon", "extraDisposalFeeRon");
    private Connection db;
    private String url;
    private String schema;
    private final Properties properties = new Properties();

    @BeforeEach
    void seedCommittedIsolatedSchema() throws SQLException {
        url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "fbe_tariff_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = db.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
        }
        EmagMirrorDBVersion51.version51(db);
        db.commit();
    }

    @AfterEach
    void removeIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                if (schema != null) {
                    try (var statement = db.createStatement()) {
                        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                    }
                }
            }
        }
    }

    @Test
    void migrationSeedsEveryOriginalValueAndRetainsRepeatedTiersAndNulls() throws SQLException {
        // Independent copy of the verified source snapshot, columns C:G, I:M and O:S, 2026-11-16.
        String[][] expected = {
                {"Packable Small / Ambalabil mic", "200", "150", "10", "520", "2", "3.5", "1.8", "2", "2", "5", "0.5", "0.3", "2", "2"},
                {"Packable Medium / Ambalabil mediu", "330", "230", "25", "840", "2", "4.2", "2.1", "2", "3", "5", "0.5", "0.3", "2", "2"},
                {"Packable Large / Ambalabil mare", "330", "230", "50", "890", "2", "4.6", "2.3", "2", "5", "10", "0.5", "0.3", "2", "2"},
                {"Shippable Small / Expediabil mic", "340", "170", "120", "920", "2", "5.5", "2.8", "3.2", "2.1", "10", "0.5", "0.3", "1", "1"},
                {"Shippable Medium / Expediabil mediu", "450", "340", "260", "1650", "2", "6.4", "3.2", "3.2", "2.4", "15", "0.5", "0.3", "1", "1"},
                {"Shippable Large / Expediabil mare", "610", "460", "460", "2450", "2", "7.5", "3.8", "3.2", "2.5", "40", "0.5", "0.3", "1", "1"},
                {"Shippable Atypical / Expediabil atipic", "1200", "600", "600", "3600", "2", "15", "7.5", "3.2", "2.3", "60", "1", "0.5", "1", "1"},
                {"Oversize / Supradimensionat", "1200", "800", "800", "4400", "5", "18", "9", "8.5", "2.3", "60", "1.5", "0.8", "1.5", "1.5"},
                {EXTRA_LARGE, "1860", "750", "630", "4620", "10", "30", "15", "12", "12", "60", "1.5", "0.8", "0.8", "0.8"},
                {EXTRA_LARGE, "1860", "750", "630", "4620", "60", "105", "52.5", "52", "52", "90", "1", "0.5", "0.8", "0.8"},
                {EXTRA_LARGE, "1860", "750", "630", "4620", "90", "135", "67.5", "76", "76", "300", "1", "0.5", "0.8", "0.8"},
                {EXTRA_LARGE, "1860", "750", "630", "4620", "300", "177", "88.5", "110", "110", null, null, null, null, null}
        };
        var rows = ProfitCalculatorFbeTariffTable.readTariffs(db);
        assertEquals(12, rows.size());
        for (int row = 0; row < expected.length; row++) {
            var tariff = rows.get(row);
            assertEquals(row + 1, tariff.id());
            assertEquals(1, tariff.revision());
            for (int column = 0; column < EDITABLE_FIELDS.size(); column++) {
                var key = EDITABLE_FIELDS.get(column);
                assertEquals(expected[row][column], tariff.jsonValues().get(key), "row " + tariff.id() + " " + key);
            }
        }
        assertEquals(4, rows.stream().filter(row -> row.sizeCategory().equals(EXTRA_LARGE)).count());
    }

    @Test
    void updatesAllFieldsExactlyAndPreservesOptionalNullsAcrossReads() throws SQLException {
        var expected = tariff(1).jsonValues();
        var changes = new LinkedHashMap<String, String>();
        changes.put("sizeCategory", " Updated size ");
        changes.put("maxLengthMm", "9007199254740993.1234567890123456789");
        changes.put("maxWidthMm", "333.3333");
        changes.put("maxHeightMm", "44.4444");
        changes.put("maxGirthMm", "9999.9999");
        changes.put("weightBandKg", "3.5555");
        changes.put("orderProcessingFeeRon", "0.000000000000000000001");
        changes.put("returnProcessingFeeRon", "0");
        changes.put("removalFeeRon", "4.5678");
        changes.put("disposalFeeRon", "5.6789");
        changes.put("weightRangeKg", null);
        changes.put("extraOrderFeeRon", "0");
        changes.put("extraReturnFeeRon", null);
        changes.put("extraRemovalFeeRon", "0.123456789123456789");
        changes.put("extraDisposalFeeRon", "");
        ProfitCalculatorFbeTariffTable.saveChanges(db, List.of(new Change(1, 1, changes)));
        db.commit();
        expected.putAll(changes);
        expected.put("sizeCategory", "Updated size");
        expected.put("extraDisposalFeeRon", null);
        expected.put("revision", 2L);
        try (var fresh = connection()) {
            assertEquals(expected, ProfitCalculatorFbeTariffTable.readTariffs(fresh).getFirst().jsonValues());
            assertEquals(1, ProfitCalculatorFbeTariffTable.readTariffs(fresh).get(1).revision());
        }
        assertNull(tariff(12).extraOrderFeeRon());
        assertNull(tariff(12).weightRangeKg());
    }

    @Test
    void staleLaterRowRollsBackTheWholeBatchButRetainsEarlierTransactionWork() throws SQLException {
        ProfitCalculatorFbeTariffTable.saveChanges(db, List.of(change(3, 1, "9")));
        var conflict = assertThrows(ConflictException.class, () -> ProfitCalculatorFbeTariffTable.saveChanges(db,
                List.of(change(2, 2, "6"), change(1, 1, "5"))));
        assertEquals(2, conflict.tariffId());
        assertEquals(new BigDecimal("3.5"), tariff(1).orderProcessingFeeRon());
        assertEquals(1, tariff(1).revision());
        assertEquals(new BigDecimal("4.2"), tariff(2).orderProcessingFeeRon());
        assertEquals(new BigDecimal("9"), tariff(3).orderProcessingFeeRon());
        assertEquals(2, tariff(3).revision());
    }

    @Test
    void concurrentStaleWriterWaitsForTheLockThenRejectsInsteadOfOverwriting() throws Exception {
        ProfitCalculatorFbeTariffTable.saveChanges(db, List.of(change(1, 1, "99")));
        var started = new CountDownLatch(1);
        try (var other = connection(); var executor = Executors.newSingleThreadExecutor()) {
            try {
                var future = executor.submit(() -> {
                    started.countDown();
                    try {
                        ProfitCalculatorFbeTariffTable.saveChanges(other, List.of(change(1, 1, "22")));
                        other.commit();
                        return (SQLException) null;
                    } catch (SQLException error) {
                        other.rollback();
                        return error;
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
                db.commit();
                var conflict = assertInstanceOf(ConflictException.class, future.get(10, TimeUnit.SECONDS));
                assertEquals(1, conflict.tariffId());
                assertEquals(new BigDecimal("99"), tariff(1).orderProcessingFeeRon());
                assertEquals(2, tariff(1).revision());
            } finally {
                db.rollback();
            }
        }
    }

    private Tariff tariff(int id) throws SQLException {
        return ProfitCalculatorFbeTariffTable.readTariffs(db).stream()
                .filter(row -> row.id() == id).findFirst().orElseThrow();
    }

    private Connection connection() throws SQLException {
        var connection = DriverManager.getConnection(url, properties);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private static Change change(int id, long revision, String value) {
        return new Change(id, revision, Map.of("orderProcessingFeeRon", value));
    }
}
