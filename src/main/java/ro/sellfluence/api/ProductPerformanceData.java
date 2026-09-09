package ro.sellfluence.api;

import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Product advertising performance, computed from eligible daily adset snapshots.
 *
 * <p>The database supplies vendor/product attribution and same-day active campaign/adset filtering.
 * This class classifies each snapshot, sums only the six source primitives, and derives all rates
 * from those sums. Stored daily rates are deliberately unused: averaging them would weight a
 * one-click adset as heavily as a thousand-click adset. See doc/ProductPerformance.md
 * for the complete data selection and calculation rules.</p>
 */
public final class ProductPerformanceData {
    private static final Primitives ZERO = new Primitives(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L);

    private static final List<ColumnGroup> GROUPS = List.of(
            new ColumnGroup("overview", "Product metrics", List.of(
                    column("week", "Week", "date"),
                    column("stock", "Stock", "integer"),
                    column("gmv30", "GMV 30", "integer"),
                    column("clicks", "Clicks", "integer"),
                    column("salesPrice", "Sales Price", "decimal"),
                    column("conversion", "Conv.", "percent"),
                    column("performance", "Perform.", "text"),
                    column("averagePrice", "Average price", "decimal"),
                    column("reviews", "# rev.", "integer"),
                    column("rating", "rating", "decimal")
            )),
            new ColumnGroup("total", "Total", adsColumns("total", null)),
            new ColumnGroup("auto", "Auto", adsColumns("auto", "Auto")),
            new ColumnGroup("broad", "Broad", adsColumns("broad", "Broad")),
            new ColumnGroup("exact", "Exact", adsColumns("exact", "Exact")),
            new ColumnGroup("product", "Product", adsColumns("product", "Prod."))
    );

    private ProductPerformanceData() {
    }

    public enum Period {
        WEEK, MONTH;

        public static Period parse(String value) {
            if (value == null) return WEEK;
            return switch (value) {
                case "week" -> WEEK;
                case "month" -> MONTH;
                default -> throw new IllegalArgumentException("period must be week or month");
            };
        }

        LocalDate start(LocalDate date) {
            return this == WEEK ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : date.withDayOfMonth(1);
        }
    }

    public record Product(String label, String name, String pnk, String url) {
    }

    /** Percent columns contain fractional numbers, for example 0.0267 means 2.67%. */
    public record Column(String key, String label, String type) {
    }

    public record ColumnGroup(String key, String label, List<Column> columns) {
    }

    public record Row(Map<String, Object> values) {
    }

    public record DataError(String code, UUID vendorId, int campaignId, int adsetId, LocalDate reportDate,
                            List<String> matchTypes, String message) {
    }

    public record Response(boolean mock, Product product, List<ColumnGroup> groups, List<Row> rows,
                           List<DataError> errors) {
    }

    /** Only observed periods are emitted; available reports also contribute to partial weeks/months. */
    public static Response create(Product product, Period period, List<DailyAdset> snapshots) {
        var periods = new TreeMap<LocalDate, PeriodTotals>();
        var errors = new ArrayList<DataError>();
        for (var snapshot : snapshots) {
            var totals = periods.computeIfAbsent(period.start(snapshot.key().reportDate()), ignored -> new PeriodTotals());
            totals.add(snapshot, errors);
        }
        var rows = periods.entrySet().stream().map(entry -> entry.getValue().row(entry.getKey())).toList();
        return new Response(false, product, GROUPS, rows, List.copyOf(errors));
    }

    private static Column column(String key, String label, String type) {
        return new Column(key, label, type);
    }

