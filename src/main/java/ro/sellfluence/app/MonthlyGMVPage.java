package ro.sellfluence.app;

import ro.sellfluence.db.ProductTable.ProductInfo;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static java.math.RoundingMode.HALF_EVEN;

/** Read-only monthly GMV report, with a shared 24-month window for every product. */
public record MonthlyGMVPage(List<YearMonth> months, List<Row> rows) {
    public MonthlyGMVPage {
        months = List.copyOf(months);
        rows = List.copyOf(rows);
    }

    public static MonthlyGMVPage from(Map<ProductInfo, ? extends Map<YearMonth, BigDecimal>> data) {
        var months = new ArrayList<YearMonth>();
        data.values().stream()
                .flatMap(values -> values.keySet().stream())
                .max(Comparator.naturalOrder())
                .ifPresent(latest -> {
                    for (int offset = 23; offset >= 0; offset--) {
                        months.add(latest.minusMonths(offset));
                    }
                });

        var productOrder = Comparator.comparing(ProductInfo::name,
                        Comparator.nullsLast(MonthlyGMVPage::compareProductNames))
                .thenComparing(ProductInfo::productCode, Comparator.nullsFirst(String::compareTo));
        var rows = data.entrySet().stream()
                .map(entry -> new Row(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Row::product, productOrder))
                .toList();
        return new MonthlyGMVPage(months, rows);
    }

    private static int compareProductNames(String first, String second) {
        boolean firstSupported = supportsProductNameOrder(first);
        boolean secondSupported = supportsProductNameOrder(second);
        if (firstSupported != secondSupported) return firstSupported ? -1 : 1;
        return firstSupported ? ProductInfo.nameComparatorString.compare(first, second) : first.compareTo(second);
    }

    private static boolean supportsProductNameOrder(String name) {
        try {
            ProductInfo.nameComparatorString.compare(name, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public record Row(ProductInfo product, Map<YearMonth, BigDecimal> gmvs) {
        public Row {
            gmvs = Map.copyOf(gmvs);
        }

        public String amount(YearMonth month) {
            var value = gmvs.get(month);
            return value == null ? "—" : value.setScale(2, HALF_EVEN).toPlainString();
        }
    }
}
