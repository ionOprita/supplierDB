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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static Observation observation(Map<String, Object> values) {
        return new Observation(UUID.randomUUID(), "P1", "PNK1", LocalDate.of(2026, 5, 11),
                "organic", values, "source", 0, "Sheet", "A1:CE1");
    }
}
