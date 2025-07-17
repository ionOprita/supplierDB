package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion20 {
    /**
     * Change the product table to depend on the product code as identification.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version20(Connection db) throws SQLException {
        fixProductInOrderPK(db);
        fixVoucherPK(db);
        fixVoucherSplitPK(db);
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
        executeStatement(db, """
                ALTER TABLE voucher_split ADD CONSTRAINT voucher_split_product_id_fkey FOREIGN KEY (product_id, emag_order_surrogate_id) REFERENCES product_in_order(id, emag_order_surrogate_id);
                """);
    }

    private static void fixVoucherPK(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE voucher DROP CONSTRAINT voucher_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE voucher ADD PRIMARY KEY (voucher_id, emag_order_surrogate_id);
                """);
    }

    private static void fixVoucherSplitPK(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE voucher_split DROP CONSTRAINT voucher_split_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE voucher_split ADD PRIMARY KEY (voucher_id, emag_order_surrogate_id);
                """);
    }
}