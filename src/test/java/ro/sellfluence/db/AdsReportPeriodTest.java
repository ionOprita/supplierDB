package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsReportPeriodTest {
    @Test
    void createsInclusiveDateRange() {
        var dateFrom = LocalDate.of(2026, 7, 1);
        var dateTo = LocalDate.of(2026, 7, 31);

        var period = new AdsReportPeriod(dateFrom, dateTo);

        assertEquals(dateFrom, period.dateFrom());
        assertEquals(dateTo, period.dateTo());
        assertFalse(period.isSingleDay());
        assertEquals("2026-07-01 to 2026-07-31", period.label());
    }

    @Test
    void createsSingleDayPeriod() {
        var date = LocalDate.of(2026, 7, 15);

        var period = AdsReportPeriod.singleDay(date);

        assertEquals(date, period.dateFrom());
        assertEquals(date, period.dateTo());
        assertTrue(period.isSingleDay());
        assertEquals("2026-07-15", period.label());
    }

    @Test
    void rejectsReversedPeriod() {
        assertThrows(IllegalArgumentException.class, () -> new AdsReportPeriod(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 7, 31)
        ));
    }

    @Test
    void rejectsNullDates() {
        var date = LocalDate.of(2026, 7, 15);

        assertThrows(NullPointerException.class, () -> new AdsReportPeriod(null, date));
        assertThrows(NullPointerException.class, () -> new AdsReportPeriod(date, null));
        assertThrows(NullPointerException.class, () -> AdsReportPeriod.singleDay(null));
    }
}
