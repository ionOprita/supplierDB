package ro.sellfluence.db;

import ro.sellfluence.support.Logs;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
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

    /** The source snapshot and signed quantity used for one exact GMV contribution. */
    public record Contribution(POInfo source, long quantity, BigDecimal amount, BigDecimal sellerVoucherAdjustment) {
        public Contribution(POInfo source, long quantity, BigDecimal amount) {
            this(source, quantity, amount, BigDecimal.ZERO.setScale(2));
        }
    }

    /** Current GMV and its contributing order lines; no values are stored. */
    public record Breakdown(BigDecimal total, List<Contribution> contributions) {
        public Breakdown {
            contributions = List.copyOf(contributions);
        }
    }

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
        var gmv = computeGMVByBucket(ordersWithProduct, bucketForPeriod(startDate, endDate));
        return gmv.getOrDefault(Boolean.TRUE, BigDecimal.ZERO).setScale(2, HALF_EVEN);
    }

    /** Return the exact contributing snapshots, including zero-valued lines, for an inclusive period. */
    static Breakdown computeGMVBreakdownForPeriod(Map<String, List<POInfo>> ordersWithProduct,
                                                  LocalDate startDate, LocalDate endDate) {
        validatePeriod(startDate, endDate);
        var contributions = new ArrayList<Contribution>();
        visitContributions(ordersWithProduct, bucketForPeriod(startDate, endDate),
                (_, contribution) -> contributions.add(contribution));
        contributions.sort(Comparator.comparing((Contribution contribution) -> contribution.source().orderDate())
                .thenComparing(contribution -> contribution.source().orderId())
                .thenComparingInt(contribution -> contribution.source().productInOrderId())
                .thenComparingInt(contribution -> contribution.source().orderStatus())
                .thenComparingInt(contribution -> contribution.source().surrogateId()));
        var total = contributions.stream().map(Contribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Breakdown(total.setScale(2, HALF_EVEN), contributions);
    }

    private static Function<LocalDate, Boolean> bucketForPeriod(LocalDate startDate, LocalDate endDate) {
        return date -> !date.isBefore(startDate) && !date.isAfter(endDate) ? Boolean.TRUE : null;
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
        visitContributions(ordersWithProduct, bucketForDate,
                (bucket, contribution) -> gmvByBucket.merge(bucket, contribution.amount(), BigDecimal::add));
        return gmvByBucket;
    }

    private static <T> void visitContributions(Map<String, List<POInfo>> ordersWithProduct,
                                               Function<LocalDate, T> bucketForDate,
                                               BiConsumer<T, Contribution> consumer) {
        ordersWithProduct.forEach((_, poInfos) -> computeGMVForOrder(poInfos, bucketForDate, consumer));
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

    /** Read all stored GMV values, grouped by product code, in a single query. */
    static Map<String, Map<YearMonth, BigDecimal>> getAllGMVByProductCode(Connection db) throws SQLException {
        var result = new HashMap<String, Map<YearMonth, BigDecimal>>();
        try (var s = db.prepareStatement("SELECT product_code, month, gmv FROM gmv")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getString("product_code"), _ -> new HashMap<>())
                            .put(toYearMonth(rs.getDate("month")), rs.getBigDecimal("gmv"));
                }
            }
        }
        return result;
    }

    private static <T> void contribute(T bucket, POInfo source, long quantity,
                                      BiConsumer<T, Contribution> consumer) {
        contribute(bucket, source, quantity, voucherForRemainingItems(source), consumer);
    }

    private static BigDecimal voucherForRemainingItems(POInfo source) {
        return source.quantity() == 0 ? BigDecimal.ZERO.setScale(2) : source.sellerVoucherTotal();
    }

    private static <T> void contribute(T bucket, POInfo source, long quantity, BigDecimal voucherAdjustment,
                                      BiConsumer<T, Contribution> consumer) {
        if (bucket == null) return;
        consumer.accept(bucket, new Contribution(source, quantity,
                source.price().multiply(BigDecimal.valueOf(quantity)).add(voucherAdjustment), voucherAdjustment));
    }

    private static <T> void singleProductWithFinalizedAndStorno(POInfo finalized, POInfo storno,
                                                               Function<LocalDate, T> bucketForDate,
                                                               BiConsumer<T, Contribution> consumer) {
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
            contribute(finalizedBucket, storno, storno.quantity(), consumer);
        } else {
            contribute(finalizedBucket, finalized, finalized.quantity(), consumer);
            contribute(stornoBucket, storno, -(long) storno.stornoQuantity(),
                    voucherForRemainingItems(storno).subtract(voucherForRemainingItems(finalized)), consumer);
        }
    }

    private static <T> void computeGMVForOrder(List<POInfo> poInfos, Function<LocalDate, T> bucketForDate,
                                              BiConsumer<T, Contribution> consumer) {
        var poInfoByOrderProductId = poInfos.stream()
                .collect(Collectors.groupingBy(POInfo::productInOrderId));
        poInfoByOrderProductId.forEach((productInOrderId, orderInfos) -> {
            if (orderInfos.size() == 1) {
                POInfo order = orderInfos.getFirst();
                contribute(bucketForDate.apply(order.orderDate()), order, order.quantity(), consumer);
            } else if (orderInfos.size() == 2) {
                POInfo finalized = orderInfos.getFirst();
                POInfo storno = orderInfos.getLast();
                singleProductWithFinalizedAndStorno(finalized, storno, bucketForDate, consumer);
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
