package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the shared SQL only against an explicitly supplied disposable PostgreSQL database. */
class OrderQuantityTableIntegrationTest {
    private static final UUID FIRST_VENDOR = new UUID(0, 1);
    private static final UUID SECOND_VENDOR = new UUID(0, 2);
    private static final String PNK = "SELECTED";
    private static final String LEGACY_MONTH_SQL = """
            WITH picked AS (
              SELECT DISTINCT ON (o.id, pio.part_number_key)
                     CASE WHEN o.status = 4 THEN pio.quantity ELSE pio.initial_qty END AS picked_qty,
                     pio.part_number_key AS pnk
              FROM emag_order o
              JOIN product_in_order pio ON o.surrogate_id = pio.emag_order_surrogate_id
              WHERE o.status IN (4,5) AND o.date >= ? AND o.date < ?
              ORDER BY o.id, pio.part_number_key, o.status, o.surrogate_id DESC
            )
            SELECT SUM(picked_qty) AS quantity, pnk FROM picked GROUP BY pnk
            """;
    private static final String LEGACY_MONTHS_SQL = """
            WITH picked AS (
              SELECT DISTINCT ON (o.id, pio.part_number_key, DATE_TRUNC('month', o.date)::date)
                     CASE WHEN o.status = 4 THEN pio.quantity ELSE pio.initial_qty END AS picked_qty,
                     pio.part_number_key AS pnk, DATE_TRUNC('month', o.date)::date AS month_start
              FROM emag_order o
              JOIN product_in_order pio ON o.surrogate_id = pio.emag_order_surrogate_id
              WHERE o.status IN (4,5) AND o.date >= ? AND o.date < ?
              ORDER BY o.id, pio.part_number_key, DATE_TRUNC('month', o.date)::date,
                       o.status, o.surrogate_id DESC
            )
            SELECT pnk, month_start, SUM(picked_qty) AS quantity
            FROM picked GROUP BY pnk, month_start
            """;

