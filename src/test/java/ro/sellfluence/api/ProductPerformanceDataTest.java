package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.sellfluence.api.ProductPerformanceData.Period.MONTH;
import static ro.sellfluence.api.ProductPerformanceData.Period.WEEK;

class ProductPerformanceDataTest {
    private static final UUID VENDOR = UUID.fromString("2d10b460-8fc9-44b0-9d65-000000000001");
    private static final ProductPerformanceData.Product PRODUCT = new ProductPerformanceData.Product(
            "Vendor", "Actual product", "ACTUALPNK", "https://emag.ro/product_details/pd/ACTUALPNK");

    @Test
    void preservesEveryDashboardColumnKeyLabelTypeAndGroupBoundary() {
        var response = ProductPerformanceData.create(PRODUCT, WEEK, weightedSample());
        assertFalse(response.mock());
        assertEquals(List.of("overview", "total", "auto", "broad", "exact", "product"),
                response.groups().stream().map(ProductPerformanceData.ColumnGroup::key).toList());
        assertEquals(List.of(10, 13, 15, 15, 15, 15),
                response.groups().stream().map(group -> group.columns().size()).toList());
        assertEquals(List.of("Week", "Stock", "GMV 30", "Clicks", "Sales Price", "Conv.",
                        "Perform.", "Average price", "# rev.", "rating"),
                response.groups().getFirst().columns().stream().map(ProductPerformanceData.Column::label).toList());
        assertEquals(List.of("date", "integer", "integer", "integer", "decimal", "percent", "text", "decimal", "integer", "decimal"),
                response.groups().getFirst().columns().stream().map(ProductPerformanceData.Column::type).toList());
        var commonAdsLabels = List.of("Imp.", "Clicks", "Spend", "Sales", "Total Units Sold",
                "CTR", "Conv.", "CPC", "ROAS", "ACOS", "TACOS");
        for (var group : response.groups().subList(1, 6)) {
            assertEquals(commonAdsLabels, group.columns().subList(0, 11).stream()
                    .map(ProductPerformanceData.Column::label).toList());
            assertEquals(List.of("integer", "integer", "integer", "integer", "integer", "percent", "percent",
                            "decimal", "decimal", "percent", "percent"),
                    group.columns().subList(0, 11).stream().map(ProductPerformanceData.Column::type).toList());
        }
        assertEquals(List.of("% Clicks Ads of Total", "% Sales Ads of Total"),
                response.groups().get(1).columns().subList(11, 13).stream()
                        .map(ProductPerformanceData.Column::label).toList());
        var shareNames = List.of("Auto", "Broad", "Exact", "Prod.");
        for (int groupIndex = 2; groupIndex < 6; groupIndex++) {
            var shareName = shareNames.get(groupIndex - 2);
            assertEquals(List.of("% Imp. " + shareName + " of Tot. Ads", "% Clicks " + shareName + " of Tot. Ads",
                            "% Spend " + shareName + " of Tot. Ads", "% Sales " + shareName + " of Tot. Ads"),
                    response.groups().get(groupIndex).columns().subList(11, 15).stream()
                            .map(ProductPerformanceData.Column::label).toList());
        }
        var keys = response.groups().stream().flatMap(group -> group.columns().stream())
                .map(ProductPerformanceData.Column::key).toList();
        assertEquals(83, keys.size());
        assertEquals(83, new HashSet<>(keys).size());
        var values = response.rows().getFirst().values();
        assertEquals(new HashSet<>(keys), values.keySet());
        assertEquals(66, values.values().stream().filter(Number.class::isInstance).count());
        response.groups().getFirst().columns().stream().filter(column -> !"week".equals(column.key()))
                .forEach(column -> assertNull(values.get(column.key()), column.key()));
        for (var group : List.of("total", "auto", "broad", "exact", "product")) {
            assertNull(values.get(group + "_tacos"));
        }
        assertNull(values.get("total_clicks_share"));
        assertNull(values.get("total_sales_share"));
    }

