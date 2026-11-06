package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.api.ProductPerformanceData.ReportPeriod;
import ro.sellfluence.api.ProductPerformanceData.Response;
import ro.sellfluence.api.ProductPerformanceData.Row;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.OffersTable.DailyProductOffer;
import ro.sellfluence.db.OffersTable.ProductHotness;
import ro.sellfluence.db.OffersTable.ProductVisits;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;
import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProductPerformanceHistoryTest {
    private static final UUID VENDOR = UUID.fromString("2d10b460-8fc9-44b0-9d65-000000000001");
    private static final ProductPerformanceData.Product PRODUCT = new ProductPerformanceData.Product(
            "Product", "Product", "PNK", "https://example.test/product");
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final List<String> CATEGORIES = List.of("auto", "broad", "exact", "product");
    private static final List<String> PRIMITIVES = List.of("impressions", "clicks", "spend", "sales", "units", "salesCount");

    @Test
    void scalesLast30CumulativePrimitivesWithoutRoundingCountsBeforeCalculatingRates() {
        for (int days : List.of(14, 30)) {
            var period = period(days);
            var adsValues = adsValues();
            adsValues.putAll(Map.of("auto_impressions", 100, "auto_clicks", 1, "auto_spend", bd("0.75"),
                    "auto_sales", bd("3.50"), "auto_units", 5, "auto_salesCount", 2));
            var response = ProductPerformanceHistory.apply(nativeReport(period, List.of()), List.of(
                    observation("organic", "2026-05-15", Map.of("clicks", 17)),
                    observation("ads", "2026-05-15", adsValues)), Set.of());
            var row = response.rows().getFirst();
            var values = row.values();
            var factor = bd(Integer.toString(days)).divide(bd("30"), PRECISION);
            assertNumber(values, "auto_clicks", factor);
            assertNumber(values, "auto_units", bd("5").multiply(bd(Integer.toString(days))).divide(bd("30"), PRECISION));
            assertNumber(values, "total_clicks", factor);
            assertNumber(values, "clicks", bd("17").multiply(bd(Integer.toString(days))).divide(bd("30"), PRECISION));
            assertNear(values, "auto_conversion", bd("2"));
            assertNear(values, "auto_ctr", bd("0.01"));
            assertNear(values, "auto_cpc", bd("0.75"));
            assertNear(values, "auto_roas", bd("3.50").divide(bd("0.75"), PRECISION));
            assertNear(values, "auto_acos", bd("0.75").divide(bd("3.50"), PRECISION));
            assertNear(values, "total_clicks_share", bd("1").divide(bd("17"), PRECISION));
            assertNear(values, "conversion", bd("2").divide(number(values.get("clicks")), PRECISION));
            assertEquals("estimated", row.cellProvenance().get("auto_conversion").quality());
            assertEquals(30, row.cellProvenance().get("auto_clicks").sourceWindowDays());
            assertTrue(row.cellProvenance().get("auto_clicks").method().contains(days == 14 ? "14/30" : "unchanged"));
        }
    }

    @Test
    void usesOnlyNearestPreviousSnapshotWithoutAddingOverlappingLast30Values() {
        var period = period(14);
        var row = apply(nativeReport(period, List.of()), List.of(
                observation("organic", "2026-05-08", Map.of("clicks", 900)),
                observation("organic", "2026-05-10", Map.of("clicks", 300)),
                observation("organic", "2026-05-15", Map.of("clicks", 600)),
                observation("organic", "2026-05-18", Map.of("clicks", 9999))), Set.of());
        assertNumber(row.values(), "clicks", bd("280"));
        assertEquals(date("2026-05-15"), row.visitsSnapshotDate());
        assertEquals("estimated", row.visitsStatus());
        assertNull(row.visitsCapturedAt());
        assertEquals(date("2026-05-15"), row.cellProvenance().get("clicks").sourceDate());
        assertTrue(row.visitsReason().contains("2 day(s)"));
        assertTrue(row.visitsReason().contains("not added together"));
    }

    @Test
    void acceptsSevenDayLagButRejectsEightDayGapsAndFutureObservations() {
        var report = nativeReport(period(30), List.of());
        var accepted = apply(report, List.of(observation("organic", "2026-05-10", Map.of("clicks", 300))), Set.of());
        assertNumber(accepted.values(), "clicks", bd("300"));
        assertTrue(accepted.cellProvenance().get("clicks").method().contains("7 day(s)"));
        for (String sourceDate : List.of("2026-05-09", "2026-05-18")) {
            var rejected = apply(report, List.of(observation("organic", sourceDate, Map.of("clicks", 300))), Set.of());
            assertNull(rejected.values().get("clicks"), sourceDate);
            assertEquals("missing", rejected.visitsStatus());
            assertFalse(rejected.cellProvenance().containsKey("clicks"));
        }
    }

    @Test
    void keepsNewestDomainSnapshotCoherentInsteadOfFillingItsUnknownFieldFromAnOlderSnapshot() {
        var period = period(14);
        var newest = new LinkedHashMap<String, Object>();
        newest.put("stock", 8);
        newest.put("clicks", null);
        var row = apply(nativeReport(period, List.of()), List.of(
                observation("organic", "2026-05-10", Map.of("stock", 1, "clicks", 300)),
                observation("organic", "2026-05-15", newest)), Set.of());
        assertNumber(row.values(), "stock", bd("8"));
        assertNull(row.values().get("clicks"));
        assertFalse(row.cellProvenance().containsKey("clicks"));
        assertEquals(date("2026-05-15"), row.cellProvenance().get("stock").sourceDate());
    }

    @Test
    void newestExplicitlyEmptyRatingAndReviewsSuppressOlderObservationsWithinTheSevenDayWindow() {
        for (int days : List.of(14, 30)) {
            var row = apply(nativeReport(period(days), List.of()), List.of(
                    observation("rating", "2026-05-10", Map.of("rating", bd("4.98"))),
                    observation("reviews", "2026-05-10", Map.of("reviews", 200)),
                    observation("rating", "2026-05-15", blankValues("rating")),
                    observation("reviews", "2026-05-15", blankValues("reviews"))), Set.of());
            for (String key : List.of("rating", "reviews")) {
                assertNull(row.values().get(key), "A withdrawn observation must stay unknown: " + key);
                assertFalse(row.cellProvenance().containsKey(key), key);
            }
        }
    }

    @Test
    void explicitOrganicBlanksWithdrawOnlyThoseEstimatesWithoutResurrectingOlderValues() {
        for (int days : List.of(14, 30)) {
            var newest = blankValues("clicks", "salesPrice");
            newest.put("stock", 8);
            newest.put("performance", "");
            var row = apply(nativeReport(period(days), List.of()), List.of(
                    observation("organic", "2026-05-10", Map.of("stock", 1, "clicks", 300,
                            "salesPrice", bd("199.99"), "performance", "SUPER HOT")),
                    observation("organic", "2026-05-15", newest)), Set.of());
            assertNumber(row.values(), "stock", bd("8"));
            assertEquals("estimated", row.cellProvenance().get("stock").quality());
            assertEquals(date("2026-05-15"), row.cellProvenance().get("stock").sourceDate());
            for (String key : List.of("clicks", "salesPrice", "performance", "conversion")) {
                assertNull(row.values().get(key), "Blank history must not become a zero or an older value: " + key);
                assertFalse(row.cellProvenance().containsKey(key), key);
            }
            assertEquals("missing", row.visitsStatus());
            assertEquals("missing", row.performanceStatus());
            assertNumber(row.values(), "orders", bd("2"));
            assertNumber(row.values(), "totalSales", bd("100"));
        }
    }

    @Test
    void allEmptyHistoricalDomainsPreserveNativeMetricsAndFlagAvailableNativeAdsAsPartial() {
        for (int days : List.of(14, 30)) {
            var period = period(days);
            var report = ProductPerformanceData.create(PRODUCT, List.of(period),
                    List.of(ad(period.endDate(), "auto", List.of(), metrics())),
                    List.of(new DailyProductOffer(period.endDate(), 1, 7, bd("199.99"))),
                    Map.of(period.startDate(), bd("121")), Map.of(period.startDate(), 2L),
                    Map.of(period.endDate(), visits(period, 60)), Map.of(period.startDate(), bd("100")),
                    Map.of(period.endDate(), new ProductHotness("available", "hot", "Hot", period.endDate())));
            report = withValues(report, Map.of("reviews", 123, "rating", bd("4.7")));
            var blankAds = adsValues();
            blankAds.replaceAll((key, ignored) -> null);
            var row = apply(report, List.of(
                    observation("organic", "2026-05-10", Map.of("stock", 99, "clicks", 999,
                            "salesPrice", bd("999"), "performance", "SUPER HOT")),
                    observation("ads", "2026-05-10", historicalAds(99)),
                    observation("organic", "2026-05-15", blankValues("stock", "clicks", "salesPrice", "performance")),
                    observation("ads", "2026-05-15", blankAds),
                    observation("rating", "2026-05-15", blankValues("rating")),
                    observation("reviews", "2026-05-15", blankValues("reviews"))), Set.of(period.endDate()));
            for (String key : List.of("stock", "clicks", "salesPrice", "orders", "totalSales", "gmv", "reviews", "rating")) {
                assertEquals(report.rows().getFirst().values().get(key), row.values().get(key), key);
                assertFalse(row.cellProvenance().containsKey(key), key);
            }
            assertEquals("Hot", row.values().get("performance"));
            assertEquals("available", row.visitsStatus());
            assertEquals("available", row.performanceStatus());
            assertFalse(row.cellProvenance().containsKey("performance"));
            assertNumber(row.values(), "auto_clicks", bd("10"));
            assertNumber(row.values(), "total_spend", bd("1"));
            assertNumber(row.values(), "total_sales", bd("20"));
            assertNear(row.values(), "total_clicks_share", bd("10").divide(bd("60"), PRECISION));
            for (String key : List.of("auto_clicks", "total_spend", "total_sales", "total_tacos", "total_clicks_share")) {
                assertEquals("partial", row.cellProvenance().get(key).quality(), key);
                assertTrue(row.cellProvenance().get(key).method().contains("1/" + days), key);
            }
        }
    }

    @Test
    void measuredHistoricalZeroRemainsEligibleAndReplacesPartialNativeAdsInsteadOfBeingTreatedAsEmpty() {
        for (int days : List.of(14, 30)) {
            var period = period(days);
            var report = nativeReport(period, List.of(ad(period.endDate(), "auto", List.of(), metrics())));
            var row = apply(report, List.of(
                    observation("organic", "2026-05-15", Map.of("stock", 0, "clicks", 0,
                            "salesPrice", BigDecimal.ZERO)),
                    observation("ads", "2026-05-15", adsValues()),
                    observation("rating", "2026-05-15", Map.of("rating", BigDecimal.ZERO)),
                    observation("reviews", "2026-05-15", Map.of("reviews", 0))), Set.of(period.endDate()));
            for (String key : List.of("stock", "clicks", "salesPrice", "rating", "reviews", "auto_clicks", "total_spend", "total_sales")) {
                assertNumber(row.values(), key, BigDecimal.ZERO);
                assertEquals("estimated", row.cellProvenance().get(key).quality(), key);
            }
            assertEquals("estimated", row.visitsStatus());
            assertNull(row.values().get("conversion"), "Zero visits cannot produce a conversion percentage");
            assertNumber(row.values(), "orders", bd("2"));
            assertNumber(row.values(), "totalSales", bd("100"));
        }
    }

    @Test
    void measuredNativeZeroAndNativeClassificationTakePrecedenceOverHistoricalOrganicValues() {
        var period = period(14);
        var visits = visits(period, 0L);
        var hotness = new ProductHotness("available", "super_cold", "Super Cold", period.endDate());
        var report = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(),
                List.of(new DailyProductOffer(period.endDate(), 1, 0, BigDecimal.ZERO)),
                Map.of(period.startDate(), BigDecimal.ZERO), Map.of(period.startDate(), 0L),
                Map.of(period.endDate(), visits), Map.of(period.startDate(), BigDecimal.ZERO),
                Map.of(period.endDate(), hotness));
        report = withValues(report, Map.of("reviews", 0, "rating", BigDecimal.ZERO));
        var row = apply(report, List.of(
                observation("organic", "2026-05-15", Map.of("stock", 99, "clicks", 999,
                        "salesPrice", bd("199.99"), "performance", "SUPER HOT")),
                observation("reviews", "2026-05-15", Map.of("reviews", 1000)),
                observation("rating", "2026-05-15", Map.of("rating", bd("4.99")))), Set.of());
        for (String key : List.of("stock", "orders", "gmv", "totalSales", "clicks", "salesPrice", "reviews", "rating")) {
            assertNumber(row.values(), key, BigDecimal.ZERO);
            assertFalse(row.cellProvenance().containsKey(key));
        }
        assertEquals("Super Cold", row.values().get("performance"));
        assertEquals("available", row.performanceStatus());
        assertEquals("super_cold", row.performanceType());
        assertEquals("available", row.visitsStatus());
        assertEquals(visits.fetchedAt().toInstant(), row.visitsCapturedAt());
        assertNull(row.values().get("conversion"));
        assertTrue(row.cellProvenance().isEmpty());
    }

    @Test
    void historicalOrganicDoesNotHideAmbiguousNativeOffers() {
        var period = period(14);
        var report = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(),
                List.of(new DailyProductOffer(period.endDate(), 2, null, null)), Map.of(), Map.of(),
                Map.of(period.endDate(), new ProductVisits("ambiguous", null, null, period.endDate(),
                        OffsetDateTime.parse("2026-05-17T21:00:00Z"), false)), Map.of(),
                Map.of(period.endDate(), new ProductHotness("ambiguous", null, null, period.endDate())));
        var row = apply(report, List.of(observation("organic", "2026-05-15",
                Map.of("stock", 8, "salesPrice", bd("199.99"), "clicks", 300, "performance", "SUPER HOT"))), Set.of());
        assertEquals("???", row.values().get("stock"));
        assertEquals("???", row.values().get("salesPrice"));
        assertNull(row.values().get("clicks"));
        assertEquals("???", row.values().get("performance"));
        assertEquals("ambiguous", row.visitsStatus());
        assertEquals("ambiguous", row.performanceStatus());
        assertTrue(row.cellProvenance().isEmpty());
    }

    @Test
    void replacesAnIncompleteNativeAdsBlockWholeWithoutCombiningItsCategoriesOrPrimitives() {
        var period = period(30);
        var nativeReport = nativeReport(period, List.of(ad(period.endDate(), "auto", List.of(),
                new Primitives(900L, 90L, bd("90"), bd("900"), 9L, 8L))));
        var history = adsValues();
        history.putAll(Map.of("broad_impressions", 100, "broad_clicks", 10, "broad_spend", bd("2"),
                "broad_sales", bd("20"), "broad_units", 4, "broad_salesCount", 3));
        history.put("auto_clicks", null);
        var row = apply(nativeReport, List.of(observation("ads", "2026-05-15", history)), Set.of(period.endDate()));
        assertNull(row.values().get("auto_clicks"), "Missing history must not retain the native 90 clicks");
        assertNumber(row.values(), "auto_spend", BigDecimal.ZERO);
        assertNumber(row.values(), "broad_clicks", bd("10"));
        assertNumber(row.values(), "total_spend", bd("2"));
        assertNumber(row.values(), "total_sales", bd("20"));
        assertNull(row.values().get("total_clicks"), "An unknown category prevents inventing the total");
        assertNull(row.values().get("total_conversion"));
        assertNull(row.values().get("auto_cpc"));
        assertEquals("estimated", row.cellProvenance().get("broad_clicks").quality());
        assertFalse(row.cellProvenance().containsKey("auto_clicks"));
    }

    @Test
    void fullObservedNativeAdsCoverageWinsWhileEstimatedClicksPropagateOnlyToDependentRatios() {
        var period = period(14);
        var report = nativeReport(period, List.of(ad(period.endDate(), "auto", List.of(), metrics())));
        var history = List.of(observation("organic", "2026-05-15", Map.of("clicks", 300)),
                observation("ads", "2026-05-15", historicalAds(99)));
        var row = apply(report, history, allDays(period));
        assertNumber(row.values(), "total_clicks", bd("10"));
        assertNumber(row.values(), "auto_clicks", bd("10"));
        assertNumber(row.values(), "total_sales", bd("20"));
        assertFalse(row.cellProvenance().containsKey("total_clicks"));
        assertFalse(row.cellProvenance().containsKey("total_tacos"));
        assertFalse(row.cellProvenance().containsKey("total_sales_share"));
        assertFalse(row.cellProvenance().containsKey("auto_clicks_share"));
        assertEquals("estimated", row.cellProvenance().get("conversion").quality());
        assertEquals("estimated", row.cellProvenance().get("total_clicks_share").quality());
        assertTrue(row.cellProvenance().get("total_clicks_share").method().contains("total_clicks / clicks"));
        assertNear(row.values(), "total_clicks_share", bd("10").divide(bd("140"), PRECISION));
    }

    @Test
    void flagsPartialNativeAdsWithoutEligibleHistoryAndPropagatesMixedQualityIntoRatios() {
        var period = period(14);
        var report = nativeReport(period, List.of(ad(period.endDate(), "auto", List.of(), metrics())));
        var row = apply(report, List.of(
                observation("organic", "2026-05-15", Map.of("clicks", 300)),
                observation("ads", "2026-05-09", historicalAds(99))), Set.of(period.endDate()));
        assertNumber(row.values(), "total_clicks", bd("10"));
        assertEquals("partial", row.cellProvenance().get("auto_clicks").quality());
        assertEquals("partial", row.cellProvenance().get("total_tacos").quality());
        assertEquals("partial", row.cellProvenance().get("total_sales_share").quality());
        assertTrue(row.cellProvenance().get("total_clicks").method().contains("1/14"));
        var mixed = row.cellProvenance().get("total_clicks_share");
        assertEquals("estimated", mixed.quality());
        assertEquals(date("2026-05-15"), mixed.sourceDate());
        assertTrue(mixed.method().contains("Both inputs have provenance"));
        assertTrue(mixed.method().contains("total_clicks (2026-05-17)"));
        assertTrue(mixed.method().contains("clicks (2026-05-15)"));
        assertFalse(row.cellProvenance().containsKey("orders"));
        assertFalse(row.cellProvenance().containsKey("totalSales"));
    }

    @Test
    void historicalAdsCannotMaskNativeAttributionErrorsOrReplaceIndependentValidCategory() {
        var period = period(14);
        var report = nativeReport(period, List.of(
                ad(period.endDate(), "auto", List.of(), metrics()),
                ad(period.endDate(), "keywords", List.of("broad", "exact"), metrics())));
        assertFalse(report.errors().isEmpty());
        var result = ProductPerformanceHistory.apply(report, List.of(
                observation("ads", "2026-05-15", historicalAds(99)),
                observation("organic", "2026-05-15", Map.of("clicks", 300))), Set.of(period.endDate()));
        var row = result.rows().getFirst();
        assertEquals(report.errors(), result.errors());
        assertNumber(row.values(), "auto_clicks", bd("10"));
        assertNumber(row.values(), "auto_spend", bd("1"));
        assertEquals("partial", row.cellProvenance().get("auto_spend").quality());
        for (String key : List.of("total_clicks", "total_spend", "broad_clicks", "exact_clicks", "total_clicks_share")) {
            assertNull(row.values().get(key), key);
            assertFalse(row.cellProvenance().containsKey(key));
        }
        assertEquals("estimated", row.cellProvenance().get("clicks").quality());
    }

    @Test
    void stateValuesAndFamilyReviewsStayUnscaledAndHistoricalGmvNeverBecomesOrdersOrNetRevenue() {
        for (int days : List.of(14, 30)) {
            var period = period(days);
            var nativeValues = new LinkedHashMap<String, Object>();
            nativeValues.put("orders", null);
            nativeValues.put("totalSales", null);
            nativeValues.put("gmv", null);
            var report = withValues(nativeReport(period, List.of()), nativeValues);
            var row = apply(report, List.of(
                    observation("organic", "2026-05-15", Map.of("stock", 5, "salesPrice", bd("199.99"),
                            "clicks", 300, "performance", "UNRECOGNIZED HOTNESS", "gmv", bd("9000"),
                            "orders", 999, "totalSales", bd("8000"), "conversion", bd("0.75"))),
                    observation("reviews", "2026-05-14", Map.of("reviews", 1000)),
                    observation("rating", "2026-05-13", Map.of("rating", bd("4.97")))), Set.of());
            assertNumber(row.values(), "stock", bd("5"));
            assertNumber(row.values(), "salesPrice", bd("199.99"));
            assertNumber(row.values(), "reviews", bd("1000"));
            assertNumber(row.values(), "rating", bd("4.97"));
            assertEquals("UNRECOGNIZED HOTNESS", row.values().get("performance"));
            assertEquals("estimated", row.performanceStatus());
            assertEquals(date("2026-05-15"), row.performanceSnapshotDate());
            assertEquals(date("2026-05-14"), row.cellProvenance().get("reviews").sourceDate());
            assertNull(row.cellProvenance().get("stock").sourceWindowDays());
            assertNull(row.cellProvenance().get("salesPrice").sourceWindowDays());
            assertNull(row.cellProvenance().get("reviews").sourceWindowDays());
            assertEquals(30, row.cellProvenance().get("performance").sourceWindowDays());
            for (String key : List.of("orders", "totalSales", "gmv", "conversion")) {
                assertNull(row.values().get(key), key);
                assertFalse(row.cellProvenance().containsKey(key));
            }
        }
    }

    @Test
    void serializesOriginalSourceDatesFractionalCountsAndPerCellQualityWithoutChangingTheInputReport() {
        var period = period(14);
        var report = nativeReport(period, List.of());
        var result = ProductPerformanceHistory.apply(report,
                List.of(observation("organic", "2026-05-15", Map.of("clicks", 1))), Set.of());
        assertNull(report.rows().getFirst().values().get("clicks"));
        assertTrue(report.rows().getFirst().cellProvenance().isEmpty());
        var json = new ObjectMapper().valueToTree(result).get("rows").get(0);
        assertEquals("2026-05-15", json.get("visitsSnapshotDate").asString());
        assertTrue(json.get("visitsCapturedAt").isNull());
        assertEquals("estimated", json.get("visitsStatus").asString());
        assertTrue(json.get("values").get("clicks").isNumber());
        assertEquals(0, bd("14").divide(bd("30"), PRECISION)
                .compareTo(json.get("values").get("clicks").decimalValue()));
        var provenance = json.get("cellProvenance").get("clicks");
        assertEquals("estimated", provenance.get("quality").asString());
        assertEquals("2026-05-15", provenance.get("sourceDate").asString());
        assertEquals(30, provenance.get("sourceWindowDays").asInt());
        assertTrue(provenance.get("sourceUrl").asString().contains("/source-book/edit#gid=123&range=G4%3AL4"));
        assertTrue(json.get("cellProvenance").get("conversion").get("method").asString()
                .contains("orders / clicks"));
        assertThrows(UnsupportedOperationException.class, () -> result.rows().getFirst().cellProvenance().clear());
    }

    private static ReportPeriod period(int days) {
        var end = date("2026-05-17");
        return new ReportPeriod(end.minusDays(days - 1L), end, days, false);
    }

    private static Response nativeReport(ReportPeriod period, List<DailyAdset> ads) {
        return ProductPerformanceData.create(PRODUCT, List.of(period), ads, List.of(),
                Map.of(period.startDate(), bd("121")), Map.of(period.startDate(), 2L), Map.of(),
                Map.of(period.startDate(), bd("100")));
    }

    private static Response withValues(Response report, Map<String, Object> replacements) {
        var row = report.rows().getFirst();
        var values = new LinkedHashMap<>(row.values());
        values.putAll(replacements);
        var replacement = new Row(row.period(), values, row.visitsSnapshotDate(), row.visitsCapturedAt(),
                row.visitsStatus(), row.visitsReason(), row.performanceSnapshotDate(), row.performanceStatus(),
                row.performanceReason(), row.performanceType(), row.cellProvenance());
        return new Response(report.mock(), report.product(), report.groups(), List.of(replacement), report.errors());
    }

    private static Row apply(Response nativeReport, List<Observation> history, Set<LocalDate> dates) {
        return ProductPerformanceHistory.apply(nativeReport, history, dates).rows().getFirst();
    }

    private static Observation observation(String domain, String date, Map<String, Object> values) {
        return new Observation(VENDOR, "CODE", "PNK", date(date), domain, values,
                "source-book", 123, domain, "G4:L4");
    }

    private static Map<String, Object> adsValues() {
        var values = new LinkedHashMap<String, Object>();
        for (String category : CATEGORIES) for (String primitive : PRIMITIVES) {
            values.put(category + "_" + primitive, BigDecimal.ZERO);
        }
        return values;
    }

    private static Map<String, Object> blankValues(String... keys) {
        var values = new LinkedHashMap<String, Object>();
        for (String key : keys) values.put(key, null);
        return values;
    }

    private static Map<String, Object> historicalAds(int value) {
        var values = adsValues();
        values.replaceAll((key, ignored) -> value);
        return values;
    }

    private static DailyAdset ad(LocalDate date, String targeting, List<String> matchTypes, Primitives metrics) {
        return new DailyAdset(new AdsAdsetKey(VENDOR, date, 1, "auto".equals(targeting) ? 1 : 2),
                targeting, matchTypes, List.of("PNK"), metrics);
    }

    private static Primitives metrics() {
        return new Primitives(100L, 10L, bd("1"), bd("20"), 4L, 3L);
    }

    private static ProductVisits visits(ReportPeriod period, long count) {
        return new ProductVisits("available", count, count, period.endDate(),
                OffsetDateTime.parse(period.endDate() + "T21:00:00Z"), false);
    }

    private static Set<LocalDate> allDays(ReportPeriod period) {
        return period.startDate().datesUntil(period.endDate().plusDays(1)).collect(Collectors.toSet());
    }

    private static LocalDate date(String value) { return LocalDate.parse(value); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static BigDecimal number(Object value) { return new BigDecimal(value.toString()); }

    private static void assertNumber(Map<String, Object> values, String key, BigDecimal expected) {
        assertInstanceOf(Number.class, values.get(key), key);
        assertEquals(0, expected.compareTo(number(values.get(key))), key);
    }

    private static void assertNear(Map<String, Object> values, String key, BigDecimal expected) {
        assertInstanceOf(Number.class, values.get(key), key);
        assertTrue(expected.subtract(number(values.get(key))).abs().compareTo(bd("1e-30")) <= 0,
                () -> key + " expected " + expected + ", got " + values.get(key));
    }
}
