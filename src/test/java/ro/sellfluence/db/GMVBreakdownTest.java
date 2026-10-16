package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.math.RoundingMode.HALF_EVEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GMVBreakdownTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate END = LocalDate.of(2026, 2, 15);

    @ParameterizedTest
    @CsvSource({
            "2026-01-15, 2026-02-15, 1, 3, 5, 33.00",
            "2026-01-20, 2026-02-10, 1, 3, 5, 33.00",
            "2026-01-15, 2026-02-16, 1, 4, 4, 40.00",
            "2026-01-14, 2026-02-15, 1, -1, 5, -11.00",
            "2026-01-14, 2026-02-16, 0, 0, 0, 0.00",
            "2026-01-13, 2026-01-14, 0, 0, 0, 0.00",
            "2026-02-16, 2026-02-17, 0, 0, 0, 0.00"
    })
    void usesOnlyTheSnapshotAndSignedQuantityThatContributeToThePeriod(
            String finalizedDate, String stornoDate, int rowCount, long quantity, int sourceStatus, String total) {
        var finalized = snapshot("order", 101, 10, finalizedDate, 4, 4, 4, 0, "10.00");
        var storno = snapshot("order", 102, 10, stornoDate, 5, 3, 4, 1, "11.00");
        var orders = Map.of("order", List.of(finalized, storno));

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, START, END);

        assertEquals(new BigDecimal(total), breakdown.total());
        assertEquals(rowCount, breakdown.contributions().size());
        if (rowCount == 1) {
            var contribution = breakdown.contributions().getFirst();
            assertSame(sourceStatus == 4 ? finalized : storno, contribution.source());
            assertEquals(quantity, contribution.quantity());
            assertEquals(new BigDecimal(total), contribution.amount());
        }
        assertReconciles(orders, START, END, breakdown);
    }

    @ParameterizedTest
    @CsvSource({
            "4, 2026-01-14, false",
            "4, 2026-01-15, true",
            "4, 2026-02-15, true",
            "4, 2026-02-16, false",
            "5, 2026-01-14, false",
            "5, 2026-01-15, true",
            "5, 2026-02-15, true",
            "5, 2026-02-16, false"
    })
    void loneSnapshotsContributeTheirActualQuantityWithoutSubtractingStornoAgain(
            int status, String date, boolean included) {
        var source = snapshot("order", 101, 10, date, status, 2, 3, 1, "12.35");
        var orders = Map.of("order", List.of(source));

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, START, END);

        assertEquals(included ? List.of(new GMV.Contribution(source, 2, new BigDecimal("24.70"))) : List.of(),
                breakdown.contributions());
        assertReconciles(orders, START, END, breakdown);
    }

    @Test
    void retainsTheStornoRowForAFullyReturnedOrderWithZeroContribution() {
        var finalized = snapshot("order", 101, 10, "2026-01-15", 4, 4, 4, 0, "10.00");
        var storno = snapshot("order", 102, 10, "2026-02-15", 5, 0, 4, 4, "11.00");
        var orders = Map.of("order", List.of(finalized, storno));

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, START, END);

        assertEquals(new BigDecimal("0.00"), breakdown.total());
        assertEquals(List.of(new GMV.Contribution(storno, 0, new BigDecimal("0.00"))), breakdown.contributions());
        assertReconciles(orders, START, END, breakdown);
    }

    @Test
    void subtractsFullStornoQuantityWhenItsFinalizedSnapshotIsOutsideThePeriod() {
        var finalized = snapshot("order", 101, 10, "2026-01-14", 4, 4, 4, 0, "10.00");
        var storno = snapshot("order", 102, 10, "2026-02-15", 5, 0, 4, 4, "11.00");
        var orders = Map.of("order", List.of(finalized, storno));

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, START, END);

        assertEquals(List.of(new GMV.Contribution(storno, -4, new BigDecimal("-44.00"))), breakdown.contributions());
        assertReconciles(orders, START, END, breakdown);
    }

    @Test
    void keepsZeroStornoAdjustmentsWhenTheFinalizedSnapshotIsOutsideThePeriod() {
        var finalized = snapshot("order", 101, 10, "2026-01-14", 4, 4, 4, 0, "10.00");
        var storno = snapshot("order", 102, 10, "2026-02-15", 5, 4, 4, 0, "11.00");
        var orders = Map.of("order", List.of(finalized, storno));

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, START, END);

        assertEquals(List.of(new GMV.Contribution(storno, 0, new BigDecimal("0.00"))), breakdown.contributions());
        assertReconciles(orders, START, END, breakdown);
    }

    @Test
    void keepsSeparateLinesAndOrdersAndSortsByDateOrderAndLine() {
        var laterLine = snapshot("first", 101, 20, "2026-01-15", 4, 2, 2, 0, "5.00");
        var earlierLine = snapshot("first", 101, 5, "2026-01-15", 4, 1, 1, 0, "3.00");
        var secondOrder = snapshot("second", 201, 10, "2026-01-15", 4, 2, 2, 0, "7.00");
        var finalized = snapshot("first", 101, 10, "2026-01-15", 4, 4, 4, 0, "10.00");
        var storno = snapshot("first", 102, 10, "2026-02-15", 5, 3, 4, 1, "11.00");
        var orders = new LinkedHashMap<String, List<POInfo>>();
        orders.put("second", List.of(secondOrder));
        orders.put("first", List.of(laterLine, finalized, earlierLine, storno));

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, START, END);

        assertEquals(List.of(earlierLine, laterLine, secondOrder, storno), breakdown.contributions().stream()
                .map(GMV.Contribution::source).toList());
        assertEquals(new BigDecimal("60.00"), breakdown.total());
        assertReconciles(orders, START, END, breakdown);
    }

    @Test
    void wholeMonthBreakdownsMatchMonthlyTotalsWhileASpanningPeriodUsesOneBucket() {
        var finalized = snapshot("pair", 101, 10, "2026-01-01", 4, 4, 4, 0, "10.00");
        var storno = snapshot("pair", 102, 10, "2026-02-01", 5, 3, 4, 1, "11.00");
        var lone = snapshot("lone", 201, 10, "2026-01-20", 4, 2, 2, 0, "5.00");
        var orders = Map.of("pair", List.of(finalized, storno), "lone", List.of(lone));
        var january = YearMonth.of(2026, 1);
        var february = YearMonth.of(2026, 2);

        assertEquals(Map.of(january, new BigDecimal("50.00"), february, new BigDecimal("-11.00")),
                GMV.computeGMVByMonth(orders));
        for (var month : List.of(january, february)) {
            var breakdown = GMV.computeGMVBreakdownForPeriod(orders, month.atDay(1), month.atEndOfMonth());
            assertEquals(GMV.computeGMVByMonth(orders).get(month), breakdown.total());
            assertReconciles(orders, month.atDay(1), month.atEndOfMonth(), breakdown);
        }
        var spanning = GMV.computeGMVBreakdownForPeriod(orders, january.atDay(1), february.atEndOfMonth());
        assertEquals(new BigDecimal("43.00"), spanning.total());
        assertEquals(List.of(lone, storno), spanning.contributions().stream().map(GMV.Contribution::source).toList());
    }

    @Test
    void supportsSingleDayPeriodsWithoutLosingExactLargeAmounts() {
        var before = snapshot("before", 101, 10, "2026-01-19", 4, 1, 1, 0, "100.00");
        var onDay = snapshot("onDay", 201, 10, "2026-01-20", 4, 2, 2, 0, "9007199254740993.12");
        var after = snapshot("after", 301, 10, "2026-01-21", 4, 1, 1, 0, "100.00");
        var orders = Map.of("before", List.of(before), "onDay", List.of(onDay), "after", List.of(after));
        var day = LocalDate.of(2026, 1, 20);

        var breakdown = GMV.computeGMVBreakdownForPeriod(orders, day, day);

        assertEquals(new BigDecimal("18014398509481986.24"), breakdown.total());
        assertEquals(List.of(new GMV.Contribution(onDay, 2, breakdown.total())), breakdown.contributions());
        assertReconciles(orders, day, day, breakdown);
    }

    @Test
    void returnsAnImmutableEmptyBreakdownWhenNothingContributes() {
        var breakdown = GMV.computeGMVBreakdownForPeriod(Map.of(), START, END);

        assertEquals(new BigDecimal("0.00"), breakdown.total());
        assertTrue(breakdown.contributions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> breakdown.contributions().add(null));
    }

    @Test
    void breakdownCopiesItsContributionList() {
        var source = snapshot("order", 101, 10, "2026-01-20", 4, 1, 1, 0, "10.00");
        var contributions = new ArrayList<>(List.of(new GMV.Contribution(source, 1, new BigDecimal("10.00"))));
        var breakdown = new GMV.Breakdown(new BigDecimal("10.00"), contributions);

        contributions.clear();

        assertEquals(1, breakdown.contributions().size());
        assertThrows(UnsupportedOperationException.class, () -> breakdown.contributions().clear());
    }

    @Test
    void rejectsInvalidDateRanges() {
        assertThrows(NullPointerException.class, () -> GMV.computeGMVBreakdownForPeriod(Map.of(), null, END));
        assertThrows(NullPointerException.class, () -> GMV.computeGMVBreakdownForPeriod(Map.of(), START, null));
        assertThrows(IllegalArgumentException.class, () -> GMV.computeGMVBreakdownForPeriod(Map.of(), END, START));
    }

    @Test
    void preservesPairValidationEvenWhenAllSnapshotsAreOutsideThePeriod() {
        var finalized = snapshot("order", 101, 10, "2026-01-01", 4, 4, 4, 0, "10.00");
        var storno = snapshot("order", 102, 10, "2026-01-02", 5, 3, 4, 1, "11.00");
        var anotherFinalized = snapshot("order", 103, 10, "2026-01-03", 4, 3, 4, 1, "11.00");
        for (var invalid : List.of(List.of(finalized, anotherFinalized), List.of(storno, finalized),
                List.of(finalized, storno, anotherFinalized))) {
            var orders = Map.of("order", invalid);
            assertThrows(RuntimeException.class, () -> GMV.computeGMVBreakdownForPeriod(orders, START, END));
            assertThrows(RuntimeException.class, () -> GMV.computeGMVForPeriod(orders, START, END));
            assertThrows(RuntimeException.class, () -> GMV.computeGMVByMonth(orders));
        }
    }

    private static void assertReconciles(Map<String, List<POInfo>> orders, LocalDate start, LocalDate end,
                                         GMV.Breakdown breakdown) {
        assertEquals(GMV.computeGMVForPeriod(orders, start, end), breakdown.total());
        assertEquals(breakdown.total(), breakdown.contributions().stream().map(GMV.Contribution::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, HALF_EVEN));
        for (var contribution : breakdown.contributions()) {
            assertEquals(contribution.source().price().multiply(BigDecimal.valueOf(contribution.quantity())),
                    contribution.amount());
        }
    }

    private static POInfo snapshot(String orderId, int surrogateId, int productInOrderId, String date, int status,
                                   int quantity, int initialQuantity, int stornoQuantity, String price) {
        return new POInfo(orderId, surrogateId, LocalDate.parse(date), status, productInOrderId,
                "Product", quantity, initialQuantity, stornoQuantity, new BigDecimal(price));
    }
}
