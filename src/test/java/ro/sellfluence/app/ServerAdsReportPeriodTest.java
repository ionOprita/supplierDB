package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.AdsReportPeriod;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerAdsReportPeriodTest {
    @Test
    void parsesLegacyDate() {
        assertEquals(
                AdsReportPeriod.singleDay(LocalDate.of(2026, 7, 15)),
                Server.parseAdsReportPeriod("2026-07-15", null, null)
        );
    }

    @Test
    void parsesInclusiveDateRange() {
        assertEquals(
                new AdsReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
                Server.parseAdsReportPeriod(null, "2026-07-01", "2026-07-31")
        );
    }

    @Test
    void rejectsMissingAndPartialPeriods() {
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod(null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod(null, "2026-07-01", null));
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod(null, null, "2026-07-31"));
    }

    @Test
    void rejectsMixedLegacyAndRangeParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod("2026-07-15", "2026-07-01", "2026-07-31"));
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod("2026-07-15", "2026-07-01", null));
    }

    @Test
    void rejectsInvalidAndReversedDates() {
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod("not-a-date", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod(null, "2026-07-31", "2026-07-01"));
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseAdsReportPeriod(null, "", "2026-07-31"));
    }
}
