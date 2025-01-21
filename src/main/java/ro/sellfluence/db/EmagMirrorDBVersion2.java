package ro.sellfluence.db;

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
    }

    private static void alterAttachmentTable(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE attachment DROP CONSTRAINT attachment_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE attachment ADD PRIMARY KEY (order_id, vendor_id, url);
                """);
        executeStatement(db, """
                ALTER TABLE flag DROP CONSTRAINT flag_order_id_vendor_id_fkey;
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
/*
SELECT order_id, vendor_id, flag, COUNT(*)
FROM flag
GROUP BY order_id, vendor_id, flag
HAVING COUNT(*) > 1;

I have this table

                CREATE TABLE flag(
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    flag VARCHAR(255),
                    value VARCHAR(255),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                );

In this table I need to add a PRIMARY KEY (order_id, vendor_id, flag) but there are already duplicates.
Knowing how the entries where created I am pretty sure, that duplicates have the same content in the value field.
Can you help me with these two issues:

1. Is there an SQL statement with which I can verify that any duplicates having the same content in order_id,
vendor_id an flag will also have the same content in the value field?

2. How can I delete all duplicates, i.e. make sure only one of multiple entries remains in the database?

 */