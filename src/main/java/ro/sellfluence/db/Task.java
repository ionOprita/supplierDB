package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record Task(String name, LocalDateTime started, LocalDateTime terminated, Duration durationOfLastRun, String error) {

    /**
     * Record the start of a task by inserting into the tasks table the name and current timestamp.
     *
     * @param db   the database connection used to execute the SQL statement.
     * @param name the name of the task.
     * @throws SQLException if a database access error occurs or the SQL statement fails.
     */
    public static void startTask(Connection db, String name) throws SQLException {
        String sql = """
                INSERT INTO tasks (name, started)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (name)
                DO UPDATE SET started = CURRENT_TIMESTAMP
                """;

        try (var s = db.prepareStatement(sql)) {
            s.setString(1, name);
            s.execute();
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
    public static void endTask(Connection db, String name, String error) throws SQLException {
        try (var s = db.prepareStatement("""
                UPDATE tasks
                SET terminated = CURRENT_TIMESTAMP,
                    duration_of_last_run = CURRENT_TIMESTAMP - started,
                    error = ?
                WHERE name = ?
                """)) {
            s.setString(1, error);
            s.setString(2, name);
            s.execute();
        }
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
                WHERE name = ?
                  AND (terminated IS NULL OR started > terminated)
                """)) {
            s.setString(1, name);
            try (var rs = s.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
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

        try (var s = db.prepareStatement("SELECT name, started, terminated, duration_of_last_run, error FROM tasks");
             var rs = s.executeQuery()) {
            List<Task> tasks = new ArrayList<>();
            while (rs.next()) {
                tasks.add(
                        new Task(
                                rs.getString("name"),
                                rs.getObject("started", LocalDateTime.class),
                                rs.getObject("terminated", LocalDateTime.class),
                                rs.getObject("duration_of_last_run", Duration.class),
                                rs.getString("error")
                        )
                );
            }
            return tasks;
        }
    }
}
