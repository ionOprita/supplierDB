package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GMVSellerVoucherTest {
    private static final LocalDate JAN = LocalDate.of(2026, 1, 10);
    private static final LocalDate FEB = LocalDate.of(2026, 2, 10);

    @Test
    void appliesTheVoucherOnceToTheWholeLineAndRetainsExactAmounts() {
        var source = line(4, JAN, 4, 0, "100.00", "-40.00");
        var result = breakdown(List.of(source), JAN, JAN);
        assertEquals(new BigDecimal("360.00"), result.total());
        assertEquals(new BigDecimal("-40.00"), result.contributions().getFirst().sellerVoucherAdjustment());
        var large = line(4, JAN, 4, 0, "9007199254740993.12", "-0.29");
        assertEquals(new BigDecimal("36028797018963972.19"), breakdown(List.of(large), JAN, JAN).total());
    }

    @Test
    void usesTheStornoVoucherAsStoredWithoutProratingItAgain() {
        var finalized = line(4, JAN, 4, 0, "100.00", "-40.00");
        var adjusted = line(5, FEB, 3, 1, "100.00", "-30.00");
        assertEquals(new BigDecimal("270.00"), breakdown(List.of(finalized, adjusted), JAN, FEB).total());
        var unchanged = line(5, FEB, 3, 1, "100.00", "-40.00");
        assertEquals(new BigDecimal("260.00"), breakdown(List.of(finalized, unchanged), JAN, FEB).total());
    }

    @Test
    void returnsOnlyTheRelevantDiscountWithTheNegativeStornoQuantity() {
        var lines = List.of(line(4, JAN, 4, 0, "100.00", "-40.00"),
                line(5, FEB, 3, 1, "100.00", "-30.00"));
        var returned = breakdown(lines, FEB, FEB).contributions().getFirst();
        assertEquals(-1, returned.quantity());
        assertEquals(new BigDecimal("10.00"), returned.sellerVoucherAdjustment());
        assertEquals(new BigDecimal("-90.00"), returned.amount());
        assertEquals(Map.of(YearMonth.from(JAN), new BigDecimal("360.00"),
                YearMonth.from(FEB), new BigDecimal("-90.00")), GMV.computeGMVByMonth(Map.of("order", lines)));
    }

    @Test
    void fullyReturnedLinesRemainZeroEvenWhenTheirVoucherWasNotCleared() {
        var finalized = line(4, JAN, 4, 0, "100.00", "-40.00");
        var storno = line(5, FEB, 0, 4, "100.00", "-40.00");
        var spanning = breakdown(List.of(finalized, storno), JAN, FEB);
        assertEquals(new BigDecimal("0.00"), spanning.total());
        assertEquals(1, spanning.contributions().size());
        assertEquals(new BigDecimal("0.00"), spanning.contributions().getFirst().sellerVoucherAdjustment());
        assertEquals(new BigDecimal("-360.00"), breakdown(List.of(finalized, storno), FEB, FEB).total());
        assertEquals(new BigDecimal("0.00"), breakdown(List.of(storno), FEB, FEB).total());
    }

    @Test
    void preservesExistingBasePriceBehaviorWhenSnapshotPricesDiffer() {
        var lines = List.of(line(4, JAN, 4, 0, "100.00", "-40.00"),
                line(5, FEB, 3, 1, "110.00", "-30.00"));
        assertEquals(new BigDecimal("-100.00"), breakdown(lines, FEB, FEB).total());
        assertEquals(new BigDecimal("300.00"), breakdown(lines, JAN, FEB).total());
    }

    @Test
    void roundsTheLineVoucherOnceUsingHalfEvenAndTreatsMissingVouchersAsZero() {
        assertEquals(new BigDecimal("399.98"), breakdown(List.of(line(4, JAN, 4, 0,
                "100.00", "-0.025")), JAN, JAN).total());
        var noVoucher = new POInfo("order", 4, JAN, 4, 1, "Product", 4, 4, 0,
                new BigDecimal("100.00"), null);
        assertEquals(new BigDecimal("400.00"), breakdown(List.of(noVoucher), JAN, JAN).total());
    }

    private static GMV.Breakdown breakdown(List<POInfo> lines, LocalDate start, LocalDate end) {
        var orders = Map.of("order", lines);
        var result = GMV.computeGMVBreakdownForPeriod(orders, start, end);
        assertEquals(GMV.computeGMVForPeriod(orders, start, end), result.total());
        assertEquals(result.total(), result.contributions().stream().map(GMV.Contribution::amount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add));
        for (var row : result.contributions()) {
            assertEquals(row.amount(), row.source().price().multiply(BigDecimal.valueOf(row.quantity()))
                    .add(row.sellerVoucherAdjustment()));
        }
        return result;
    }

    private static POInfo line(int status, LocalDate date, int quantity, int returned, String price, String voucher) {
        return new POInfo("order", status, date, status, 1, "Product", quantity, 4, returned,
                new BigDecimal(price), new BigDecimal(voucher));
    }
}
