package ro.sellfluence.api;

import ro.sellfluence.api.ProductPerformanceData.CellProvenance;
import ro.sellfluence.api.ProductPerformanceData.ReportPeriod;
import ro.sellfluence.api.ProductPerformanceData.Response;
import ro.sellfluence.api.ProductPerformanceData.Row;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds labelled historical estimates without treating overlapping Last30 observations as daily data. */
final class ProductPerformanceHistory {
    private static final List<String> CATEGORIES = List.of("auto", "broad", "exact", "product");
    private static final List<String> PRIMITIVES = List.of("impressions", "clicks", "spend", "sales", "units", "salesCount");
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private ProductPerformanceHistory() {}

    static Response apply(Response nativeReport, List<Observation> history, Set<LocalDate> adsReportDates) {
        var rows = new ArrayList<Row>();
        var adsKeys = nativeReport.groups().stream().filter(group -> !"overview".equals(group.key()))
                .flatMap(group -> group.columns().stream()).map(ProductPerformanceData.Column::key).toList();
        for (var row : nativeReport.rows()) {
            var values = new LinkedHashMap<>(row.values());
            var provenance = new LinkedHashMap<>(row.cellProvenance());
            var period = row.period();
            var organic = select(history, "organic", period.endDate());
            fill(values, provenance, "stock", organic, false, period,
                    "Stock observed on the source date; not a period average.");
            fill(values, provenance, "salesPrice", organic, false, period,
                    "VAT-inclusive price observed on the source date, used as an estimate of the period price.");
            boolean estimatedClicks = false;
            if (!"ambiguous".equals(row.visitsStatus())) {
                estimatedClicks = fill(values, provenance, "clicks", organic, true, period, null);
            }
            boolean estimatedPerformance = false;
            if (!"ambiguous".equals(row.performanceStatus())) {
                estimatedPerformance = fill(values, provenance, "performance", organic, false, period,
                        "eMAG 30-day classification observed on the source date, including for 14-day rows.");
            }
            fill(values, provenance, "reviews", select(history, "reviews", period.endDate()), false, period,
                    "Historical product-family review count observed on the source date; never summed across products.");
            fill(values, provenance, "rating", select(history, "rating", period.endDate()), false, period,
                    "Historical product-family rating observed on the source date; not an average across dates.");

            long observedDays = period.startDate().datesUntil(period.endDate().plusDays(1))
                    .filter(adsReportDates::contains).count();
            boolean attributionError = nativeReport.errors().stream().anyMatch(error ->
                    !error.reportDate().isBefore(period.startDate()) && !error.reportDate().isAfter(period.endDate()));
            var historicalAds = select(history, "ads", period.endDate());
            if (observedDays < period.windowDays() && historicalAds != null
                    && historicalAds.hasMetricValues() && !attributionError) {
                // Replace the whole block, not just holes in a partial native report.
                for (var key : adsKeys) {
                    values.put(key, null);
                    provenance.remove(key);
                }
                writeAds(values, provenance, historicalAds, period);
            } else if (observedDays < period.windowDays()) {
                var quality = new CellProvenance("partial", "Native eMAG daily reports", period.endDate(),
                        period.windowDays(), "Saved daily Ads report dates: " + observedDays + "/" + period.windowDays()
                        + ". Values cover available observations only; no eligible historical block replaced them.", null);
                for (var key : adsKeys) if (values.get(key) != null) provenance.put(key, quality);
            }

            // Orders and net revenue always remain the native Order Table calculations.
            derive(values, provenance, "conversion", "orders", "clicks", true);
            for (var group : List.of("total", "auto", "broad", "exact", "product")) {
                derive(values, provenance, group + "_tacos", group + "_spend", "totalSales", true);
            }
            derive(values, provenance, "total_clicks_share", "total_clicks", "clicks", true);
            derive(values, provenance, "total_sales_share", "total_sales", "totalSales", true);

            rows.add(new Row(period, Collections.unmodifiableMap(values),
                    estimatedClicks ? organic.observationDate() : row.visitsSnapshotDate(),
                    estimatedClicks ? null : row.visitsCapturedAt(),
                    estimatedClicks ? "estimated" : row.visitsStatus(),
                    estimatedClicks ? provenance.get("clicks").method() : row.visitsReason(),
                    estimatedPerformance ? organic.observationDate() : row.performanceSnapshotDate(),
                    estimatedPerformance ? "estimated" : row.performanceStatus(),
                    estimatedPerformance ? provenance.get("performance").method() : row.performanceReason(),
                    estimatedPerformance ? null : row.performanceType(), provenance));
        }
        return new Response(nativeReport.mock(), nativeReport.product(), nativeReport.groups(), List.copyOf(rows),
                nativeReport.errors());
    }

    private static Observation select(List<Observation> history, String domain, LocalDate end) {
        // Select explicit blanks too: filtering them out would resurrect an older source value.
        return history.stream().filter(observation -> domain.equals(observation.domain())
                        && !observation.observationDate().isAfter(end)
                        && ChronoUnit.DAYS.between(observation.observationDate(), end) <= 7)
                .max(Comparator.comparing(Observation::observationDate)).orElse(null);
    }

