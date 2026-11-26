package ro.sellfluence.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ro.sellfluence.api.ProductPerformanceData.Period.*;

class ProductPerformancePeriodsTest {
    @Test
    void fortnightlyHistoryUsesTheSameAnchorBeforeAndAfterNovemberSecond() {
        var rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, date("2026-10-20"), date("2026-12-14"));
        assertEquals(List.of("2026-10-19/2026-11-01", "2026-11-02/2026-11-15",
                "2026-11-16/2026-11-29", "2026-11-30/2026-12-13", "2026-12-14/2026-12-14"), ranges(rows));
        assertSingleOpenRow(rows, 1);
    }

    @Test
    void monthEndRowsAlwaysHaveThirtyDatesEvenInLeapYears() {
        var rows = ProductPerformancePeriods.create(THIRTY_DAYS, date("2023-12-20"), date("2024-04-01"));
        assertEquals(List.of("2023-12-02/2023-12-31", "2024-01-02/2024-01-31",
                "2024-01-31/2024-02-29", "2024-03-02/2024-03-31", "2024-04-01/2024-04-01"), ranges(rows));
        assertSingleOpenRow(rows, 1);
        assertTrue(rows.stream().filter(row -> !row.provisional()).allMatch(row -> row.windowDays() == 30));
    }

    @Test
    void closingDayStaysOpenUntilMidnightAndTheNextFortnightStartsAtOneDay() {
        var first = date("2026-11-02");
        var rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, first, date("2026-11-15"));
        assertEquals(List.of("2026-11-02/2026-11-15"), ranges(rows));
        assertSingleOpenRow(rows, 14);
        rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, first, date("2026-11-16"));
        assertEquals(List.of("2026-11-02/2026-11-15", "2026-11-16/2026-11-16"), ranges(rows));
        assertSingleOpenRow(rows, 1);
    }

    @Test
    void currentMonthStartsOnTheFirstAndIncludesEveryElapsedDateInShortAndLongMonths() {
        for (String today : List.of("2025-02-28", "2024-02-29", "2026-10-31", "2026-11-06")) {
            var end = date(today);
            var rows = ProductPerformancePeriods.create(THIRTY_DAYS, end, end);
            assertEquals(List.of(end.withDayOfMonth(1) + "/" + end), ranges(rows));
            assertSingleOpenRow(rows, end.getDayOfMonth());
        }
    }

    @Test
    void firstActivityTodayQualifiesAndOnlyAbsentOrFutureActivityProducesNoPeriod() {
        var today = date("2026-11-26");
        for (var mode : List.of(FOURTEEN_DAYS, THIRTY_DAYS)) {
            assertTrue(ProductPerformancePeriods.create(mode, null, today).isEmpty());
            assertTrue(ProductPerformancePeriods.create(mode, today.plusDays(1), today).isEmpty());
        }
        var rows = ProductPerformancePeriods.create(FOURTEEN_DAYS, today, today);
        assertEquals(List.of("2026-11-16/2026-11-26"), ranges(rows));
        assertSingleOpenRow(rows, 11);
    }

    private static void assertSingleOpenRow(List<ProductPerformanceData.ReportPeriod> rows, int elapsedDays) {
        assertTrue(rows.getLast().provisional());
        assertEquals(1L, rows.stream().filter(ProductPerformanceData.ReportPeriod::provisional).count());
        assertEquals(elapsedDays, rows.getLast().windowDays());
    }

    private static LocalDate date(String value) { return LocalDate.parse(value); }

    private static List<String> ranges(List<ProductPerformanceData.ReportPeriod> rows) {
        return rows.stream().map(it -> it.startDate() + "/" + it.endDate()).toList();
    }
}
