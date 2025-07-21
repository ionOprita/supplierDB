package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion21 {
    /**
     * Add missing foreign key constraints and primary keys.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version21(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE emag_order ADD FOREIGN KEY (vendor_id) REFERENCES vendor(id);
                """);
        executeStatement(db, """
                ALTER TABLE gmv ADD PRIMARY KEY (month, product_code);
                """);
    }
}