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
                   Duration durationOfLastRun, int unsuccessfulRuns, String error) {

    /**
     * Record the start of a task by inserting into the tasks table the name and current timestamp.
     *
     * @param db   the database connection used to execute the SQL statement.
     * @param name the name of the task.
     * @throws SQLException if a database access error occurs or the SQL statement fails.
     */
    public static int startTask(Connection db, String name) throws SQLException {
        String sql = """
                INSERT INTO tasks (name, started)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (name)
                DO UPDATE SET started = CURRENT_TIMESTAMP, terminated = NULL, duration_of_last_run = NULL
                """;

        try (var s = db.prepareStatement(sql)) {
            s.setString(1, name);
            return s.executeUpdate();
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
                WITH input AS (SELECT ?::text AS new_error)
                UPDATE tasks AS t
                SET terminated = CURRENT_TIMESTAMP,
                    duration_of_last_run = CURRENT_TIMESTAMP - t.started,
                    error = i.new_error,
                    last_successful_run = CASE
                      WHEN i.new_error IS NULL OR i.new_error = '' THEN CURRENT_TIMESTAMP
                      ELSE t.last_successful_run
                    END,
                    unsuccessful_runs = CASE
                      WHEN i.new_error IS NULL OR i.new_error = '' THEN 0
                      ELSE COALESCE(t.unsuccessful_runs, 0) + 1
                    END
                FROM input AS i
                WHERE t.name = ?
                """)) {
            s.setString(1, error);
            s.setString(2, name);
            return s.executeUpdate();
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

    public static int resetState(Connection db) throws SQLException {
        String sql = """
                UPDATE tasks SET started = NULL
                """;
        try (var s = db.prepareStatement(sql)) {
            return s.executeUpdate();
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
        try (var s = db.prepareStatement("SELECT name, started, terminated, last_successful_run, duration_of_last_run, unsuccessful_runs, error FROM tasks ORDER BY name");
             var rs = s.executeQuery()) {
            List<Task> tasks = new ArrayList<>();
            while (rs.next()) {
                tasks.add(
                        new Task(
                                rs.getString("name"),
                                rs.getObject("started", LocalDateTime.class),
                                rs.getObject("terminated", LocalDateTime.class),
                                rs.getObject("last_successful_run", LocalDateTime.class),
                                toDuration(rs.getObject("duration_of_last_run", PGInterval.class)),
                                rs.getInt("unsuccessful_runs"),
                                rs.getString("error")
                        )
                );
            }
            return tasks;
        }
    }
}