package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion25 {
    /**
     * Cache the tab within the employee sheet.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version25(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE tasks (
                    name VARCHAR(255) PRIMARY KEY,
                    started TIMESTAMP,
                    terminated TIMESTAMP,
                    duration_of_last_run INTERVAL,
                    error VARCHAR(65535)
                );
                """);
    }
}