    @Test
    void sumsSourceMetricsAndDerivesWeightedRatesAndSharesFromTotals() {
        var response = ProductPerformanceData.create(PRODUCT, WEEK, weightedSample());
        assertTrue(response.errors().isEmpty());
        var values = response.rows().getFirst().values();
        assertMetrics(values, "auto", 1000, 40, "10", "100", 12, ".04", ".15", ".25", "10", ".1");
        assertMetrics(values, "broad", 500, 25, "20", "100", 12, ".05", ".2", ".8", "5", ".2");
        assertMetrics(values, "exact", 200, 5, "5", "20", 2, ".025", ".2", "1", "4", ".25");
        assertMetrics(values, "product", 300, 30, "15", "80", 9, ".1", ".1", ".5", fraction(16, 3), ".1875");
        assertMetrics(values, "total", 2000, 100, "50", "300", 35, ".05", ".15", ".5", "6", fraction(1, 6));
        assertShares(values, "auto", ".5", ".4", ".2", fraction(1, 3));
        assertShares(values, "broad", ".25", ".25", ".4", fraction(1, 3));
        assertShares(values, "exact", ".1", ".05", ".1", fraction(1, 15));
        assertShares(values, "product", ".15", ".3", ".3", fraction(4, 15));
    }

    @Test
    void retainsSourceDecimalPrecisionAndSerializesNumbersAndUnavailableCells() {
        var source = metrics(3, 2, "0.123456789012345678901234567890123456789", "1.00000000000000000001", 5, 1);
        var response = ProductPerformanceData.create(PRODUCT, WEEK,
                List.of(snapshot("2026-09-08", "auto", List.of(), source)));
        var values = response.rows().getFirst().values();
        assertEquals(source.spend(), values.get("auto_spend"));
        assertEquals(source.sales(), values.get("total_sales"));
        assertNumber(values, "auto_ctr", fraction(2, 3));
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertFalse(json.get("mock").asBoolean());
        assertEquals("Actual product", json.get("product").get("name").asString());
        assertEquals(0, json.get("errors").size());
        var jsonValues = json.get("rows").get(0).get("values");
        assertTrue(jsonValues.get("auto_impressions").isIntegralNumber());
        assertTrue(jsonValues.get("auto_spend").isNumber());
        assertTrue(jsonValues.get("auto_ctr").isNumber());
        assertEquals("2026-09-07", jsonValues.get("week").asString());
        assertTrue(jsonValues.get("rating").isNull());
    }

    @Test
    void missingPrimitivesPropagateToOnlyDependentAggregatesAndRates() {
        var missing = new Primitives(100L, 10L, null, new BigDecimal("50"), 4L, null);
        var response = ProductPerformanceData.create(PRODUCT, WEEK, List.of(
                snapshot("2026-09-08", "auto", List.of(), missing),
                snapshot("2026-09-09", "auto", List.of(), metrics(100, 10, "10", "50", 4, 3)),
                snapshot("2026-09-08", "products", List.of(), metrics(200, 20, "20", "100", 8, 6))));
        var values = response.rows().getFirst().values();
        for (var prefix : List.of("auto", "total")) {
            for (var suffix : List.of("spend", "conversion", "cpc", "roas", "acos")) {
                assertNull(values.get(prefix + "_" + suffix), prefix + "_" + suffix);
            }
        }
        for (var prefix : List.of("auto", "broad", "exact", "product")) {
            assertNull(values.get(prefix + "_spend_share"));
        }
        assertEquals(400L, values.get("total_impressions"));
        assertEquals(40L, values.get("total_clicks"));
        assertNumber(values, "total_sales", "200");
        assertEquals(16L, values.get("total_units"));
        assertNumber(values, "total_ctr", ".1");
        assertNumber(values, "product_cpc", "1");
        assertNumber(values, "product_conversion", ".3");
        assertNumber(values, "auto_sales_share", ".5");
    }

