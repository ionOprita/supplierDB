package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion14 {

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version14(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE flag
                ADD PRIMARY KEY (emag_order_surrogate_id, flag);
                """);
    }
}