package ro.sellfluence.app;

import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ReviewsTable.ExportReview;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Logs;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;

/** Replaces the review values in the Date Recenzii spreadsheet from the database snapshot. */
public final class TransferReviews {
    private static final Logger logger = Logs.getConsoleAndFileLogger("TransferReviews", INFO, 10, 1_000_000);
    private static final String SPREADSHEET_NAME = "Date Recenzii";
    private static final String TAB_NAME = "Introducere date";
    private static final int FIRST_DATA_ROW = 3;
    private static final int FIRST_DATA_COLUMN = 1;
    private static final LocalDate SHEETS_EPOCH = LocalDate.of(1899, 12, 30);
    private static final Pattern BR_TAG = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_WHITESPACE = Pattern.compile("[\\r\\n\\t]+");
    private static final Map<Integer, String> DATE_FORMATS = Map.of(
            6, "dd/MM/yyyy hh:mm:ss", 12, "dd/MM/yyyy hh:mm:ss", 13, "dd/MM/yyyy"
    );

    private TransferReviews() {
    }

    public static void transferReviews(EmagMirrorDB db, Clock clock) throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(clock, "clock");
        LocalDate extractionDate = LocalDate.now(clock);
        List<ExportReview> reviews = db.readReviewExportRows();
        List<List<CellData>> rows = toSheetRows(reviews, extractionDate);

        var sheet = SheetsAPI.getSpreadSheetByName(defaultGoogleApp, SPREADSHEET_NAME);
        if (sheet == null) {
            throw new IllegalStateException("Could not find the spreadsheet " + SPREADSHEET_NAME);
        }
        if (sheet.getSheetId(TAB_NAME) == null) {
            throw new IllegalStateException("Could not find tab " + TAB_NAME + " in " + SPREADSHEET_NAME);
        }
        sheet.replaceCellValuesFrom(TAB_NAME, FIRST_DATA_ROW, FIRST_DATA_COLUMN, rows, DATE_FORMATS);
        logger.info(() -> "Transferred " + rows.size() + " review rows to " + SPREADSHEET_NAME + " / " + TAB_NAME);
    }

    /** Package-private to verify column order and value types without a live Google Sheets connection. */
    static List<List<CellData>> toSheetRows(List<ExportReview> reviews, LocalDate extractionDate) {
        Objects.requireNonNull(reviews, "reviews");
        Objects.requireNonNull(extractionDate, "extractionDate");
        var rows = new ArrayList<List<CellData>>(reviews.size());
        for (var review : reviews) {
            var created = parseEmagDateTime(review.created(), "created", review.reviewId());
            var published = parseEmagDateTime(review.published(), "published", review.reviewId());
            rows.add(List.of(
                    numberCell(review.reviewId()),
                    numberCell(review.productFamilyId()),
                    textCell(review.pnk()),
                    numberCell(review.rating()),
                    textCell(cleanOption(review.optionValue())),
                    dateTimeCell(created),
                    textCell(cleanContent(review.content())),
                    textCell(cleanName(review.clientName())),
                    numberCell(review.clientId()),
                    textCell(review.clientHash()),
                    textCell(review.clientType()),
                    dateTimeCell(published),
                    dateCell(extractionDate)
            ));
        }
        return rows;
    }

    private static LocalDateTime parseEmagDateTime(String value, String field, long reviewId) {
        if (value == null || value.isBlank()) return null;
        try {
            // Preserve eMag's wall-clock time; Sheets serial dates do not carry a timezone.
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + field + " timestamp for review " + reviewId + ": " + value, e);
        }
    }

    private static String cleanContent(String content) {
        if (content == null) return "";
        return CONTENT_WHITESPACE.matcher(BR_TAG.matcher(content).replaceAll(" ")).replaceAll(" ").trim();
    }

    private static String cleanName(String name) {
        return (name == null || name.isEmpty() ? "Anonymous" : name).replace('\t', ' ').trim();
    }

    private static String cleanOption(String value) {
        return value == null ? "" : value.replace('\t', ' ');
    }

    private static CellData textCell(String value) {
        return value == null || value.isEmpty() ? new CellData()
                : new CellData().setUserEnteredValue(new ExtendedValue().setStringValue(value));
    }

    private static CellData numberCell(Number value) {
        return value == null ? new CellData()
                : new CellData().setUserEnteredValue(new ExtendedValue().setNumberValue(value.doubleValue()));
    }

    private static CellData dateCell(LocalDate date) {
        return date == null ? new CellData()
                : numberCell(ChronoUnit.DAYS.between(SHEETS_EPOCH, date));
    }

    private static CellData dateTimeCell(LocalDateTime dateTime) {
        return dateTime == null ? new CellData()
                : numberCell(ChronoUnit.DAYS.between(SHEETS_EPOCH, dateTime.toLocalDate())
                + dateTime.toLocalTime().toNanoOfDay() / (double) ChronoUnit.DAYS.getDuration().toNanos());
    }
}
