package ro.sellfluence.db;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;

import static java.math.RoundingMode.HALF_EVEN;
import static ro.sellfluence.support.UsefulMethods.toDate;
import static ro.sellfluence.support.UsefulMethods.toLocalDate;

/**
 * Information regarding a product in an order.
 *
 * @param orderId eMag order ID.
 * @param surrogateId internal ID of order.
 * @param orderDate date the order was placed.
 * @param orderStatus of the order.
 * @param productInOrderId id given by eMag to this product within the order.
 * @param productName name of the product.
 * @param quantity actual quantity sold.
 * @param initialQuantity quantity initially ordered.
 * @param stornoQuantity quantity cancelled/returned.
 * @param price price including VAT.
 */
public record POInfo(String orderId, int surrogateId, LocalDate orderDate, int orderStatus, int productInOrderId,
                     String productName,
                     int quantity,
                     int initialQuantity, int stornoQuantity,
                     BigDecimal price) {

    /**
     * Retrieves a list of POInfo objects for a specific product and month. The method queries the database to find
     * all product orders that match the specified product code within the given month and have a status of 4 or 5.
     *
     * @param db          the database connection to be used for executing the query
     * @param productCode the unique code of the product for which the data is being retrieved.
     * @param yearMonth   the month for which the data is being queried.
     * @return a list of POInfo objects containing information about the product orders for the specified product and month.
     * @throws SQLException if a database access error occurs or the SQL query execution fails.
     */
    static @NotNull ArrayList<POInfo> getByProductAndMonth(Connection db, String productCode, YearMonth yearMonth) throws SQLException {
        var result = new ArrayList<POInfo>();
        try (var s = db.prepareStatement("""
                SELECT p.name AS productName,
                pio.quantity AS quantity,
                pio.initial_qty AS initialQuantity,
                pio.storno_qty AS stornoQuantity,
                pio.sale_price AS salePrice,
                pio.vat AS vat,
                pio.created, pio.modified,
                o.status AS orderStatus,
                o.date AS orderDate,
                o.modified,
                o.id AS orderId,
                o.surrogate_id AS surrogateId,
                pio.id AS pioId
                FROM product_in_order AS pio
                INNER JOIN product AS p ON p.emag_pnk = pio.part_number_key
                INNER JOIN emag_order AS o ON pio.emag_order_surrogate_id = o.surrogate_id
                WHERE p.product_code = ? AND (o.status = 4 OR o.status = 5) AND o.date >= ? AND o.date < ?
                ORDER BY o.id, o.status
                """)) {
            s.setObject(1, productCode);
            s.setDate(2, toDate(yearMonth));
            s.setDate(3, toDate(yearMonth.plusMonths(1)));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var orderId = rs.getString("orderId");
                    var quantity = rs.getInt("quantity");
                    var initialQuantity = rs.getInt("initialQuantity");
                    var stornoQuantity = rs.getInt("stornoQuantity");
                    var salePrice = rs.getBigDecimal("salePrice");
                    var vat = new BigDecimal(rs.getString("vat"));
                    var price = vat.add(BigDecimal.ONE).multiply(salePrice).setScale(2, HALF_EVEN);
                    var poInfo = new POInfo(
                            orderId,
                            rs.getInt("surrogateId"),
                            toLocalDate(rs.getTimestamp("orderDate")),
                            rs.getInt("orderStatus"),
                            rs.getInt("pioId"),
                            rs.getString("productName"),
                            quantity,
                            initialQuantity,
                            stornoQuantity,
                            price
                    );
                    result.add(poInfo);
                }

            }
        }
        return result;
    }
}