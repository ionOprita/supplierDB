package ro.sellfluence.api;

import ro.sellfluence.api.ProductPerformanceData.Period;
import ro.sellfluence.api.ProductPerformanceData.ReportPeriod;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Calendar closing dates and a separately labelled rolling preview, in the Romanian business calendar. */
final class ProductPerformancePeriods {
    private ProductPerformancePeriods() {}

    static List<ReportPeriod> create(Period mode, LocalDate firstActivity, LocalDate today,
                                    LocalDate latestCompletedSnapshot) {
        if (firstActivity == null || !firstActivity.isBefore(today)) return List.of();
        var periods = new ArrayList<ReportPeriod>();
        var end = mode.end(firstActivity);
        while (end.isBefore(today)) {
            periods.add(new ReportPeriod(end.minusDays(mode.windowDays() - 1L), end, mode.windowDays(), false));
            end = mode.end(end.plusDays(1));
        }

        // A full rolling counter must never be placed on a partially elapsed fixed interval.
        // Ignore observations on today's still-replaceable snapshot, or before the last closing date.
        var latestClosed = mode.end(today.minusDays(1));
        if (!latestClosed.isBefore(today)) {
            latestClosed = mode == Period.THIRTY_DAYS ? today.withDayOfMonth(1).minusDays(1)
                    : latestClosed.minusDays(14);
        }
        if (latestCompletedSnapshot != null && latestCompletedSnapshot.isBefore(today)
                && !latestCompletedSnapshot.isBefore(firstActivity)
                && latestCompletedSnapshot.isAfter(latestClosed)) {
            periods.add(new ReportPeriod(latestCompletedSnapshot.minusDays(mode.windowDays() - 1L),
                    latestCompletedSnapshot, mode.windowDays(), true));
        }
        return List.copyOf(periods);
    }
}