    @Test
    void missingCountsAndSalesStayUnknownAndDoNotEraseIndependentValues() {
        var missing = new Primitives(null, null, new BigDecimal("10"), null, null, 1L);
        var values = ProductPerformanceData.create(PRODUCT, WEEK,
                List.of(snapshot("2026-09-08", "auto", List.of(), missing))).rows().getFirst().values();
        for (var suffix : List.of("impressions", "clicks", "sales", "units", "ctr", "conversion", "cpc", "roas", "acos")) {
            assertNull(values.get("auto_" + suffix), suffix);
            assertNull(values.get("total_" + suffix), suffix);
        }
        assertNumber(values, "total_spend", "10");
        assertNumber(values, "auto_spend_share", "1");
        assertNull(values.get("product_clicks_share"));
        assertNumber(values, "product_clicks", "0");
        assertNumber(values, "product_cpc", "0");
    }

    @Test
    void zeroDenominatorsAndEmptyCategoriesYieldZeroWhileMissingInputsRemainUnknown() {
        var values = ProductPerformanceData.create(PRODUCT, WEEK,
                List.of(snapshot("2026-09-08", "auto", List.of(), metrics(0, 0, "0", "0", 0, 0))))
                .rows().getFirst().values();
        assertEquals(66, values.values().stream().filter(Number.class::isInstance).count());
        values.forEach((key, value) -> {
            if (value instanceof Number) assertNumber(values, key, "0");
        });
        var missing = new Primitives(0L, null, new BigDecimal("10"), BigDecimal.ZERO, 1L, null);
        var unknown = ProductPerformanceData.create(PRODUCT, WEEK,
                List.of(snapshot("2026-09-08", "auto", List.of(), missing))).rows().getFirst().values();
        assertNull(unknown.get("auto_ctr"));
        assertNull(unknown.get("auto_conversion"));
        assertNull(unknown.get("auto_cpc"));
        assertNumber(unknown, "auto_roas", "0");
        assertNumber(unknown, "auto_acos", "0");
    }

    @Test
    void classifiesDistinctKeywordTypesAndIgnoresNegativeEntries() {
        var response = ProductPerformanceData.create(PRODUCT, WEEK, List.of(
                snapshot("2026-09-08", "keywords", List.of("negative", "broad", "broad"), metrics(1, 1, "2", "3", 4, 1)),
                snapshot("2026-09-08", "keywords", List.of("exact", "negative", "exact"), metrics(5, 5, "6", "7", 8, 2))));
        assertTrue(response.errors().isEmpty());
        assertEquals(1L, response.rows().getFirst().values().get("broad_impressions"));
        assertEquals(5L, response.rows().getFirst().values().get("exact_impressions"));
        assertEquals(6L, response.rows().getFirst().values().get("total_impressions"));
    }

