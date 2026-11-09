package ro.sellfluence.db;

import java.math.BigDecimal;
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
import java.util.UUID;

import static java.math.RoundingMode.HALF_EVEN;

/** Read-only company GMV, allocating one monthly calculation across its contributing snapshot dates. */
public final class GMVDashboardTable {
    private GMVDashboardTable() {
    }

    public record GMVDashboardData(String month, String throughDate, List<String> days, String currency,
                                   String total, List<CompanySeries> companies) {
        public GMVDashboardData {
            days = List.copyOf(days);
            companies = List.copyOf(companies);
        }
    }

    public record CompanySeries(String key, String name, String total, List<String> gmv) {
        public CompanySeries {
            gmv = List.copyOf(gmv);
        }
    }

    /** A safe user-facing error; amounts in different currencies must never be combined. */
    public static final class UnsupportedCurrencyException extends SQLException {
        public UnsupportedCurrencyException(String currency) {
            super("GMV dashboard only supports RON amounts; found "
                    + (currency == null || currency.isBlank() ? "a missing currency"
                    : currency.matches("[A-Z]{3}") ? currency : "an unsupported currency value") + ".");
        }
    }

    record VendorCompany(UUID vendorId, String key, String name) {
    }

    record SourceLine(String productCode, UUID vendorId, String currency, POInfo line) {
    }

    private record OrderKey(String productCode, UUID vendorId, String orderId) {
    }

    private static final String COMPANIES_SQL = """
            SELECT id,
                   CASE WHEN NULLIF(BTRIM(company_name, E' \\t\\n\\r\\f'), '') IS NULL
                        THEN 'vendor:' || id::text
                        ELSE 'company:' || BTRIM(company_name, E' \\t\\n\\r\\f') END AS company_key,
                   COALESCE(NULLIF(BTRIM(company_name, E' \\t\\n\\r\\f'), ''),
                            NULLIF(BTRIM(vendor_name, E' \\t\\n\\r\\f'), ''), id::text) AS company_name
            FROM vendor
            """;

    private static final String SOURCES_SQL = """
            WITH candidate_lines AS (
              SELECT DISTINCT p.product_code, o.vendor_id, o.id AS order_id, pio.id AS line_id
              FROM emag_order o
              JOIN product_in_order pio ON pio.emag_order_surrogate_id = o.surrogate_id
              JOIN product p ON p.emag_pnk = pio.part_number_key
              WHERE o.status IN (4,5) AND o.date >= ? AND o.date < ?
            )
            SELECT p.product_code, p.name AS product_name, o.vendor_id, o.id AS order_id,
                   o.surrogate_id, o.status, o.date AS order_date, pio.id AS line_id,
                   pio.quantity, pio.initial_qty, pio.storno_qty, pio.sale_price, pio.vat, pio.currency,
                   seller_voucher.total AS seller_voucher_total
            FROM candidate_lines c
            JOIN emag_order o ON o.vendor_id = c.vendor_id AND o.id = c.order_id
            JOIN product_in_order pio ON pio.emag_order_surrogate_id = o.surrogate_id AND pio.id = c.line_id
            JOIN product p ON p.emag_pnk = pio.part_number_key AND p.product_code = c.product_code
            LEFT JOIN LATERAL (
              SELECT SUM(COALESCE(v.value, 0) + COALESCE(v.vat_value, 0)) AS total
              FROM voucher_split v
              WHERE v.product_id = pio.id AND v.emag_order_surrogate_id = pio.emag_order_surrogate_id
                AND v.offered_by = 'seller'
            ) seller_voucher ON TRUE
            WHERE o.status IN (4,5)
            ORDER BY p.product_code, o.vendor_id, o.id, o.status, o.surrogate_id, pio.id
            """;

