package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static ro.sellfluence.support.UsefulMethods.require;
import static ro.sellfluence.support.UsefulMethods.toTimestamp;
import static ro.sellfluence.support.UsefulMethods.toYearMonth;

/** Shared monthly counts for the existing order, return, and storno reports. */
final class MonthlyUnitCounts {
    static final String STORNO_SQL = """
            SELECT
              p.part_number_key AS pnk,
              DATE_TRUNC('month', s.storno_date)::date AS month_start,
              SUM(s.quantity) AS quantity
            FROM storno AS s
            JOIN emag_order AS o ON o.id = s.order_id
            JOIN product_in_order AS p ON p.id = s.product_id AND p.emag_order_surrogate_id = o.surrogate_id
            WHERE o.status = 5
              AND s.storno_date >= ?
              AND s.storno_date < ?
            GROUP BY p.part_number_key, month_start
            ORDER BY p.part_number_key, month_start
            """;

    static final String RETURN_SQL = """
            SELECT
              pio.part_number_key AS pnk,
              DATE_TRUNC('month', r.date)::date AS month_start,
              SUM(rp.quantity) AS quantity
            FROM rma_result AS r
            INNER JOIN emag_returned_products AS rp ON r.emag_id = rp.emag_id
            INNER JOIN (
                SELECT DISTINCT ON (id) id, surrogate_id, vendor_id
                FROM emag_order
                ORDER BY id, status DESC
            ) AS o ON r.order_id = o.id
            INNER JOIN product_in_order AS pio ON o.surrogate_id = pio.emag_order_surrogate_id
            WHERE r.request_status = 7
              AND rp.product_id = pio.product_id
              AND rp.product_emag_id = pio.mkt_id
              AND r.date >= ?
              AND r.date < ?
            GROUP BY pnk, month_start
            ORDER BY pnk, month_start
            """;

    private MonthlyUnitCounts() {
    }

    /** A null PNK filter selects every product. Filter the result after legacy order snapshot selection. */
    static Map<String, Map<YearMonth, Integer>> read(Connection db, YearMonth startMonth, YearMonth endMonth,
                                                  String sql, Set<String> pnks) throws SQLException {
        var query = pnks == null ? sql : "SELECT * FROM (" + sql + ") AS monthly_counts WHERE pnk = ANY (?)";
        var result = new HashMap<String, Map<YearMonth, Integer>>();
        var pnkArray = pnks == null ? null : db.createArrayOf("text", pnks.toArray(String[]::new));
        try (var statement = db.prepareStatement(query)) {
            statement.setTimestamp(1, toTimestamp(startMonth.atDay(1).atStartOfDay()));
            statement.setTimestamp(2, toTimestamp(endMonth.atDay(1).atStartOfDay()));
            if (pnkArray != null) statement.setArray(3, pnkArray);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var pnk = rows.getString("pnk");
                    var month = toYearMonth(rows.getDate("month_start"));
                    var oldValue = result.computeIfAbsent(pnk, _ -> new HashMap<>()).put(month, rows.getInt("quantity"));
                    require(oldValue == null, () -> "Unexpected duplicate for PNK %s in month %s".formatted(pnk, month));
                }
            }
        } finally {
            if (pnkArray != null) pnkArray.free();
        }
        return result;
    }
}
