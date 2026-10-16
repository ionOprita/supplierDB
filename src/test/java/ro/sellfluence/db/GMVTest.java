package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GMVTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate END = LocalDate.of(2026, 2, 15);

    @ParameterizedTest
    @CsvSource({
            "2026-01-15, 2026-02-15, 33.00",
            "2026-01-20, 2026-02-10, 33.00",
            "2026-01-15, 2026-02-16, 40.00",
            "2026-01-14, 2026-02-15, -11.00",
            "2026-01-14, 2026-02-16, 0.00",
            "2026-01-13, 2026-01-14, 0.00",
            "2026-02-16, 2026-02-17, 0.00"
    })
    void computesPairedSnapshotsWithinOnePeriod(String finalizedDate, String stornoDate, String expected) {
        var orders = Map.of("order", List.of(
                snapshot("order", 10, finalizedDate, 4, 4, 4, 0, "10.00"),
                snapshot("order", 10, stornoDate, 5, 3, 4, 1, "11.00")
        ));

        assertEquals(new BigDecimal(expected), GMV.computeGMVForPeriod(orders, START, END));
    }

    @ParameterizedTest
    @CsvSource({
            "4, 2026-01-14, 0.00",
            "4, 2026-01-15, 24.70",
            "4, 2026-02-15, 24.70",
            "4, 2026-02-16, 0.00",
            "5, 2026-01-14, 0.00",
            "5, 2026-01-15, 24.70",
            "5, 2026-02-15, 24.70",
            "5, 2026-02-16, 0.00"
    })
    void addsRemainingQuantityForLoneSnapshots(int status, String date, String expected) {
        var orders = Map.of("order", List.of(
                snapshot("order", 10, date, status, 2, 3, 1, "12.35")
        ));

        assertEquals(new BigDecimal(expected), GMV.computeGMVForPeriod(orders, START, END));
    }

    @Test
    void acceptsSingleDayPeriodAndExcludesAdjacentDays() {
        var date = LocalDate.of(2026, 1, 20);
        var orders = Map.of(
                "before", List.of(snapshot("before", 10, "2026-01-19", 4, 1, 1, 0, "100.00")),
                "onDay", List.of(snapshot("onDay", 10, "2026-01-20", 4, 3, 3, 0, "12.35")),
                "after", List.of(snapshot("after", 10, "2026-01-21", 4, 1, 1, 0, "100.00"))
        );

        assertEquals(new BigDecimal("37.05"), GMV.computeGMVForPeriod(orders, date, date));
    }

    @Test
    void returnsZeroForFullyReturnedOrderInPeriod() {
        var orders = Map.of("order", List.of(
                snapshot("order", 10, "2026-01-15", 4, 4, 4, 0, "10.00"),
                snapshot("order", 10, "2026-02-15", 5, 0, 4, 4, "11.00")
        ));

        assertEquals(new BigDecimal("0.00"), GMV.computeGMVForPeriod(orders, START, END));
    }

    @Test
    void subtractsFullStornoWhenFinalizedSnapshotIsOutsidePeriod() {
        var orders = Map.of("order", List.of(
                snapshot("order", 10, "2026-01-14", 4, 4, 4, 0, "10.00"),
                snapshot("order", 10, "2026-02-15", 5, 0, 4, 4, "11.00")
        ));

        assertEquals(new BigDecimal("-44.00"), GMV.computeGMVForPeriod(orders, START, END));
    }

    @Test
    void keepsProductLinesAndOrdersSeparate() {
        var orders = Map.of(
                "first", List.of(
                        snapshot("first", 10, "2026-01-15", 4, 4, 4, 0, "10.00"),
                        snapshot("first", 20, "2026-01-15", 4, 2, 2, 0, "5.00"),
                        snapshot("first", 10, "2026-02-15", 5, 3, 4, 1, "11.00")
                ),
                "second", List.of(snapshot("second", 10, "2026-01-20", 4, 2, 2, 0, "7.00"))
        );

        assertEquals(new BigDecimal("57.00"), GMV.computeGMVForPeriod(orders, START, END));
    }

    @Test
    void returnsMonetaryZeroWhenThereAreNoOrders() {
        assertEquals(new BigDecimal("0.00"), GMV.computeGMVForPeriod(Map.of(), START, END));
    }

    @Test
    void rejectsInvalidDateRanges() {
        assertThrows(NullPointerException.class, () -> GMV.computeGMVForPeriod(Map.of(), null, END));
        assertThrows(NullPointerException.class, () -> GMV.computeGMVForPeriod(Map.of(), START, null));
        assertThrows(IllegalArgumentException.class, () -> GMV.computeGMVForPeriod(Map.of(), END, START));
    }

    @Test
    void rejectsInvalidPairedStatuses() {
        var orders = Map.of("order", List.of(
                snapshot("order", 10, "2026-01-15", 4, 4, 4, 0, "10.00"),
                snapshot("order", 10, "2026-02-15", 4, 3, 4, 1, "11.00")
        ));

        assertThrows(RuntimeException.class, () -> GMV.computeGMVForPeriod(orders, START, END));
        assertThrows(RuntimeException.class, () -> GMV.computeGMVByMonth(orders));
    }

    @Test
    void rejectsMoreThanTwoSnapshotsForOneProductLine() {
        var orders = Map.of("order", List.of(
                snapshot("order", 10, "2026-01-15", 4, 4, 4, 0, "10.00"),
                snapshot("order", 10, "2026-02-14", 5, 3, 4, 1, "11.00"),
                snapshot("order", 10, "2026-02-15", 5, 2, 4, 2, "11.00")
        ));

        assertThrows(RuntimeException.class, () -> GMV.computeGMVForPeriod(orders, START, END));
        assertThrows(RuntimeException.class, () -> GMV.computeGMVByMonth(orders));
    }

    @Test
    void preservesMonthlyCalculationRules() {
        var orders = monthlyOrders();

        assertEquals(Map.of(
                YearMonth.of(2026, 1), new BigDecimal("305.00"),
                YearMonth.of(2026, 2), new BigDecimal("-8.00")
        ), GMV.computeGMVByMonth(orders));
    }

    @ParameterizedTest
    @CsvSource({"2026-01", "2026-02"})
    void wholeMonthPeriodMatchesExistingMonthlyCalculation(String monthText) {
        var month = YearMonth.parse(monthText);
        var orders = monthlyOrders();

        assertEquals(
                GMV.computeGMVByMonth(orders).get(month),
                GMV.computeGMVForPeriod(orders, month.atDay(1), month.atEndOfMonth())
        );
    }

    private static Map<String, List<POInfo>> monthlyOrders() {
        return Map.of(
                "sameMonth", List.of(
                        snapshot("sameMonth", 10, "2026-01-02", 4, 3, 3, 0, "100.00"),
                        snapshot("sameMonth", 10, "2026-01-30", 5, 2, 3, 1, "110.00")
                ),
                "differentMonths", List.of(
                        snapshot("differentMonths", 10, "2026-01-03", 4, 4, 4, 0, "20.00"),
                        snapshot("differentMonths", 10, "2026-02-02", 5, 3, 4, 1, "22.00")
                ),
                "loneFinalized", List.of(snapshot("loneFinalized", 10, "2026-01-20", 4, 1, 1, 0, "5.00")),
                "loneStorno", List.of(snapshot("loneStorno", 10, "2026-02-20", 5, 2, 3, 1, "7.00"))
        );
    }

    private static POInfo snapshot(String orderId, int productInOrderId, String date, int status,
                                   int quantity, int initialQuantity, int stornoQuantity, String price) {
        return new POInfo(orderId, status, LocalDate.parse(date), status, productInOrderId,
                "Product", quantity, initialQuantity, stornoQuantity, new BigDecimal(price));
    }
}
