package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;

import java.util.Comparator;
import java.util.List;

/** The selected category's product columns, with all financial values intentionally absent. */
public final class ProfitCalculatorCategory {
    private ProfitCalculatorCategory() {
    }

    public record Response(String vendorGroup, String category, List<CategoryProduct> products) {
    }

    static Response create(String vendorGroup, String category, List<CategoryProduct> products) {
        var ordered = products.stream()
                .sorted(Comparator.comparing(ProfitCalculatorCategory::sortName,
                                ProductPerformanceOptions::compareProductNames)
                        .thenComparing(CategoryProduct::productCode))
                .toList();
        return new Response(vendorGroup, category, ordered);
    }

    private static String sortName(CategoryProduct product) {
        return product.name() == null || product.name().isBlank() ? product.productCode() : product.name();
    }
}