    @Test
    void invalidKeywordClassificationInvalidatesBothKeywordSectionsTotalsAndEveryShareForItsPeriod() {
        for (var types : List.of(List.<String>of(), List.of("negative"), List.of("broad", "exact", "negative"),
                List.of("phrase"), List.of("(missing)"), List.of("broad", "phrase"))) {
            var bad = snapshot("2026-09-08", "keywords", types, metrics(100, 10, "10", "50", 4, 2));
            var response = ProductPerformanceData.create(PRODUCT, WEEK, List.of(
                    bad,
                    snapshot("2026-09-08", "auto", List.of(), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-08", "products", List.of(), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-08", "keywords", List.of("broad"), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-14", "keywords", List.of("exact"), metrics(100, 10, "10", "50", 4, 2))));
            var affected = response.rows().getFirst().values();
            affected.forEach((key, value) -> {
                if (key.startsWith("broad_") || key.startsWith("exact_") || key.startsWith("total_") || key.endsWith("_share")) {
                    assertNull(value, key + " for " + types);
                }
            });
            assertEquals(100L, affected.get("auto_impressions"));
            assertNumber(affected, "product_sales", "50");
            assertEquals(100L, response.rows().getLast().values().get("total_impressions"));
            assertEquals(1, response.errors().size());
            var error = response.errors().getFirst();
            assertEquals("INVALID_KEYWORD_MATCH_TYPE", error.code());
            assertEquals(VENDOR, error.vendorId());
            assertEquals(bad.key().campaignId(), error.campaignId());
            assertEquals(bad.key().adsetId(), error.adsetId());
            assertEquals(LocalDate.of(2026, 9, 8), error.reportDate());
            assertEquals(types.stream().filter(type -> !"negative".equals(type)).distinct().toList(), error.matchTypes());
            assertTrue(error.message().contains("broad or exact"));
            var mapper = new ObjectMapper();
            var json = mapper.readTree(mapper.writeValueAsString(response));
            assertEquals("2026-09-08", json.get("errors").get(0).get("reportDate").asString());
        }
    }

    @Test
    void ambiguousAttributionInvalidatesOnlyItsCategoryAndAllTotalsAndShares() {
        var normal = snapshot("2026-09-08", "keywords", List.of("broad"), metrics(100, 10, "10", "50", 4, 2));
        var ambiguous = new DailyAdset(normal.key(), normal.targeting(), normal.matchTypes(),
                List.of("ACTUALPNK", "OTHERPNK"), normal.metrics());
        var response = ProductPerformanceData.create(PRODUCT, WEEK, List.of(ambiguous,
                snapshot("2026-09-08", "keywords", List.of("exact"), metrics(100, 10, "10", "50", 4, 2))));
        var affected = response.rows().getFirst().values();
        assertNull(affected.get("broad_impressions"));
        assertNull(affected.get("total_impressions"));
        assertNull(affected.get("exact_impressions_share"));
        assertEquals(100L, affected.get("exact_impressions"));
        assertEquals(0L, affected.get("auto_impressions"));
        assertEquals("AMBIGUOUS_PRODUCT_ATTRIBUTION", response.errors().getFirst().code());
        assertTrue(response.errors().getFirst().message().contains("OTHERPNK"));
    }

    @Test
    void unknownTargetingProducesAnErrorAndInvalidatesAllAdvertisingSections() {
        for (var targeting : new String[]{"unsupported", null}) {
            var response = ProductPerformanceData.create(PRODUCT, WEEK, List.of(
                    snapshot("2026-09-08", targeting, List.of(), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-08", "auto", List.of(), metrics(100, 10, "10", "50", 4, 2))));
            assertEquals("UNKNOWN_TARGETING", response.errors().getFirst().code());
            response.rows().getFirst().values().forEach((key, value) -> {
                if (!"week".equals(key)) assertNull(value, key);
            });
        }
    }

    @Test
    void groupsMondayThroughSundayAndCalendarMonthsWithoutManufacturingUnobservedPeriods() {
        var snapshots = List.of("2026-02-01", "2026-01-05", "2025-12-31", "2026-01-04", "2026-01-01").stream()
                .map(date -> snapshot(date, "auto", List.of(), metrics(1, 1, "1", "1", 1, 1))).toList();
        var weeks = ProductPerformanceData.create(PRODUCT, WEEK, snapshots).rows();
        assertEquals(List.of("2025-12-29", "2026-01-05", "2026-01-26"),
                weeks.stream().map(row -> row.values().get("week")).toList());
        assertEquals(List.of(3L, 1L, 1L), weeks.stream().map(row -> row.values().get("total_impressions")).toList());
        var months = ProductPerformanceData.create(PRODUCT, MONTH, snapshots).rows();
        assertEquals(List.of("2025-12-01", "2026-01-01", "2026-02-01"),
                months.stream().map(row -> row.values().get("week")).toList());
        assertEquals(List.of(1L, 3L, 1L), months.stream().map(row -> row.values().get("total_impressions")).toList());
    }

    @Test
    void monthlyAggregationUsesDailyPrimitivesRatherThanAveragingWeeklyRates() {
        var snapshots = List.of(
                snapshot("2026-09-01", "auto", List.of(), metrics(100, 10, "1", "10", 1, 1)),
                snapshot("2026-09-14", "auto", List.of(), metrics(900, 30, "9", "90", 9, 3)));
        var values = ProductPerformanceData.create(PRODUCT, MONTH, snapshots).rows().getFirst().values();
        assertEquals("2026-09-01", values.get("week"));
        assertNumber(values, "auto_ctr", ".04");
        assertNumber(values, "auto_cpc", ".25");
        assertEquals(1000L, values.get("total_impressions"));
    }

    @Test
    void supportsEmptyHistoryAndValidatesPeriodInputs() {
        for (var period : List.of(WEEK, MONTH)) {
            var response = ProductPerformanceData.create(PRODUCT, period, List.of());
            assertEquals(PRODUCT, response.product());
            assertTrue(response.rows().isEmpty());
            assertTrue(response.errors().isEmpty());
            assertFalse(response.mock());
        }
        assertEquals(WEEK, ProductPerformanceData.Period.parse(null));
        assertEquals(WEEK, ProductPerformanceData.Period.parse("week"));
        assertEquals(MONTH, ProductPerformanceData.Period.parse("month"));
        for (var value : List.of("", " ", "Week", "quarter", " month ")) {
            assertThrows(IllegalArgumentException.class, () -> ProductPerformanceData.Period.parse(value));
        }
        // Invalid selector input is rejected before any database access.
        var api = new API(null);
        assertTrue(assertNoDatabaseLookup(api, null, "code"));
        assertTrue(assertNoDatabaseLookup(api, VENDOR, null));
        assertTrue(assertNoDatabaseLookup(api, VENDOR, " "));
    }

    private static boolean assertNoDatabaseLookup(API api, UUID vendorId, String code) {
        try {
            return api.getProductPerformance(vendorId, code, WEEK).isEmpty();
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static List<DailyAdset> weightedSample() {
        return List.of(
                snapshot("2026-09-07", "auto", List.of(), metrics(100, 10, "3.125", "25", 4, 2)),
                snapshot("2026-09-08", "auto", List.of(), metrics(900, 30, "6.875", "75", 8, 4)),
                snapshot("2026-09-09", "keywords", List.of("broad", "negative"), metrics(500, 25, "20", "100", 12, 5)),
                snapshot("2026-09-10", "keywords", List.of("exact"), metrics(200, 5, "5", "20", 2, 1)),
                snapshot("2026-09-13", "products", List.of(), metrics(300, 30, "15", "80", 9, 3)));
    }

    private static Primitives metrics(long impressions, long clicks, String spend, String sales, long units, long salesCount) {
        return new Primitives(impressions, clicks, new BigDecimal(spend), new BigDecimal(sales), units, salesCount);
    }

    private static DailyAdset snapshot(String date, String targeting, List<String> matchTypes, Primitives metrics) {
        return new DailyAdset(new AdsAdsetKey(VENDOR, LocalDate.parse(date), 17, 23), targeting,
                matchTypes, List.of("ACTUALPNK"), metrics);
    }

    private static void assertMetrics(Map<String, Object> values, String prefix, long impressions, long clicks,
                                      String spend, String sales, long units, String ctr, String conversion,
                                      String cpc, String roas, String acos) {
        assertEquals(impressions, values.get(prefix + "_impressions"));
        assertEquals(clicks, values.get(prefix + "_clicks"));
        assertNumber(values, prefix + "_spend", spend);
        assertNumber(values, prefix + "_sales", sales);
        assertEquals(units, values.get(prefix + "_units"));
        assertNumber(values, prefix + "_ctr", ctr);
        assertNumber(values, prefix + "_conversion", conversion);
        assertNumber(values, prefix + "_cpc", cpc);
        assertNumber(values, prefix + "_roas", roas);
        assertNumber(values, prefix + "_acos", acos);
    }

    private static void assertShares(Map<String, Object> values, String prefix, String impressions, String clicks,
                                     String spend, String sales) {
        assertNumber(values, prefix + "_impressions_share", impressions);
        assertNumber(values, prefix + "_clicks_share", clicks);
        assertNumber(values, prefix + "_spend_share", spend);
        assertNumber(values, prefix + "_sales_share", sales);
    }

    private static String fraction(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128).toString();
    }

    private static void assertNumber(Map<String, Object> values, String key, String expected) {
        assertTrue(values.get(key) instanceof Number, key + " must be numeric");
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(values.get(key).toString())), key);
    }
}
