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
        var voucherSplitCount = countAllInTable(db, "voucher_split");
        var nullVoucherSplits = singleIntQuery(db, "SELECT COUNT(*) FROM voucher_split WHERE product_id IS NULL");
        createNewTable(db);
        moveEntriesWithoutProductId(db);
        makeNewPrimaryKey(db);
        var voucherSplitNewCount = countAllInTable(db, "voucher_split");
        var orderVoucherSplitCount = countAllInTable(db, "order_voucher_split");
        if (orderVoucherSplitCount != nullVoucherSplits) {
            throw new RuntimeException(
                    "The new order related table has %d rows while the old table had %d rows with a product_id NULL."
                            .formatted(orderVoucherSplitCount, nullVoucherSplits)
            );
        }
        if (voucherSplitNewCount != voucherSplitCount - nullVoucherSplits) {
            throw new RuntimeException(
                    "The old table has now %d rows while the old table had %d - %d = %d rows with a product_id that is not NULL."
                            .formatted(voucherSplitNewCount, voucherSplitCount, nullVoucherSplits, voucherSplitCount-nullVoucherSplits)
            );
        }
    }

    private static int countAllInTable(Connection db, final String table) throws SQLException {
        return singleIntQuery(db, "SELECT COUNT(*) FROM " + table);
    }

    private static int singleIntQuery(Connection db, final String query) throws SQLException {
        int count;
        try (var s = db.prepareStatement(query)) {
            try (var rs = s.executeQuery()) {
                if (!rs.next()) throw new RuntimeException("Missing count");
                count=rs.getInt(1);
                if (rs.next()) throw new RuntimeException("Unexpected second value");
            }
        }
        return count;
    }

    private static void createNewTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE order_voucher_split (
                                    voucher_id integer NOT NULL,
                                    value numeric(19,4),
                                    vat_value numeric(19,4),
                                    vat character varying(255),
                                    offered_by character varying(255),
                                    emag_order_surrogate_id integer NOT NULL,
                                    CONSTRAINT order_voucher_split_pkey PRIMARY KEY (voucher_id, emag_order_surrogate_id),
                                    CONSTRAINT order_voucher_split_emag_order_surrogate_id_fkey FOREIGN KEY (emag_order_surrogate_id)
                                        REFERENCES public.emag_order(surrogate_id)
                                );
                """);
    }

    private static void moveEntriesWithoutProductId(Connection db) throws SQLException {
        executeStatement(db, """
                INSERT INTO order_voucher_split (
                                               voucher_id, value, vat_value, vat, offered_by, emag_order_surrogate_id
                                           )
                                           SELECT voucher_id, value, vat_value, vat, offered_by, emag_order_surrogate_id
                                           FROM voucher_split
                                           WHERE product_id IS NULL;
                """);
        executeStatement(db, """
                DELETE FROM voucher_split WHERE product_id IS NULL;
                """);
    }

    private static void makeNewPrimaryKey(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE voucher_split DROP CONSTRAINT voucher_split_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE voucher_split ADD CONSTRAINT voucher_split_pkey PRIMARY KEY (voucher_id, product_id, emag_order_surrogate_id);
                """);
    }
}