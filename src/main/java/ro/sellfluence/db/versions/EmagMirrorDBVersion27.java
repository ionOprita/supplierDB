package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion27 {
    /**
     * Cache the tab within the employee sheet.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version27(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product
                  ADD COLUMN vendor UUID;
                """);
    }
}