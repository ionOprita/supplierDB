package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only against an explicitly configured disposable PostgreSQL database. */
class TaskHistoryIntegrationTest {
    private static final UUID FIRST_VENDOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_VENDOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void keepsFailuresAfterSuccessAndMarksOnlyUnfinishedAttemptsInterrupted() throws Exception {
        withDatabase(db -> {
            assertEquals(1, Task.startTask(db, "import"));
            assertEquals(1, Task.endTask(db, "import", "HTTP 503"));
            assertEquals(1, Task.startTask(db, "import"));
            assertEquals(1, Task.endTask(db, "import", ""));
            Task.startTask(db, "crashed");
            assertEquals(1, Task.resetState(db));
            assertEquals(0, Task.resetState(db));

            var history = TaskHistory.readHistory(db, null, null, null, null, 1, 50);
            assertEquals(List.of("INTERRUPTED", "SUCCESS", "FAILED"),
                    history.items().stream().map(TaskHistory.Attempt::status).toList());
            assertEquals("HTTP 503", history.items().getLast().error());
            assertTrue(history.items().stream().allMatch(item -> item.finishedAt() != null));
            var latest = Task.getAllTasks(db).stream().filter(task -> task.name().equals("import")).findFirst().orElseThrow();
            assertEquals("", latest.error());
            assertEquals(0, latest.unsuccessfulRuns());
            assertNotNull(latest.lastSuccessfulStart());
            assertEquals(0, Task.endTask(db, "unknown", "not started"));
        });
    }

    @Test
    void startingAgainPreservesAnUnfinishedAttemptInsteadOfOverwritingIt() throws Exception {
        withDatabase(db -> {
            Task.startTask(db, "import");
            Task.startTask(db, "import");
            var history = TaskHistory.readHistory(db, "import", null, null, null, 1, 50);
            assertEquals(List.of("RUNNING", "INTERRUPTED"),
                    history.items().stream().map(TaskHistory.Attempt::status).toList());
            assertNull(history.items().getFirst().finishedAt());
            Task.endTask(db, "import", "");
            assertEquals(1, TaskHistory.readHistory(db, "import", "SUCCESS", null, null, 1, 50).total());
        });
    }

    @Test
    void failureToWriteHistoryAlsoRollsBackTheLatestTaskUpdate() throws Exception {
        withDatabase(db -> {
            try (var statement = db.createStatement()) {
                statement.execute("ALTER TABLE task_runs ADD CONSTRAINT reject_test CHECK (task_name <> 'blocked')");
            }
            var savepoint = db.setSavepoint();
            assertThrows(SQLException.class, () -> Task.startTask(db, "blocked"));
            db.rollback(savepoint);
            assertTrue(Task.getAllTasks(db).isEmpty());
            assertEquals(0, TaskHistory.readHistory(db, null, null, null, null, 1, 50).total());
        });
    }

    @Test
    void filtersWholeBucharestDaysAcrossDstAndPaginatesStably() throws Exception {
        withDatabase(db -> {
            attempt(db, "same", "2026-10-24T20:59:59Z", "FAILED", "previous day");
            attempt(db, "same", "2026-10-24T21:00:00Z", "SUCCESS", null);
            attempt(db, "same", "2026-10-25T21:59:59Z", "FAILED", "last second");
            attempt(db, "same", "2026-10-25T22:00:00Z", "FAILED", "next day");
            attempt(db, "same extra", "2026-10-25T12:00:00Z", "FAILED", "other task");
            var date = LocalDate.of(2026, 10, 25);
            var first = TaskHistory.readHistory(db, "same", null, date, date, 1, 1);
            assertEquals(2, first.total());
            assertEquals("last second", first.items().getFirst().error());
            assertEquals("SUCCESS", TaskHistory.readHistory(db, "same", null, date, date, 2, 1)
                    .items().getFirst().status());
            var beyond = TaskHistory.readHistory(db, "same", null, date, date, 3, 1);
            assertTrue(beyond.items().isEmpty());
            assertEquals(2, beyond.total());
            assertEquals(1, TaskHistory.readHistory(db, "same", "FAILED", date, date, 1, 50).total());
        });
    }

