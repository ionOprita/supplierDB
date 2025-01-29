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
        createCancellationReasonTable(db);
        alterEmagOrderTable(db);
    }

    private static void createCancellationReasonTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE cancellation_reason (
                    id INTEGER,
                    name VARCHAR(255),
                    PRIMARY KEY (id),
                );
                """);
    }
    private static void alterEmagOrderTable(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE emag_order
                  ADD FOREIGN KEY (cancellation_reason)
                  REFERENCES cancellation_reason (id);
                """);
    }

    private static void executeStatement(Connection db, String createStatement) throws SQLException {
        try (var s = db.prepareStatement(createStatement)) {
            s.execute();
        }
    }
}
