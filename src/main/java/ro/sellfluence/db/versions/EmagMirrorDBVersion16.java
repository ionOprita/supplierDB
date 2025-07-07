package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion16 {

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version16(Connection db) throws SQLException {
        executeStatement(db, """
                DELETE FROM gmv;
                """);
        executeStatement(db, """
                DELETE FROM product;
                """);
        executeStatement(db, """
                ALTER TABLE product ADD COLUMN continue_to_sell BOOLEAN NOT NULL DEFAULT FALSE;
                """);
        executeStatement(db, """
                ALTER TABLE product ADD COLUMN retracted BOOLEAN NOT NULL DEFAULT FALSE;
                """);
    }
}