package ro.sellfluence.db;

import org.postgresql.util.PGInterval;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static ro.sellfluence.support.UsefulMethods.toDuration;

public record Task(String name, LocalDateTime started, LocalDateTime terminated, LocalDateTime lastSuccessfulRun,
                   LocalDateTime lastSuccessfulStart, Duration durationOfLastRun, Long currentRunSeconds,
                   int unsuccessfulRuns, String error) {

    /**
     * Ensure every configured task has a history row, including tasks which have never run.
     *
     * @param db        database connection
     * @param taskNames configured task names
     * @return number of newly inserted rows
     * @throws SQLException if task registration fails
     */
    public static int registerTasks(Connection db, List<String> taskNames) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO tasks (name)
                VALUES (?)
                ON CONFLICT (name) DO NOTHING
                """)) {
            var inserted = 0;
            for (var taskName : taskNames) {
                s.setString(1, taskName);
                inserted += s.executeUpdate();
            }
            return inserted;
        }
    }

    /**
     * Record the start of a task by inserting into the tasks table the name and current timestamp.
     *
     * @param db   the database connection used to execute the SQL statement.
     * @param name the name of the task.
     * @throws SQLException if a database access error occurs or the SQL statement fails.
     */
    public static int startTask(Connection db, String name) throws SQLException {
        String sql = """
                WITH latest AS (
                    INSERT INTO tasks (name, started)
                    VALUES (?, CURRENT_TIMESTAMP)
                    ON CONFLICT (name)
                    DO UPDATE SET started = CURRENT_TIMESTAMP, terminated = NULL
                    RETURNING name
                ), interrupted AS (
                    UPDATE task_runs r SET status = 'INTERRUPTED', finished_at = CURRENT_TIMESTAMP,
                        error = 'A new attempt started before this attempt was completed.'
                    FROM latest WHERE r.task_name = latest.name AND r.status = 'RUNNING'
                    RETURNING r.id
                ), history AS (
                    INSERT INTO task_runs (task_name, started_at, status)
                    SELECT latest.name, CURRENT_TIMESTAMP, 'RUNNING'
                    FROM latest CROSS JOIN (SELECT COUNT(*) FROM interrupted) previous
                    RETURNING id
                )
                SELECT COUNT(*) FROM latest CROSS JOIN (SELECT COUNT(*) FROM history) recorded
                """;

        try (var s = db.prepareStatement(sql)) {
            s.setString(1, name);
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /**
     * Record the end of a task by inserting into the tasks table the time, run time, and error.
     *
     * @param db    the database connection used to execute the SQL statement.
     * @param name  the name of the task.
     * @param error the error message if the task failed.
     * @throws SQLException if a database access error occurs or the SQL statement fails.
     */
    public static int endTask(Connection db, String name, String error) throws SQLException {
        try (var s = db.prepareStatement("""
                WITH input AS (SELECT ?::text AS new_error), latest AS (
                UPDATE tasks AS t
                SET terminated = CURRENT_TIMESTAMP,
                    duration_of_last_run = CURRENT_TIMESTAMP - t.started,
                    error = i.new_error,
                    last_successful_run = CASE
                      WHEN i.new_error IS NULL OR i.new_error = '' THEN CURRENT_TIMESTAMP
                      ELSE t.last_successful_run
                    END,
                    last_successful_start = CASE
                      WHEN i.new_error IS NULL OR i.new_error = '' THEN t.started
                      ELSE t.last_successful_start
                    END,
                    unsuccessful_runs = CASE
                      WHEN i.new_error IS NULL OR i.new_error = '' THEN 0
                      ELSE COALESCE(t.unsuccessful_runs, 0) + 1
                    END
                FROM input AS i
                WHERE t.name = ?
                RETURNING t.name
                ), history AS (
                    UPDATE task_runs r
                    SET finished_at = CURRENT_TIMESTAMP,
                        status = CASE WHEN i.new_error IS NULL OR i.new_error = '' THEN 'SUCCESS' ELSE 'FAILED' END,
                        error = i.new_error
                    FROM latest, input i
                    WHERE r.task_name = latest.name AND r.status = 'RUNNING'
                    RETURNING r.id
                )
                SELECT COUNT(*) FROM latest CROSS JOIN (SELECT COUNT(*) FROM history) recorded
                """)) {
            s.setString(1, error);
            s.setString(2, name);
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public static int endTask(Connection db, String name, Throwable e) throws SQLException {
        return endTask(db, name, getStackTraceAsString(e));
    }

    /**
     * Checks if a task with the specified name is currently running.
     * A task is considered running if its `terminated` field is null
     * or the `started` field is later than the `terminated` field.
     *
     * @param db   the database connection used to execute the SQL statement.
     * @param name the name of the task to check.
     * @return true if the task is running, false otherwise.
     * @throws SQLException if a database access error occurs or the SQL statement fails.
     */
    public static boolean isRunning(Connection db, String name) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT COUNT(*)
                FROM tasks
                WHERE name = ? AND started IS NOT NULL AND terminated IS NULL
                """)) {
            s.setString(1, name);
            try (var rs = s.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** Clear interrupted attempts on startup while preserving completed attempts' scheduling history. */
    public static int resetState(Connection db) throws SQLException {
        String sql = """
                WITH interrupted AS (
                    UPDATE task_runs SET status = 'INTERRUPTED', finished_at = CURRENT_TIMESTAMP,
                        error = 'The server restarted before this attempt was completed.'
                    WHERE status = 'RUNNING'
                    RETURNING id
                ), reset AS (
                    UPDATE tasks SET started = NULL
                    WHERE started IS NOT NULL AND terminated IS NULL
                    RETURNING name
                )
                SELECT COUNT(*) FROM reset CROSS JOIN (SELECT COUNT(*) FROM interrupted) recorded
                """;
        try (var s = db.prepareStatement(sql)) {
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /**
     * Retrieves all tasks from the database.
     *
     * @param db the database connection used to retrieve the tasks.
     * @return a list of Task objects, where each Task represents a record from the tasks table.
     * @throws SQLException if a database access error occurs or the SQL statement fails.
     */
    public static List<Task> getAllTasks(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT name, started, terminated, last_successful_run, last_successful_start, duration_of_last_run,
                       CASE WHEN started IS NOT NULL AND terminated IS NULL
                            THEN GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (clock_timestamp()::timestamp - started))))::bigint
                            ELSE NULL
                       END AS current_run_seconds,
                       unsuccessful_runs, error
                FROM tasks ORDER BY name
                """);
             var rs = s.executeQuery()) {
            List<Task> tasks = new ArrayList<>();
            while (rs.next()) {
                tasks.add(
                        new Task(
                                rs.getString("name"),
                                rs.getObject("started", LocalDateTime.class),
                                rs.getObject("terminated", LocalDateTime.class),
                                rs.getObject("last_successful_run", LocalDateTime.class),
                                rs.getObject("last_successful_start", LocalDateTime.class),
                                toDuration((PGInterval) rs.getObject("duration_of_last_run")),
                                rs.getObject("current_run_seconds", Long.class),
                                rs.getInt("unsuccessful_runs"),
                                rs.getString("error")
                        )
                );
            }
            return tasks;
        }
    }
}
