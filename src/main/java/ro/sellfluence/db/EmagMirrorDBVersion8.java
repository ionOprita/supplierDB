package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion8 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version8(Connection db) throws SQLException {
        executeStatement(db, """
                DELETE FROM product;
                """);
        executeStatement(db, """
                ALTER TABLE product ADD COLUMN product_code VARCHAR(255);
                """);
    }
}