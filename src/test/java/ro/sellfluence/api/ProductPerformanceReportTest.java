package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.OffersTable.DailyProductOffer;
import ro.sellfluence.db.OffersTable.ProductHotness;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;
import ro.sellfluence.db.OffersTable.ProductVisits;
import ro.sellfluence.db.ProductPerformanceSource;
import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;
import ro.sellfluence.db.ReviewsTable.PublishedReview;
import ro.sellfluence.db.ReviewsTable.ReviewHistory;

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
        assertEquals(date("2026-11-16"), source.requestedToday);
        assertEquals(Map.of(date("2026-11-02"), date("2026-11-15")), source.gmvPeriods);
        assertEquals(source.gmvPeriods, source.orderPeriods);
        assertEquals(source.gmvPeriods, source.netSalesPeriods);
        assertEquals(Set.of(date("2026-11-15")), Set.copyOf(source.visitsDates));
        assertEquals(Set.copyOf(source.visitsDates), Set.copyOf(source.hotnessDates));
        assertEquals(List.of(date("2026-11-02"), date("2026-11-15")), source.offerBounds);
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
    }

    @Test
    void organicOnlyProductHasClosedHistoryAndFullRollingPreviewWithoutInventedAds() throws Exception {
        var source = new Source();
        source.first = date("2026-10-01");
        source.latest = date("2026-11-05");
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(Map.of(date("2026-10-02"), date("2026-10-31"),
                date("2026-10-07"), date("2026-11-05")), source.gmvPeriods);
        assertEquals(source.gmvPeriods, source.orderPeriods);
        assertEquals(List.of(date("2026-10-02"), date("2026-11-05")), source.offerBounds);
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
        assertEquals(99L, preview.values().get("clicks"));
        assertEquals(7.0 / 99.0, ((BigDecimal) preview.values().get("conversion")).doubleValue(), 1e-15);
        assertEquals("Super Hot", preview.values().get("performance"));
        assertNull(preview.values().get("total_clicks"));
        assertNull(preview.values().get("auto_clicks"));
        assertNull(preview.values().get("total_clicks_share"));
        assertEquals(1, source.visitsReads);
        assertEquals(1, source.hotnessReads);
        assertEquals(1, source.reviewReads);
        assertEquals(1, source.gmvReads);
        assertEquals(1, source.netSalesReads);
    }

    @Test
    void adHistoryExtendsRowsAndNoActivityAvoidsAggregateReads() throws Exception {
        var source = new Source();
        var clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
        assertTrue(API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS, clock).rows().isEmpty());
        assertEquals(0, source.gmvReads);
        assertEquals(0, source.netSalesReads);
        assertEquals(0, source.visitsReads);
        assertEquals(0, source.hotnessReads);
        assertEquals(0, source.reviewReads);
        source.ads = List.of(ad("2026-09-10", 3));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS, clock);
        assertEquals(2, response.rows().size());
        assertEquals(date("2026-09-01"), response.rows().getFirst().period().startDate());
        assertEquals(3L, response.rows().getFirst().values().get("total_clicks"));
        assertNull(response.rows().getLast().values().get("total_clicks"));
    }

    @Test
    void importedHistoryExtendsClosedRowsAndPreviewWithoutUsingTodayOrFutureSnapshots() throws Exception {
        var source = new Source();
        source.first = date("2026-10-01");
        source.history = List.of(history("2026-08-28", "60"), history("2026-10-30", "80"),
                history("2026-11-05", "90"), history("2026-11-06", "999"), history("2026-11-08", "9999"));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(List.of(date("2026-08-31"), date("2026-09-30"), date("2026-10-31"), date("2026-11-05")),
                response.rows().stream().map(row -> row.period().endDate()).toList());
        assertEquals(new BigDecimal("60"), response.rows().getFirst().values().get("clicks"));
        assertNull(response.rows().get(1).values().get("clicks"));
        var preview = response.rows().getLast();
        assertTrue(preview.period().provisional());
        assertEquals(new BigDecimal("90"), preview.values().get("clicks"));
        assertEquals("estimated", preview.cellProvenance().get("clicks").quality());
        assertEquals(date("2026-11-05"), preview.cellProvenance().get("clicks").sourceDate());
        assertEquals(7L, preview.values().get("orders"));
        assertEquals(new BigDecimal("102.02"), preview.values().get("totalSales"));
        assertEquals(date("2026-08-02"), source.offerBounds.getFirst());
        assertEquals(date("2026-11-05"), source.offerBounds.getLast());
    }

    private static Observation history(String date, String clicks) {
        return new Observation(VENDOR, "CODE", "PNK", date(date), "organic",
                Map.of("clicks", new BigDecimal(clicks)), "source", 1, "Organic", "G4:L4");
    }

    @Test
    void explicitBlanksWithSourceMetadataDoNotCreateActivityOrTriggerMetricReads() throws Exception {
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
        assertEquals(0, source.reviewReads);
    }

    @Test
    void emptyObservationsCannotExtendNativeHistoryOrAdvanceTheRollingPreview() throws Exception {
        var source = new Source();
        source.first = date("2026-10-01");
        source.latest = date("2026-11-04");
        source.history = List.of(emptyHistory("2023-12-29", "reviews", "reviews"),
                emptyHistory("2026-11-05", "organic", "clicks"),
                emptyHistory("2026-11-05", "ads", "auto_clicks"));

        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", THIRTY_DAYS,
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));

        assertEquals(List.of(date("2026-10-31"), date("2026-11-04")),
                response.rows().stream().map(row -> row.period().endDate()).toList());
        assertEquals(List.of(date("2026-10-02"), date("2026-11-04")), source.offerBounds);
        assertEquals(99L, response.rows().getLast().values().get("clicks"));
    }

    @Test
    void clearedLatestHistoryDoesNotCreateAPreviewAfterTheLastValuedObservation() throws Exception {
        var source = new Source();
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
        assertEquals(1, source.reviewReads);
        var row = response.rows().getFirst();
        assertEquals(120, row.values().get("reviews"));
        assertEquals(new BigDecimal("4.6"), row.values().get("rating"));
        assertEquals("estimated", row.cellProvenance().get("reviews").quality());
        assertEquals("estimated", row.cellProvenance().get("rating").quality());
    }

    @Test
    void nativeReviewsAreReadOnceForScheduledRowsWithoutAdsAndCannotExtendTheReport() throws Exception {
        var source = new Source();
        source.first = date("2026-11-02");
        source.latest = date("2026-11-15");
        source.reviews = new ReviewHistory(true, List.of(
                new PublishedReview(1, "2024-01-01T12:00:00Z", 1),
                new PublishedReview(2, "2026-11-02T00:00:00+14:00", 5),
                new PublishedReview(3, "2026-11-15T23:59:59-12:00", 1),
                new PublishedReview(4, "2026-12-30T12:00:00Z", 1)));
        var response = API.buildProductPerformance(source, PRODUCT, VENDOR, "CODE", FOURTEEN_DAYS,
                Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC));
        assertEquals(1, source.reviewReads);
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

    private static final class Source implements ProductPerformanceSource {
        String expectedPnk = "PNK";
        LocalDate first, latest, requestedToday;
        List<DailyAdset> ads = List.of();
        List<DailyProductOffer> offers = List.of();
        List<Observation> history = List.of();
        List<LocalDate> offerBounds;
        Map<LocalDate, LocalDate> gmvPeriods, netSalesPeriods, orderPeriods;
        Collection<LocalDate> visitsDates, hotnessDates;
        int visitsReads, hotnessReads, gmvReads, netSalesReads, reviewReads;
        ReviewHistory reviews = ReviewHistory.unavailable();

        public ReviewHistory readProductReviews(String pnk) {
            assertEquals(expectedPnk, pnk);
            reviewReads++;
            return reviews;
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
            return start.datesUntil(end.plusDays(1)).collect(Collectors.toSet());
        }
        public Optional<LocalDate> getFirstProductPerformanceActivity(UUID vendor, String code, String pnk) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", code);
            assertEquals(expectedPnk, pnk);
            return Optional.ofNullable(first);
        }
        public Optional<LocalDate> getLatestCompletedProductVisitsSnapshotDate(UUID vendor, LocalDate today) {
            assertEquals(VENDOR, vendor);
            requestedToday = today;
            return Optional.ofNullable(latest);
        }
        public List<DailyProductOffer> getProductPerformanceOffers(UUID vendor, String pnk, LocalDate start, LocalDate end) {
            assertEquals(VENDOR, vendor);
            offerBounds = List.of(start, end);
            return offers;
        }
        public Map<LocalDate, BigDecimal> computeGMVForProductPeriods(UUID vendor, String code, Map<LocalDate, LocalDate> periods) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", code);
            gmvReads++;
            gmvPeriods = Map.copyOf(periods);
            return periods.keySet().stream().collect(Collectors.toMap(it -> it, it -> new BigDecimal("123.45")));
        }
        public Map<LocalDate, BigDecimal> computeNetSalesForProductPeriods(UUID vendor, String code, Map<LocalDate, LocalDate> periods) {
            assertEquals(VENDOR, vendor);
            assertEquals("CODE", code);
            netSalesReads++;
            netSalesPeriods = Map.copyOf(periods);
            return periods.keySet().stream().collect(Collectors.toMap(it -> it, it -> new BigDecimal("102.02")));
        }
        public Map<LocalDate, Long> countOrdersForProductPeriods(UUID vendor, String pnk, Map<LocalDate, LocalDate> periods) {
            assertNotNull(pnk);
            assertEquals(VENDOR, vendor);
            assertEquals("PNK", pnk);
            orderPeriods = Map.copyOf(periods);
            return periods.keySet().stream().collect(Collectors.toMap(it -> it, it -> 7L));
        }
        public Map<LocalDate, ProductVisits> getProductVisitsForDates(UUID vendor, String pnk, Collection<LocalDate> dates) {
            assertEquals(VENDOR, vendor);
            visitsReads++;
            visitsDates = List.copyOf(dates);
            if (latest == null) return Map.of();
            return Map.of(latest, new ProductVisits("available", 44L, 99L, latest,
                    latest.atTime(23, 0).atOffset(ZoneOffset.ofHours(2)), false));
        }
        public Map<LocalDate, ProductHotness> getProductHotnessForDates(UUID vendor, String pnk, Collection<LocalDate> dates) {
            assertEquals(VENDOR, vendor);
            assertEquals(expectedPnk, pnk);
            hotnessReads++;
            hotnessDates = List.copyOf(dates);
            return dates.stream().distinct().collect(Collectors.toMap(it -> it, it ->
                    it.equals(latest) ? new ProductHotness("available", "super_hot", "Super Hot", it)
                            : new ProductHotness("available", "regular", "Regular", it)));
        }
    }

    private static DailyAdset ad(String date, long clicks) {
        return new DailyAdset(new AdsAdsetKey(VENDOR, date(date), 1, 1), "auto", List.of(), List.of("PNK"),
                new Primitives(100L, clicks, BigDecimal.ONE, BigDecimal.TEN, 1L, 1L));
    }
    private static DailyProductOffer offer(String date, String price) {
        return new DailyProductOffer(date(date), 1, 10, new BigDecimal(price));
    }
    private static LocalDate date(String value) { return LocalDate.parse(value); }
}
