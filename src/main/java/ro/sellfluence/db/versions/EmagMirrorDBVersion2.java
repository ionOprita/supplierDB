package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

class EmagMirrorDBVersion2 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version2(Connection db) throws SQLException {
        alterAttachmentTable(db);
        alterFlagTable(db);
    }

    private static void alterAttachmentTable(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE attachment DROP CONSTRAINT attachment_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE attachment ADD PRIMARY KEY (order_id, vendor_id, url);
                """);
    }

    private static void alterFlagTable(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE flag DROP CONSTRAINT flag_order_id_vendor_id_fkey;
                """);
        executeStatement(db, """
                CREATE TEMP TABLE flag_temp AS SELECT DISTINCT ON (order_id, vendor_id, flag) * FROM flag ORDER BY order_id, vendor_id, flag;
                """);
        executeStatement(db, """
                TRUNCATE flag;
                """);
        executeStatement(db, """
                INSERT INTO flag SELECT * FROM flag_temp;
                """);
        executeStatement(db, """
                DROP TABLE flag_temp;
                """);
        executeStatement(db, """
                ALTER TABLE flag ADD PRIMARY KEY (order_id, vendor_id, flag);
                """);
    }

    private static void executeStatement(Connection db, String createStatement) throws SQLException {
        try (var s = db.prepareStatement(createStatement)) {
            s.execute();
        }
    }
}