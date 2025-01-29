package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

class EmagMirrorDBVersion3 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version3(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_order
                    ADD COLUMN cancellation_reason_text VARCHAR(255);
                """)) {
            s.execute();
        }
    }
}