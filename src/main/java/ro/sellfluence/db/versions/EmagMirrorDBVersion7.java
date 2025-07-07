package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion7 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version7(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE request_history ADD COLUMN emag_id INT;
                """);
        executeStatement(db, """
                ALTER TABLE request_history ADD FOREIGN KEY (emag_id) REFERENCES rma_result(emag_id);
                """);
        executeStatement(db, """
                ALTER TABLE rma_result DROP COLUMN request_history;
                """);
    }
}