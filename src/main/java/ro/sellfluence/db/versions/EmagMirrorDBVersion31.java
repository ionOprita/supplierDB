package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion31 {
    /**
     * Precompute canonical orders and daily facts used by the cohort-based rolling return rate chart.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version31(Connection db) throws SQLException {
        createOrdersCanonical(db);
        createSalesDaily(db);
        createReturnsLinked(db);
    }

    private static void createOrdersCanonical(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE MATERIALIZED VIEW orders_canonical AS
                SELECT DISTINCT ON (eo.id)
                  eo.id           AS order_id,
                  eo.surrogate_id AS order_surrogate_id,
                  eo.date         AS order_ts,
                  eo.status       AS status
                FROM emag_order eo
                ORDER BY eo.id, eo.status DESC;
                """);
        executeStatement(db, """
                CREATE INDEX idx_orders_canonical_order_id
                    ON orders_canonical (order_id);
                """);
        executeStatement(db, """
                CREATE INDEX idx_orders_canonical_order_surrogate_id
                    ON orders_canonical (order_surrogate_id);
                """);
        executeStatement(db, """
                CREATE INDEX idx_orders_canonical_order_date
                    ON orders_canonical ((order_ts::date));
                """);
    }

    private static void createSalesDaily(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE MATERIALIZED VIEW sales_daily AS
                SELECT
                  pio.product_id,
                  (oc.order_ts::date) AS sale_d,
                  SUM(pio.quantity)::bigint AS sold_qty
                FROM product_in_order pio
                JOIN orders_canonical oc
                  ON oc.order_surrogate_id = pio.emag_order_surrogate_id
                WHERE oc.status IN (4,5)
                GROUP BY 1,2;
                """);
        executeStatement(db, """
                CREATE INDEX idx_sales_daily_product_date
                    ON sales_daily (product_id, sale_d);
                """);
    }

    private static void createReturnsLinked(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE MATERIALIZED VIEW returns_linked AS
                SELECT
                  erp.product_id,
                  (oc.order_ts::date) AS sale_d,
                  (rr.date::date)     AS return_d,
                  SUM(erp.quantity)::bigint AS returned_qty
                FROM emag_returned_products erp
                JOIN rma_result rr
                  ON rr.emag_id = erp.emag_id
                 AND rr.request_status = 7
                JOIN orders_canonical oc
                  ON oc.order_id = rr.order_id
                JOIN product_in_order pio
                  ON pio.emag_order_surrogate_id = oc.order_surrogate_id
                 AND pio.product_id = erp.product_id
                 AND pio.mkt_id = erp.product_emag_id
                GROUP BY 1,2,3;
                """);
        executeStatement(db, """
                CREATE INDEX idx_returns_linked_product_sale_return
                    ON returns_linked (product_id, sale_d, return_d);
                """);
    }
}
