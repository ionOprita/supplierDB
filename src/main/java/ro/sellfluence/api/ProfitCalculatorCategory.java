package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;

import java.util.Comparator;
import java.util.List;

/** The selected category's product columns and the implemented database-backed reference values. */
public final class ProfitCalculatorCategory {
    private ProfitCalculatorCategory() {
    }

    public record Response(String vendorGroup, String category, List<CategoryProduct> products) {
    }

    static Response create(String vendorGroup, String category, List<CategoryProduct> products) {
        var ordered = products.stream()
                .map(ProfitCalculatorCategory::withEligibleValues)
                .sorted(Comparator.comparing(ProfitCalculatorCategory::sortName,
                                ProductPerformanceOptions::compareProductNames)
                        .thenComparing(CategoryProduct::productCode))
                .toList();
        return new Response(vendorGroup, category, ordered);
    }

    private static CategoryProduct withEligibleValues(CategoryProduct product) {
        if (product.retracted() || product.pnk() == null || product.pnk().isBlank()) {
            // Keep the product column, matching the source formulas' blank-value guards.
            return new CategoryProduct(product.productCode(), product.name(), product.pnk(), product.retracted());
        }
        return product;
    }

    private static String sortName(CategoryProduct product) {
        return product.name() == null || product.name().isBlank() ? product.productCode() : product.name();
    }
}
