package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion26 {
    /**
     * Cache the tab within the employee sheet.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version26(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE rma_result
                  ALTER COLUMN order_id SET NOT NULL;
                """);
        executeStatement(db, """
                CREATE INDEX idx_emag_order_id_status_desc
                             ON emag_order (id, status DESC)
                             INCLUDE (surrogate_id, vendor_id)
                """);
        executeStatement(db, """
                CREATE INDEX idx_pio_order_surrogate_prod_mkt
                             ON product_in_order (emag_order_surrogate_id, product_id, mkt_id)
                             INCLUDE (part_number_key)
                """);
        executeStatement(db, """
                CREATE INDEX idx_rma_result_status_date
                             ON rma_result (request_status, date)
                             INCLUDE (order_id, emag_id)
                """);
        executeStatement(db, """
                CREATE INDEX idx_rp_emag_prod_mkt
                             ON emag_returned_products (emag_id, product_id, product_emag_id)
                """);
    }
}