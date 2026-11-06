package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Shared Orders-table quantity selection. Period filtering deliberately precedes snapshot selection. */
final class OrderQuantityTable {
    private OrderQuantityTable() {
    }

    static final String PICKED_CTE = pickedCte(false);

    static final String MONTH_SQL = """
            WITH requested_periods AS (
              SELECT ?::timestamp AS period_start, ?::timestamp AS end_exclusive
            ),
            """ + PICKED_CTE + """
            SELECT SUM(picked_qty) AS quantity, pnk
            FROM picked_order_quantities
            GROUP BY pnk
            ORDER BY pnk
            """;

    static final String MONTHS_SQL = """
            WITH bounds AS (
              SELECT ?::timestamp AS first_month, ?::timestamp AS end_month
            ), requested_periods AS (
              SELECT month_start AS period_start, month_start + INTERVAL '1 month' AS end_exclusive
              FROM bounds
              CROSS JOIN LATERAL generate_series(
                first_month, end_month - INTERVAL '1 month', INTERVAL '1 month'
              ) AS month_start
            ),
            """ + PICKED_CTE + """
            SELECT pnk, period_start::date AS month_start, SUM(picked_qty) AS quantity
            FROM picked_order_quantities
            GROUP BY pnk, period_start
            ORDER BY pnk, period_start
            """;

    private static String pickedCte(boolean selectedProductOnly) {
        return pickedCte(selectedProductOnly, false);
    }

    private static String pickedCte(boolean selectedProductOnly, boolean selectedVendorOnly) {
        return """
                picked_order_quantities AS (
                  SELECT DISTINCT ON (r.period_start, o.id, pio.part_number_key)
                         r.period_start, o.id AS order_id, pio.part_number_key AS pnk,
                         o.vendor_id, o.date::date AS event_date,
                         CASE WHEN o.status = 4 THEN pio.quantity ELSE pio.initial_qty END AS picked_qty
                  FROM requested_periods r
                  JOIN emag_order o ON o.date >= r.period_start AND o.date < r.end_exclusive
                  JOIN product_in_order pio ON pio.emag_order_surrogate_id = o.surrogate_id
                  WHERE o.status IN (4,5)
                """ + (selectedProductOnly ? "    AND pio.part_number_key = ?\n" : "")
                + (selectedVendorOnly ? "    AND o.vendor_id = ?\n" : "") + """
                  ORDER BY r.period_start, o.id, pio.part_number_key, o.status, o.surrogate_id DESC
                )
                """;
    }

    static void validatePeriods(String pnk, Map<LocalDate, LocalDate> inclusivePeriodEnds) {
        Objects.requireNonNull(pnk, "pnk");
        Objects.requireNonNull(inclusivePeriodEnds, "inclusivePeriodEnds");
        inclusivePeriodEnds.forEach((start, end) -> {
            Objects.requireNonNull(start, "period start");
            Objects.requireNonNull(end, "period end");
            if (start.isAfter(end)) throw new IllegalArgumentException("Period start must not be after its end.");
        });
    }

    /** Returns an entry, including zero, for every supplied inclusive period without reading unrelated PNKs. */
    static Map<LocalDate, Long> countForProductPeriods(
            Connection db, String pnk, Map<LocalDate, LocalDate> inclusivePeriodEnds) throws SQLException {
        return countProductPeriods(db, null, pnk, inclusivePeriodEnds);
    }

    /** Applies the exact selling account before choosing the order snapshot for each period. */
    static Map<LocalDate, Long> countForProductPeriods(
            Connection db, UUID vendorId, String pnk, Map<LocalDate, LocalDate> inclusivePeriodEnds)
            throws SQLException {
        Objects.requireNonNull(vendorId, "vendorId");
        return countProductPeriods(db, vendorId, pnk, inclusivePeriodEnds);
    }

    private static Map<LocalDate, Long> countProductPeriods(
            Connection db, UUID vendorId, String pnk, Map<LocalDate, LocalDate> inclusivePeriodEnds)
            throws SQLException {
        validatePeriods(pnk, inclusivePeriodEnds);
        if (inclusivePeriodEnds.isEmpty()) return Map.of();
        Objects.requireNonNull(db, "db");
        var periods = new TreeMap<>(inclusivePeriodEnds);
        var requestedPeriods = "WITH requested_periods(period_start, end_exclusive) AS (VALUES "
                + String.join(", ", Collections.nCopies(periods.size(), "(?::timestamp, ?::timestamp)")) + "),\n";
        var sql = requestedPeriods + pickedCte(true, vendorId != null) + """
                SELECT r.period_start::date AS period_start, COALESCE(SUM(p.picked_qty), 0)::bigint AS quantity
                FROM requested_periods r
                LEFT JOIN picked_order_quantities p USING (period_start)
                GROUP BY r.period_start
                ORDER BY r.period_start
                """;
        var result = new HashMap<LocalDate, Long>();
        try (var statement = db.prepareStatement(sql)) {
            var parameter = 1;
            for (var period : periods.entrySet()) {
                statement.setTimestamp(parameter++, Timestamp.valueOf(period.getKey().atStartOfDay()));
                statement.setTimestamp(parameter++, Timestamp.valueOf(period.getValue().plusDays(1).atStartOfDay()));
            }
            statement.setString(parameter++, pnk);
            if (vendorId != null) statement.setObject(parameter, vendorId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getDate("period_start").toLocalDate(), rows.getLong("quantity"));
                }
            }
        }
        return Map.copyOf(result);
    }
}
