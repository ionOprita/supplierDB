package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Product quantities for company dashboards. The caller owns the read transaction. */
public final class OverviewTable {
    private OverviewTable() {
    }

    public record OverviewData(String month, String throughDate, List<String> days,
                               List<CompanySeries> companies, long unassignedReturns) {
    }

    public record CompanySeries(String key, String name, List<Long> orders, List<Long> returns) {
    }

    private static final String COMPANY_VENDORS = """
            WITH company_vendors AS (
              SELECT id,
                     CASE WHEN NULLIF(BTRIM(company_name, E' \\t\\n\\r\\f'), '') IS NULL
                          THEN 'vendor:' || id::text
                          ELSE 'company:' || BTRIM(company_name, E' \\t\\n\\r\\f') END AS company_key,
                     COALESCE(NULLIF(BTRIM(company_name, E' \\t\\n\\r\\f'), ''),
                              NULLIF(BTRIM(vendor_name, E' \\t\\n\\r\\f'), ''), id::text) AS company_name
              FROM vendor
            )
            """;

    private static final String ORDERS_SQL = COMPANY_VENDORS + """
            , requested_periods AS (
              SELECT ?::timestamp AS period_start, ?::timestamp AS end_exclusive
            ),
            """ + OrderQuantityTable.PICKED_CTE + """
            SELECT v.company_key, o.event_date, COALESCE(SUM(o.picked_qty), 0)::bigint AS quantity
            FROM picked_order_quantities o
            JOIN company_vendors v ON v.id = o.vendor_id
            GROUP BY v.company_key, o.event_date
            """;

    private static final String RETURNS_SQL = COMPANY_VENDORS + """
            , month_returns AS (
              SELECT r.emag_id, r.order_id, r.date::date AS event_date,
                     COALESCE(returned.quantity, 0) AS returned_units
              FROM rma_result r
              LEFT JOIN LATERAL (
                SELECT SUM(rp.quantity)::bigint AS quantity
                FROM emag_returned_products rp
                WHERE rp.emag_id = r.emag_id
              ) returned ON TRUE
              WHERE r.request_status = 7 AND r.date >= ? AND r.date < ?
            ), candidate_companies AS (
              SELECT DISTINCT r.emag_id, v.company_key
              FROM month_returns r
              JOIN emag_order o ON o.id = r.order_id
              JOIN company_vendors v ON v.id = o.vendor_id
            ), candidate_counts AS (
              SELECT emag_id, COUNT(*) AS company_count
              FROM candidate_companies
              GROUP BY emag_id
            ), matching_companies AS (
              SELECT c.emag_id, c.company_key
              FROM candidate_companies c
              JOIN candidate_counts n ON n.emag_id = c.emag_id AND n.company_count > 1
              JOIN month_returns r ON r.emag_id = c.emag_id
              WHERE EXISTS (
                SELECT 1 FROM emag_returned_products rp WHERE rp.emag_id = r.emag_id
              ) AND NOT EXISTS (
                SELECT 1
                FROM emag_returned_products rp
                WHERE rp.emag_id = r.emag_id AND NOT EXISTS (
                  SELECT 1
                  FROM emag_order o
                  JOIN company_vendors v ON v.id = o.vendor_id
                  JOIN product_in_order pio ON pio.emag_order_surrogate_id = o.surrogate_id
                  WHERE o.id = r.order_id AND v.company_key = c.company_key
                    AND pio.product_id = rp.product_id AND pio.mkt_id = rp.product_emag_id
                )
              )
            ), resolved_returns AS (
              SELECT c.emag_id, c.company_key
              FROM candidate_companies c
              JOIN candidate_counts n ON n.emag_id = c.emag_id AND n.company_count = 1
              UNION ALL
              SELECT emag_id, MIN(company_key)
              FROM matching_companies
              GROUP BY emag_id
              HAVING COUNT(*) = 1
            )
            SELECT resolved.company_key, r.event_date, COALESCE(SUM(r.returned_units), 0)::bigint AS quantity
            FROM month_returns r
            LEFT JOIN resolved_returns resolved ON resolved.emag_id = r.emag_id
            GROUP BY resolved.company_key, r.event_date
            """;

    public static OverviewData read(Connection db, LocalDate throughDate) throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(throughDate, "throughDate");
        var month = YearMonth.from(throughDate);
        var start = month.atDay(1);
        var end = throughDate.plusDays(1);
        var days = start.datesUntil(end).map(LocalDate::toString).toList();
        var companies = readCompanies(db, days.size());

        try (var statement = db.prepareStatement(ORDERS_SQL)) {
            setRange(statement, 1, start, end);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var company = companies.get(rows.getString("company_key"));
                    company.orders[rows.getDate("event_date").toLocalDate().getDayOfMonth() - 1]
                            += rows.getLong("quantity");
                }
            }
        }

        long unassignedReturns = 0;
        try (var statement = db.prepareStatement(RETURNS_SQL)) {
            setRange(statement, 1, start, end);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var key = rows.getString("company_key");
                    if (key == null) {
                        unassignedReturns += rows.getLong("quantity");
                    } else {
                        companies.get(key).returns[rows.getDate("event_date").toLocalDate().getDayOfMonth() - 1]
                                += rows.getLong("quantity");
                    }
                }
            }
        }

        var series = companies.values().stream()
                .sorted(Comparator.comparing((CompanyCounts c) -> c.name).thenComparing(c -> c.key))
                .map(c -> new CompanySeries(c.key, c.name, boxed(c.orders), boxed(c.returns)))
                .toList();
        return new OverviewData(month.toString(), throughDate.toString(), days, series, unassignedReturns);
    }

    private static Map<String, CompanyCounts> readCompanies(Connection db, int dayCount) throws SQLException {
        var companies = new HashMap<String, CompanyCounts>();
        try (var statement = db.prepareStatement(COMPANY_VENDORS + """
                SELECT DISTINCT company_key, company_name FROM company_vendors
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                var key = rows.getString("company_key");
                companies.put(key, new CompanyCounts(key, rows.getString("company_name"), dayCount));
            }
        }
        return companies;
    }

    private static void setRange(java.sql.PreparedStatement statement, int firstParameter,
                                 LocalDate start, LocalDate end) throws SQLException {
        statement.setTimestamp(firstParameter, Timestamp.valueOf(start.atStartOfDay()));
        statement.setTimestamp(firstParameter + 1, Timestamp.valueOf(end.atStartOfDay()));
    }

    private static List<Long> boxed(long[] counts) {
        var result = new ArrayList<Long>(counts.length);
        for (long count : counts) result.add(count);
        return List.copyOf(result);
    }

    private static final class CompanyCounts {
        private final String key;
        private final String name;
        private final long[] orders;
        private final long[] returns;

        private CompanyCounts(String key, String name, int days) {
            this.key = key;
            this.name = name;
            this.orders = new long[days];
            this.returns = new long[days];
        }
    }
}
