package ro.sellfluence.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfitCalculatorMonthTest {
    @ParameterizedTest
    @CsvSource({
            "2026-11-16T10:00:00Z, UTC, 2026-10",
            "2026-10-31T21:59:59Z, UTC, 2026-09",
            "2026-10-31T22:00:00Z, America/Los_Angeles, 2026-10",
            "2026-09-30T21:30:00Z, UTC, 2026-09",
            "2026-12-31T22:00:00Z, America/Los_Angeles, 2026-12",
            "2024-02-29T22:00:00Z, Asia/Tokyo, 2024-02"
    })
    void selectsPreviousCalendarMonthInBucharestRegardlessOfServerClockZone(
            String instant, String clockZone, String expectedMonth) {
        var clock = Clock.fixed(Instant.parse(instant), ZoneId.of(clockZone));
        assertEquals(YearMonth.parse(expectedMonth), API.profitCalculatorPreviousMonth(clock));
    }
}
