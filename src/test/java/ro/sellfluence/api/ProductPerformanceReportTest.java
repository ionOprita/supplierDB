package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ro.sellfluence.api.ProductPerformanceData.LifetimeReviews;
import ro.sellfluence.api.ProductPerformanceData.Row;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.OffersTable.DailyProductOffer;
import ro.sellfluence.db.OffersTable.ProductHotness;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;
import ro.sellfluence.db.OffersTable.ProductVisits;
import ro.sellfluence.db.ProductPerformanceSource;
import ro.sellfluence.db.ProductPerformanceSource.ProductOrders;
import ro.sellfluence.db.ProductPerformanceSource.ReviewDay;
import ro.sellfluence.db.ProductPerformanceSource.ReviewHistory;
import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static ro.sellfluence.api.ProductPerformanceData.Period.*;

class ProductPerformanceReportTest {
    private static final UUID VENDOR = UUID.randomUUID();
    private static final ProductPerformanceData.Product PRODUCT =
            new ProductPerformanceData.Product("Product", "Product", "PNK", "https://example.test/product");

    @Test
    void bucharestClosingAndAllMetricBoundariesAgreeIncludingExactFirstAndLastDates() throws Exception {
        var source = new Source();
        source.first = date("2026-11-02");
        source.latest = date("2026-11-15");
        source.ads = List.of(ad("2026-11-02", 2), ad("2026-11-15", 3), ad("2026-11-16", 100));
        source.offers = List.of(offer("2026-11-02", "10"), offer("2026-11-15", "20"));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS,
                Clock.fixed(Instant.parse("2026-11-15T22:30:00Z"), ZoneOffset.UTC));
        assertEquals(Map.of(date("2026-11-02"), date("2026-11-15"),
                date("2026-11-16"), date("2026-11-16")), source.gmvPeriods);
        assertEquals(source.gmvPeriods, source.orderPeriods);
        assertEquals(source.gmvPeriods, source.netSalesPeriods);
        assertEquals(Set.of(date("2026-11-15")), Set.copyOf(source.visitsDates));
        assertEquals(Set.copyOf(source.visitsDates), Set.copyOf(source.hotnessDates));
        assertEquals(List.of(date("2026-11-02"), date("2026-11-16")), source.offerBounds);
        var row = response.rows().getFirst();
        assertFalse(row.period().provisional());
        assertEquals(5L, row.values().get("total_clicks"));
        assertEquals(new BigDecimal("15.00"), row.values().get("salesPrice"));
        assertEquals(44L, row.values().get("clicks"));
        assertEquals(7L, row.values().get("orders"));
        assertEquals(new BigDecimal("123.45"), row.values().get("gmv"));
        assertEquals(new BigDecimal("102.02"), row.values().get("totalSales"));
        assertEquals(7.0 / 44.0, ((BigDecimal) row.values().get("conversion")).doubleValue(), 1e-15);
        assertEquals("Super Hot", row.values().get("performance"));
        assertEquals("available", row.performanceStatus());
        assertEquals(date("2026-11-15"), row.performanceSnapshotDate());
        assertEquals(5.0 / 44.0, ((BigDecimal) row.values().get("total_clicks_share")).doubleValue(), 1e-15);
        assertEquals(2.0 / 102.02, ((BigDecimal) row.values().get("total_tacos")).doubleValue(), 1e-15);
        assertEquals(20.0 / 102.02, ((BigDecimal) row.values().get("total_sales_share")).doubleValue(), 1e-15);
        var open = response.rows().getLast();
        assertTrue(open.period().provisional());
        assertEquals(1, open.period().windowDays());
        assertEquals(100L, open.values().get("total_clicks"));
    }

    @Test
    void monthlyOpenPeriodUsesActualDatesAndLeavesRollingMetricsBlank() throws Exception {
        var source = new Source();
        source.first = date("2026-10-01");
        source.latest = date("2026-11-05");
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(Map.of(date("2026-10-02"), date("2026-10-31"),
                date("2026-11-01"), date("2026-11-06")), source.gmvPeriods);
        assertEquals(source.gmvPeriods, source.orderPeriods);
        assertEquals(List.of(date("2026-10-02"), date("2026-11-06")), source.offerBounds);
        assertEquals(2, response.rows().size());
        var historical = response.rows().getFirst();
        assertNull(historical.values().get("clicks"));
        assertEquals("missing", historical.visitsStatus());
        assertEquals("Regular", historical.values().get("performance"));
        assertEquals("available", historical.performanceStatus());
        assertEquals(date("2026-10-31"), historical.performanceSnapshotDate());
        assertNull(historical.values().get("conversion"));
        var preview = response.rows().getLast();
        assertTrue(preview.period().provisional());
        assertEquals(6, preview.period().windowDays());
        assertNull(preview.values().get("clicks"));
        assertNull(preview.values().get("conversion"));
        assertNull(preview.values().get("performance"));
        assertNull(preview.values().get("total_clicks"));
        assertNull(preview.values().get("auto_clicks"));
        assertNull(preview.values().get("total_clicks_share"));
        assertEquals(1, source.visitsReads);
        assertEquals(1, source.hotnessReads);
        assertEquals(1, source.reviewReads);
        assertEquals(1, source.gmvReads);
        assertEquals(1, source.netSalesReads);
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void vendorSnapshotAndEmptyAggregatesDoNotCreateAProvisionalRow(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();

        var report = novemberReport(source, mode);

        assertFalse(report.rows().isEmpty());
        assertTrue(report.rows().stream().noneMatch(row -> row.period().provisional()));
        assertEquals(0L, report.rows().getLast().values().get("orders"));
        assertEquals(new BigDecimal("0.00"), report.rows().getLast().values().get("totalSales"));
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void ambiguityMarkersAndEmptyAdsPrimitivesDoNotCreateAProvisionalRow(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.offers = List.of(new DailyProductOffer(source.latest, 2, null, null));
        source.visits = Map.of(source.latest, visits(source.latest, "ambiguous", null, null));
        source.hotness = Map.of(source.latest, new ProductHotness("ambiguous", null, null, source.latest));
        source.ads = List.of(ad(source.latest, new Primitives(null, null, null, null, null, null)));

        assertTrue(novemberReport(source, mode).rows().stream().noneMatch(row -> row.period().provisional()));
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void recordedStockKeepsTheProvisionalRowWithBlankClicks(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.offers = List.of(new DailyProductOffer(source.latest, 1, 12, null));
        source.ads = List.of(ad(source.latest, new Primitives(null, null, null, null, null, null)));

        var preview = novemberReport(source, mode).rows().getLast();

        assertTrue(preview.period().provisional());
        assertEquals(12, preview.values().get("stock"));
        assertNull(preview.values().get("clicks"));
        for (var key : List.of("orders", "totalSales", "gmv", "auto_clicks", "broad_clicks", "total_clicks")) {
            assertNull(preview.values().get(key), key);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void recordedOrdersThatSumToZeroKeepTheProvisionalRowWithBlankClicks(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.orders = new ProductOrders(0, true);

        var preview = novemberReport(source, mode).rows().getLast();

        assertTrue(preview.period().provisional());
        assertEquals(0L, preview.values().get("orders"));
        assertNull(preview.values().get("clicks"));
    }

    @Test
    void observedZeroNetSalesCanKeepTodaysFirstRowWithoutAnyOrderQuantities() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = date("2026-11-26");
        source.netSales = new BigDecimal("0.00");

        var report = novemberReport(source, FOURTEEN_DAYS);

        assertEquals(1, report.rows().size());
        var row = report.rows().getFirst();
        assertEquals(date("2026-11-16"), row.period().startDate());
        assertEquals(date("2026-11-26"), row.period().endDate());
        assertEquals(11, row.period().windowDays());
        assertTrue(row.period().provisional());
        assertEquals(new BigDecimal("0.00"), row.values().get("totalSales"));
        assertNull(row.values().get("orders"));
        assertEquals(0, source.visitsReads);
        assertEquals(0, source.hotnessReads);
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void rollingClicksAndClassificationAloneDoNotCreateAnOpenRow(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();
        Long visits14 = mode == FOURTEEN_DAYS ? 0L : null;
        Long visits30 = mode == THIRTY_DAYS ? 0L : null;
        var today = date("2026-11-26");
        source.visits = Map.of(today, visits(today, "available", visits14, visits30));
        source.hotness = Map.of(today, new ProductHotness("available", "hot", "Hot", today));
        assertTrue(novemberReport(source, mode).rows().stream().noneMatch(row -> row.period().provisional()));
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void measuredAdsZeroKeepsTheProvisionalRowWithBlankProductClicks(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.ads = List.of(ad(source.latest, new Primitives(null, 0L, null, null, null, null)));

        var preview = novemberReport(source, mode).rows().getLast();

        assertTrue(preview.period().provisional());
        assertEquals(0L, preview.values().get("auto_clicks"));
        assertEquals(0L, preview.values().get("total_clicks"));
        assertNull(preview.values().get("exact_clicks"));
        assertNull(preview.values().get("clicks"));
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void anImportedEstimateCannotCreateAnOpenRow(ProductPerformanceData.Period mode) throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.history = List.of(history("2026-11-25", "30"));
        source.latest = null;

        assertFalse(novemberReport(source, mode).rows().getLast().period().provisional());
    }

    @Test
    void novemberOpenRowUsesOnlyItsDailyRecordsAndKeepsIncompleteAdsRatiosBlank() throws Exception {
        var source = new Source();
        var first = date("2026-11-16");
        var today = date("2026-11-26");
        source.first = date("2026-11-02");
        source.latest = today;
        source.savedAdsDates = first.datesUntil(today).collect(Collectors.toSet());
        source.ads = List.of(ad("2026-11-15", 500), ad("2026-11-16", 4), ad("2026-11-25", 6),
                ad("2026-11-27", 500));
        source.offers = List.of(offer("2026-11-15", "990"), offer("2026-11-16", "40"),
                offer("2026-11-26", "60"), offer("2026-11-27", "990"));
        source.history = List.of(new Observation(VENDOR, "CODE", "PNK", date("2026-11-25"), "organic",
                Map.of("clicks", 300, "performance", "Super Hot"), "source", 1, "Organic", "G4:L4"),
                new Observation(VENDOR, "CODE", "PNK", date("2026-11-25"), "ads",
                        Map.of("auto_clicks", 900), "source", 2, "Ads", "G4:L4"));

        var row = novemberReport(source, FOURTEEN_DAYS).rows().getLast();

        assertEquals(new ProductPerformanceData.ReportPeriod(first, today, 11, true), row.period());
        assertEquals(today, source.orderPeriods.get(first));
        assertEquals(today, source.netSalesPeriods.get(first));
        assertEquals(7L, row.values().get("orders"));
        assertEquals(new BigDecimal("102.02"), row.values().get("totalSales"));
        assertEquals(new BigDecimal("50.00"), row.values().get("salesPrice"));
        assertEquals(10, row.values().get("stock"));
        assertEquals(10L, row.values().get("auto_clicks"));
        assertEquals(10L, row.values().get("total_clicks"));
        assertEquals(new BigDecimal("0.05"), row.values().get("total_ctr"));
        for (var key : List.of("clicks", "performance", "conversion", "reviews", "rating", "total_clicks_share",
                "total_sales_share", "total_tacos", "auto_tacos", "broad_clicks")) {
            assertNull(row.values().get(key), key);
        }
        var coverage = row.cellProvenance().get("total_clicks");
        assertEquals("partial", coverage.quality());
        assertEquals(date("2026-11-25"), coverage.sourceDate());
        assertTrue(coverage.method().contains("10/11"));

        source.savedAdsDates.add(today);
        row = novemberReport(source, FOURTEEN_DAYS).rows().getLast();
        assertEquals(2.0 / 102.02, ((BigDecimal) row.values().get("total_tacos")).doubleValue(), 1e-15);
        assertEquals(20.0 / 102.02, ((BigDecimal) row.values().get("total_sales_share")).doubleValue(), 1e-15);
        assertTrue(row.cellProvenance().isEmpty());
    }

    @Test
    void aSnapshotTwoDaysLateCannotFillAMissedClose() throws Exception {
        var source = new Source();
        source.first = date("2026-11-02");
        source.latest = date("2026-11-17");
        source.hotness = Map.of(source.latest, new ProductHotness("available", "regular", "Regular", source.latest));
        source.offers = List.of(offer("2026-11-12", "20"));
        source.history = List.of(history("2026-11-06", "90"));

        var row = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();

        assertEquals(date("2026-11-02"), row.period().startDate());
        assertEquals(date("2026-11-15"), row.period().endDate());
        assertFalse(row.period().provisional());
        assertEquals(7L, row.values().get("orders"));
        assertEquals(new BigDecimal("102.02"), row.values().get("totalSales"));
        assertEquals(10, row.values().get("stock"));
        assertEquals(new BigDecimal("20.00"), row.values().get("salesPrice"));
        assertNull(row.values().get("clicks"));
        assertNull(row.values().get("conversion"));
        assertNull(row.values().get("performance"));
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void aMissedCloseUsesOnlyTheNextDaysRollingMetrics(ProductPerformanceData.Period mode) throws Exception {
        var end = mode == FOURTEEN_DAYS ? date("2026-11-15") : date("2026-10-31");
        var next = end.plusDays(1);
        var source = new Source();
        source.first = end.minusDays(mode.windowDays() - 1);
        source.latest = next;
        source.hotness = Map.of(next, new ProductHotness("available", "regular", "Regular", next));
        source.ads = List.of(ad(end.toString(), 3), ad(next.toString(), 900));
        source.offers = List.of(offer(end.toString(), "20"), offer(next.toString(), "200"));
        source.history = List.of(history(end.toString(), "300"));

        var row = novemberReport(source, mode).rows().getFirst();

        long clicks = mode == FOURTEEN_DAYS ? 44L : 99L;
        assertFalse(row.period().provisional());
        assertEquals(end, row.period().endDate());
        assertEquals(source.first, row.period().startDate());
        assertEquals(end, source.orderPeriods.get(source.first));
        assertEquals(end, source.netSalesPeriods.get(source.first));
        assertEquals(clicks, row.values().get("clicks"));
        assertEquals("Regular", row.values().get("performance"));
        assertEquals("regular", row.performanceType());
        assertEquals(next, row.visitsSnapshotDate());
        assertEquals(next.atTime(21, 0).toInstant(ZoneOffset.UTC), row.visitsCapturedAt());
        assertEquals(next, row.performanceSnapshotDate());
        assertEquals("estimated", row.visitsStatus());
        assertEquals("estimated", row.performanceStatus());
        assertEquals(3L, row.values().get("total_clicks"));
        assertEquals(new BigDecimal("20.00"), row.values().get("salesPrice"));
        assertEquals(7.0 / clicks, ((BigDecimal) row.values().get("conversion")).doubleValue(), 1e-15);
        assertEquals(3.0 / clicks, ((BigDecimal) row.values().get("total_clicks_share")).doubleValue(), 1e-15);
        assertEquals(Set.of("clicks", "performance", "conversion", "total_clicks_share"), row.cellProvenance().keySet());
        row.cellProvenance().forEach((key, provenance) -> {
            assertEquals("eMAG offers snapshot", provenance.source());
            assertEquals("estimated", provenance.quality());
            assertEquals(next, provenance.sourceDate());
            assertEquals(key.equals("performance") ? 30 : mode.windowDays(), provenance.sourceWindowDays());
        });
        assertTrue(source.visitsDates.containsAll(List.of(end, next)));
        assertEquals(Set.copyOf(source.visitsDates), Set.copyOf(source.hotnessDates));
        assertEquals(1, source.visitsReads);
        assertEquals(1, source.hotnessReads);
    }

    @Test
    void anExistingClosingDayAccountSnapshotPreventsBorrowing() throws Exception {
        var source = new Source();
        var end = date("2026-11-15");
        var next = end.plusDays(1);
        source.first = date("2026-11-02");
        source.latest = next;
        var later = visits(next, "available", 100L, 200L);
        var classification = new ProductHotness("available", "hot", "Hot", next);
        source.hotness = Map.of(next, classification);
        for (var status : List.of("available", "missing", "ambiguous")) {
            source.visits = Map.of(end, visits(end, status, 0L, 0L), next, later);
            var row = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();
            assertEquals(status, row.visitsStatus());
            assertEquals(status.equals("available") ? 0L : null, row.values().get("clicks"));
            assertNull(row.values().get("performance"));
            assertTrue(row.cellProvenance().isEmpty());
        }
        // Even a snapshot without live counters or a classification records that the vendor was collected.
        source.visits = Map.of(next, later);
        source.hotness = Map.of(end, new ProductHotness("missing", null, null, end), next, classification);
        var row = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();
        assertNull(row.values().get("clicks"));
        assertNull(row.values().get("performance"));
    }

    @Test
    void eachLateMetricMustBeUsableAndMayFallBackToEarlierHistoryIndependently() throws Exception {
        var source = new Source();
        source.first = date("2026-11-02");
        var next = date("2026-11-16");
        source.latest = next;
        source.visits = Map.of(next, visits(next, "available", 0L, null));
        source.hotness = Map.of(next, new ProductHotness("ambiguous", null, null, next));
        var row = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();
        assertEquals(0L, row.values().get("clicks"));
        assertEquals("estimated", row.visitsStatus());
        assertNull(row.values().get("conversion"));
        assertNull(row.values().get("performance"));
        assertFalse(row.cellProvenance().containsKey("performance"));

        source.visits = Map.of(next, visits(next, "ambiguous", null, null));
        source.hotness = Map.of(next, new ProductHotness("available", "cold", "Cold", next));
        source.history = List.of(history("2026-11-14", "30"));
        row = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();
        assertEquals(new BigDecimal("14"), row.values().get("clicks"));
        assertEquals(date("2026-11-14"), row.visitsSnapshotDate());
        assertNull(row.visitsCapturedAt());
        assertEquals("Cold", row.values().get("performance"));
        assertEquals(next, row.performanceSnapshotDate());
    }

    @Test
    void nextDayMustHaveFinishedInBucharestAndCannotFillAProvisionalRow() throws Exception {
        var source = new Source();
        source.first = date("2026-11-02");
        var end = date("2026-11-15");
        var next = end.plusDays(1);
        source.hotness = Map.of();
        source.visits = Map.of(next, visits(next, "available", 44L, 99L));
        var beforeMidnight = Clock.fixed(Instant.parse("2026-11-16T21:59:59Z"), ZoneOffset.UTC);
        var row = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS, beforeMidnight)
                .rows().getFirst();
        assertNull(row.values().get("clicks"));
        assertFalse(source.visitsDates.contains(next));
        var atMidnight = Clock.fixed(Instant.parse("2026-11-16T22:00:00Z"), ZoneOffset.UTC);
        row = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS, atMidnight)
                .rows().getFirst();
        assertEquals(44L, row.values().get("clicks"));

        var today = date("2026-11-26");
        source.visits = Map.of(today, visits(today, "available", 44L, 99L));
        var preview = novemberReport(source, FOURTEEN_DAYS).rows().getLast();
        assertTrue(preview.period().provisional());
        assertNull(preview.values().get("clicks"));
        assertFalse(source.visitsDates.contains(today));
    }

    private static Source sourceWithoutPreviewMetrics() {
        var source = new Source();
        source.first = date("2026-10-01");
        source.latest = date("2026-11-25");
        source.visits = Map.of();
        source.hotness = Map.of();
        source.gmv = null;
        source.netSales = null;
        source.orders = new ProductOrders(0, false);
        return source;
    }

    private static ProductPerformanceData.Response novemberReport(Source source, ProductPerformanceData.Period mode)
            throws Exception {
        return API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", mode,
                Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void adHistoryExtendsRowsAndNoActivityAvoidsAggregateReads() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = null;
        var clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
        assertTrue(API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS, clock).rows().isEmpty());
        assertEquals(0, source.gmvReads);
        assertEquals(0, source.netSalesReads);
        assertEquals(0, source.visitsReads);
        assertEquals(0, source.hotnessReads);
        assertEquals(1, source.reviewReads);
        source.ads = List.of(ad("2026-09-10", 3));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS, clock);
        assertEquals(2, response.rows().size());
        assertEquals(date("2026-09-01"), response.rows().getFirst().period().startDate());
        assertFalse(response.rows().getLast().period().provisional());
        assertEquals(3L, response.rows().getFirst().values().get("total_clicks"));
        assertNull(response.rows().getLast().values().get("total_clicks"));
    }

    @Test
    void importedHistoryExtendsClosedRowsAndDoesNotFillTheOpenRow() throws Exception {
        var source = new Source();
        source.first = date("2026-10-01");
        source.history = List.of(history("2026-08-28", "60"), history("2026-10-30", "80"),
                history("2026-11-05", "90"), history("2026-11-06", "999"), history("2026-11-08", "9999"));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(List.of(date("2026-08-31"), date("2026-09-30"), date("2026-10-31"), date("2026-11-06")),
                response.rows().stream().map(row -> row.period().endDate()).toList());
        assertEquals(new BigDecimal("60"), response.rows().getFirst().values().get("clicks"));
        assertNull(response.rows().get(1).values().get("clicks"));
        var preview = response.rows().getLast();
        assertTrue(preview.period().provisional());
        assertNull(preview.values().get("clicks"));
        assertFalse(preview.cellProvenance().containsKey("clicks"));
        assertEquals(7L, preview.values().get("orders"));
        assertEquals(new BigDecimal("102.02"), preview.values().get("totalSales"));
        assertEquals(date("2026-08-02"), source.offerBounds.getFirst());
        assertEquals(date("2026-11-06"), source.offerBounds.getLast());
    }

    private static Observation history(String date, String clicks) {
        return new Observation(VENDOR, "CODE", "PNK", date(date), "organic",
                Map.of("clicks", new BigDecimal(clicks)), "source", 1, "Organic", "G4:L4");
    }

    @Test
    void explicitBlanksWithSourceMetadataDoNotCreateActivityOrTriggerPeriodReads() throws Exception {
        var source = new Source();
        source.history = List.of(emptyHistory("2023-12-29", "rating", "rating"),
                emptyHistory("2026-10-30", "organic", "clicks"),
                emptyHistory("2026-11-05", "ads", "auto_clicks"));

        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));

        assertTrue(response.rows().isEmpty());
        assertEquals(0, source.gmvReads);
        assertEquals(0, source.netSalesReads);
        assertEquals(0, source.visitsReads);
        assertEquals(0, source.hotnessReads);
        assertEquals(1, source.reviewReads);
    }

    @Test
    void emptyObservationsCannotExtendClosedHistoryOrPopulateTheOpenRow() throws Exception {
        var source = new Source();
        source.first = date("2026-10-01");
        source.latest = date("2026-11-04");
        source.history = List.of(emptyHistory("2023-12-29", "reviews", "reviews"),
                emptyHistory("2026-11-05", "organic", "clicks"),
                emptyHistory("2026-11-05", "ads", "auto_clicks"));

        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));

        assertEquals(List.of(date("2026-10-31"), date("2026-11-06")),
                response.rows().stream().map(row -> row.period().endDate()).toList());
        assertEquals(List.of(date("2026-10-02"), date("2026-11-06")), source.offerBounds);
        assertNull(response.rows().getLast().values().get("clicks"));
    }

    @Test
    void clearedLatestHistoryDoesNotCreateAPreviewAfterTheLastValuedObservation() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = null;
        source.history = List.of(history("2026-10-30", "80"),
                emptyHistory("2026-11-05", "organic", "clicks"));

        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));

        assertEquals(List.of(date("2026-10-31")),
                response.rows().stream().map(row -> row.period().endDate()).toList());
        assertEquals(new BigDecimal("80"), response.rows().getFirst().values().get("clicks"));
        assertFalse(response.rows().getFirst().period().provisional());
    }

    private static Observation emptyHistory(String date, String domain, String metric) {
        var values = new LinkedHashMap<String, Object>();
        values.put(metric, null);
        values.put("familyId", 123);
        values.put("sourceStartDate", "2026-10-07");
        values.put("sourceEndDate", date);
        values.put("sourceWindowDays", 30);
        return new Observation(VENDOR, "CODE", "PNK", date(date), domain, values,
                "source", 1, "History", "G4:L4");
    }

    @Test
    void importedProductWithoutCurrentPnkRemainsReadableAndOrdersStayUnknown() throws Exception {
        var source = new Source();
        source.expectedPnk = null;
        source.singleSalesPeriodStart = date("2026-10-02");
        source.history = List.of(history("2026-10-30", "80"),
                new Observation(VENDOR, "CODE", null, date("2026-10-30"), "reviews", Map.of("reviews", 120),
                        "source", 2, "Nr. Rew. Fam./W.", "F4:F4"),
                new Observation(VENDOR, "CODE", null, date("2026-10-30"), "rating", Map.of("rating", new BigDecimal("4.6")),
                        "source", 3, "Rating Fam./W.", "F4:F4"));
        var metadata = new ProductPerformanceData.Product("Product", "Product", null, null);
        var response = API.buildProductPerformance(source, metadata, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(1, response.rows().size());
        assertEquals(new BigDecimal("80"), response.rows().getFirst().values().get("clicks"));
        assertNull(response.rows().getFirst().values().get("orders"));
        assertNull(source.orderPeriods);
        assertEquals(new BigDecimal("102.02"), response.rows().getFirst().values().get("totalSales"));
        assertEquals(0, source.reviewReads);
        var row = response.rows().getFirst();
        assertNull(row.values().get("reviews"));
        assertNull(row.values().get("rating"));
        assertNull(row.values().get("lifetimeReviews"));
        assertFalse(row.cellProvenance().containsKey("reviews"));
        assertFalse(row.cellProvenance().containsKey("rating"));
    }

    @Test
    void nativeReviewsAreReadOnceThroughTodayAndRemainAvailableWithoutAds() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = date("2026-11-02");
        source.reviews = new ReviewHistory(Instant.parse("2026-11-16T08:00:00Z"), Map.of(
                date("2026-11-02"), new ReviewDay(1, 5, 1),
                date("2026-11-15"), new ReviewDay(1, 1, 1),
                date("2026-12-30"), new ReviewDay(1, 1, 1)));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS,
                Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(1, source.reviewReads);
        assertNull(source.reviewFrom);
        assertEquals(date("2026-11-16"), source.reviewTo);
        assertEquals(1, response.rows().size());
        var row = response.rows().getFirst();
        assertEquals(date("2026-11-02"), row.period().startDate());
        assertEquals(date("2026-11-15"), row.period().endDate());
        assertEquals(2L, row.values().get("reviews"));
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) row.values().get("rating")));
        assertNull(row.values().get("total_clicks"));
        assertFalse(row.cellProvenance().containsKey("reviews"));
        assertFalse(row.cellProvenance().containsKey("rating"));
    }

    @Test
    void reviewsStartHistoryAtPublicationAndWeightEachRatingInTheDisplayedDates() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = null;
        source.history = List.of(new Observation(VENDOR, "CODE", "PNK", date("2023-12-29"), "reviews",
                Map.of("reviews", 96), "sheet", 2, "Family reviews", "F4"));
        source.reviews = new ReviewHistory(Instant.parse("2026-12-01T08:00:00Z"), Map.of(
                date("2026-11-02"), new ReviewDay(1, 5, 1),
                date("2026-11-15"), new ReviewDay(4, 9, 3),
                date("2026-11-16"), new ReviewDay(1, 1, 1)));
        var clock = Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC);

        var fortnight = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS, clock);
        var first = fortnight.rows().getFirst();
        assertEquals(date("2026-11-02"), first.period().startDate());
        assertEquals(date("2026-11-15"), first.period().endDate());
        assertEquals(5L, first.values().get("reviews"));
        assertEquals(new BigDecimal("3.5"), first.values().get("rating"));
        assertEquals(1L, fortnight.rows().get(1).values().get("reviews"));
        assertNull(first.reviewFetchedAt());

        var month = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS, clock);
        assertEquals(1, month.rows().size());
        assertEquals(6L, month.rows().getFirst().values().get("reviews"));
        assertEquals(new BigDecimal("3"), month.rows().getFirst().values().get("rating"));
    }

    @Test
    void fortnightlyLifetimeRatingWeightsAllReviewsThroughEachPeriodEnd() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = date("2026-11-02");
        source.reviews = new ReviewHistory(Instant.parse("2026-12-14T08:00:00Z"), Map.of(
                date("2026-11-15"), new ReviewDay(100, 480, 100),
                date("2026-11-16"), new ReviewDay(20, 90, 20)));
        var clock = Clock.fixed(Instant.parse("2026-12-14T10:00:00Z"), ZoneOffset.UTC);

        var report = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS, clock);

        assertEquals(List.of(date("2026-11-15"), date("2026-11-29"), date("2026-12-13")),
                report.rows().stream().map(row -> row.period().endDate()).toList());
        assertEquals(List.of(100L, 20L, 0L),
                report.rows().stream().map(row -> row.values().get("reviews")).toList());
        assertEquals(new BigDecimal("4.5"), report.rows().get(1).values().get("rating"));
        assertNull(report.rows().get(2).values().get("rating"));
        assertLifetimeReviews(report.rows().get(0), 100, "4.80");
        assertLifetimeReviews(report.rows().get(1), 120, "4.75");
        assertLifetimeReviews(report.rows().get(2), 120, "4.75");
    }

    @Test
    void monthlyLifetimeReviewsSpanOverlappingWindowsAndCalendarGaps() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = date("2026-01-02");
        source.reviews = new ReviewHistory(Instant.parse("2026-04-02T08:00:00Z"), Map.of(
                date("2026-01-30"), new ReviewDay(2, 9, 2),
                date("2026-02-05"), new ReviewDay(1, 3, 1),
                date("2026-03-01"), new ReviewDay(1, 1, 1),
                date("2026-03-10"), new ReviewDay(1, 4, 1)));
        var clock = Clock.fixed(Instant.parse("2026-04-02T10:00:00Z"), ZoneOffset.UTC);

        var report = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS, clock);
        var january = report.rows().get(0);
        var february = report.rows().get(1);
        var march = report.rows().get(2);

        assertEquals(date("2026-01-30"), february.period().startDate());
        assertEquals(date("2026-03-02"), march.period().startDate());
        assertEquals(List.of(2L, 3L, 1L),
                report.rows().stream().map(row -> row.values().get("reviews")).toList());
        assertLifetimeReviews(january, 2, "4.5");
        assertLifetimeReviews(february, 3, "4");
        assertLifetimeReviews(march, 5, "3.4");
    }

    @Test
    void closedReviewsNeedACollectionAfterTheEndOfTheRomanianDay() throws Exception {
        var source = sourceWithoutPreviewMetrics();
        source.first = date("2026-11-02");
        var days = Map.of(date("2026-11-15"), new ReviewDay(1, 4, 1));
        source.reviews = new ReviewHistory(Instant.parse("2026-11-15T21:59:59Z"), days);
        var stale = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();
        assertNull(stale.values().get("reviews"));
        assertNull(stale.values().get("rating"));
        assertNull(stale.values().get("lifetimeReviews"));

        source.reviews = new ReviewHistory(Instant.parse("2026-11-15T22:00:00Z"), days);
        var covered = novemberReport(source, FOURTEEN_DAYS).rows().getFirst();
        assertEquals(1L, covered.values().get("reviews"));
        assertEquals(new BigDecimal("4"), covered.values().get("rating"));
        assertLifetimeReviews(covered, 1, "4");
    }

    @ParameterizedTest
    @EnumSource(ProductPerformanceData.Period.class)
    void aFreshPositiveReviewCountCanCreateTheCurrentRowEvenWithoutStars(ProductPerformanceData.Period mode)
            throws Exception {
        var source = sourceWithoutPreviewMetrics();
        var today = date("2026-11-26");
        var fetchedAt = Instant.parse("2026-11-26T07:42:00Z");
        source.reviews = new ReviewHistory(fetchedAt, Map.of());
        assertTrue(novemberReport(source, mode).rows().stream().noneMatch(row -> row.period().provisional()));

        source.offers = List.of(offer(today.toString(), "12.00"));
        var confirmedZero = novemberReport(source, mode).rows().getLast();
        assertEquals(0L, confirmedZero.values().get("reviews"));
        assertNull(confirmedZero.values().get("rating"));
        assertEquals(fetchedAt, confirmedZero.reviewFetchedAt());
        assertLifetimeReviews(confirmedZero, 0, null);

        source.reviews = new ReviewHistory(Instant.parse("2026-11-25T18:00:00Z"), Map.of());
        var stale = novemberReport(source, mode).rows().getLast();
        assertNull(stale.values().get("reviews"));
        assertNull(stale.values().get("rating"));
        assertNull(stale.values().get("lifetimeReviews"));
        assertNull(stale.reviewFetchedAt());

        source.offers = List.of();
        source.reviews = new ReviewHistory(fetchedAt, Map.of(date("2026-10-30"), new ReviewDay(1, 3, 1)));
        assertTrue(novemberReport(source, mode).rows().stream().noneMatch(row -> row.period().provisional()));

        source.reviews = new ReviewHistory(fetchedAt, Map.of(today, new ReviewDay(1, 0, 0)));
        var current = novemberReport(source, mode).rows().getLast();
        assertTrue(current.period().provisional());
        assertEquals(1L, current.values().get("reviews"));
        assertNull(current.values().get("rating"));
        assertEquals(fetchedAt, current.reviewFetchedAt());
        assertLifetimeReviews(current, 1, null);
    }

    private static void assertLifetimeReviews(Row row, long count, String averageRating) {
        var lifetime = assertInstanceOf(LifetimeReviews.class, row.values().get("lifetimeReviews"));
        assertEquals(count, lifetime.count());
        if (averageRating == null) {
            assertNull(lifetime.averageRating());
        } else {
            assertNotNull(lifetime.averageRating());
            assertEquals(0, new BigDecimal(averageRating).compareTo(lifetime.averageRating()));
        }
    }

    private static final class Source implements ProductPerformanceSource {
        String expectedPnk = "PNK";
        LocalDate first, latest;
        List<DailyAdset> ads = List.of();
        List<DailyProductOffer> offers = List.of();
        List<Observation> history = List.of();
        Map<LocalDate, ProductVisits> visits;
        Map<LocalDate, ProductHotness> hotness;
        ProductOrders orders = new ProductOrders(7, true);
        BigDecimal gmv = new BigDecimal("123.45");
        BigDecimal netSales = new BigDecimal("102.02");
        LocalDate singleSalesPeriodStart;
        Set<LocalDate> savedAdsDates;
        List<LocalDate> offerBounds;
        ReviewHistory reviews = ReviewHistory.empty();
        Map<LocalDate, LocalDate> gmvPeriods, netSalesPeriods, orderPeriods;
        Collection<LocalDate> visitsDates, hotnessDates;
        int visitsReads, hotnessReads, gmvReads, netSalesReads, reviewReads;
        LocalDate reviewFrom, reviewTo;

        public ReviewHistory getProductPerformanceReviews(String pnk, LocalDate from, LocalDate to) {
            assertEquals(expectedPnk, pnk);
            reviewReads++;
            reviewFrom = from;
            reviewTo = to;
            var selected = reviews.days().entrySet().stream()
                    .filter(entry -> from == null || !entry.getKey().isBefore(from))
                    .filter(entry -> !entry.getKey().isAfter(to))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return new ReviewHistory(reviews.lastSuccessfulFetchAt(), selected);
        }

        public List<DailyAdset> getProductPerformanceAdsets(UUID vendor, String pnk) {
            assertEquals(VENDOR, vendor);
            return ads;
        }
        public List<Observation> getProductPerformanceHistory(UUID vendor, String productCode) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", productCode);
            return history;
        }
        public Set<LocalDate> getProductPerformanceAdsReportDates(UUID vendor, LocalDate start, LocalDate end) {
            assertEquals(VENDOR, vendor);
            if (savedAdsDates != null) return savedAdsDates;
            return start.datesUntil(end.plusDays(1)).collect(Collectors.toSet());
        }
        public Optional<LocalDate> getFirstProductPerformanceActivity(UUID vendor, String code, String pnk) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", code);
            assertEquals(expectedPnk, pnk);
            return Optional.ofNullable(first);
        }
        public List<DailyProductOffer> getProductPerformanceOffers(UUID vendor, String pnk, LocalDate start, LocalDate end) {
            assertEquals(VENDOR, vendor);
            offerBounds = List.of(start, end);
            return offers;
        }
        public Map<LocalDate, BigDecimal> getObservedGMVForProductPeriods(UUID vendor, String code, Map<LocalDate, LocalDate> periods) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", code);
            gmvReads++;
            gmvPeriods = Map.copyOf(periods);
            return amountsFor(periods, gmv);
        }
        public Map<LocalDate, BigDecimal> getObservedNetSalesForProductPeriods(UUID vendor, String code, Map<LocalDate, LocalDate> periods) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", code);
            netSalesReads++;
            netSalesPeriods = Map.copyOf(periods);
            return amountsFor(periods, netSales);
        }
        private Map<LocalDate, BigDecimal> amountsFor(Map<LocalDate, LocalDate> periods, BigDecimal amount) {
            if (amount == null) return Map.of();
            return periods.keySet().stream().filter(it -> singleSalesPeriodStart == null || it.equals(singleSalesPeriodStart))
                    .collect(Collectors.toMap(it -> it, it -> amount));
        }
        public Map<LocalDate, ProductOrders> getProductPerformanceOrders(UUID vendor, String pnk,
                                                                        Map<LocalDate, LocalDate> periods) {
            assertNotNull(pnk);
            assertEquals(VENDOR, vendor);
            assertEquals("PNK", pnk);
            orderPeriods = Map.copyOf(periods);
            return periods.keySet().stream().collect(Collectors.toMap(it -> it, it -> orders));
        }
        public Map<LocalDate, ProductVisits> getProductVisitsForDates(UUID vendor, String pnk, Collection<LocalDate> dates) {
            assertEquals(VENDOR, vendor);
            visitsReads++;
            visitsDates = List.copyOf(dates);
            if (visits != null) return visits;
            if (latest == null) return Map.of();
            return Map.of(latest, visits(latest, "available", 44L, 99L));
        }
        public Map<LocalDate, ProductHotness> getProductHotnessForDates(UUID vendor, String pnk, Collection<LocalDate> dates) {
            assertEquals(VENDOR, vendor);
            assertEquals(expectedPnk, pnk);
            hotnessReads++;
            hotnessDates = List.copyOf(dates);
            if (hotness != null) return hotness;
            return dates.stream().distinct().collect(Collectors.toMap(it -> it, it ->
                    it.equals(latest) ? new ProductHotness("available", "super_hot", "Super Hot", it)
                            : new ProductHotness("available", "regular", "Regular", it)));
        }
    }

    private static ProductVisits visits(LocalDate date, String status, Long visits14, Long visits30) {
        return new ProductVisits(status, visits14, visits30, date,
                date.atTime(23, 0).atOffset(ZoneOffset.ofHours(2)), false);
    }

    private static DailyAdset ad(String date, long clicks) {
        return ad(date(date), new Primitives(100L, clicks, BigDecimal.ONE, BigDecimal.TEN, 1L, 1L));
    }

    private static DailyAdset ad(LocalDate date, Primitives metrics) {
        return new DailyAdset(new AdsAdsetKey(VENDOR, date, 1, 1), "auto", List.of(), List.of("PNK"), metrics);
    }
    private static DailyProductOffer offer(String date, String price) {
        return new DailyProductOffer(date(date), 1, 10, new BigDecimal(price));
    }
    private static LocalDate date(String value) { return LocalDate.parse(value); }
}
