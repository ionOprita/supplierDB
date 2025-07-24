package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion21 {
    /**
     * Change the product table to depend on the product code as identification.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version21(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE voucher_split ADD COLUMN voucher_name character varying(255);
                """);
        executeStatement(db, """
                ALTER TABLE order_voucher_split ADD COLUMN voucher_name character varying(255);
                """);
    }
}