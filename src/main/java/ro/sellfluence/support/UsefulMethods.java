package ro.sellfluence.support;

import org.postgresql.util.PGInterval;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

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
     * @return true if the string is null or empty.
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

    public static Duration toDuration(PGInterval pgInterval) {
        return pgInterval != null
                ? Duration.ofSeconds((long) pgInterval.getSeconds())
                .plusDays(pgInterval.getDays())
                .plusHours(pgInterval.getHours())
                .plusMinutes(pgInterval.getMinutes())
                : null;
    }

    public static YearMonth toYearMonth(Date date) {
        return date == null ? null : YearMonth.from(date.toLocalDate());
    }

    /**
     * Helper function to convert a column number to its corresponding letters.
     */
    public static String toColumnName(int columnNumber) {
        require(columnNumber > 0, "The column number must be positive.");
        var columnName = new StringBuilder();
        while (columnNumber > 0) {
            int modulo = (columnNumber - 1) % 26;
            columnName.insert(0, (char) ('A' + modulo));
            columnNumber = (columnNumber - modulo - 1) / 26;
        }
        return columnName.toString();
    }

    /**
     * Reference date of Google Sheets serial numbers.
     */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private static final LocalDateTime EXCEL_EPOCH_TIME = EXCEL_EPOCH.atStartOfDay();

    /**
     * Convert a Google Sheets serial number to a LocalDate.
     *
     * @param serial serial number as read from the spreadsheet.
     * @return LocalDate.
     */
    public static LocalDate sheetToLocalDate(BigDecimal serial) {
        if (serial == null) {
            return null;
        }
        long serialDays = serial.longValue();
        return EXCEL_EPOCH.plusDays(serialDays);
    }

    /**
     * Convert a Google Sheets serial number to a LocalDateTime.
     *
     * @param serial serial number as read from the spreadsheet.
     * @return LocalDate.
     */
    public static LocalDateTime sheetToLocalDateTime(BigDecimal serial) {
        if (serial == null) {
            return null;
        }
        long serialDays = serial.longValue();
        BigDecimal fractional = serial.remainder(BigDecimal.ONE); // Fraction = time of day

        // Convert fractional day to seconds and add to the date
        long secondsOfDay = fractional.multiply(BigDecimal.valueOf(86400)).longValue(); // 86400 seconds in a day
        return EXCEL_EPOCH_TIME.plusDays(serialDays).plusSeconds(secondsOfDay).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Find the column number for a given month.
     *
     * @param row data from the spreadsheet
     * @param month month to look for.
     * @return column identifier.
     */
    public static String findColumnMatchingMonth(List<Object> row, YearMonth month) {
        String columnIdentifier = null;
        var columnNumber = 1;
        for (Object it : row) {
            if (it instanceof BigDecimal dateSerial) {
                LocalDate localDate = sheetToLocalDate(dateSerial);
                if (YearMonth.from(localDate).equals(month)) {
                    columnIdentifier = toColumnName(columnNumber);
                }
            }
            columnNumber++;
        }
        if (columnIdentifier == null) {
            throw new RuntimeException("Could not find the column for the month %s.".formatted(month));
        }
        return columnIdentifier;
    }

    /**
     * Validates the provided condition and throws an {@link IllegalStateException} with the specified message
     * if the condition is false.
     *
     * @param condition the boolean condition to be checked. If false, an exception is thrown.
     * @param message   the exception message to be used in case the condition is not met.
     */
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /**
     * Validates the provided condition and throws an {@link IllegalStateException} with the specified message
     * if the condition is false. The message is supplied dynamically through a {@link Supplier}.
     *
     * @param condition the boolean condition to be checked. If false, an exception is thrown.
     * @param message   a {@link Supplier} that provides the exception message to be used if the condition is not met.
     */
    public static void require(boolean condition, Supplier<String> message) {
        if (!condition) throw new IllegalStateException(message.get());
    }

    public static String getStackTraceAsString(Throwable e) {
        if (e == null) { return ""; }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }


}