    private Connection db;
    private String schema;
    private int nextSnapshot = 1;
    private int nextItem = 1;

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
        schema = "order_quantities_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("""
                CREATE TABLE emag_order (
                    surrogate_id INTEGER PRIMARY KEY, id TEXT, vendor_id UUID,
                    status INTEGER, date TIMESTAMP, modified TIMESTAMP,
                    UNIQUE (vendor_id, id, status)
                )
                """);
        execute("""
                CREATE TABLE product_in_order (
                    id INTEGER PRIMARY KEY, emag_order_surrogate_id INTEGER,
                    part_number_key TEXT, quantity INTEGER, initial_qty INTEGER
                )
                """);
        // No product catalog exists: selection must retain unknown PNKs and avoid catalog/vendor filtering.
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
    void preservesBothLegacyMonthlyQueriesIncludingCrossVendorDedupAndUncataloguedPnks() throws SQLException {
        snapshot("paired", FIRST_VENDOR, 4, "2026-10-10T12:00:00", PNK, 4, 4);
        snapshot("paired", FIRST_VENDOR, 5, "2026-10-20T12:00:00", PNK, 1, 4);
        snapshot("cross-month", FIRST_VENDOR, 4, "2026-10-31T23:00:00", PNK, 6, 6);
        snapshot("cross-month", FIRST_VENDOR, 5, "2026-11-02T12:00:00", PNK, 2, 6);
        snapshot("returned-only", FIRST_VENDOR, 5, "2026-10-15T12:00:00", PNK, 1, 5);
        snapshot("status-precedence", FIRST_VENDOR, 4, "2026-10-15T12:00:00", PNK, 7, 7);
        snapshot("status-precedence", SECOND_VENDOR, 5, "2026-10-15T12:00:00", PNK, 1, 30);
        snapshot("surrogate-precedence", FIRST_VENDOR, 4, "2026-10-15T12:00:00", PNK, 3, 3);
        snapshot("surrogate-precedence", SECOND_VENDOR, 4, "2026-10-15T12:00:00", PNK, 9, 9);
        snapshot("negative", FIRST_VENDOR, 4, "2026-10-15T12:00:00", PNK, -2, 20);
        snapshot("zero", FIRST_VENDOR, 4, "2026-10-15T12:00:00", PNK, 0, 20);
        snapshot("zero", FIRST_VENDOR, 5, "2026-10-20T12:00:00", PNK, 0, 20);
        snapshot("null-quantity", FIRST_VENDOR, 4, "2026-10-15T12:00:00", "NULL", null, 20);
        snapshot("unknown", SECOND_VENDOR, 4, "2026-10-15T12:00:00", "UNKNOWN", 11, 11);
        for (int status = 0; status < 4; status++) {
            snapshot("ignored-" + status, FIRST_VENDOR, status, "2026-10-15T12:00:00", PNK, 100, 100);
        }

        var october = YearMonth.of(2026, 10);
        assertEquals(Map.of(PNK, 29L, "NULL", 0L, "UNKNOWN", 11L), monthly(OrderQuantityTable.MONTH_SQL, october));
        for (var month : List.of(october, october.plusMonths(1), october.plusMonths(2))) {
            assertEquals(monthly(LEGACY_MONTH_SQL, month), monthly(OrderQuantityTable.MONTH_SQL, month));
        }
        assertEquals(months(LEGACY_MONTHS_SQL, october, october.plusMonths(3)),
                months(OrderQuantityTable.MONTHS_SQL, october, october.plusMonths(3)));
        assertEquals(Map.of(PNK, Map.of(day("2026-10-01"), 29L, day("2026-11-01"), 6L),
                        "NULL", Map.of(day("2026-10-01"), 0L), "UNKNOWN", Map.of(day("2026-10-01"), 11L)),
                months(OrderQuantityTable.MONTHS_SQL, october, october.plusMonths(3)));
    }

    @Test
    void selectsSnapshotsIndependentlyInsideEachWeekOrMonthRatherThanSummingDailyCounts() throws SQLException {
        snapshot("spanning", FIRST_VENDOR, 4, "2026-10-31T12:00:00", PNK, 4, 4);
        snapshot("spanning", FIRST_VENDOR, 5, "2026-11-02T12:00:00", PNK, 1, 4);
        var periods = Map.of(
                day("2026-10-26"), day("2026-11-01"),
                day("2026-11-02"), day("2026-11-08"),
                day("2026-10-01"), day("2026-10-31"),
                day("2026-11-01"), day("2026-11-30"),
                day("2026-10-30"), day("2026-11-03")
        );

        assertEquals(Map.of(day("2026-10-26"), 4L, day("2026-11-02"), 4L,
                        day("2026-10-01"), 4L, day("2026-11-01"), 4L, day("2026-10-30"), 4L),
                OrderQuantityTable.countForProductPeriods(db, PNK, periods));
    }

    @Test
    void usesInclusivePeriodBoundariesAndReturnsZeroForEmptyOrNullQuantities() throws SQLException {
        snapshot("before", FIRST_VENDOR, 4, "2026-11-01T23:59:59", PNK, 100, 100);
        snapshot("start", FIRST_VENDOR, 4, "2026-11-02T00:00:00", PNK, 2, 2);
        var end = snapshot("end", FIRST_VENDOR, 4, "2026-11-08T23:59:59", PNK, 3, 3);
        item(end, "OTHER", 100, 100);
        snapshot("after", FIRST_VENDOR, 4, "2026-11-09T00:00:00", PNK, 100, 100);
        snapshot("null", FIRST_VENDOR, 5, "2026-11-10T12:00:00", PNK, 7, null);

        assertEquals(Map.of(day("2026-11-02"), 5L, day("2026-11-10"), 0L, day("2026-12-01"), 0L),
                OrderQuantityTable.countForProductPeriods(db, PNK, Map.of(
                        day("2026-11-02"), day("2026-11-08"),
                        day("2026-11-10"), day("2026-11-10"),
                        day("2026-12-01"), day("2026-12-31"))));
        assertEquals(Map.of(day("2026-11-02"), 0L), OrderQuantityTable.countForProductPeriods(db,
                "MISSING", Map.of(day("2026-11-02"), day("2026-11-08"))));
    }

    @Test
    void keepsLongTotalsAndSignedQuantities() throws SQLException {
        snapshot("large-1", FIRST_VENDOR, 4, "2026-11-02T12:00:00", PNK, Integer.MAX_VALUE, 1);
        snapshot("large-2", FIRST_VENDOR, 4, "2026-11-02T12:00:00", PNK, Integer.MAX_VALUE, 1);
        snapshot("negative", FIRST_VENDOR, 4, "2026-11-03T12:00:00", PNK, -7, 1);

        assertEquals(Map.of(day("2026-11-02"), 4294967287L, day("2026-11-03"), -7L),
                OrderQuantityTable.countForProductPeriods(db, PNK, Map.of(
                        day("2026-11-02"), day("2026-11-08"), day("2026-11-03"), day("2026-11-03"))));
    }

    @Test
    void validatesPeriodsAndDoesNotRequireAConnectionForAnEmptyRequest() throws SQLException {
        assertTrue(OrderQuantityTable.countForProductPeriods(null, PNK, Map.of()).isEmpty());
        assertThrows(NullPointerException.class,
                () -> OrderQuantityTable.validatePeriods(null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> OrderQuantityTable.validatePeriods(PNK, null));
        var nullStart = new HashMap<LocalDate, LocalDate>();
        nullStart.put(null, day("2026-11-02"));
        assertThrows(NullPointerException.class,
                () -> OrderQuantityTable.validatePeriods(PNK, nullStart));
        var nullEnd = new HashMap<LocalDate, LocalDate>();
        nullEnd.put(day("2026-11-02"), null);
        assertThrows(NullPointerException.class,
                () -> OrderQuantityTable.validatePeriods(PNK, nullEnd));
        assertThrows(IllegalArgumentException.class, () -> OrderQuantityTable.validatePeriods(PNK,
                Map.of(day("2026-11-03"), day("2026-11-02"))));
    }

    private Map<String, Long> monthly(String sql, YearMonth month) throws SQLException {
        var result = new HashMap<String, Long>();
        try (var statement = db.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(month.atDay(1).atStartOfDay()));
            statement.setTimestamp(2, Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString("pnk"), rows.getLong("quantity"));
            }
        }
        return result;
    }

    private Map<String, Map<LocalDate, Long>> months(String sql, YearMonth start, YearMonth end) throws SQLException {
        var result = new HashMap<String, Map<LocalDate, Long>>();
        try (var statement = db.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(start.atDay(1).atStartOfDay()));
            statement.setTimestamp(2, Timestamp.valueOf(end.atDay(1).atStartOfDay()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.computeIfAbsent(rows.getString("pnk"), ignored -> new HashMap<>())
                        .put(rows.getDate("month_start").toLocalDate(), rows.getLong("quantity"));
            }
        }
        return result;
    }

    private int snapshot(String id, UUID vendor, int status, String date,
                         String pnk, Integer quantity, Integer initial) throws SQLException {
        var surrogate = nextSnapshot++;
        try (var statement = db.prepareStatement("INSERT INTO emag_order VALUES (?, ?, ?, ?, ?, NULL)")) {
            statement.setInt(1, surrogate);
            statement.setString(2, id);
            statement.setObject(3, vendor);
            statement.setInt(4, status);
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.parse(date)));
            statement.executeUpdate();
        }
        item(surrogate, pnk, quantity, initial);
        return surrogate;
    }

    private void item(int surrogate, String pnk, Integer quantity, Integer initial) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO product_in_order VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, nextItem++);
            statement.setInt(2, surrogate);
            statement.setString(3, pnk);
            statement.setObject(4, quantity);
            statement.setObject(5, initial);
            statement.executeUpdate();
        }
    }

    private static LocalDate day(String value) {
        return LocalDate.parse(value);
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
