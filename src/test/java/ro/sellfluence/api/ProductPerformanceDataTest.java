package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.ProductPerformanceTable.DailyAdset;
import ro.sellfluence.db.ProductPerformanceTable.Primitives;
import ro.sellfluence.db.OffersTable.DailyProductOffer;
import ro.sellfluence.db.OffersTable.ProductHotness;
import ro.sellfluence.db.OffersTable.ProductVisits;
import ro.sellfluence.api.ProductPerformanceData.ReportPeriod;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.sellfluence.api.ProductPerformanceData.Period.THIRTY_DAYS;
import static ro.sellfluence.api.ProductPerformanceData.Period.FOURTEEN_DAYS;

class ProductPerformanceDataTest {
    private static final UUID VENDOR = UUID.fromString("2d10b460-8fc9-44b0-9d65-000000000001");
    private static final ProductPerformanceData.Product PRODUCT = new ProductPerformanceData.Product(
            "Vendor", "Actual product", "ACTUALPNK", "https://emag.ro/product_details/pd/ACTUALPNK");

    @Test
    void preservesEveryDashboardColumnKeyLabelTypeAndGroupBoundary() {
        var response = report(PRODUCT, FOURTEEN_DAYS, weightedSample());
        assertFalse(response.mock());
        assertEquals(List.of("overview", "total", "auto", "broad", "exact", "product"),
                response.groups().stream().map(ProductPerformanceData.ColumnGroup::key).toList());
        assertEquals(List.of(10, 13, 15, 15, 15, 15),
                response.groups().stream().map(group -> group.columns().size()).toList());
        assertEquals(List.of("Period", "Stock", "Orders", "Total Sales (excl. VAT)", "Clicks", "Sales Price (incl. VAT)", "Conv.",
                        "Perform.", "# rev.", "rating"),
                response.groups().getFirst().columns().stream().map(ProductPerformanceData.Column::label).toList());
        assertEquals(List.of("date", "integer", "integer", "integer", "integer", "decimal", "percent", "text", "integer", "decimal"),
                response.groups().getFirst().columns().stream().map(ProductPerformanceData.Column::type).toList());
        var commonAdsLabels = List.of("Imp.", "Clicks", "Spend (excl. VAT)", "Sales (excl. VAT)", "Total Units Sold",
                "CTR", "Conv.", "CPC (excl. VAT)", "ROAS", "ACOS", "TACOS");
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
        assertFalse(keys.contains("averagePrice"));
        var values = response.rows().getFirst().values();
        var apiKeys = new HashSet<>(keys);
        apiKeys.add("gmv");
        assertEquals(apiKeys, values.keySet());
        assertEquals(66, values.values().stream().filter(Number.class::isInstance).count());
        response.groups().getFirst().columns().stream().filter(column -> !"period".equals(column.key()))
                .forEach(column -> assertNull(values.get(column.key()), column.key()));
        for (var group : List.of("total", "auto", "broad", "exact", "product")) {
            assertNull(values.get(group + "_tacos"));
        }
        assertNull(values.get("total_clicks_share"));
        assertNull(values.get("total_sales_share"));
    }

