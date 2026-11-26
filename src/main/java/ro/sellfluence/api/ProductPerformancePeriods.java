package ro.sellfluence.api;

import ro.sellfluence.api.ProductPerformanceData.Period;
import ro.sellfluence.api.ProductPerformanceData.ReportPeriod;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Closed reporting windows and the current period through today's Romanian business date. */
final class ProductPerformancePeriods {
    private ProductPerformancePeriods() {}

    static List<ReportPeriod> create(Period mode, LocalDate firstActivity, LocalDate today) {
        if (firstActivity == null || firstActivity.isAfter(today)) return List.of();
        var periods = new ArrayList<ReportPeriod>();
        var end = mode.end(firstActivity);
        while (end.isBefore(today)) {
            periods.add(new ReportPeriod(end.minusDays(mode.windowDays() - 1L), end, mode.windowDays(), false));
            end = mode.end(end.plusDays(1));
        }

        var start = mode == Period.THIRTY_DAYS ? today.withDayOfMonth(1) : mode.start(today);
        int elapsedDays = Math.toIntExact(ChronoUnit.DAYS.between(start, today) + 1);
        periods.add(new ReportPeriod(start, today, elapsedDays, true));
        return List.copyOf(periods);
    }
}
