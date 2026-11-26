package ro.sellfluence.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import ro.sellfluence.db.ProductPerformanceSource.ReviewDay;
import ro.sellfluence.db.ProductPerformanceSource.ReviewHistory;
import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;
import ro.sellfluence.db.OffersTable.DailyProductOffer;
import ro.sellfluence.db.OffersTable.ProductHotness;
import ro.sellfluence.db.OffersTable.ProductVisits;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.math.RoundingMode.HALF_EVEN;

/**
 * Product and advertising performance from daily adsets/offers, net sales, gross GMV, and Order Table quantities.
 *
 * <p>The database supplies vendor/product attribution and same-day active campaign/adset filtering.
 * This class classifies each adset snapshot, sums only the six advertising source primitives,
 * and derives all rates from those sums. Stored daily rates are deliberately unused: averaging them would weight a
 * one-click adset as heavily as a thousand-click adset. See doc/ProductPerformance.md
 * for the complete data selection and calculation rules.</p>
 */
public final class ProductPerformanceData {
    static final String OFFERS_SNAPSHOT_SOURCE = "eMAG offers snapshot";
    private static final Primitives ZERO = new Primitives(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L);

    private static final List<ColumnGroup> GROUPS = List.of(
            new ColumnGroup("overview", "Product metrics", List.of(
                    column("period", "Period", "date"),
                    column("stock", "Stock", "integer"),
                    column("orders", "Orders", "integer",
                            "Ordered units in the selected eMAG account, using the same quantity rules as Order Table. "
                                    + "Returns are not subtracted from this number."),
                    column("totalSales", "Total Sales (excl. VAT)", "integer",
                            "Order-line sales excluding VAT, with net voucher and storno adjustments. "
                                    + "Includes the product's organic and Ads sales in the selected eMAG account."),
                    column("clicks", "Clicks", "integer"),
                    column("salesPrice", "Sales Price (incl. VAT)", "decimal",
                            "Mean of the available daily offer prices, including VAT, during the selected period. "
                                    + "Each day has equal weight; this is not the average price paid in orders."),
                    column("conversion", "Conv.", "percent",
                            "Orders (units) / Clicks × 100%, for the selected product and eMAG account. "
                                    + "Includes organic and Ads traffic; returns are not subtracted from Orders. "
                                    + "The window is approximate because eMAG does not expose the clicks counter's exact cutoff time."),
                    column("performance", "Perform.", "text",
                            "The eMAG 30-day classification, including for 14-day rows. The report looks for the "
                                    + "period's closing-day snapshot first. Estimates identify their actual observation date."),
                    column("reviews", "Reviews received", "integer",
                            "Number of reviews eMAG published for this product variant during the displayed dates in Romania. "
                                    + "These reviews are shared by sellers of the same variant."),
                    column("rating", "Average rating", "decimal",
                            "Average of the individual 1–5 star ratings on reviews published for this variant "
                                    + "during the displayed dates. A dash means no rated reviews or unavailable data.")
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
        FOURTEEN_DAYS(14), THIRTY_DAYS(30);

        private static final LocalDate FOURTEEN_DAY_ANCHOR = LocalDate.of(2026, 11, 2);
        private final int windowDays;

        Period(int windowDays) {
            this.windowDays = windowDays;
        }

        public int windowDays() {
            return windowDays;
        }

        public static Period parse(String value) {
            if (value == null) return FOURTEEN_DAYS;
            return switch (value) {
                case "14days", "week" -> FOURTEEN_DAYS;
                case "30days", "month" -> THIRTY_DAYS;
                default -> throw new IllegalArgumentException("period must be 14days or 30days");
            };
        }

        public LocalDate start(LocalDate date) {
            Objects.requireNonNull(date, "date");
            return this == FOURTEEN_DAYS
                    ? FOURTEEN_DAY_ANCHOR.plusDays(Math.floorDiv(ChronoUnit.DAYS.between(FOURTEEN_DAY_ANCHOR, date), 14) * 14)
                    : end(date).minusDays(29);
        }

        public LocalDate end(LocalDate date) {
            Objects.requireNonNull(date, "date");
            return this == FOURTEEN_DAYS ? start(date).plusDays(13) : date.withDayOfMonth(date.lengthOfMonth());
        }
    }

    public record Product(String label, String name, String pnk, String url) {
    }

    /** Percent columns contain fractional numbers, for example 0.0267 means 2.67%. */
    public record Column(String key, String label, String type, String description) {
        public Column(String key, String label, String type) {
            this(key, label, type, null);
        }
    }

    public record ColumnGroup(String key, String label, List<Column> columns) {
    }

    /** Inclusive calculation dates; the raw eMAG visits counter has an unverified source cutoff. */
    public record ReportPeriod(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate startDate,
                               @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate endDate,
                               int windowDays, boolean provisional) {
        public ReportPeriod {
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDate, "endDate");
            if (provisional ? windowDays < 1 || windowDays > 31 : windowDays != 14 && windowDays != 30) {
                throw new IllegalArgumentException("Invalid windowDays for the report period");
            }
            if (ChronoUnit.DAYS.between(startDate, endDate) != windowDays - 1L) {
                throw new IllegalArgumentException("report period must contain exactly " + windowDays + " days");
            }
        }

        private boolean contains(LocalDate date) {
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    public record Row(ReportPeriod period, Map<String, Object> values,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate visitsSnapshotDate,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant visitsCapturedAt,
                      String visitsStatus, String visitsReason,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate performanceSnapshotDate,
                      String performanceStatus, String performanceReason, String performanceType,
                      Map<String, CellProvenance> cellProvenance,
                      @JsonInclude(JsonInclude.Include.NON_NULL)
                      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant reviewFetchedAt) {
        public Row {
            cellProvenance = Map.copyOf(cellProvenance);
        }

        public Row(ReportPeriod period, Map<String, Object> values, LocalDate visitsSnapshotDate,
                   Instant visitsCapturedAt, String visitsStatus, String visitsReason,
                   LocalDate performanceSnapshotDate, String performanceStatus, String performanceReason,
                   String performanceType, Map<String, CellProvenance> cellProvenance) {
            this(period, values, visitsSnapshotDate, visitsCapturedAt, visitsStatus, visitsReason,
                    performanceSnapshotDate, performanceStatus, performanceReason, performanceType, cellProvenance, null);
        }

        public Row(ReportPeriod period, Map<String, Object> values, LocalDate visitsSnapshotDate,
                   Instant visitsCapturedAt, String visitsStatus, String visitsReason,
                   LocalDate performanceSnapshotDate, String performanceStatus, String performanceReason,
                   String performanceType) {
            this(period, values, visitsSnapshotDate, visitsCapturedAt, visitsStatus, visitsReason,
                    performanceSnapshotDate, performanceStatus, performanceReason, performanceType, Map.of());
        }
    }

    /** Quality applies to a cell, including ratios derived from imported or partial inputs. */
    public record CellProvenance(String quality, String source,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate sourceDate,
                                 Integer sourceWindowDays, String method, String sourceUrl) {
    }

    public record DataError(String code, UUID vendorId, int campaignId, int adsetId, LocalDate reportDate,
                            List<String> matchTypes, String message) {
    }

    public record Response(boolean mock, Product product, List<ColumnGroup> groups, List<Row> rows,
                           List<DataError> errors) {
    }

    /**
     * Aggregate every explicit window independently: 30-day month-end windows can overlap.
     * Gross GMV, net sales and ordered units are keyed by start date; raw rolling visits are keyed by end date.
     * A scheduled interval creates a row even when it has no advertising observations.
     */
    public static Response create(Product product, List<ReportPeriod> periods, List<DailyAdset> snapshots,
                                  List<DailyProductOffer> offers, Map<LocalDate, BigDecimal> gmvByPeriod,
                                  Map<LocalDate, Long> ordersByPeriod, Map<LocalDate, ProductVisits> visitsByEndDate,
                                  Map<LocalDate, BigDecimal> totalSalesByPeriod) {
        return create(product, periods, snapshots, offers, gmvByPeriod, ordersByPeriod, visitsByEndDate,
                totalSalesByPeriod, Map.of());
    }

    public static Response create(Product product, List<ReportPeriod> periods, List<DailyAdset> snapshots,
                                  List<DailyProductOffer> offers, Map<LocalDate, BigDecimal> gmvByPeriod,
                                  Map<LocalDate, Long> ordersByPeriod, Map<LocalDate, ProductVisits> visitsByEndDate,
                                  Map<LocalDate, BigDecimal> totalSalesByPeriod,
                                  Map<LocalDate, ProductHotness> hotnessByEndDate) {
        return createNativeReport(product, periods, snapshots, offers, gmvByPeriod, ordersByPeriod, visitsByEndDate,
                totalSalesByPeriod, hotnessByEndDate, Set.of(), ReviewHistory.empty());
    }

    static Response createNativeReport(Product product, List<ReportPeriod> periods, List<DailyAdset> snapshots,
                                           List<DailyProductOffer> offers, Map<LocalDate, BigDecimal> gmvByPeriod,
                                           Map<LocalDate, Long> ordersByPeriod,
                                           Map<LocalDate, ProductVisits> visitsByDate,
                                           Map<LocalDate, BigDecimal> totalSalesByPeriod,
                                           Map<LocalDate, ProductHotness> hotnessByDate,
                                           Set<LocalDate> missedCloseFallbackDates, ReviewHistory reviews) {
        var errors = new ArrayList<DataError>();
        var rows = new ArrayList<Row>();
        for (var period : periods) {
            var totals = new PeriodTotals();
            boolean hasAds = false;
            for (var snapshot : snapshots) {
                if (period.contains(snapshot.key().reportDate())) {
                    hasAds = true;
                    totals.add(snapshot, errors);
                }
            }
            var offerTotals = new OfferTotals();
            for (var offer : offers) {
                if (period.contains(offer.fetchDate())) offerTotals.add(offer);
            }
            var closing = closingMetrics(period, visitsByDate, hotnessByDate, missedCloseFallbackDates);
            var row = totals.row(period, offerTotals, hasAds, gmvByPeriod.get(period.startDate()),
                    ordersByPeriod.get(period.startDate()), totalSalesByPeriod.get(period.startDate()), closing, reviews);
            rows.add(row);
        }
        return new Response(false, product, GROUPS, List.copyOf(rows), List.copyOf(new LinkedHashSet<>(errors)));
    }

    static Response omitEmptyPreview(Response report) {
        if (report.rows().isEmpty()) return report;
        var preview = report.rows().getLast();
        if (!preview.period().provisional() || hasObservedMetrics(preview)) {
            return report;
        }
        var closedRows = List.copyOf(report.rows().subList(0, report.rows().size() - 1));
        var errors = report.errors().stream()
                .filter(error -> closedRows.stream().anyMatch(row -> row.period().contains(error.reportDate())))
                .toList();
        return new Response(report.mock(), report.product(), report.groups(), closedRows, errors);
    }

    private static boolean hasObservedMetrics(Row row) {
        return GROUPS.stream().flatMap(group -> group.columns().stream())
                .anyMatch(column -> row.values().get(column.key()) instanceof Number number
                        && (!"reviews".equals(column.key()) || number.longValue() > 0));
    }

    /** Stock uses the latest eligible day; both windows give each available daily gross price one weight. */
    private static final class OfferTotals {
        private DailyProductOffer latest;
        private BigDecimal priceSum = BigDecimal.ZERO;
        private long priceDays;
        private boolean ambiguousPrice;

        private void add(DailyProductOffer offer) {
            if (latest == null || !latest.fetchDate().isAfter(offer.fetchDate())) latest = offer;
            if (offer.matchingOffers() > 1) {
                ambiguousPrice = true;
            } else if (offer.matchingOffers() == 1 && offer.salesPrice() != null) {
                priceSum = priceSum.add(offer.salesPrice().setScale(2, HALF_EVEN));
                priceDays++;
            }
        }

        private Object salesPrice(boolean provisional) {
            if (ambiguousPrice && !provisional) return "???";
            return priceDays == 0 ? null : priceSum.divide(BigDecimal.valueOf(priceDays), 2, HALF_EVEN);
        }
    }

    private static Column column(String key, String label, String type) {
        return new Column(key, label, type);
    }

    private static Column column(String key, String label, String type, String description) {
        return new Column(key, label, type, description);
    }

    private static List<Column> adsColumns(String group, String shareLabel) {
        var columns = new ArrayList<>(List.of(
                column(group + "_impressions", "Imp.", "integer"),
                column(group + "_clicks", "Clicks", "integer"),
                column(group + "_spend", "Spend (excl. VAT)", "integer",
                        "Consumption of Ads credit excluding VAT, using the confirmed reporting convention. "
                                + "The API does not explicitly identify the tax basis; the original amount is unchanged."),
                column(group + "_sales", "Sales (excl. VAT)", "integer",
                        "Sales attributed to advertising, reported by eMAG excluding VAT. "
                                + "The original amount is retained without any additional tax conversion."),
                column(group + "_units", "Total Units Sold", "integer"),
                column(group + "_ctr", "CTR", "percent"),
                column(group + "_conversion", "Conv.", "percent"),
                column(group + "_cpc", "CPC (excl. VAT)", "decimal",
                        "Spend / Ads clicks. Spend is consumption of Ads credit excluding VAT."),
                column(group + "_roas", "ROAS", "decimal",
                        "Sales / Spend. Both amounts exclude VAT."),
                column(group + "_acos", "ACOS", "percent",
                        "Spend / Sales × 100. Both amounts exclude VAT."),
                column(group + "_tacos", "TACOS", "percent",
                        (shareLabel == null ? "Total Ads Spend" : shareLabel + " category Spend")
                                + " / Total Sales in Product metrics × 100. Both amounts exclude VAT and use the selected eMAG "
                                + "account and the row's period. Total Sales includes organic and Ads sales. "
                                + "— means missing data or zero/negative Total Sales; values above 100% are not capped.")
        ));
        if (shareLabel == null) {
            columns.add(column(group + "_clicks_share", "% Clicks Ads of Total", "percent",
                    "Total Ads Clicks / Clicks in Product metrics × 100, for the selected eMAG account. "
                            + "The eMAG 14/30-day counter window is approximate. — means missing data "
                            + "or zero/negative Clicks; values above 100% are not capped."));
            columns.add(column(group + "_sales_share", "% Sales Ads of Total", "percent",
                    "Total Ads Sales / Total Sales in Product metrics × 100. Both are revenue excluding VAT, "
                            + "for the selected eMAG account and the row's period. Ads attribution and voucher/storno "
                            + "adjustments can differ. — means missing data or zero/negative Total Sales; "
                            + "values above 100% are not capped."));
        } else {
            columns.add(column(group + "_impressions_share", "% Imp. " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_clicks_share", "% Clicks " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_spend_share", "% Spend " + shareLabel + " of Tot. Ads", "percent",
                    "Category Spend / Total Ads Spend, both using Ads credit consumption excluding VAT."));
            columns.add(column(group + "_sales_share", "% Sales " + shareLabel + " of Tot. Ads", "percent",
                    "Category Sales / Total Ads Sales, using eMAG's VAT-exclusive values for both amounts."));
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
        private boolean observedUnclassifiedZero;

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
                // A known zero contribution needs no category and cannot affect totals or shares.
                if (types.isEmpty() && allZero(snapshot.metrics())) {
                    if (snapshot.matchedPnks().size() == 1) observedUnclassifiedZero = true;
                    return null;
                }
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

        private Row row(ReportPeriod period, OfferTotals offers, boolean hasAds, BigDecimal gmv, Long orders,
                        BigDecimal totalSales, ClosingMetrics closing, ReviewHistory reviews) {
            var values = new LinkedHashMap<String, Object>();
            // Unsupported overview columns and metrics with unavailable inputs remain null.
            for (var group : GROUPS) {
                for (var column : group.columns()) values.put(column.key(), null);
            }
            values.put("period", period.startDate().toString());
            values.put("orders", orders);
            values.put("totalSales", totalSales);
            // Keep the legacy gross amount available to API consumers, outside the displayed columns.
            values.put("gmv", gmv);
            if (offers.latest != null) {
                values.put("salesPrice", offers.salesPrice(period.provisional()));
                var offer = offers.latest;
                if (offer.matchingOffers() == 1) {
                    values.put("stock", offer.stock());
                } else if (offer.matchingOffers() > 1 && !period.provisional()) {
                    values.put("stock", "???");
                }
            }
            var visitValue = closing.visits();
            values.put("clicks", visitValue.clicks());
            values.put("conversion", productRatio(decimal(orders), decimal(visitValue.clicks())));
            var performance = closing.performance();
            values.put("performance", performance.label());
            var total = new Accumulator();
            for (var category : Category.values()) {
                if (hasAds && !invalid.contains(category)) {
                    var categoryTotals = categories.get(category);
                    if (!period.provisional() || categoryTotals.hasObservations) {
                        writeMetrics(values, category.key, categoryTotals.value);
                        if (!period.provisional()) {
                            values.put(category.key + "_tacos", productRatio(categoryTotals.value.spend(), totalSales));
                        }
                        total.add(categoryTotals.value);
                    }
                }
            }
            if (hasAds && invalid.isEmpty() && (!period.provisional() || total.hasObservations || observedUnclassifiedZero)) {
                writeMetrics(values, "total", total.value);
                if (!period.provisional()) {
                    values.put("total_tacos", productRatio(total.value.spend(), totalSales));
                    values.put("total_clicks_share", productRatio(decimal(total.value.clicks()), decimal(visitValue.clicks())));
                    values.put("total_sales_share", productRatio(total.value.sales(), totalSales));
                }
                for (var category : Category.values()) {
                    var categoryTotals = categories.get(category);
                    if (!period.provisional() || categoryTotals.hasObservations) {
                        writeShares(values, category.key, categoryTotals.value, total.value);
                    }
                }
            }
            var reviewFetchedAt = writeReviews(values, period, reviews);
            // Map.copyOf rejects nulls, which intentionally represent unavailable cells here.
            return new Row(period, Collections.unmodifiableMap(values), visitValue.snapshotDate(),
                    visitValue.capturedAt(), visitValue.status(), visitValue.reason(),
                    performance.snapshotDate(), performance.status(), performance.reason(), performance.type(),
                    closing.provenance(), reviewFetchedAt);
        }
    }

    /** A closed date needs a later collection. Today's values cover only the time up to today's fetch. */
    private static Instant writeReviews(Map<String, Object> values, ReportPeriod period, ReviewHistory reviews) {
        var fetchedOn = reviews.lastSuccessfulFetchDate();
        if (fetchedOn == null || (period.provisional()
                ? !fetchedOn.equals(period.endDate()) : !fetchedOn.isAfter(period.endDate()))) return null;

        var total = new ReviewDay(0, 0, 0);
        for (var date = period.startDate(); !date.isAfter(period.endDate()); date = date.plusDays(1)) {
            var day = reviews.days().get(date);
            if (day != null) total = total.plus(day);
        }
        values.put("reviews", total.count());
        if (total.ratedCount() > 0) {
            values.put("rating", BigDecimal.valueOf(total.starSum())
                    .divide(BigDecimal.valueOf(total.ratedCount()), MathContext.DECIMAL128));
        }
        return period.provisional() ? reviews.lastSuccessfulFetchAt() : null;
    }

    private record HotnessValue(String label, LocalDate snapshotDate, String status, String reason, String type) {
    }

    private record ClosingMetrics(VisitsValue visits, HotnessValue performance, Map<String, CellProvenance> provenance) {}

    private static ClosingMetrics closingMetrics(ReportPeriod period, Map<LocalDate, ProductVisits> visitsByDate,
            Map<LocalDate, ProductHotness> hotnessByDate, Set<LocalDate> missedCloseFallbackDates) {
        if (period.provisional()) {
            return new ClosingMetrics(
                    new VisitsValue(null, null, null, "missing",
                            "eMAG supplies rolling 14- and 30-day visits only. This row shows the current period through today."),
                    new HotnessValue(null, null, "missing",
                            "eMAG reports a rolling 30-day classification.", null), Map.of());
        }
        var end = period.endDate();
        var visits = visitsFor(end, period.windowDays(), visitsByDate.get(end));
        var performance = hotnessFor(end, hotnessByDate.get(end));
        var nextDay = end.plusDays(1);
        // The database readers retain the snapshot date even when this product was missing or ambiguous.
        if (!missedCloseFallbackDates.contains(nextDay)
                || visits.snapshotDate() != null || performance.snapshotDate() != null) {
            return new ClosingMetrics(visits, performance, Map.of());
        }
        var provenance = new LinkedHashMap<String, CellProvenance>();
        var laterVisits = visitsFor(nextDay, period.windowDays(), visitsByDate.get(nextDay));
        if (laterVisits.clicks() != null) {
            String reason = "The rolling " + period.windowDays()
                    + "-day visits counter was captured one day after the missed close.";
            visits = new VisitsValue(laterVisits.clicks(), nextDay, laterVisits.capturedAt(), "estimated", reason);
            provenance.put("clicks", new CellProvenance("estimated", OFFERS_SNAPSHOT_SOURCE, nextDay,
                    period.windowDays(), reason, null));
        }
        var laterPerformance = hotnessFor(nextDay, hotnessByDate.get(nextDay));
        if ("available".equals(laterPerformance.status())) {
            String reason = "The 30-day performance classification was captured one day after the missed close.";
            performance = new HotnessValue(laterPerformance.label(), nextDay, "estimated", reason, laterPerformance.type());
            provenance.put("performance", new CellProvenance("estimated", OFFERS_SNAPSHOT_SOURCE, nextDay,
                    30, reason, null));
        }
        return new ClosingMetrics(visits, performance, provenance);
    }

    private static HotnessValue hotnessFor(LocalDate date, ProductHotness hotness) {
        if (hotness == null || !date.equals(hotness.snapshotDate())) {
            return new HotnessValue(null, hotness == null ? null : hotness.snapshotDate(), "missing",
                    "No hotness snapshot matches this period end date.", null);
        }
        if ("ambiguous".equals(hotness.status())) {
            return new HotnessValue("???", hotness.snapshotDate(), "ambiguous",
                    "Multiple matching offers prevent a reliable hotness classification.", null);
        }
        if (!"available".equals(hotness.status())) {
            return new HotnessValue(null, hotness.snapshotDate(), "missing",
                    "The snapshot has no hotness classification for this product.", null);
        }
        var type = hotness.hotnessType();
        if (type != null && type.isBlank()) type = null;
        var label = hotness.hotnessName();
        if (label == null || label.isBlank()) {
            label = type == null ? null : switch (type) {
                case "super_hot" -> "Super Hot";
                case "hot" -> "Hot";
                case "regular" -> "Regular";
                case "cold" -> "Cold";
                case "super_cold" -> "Super Cold";
                default -> type;
            };
        }
        return new HotnessValue(label, hotness.snapshotDate(), label == null ? "missing" : "available",
                label == null ? "The snapshot has no hotness classification for this product." : null, type);
    }

    private record VisitsValue(Long clicks, LocalDate snapshotDate, Instant capturedAt, String status, String reason) {
    }

    private static VisitsValue visitsFor(LocalDate date, int windowDays, ProductVisits visits) {
        if (visits == null) return new VisitsValue(null, null, null, "missing",
                "No visits snapshot was captured for this period end date.");
        var capturedAt = visits.fetchedAt() == null ? null : visits.fetchedAt().toInstant();
        if (!date.equals(visits.snapshotDate()) || capturedAt == null) {
            return new VisitsValue(null, visits.snapshotDate(), capturedAt, "missing",
                    "No live visits snapshot matches this period end date.");
        }
        if ("ambiguous".equals(visits.status())) {
            return new VisitsValue(null, visits.snapshotDate(), capturedAt, "ambiguous",
                    "Multiple matching offers prevent a reliable visits value.");
        }
        Long clicks = windowDays == 14 ? visits.visits14() : visits.visits30();
        if (!"available".equals(visits.status()) || clicks == null) {
            return new VisitsValue(null, visits.snapshotDate(), capturedAt, "missing",
                    "The snapshot has no visits counter for the last " + windowDays + " days.");
        }
        return new VisitsValue(clicks, visits.snapshotDate(), capturedAt, "available", null);
    }

    private static boolean allZero(Primitives metrics) {
        return metrics.impressions() != null && metrics.impressions() == 0L
                && metrics.clicks() != null && metrics.clicks() == 0L
                && metrics.spend() != null && metrics.spend().signum() == 0
                && metrics.sales() != null && metrics.sales().signum() == 0
                && metrics.units() != null && metrics.units() == 0L
                && metrics.salesCount() != null && metrics.salesCount() == 0L;
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
        private boolean hasObservations;

        private void add(Primitives next) {
            hasObservations = true;
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

    /** Product-relative rates require a known positive base; signed source numerators remain unchanged. */
    private static BigDecimal productRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) return null;
        return numerator.divide(denominator, MathContext.DECIMAL128);
    }

    /** Missing inputs stay unknown, even with a zero denominator; known zero denominators yield zero. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) return null;
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, MathContext.DECIMAL128);
    }
}
