package ro.sellfluence.db;

import ro.sellfluence.support.Logs;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.math.RoundingMode.HALF_EVEN;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.support.UsefulMethods.toDate;
import static ro.sellfluence.support.UsefulMethods.toYearMonth;

/**
 * Collect all database methods tied to the GMV table.
 */
public class GMV {

    private static final Logger logger = Logs.getConsoleLogger("GMV", WARNING);

    /**
     * Compute the GMV for a specific product.
     *
     * @param db database for writing to the GMV table.
     * @param productCode ID of the product.
     * @param ordersWithProduct Flattened orders with the product information belonging to the selected product.
     * @throws SQLException on database error.
     */
    static void computeAndStoreGMVForProduct(Connection db, String productCode, Map<String, List<POInfo>> ordersWithProduct) throws SQLException {
        var gmvByMonth = new HashMap<YearMonth, BigDecimal>();
        ordersWithProduct.forEach((_, poInfos) -> computeGMVForOrder(poInfos, gmvByMonth));
        insertOrUpdateGMV(db, productCode, gmvByMonth);
    }

    /**
     * Return GMV values of all products for a given month.
     *
     * @param db database connection.
     * @param month for which to retrieve the GMV values.
     * @return map, which associates each product with its GMV value.
     * @throws SQLException on database error.
     */
    static Map<String, BigDecimal> getGMVByMonth(Connection db, YearMonth month) throws SQLException {
        var result = new HashMap<String, BigDecimal>();
        try (var s = db.prepareStatement("SELECT name, gmv FROM gmv INNER JOIN product ON gmv.product_code=product.product_code WHERE month = ? ORDER BY name")) {
            s.setDate(1, toDate(month));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getBigDecimal(2));
                }
            }
        }
        return result;
    }

    /**
     * Return GMV values of all months for a given product.
     *
     * @param db database connection.
     * @param productCode for which to retrieve the GMV values.
     * @return map, which associates each month with its GMV value.
     * @throws SQLException on database error.
     */
    static Map<YearMonth, BigDecimal> getGMVByProductId(Connection db, String productCode) throws SQLException {
        var result = new HashMap<YearMonth, BigDecimal>();
        try (var s = db.prepareStatement("SELECT month, gmv FROM gmv WHERE product_code = ?")) {
            s.setObject(1, productCode);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    result.put(toYearMonth(rs.getDate(1)), rs.getBigDecimal(2));
                }
            }
        }
        return result;
    }

    private static void addToGMV(HashMap<YearMonth, BigDecimal> gmvByMonth, YearMonth yearMonth, POInfo order) {
        var gmv = gmvByMonth.getOrDefault(yearMonth, BigDecimal.ZERO);
        gmv = gmv.add(order.price().multiply(BigDecimal.valueOf(order.quantity())));
        gmvByMonth.put(yearMonth, gmv);
    }

    private static void subtractFromGMV(HashMap<YearMonth, BigDecimal> gmvByMonth, YearMonth yearMonthMonth, POInfo storno) {
        var stornoGMV = gmvByMonth.getOrDefault(yearMonthMonth, BigDecimal.ZERO);
        stornoGMV = stornoGMV.subtract(storno.price().multiply(BigDecimal.valueOf(storno.stornoQuantity())));
        gmvByMonth.put(yearMonthMonth, stornoGMV);
    }

    private static void singleOrderWithSameProductTwice(HashMap<YearMonth, BigDecimal> gmvByMonth, POInfo finalized, POInfo storno) {
        addToGMV(gmvByMonth, YearMonth.from(finalized.orderDate()),finalized);
        addToGMV(gmvByMonth, YearMonth.from(storno.orderDate()), storno);
    }

    private static void singleProductWithFinalizedAndStorno(HashMap<YearMonth, BigDecimal> gmvByMonth, POInfo finalized, POInfo storno) {
        if (storno.initialQuantity() != finalized.quantity()) {
            logger.log(WARNING, "Mismatch in quantity between\n finalized order: %s\n and storno order: %s.".formatted(finalized, storno));
        }
        if (finalized.orderStatus() != 4) {
            throw new RuntimeException("Finalised order status wrong: %s.".formatted(finalized));
        }
        if (storno.orderStatus() != 5) {
            throw new RuntimeException("Storno order status wrong: %s.".formatted(storno));
        }
        var finalizedMonth = YearMonth.from(finalized.orderDate());
        var stornoMonth = YearMonth.from(storno.orderDate());
        if (finalizedMonth.equals(stornoMonth)) {
            addToGMV(gmvByMonth, finalizedMonth, storno);
        } else {
            addToGMV(gmvByMonth, finalizedMonth, finalized);
            subtractFromGMV(gmvByMonth, stornoMonth, storno);
        }
    }

    private static void computeGMVForOrder(List<POInfo> poInfos, HashMap<YearMonth, BigDecimal> gmvByMonth) {
        var poInfoByOrderProductId = poInfos.stream()
                .collect(Collectors.groupingBy(POInfo::productInOrderId));
        poInfoByOrderProductId.forEach((productInOrderId, orderInfos) -> {
            if (orderInfos.size() == 1) {
                POInfo order = orderInfos.getFirst();
                var yearMonth = YearMonth.from(order.orderDate());
                addToGMV(gmvByMonth, yearMonth, order);
            } else if (orderInfos.size() == 2) {
                POInfo finalized = orderInfos.getFirst();
                POInfo storno = orderInfos.getLast();
                singleProductWithFinalizedAndStorno(gmvByMonth, finalized, storno);
            } else {
                throw new RuntimeException("Not prepared to handle order with more than two states: %s / %d.".formatted(orderInfos, productInOrderId));
            }
        });
    }

    /**
     * Update the GMV table with GV values.
     * This method will take care of inserting or updating values.
     *
     * @param db database connection.
     * @param productCode code of the product.
     * @param gmvByMonth GMV table having the GMV value for several months.
     * @throws SQLException on database error.
     */
    private static void insertOrUpdateGMV(Connection db, String productCode, Map<YearMonth, BigDecimal> gmvByMonth) throws SQLException {
        var currentGMVByMonth = getGMVByProductId(db, productCode);
        for (Map.Entry<YearMonth, BigDecimal> entry : gmvByMonth.entrySet()) {
            YearMonth month = entry.getKey();
            BigDecimal gmv = entry.getValue().setScale(2, HALF_EVEN);
            var currentGMV = currentGMVByMonth.get(month);
            if (currentGMV == null) {
                var linesAdded = insertGMV(db, productCode, month, gmv);
                if (linesAdded != 1) {
                    throw new RuntimeException("Unexpected number of lines added: %d for the product %s and month %s.".formatted(linesAdded, productCode, month));
                }
            } else if (!gmv.equals(currentGMV)) {
                var linesUpdated = updateGMV(db, productCode, month, gmv);
                if (linesUpdated != 1) {
                    throw new RuntimeException("Unexpected number of lines updated: %d for the product %s and month %s.".formatted(linesUpdated, productCode, month));
                }
            }
        }
    }

    /**
     * Insert a GMV value.
     *
     * @param db database connection.
     * @param productCode code of the product for which the GMV value is provided.
     * @param month for which the GMV value is provided.
     * @param gmv GMV value to insert.
     * @return 1 if the value was inserted, 0 otherwise.
     * @throws SQLException on database error.
     */
    private static int insertGMV(Connection db, String productCode, YearMonth month, BigDecimal gmv) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO gmv (product_code, month, gmv) VALUES (?,?,?)")) {
            s.setObject(1, productCode);
            s.setDate(2, toDate(month));
            s.setBigDecimal(3, gmv);
            return s.executeUpdate();
        }
    }

    /**
     * Update a GMV value.
     *
     * @param db database connection.
     * @param productCode code of the product for which the GMV value is provided.
     * @param month for which the GMV value is provided.
     * @param gmv GMV new GMV value.
     * @return 1 if the value was update, 0 otherwise.
     * @throws SQLException on database error.
     */
    private static int updateGMV(Connection db, String productCode, YearMonth month, BigDecimal gmv) throws SQLException {
        try (var s = db.prepareStatement("UPDATE gmv SET gmv = ? WHERE product_code = ? AND month = ?")) {
            s.setBigDecimal(1, gmv);
            s.setObject(2, productCode);
            s.setDate(3, toDate(month));
            return s.executeUpdate();
        }
    }
}