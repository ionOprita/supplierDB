package ro.sellfluence.app;

import com.google.api.services.sheets.v4.model.CellData;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ReviewsTable.ExportReview;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferReviewsTest {
    @Test
    void mapsEveryColumnWithEmagTimestampsAndLiteralReviewText() {
        var review = new ExportReview(
                9_007_199_254_740_991L, 123L, "PNK", 5, "Blue\tLarge",
                "2026-09-29T01:30:17+03:00", "=1+1<br />hi\nthere", "A\tB", 456L,
                "hash", "registered", "2026-09-29T23:40:29-04:00", "Moderator"
        );

        var row = TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)).getFirst();

        assertEquals(13, row.size());
        assertEquals(9_007_199_254_740_991.0, number(row.get(0)));
        assertEquals(123.0, number(row.get(1)));
        assertEquals("PNK", string(row.get(2)));
        assertEquals(5.0, number(row.get(3)));
        assertEquals("Blue Large", string(row.get(4)));
        assertEquals(serial(2026, 9, 29) + (3_600 + 30 * 60 + 17) / 86_400.0, number(row.get(5)), 1e-9);
        assertEquals("=1+1 hi there", string(row.get(6)));
        assertEquals("A B", string(row.get(7)));
        assertEquals(456.0, number(row.get(8)));
        assertEquals("hash", string(row.get(9)));
        assertEquals("registered", string(row.get(10)));
        assertEquals(serial(2026, 9, 29) + (23 * 3_600 + 40 * 60 + 29) / 86_400.0, number(row.get(11)), 1e-9);
        assertEquals(serial(2026, 9, 30), number(row.get(12)));
    }

    @Test
    void leavesMissingValuesBlankAndUsesAnonymousName() {
        var review = new ExportReview(1L, null, "PNK", null, null, null,
                null, null, null, null, null, null, null);

        var row = TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)).getFirst();

        assertEquals(13, row.size());
        for (var index : List.of(1, 3, 4, 5, 6, 8, 9, 10, 11)) {
            assertNull(row.get(index).getUserEnteredValue(), "Column index " + index + " should be blank");
        }
        assertEquals("Anonymous", string(row.get(7)));
        assertEquals(List.of(), TransferReviews.toSheetRows(List.of(), LocalDate.of(2026, 9, 30)));
    }

    @Test
    void leavesBlankTimestampsEmpty() {
        var review = new ExportReview(1L, null, "PNK", null, null, " \t",
                null, null, null, null, null, "", null);

        var row = TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)).getFirst();

        assertNull(row.get(5).getUserEnteredValue());
        assertNull(row.get(11).getUserEnteredValue());
    }

    @Test
    void preservesFractionalSecondsAndAcceptsZuluTimestamps() {
        var review = new ExportReview(1L, null, "PNK", null, null, "2026-09-21T13:27:41.123456+03:00",
                null, null, null, null, null, "2026-09-22T00:00:00Z", null);

        var row = TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)).getFirst();

        assertEquals(serial(2026, 9, 21) + (13 * 3_600 + 27 * 60 + 41.123456) / 86_400.0,
                number(row.get(5)), 1e-9);
        assertEquals(serial(2026, 9, 22), number(row.get(11)));
    }

    @Test
    void rejectsMalformedNonemptyDates() {
        for (var field : List.of("created", "published")) {
            var review = new ExportReview(1L, null, "PNK", null, null,
                    field.equals("created") ? "not-a-date" : null,
                    null, null, null, null, null,
                    field.equals("published") ? "not-a-date" : null, null);

            var error = assertThrows(IllegalArgumentException.class,
                    () -> TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)));
            assertEquals("Invalid " + field + " timestamp for review 1: not-a-date", error.getMessage());
        }
    }

    private static String string(CellData cell) {
        return cell.getUserEnteredValue().getStringValue();
    }

    private static Double number(CellData cell) {
        return cell.getUserEnteredValue().getNumberValue();
    }

    private static double serial(int year, int month, int day) {
        return LocalDate.of(year, month, day).toEpochDay() + 25_569.0;
    }
}