    private static List<Column> adsColumns(String group, String shareLabel) {
        var columns = new ArrayList<>(List.of(
                column(group + "_impressions", "Imp.", "integer"),
                column(group + "_clicks", "Clicks", "integer"),
                column(group + "_spend", "Spend", "integer"),
                column(group + "_sales", "Sales", "integer"),
                column(group + "_units", "Total Units Sold", "integer"),
                column(group + "_ctr", "CTR", "percent"),
                column(group + "_conversion", "Conv.", "percent"),
                column(group + "_cpc", "CPC", "decimal"),
                column(group + "_roas", "ROAS", "decimal"),
                column(group + "_acos", "ACOS", "percent"),
                column(group + "_tacos", "TACOS", "percent")
        ));
        if (shareLabel == null) {
            columns.add(column(group + "_clicks_share", "% Clicks Ads of Total", "percent"));
            columns.add(column(group + "_sales_share", "% Sales Ads of Total", "percent"));
        } else {
            columns.add(column(group + "_impressions_share", "% Imp. " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_clicks_share", "% Clicks " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_spend_share", "% Spend " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_sales_share", "% Sales " + shareLabel + " of Tot. Ads", "percent"));
        }
        return List.copyOf(columns);
    }

    private enum Category {
        AUTO("auto"), BROAD("broad"), EXACT("exact"), PRODUCT("product");

        private final String key;

        Category(String key) {
            this.key = key;
        }
    }

    private static final class PeriodTotals {
        private final EnumMap<Category, Accumulator> categories = new EnumMap<>(Category.class);
        private final EnumSet<Category> invalid = EnumSet.noneOf(Category.class);

        private PeriodTotals() {
            for (var category : Category.values()) categories.put(category, new Accumulator());
        }

        private void add(DailyAdset snapshot, List<DataError> errors) {
            var category = classify(snapshot, errors);
            if (snapshot.matchedPnks().size() > 1) {
                errors.add(error(snapshot, "AMBIGUOUS_PRODUCT_ATTRIBUTION",
                        "Campaign name matches multiple product PNKs: " + snapshot.matchedPnks()));
                if (category != null) invalid.add(category);
                return;
            }
            if (category != null) categories.get(category).add(snapshot.metrics());
        }

        private Category classify(DailyAdset snapshot, List<DataError> errors) {
            if ("auto".equals(snapshot.targeting())) return Category.AUTO;
            if ("products".equals(snapshot.targeting())) return Category.PRODUCT;
            if ("keywords".equals(snapshot.targeting())) {
                var types = matchTypes(snapshot);
                if (types.size() == 1) {
                    if ("broad".equals(types.getFirst())) return Category.BROAD;
                    if ("exact".equals(types.getFirst())) return Category.EXACT;
                }
                // Its metrics cannot safely be assigned to either keyword section. Also invalidate
                // Total and all shares below, while preserving independently classified sections.
                invalid.add(Category.BROAD);
                invalid.add(Category.EXACT);
                errors.add(error(snapshot, "INVALID_KEYWORD_MATCH_TYPE",
                        "Expected exactly one non-negative keyword match type (broad or exact); found " + types));
                return null;
            }
            invalid.addAll(EnumSet.allOf(Category.class));
            errors.add(error(snapshot, "UNKNOWN_TARGETING", "Unsupported adset targeting: " + snapshot.targeting()));
            return null;
        }

        private Row row(LocalDate start) {
            var values = new LinkedHashMap<String, Object>();
            // Unsupported overview, TACOS, and shares of overall product activity remain null.
            for (var group : GROUPS) {
                for (var column : group.columns()) values.put(column.key(), null);
            }
            values.put("week", start.toString());
            var total = new Accumulator();
            for (var category : Category.values()) {
                if (!invalid.contains(category)) {
                    var primitives = categories.get(category).value;
                    writeMetrics(values, category.key, primitives);
                    total.add(primitives);
                }
            }
            if (invalid.isEmpty()) {
                writeMetrics(values, "total", total.value);
                for (var category : Category.values()) {
                    writeShares(values, category.key, categories.get(category).value, total.value);
                }
            }
            // Map.copyOf rejects nulls, which intentionally represent unavailable cells here.
            return new Row(Collections.unmodifiableMap(values));
        }
    }

    private static List<String> matchTypes(DailyAdset snapshot) {
        var types = new LinkedHashSet<>(snapshot.matchTypes());
        types.remove("negative");
        return Collections.unmodifiableList(new ArrayList<>(types));
    }

    private static DataError error(DailyAdset snapshot, String code, String message) {
        var key = snapshot.key();
        return new DataError(code, key.vendorId(), key.campaignId(), key.adsetId(), key.reportDate(),
                matchTypes(snapshot), message);
    }

    /**
     * The same addition is used for daily-to-category and category-to-total aggregation.
     * Unknown inputs propagate per primitive; an empty category starts with known zero values.
     * BigDecimal addition is exact, retaining source precision until the UI formats the values.
     */
    private static final class Accumulator {
        private Primitives value = ZERO;

        private void add(Primitives next) {
            value = new Primitives(sum(value.impressions(), next.impressions()), sum(value.clicks(), next.clicks()),
                    sum(value.spend(), next.spend()), sum(value.sales(), next.sales()),
                    sum(value.units(), next.units()), sum(value.salesCount(), next.salesCount()));
        }
    }

    private static Long sum(Long left, Long right) {
        return left == null || right == null ? null : Math.addExact(left, right);
    }

    private static BigDecimal sum(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    /**
     * Source totals: impressions, clicks, spent, sales, sold_units and sales_count in ads_adset.
     * Conversion uses attributed sales_count (orders), not sold_units (quantity).
     * Percentages are fractions; stored CTR/CPC/ROAS/ACOS and daily conversion are never averaged.
     */
    private static void writeMetrics(Map<String, Object> values, String prefix, Primitives metrics) {
        var impressions = decimal(metrics.impressions());
        var clicks = decimal(metrics.clicks());
        values.put(prefix + "_impressions", metrics.impressions());
        values.put(prefix + "_clicks", metrics.clicks());
        values.put(prefix + "_spend", metrics.spend());
        values.put(prefix + "_sales", metrics.sales());
        values.put(prefix + "_units", metrics.units());
        values.put(prefix + "_ctr", ratio(clicks, impressions));
        values.put(prefix + "_conversion", ratio(decimal(metrics.salesCount()), clicks));
        values.put(prefix + "_cpc", ratio(metrics.spend(), clicks));
        values.put(prefix + "_roas", ratio(metrics.sales(), metrics.spend()));
        values.put(prefix + "_acos", ratio(metrics.spend(), metrics.sales()));
    }

    /** Each category share divides its summed primitive by the corresponding total ads primitive. */
    private static void writeShares(Map<String, Object> values, String prefix, Primitives category, Primitives total) {
        values.put(prefix + "_impressions_share", ratio(decimal(category.impressions()), decimal(total.impressions())));
        values.put(prefix + "_clicks_share", ratio(decimal(category.clicks()), decimal(total.clicks())));
        values.put(prefix + "_spend_share", ratio(category.spend(), total.spend()));
        values.put(prefix + "_sales_share", ratio(category.sales(), total.sales()));
    }

    private static BigDecimal decimal(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** Missing inputs stay unknown, even with a zero denominator; known zero denominators yield zero. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) return null;
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, MathContext.DECIMAL128);
    }
}
