package ro.sellfluence.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

public class UsefulMethods {
    /**
     * Null safe comparison of two BigDecimal numbers.
     *
     * @param bd1 first number
     * @param bd2 second number
     * @return true if they represent the same value.
     */
    public static boolean bdEquals(BigDecimal bd1, BigDecimal bd2) {
        if (bd1 == null) {
            return bd2 == null;
        }
        if (bd2 == null) {
            return false;
        }
        return bd1.compareTo(bd2) == 0; // Both are non-null, use compareTo
    }

    /**
     * Null safe setting scale of BigDecimal.
     *
     * @param bd value to adjust scale.
     * @return scaled value.
     */
    public static BigDecimal round(BigDecimal bd) {
        return bd == null ? null : bd.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Extended and null safe test for a string to be blank.
     *
     * @param s String or null
     * @return true if the string is null or blank.
     */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Extended and null safe test for a string to be empty.
     *
     * @param s String or null
     * @return true if the string is null or emtpy.
     */
    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * Extended test for a list to be empty.
     * In some cases, a list might contain a single empty string.
     * Such a list is also considered to be empty.
     *
     * @param list List of string or null
     * @return true if the list is to be considered empty.
     */
    public static boolean isEmpty(List<? extends String> list) {
        return list == null || list.isEmpty() || (list.size() == 1 && isEmpty(list.getFirst()));
    }

    public static Date toDate(LocalDate localDate) {
        return localDate == null ? null : Date.valueOf(localDate);
    }

    public static Date toDate(YearMonth yearMonth) {
        return yearMonth == null ? null : Date.valueOf(yearMonth.atDay(1));
    }

    public static Timestamp toTimestamp(LocalDateTime localDateTime) {
        return localDateTime == null ? null : Timestamp.valueOf(localDateTime);
    }

    public static Timestamp toTimestamp(LocalDate localDate) {
        return localDate == null ? null : Timestamp.valueOf(localDate.atStartOfDay());
    }

    public static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public static LocalDate toLocalDate(Timestamp timestamp) {
        return timestamp == null ? null : toLocalDateTime(timestamp).toLocalDate();
    }

    public static YearMonth toYearMonth(Date date) {
        return date == null ? null : YearMonth.from(date.toLocalDate());
    }
}