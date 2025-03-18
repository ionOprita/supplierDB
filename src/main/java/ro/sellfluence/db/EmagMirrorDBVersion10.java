package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static ro.sellfluence.db.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion10 {

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version10(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE vendor ADD COLUMN last_fetch TIMESTAMP;
                """);
    }
}