package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class APIOverviewTest {
    @ParameterizedTest
    @CsvSource({
            "2026-10-31T21:59:59Z, 2026-10-31",
            "2026-10-31T22:00:00Z, 2026-11-01",
            "2026-06-30T21:00:00Z, 2026-07-01",
            "2024-02-28T22:00:00Z, 2024-02-29",
            "2026-12-31T22:00:00Z, 2027-01-01"
    })
    void usesBucharestCalendarRegardlessOfClockZone(String instant, String expected) {
        for (var zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}) {
            var clock = Clock.fixed(Instant.parse(instant), ZoneId.of(zone));
            assertEquals(LocalDate.parse(expected), API.overviewDate(clock));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-31T21:59:59Z, 2026-10-31",
            "2026-10-31T22:00:00Z, 2026-11-01",
            "2026-06-30T21:00:00Z, 2026-07-01",
            "2024-02-28T22:00:00Z, 2024-02-29",
            "2026-12-31T22:00:00Z, 2027-01-01"
    })
    void defaultsToTodayAndLimitsTheCurrentMonthToToday(String instant, String expected) {
        for (var zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}) {
            var clock = Clock.fixed(Instant.parse(instant), ZoneId.of(zone));
            var today = LocalDate.parse(expected);
            assertEquals(today, API.overviewThroughDate(null, clock));
            assertEquals(today, API.overviewThroughDate(YearMonth.from(today), clock));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-03T10:00:00Z, 2026-10, 2026-10-31",
            "2026-11-03T10:00:00Z, 2024-02, 2024-02-29",
            "2026-11-03T10:00:00Z, 2025-02, 2025-02-28",
            "2026-11-03T10:00:00Z, 0001-01, 0001-01-31",
            "2026-12-31T21:59:59Z, 2026-11, 2026-11-30",
            "2026-12-31T22:00:00Z, 2026-12, 2026-12-31",
            "2026-06-30T21:00:00Z, 2026-06, 2026-06-30"
    })
    void includesEveryDayOfAPastMonth(String instant, String month, String expected) {
        var clock = Clock.fixed(Instant.parse(instant), ZoneId.of("America/Los_Angeles"));

        assertEquals(LocalDate.parse(expected), API.overviewThroughDate(YearMonth.parse(month), clock));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-31T21:59:59Z, 2026-11",
            "2026-10-31T22:00:00Z, 2026-12",
            "2026-12-31T21:59:59Z, 2027-01",
            "2026-12-31T22:00:00Z, 2027-02"
    })
    void rejectsFutureMonthsAtBucharestMonthAndYearBoundaries(String instant, String month) {
        var clock = Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Tokyo"));

        assertThrows(IllegalArgumentException.class,
                () -> API.overviewThroughDate(YearMonth.parse(month), clock));
    }

    @Test
    void acceptsOnlyFourDigitPositiveYears() {
        var clock = Clock.fixed(Instant.parse("9999-12-01T10:00:00Z"), ZoneId.of("UTC"));

        assertEquals(LocalDate.of(9999, 12, 1), API.overviewThroughDate(YearMonth.of(9999, 12), clock));
        assertEquals(LocalDate.of(1, 1, 31), API.overviewThroughDate(YearMonth.of(1, 1), clock));
        for (int year : new int[]{-1, 0, 10000}) {
            assertThrows(IllegalArgumentException.class,
                    () -> API.overviewThroughDate(YearMonth.of(year, 1), clock));
        }
    }

    @Test
    void readsTheClockOnceWhenResolvingTheMonth() {
        var clock = new Clock() {
            private int reads;

            @Override
            public ZoneId getZone() {
                return ZoneId.of("Europe/Bucharest");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                assertEquals(getZone(), zone);
                return this;
            }

            @Override
            public Instant instant() {
                if (++reads > 1) throw new AssertionError("The overview request read the clock more than once.");
                return Instant.parse("2026-10-31T21:59:59Z");
            }
        };

        assertEquals(LocalDate.of(2026, 10, 31), API.overviewThroughDate(null, clock));
    }
}
