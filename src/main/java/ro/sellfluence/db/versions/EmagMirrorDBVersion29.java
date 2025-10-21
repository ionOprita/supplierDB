package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion29 {
    /**
     * Record last time of successful runs plus count of unsuccessful runs.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version29(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE tasks ADD COLUMN last_successful_run TIMESTAMP;
                """);
        executeStatement(db, """
                ALTER TABLE tasks ADD COLUMN unsuccessful_runs INTEGER;
                """);
    }
}