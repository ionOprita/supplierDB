package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.Overrides;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** The selected category's product columns and the implemented database-backed reference values. */
public final class ProfitCalculatorCategory {
    private ProfitCalculatorCategory() {
    }

    public record Calculated(BigDecimal effectiveSalePriceRon, BigDecimal effectiveCommissionRate,
                             BigDecimal commissionCostRon, BigDecimal purchaseCostRon, BigDecimal transportCostRon) {
    }

    public record Product(String productCode, String name, String pnk, boolean retracted,
                          BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer,
                          String companyName, Overrides overrides, long overrideRevision, Calculated calculated) {
    }

    public record Response(String vendorGroup, String category, List<Product> products) {
    }

    static Response create(String vendorGroup, String category, List<CategoryProduct> products) {
        var ordered = products.stream()
                .map(ProfitCalculatorCategory::evaluate)
                .sorted(Comparator.comparing(ProfitCalculatorCategory::sortName,
                                ProductPerformanceOptions::compareProductNames)
                        .thenComparing(Product::productCode))
                .toList();
        return new Response(vendorGroup, category, ordered);
    }

    static Response preview(String vendorGroup, String category, List<CategoryProduct> products,
                            List<OverrideChange> changes) {
        var patches = new HashMap<String, OverrideChange>();
        changes.forEach(change -> patches.put(change.productCode(), change));
        var preview = products.stream().map(product -> {
            var patch = patches.remove(product.productCode());
            if (patch == null) return product;
            return new CategoryProduct(product.productCode(), product.name(), product.pnk(), product.retracted(),
                    product.emagSalePriceRon(), product.incomeProfitTax(), product.vatPayer(), product.companyName(),
                    overrides(product).applyChanges(patch.values()), product.overrideRevision());
        }).toList();
        if (!patches.isEmpty()) {
            throw new IllegalArgumentException("A preview product does not belong to the selected category.");
        }
        return create(vendorGroup, category, preview);
    }

    private static Product evaluate(CategoryProduct product) {
        var overrides = overrides(product);
        boolean eligible = !product.retracted() && product.pnk() != null && !product.pnk().isBlank();
        var price = eligible ? firstKnown(overrides.salePriceRon(), product.emagSalePriceRon()) : null;
        // The category-wide commission default remains unresolved; no rate is invented here.
        var commission = eligible ? overrides.commissionRate() : null;
        var purchase = eligible ? multiply(overrides.purchasePriceUsd(), overrides.usdRonRate()) : null;
        var transport = eligible ? multiply(overrides.transportPriceUsd(), overrides.usdRonRate()) : null;
        return new Product(product.productCode(), product.name(), product.pnk(), product.retracted(),
                eligible ? product.emagSalePriceRon() : null, eligible ? product.incomeProfitTax() : null,
                eligible ? product.vatPayer() : null, eligible ? product.companyName() : null,
                overrides, product.overrideRevision(),
                new Calculated(price, commission, multiply(price, commission), purchase, transport));
    }

    private static Overrides overrides(CategoryProduct product) {
        return product.overrides() == null ? Overrides.empty() : product.overrides();
    }

    private static BigDecimal firstKnown(BigDecimal override, BigDecimal reference) {
        return override != null ? override : reference;
    }

    private static BigDecimal multiply(BigDecimal first, BigDecimal second) {
        return first == null || second == null ? null : first.multiply(second);
    }

    private static String sortName(Product product) {
        return product.name() == null || product.name().isBlank() ? product.productCode() : product.name();
    }
}
