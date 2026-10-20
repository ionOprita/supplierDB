package ro.sellfluence.db.versions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Task;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and rolls back its isolated schema. */
class EmagMirrorDBVersion46Test {
    private static final String TASK_NAME = "Fetch product reviews from eMAG";
    private Connection db;

    @BeforeEach
    void createPreviousTaskSchema() throws SQLException {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        var schema = "tasks_v46_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        EmagMirrorDBVersion25.version25(db);
        EmagMirrorDBVersion29.version29(db);
        execute("ALTER TABLE tasks ADD COLUMN last_successful_start TIMESTAMP");
        try (var statement = db.prepareStatement("""
                INSERT INTO tasks (name, started, terminated, duration_of_last_run, error,
                                   last_successful_run, last_successful_start, unsuccessful_runs)
                VALUES (?, TIMESTAMP '2026-10-18 19:01:00', TIMESTAMP '2026-10-18 19:06:00',
                        INTERVAL '5 minutes', 'Previous failure', TIMESTAMP '2026-10-17 19:35:00',
                        TIMESTAMP '2026-10-17 19:01:00', 2)
                """)) {
            statement.setString(1, TASK_NAME);
            statement.executeUpdate();
        }
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
    void widensTheErrorColumnWithoutChangingTaskHistory() throws SQLException {
        var previous = Task.getAllTasks(db);
        assertErrorColumnType("character varying", 65_535);

        EmagMirrorDBVersion46.version46(db);

        assertErrorColumnType("text", null);
        assertEquals(previous, Task.getAllTasks(db));
    }

    @Test
    void persistsAnOversizedAggregatedFailureAndEndsTheTask() throws SQLException {
        var previous = Task.getAllTasks(db).getFirst();
        var failure = aggregatedReviewFailure();
        var fullError = getStackTraceAsString(failure);
        assertTrue(fullError.length() > 65_535, "The regression fixture must exceed the old column limit");
        assertEquals(1, Task.startTask(db, TASK_NAME));

        var beforeFailure = db.setSavepoint();
        var rejected = assertThrows(SQLException.class, () -> Task.endTask(db, TASK_NAME, failure));
        assertEquals("22001", rejected.getSQLState(), "The old schema rejects an oversized task error");
        db.rollback(beforeFailure);
        assertTrue(Task.isRunning(db, TASK_NAME));

        EmagMirrorDBVersion46.version46(db);
        assertEquals(1, Task.endTask(db, TASK_NAME, failure));

        var completed = Task.getAllTasks(db).getFirst();
        assertEquals(fullError, completed.error());
        assertNotNull(completed.terminated());
        assertNotNull(completed.durationOfLastRun());
        assertNull(completed.currentRunSeconds());
        assertFalse(Task.isRunning(db, TASK_NAME));
        assertEquals(previous.unsuccessfulRuns() + 1, completed.unsuccessfulRuns());
        assertEquals(previous.lastSuccessfulRun(), completed.lastSuccessfulRun());
        assertEquals(previous.lastSuccessfulStart(), completed.lastSuccessfulStart());
    }

    private static IllegalStateException aggregatedReviewFailure() {
        var failure = new IllegalStateException("Review fetch failed for 159 of 159 products");
        for (int index = 0; index < 159; index++) {
            var cause = new IllegalStateException("eMAG returned HTTP 501\n" + "Response diagnostic details.\n".repeat(20));
            failure.addSuppressed(new IllegalStateException(
                    "Unable to fetch or store reviews for PNK-" + index, cause));
        }
        return failure;
    }

    private void assertErrorColumnType(String expectedType, Integer expectedLimit) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'tasks' AND column_name = 'error'
                """);
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(expectedType, result.getString("data_type"));
            assertEquals(expectedLimit, result.getObject("character_maximum_length", Integer.class));
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
