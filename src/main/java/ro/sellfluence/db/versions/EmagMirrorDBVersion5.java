package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion5 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version5(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE request_history (
                    id INT,
                    req_user VARCHAR(255),
                    action VARCHAR(255),
                    action_type VARCHAR(255),
                    source VARCHAR(255),
                    PRIMARY KEY (id)
                );
                """);
    }
}