    public static GMVDashboardData read(Connection db, LocalDate throughDate) throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(throughDate, "throughDate");
        var start = throughDate.withDayOfMonth(1);
        return calculate(throughDate, readCompanies(db), readSources(db, start, throughDate.plusDays(1)));
    }

    private static List<VendorCompany> readCompanies(Connection db) throws SQLException {
        var result = new ArrayList<VendorCompany>();
        try (var statement = db.prepareStatement(COMPANIES_SQL); var rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new VendorCompany(rows.getObject("id", UUID.class),
                        rows.getString("company_key"), rows.getString("company_name")));
            }
        }
        return result;
    }

    private static List<SourceLine> readSources(Connection db, LocalDate start, LocalDate end) throws SQLException {
        var result = new ArrayList<SourceLine>();
        try (var statement = db.prepareStatement(SOURCES_SQL)) {
            statement.setTimestamp(1, Timestamp.valueOf(start.atStartOfDay()));
            statement.setTimestamp(2, Timestamp.valueOf(end.atStartOfDay()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var currency = rows.getString("currency");
                    requireRon(currency);
                    var salePrice = rows.getBigDecimal("sale_price");
                    var vat = rows.getString("vat");
                    if (salePrice == null || vat == null) {
                        throw new SQLException("GMV dashboard source is missing a price or VAT rate.");
                    }
                    final BigDecimal price;
                    try {
                        price = salePrice.multiply(BigDecimal.ONE.add(new BigDecimal(vat))).setScale(2, HALF_EVEN);
                    } catch (NumberFormatException e) {
                        throw new SQLException("GMV dashboard source contains an invalid VAT rate.", e);
                    }
                    var line = new POInfo(rows.getString("order_id"), rows.getInt("surrogate_id"),
                            rows.getTimestamp("order_date").toLocalDateTime().toLocalDate(), rows.getInt("status"),
                            rows.getInt("line_id"), rows.getString("product_name"), rows.getInt("quantity"),
                            rows.getInt("initial_qty"), rows.getInt("storno_qty"), price,
                            rows.getBigDecimal("seller_voucher_total"));
                    result.add(new SourceLine(rows.getString("product_code"),
                            rows.getObject("vendor_id", UUID.class), currency, line));
                }
            }
        }
        return result;
    }

    static GMVDashboardData calculate(LocalDate throughDate, List<VendorCompany> vendors,
                                      List<SourceLine> sources) throws SQLException {
        Objects.requireNonNull(throughDate, "throughDate");
        Objects.requireNonNull(vendors, "vendors");
        Objects.requireNonNull(sources, "sources");
        var start = throughDate.withDayOfMonth(1);
        var days = start.datesUntil(throughDate.plusDays(1)).map(LocalDate::toString).toList();
        var companies = new HashMap<String, CompanyAmounts>();
        var companyByVendor = new HashMap<UUID, String>();
        for (var vendor : vendors) {
            companies.computeIfAbsent(vendor.key(), key -> new CompanyAmounts(key, vendor.name(), days.size()));
            companyByVendor.put(vendor.vendorId(), vendor.key());
        }

        var orderGroups = new HashMap<OrderKey, List<POInfo>>();
        var companyBySnapshot = new HashMap<Integer, String>();
        for (var source : sources) {
            requireRon(source.currency());
            var company = companyByVendor.get(source.vendorId());
            if (company == null) throw new SQLException("GMV dashboard source vendor has no company metadata.");
            var previousCompany = companyBySnapshot.putIfAbsent(source.line().surrogateId(), company);
            if (previousCompany != null && !previousCompany.equals(company)) {
                throw new SQLException("GMV dashboard source snapshot belongs to conflicting companies.");
            }
            var key = new OrderKey(source.productCode(), source.vendorId(), source.line().orderId());
            orderGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source.line());
        }
        var orders = new HashMap<String, List<POInfo>>();
        for (var group : orderGroups.values()) {
            group.sort(Comparator.comparingInt(POInfo::orderStatus).thenComparingInt(POInfo::surrogateId));
            orders.put(Integer.toString(orders.size()), group);
        }

        final GMV.Breakdown breakdown;
        try {
            breakdown = GMV.computeGMVBreakdownForPeriod(orders, start, throughDate);
        } catch (RuntimeException e) {
            throw new SQLException("GMV dashboard cannot calculate inconsistent order snapshots.", e);
        }
        for (var contribution : breakdown.contributions()) {
            var company = companies.get(companyBySnapshot.get(contribution.source().surrogateId()));
            var index = contribution.source().orderDate().getDayOfMonth() - 1;
            company.daily.set(index, company.daily.get(index).add(contribution.amount()));
            company.total = company.total.add(contribution.amount());
        }
        var series = companies.values().stream()
                .sorted(Comparator.comparing((CompanyAmounts company) -> company.name).thenComparing(company -> company.key))
                .map(company -> new CompanySeries(company.key, company.name, money(company.total),
                        company.daily.stream().map(GMVDashboardTable::money).toList()))
                .toList();
        return new GMVDashboardData(YearMonth.from(throughDate).toString(), throughDate.toString(), days,
                "RON", money(breakdown.total()), series);
    }

    private static void requireRon(String currency) throws UnsupportedCurrencyException {
        if (!"RON".equals(currency)) throw new UnsupportedCurrencyException(currency);
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, HALF_EVEN).toPlainString();
    }

    private static final class CompanyAmounts {
        private final String key;
        private final String name;
        private final List<BigDecimal> daily;
        private BigDecimal total = BigDecimal.ZERO;

        private CompanyAmounts(String key, String name, int days) {
            this.key = key;
            this.name = name;
            daily = new ArrayList<>();
            for (int index = 0; index < days; index++) daily.add(BigDecimal.ZERO);
        }
    }
}