    private static boolean fill(Map<String, Object> values, Map<String, CellProvenance> provenance, String key,
                                Observation observation, boolean cumulative, ReportPeriod period, String stateMethod) {
        if (values.get(key) != null || observation == null) return false;
        Object raw = observation.values().get(key);
        if (raw == null || raw instanceof String text && text.isBlank()) return false;
        Object value = cumulative ? scale(raw, period) : raw;
        if (value == null) return false;
        values.put(key, value);
        var quality = source(observation, period, cumulative, stateMethod);
        if ("performance".equals(key)) {
            quality = new CellProvenance(quality.quality(), quality.source(), quality.sourceDate(), 30,
                    quality.method(), quality.sourceUrl());
        }
        provenance.put(key, quality);
        return true;
    }

    private static BigDecimal scale(Object raw, ReportPeriod period) {
        var number = number(raw);
        return number == null ? null : number.multiply(BigDecimal.valueOf(period.windowDays()))
                .divide(BigDecimal.valueOf(30), PRECISION);
    }

    private static CellProvenance source(Observation observation, ReportPeriod period, boolean cumulative, String method) {
        var description = cumulative
                ? period.windowDays() == 14
                    ? "Last30 × 14/30, assuming uniform activity; weekly Last30 observations are not added together."
                    : "Last30 observation used unchanged; weekly Last30 observations are not added together."
                : method;
        var lag = ChronoUnit.DAYS.between(observation.observationDate(), period.endDate());
        description += " Observation is " + lag + " day(s) before the report end.";
        var url = "https://docs.google.com/spreadsheets/d/" + observation.sourceSpreadsheetId()
                + "/edit#gid=" + observation.sourceSheetId() + "&range="
                + URLEncoder.encode(observation.sourceRange(), StandardCharsets.UTF_8);
        return new CellProvenance("estimated", "Google Sheets: " + observation.sourceSheet(),
                observation.observationDate(), cumulative ? 30 : null, description, url);
    }

    private static void writeAds(Map<String, Object> values, Map<String, CellProvenance> provenance,
                                  Observation observation, ReportPeriod period) {
        var original = observation.values();
        var source = source(observation, period, true, null);
        var primitives = new LinkedHashMap<String, Map<String, BigDecimal>>();
        for (var category : CATEGORIES) {
            var metrics = new LinkedHashMap<String, BigDecimal>();
            for (var metric : PRIMITIVES) metrics.put(metric, scale(original.get(category + "_" + metric), period));
            primitives.put(category, metrics);
            writeAdsGroup(values, provenance, category, metrics, source);
        }
        var total = new LinkedHashMap<String, BigDecimal>();
        for (var metric : PRIMITIVES) {
            BigDecimal sum = BigDecimal.ZERO;
            for (var metrics : primitives.values()) {
                var value = metrics.get(metric);
                if (value == null) { sum = null; break; }
                sum = sum.add(value);
            }
            total.put(metric, sum);
        }
        writeAdsGroup(values, provenance, "total", total, source);
        for (var category : CATEGORIES) {
            for (var metric : List.of("impressions", "clicks", "spend", "sales")) {
                derive(values, provenance, category + "_" + metric + "_share",
                        category + "_" + metric, "total_" + metric, false);
            }
        }
    }

    private static void writeAdsGroup(Map<String, Object> values, Map<String, CellProvenance> provenance,
                                       String group, Map<String, BigDecimal> metrics, CellProvenance source) {
        for (var metric : PRIMITIVES) {
            // salesCount is retained as an additional non-visible value for rate provenance.
            var key = group + "_" + metric;
            values.put(key, metrics.get(metric));
            if (metrics.get(metric) != null) provenance.put(key, source);
        }
        derive(values, provenance, group + "_ctr", group + "_clicks", group + "_impressions", false);
        derive(values, provenance, group + "_conversion", group + "_salesCount", group + "_clicks", false);
        derive(values, provenance, group + "_cpc", group + "_spend", group + "_clicks", false);
        derive(values, provenance, group + "_roas", group + "_sales", group + "_spend", false);
        derive(values, provenance, group + "_acos", group + "_spend", group + "_sales", false);
    }

    private static void derive(Map<String, Object> values, Map<String, CellProvenance> provenance,
                               String key, String numeratorKey, String denominatorKey, boolean positiveBase) {
        var numerator = number(values.get(numeratorKey));
        var denominator = number(values.get(denominatorKey));
        var ratio = numerator == null || denominator == null || positiveBase && denominator.signum() <= 0 ? null
                : denominator.signum() == 0 ? BigDecimal.ZERO : numerator.divide(denominator, PRECISION);
        values.put(key, ratio);
        provenance.remove(key);
        if (ratio == null) return;
        var a = provenance.get(numeratorKey);
        var b = provenance.get(denominatorKey);
        var primary = a != null && "estimated".equals(a.quality()) ? a : b != null ? b : a;
        if (primary == null) return;
        var details = "Recalculated as " + numeratorKey + " / " + denominatorKey + ". "
                + primary.method();
        if (a != null && b != null && !a.equals(b)) {
            details += " Both inputs have provenance; see " + numeratorKey + " (" + a.sourceDate() + ") and "
                    + denominatorKey + " (" + b.sourceDate() + ").";
        }
        provenance.put(key, new CellProvenance(primary.quality(), primary.source(), primary.sourceDate(),
                primary.sourceWindowDays(), details, primary.sourceUrl()));
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (!(value instanceof Number number)) return null;
        try { return new BigDecimal(number.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
