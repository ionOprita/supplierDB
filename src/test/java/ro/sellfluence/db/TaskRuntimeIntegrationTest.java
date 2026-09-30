package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only the explicitly configured disposable PostgreSQL database. */
class TaskRuntimeIntegrationTest {
    @Test
    void keepsLastRuntimeDuringANewRunAndReportsElapsedSeconds() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);

        try (var db = DriverManager.getConnection(url, properties)) {
            db.setAutoCommit(false);
            var schema = "task_runtime_test_" + UUID.randomUUID().toString().replace("-", "");
            try {
                try (var statement = db.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                    statement.execute("SET search_path TO " + schema);
                    statement.execute("""
                            CREATE TABLE tasks (
                                name TEXT PRIMARY KEY,
                                started TIMESTAMP,
                                terminated TIMESTAMP,
                                last_successful_run TIMESTAMP,
                                last_successful_start TIMESTAMP,
                                duration_of_last_run INTERVAL,
                                unsuccessful_runs INTEGER,
                                error TEXT
                            )
                            """);
                    statement.execute("""
                            INSERT INTO tasks (name, duration_of_last_run, terminated)
                            VALUES ('previous', INTERVAL '2 minutes', LOCALTIMESTAMP)
                            """);
                }

                Task.registerTasks(db, List.of("new"));
                var neverRun = Task.getAllTasks(db).stream()
                        .filter(task -> task.name().equals("new")).findFirst().orElseThrow();
                assertNull(neverRun.durationOfLastRun());
                assertNull(neverRun.currentRunSeconds());
                assertNull(neverRun.lastSuccessfulStart());

                Task.startTask(db, "previous");
                var running = Task.getAllTasks(db).stream()
                        .filter(task -> task.name().equals("previous")).findFirst().orElseThrow();
                assertEquals(Duration.ofMinutes(2), running.durationOfLastRun());
                assertNull(running.terminated());
                assertNotNull(running.currentRunSeconds());
                assertTrue(running.currentRunSeconds() >= 0);

                try (var statement = db.createStatement()) {
                    statement.execute("""
                            UPDATE tasks SET started = clock_timestamp()::timestamp - INTERVAL '90 seconds'
                            WHERE name = 'previous'
                            """);
                }
                running = Task.getAllTasks(db).stream()
                        .filter(task -> task.name().equals("previous")).findFirst().orElseThrow();
                assertTrue(running.currentRunSeconds() >= 90);

                Task.endTask(db, "previous", "");
                var completed = Task.getAllTasks(db).stream()
                        .filter(task -> task.name().equals("previous")).findFirst().orElseThrow();
                assertNull(completed.currentRunSeconds());
                assertNotNull(completed.durationOfLastRun());
                assertEquals(completed.started(), completed.lastSuccessfulStart());

                Task.startTask(db, "previous");
                Task.endTask(db, "previous", "failed");
                var failed = Task.getAllTasks(db).stream()
                        .filter(task -> task.name().equals("previous")).findFirst().orElseThrow();
                assertEquals(completed.lastSuccessfulRun(), failed.lastSuccessfulRun());
                assertEquals(completed.lastSuccessfulStart(), failed.lastSuccessfulStart());
            } finally {
                db.rollback();
            }
        }
    }
}
