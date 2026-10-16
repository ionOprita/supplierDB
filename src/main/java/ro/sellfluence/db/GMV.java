package ro.sellfluence.db;

import ro.sellfluence.support.Logs;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.math.RoundingMode.HALF_EVEN;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.support.UsefulMethods.toDate;
import static ro.sellfluence.support.UsefulMethods.toYearMonth;

/**
 * Collect GMV calculations and database methods tied to the GMV table.
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
        insertOrUpdateGMV(db, productCode, computeGMVByMonth(ordersWithProduct));
    }

    static Map<YearMonth, BigDecimal> computeGMVByMonth(Map<String, List<POInfo>> ordersWithProduct) {
        return computeGMVByBucket(ordersWithProduct, YearMonth::from);
    }

    /**
     * Compute GMV with the inclusive date range treated as a single bucket.
     * Matching finalized and storno snapshots outside the range must also be supplied.
     */
    static BigDecimal computeGMVForPeriod(Map<String, List<POInfo>> ordersWithProduct,
                                         LocalDate startDate, LocalDate endDate) {
        validatePeriod(startDate, endDate);
        var gmv = computeGMVByBucket(ordersWithProduct,
                date -> !date.isBefore(startDate) && !date.isAfter(endDate) ? Boolean.TRUE : null);
        return gmv.getOrDefault(Boolean.TRUE, BigDecimal.ZERO).setScale(2, HALF_EVEN);
    }

    static void validatePeriod(LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
    }

    /** A null bucket excludes that snapshot's contribution without losing its matching state. */
    private static <T> Map<T, BigDecimal> computeGMVByBucket(Map<String, List<POInfo>> ordersWithProduct,
                                                           Function<LocalDate, T> bucketForDate) {
        var gmvByBucket = new HashMap<T, BigDecimal>();
        ordersWithProduct.forEach((_, poInfos) -> computeGMVForOrder(poInfos, gmvByBucket, bucketForDate));
        return gmvByBucket;
    }

    /**
     * Return GMV values of all products for a given month.
     *
     * @param db database connection.
     * @param month for which to retrieve the GMV values.
     * @return map, which associates each product name with its GMV value.
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

    private static <T> void addToGMV(Map<T, BigDecimal> gmvByBucket, T bucket, POInfo order) {
        if (bucket == null) return;
        var gmv = gmvByBucket.getOrDefault(bucket, BigDecimal.ZERO);
        gmv = gmv.add(order.price().multiply(BigDecimal.valueOf(order.quantity())));
        gmvByBucket.put(bucket, gmv);
    }

    private static <T> void subtractFromGMV(Map<T, BigDecimal> gmvByBucket, T bucket, POInfo storno) {
        if (bucket == null) return;
        var stornoGMV = gmvByBucket.getOrDefault(bucket, BigDecimal.ZERO);
        stornoGMV = stornoGMV.subtract(storno.price().multiply(BigDecimal.valueOf(storno.stornoQuantity())));
        gmvByBucket.put(bucket, stornoGMV);
    }

    private static <T> void singleProductWithFinalizedAndStorno(Map<T, BigDecimal> gmvByBucket,
                                                               POInfo finalized, POInfo storno,
                                                               Function<LocalDate, T> bucketForDate) {
        if (storno.initialQuantity() != finalized.quantity()) {
            logger.log(WARNING, "Mismatch in quantity between\n finalized order: %s\n and storno order: %s.".formatted(finalized, storno));
        }
        if (finalized.orderStatus() != 4) {
            throw new RuntimeException("Finalised order status wrong: %s.".formatted(finalized));
        }
        if (storno.orderStatus() != 5) {
            throw new RuntimeException("Storno order status wrong: %s.".formatted(storno));
        }
        var finalizedBucket = bucketForDate.apply(finalized.orderDate());
        var stornoBucket = bucketForDate.apply(storno.orderDate());
        if (Objects.equals(finalizedBucket, stornoBucket)) {
            addToGMV(gmvByBucket, finalizedBucket, storno);
        } else {
            addToGMV(gmvByBucket, finalizedBucket, finalized);
            subtractFromGMV(gmvByBucket, stornoBucket, storno);
        }
    }

    private static <T> void computeGMVForOrder(List<POInfo> poInfos, Map<T, BigDecimal> gmvByBucket,
                                              Function<LocalDate, T> bucketForDate) {
        var poInfoByOrderProductId = poInfos.stream()
                .collect(Collectors.groupingBy(POInfo::productInOrderId));
        poInfoByOrderProductId.forEach((productInOrderId, orderInfos) -> {
            if (orderInfos.size() == 1) {
                POInfo order = orderInfos.getFirst();
                addToGMV(gmvByBucket, bucketForDate.apply(order.orderDate()), order);
            } else if (orderInfos.size() == 2) {
                POInfo finalized = orderInfos.getFirst();
                POInfo storno = orderInfos.getLast();
                singleProductWithFinalizedAndStorno(gmvByBucket, finalized, storno, bucketForDate);
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
