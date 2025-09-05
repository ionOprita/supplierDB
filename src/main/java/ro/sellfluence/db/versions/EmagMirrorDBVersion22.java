package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion22 {
    /**
     * Change the product in oder table to include surrogate_id in the primary key.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version22(Connection db) throws SQLException {
        fixProductInOrderPK(db);
        fixVoucherPK(db);
        fixSomeKeys(db);
    }

    private static void fixProductInOrderPK(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE voucher_split DROP CONSTRAINT voucher_split_product_id_fkey;
                """);
        executeStatement(db, """
                ALTER TABLE product_in_order DROP CONSTRAINT product_in_order_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE product_in_order ADD PRIMARY KEY (id, emag_order_surrogate_id);
                """);
        // The following statement needs to wait, until the voucher_split table was updated.
//        executeStatement(db, """
//                ALTER TABLE voucher_split ADD CONSTRAINT voucher_split_product_id_fkey FOREIGN KEY (product_id, emag_order_surrogate_id) REFERENCES product_in_order(id, emag_order_surrogate_id);
//                """);
    }

    private static void fixVoucherPK(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE voucher DROP CONSTRAINT voucher_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE voucher ADD PRIMARY KEY (voucher_id, emag_order_surrogate_id);
                """);
    }

    /**
     * Add missing foreign key constraints and primary keys.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    private static void fixSomeKeys(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE emag_order ADD FOREIGN KEY (vendor_id) REFERENCES vendor(id);
                """);
        executeStatement(db, """
                ALTER TABLE gmv ADD PRIMARY KEY (month, product_code);
                """);
    }
}