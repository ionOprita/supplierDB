package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductPerformanceHistoryTable.ImportBatch;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPerformanceHistoryTableTest {
    @Test
    void snapshotsMetricMapWithoutTurningNullsIntoZeros() {
        var original = new LinkedHashMap<String, Object>();
        original.put("clicks", null);
        original.put("spend", 0);
        var observation = observation(original);

        original.put("clicks", 100);

        assertNull(observation.values().get("clicks"));
        assertEquals(0, observation.values().get("spend"));
        assertThrows(UnsupportedOperationException.class, () -> observation.values().put("clicks", 2));
    }

    @Test
    void rejectsDuplicateObservationsAndMalformedOrNonFiniteEvidence() {
        var observation = observation(Map.of("clicks", 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ImportBatch("a".repeat(64), "{}", List.of(observation, observation)));
        assertThrows(IllegalArgumentException.class, () -> new ImportBatch("not-a-hash", "{}", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ImportBatch("a".repeat(64), "null", List.of()));
        assertThrows(IllegalArgumentException.class, () -> observation(Map.of("clicks", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> observation(Map.of("clicks", List.of(1, 2))));
    }

    @Test
    void rejectsConflictingSameBatchObservationsEvenWhenTheyComeFromDifferentFiles() {
        var first = observation(Map.of("clicks", 10));
        var otherSource = new Observation(first.vendorId(), first.productCode(), first.pnk(),
                first.observationDate(), first.domain(), Map.of("clicks", 20),
                "another-source", 42, "Other sheet", "B2:CF2");

        assertThrows(IllegalArgumentException.class, () ->
                new ImportBatch("a".repeat(64), "{}", List.of(first, otherSource)));
    }

    @Test
    void metricPresenceRecognizesEveryDomainMetricAndPreservesMeasuredZeros() {
        for (var key : List.of("stock", "gmv", "clicks", "salesPrice", "sourceConversion", "performance")) {
            assertTrue(observation("organic", Map.of(key, 0)).hasMetricValues(), key);
        }
        for (var category : List.of("auto", "broad", "exact", "product")) {
            for (var primitive : List.of("impressions", "clicks", "spend", "sales", "units", "salesCount")) {
                var key = category + "_" + primitive;
                assertTrue(observation("ads", Map.of(key, 0)).hasMetricValues(), key);
            }
        }
        assertTrue(observation("organic", Map.of("performance", "Cold")).hasMetricValues());
        assertTrue(observation("reviews", Map.of("reviews", 0)).hasMetricValues());
        assertTrue(observation("rating", Map.of("rating", 0)).hasMetricValues());
    }

    @Test
    void metricPresenceIgnoresEmptyCorrectionsMetadataAndMetricsFromOtherDomains() {
        for (var domain : List.of("organic", "ads", "reviews", "rating")) {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("familyId", "family-123");
            metadata.put("sourceStartDate", "2026-04-12");
            metadata.put("sourceEndDate", "2026-05-11");
            metadata.put("sourceWindowDays", 30);
            metadata.put("zeroRestoredMetrics", "auto_clicks");
            assertFalse(observation(domain, metadata).hasMetricValues(), domain);
            assertFalse(observation(domain, Map.of()).hasMetricValues(), domain);
        }
        var keysByDomain = Map.of("organic", "clicks", "ads", "auto_clicks",
                "reviews", "reviews", "rating", "rating");
        keysByDomain.forEach((domain, key) -> {
            var empty = new LinkedHashMap<String, Object>();
            empty.put(key, null);
            assertFalse(observation(domain, empty).hasMetricValues(), domain);
            assertFalse(observation(domain, Map.of(key, " \t\n")).hasMetricValues(), domain);
        });
        assertFalse(observation("organic", Map.of("rating", 4.9, "auto_clicks", 20)).hasMetricValues());
        assertFalse(observation("ads", Map.of("clicks", 20, "total_clicks", 20,
                "auto_conversion", 0.1, "auto_spend_share", 0.2)).hasMetricValues());
        assertFalse(observation("reviews", Map.of("rating", 4.9)).hasMetricValues());
        assertFalse(observation("rating", Map.of("reviews", 20)).hasMetricValues());
    }

    private static Observation observation(Map<String, Object> values) {
        return observation("organic", values);
    }

    private static Observation observation(String domain, Map<String, Object> values) {
        return new Observation(UUID.randomUUID(), "P1", "PNK1", LocalDate.of(2026, 5, 11),
                domain, values, "source", 0, "Sheet", "A1:CE1");
    }
}