    @Test
    void sumsSourceMetricsAndDerivesWeightedRatesAndSharesFromTotals() {
        var response = report(PRODUCT, FOURTEEN_DAYS, weightedSample());
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
        var response = report(PRODUCT, FOURTEEN_DAYS,
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
        assertEquals("2026-09-07", jsonValues.get("period").asString());
        assertTrue(jsonValues.get("rating").isNull());
    }

    @Test
    void missingPrimitivesPropagateToOnlyDependentAggregatesAndRates() {
        var missing = new Primitives(100L, 10L, null, new BigDecimal("50"), 4L, null);
        var response = report(PRODUCT, FOURTEEN_DAYS, List.of(
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
        var values = report(PRODUCT, FOURTEEN_DAYS,
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
        var values = report(PRODUCT, FOURTEEN_DAYS,
                List.of(snapshot("2026-09-08", "auto", List.of(), metrics(0, 0, "0", "0", 0, 0))))
                .rows().getFirst().values();
        assertEquals(66, values.values().stream().filter(Number.class::isInstance).count());
        values.forEach((key, value) -> {
            if (value instanceof Number) assertNumber(values, key, "0");
        });
        var missing = new Primitives(0L, null, new BigDecimal("10"), BigDecimal.ZERO, 1L, null);
        var unknown = report(PRODUCT, FOURTEEN_DAYS,
                List.of(snapshot("2026-09-08", "auto", List.of(), missing))).rows().getFirst().values();
        assertNull(unknown.get("auto_ctr"));
        assertNull(unknown.get("auto_conversion"));
        assertNull(unknown.get("auto_cpc"));
        assertNumber(unknown, "auto_roas", "0");
        assertNumber(unknown, "auto_acos", "0");
    }

    @Test
    void classifiesDistinctKeywordTypesAndIgnoresNegativeEntries() {
        var response = report(PRODUCT, FOURTEEN_DAYS, List.of(
                snapshot("2026-09-08", "keywords", List.of("negative", "broad", "broad"), metrics(1, 1, "2", "3", 4, 1)),
                snapshot("2026-09-08", "keywords", List.of("exact", "negative", "exact"), metrics(5, 5, "6", "7", 8, 2))));
        assertTrue(response.errors().isEmpty());
        assertEquals(1L, response.rows().getFirst().values().get("broad_impressions"));
        assertEquals(5L, response.rows().getFirst().values().get("exact_impressions"));
        assertEquals(6L, response.rows().getFirst().values().get("total_impressions"));
    }

    @Test
    void missingKeywordTypesWithKnownZeroMetricsPreserveObservedWindows() {
        for (var period : List.of(FOURTEEN_DAYS, THIRTY_DAYS)) {
            for (var types : List.of(List.<String>of(), List.of("negative", "negative"))) {
                var response = report(PRODUCT, period, List.of(
                        snapshot("2026-08-31", "keywords", types, metrics(0, 0, "0.0000", "0E+3", 0, 0)),
                        snapshot("2026-09-08", "keywords", types, metrics(0, 0, "0", "0.00", 0, 0))));
                assertTrue(response.errors().isEmpty(), period + " " + types);
                assertEquals(period == FOURTEEN_DAYS ? List.of("2026-08-24", "2026-09-07")
                                : List.of("2026-08-02", "2026-09-01"),
                        response.rows().stream().map(row -> row.values().get("period")).toList());
                for (var row : response.rows()) {
                    var values = row.values();
                    assertEquals(66, values.values().stream().filter(Number.class::isInstance).count());
                    values.forEach((key, value) -> {
                        if (value instanceof Number) assertNumber(values, key, "0");
                    });
                }
            }
        }
    }

    @Test
    void missingKeywordTypesWithKnownZeroMetricsLeaveExistingTotalsRatesAndSharesUnchanged() {
        for (var period : List.of(FOURTEEN_DAYS, THIRTY_DAYS)) {
            var baseline = report(PRODUCT, period, weightedSample());
            var snapshots = new ArrayList<>(weightedSample());
            snapshots.addFirst(snapshot("2026-09-08", "keywords", List.of(), metrics(0, 0, "0.00", "0", 0, 0)));
            snapshots.add(snapshot("2026-09-09", "keywords", List.of("negative"), metrics(0, 0, "0", "0.000", 0, 0)));
            var response = report(PRODUCT, period, snapshots);
            assertTrue(response.errors().isEmpty());
            assertEquals(baseline.rows(), response.rows());
        }
    }

    @Test
    void missingKeywordTypesStillInvalidateMetricsIfAnyPrimitiveIsNonzeroOrUnknown() {
        var uncertainMetrics = List.of(
                metrics(5, 0, "0", "0", 0, 0),
                metrics(0, 1, "0", "0", 0, 0),
                metrics(0, 0, "0.01", "0", 0, 0),
                metrics(0, 0, "0", "0.01", 0, 0),
                metrics(0, 0, "0", "0", 1, 0),
                metrics(0, 0, "0", "0", 0, 1),
                metrics(0, 0, "-0.01", "0", 0, 0),
                new Primitives(null, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L),
                new Primitives(0L, null, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L),
                new Primitives(0L, 0L, null, BigDecimal.ZERO, 0L, 0L),
                new Primitives(0L, 0L, BigDecimal.ZERO, null, 0L, 0L),
                new Primitives(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, null, 0L),
                new Primitives(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 0L, null));
        for (var types : List.of(List.<String>of(), List.of("negative"))) {
            for (var metrics : uncertainMetrics) {
                var response = report(PRODUCT, FOURTEEN_DAYS,
                        List.of(snapshot("2026-06-12", "keywords", types, metrics)));
                assertEquals(1, response.errors().size(), types + " " + metrics);
                assertEquals("INVALID_KEYWORD_MATCH_TYPE", response.errors().getFirst().code());
                assertTrue(response.errors().getFirst().matchTypes().isEmpty());
                assertKeywordSectionsUnavailable(response.rows().getFirst().values());
                assertEquals(0L, response.rows().getFirst().values().get("auto_impressions"));
            }
        }
    }

    @Test
    void allZeroMetricsDoNotExemptConflictingOrUnsupportedKeywordTypes() {
        for (var types : List.of(List.of("broad", "exact", "negative"), List.of("phrase"),
                List.of("(missing)"), List.of("broad", "phrase"))) {
            var response = report(PRODUCT, FOURTEEN_DAYS,
                    List.of(snapshot("2026-09-08", "keywords", types, metrics(0, 0, "0", "0", 0, 0))));
            assertEquals(1, response.errors().size(), types.toString());
            assertEquals("INVALID_KEYWORD_MATCH_TYPE", response.errors().getFirst().code());
            assertKeywordSectionsUnavailable(response.rows().getFirst().values());
        }
    }

    @Test
    void exemptZeroSnapshotsPreserveOtherErrorsAndInvalidationRegardlessOfOrder() {
        var snapshots = new ArrayList<>(weightedSample());
        snapshots.add(snapshot("2026-09-08", "keywords", List.of(), metrics(5, 0, "0", "0", 0, 0)));
        var ambiguous = snapshot("2026-09-09", "auto", List.of(), metrics(100, 10, "10", "50", 4, 2));
        snapshots.add(new DailyAdset(ambiguous.key(), ambiguous.targeting(), ambiguous.matchTypes(),
                List.of("ACTUALPNK", "OTHERPNK"), ambiguous.metrics()));
        for (var period : List.of(FOURTEEN_DAYS, THIRTY_DAYS)) {
            var baseline = report(PRODUCT, period, snapshots);
            assertEquals(2, baseline.errors().size());
            var withZeros = new ArrayList<>(snapshots);
            withZeros.addFirst(snapshot("2026-09-08", "keywords", List.of(), metrics(0, 0, "0", "0", 0, 0)));
            withZeros.add(snapshot("2026-09-09", "keywords", List.of("negative"), metrics(0, 0, "0", "0", 0, 0)));
            var response = report(PRODUCT, period, withZeros);
            assertEquals(baseline.errors(), response.errors());
            assertEquals(baseline.rows(), response.rows());
        }
    }

    @Test
    void zeroMetricsWithMissingKeywordTypesStillReportAmbiguousProductAttribution() {
        var zero = snapshot("2026-09-08", "keywords", List.of(), metrics(0, 0, "0", "0", 0, 0));
        var ambiguous = new DailyAdset(zero.key(), zero.targeting(), zero.matchTypes(),
                List.of("ACTUALPNK", "OTHERPNK"), zero.metrics());
        var response = report(PRODUCT, FOURTEEN_DAYS, List.of(ambiguous));
        assertEquals(1, response.errors().size());
        assertEquals("AMBIGUOUS_PRODUCT_ATTRIBUTION", response.errors().getFirst().code());
        assertTrue(response.errors().getFirst().message().contains("OTHERPNK"));
    }

    @Test
    void invalidKeywordClassificationInvalidatesBothKeywordSectionsTotalsAndEveryShareForItsPeriod() {
        for (var types : List.of(List.<String>of(), List.of("negative"), List.of("broad", "exact", "negative"),
                List.of("phrase"), List.of("(missing)"), List.of("broad", "phrase"))) {
            var bad = snapshot("2026-09-08", "keywords", types, metrics(100, 10, "10", "50", 4, 2));
            var response = report(PRODUCT, FOURTEEN_DAYS, List.of(
                    bad,
                    snapshot("2026-09-08", "auto", List.of(), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-08", "products", List.of(), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-08", "keywords", List.of("broad"), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-21", "keywords", List.of("exact"), metrics(100, 10, "10", "50", 4, 2))));
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
        var response = report(PRODUCT, FOURTEEN_DAYS, List.of(ambiguous,
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
            var response = report(PRODUCT, FOURTEEN_DAYS, List.of(
                    snapshot("2026-09-08", targeting, List.of(), metrics(100, 10, "10", "50", 4, 2)),
                    snapshot("2026-09-08", "auto", List.of(), metrics(100, 10, "10", "50", 4, 2))));
            assertEquals("UNKNOWN_TARGETING", response.errors().getFirst().code());
            response.rows().getFirst().values().forEach((key, value) -> {
                if (!"period".equals(key)) assertNull(value, key);
            });
        }
    }


    @Test
    void usesConsecutiveFourteenDayWindowsAndThirtyDayMonthEndWindows() {
        assertEquals(LocalDate.parse("2026-11-02"), FOURTEEN_DAYS.start(LocalDate.parse("2026-11-02")));
        assertEquals(LocalDate.parse("2026-11-15"), FOURTEEN_DAYS.end(LocalDate.parse("2026-11-02")));
        assertEquals(LocalDate.parse("2026-11-02"), FOURTEEN_DAYS.start(LocalDate.parse("2026-11-15")));
        assertEquals(LocalDate.parse("2026-11-16"), FOURTEEN_DAYS.start(LocalDate.parse("2026-11-16")));
        // floorDiv is essential before the anchor: truncation would incorrectly put Nov 1 in Nov 2's window.
        assertEquals(LocalDate.parse("2026-10-19"), FOURTEEN_DAYS.start(LocalDate.parse("2026-11-01")));
        assertEquals(LocalDate.parse("2026-11-01"), FOURTEEN_DAYS.end(LocalDate.parse("2026-10-19")));
        assertEquals(LocalDate.parse("2026-01-02"), THIRTY_DAYS.start(LocalDate.parse("2026-01-01")));
        assertEquals(LocalDate.parse("2026-01-31"), THIRTY_DAYS.end(LocalDate.parse("2026-01-01")));
        assertEquals(LocalDate.parse("2026-01-30"), THIRTY_DAYS.start(LocalDate.parse("2026-02-01")));
        assertEquals(LocalDate.parse("2026-02-28"), THIRTY_DAYS.end(LocalDate.parse("2026-02-28")));
        assertEquals(LocalDate.parse("2024-01-31"), THIRTY_DAYS.start(LocalDate.parse("2024-02-01")));
        assertEquals(LocalDate.parse("2024-02-29"), THIRTY_DAYS.end(LocalDate.parse("2024-02-29")));
        assertEquals(14, FOURTEEN_DAYS.windowDays());
        assertEquals(30, THIRTY_DAYS.windowDays());
    }

    @Test
    void aggregatesOverlappingWindowsIndependentlyAndUsesOnlyDatesInsideEachWindow() {
        var january = window("2026-01-31", 30, false);
        var february = window("2026-02-28", 30, false);
        var snapshots = List.of(
                snapshot("2026-01-01", "auto", List.of(), metrics(1000, 100, "100", "1000", 100, 20)),
                snapshot("2026-01-29", "auto", List.of(), metrics(100, 10, "1", "10", 1, 1)),
                snapshot("2026-01-30", "auto", List.of(), metrics(900, 30, "9", "90", 9, 3)),
                snapshot("2026-02-28", "auto", List.of(), metrics(100, 10, "1", "10", 1, 1)),
                snapshot("2026-03-01", "auto", List.of(), metrics(1000, 100, "100", "1000", 100, 20)));
        var response = ProductPerformanceData.create(PRODUCT, List.of(january, february), snapshots,
                List.of(offer("2026-01-30", 1, 10, "100.00"), offer("2026-02-28", 1, 20, "200.00")),
                Map.of(january.startDate(), new BigDecimal("1234.56"), february.startDate(), new BigDecimal("-42.75")),
                Map.of(january.startDate(), 5L, february.startDate(), -2L), Map.of(), Map.of());
        assertEquals(List.of(january, february), response.rows().stream().map(ProductPerformanceData.Row::period).toList());
        for (var row : response.rows()) {
            assertEquals(1000L, row.values().get("total_impressions"));
            assertNumber(row.values(), "auto_ctr", ".04");
            assertNumber(row.values(), "auto_cpc", ".25");
        }
        assertNumber(response.rows().getFirst().values(), "salesPrice", "100.00");
        assertNumber(response.rows().getLast().values(), "salesPrice", "150.00");
        assertEquals(List.of(10, 20), response.rows().stream().map(row -> row.values().get("stock")).toList());
        assertEquals(List.of(5L, -2L), response.rows().stream().map(row -> row.values().get("orders")).toList());
        assertNumber(response.rows().getFirst().values(), "gmv", "1234.56");
        assertNumber(response.rows().getLast().values(), "gmv", "-42.75");
    }

    @Test
    void explicitRowsShowOrganicVisitsAndIndependentMetricsWithoutAdvertisingObservations() {
        var period = window("2026-11-05", 14, true);
        var response = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(),
                List.of(offer("2026-11-04", 1, 8, "199.99")),
                Map.of(period.startDate(), new BigDecimal("0.00")), Map.of(period.startDate(), 0L),
                Map.of(period.endDate(), visits("available", "2026-11-05", 123L, 456L)), Map.of());
        var row = response.rows().getFirst();
        assertEquals(period, row.period());
        assertEquals(123L, row.values().get("clicks"));
        assertEquals(0L, row.values().get("orders"));
        assertNumber(row.values(), "gmv", "0.00");
        assertNumber(row.values(), "salesPrice", "199.99");
        assertEquals(8, row.values().get("stock"));
        for (var group : response.groups().subList(1, response.groups().size())) {
            for (var column : group.columns()) assertNull(row.values().get(column.key()), column.key());
        }
        assertEquals("available", row.visitsStatus());
        assertNull(row.visitsReason());
        assertTrue(response.errors().isEmpty());
    }

    @Test
    void eachWindowUsesItsOwnRawClicksAndNetRevenueForProductRelativeRatios() {
        var fortnight = window("2026-11-05", 14, true);
        var month = window("2026-11-05", 30, true);
        var response = ProductPerformanceData.create(PRODUCT, List.of(fortnight, month),
                List.of(snapshot("2026-11-05", "auto", List.of(), metrics(1000, 10, "5", "100", 4, 2))),
                List.of(), Map.of(fortnight.startDate(), new BigDecimal("200"), month.startDate(), new BigDecimal("300")),
                Map.of(fortnight.startDate(), 8L, month.startDate(), 12L),
                Map.of(fortnight.endDate(), visits("available", "2026-11-05", 50L, 120L)),
                Map.of(fortnight.startDate(), new BigDecimal("200"), month.startDate(), new BigDecimal("300")));
        assertEquals(List.of(50L, 120L), response.rows().stream().map(row -> row.values().get("clicks")).toList());
        for (var row : response.rows()) {
            assertNumber(row.values(), "total_conversion", ".2");
            for (var key : List.of("performance", "reviews", "rating")) {
                assertNull(row.values().get(key), key);
            }
            for (var group : List.of("broad", "exact", "product")) {
                assertNumber(row.values(), group + "_tacos", "0");
            }
        }
        var fortnightValues = response.rows().getFirst().values();
        var monthValues = response.rows().getLast().values();
        assertNumber(fortnightValues, "conversion", ".16");
        assertNumber(monthValues, "conversion", ".1");
        assertNumber(fortnightValues, "auto_tacos", ".025");
        assertNumber(fortnightValues, "total_tacos", ".025");
        assertNumber(fortnightValues, "total_sales_share", ".5");
        assertNumber(fortnightValues, "total_clicks_share", ".2");
        assertNumber(monthValues, "auto_tacos", fraction(5, 300));
        assertNumber(monthValues, "total_tacos", fraction(5, 300));
        assertNumber(monthValues, "total_sales_share", fraction(100, 300));
        assertNumber(monthValues, "total_clicks_share", fraction(10, 120));
    }

    @Test
    void categoryTacosUsesTheSameNetRevenueAndTotalSharesUseMonetarySalesAndProductClicks() {
        var period = window("2026-09-20", 14, false);
        var values = productRatios(period, weightedSample(), "1000",
                visits("available", "2026-09-20", 200L, 400L)).values();

        assertNumber(values, "auto_tacos", ".01");
        assertNumber(values, "broad_tacos", ".02");
        assertNumber(values, "exact_tacos", ".005");
        assertNumber(values, "product_tacos", ".015");
        assertNumber(values, "total_tacos", ".05");
        assertNumber(values, "total_sales_share", ".3");
        assertNumber(values, "total_clicks_share", ".5");
        var categorySum = List.of("auto", "broad", "exact", "product").stream()
                .map(group -> (BigDecimal) values.get(group + "_tacos")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, categorySum.compareTo((BigDecimal) values.get("total_tacos")));
        // Category shares still use Total Ads, rather than the product-wide denominators.
        assertNumber(values, "auto_sales_share", fraction(1, 3));
        assertNumber(values, "auto_clicks_share", ".4");
        var json = new ObjectMapper().valueToTree(values);
        assertEquals(0, new BigDecimal("0.05").compareTo(json.get("total_tacos").decimalValue()));
        assertEquals(0, new BigDecimal("0.3").compareTo(json.get("total_sales_share").decimalValue()));
    }

    @Test
    void productRatiosRequirePositiveKnownDenominatorsWithoutInventingMissingAds() {
        var period = window("2026-11-05", 14, false);
        var snapshots = List.of(snapshot("2026-11-05", "auto", List.of(), metrics(100, 10, "5", "100", 4, 2)));
        var captured = visits("available", "2026-11-05", 50L, 120L);
        for (var revenue : new String[]{null, "0", "-100"}) {
            var values = productRatios(period, snapshots, revenue, captured).values();
            for (var group : List.of("total", "auto", "broad", "exact", "product")) {
                assertNull(values.get(group + "_tacos"), group + " revenue=" + revenue);
            }
            assertNull(values.get("total_sales_share"));
            assertNumber(values, "total_clicks_share", ".2");
        }
        for (var unavailable : new ProductVisits[]{null,
                visits("available", "2026-11-05", 0L, 120L),
                visits("available", "2026-11-05", -1L, 120L),
                visits("available", "2026-11-05", null, 120L),
                visits("ambiguous", "2026-11-05", 50L, 120L),
                visits("missing", "2026-11-05", 50L, 120L),
                visits("available", "2026-11-04", 50L, 120L),
                new ProductVisits("available", 50L, 120L, period.endDate(), null, false)}) {
            var values = productRatios(period, snapshots, "100", unavailable).values();
            assertNull(values.get("total_clicks_share"));
            assertNumber(values, "total_tacos", ".05");
            assertNumber(values, "total_sales_share", "1");
        }
        var noAds = productRatios(period, List.of(), "100", captured).values();
        for (var group : List.of("total", "auto", "broad", "exact", "product")) {
            assertNull(noAds.get(group + "_tacos"));
        }
        assertNull(noAds.get("total_clicks_share"));
        assertNull(noAds.get("total_sales_share"));
        var zero = productRatios(period,
                List.of(snapshot("2026-11-05", "auto", List.of(), metrics(0, 0, "0", "0", 0, 0))),
                "100", captured).values();
        for (var group : List.of("total", "auto", "broad", "exact", "product")) {
            assertNumber(zero, group + "_tacos", "0");
        }
        assertNumber(zero, "total_clicks_share", "0");
        assertNumber(zero, "total_sales_share", "0");
    }

    @Test
    void missingAdsPrimitivesOnlyInvalidateDependentProductRatios() {
        var period = window("2026-11-05", 14, false);
        var product = snapshot("2026-11-05", "products", List.of(), metrics(200, 20, "20", "100", 8, 6));
        var captured = visits("available", "2026-11-05", 200L, 400L);
        var missingSpend = productRatios(period, List.of(product,
                snapshot("2026-11-05", "auto", List.of(), new Primitives(100L, 10L, null, new BigDecimal("50"), 4L, 1L))),
                "1000", captured).values();
        assertNull(missingSpend.get("auto_tacos"));
        assertNull(missingSpend.get("total_tacos"));
        assertNumber(missingSpend, "product_tacos", ".02");
        assertNumber(missingSpend, "total_clicks_share", ".15");
        assertNumber(missingSpend, "total_sales_share", ".15");

        var missingSales = productRatios(period, List.of(product,
                snapshot("2026-11-05", "auto", List.of(), new Primitives(100L, 10L, BigDecimal.TEN, null, 4L, 1L))),
                "1000", captured).values();
        assertNumber(missingSales, "auto_tacos", ".01");
        assertNumber(missingSales, "total_tacos", ".03");
        assertNumber(missingSales, "total_clicks_share", ".15");
        assertNull(missingSales.get("total_sales_share"));

        var missingClicks = productRatios(period, List.of(product,
                snapshot("2026-11-05", "auto", List.of(), new Primitives(100L, null, BigDecimal.TEN, new BigDecimal("50"), 4L, 1L))),
                "1000", captured).values();
        assertNumber(missingClicks, "total_tacos", ".03");
        assertNumber(missingClicks, "total_sales_share", ".15");
        assertNull(missingClicks.get("total_clicks_share"));
    }

    @Test
    void invalidKeywordAttributionPreservesIndependentCategoryTacosButInvalidatesTotalRatios() {
        var period = window("2026-11-05", 14, false);
        var row = productRatios(period, List.of(
                snapshot("2026-11-05", "auto", List.of(), metrics(100, 10, "10", "100", 4, 2)),
                snapshot("2026-11-05", "products", List.of(), metrics(100, 10, "20", "100", 4, 2)),
                snapshot("2026-11-05", "keywords", List.of("broad", "exact"), metrics(100, 10, "5", "50", 1, 1))),
                "1000", visits("available", "2026-11-05", 200L, 400L));
        assertNumber(row.values(), "auto_tacos", ".01");
        assertNumber(row.values(), "product_tacos", ".02");
        assertNumber(row.values(), "totalSales", "1000");
        assertKeywordSectionsUnavailable(row.values());
    }

    @Test
    void productRatiosPreserveValuesAboveOneAndSignedNumerators() {
        var period = window("2026-11-05", 14, false);
        var captured = visits("available", "2026-11-05", 20L, 40L);
        var overOne = productRatios(period,
                List.of(snapshot("2026-11-05", "auto", List.of(), metrics(100, 30, "200", "300", 4, 2))),
                "100", captured).values();
        assertNumber(overOne, "auto_tacos", "2");
        assertNumber(overOne, "total_tacos", "2");
        assertNumber(overOne, "total_sales_share", "3");
        assertNumber(overOne, "total_clicks_share", "1.5");
        var signed = productRatios(period,
                List.of(snapshot("2026-11-05", "auto", List.of(), metrics(100, 10, "-5", "-20", 4, 2))),
                "100", captured).values();
        assertNumber(signed, "auto_tacos", "-.05");
        assertNumber(signed, "total_tacos", "-.05");
        assertNumber(signed, "total_sales_share", "-.2");
    }

    @Test
    void preservesVisitZeroMissingCounterAndAmbiguityWithoutFallingBackToAnotherSnapshot() {
        var period = window("2026-11-05", 14, false);
        var zero = visitsRow(period, visits("available", "2026-11-05", 0L, 100L));
        assertEquals(0L, zero.values().get("clicks"));
        assertEquals("available", zero.visitsStatus());

        for (var missing : List.of(
                visits("available", "2026-11-05", null, 100L),
                visits("missing", "2026-11-05", null, null),
                visits("available", "2026-11-04", 99L, 100L),
                visits("available", "2026-11-06", 99L, 100L),
                new ProductVisits("available", 99L, 100L, period.endDate(), null, false))) {
            var row = visitsRow(period, missing);
            assertNull(row.values().get("clicks"));
            assertEquals("missing", row.visitsStatus());
            assertTrue(row.visitsReason() != null && !row.visitsReason().isBlank());
            assertEquals(missing.snapshotDate(), row.visitsSnapshotDate());
        }
        var absent = visitsRow(period, null);
        assertEquals("missing", absent.visitsStatus());
        assertNull(absent.visitsSnapshotDate());
        assertNull(absent.visitsCapturedAt());
        var ambiguous = visitsRow(period, visits("ambiguous", "2026-11-05", 99L, 100L));
        assertNull(ambiguous.values().get("clicks"));
        assertEquals("ambiguous", ambiguous.visitsStatus());
        assertTrue(ambiguous.visitsReason().contains("Multiple"));
        assertEquals(period.endDate(), ambiguous.visitsSnapshotDate());
        assertEquals(Instant.parse("2026-11-05T21:00:00Z"), ambiguous.visitsCapturedAt());
    }

    @Test
    void bothWindowsAverageObservedDailyGrossPricesWhileStockUsesLatestObservation() {
        for (var days : List.of(14, 30)) {
            var period = window("2026-11-05", days, true);
            var response = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(), List.of(
                    offer(period.startDate().minusDays(1).toString(), 1, 100, "9999.99"),
                    offer(period.startDate().toString(), 1, 20, "100.00"),
                    offer("2026-11-02", 1, 15, "0.00"),
                    offer("2026-11-03", 1, 10, null),
                    offer("2026-11-04", 1, 7, "200.00"),
                    offer("2026-11-05", 1, 5, null),
                    offer("2026-11-06", 1, 999, "9999.99")), Map.of(), Map.of(), Map.of(), Map.of());
            var values = response.rows().getFirst().values();
            assertNumber(values, "salesPrice", "100.00");
            assertEquals(5, values.get("stock"));
        }
    }

    @Test
    void ambiguousPriceDayInvalidatesMeanButNotLatestStockAndMissingPricesStayUnavailable() {
        var period = window("2026-11-05", 14, true);
        var response = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(),
                List.of(offer("2026-11-03", 2, 90, null), offer("2026-11-05", 1, 8, "199.99")),
                Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("???", response.rows().getFirst().values().get("salesPrice"));
        assertEquals(8, response.rows().getFirst().values().get("stock"));
        var missing = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(),
                List.of(offer("2026-11-05", 1, null, null)), Map.of(), Map.of(), Map.of(), Map.of()).rows().getFirst();
        assertNull(missing.values().get("salesPrice"));
        assertNull(missing.values().get("stock"));
        var rounded = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(),
                List.of(offer("2026-11-04", 1, 2, "0.005"), offer("2026-11-05", 1, 1, "0.015")),
                Map.of(), Map.of(), Map.of(), Map.of()).rows().getFirst();
        assertNumber(rounded.values(), "salesPrice", "0.01");
    }

    @Test
    void advertisingErrorsDoNotHideIndependentMetricsAndRepeatedWindowsDoNotDuplicateErrors() {
        var january = window("2026-01-31", 30, false);
        var february = window("2026-02-28", 30, false);
        var response = ProductPerformanceData.create(PRODUCT, List.of(january, february),
                List.of(snapshot("2026-01-30", "unsupported", List.of(), metrics(1, 1, "1", "1", 1, 1))),
                List.of(offer("2026-01-30", 1, 9, "199.99")),
                Map.of(january.startDate(), new BigDecimal("-10.25"), february.startDate(), new BigDecimal("0.00")),
                Map.of(january.startDate(), -2L, february.startDate(), 0L),
                Map.of(january.endDate(), visits("available", "2026-01-31", 10L, 20L)), Map.of());
        assertEquals(1, response.errors().size());
        assertEquals("UNKNOWN_TARGETING", response.errors().getFirst().code());
        assertEquals(List.of(-2L, 0L), response.rows().stream().map(row -> row.values().get("orders")).toList());
        assertNumber(response.rows().getFirst().values(), "gmv", "-10.25");
        assertEquals(20L, response.rows().getFirst().values().get("clicks"));
        for (var row : response.rows()) {
            assertNumber(row.values(), "salesPrice", "199.99");
            assertNull(row.values().get("total_units"));
        }
    }

    @Test
    void productConversionUsesUnitsAndRollingClicksWithoutAdsForBothWindows() {
        for (var days : List.of(14, 30)) {
            var period = window("2026-11-05", days, true);
            var row = productOverview(period, 25L, visits("available", "2026-11-05", 1000L, 1000L), null);
            assertNumber(row.values(), "conversion", ".025");
            assertNull(row.values().get("total_conversion"));
            assertNull(row.values().get("total_units"));
            assertNumber(productOverview(period, 0L, visits("available", "2026-11-05", 100L, 100L), null)
                    .values(), "conversion", "0");
            assertNumber(productOverview(period, 125L, visits("available", "2026-11-05", 100L, 100L), null)
                    .values(), "conversion", "1.25");
            assertNumber(productOverview(period, -2L, visits("available", "2026-11-05", 100L, 100L), null)
                    .values(), "conversion", "-.02");
        }
    }

    @Test
    void conversionRequiresKnownUnitsAndPositiveUnambiguousExactDateClicks() {
        for (var days : List.of(14, 30)) {
            var period = window("2026-11-05", days, false);
            assertNull(productOverview(period, null, visits("available", "2026-11-05", 100L, 100L), null)
                    .values().get("conversion"));
            assertNull(productOverview(period, 25L, null, null).values().get("conversion"));
            for (var unavailable : List.of(
                    visits("available", "2026-11-05", 0L, 0L),
                    visits("available", "2026-11-05", -1L, -1L),
                    visits("available", "2026-11-05", null, null),
                    visits("missing", "2026-11-05", 100L, 100L),
                    visits("ambiguous", "2026-11-05", 100L, 100L),
                    visits("available", "2026-11-04", 100L, 100L),
                    new ProductVisits("available", 100L, 100L, period.endDate(), null, false))) {
                assertNull(productOverview(period, 25L, unavailable, null).values().get("conversion"));
                assertNull(productOverview(period, 0L, unavailable, null).values().get("conversion"));
            }
        }
    }

    @Test
    void hotnessUsesEndDateObservationInBothWindowsIndependentlyOfVisitsAndAds() {
        for (var days : List.of(14, 30)) {
            var period = window("2026-11-05", days, false);
            var hotness = new ProductHotness("available", "super_hot", "Super Hot", period.endDate());
            var row = productOverview(period, 25L, null, hotness);
            assertEquals("Super Hot", row.values().get("performance"));
            assertEquals("available", row.performanceStatus());
            assertEquals("super_hot", row.performanceType());
            assertEquals(period.endDate(), row.performanceSnapshotDate());
            assertNull(row.performanceReason());
            assertNull(row.values().get("conversion"));
            assertEquals("missing", row.visitsStatus());
            var json = new ObjectMapper().valueToTree(row);
            assertEquals("2026-11-05", json.get("performanceSnapshotDate").asString());
            assertEquals("available", json.get("performanceStatus").asString());
            assertEquals("super_hot", json.get("performanceType").asString());
            assertTrue(json.get("performanceReason").isNull());
        }
    }

    @Test
    void hotnessPreservesUnknownLabelsAndFallsBackToKnownOrRawTypes() {
        var period = window("2026-11-05", 14, true);
        var labels = Map.of("super_hot", "Super Hot", "hot", "Hot", "regular", "Regular",
                "cold", "Cold", "super_cold", "Super Cold", "new_classification", "new_classification");
        labels.forEach((type, label) -> {
            var row = productOverview(period, 25L, null, new ProductHotness("available", type, " ", period.endDate()));
            assertEquals(label, row.values().get("performance"));
            assertEquals(type, row.performanceType());
        });
        var raw = "New <classification>, \"Label\"";
        assertEquals(raw, productOverview(period, 25L, null,
                new ProductHotness("available", "super_hot", raw, period.endDate())).values().get("performance"));
        assertEquals(raw, productOverview(period, 25L, null,
                new ProductHotness("available", null, raw, period.endDate())).values().get("performance"));
    }

    @Test
    void hotnessDoesNotBorrowOtherDatesOrUseAmbiguousLabels() {
        var period = window("2026-11-05", 30, false);
        var clicks = visits("available", "2026-11-05", 1000L, 1000L);
        var missing = productOverview(period, 25L, clicks, null);
        assertNull(missing.values().get("performance"));
        assertEquals("missing", missing.performanceStatus());
        assertNull(missing.performanceSnapshotDate());
        for (var unavailable : List.of(
                new ProductHotness("available", "hot", "Hot", period.endDate().minusDays(1)),
                new ProductHotness("available", "hot", "Hot", period.endDate().plusDays(1)),
                new ProductHotness("missing", null, null, period.endDate()),
                new ProductHotness("available", " ", "", period.endDate()))) {
            var row = productOverview(period, 25L, clicks, unavailable);
            assertNull(row.values().get("performance"));
            assertEquals("missing", row.performanceStatus());
            assertTrue(row.performanceReason().contains("snapshot"));
            assertNumber(row.values(), "conversion", ".025");
        }
        var ambiguous = productOverview(period, 25L, clicks,
                new ProductHotness("ambiguous", "hot", "Hot", period.endDate()));
        assertEquals("???", ambiguous.values().get("performance"));
        assertEquals("ambiguous", ambiguous.performanceStatus());
        assertNull(ambiguous.performanceType());
        assertTrue(ambiguous.performanceReason().contains("Multiple"));
        assertNumber(ambiguous.values(), "conversion", ".025");
    }

    @Test
    void serializesExactDatesAndCaptureTimestampAndPreservesNumericValues() {
        var period = window("2026-11-05", 30, true);
        var response = ProductPerformanceData.create(PRODUCT, List.of(period), List.of(), List.of(),
                Map.of(period.startDate(), new BigDecimal("1234.56")), Map.of(period.startDate(), 5L),
                Map.of(period.endDate(), visits("available", "2026-11-05", 10L, 20L)),
                Map.of(period.startDate(), new BigDecimal("1020.30")));
        var json = new ObjectMapper().valueToTree(response).get("rows").get(0);
        assertEquals("2026-10-07", json.get("period").get("startDate").asString());
        assertEquals("2026-11-05", json.get("period").get("endDate").asString());
        assertEquals(30, json.get("period").get("windowDays").asInt());
        assertTrue(json.get("period").get("provisional").asBoolean());
        assertEquals("2026-11-05", json.get("visitsSnapshotDate").asString());
        assertEquals("2026-11-05T21:00:00Z", json.get("visitsCapturedAt").asString());
        assertEquals("2026-10-07", json.get("values").get("period").asString());
        assertTrue(json.get("values").get("orders").isIntegralNumber());
        assertEquals(new BigDecimal("1234.56"), json.get("values").get("gmv").decimalValue());
        assertEquals(new BigDecimal("1020.30"), json.get("values").get("totalSales").decimalValue());
    }

    @Test
    void validatesWindowInputsAndLegacyPeriodAliasesAndPreservesAnExplicitEmptyHistory() {
        assertEquals(FOURTEEN_DAYS, ProductPerformanceData.Period.parse(null));
        assertEquals(FOURTEEN_DAYS, ProductPerformanceData.Period.parse("14days"));
        assertEquals(FOURTEEN_DAYS, ProductPerformanceData.Period.parse("week"));
        assertEquals(THIRTY_DAYS, ProductPerformanceData.Period.parse("30days"));
        assertEquals(THIRTY_DAYS, ProductPerformanceData.Period.parse("month"));
        for (var value : List.of("", " ", "Week", "quarter", " month ", "7days")) {
            assertThrows(IllegalArgumentException.class, () -> ProductPerformanceData.Period.parse(value));
        }
        assertThrows(IllegalArgumentException.class, () -> new ReportPeriod(LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-14"), 30, false));
        assertThrows(IllegalArgumentException.class, () -> new ReportPeriod(LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-07"), 7, false));
        var response = ProductPerformanceData.create(PRODUCT, List.of(), weightedSample(),
                List.of(offer("2026-11-05", 1, 5, "100")), Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals(PRODUCT, response.product());
        assertTrue(response.rows().isEmpty());
        assertTrue(response.errors().isEmpty());
        assertFalse(response.mock());
    }

    /** Explicit interval fixture for the unchanged advertising formula/classification tests above. */
    private static ProductPerformanceData.Response report(ProductPerformanceData.Product product,
            ProductPerformanceData.Period period, List<DailyAdset> snapshots) {
        var periods = snapshots.stream().map(snapshot -> period.end(snapshot.key().reportDate())).distinct().sorted()
                .map(end -> new ReportPeriod(end.minusDays(period.windowDays() - 1L), end, period.windowDays(), false)).toList();
        return ProductPerformanceData.create(product, periods, snapshots, List.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static ReportPeriod window(String end, int days, boolean provisional) {
        var endDate = LocalDate.parse(end);
        return new ReportPeriod(endDate.minusDays(days - 1L), endDate, days, provisional);
    }

    private static ProductVisits visits(String status, String date, Long visits14, Long visits30) {
        return new ProductVisits(status, visits14, visits30, LocalDate.parse(date),
                OffsetDateTime.parse(date + "T23:00:00+02:00"), false);
    }

    private static ProductPerformanceData.Row visitsRow(ReportPeriod period, ProductVisits visits) {
        return ProductPerformanceData.create(PRODUCT, List.of(period), List.of(), List.of(), Map.of(), Map.of(),
                visits == null ? Map.of() : Map.of(period.endDate(), visits), Map.of()).rows().getFirst();
    }

    private static ProductPerformanceData.Row productOverview(ReportPeriod period, Long orders,
            ProductVisits visits, ProductHotness hotness) {
        return ProductPerformanceData.create(PRODUCT, List.of(period), List.of(), List.of(), Map.of(),
                orders == null ? Map.of() : Map.of(period.startDate(), orders),
                visits == null ? Map.of() : Map.of(period.endDate(), visits), Map.of(),
                hotness == null ? Map.of() : Map.of(period.endDate(), hotness)).rows().getFirst();
    }

    private static ProductPerformanceData.Row productRatios(ReportPeriod period, List<DailyAdset> snapshots,
            String netRevenue, ProductVisits visits) {
        return ProductPerformanceData.create(PRODUCT, List.of(period), snapshots, List.of(),
                Map.of(period.startDate(), new BigDecimal("1210")), Map.of(period.startDate(), 999L),
                visits == null ? Map.of() : Map.of(period.endDate(), visits),
                netRevenue == null ? Map.of() : Map.of(period.startDate(), new BigDecimal(netRevenue)))
                .rows().getFirst();
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

    private static DailyProductOffer offer(String date, long count, Integer stock, String price) {
        return new DailyProductOffer(LocalDate.parse(date), count, stock,
                price == null ? null : new BigDecimal(price));
    }

    private static DailyAdset snapshot(String date, String targeting, List<String> matchTypes, Primitives metrics) {
        return new DailyAdset(new AdsAdsetKey(VENDOR, LocalDate.parse(date), 17, 23), targeting,
                matchTypes, List.of("ACTUALPNK"), metrics);
    }

    private static void assertKeywordSectionsUnavailable(Map<String, Object> values) {
        values.forEach((key, value) -> {
            if (key.startsWith("broad_") || key.startsWith("exact_") || key.startsWith("total_") || key.endsWith("_share")) {
                assertNull(value, key);
            }
        });
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
