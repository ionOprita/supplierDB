package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductTable.ProductInfo;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthlyGMVPageTest {
    @Test
    void showsTwentyFourConsecutiveMonthsEndingAtLatestStoredMonthAcrossProducts() {
        var earlier = product("earlier", "S. 1 - Earlier");
        var latest = product("latest", "S. 2 - Latest");

        var report = MonthlyGMVPage.from(Map.of(
                earlier, Map.of(
                        YearMonth.of(2020, 1), BigDecimal.ONE,
                        YearMonth.of(2025, 12), BigDecimal.TEN
                ),
                latest, Map.of(YearMonth.of(2026, 2), new BigDecimal("20.00"))
        ));

        assertEquals(24, report.months().size());
        assertEquals(YearMonth.of(2024, 3), report.months().getFirst());
        assertEquals(YearMonth.of(2026, 2), report.months().getLast());
        assertTrue(report.months().contains(YearMonth.of(2025, 1)));
        assertTrue(report.months().contains(YearMonth.of(2026, 1)));
        assertFalse(report.months().contains(YearMonth.of(2024, 2)));
        for (int i = 1; i < report.months().size(); i++) {
            assertEquals(report.months().get(i - 1).plusMonths(1), report.months().get(i));
        }
    }

    @Test
    void fillsTheCalendarWindowEvenWhenOnlyOneMonthIsStored() {
        var product = product("only", "S. 1 - Only");

        var report = MonthlyGMVPage.from(Map.of(
                product, Map.of(YearMonth.of(2026, 1), BigDecimal.TEN)
        ));

        assertEquals(24, report.months().size());
        assertEquals(YearMonth.of(2024, 2), report.months().getFirst());
        assertEquals(YearMonth.of(2026, 1), report.months().getLast());
        assertEquals("—", report.rows().getFirst().amount(YearMonth.of(2025, 12)));
    }

    @Test
    void distinguishesMissingValuesFromZeroAndFormatsExactDecimalsWithHalfEvenRounding() {
        var january = YearMonth.of(2026, 1);
        var row = new MonthlyGMVPage.Row(product("amounts", "S. 1 - Amounts"), Map.of(
                january, new BigDecimal("9007199254740993.125"),
                january.plusMonths(1), new BigDecimal("-1.235"),
                january.plusMonths(2), BigDecimal.ZERO,
                january.plusMonths(3), new BigDecimal("1.245"),
                january.plusMonths(4), new BigDecimal("1E+3")
        ));

        assertEquals("9007199254740993.12", row.amount(january));
        assertEquals("-1.24", row.amount(january.plusMonths(1)));
        assertEquals("0.00", row.amount(january.plusMonths(2)));
        assertEquals("1.24", row.amount(january.plusMonths(3)));
        assertEquals("1000.00", row.amount(january.plusMonths(4)));
        assertEquals("—", row.amount(january.plusMonths(5)));
    }

    @Test
    void sortsProductsNaturallyAndKeepsDuplicateNamesAndProductsWithoutGmv() {
        var unnamed = product("unnamed", null);
        var ten = product("ten", "S. 10 - Product");
        var second = product("two-b", "S. 2 - Product");
        var first = product("two-a", "S. 2 - Product");
        var data = new LinkedHashMap<ProductInfo, Map<YearMonth, BigDecimal>>();
        data.put(unnamed, Map.of());
        data.put(ten, Map.of(YearMonth.of(2026, 1), BigDecimal.TEN));
        data.put(second, Map.of());
        data.put(first, Map.of(YearMonth.of(2026, 1), BigDecimal.ONE));

        var report = MonthlyGMVPage.from(data);

        assertEquals(List.of("two-a", "two-b", "ten", "unnamed"), report.rows().stream()
                .map(row -> row.product().productCode()).toList());
        assertEquals("1.00", report.rows().getFirst().amount(YearMonth.of(2026, 1)));
        assertEquals("—", report.rows().get(1).amount(YearMonth.of(2026, 1)));
        assertEquals("—", report.rows().getLast().amount(YearMonth.of(2026, 1)));
    }

    @Test
    void retainsProductRowsWhenNoStoredGmvExists() {
        var first = product("first", "S. 1 - First");
        var second = product("second", "S. 2 - Second");

        var report = MonthlyGMVPage.from(Map.of(second, Map.of(), first, Map.of()));

        assertTrue(report.months().isEmpty());
        assertEquals(List.of(first, second), report.rows().stream().map(MonthlyGMVPage.Row::product).toList());
    }

    @Test
    void keepsLegacyAndOtherVendorNamesVisibleWithLexicalFallback() {
        var report = MonthlyGMVPage.from(Map.of(
                product("plain", "Legacy product"), Map.of(),
                product("other-vendor", "A. 1 - Product"), Map.of(),
                product("ten", "S. 10 - Product"), Map.of(),
                product("two", "S. 2 - Product"), Map.of(),
                product("unnamed", null), Map.of()
        ));

        assertEquals(List.of("two", "ten", "other-vendor", "plain", "unnamed"),
                report.rows().stream().map(row -> row.product().productCode()).toList());
    }

    @Test
    void handlesAnEmptyProductCatalog() {
        var report = MonthlyGMVPage.from(Map.of());

        assertTrue(report.months().isEmpty());
        assertTrue(report.rows().isEmpty());
    }

    private static ProductInfo product(String code, String name) {
        return new ProductInfo("PNK-" + code, code, name, null, false, false,
                null, null, null, null);
    }
}
