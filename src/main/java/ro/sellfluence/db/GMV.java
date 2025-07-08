package ro.sellfluence.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import static java.math.RoundingMode.HALF_EVEN;
import static ro.sellfluence.support.UsefulMethods.toDate;
import static ro.sellfluence.support.UsefulMethods.toYearMonth;

/**
 * Collect all database methods tied to the GMV table.
 */
public class GMV {
    /**
     * Update the GMV table with GV values.
     * This method will take care of inserting or updating values.
     *
     * @param db database connection.
     * @param productCode code of the product.
     * @param gmvByMonth GMV table having the GMV value for several months.
     * @throws SQLException on database error.
     */
    static void insertOrUpdateGMV(Connection db, String productCode, Map<YearMonth, BigDecimal> gmvByMonth) throws SQLException {
        var currentGMVByMonth = getGMVByProductId(db, productCode);
        for (Map.Entry<YearMonth, BigDecimal> entry : gmvByMonth.entrySet()) {
            YearMonth month = entry.getKey();
            BigDecimal gmv = entry.getValue().setScale(2, HALF_EVEN);
            var currentGMV = currentGMVByMonth.get(month);
            if (currentGMV == null) {
                insertGMV(db, productCode, month, gmv);
            } else if (!gmv.equals(currentGMV)) {
                updateGMV(db, productCode, month, gmv);
            }
        }
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

    static void addToGMV(HashMap<YearMonth, BigDecimal> gmvByMonth, YearMonth yearMonth, EmagMirrorDB.POInfo order) {
        var gmv = gmvByMonth.getOrDefault(yearMonth, BigDecimal.ZERO);
        gmv = gmv.add(order.price().multiply(BigDecimal.valueOf(order.quantity())));
        gmvByMonth.put(yearMonth, gmv);
    }

    static void subtractFromGMV(HashMap<YearMonth, BigDecimal> gmvByMonth, YearMonth yearMonthMonth, EmagMirrorDB.POInfo storno) {
        var stornoGMV = gmvByMonth.getOrDefault(yearMonthMonth, BigDecimal.ZERO);
        stornoGMV = stornoGMV.subtract(storno.price().multiply(BigDecimal.valueOf(storno.stornoQuantity())));
        gmvByMonth.put(yearMonthMonth, stornoGMV);
    }

    static void specialCase(HashMap<YearMonth, BigDecimal> gmvByMonth, EmagMirrorDB.POInfo finalized, EmagMirrorDB.POInfo storno) {
        addToGMV(gmvByMonth, YearMonth.from(finalized.orderDate()),finalized);
        addToGMV(gmvByMonth, YearMonth.from(storno.orderDate()), storno);
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