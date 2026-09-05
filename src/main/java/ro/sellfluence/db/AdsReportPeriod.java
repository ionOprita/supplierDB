package ro.sellfluence.db;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Inclusive reporting period for ads performance data.
 */
public record AdsReportPeriod(LocalDate dateFrom, LocalDate dateTo) {
    public AdsReportPeriod {
        Objects.requireNonNull(dateFrom, "dateFrom");
        Objects.requireNonNull(dateTo, "dateTo");
        if (dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("dateFrom must not be after dateTo");
        }
    }

    public static AdsReportPeriod singleDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return new AdsReportPeriod(date, date);
    }

    public boolean isSingleDay() {
        return dateFrom.equals(dateTo);
    }

    public String label() {
        return isSingleDay() ? dateFrom.toString() : dateFrom + " to " + dateTo;
    }
}