    @Test
    void registersOnlyUnambiguousAccountsAndPreservesTheInitialEnableDate() throws Exception {
        withDatabase(db -> {
            vendors(db);
            var names = List.of("unrelated", "Fetch offers for missing", "Fetch offers for alpha", "Fetch offers for alpha");
            assertEquals(1, TaskHistory.registerOfferSources(db, names));
            try (var statement = db.createStatement()) {
                statement.execute("UPDATE offer_collection_sources SET enabled_on = DATE '2026-11-01'");
                statement.execute("INSERT INTO vendor VALUES ('00000000-0000-0000-0000-000000000003', 'Duplicate', 'beta')");
            }
            assertEquals(0, TaskHistory.registerOfferSources(db, names));
            assertEquals(0, TaskHistory.registerOfferSources(db, List.of("Fetch offers for beta")));
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM offer_collection_sources")) {
                assertTrue(rows.next());
                assertEquals(FIRST_VENDOR, rows.getObject("vendor_id", UUID.class));
                assertEquals(LocalDate.of(2026, 11, 1), rows.getObject("enabled_on", LocalDate.class));
                assertFalse(rows.next());
            }
        });
    }

    @Test
    void coverageShowsOfflineDaysAndRetainsSuccessAfterLaterFailure() throws Exception {
        withDatabase(db -> {
            vendors(db);
            TaskHistory.registerOfferSources(db, List.of("Fetch offers for alpha", "Fetch offers for beta"));
            try (var statement = db.createStatement()) {
                statement.execute("UPDATE offer_collection_sources SET enabled_on = DATE '2026-11-02' WHERE task_name = 'Fetch offers for alpha'");
                statement.execute("UPDATE offer_collection_sources SET enabled_on = DATE '2026-11-05' WHERE task_name = 'Fetch offers for beta'");
                statement.execute("""
                        INSERT INTO offers_snapshot VALUES
                          ('00000000-0000-0000-0000-000000000001', '2026-11-02', '2026-11-02T08:00:00Z'),
                          ('00000000-0000-0000-0000-000000000001', '2026-11-03', NULL)
                        """);
            }
            attempt(db, "Fetch offers for alpha", "2026-11-02T08:00:00Z", "SUCCESS", null);
            attempt(db, "Fetch offers for alpha", "2026-11-02T09:00:00Z", "FAILED", "later failure");
            attempt(db, "Fetch offers for alpha", "2026-11-04T07:00:00Z", "FAILED", "unavailable");
            // UTC midnight belongs to the same day in the Bucharest coverage report.
            attempt(db, "Fetch offers for alpha", "2026-11-04T23:00:00Z", "FAILED", "retry later");
            var today = LocalDate.of(2026, 11, 5);
            var days = TaskHistory.readCollectionDays(db, null, today.minusDays(6), today.plusDays(2), today);
            assertEquals(5, days.size());
            var alpha = days.stream().filter(day -> day.vendorId().equals(FIRST_VENDOR)).toList();
            assertEquals(List.of("RETRYING", "MISSING", "MISSING", "COLLECTED"),
                    alpha.stream().map(TaskHistory.CollectionDay::status).toList());
            assertEquals(List.of(1, 1, 0, 2), alpha.stream().map(TaskHistory.CollectionDay::attempts).toList());
            assertEquals("later failure", alpha.getLast().lastError());
            assertNotNull(alpha.getLast().fetchedAt());
            assertEquals("PENDING", days.stream().filter(day -> day.vendorId().equals(SECOND_VENDOR))
                    .findFirst().orElseThrow().status());
            assertEquals(4, TaskHistory.readCollectionDays(db, "Fetch offers for alpha", null, null, today).size());
            assertTrue(TaskHistory.readCollectionDays(db, null, today.minusDays(10), today.minusDays(5), today).isEmpty());
        });
    }

    private static void vendors(Connection db) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    INSERT INTO vendor VALUES
                        ('00000000-0000-0000-0000-000000000001', 'Alpha', 'alpha'),
                        ('00000000-0000-0000-0000-000000000002', 'Beta', 'beta')
                    """);
        }
    }

    private static void attempt(Connection db, String task, String time, String status, String error) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO task_runs(task_name, started_at, finished_at, status, error) VALUES (?, ?, ?, ?, ?)
                """)) {
            var at = OffsetDateTime.parse(time);
            statement.setString(1, task);
            statement.setObject(2, at);
            statement.setObject(3, status.equals("RUNNING") ? null : at.plusSeconds(10));
            statement.setString(4, status);
            statement.setString(5, error);
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface DatabaseTest { void run(Connection db) throws Exception; }

    private static void withDatabase(DatabaseTest test) throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        try (var db = DriverManager.getConnection(url, properties)) {
            db.setAutoCommit(false);
            try {
                var schema = "task_history_test_" + UUID.randomUUID().toString().replace("-", "");
                try (var statement = db.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                    statement.execute("SET search_path TO " + schema);
                    statement.execute("SET TIME ZONE 'UTC'");
                    statement.execute("CREATE TABLE vendor(id UUID PRIMARY KEY, vendor_name TEXT, account TEXT)");
                    statement.execute("""
                            CREATE TABLE tasks(name TEXT PRIMARY KEY, started TIMESTAMP, terminated TIMESTAMP,
                                last_successful_run TIMESTAMP, last_successful_start TIMESTAMP,
                                duration_of_last_run INTERVAL, unsuccessful_runs INTEGER, error TEXT)
                            """);
                    statement.execute("""
                            CREATE TABLE offers_snapshot(vendor_id UUID, fetch_date DATE, visits_fetched_at TIMESTAMPTZ,
                                PRIMARY KEY(vendor_id, fetch_date))
                            """);
                }
                var migration = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion47")
                        .getDeclaredMethod("version47", Connection.class);
                migration.setAccessible(true);
                migration.invoke(null, db);
                test.run(db);
            } finally {
                db.rollback();
            }
        }
    }
}
