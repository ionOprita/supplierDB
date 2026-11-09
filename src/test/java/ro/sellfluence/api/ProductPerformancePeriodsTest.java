package ro.sellfluence.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ro.sellfluence.api.ProductPerformanceData.Period.*;

class ProductPerformancePeriodsTest {
    @Test
    void fortnightlyHistoryUsesTheSameAnchorBeforeAndAfterNovemberSecond() {
        var rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, date("2026-10-20"), date("2026-12-14"), null);
        assertEquals(List.of("2026-10-19/2026-11-01", "2026-11-02/2026-11-15",
                "2026-11-16/2026-11-29", "2026-11-30/2026-12-13"), ranges(rows));
        assertTrue(rows.stream().noneMatch(ProductPerformanceData.ReportPeriod::provisional));
    }

    @Test
    void monthEndRowsAlwaysHaveThirtyDatesEvenInLeapYears() {
        var rows = ProductPerformancePeriods.create(THIRTY_DAYS, date("2023-12-20"), date("2024-04-01"), null);
        assertEquals(List.of("2023-12-02/2023-12-31", "2024-01-02/2024-01-31",
                "2024-01-31/2024-02-29", "2024-03-02/2024-03-31"), ranges(rows));
        assertTrue(rows.stream().allMatch(row -> row.windowDays() == 30));
    }

    @Test
    void closingDayStaysOpenUntilMidnightAndPreviewAlwaysSpansFourteenDates() {
        var rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, date("2026-11-02"),
                date("2026-11-15"), date("2026-11-14"));
        assertEquals(List.of("2026-11-01/2026-11-14"), ranges(rows));
        assertTrue(rows.getFirst().provisional());
        rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, date("2026-11-02"),
                date("2026-11-16"), date("2026-11-15"));
        assertEquals(List.of("2026-11-02/2026-11-15"), ranges(rows));
        assertFalse(rows.getFirst().provisional());
    }

    @Test
    void monthPreviewOverlapsHistoryButDoesNotChangeHistoricalDates() {
        var rows = ProductPerformancePeriods.create(THIRTY_DAYS, date("2026-10-01"),
                date("2026-11-06"), date("2026-11-05"));
        assertEquals(List.of("2026-10-02/2026-10-31", "2026-10-07/2026-11-05"), ranges(rows));
        assertFalse(rows.getFirst().provisional());
        assertTrue(rows.getLast().provisional());
    }

    @Test
    void missingStaleFutureOrSameDaySnapshotsNeverBecomePreviews() {
        for (var mode : List.of(FOURTEEN_DAYS, THIRTY_DAYS)) {
            var baseline = ProductPerformancePeriods.create(mode, date("2026-10-01"), date("2026-11-16"), null);
            for (var snapshot : List.of("2026-10-01", "2026-11-16", "2026-11-17")) {
                assertEquals(baseline, ProductPerformancePeriods.create(mode, date("2026-10-01"),
                        date("2026-11-16"), date(snapshot)));
            }
            assertTrue(ProductPerformancePeriods.create(mode, null, date("2026-11-16"), date("2026-11-15")).isEmpty());
            assertTrue(ProductPerformancePeriods.create(mode, date("2026-11-17"), date("2026-11-16"), null).isEmpty());
        }
    }

    private static LocalDate date(String value) { return LocalDate.parse(value); }

    private static List<String> ranges(List<ProductPerformanceData.ReportPeriod> rows) {
        return rows.stream().map(it -> it.startDate() + "/" + it.endDate()).toList();
    }
}
