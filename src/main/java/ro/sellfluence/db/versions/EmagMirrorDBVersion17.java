package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion17 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version17(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product ALTER COLUMN name SET NOT NULL;
                """);
        executeStatement(db, """
                ALTER TABLE product ADD CONSTRAINT unique_name UNIQUE (name);
                """);
        executeStatement(db, """
                ALTER TABLE product ADD COLUMN employee_sheet_name VARCHAR(255);
                """);
    }
}