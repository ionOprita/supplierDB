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
    void mapsEveryColumnWithUtcDatesAndLiteralReviewText() {
        var review = new ExportReview(
                9_007_199_254_740_993L, 123L, "PNK", 5, "Blue\tLarge",
                "2026-09-29T01:30:00+03:00", "=1+1<br />hi\nthere", "A\tB", 456L,
                "hash", "registered", "2026-09-29T00:40:00-04:00", "Moderator"
        );

        var row = TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)).getFirst();

        assertEquals(16, row.size());
        assertEquals("9007199254740993", string(row.get(0)));
        assertEquals("123", string(row.get(1)));
        assertEquals("PNK", string(row.get(2)));
        assertEquals(5.0, number(row.get(3)));
        assertEquals("Blue Large", string(row.get(4)));
        assertEquals(serial(2026, 9, 28), number(row.get(5)));
        assertEquals("=1+1 hi there", string(row.get(6)));
        assertEquals("A B", string(row.get(7)));
        assertEquals("456", string(row.get(8)));
        assertEquals("hash", string(row.get(9)));
        assertEquals("registered", string(row.get(10)));
        assertEquals(22.0, number(row.get(11)));
        assertEquals(serial(2026, 9, 29), number(row.get(12)));
        assertEquals(4.0, number(row.get(13)));
        assertEquals("Moderator", string(row.get(14)));
        assertEquals(serial(2026, 9, 30), number(row.get(15)));
    }

    @Test
    void leavesMissingValuesBlankAndUsesAnonymousName() {
        var review = new ExportReview(1L, null, "PNK", null, null, null,
                null, null, null, null, null, null, null);

        var row = TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)).getFirst();

        for (var index : List.of(1, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14)) {
            assertNull(row.get(index).getUserEnteredValue(), "Column index " + index + " should be blank");
        }
        assertEquals("Anonymous", string(row.get(7)));
        assertEquals(List.of(), TransferReviews.toSheetRows(List.of(), LocalDate.of(2026, 9, 30)));
    }

    @Test
    void rejectsMalformedNonemptyDates() {
        var review = new ExportReview(1L, null, "PNK", null, null, "not-a-date",
                null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> TransferReviews.toSheetRows(List.of(review), LocalDate.of(2026, 9, 30)));
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
