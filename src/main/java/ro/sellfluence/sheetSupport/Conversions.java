package ro.sellfluence.sheetSupport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.DAY_OF_WEEK;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;

public class Conversions {
    /**
     * Formatter, which is similar to {@link DateTimeFormatter#RFC_1123_DATE_TIME}
     * but assumes the year is only two digits.
     * It uses Unix epoch as a base date, thus 70-99 are interpreted as 1970-1999
     * while 00-69 are interpreted as 2000-2069.
     */
    private static final DateTimeFormatter rfc1123_2digit_year;

    /**
     * Formatter for parsing the date as seen in request_history date field.
     */
    public static final DateTimeFormatter requestHistoryFormat;

    /**
     * Formatter, which is similar to {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME}
     * but does separate date and time with a blank character instead of 'T'.
     */
    public static final DateTimeFormatter isoLikeLocalDateTime;
    public static final DateTimeFormatter isoLikeLocalDateTimeWithoutFractionalSeconds;
    private static final String emagFBEString = "eMAG FBE";
    private static final String emagNonFBEString = "eMAG NON-FBE";
    private static final String statusString = "Status:";
    private static final Pattern statusStringPattern = Pattern.compile(statusString + "(\\d+)");

    static {
        isoLikeLocalDateTime = new DateTimeFormatterBuilder().parseCaseInsensitive().append(ISO_LOCAL_DATE).appendLiteral(' ').append(ISO_LOCAL_TIME).toFormatter(Locale.ENGLISH);
        isoLikeLocalDateTimeWithoutFractionalSeconds = new DateTimeFormatterBuilder().parseCaseInsensitive()
                .append(ISO_LOCAL_DATE)
                .appendLiteral(' ')
                .appendValue(HOUR_OF_DAY, 2)
                .appendLiteral(':')
                .appendValue(MINUTE_OF_HOUR, 2)
                .optionalStart()
                .appendLiteral(':')
                .appendValue(SECOND_OF_MINUTE, 2)
                .toFormatter(Locale.ENGLISH);
        Map<Long, String> dayOfWeekEN = new HashMap<>();
        dayOfWeekEN.put(1L, "Mon");
        dayOfWeekEN.put(2L, "Tue");
        dayOfWeekEN.put(3L, "Wed");
        dayOfWeekEN.put(4L, "Thu");
        dayOfWeekEN.put(5L, "Fri");
        dayOfWeekEN.put(6L, "Sat");
        dayOfWeekEN.put(7L, "Sun");
        Map<Long, String> monthOfYearEN = new HashMap<>();
        monthOfYearEN.put(1L, "Jan");
        monthOfYearEN.put(2L, "Feb");
        monthOfYearEN.put(3L, "Mar");
        monthOfYearEN.put(4L, "Apr");
        monthOfYearEN.put(5L, "May");
        monthOfYearEN.put(6L, "Jun");
        monthOfYearEN.put(7L, "Jul");
        monthOfYearEN.put(8L, "Aug");
        monthOfYearEN.put(9L, "Sep");
        monthOfYearEN.put(10L, "Oct");
        monthOfYearEN.put(11L, "Nov");
        monthOfYearEN.put(12L, "Dec");
        rfc1123_2digit_year = new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().optionalStart().appendText(DAY_OF_WEEK, dayOfWeekEN).appendLiteral(", ").optionalEnd().appendValue(DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral(' ').appendText(MONTH_OF_YEAR, monthOfYearEN).appendLiteral(' ').appendValueReduced(YEAR, 2, 2, LocalDate.of(1970, 1, 1)).appendLiteral(", ").appendValue(HOUR_OF_DAY, 2).appendLiteral(':').appendValue(MINUTE_OF_HOUR, 2).appendLiteral(':').appendValue(SECOND_OF_MINUTE, 2).toFormatter(Locale.ENGLISH);
        requestHistoryFormat = new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().optionalStart()
                .appendValue(DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral(' ')
                .appendText(MONTH_OF_YEAR, monthOfYearEN).appendLiteral(' ')
                .appendValue(YEAR).appendLiteral(", ")
                .appendValue(HOUR_OF_DAY, 2).appendLiteral(':').appendValue(MINUTE_OF_HOUR, 2)
                .toFormatter(Locale.ENGLISH);
    }

    /**
     * Detect the template line so it can be excluded.
     *
     * @param row spreadsheet row.
     * @return true if it is a template line.
     */
    public static boolean isTemplate(List<String> row) {
        return row.get(7).equals("XXX.XX") && row.get(8).equals("XXX.XX");
    }

    /**
     * Convert a date as found in the spreadsheets to {@link LocalDateTime}.
     * It tries up to four different formats before giving up.
     * @param s date as string.
     * @return parsed date.
     */
    public static LocalDateTime toLocalDateTime(String s) {
        LocalDateTime date;
        try {
            date = LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            try {
                date = LocalDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
            } catch (Exception ex) {
                try {
                    date = LocalDateTime.parse(s, rfc1123_2digit_year);
                } catch (Exception exc) {
                    try {
                        date = LocalDateTime.parse(s, isoLikeLocalDateTime);
                    } catch (Exception exception) {
                        date = LocalDateTime.parse(s, requestHistoryFormat);
                    }
                }
            }
        }
        return date;
    }

    /**
     * Decodes the FBE column in the spreadsheet.
     *
     * @param fbe Textual information if it is FBE or not.
     * @return true if it is FBE, false otherwise.
     */
    public static boolean isEMAGFbe(String fbe) {
        return switch (fbe) {
            case emagFBEString -> true;
            case emagNonFBEString -> false;
            default -> throw new IllegalArgumentException("Unrecognized FBE value " + fbe);
        };
    }

    /**
     * Convert to the strings used in the spreadsheet.
     *
     * @param isFBE
     * @return
     */
    public static String booleanToFBE(boolean isFBE) {
        return isFBE ? emagFBEString : emagNonFBEString;
    }

    /**
     * Convert a numeric status to the value expected in the spreadsheet.
     *
     * @param status
     * @return
     */
    public static String statusToString(int status) {
        return switch (status) {
            case 4 -> "Finalizata";
            case 5 -> "Stornata";
            default -> "Status:" + status;
        };
    }

    /**
     * Convert a string s found in the spreadsheet to a numeric value.
     *
     * @param status
     * @return
     */
    public static int statusFromString(String status) {
        Matcher matcher = statusStringPattern.matcher(status);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return switch (status) {
            case "Finalizata" -> 4;
            case "Stornata" -> 5;
            default -> throw new IllegalArgumentException("Unknown status %s".formatted(status));
        };
    }
}