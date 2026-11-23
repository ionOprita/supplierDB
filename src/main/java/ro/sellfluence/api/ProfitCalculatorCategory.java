package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.CategorySettings;
import ro.sellfluence.db.ProfitCalculatorTable.CategoryData;
import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.Overrides;
import ro.sellfluence.db.ProfitCalculatorTable.ReturnRates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The selected category's product columns and the implemented database-backed reference values. */
public final class ProfitCalculatorCategory {
    private static final ReturnRates UNKNOWN_RATES = new ReturnRates(null, null);

    private ProfitCalculatorCategory() {
    }

    public record Calculated(BigDecimal effectiveSalePriceRon, BigDecimal effectiveCommissionRate,
                             BigDecimal commissionCostRon, BigDecimal purchaseCostRon, BigDecimal transportCostRon,
                             BigDecimal googleAdsCostRon, BigDecimal socialMediaAdsCostRon,
                             BigDecimal storageCostRon, BigDecimal turnoverTaxCostRon, BigDecimal vatCostRon,
                             BigDecimal profitTaxCostRon, BigDecimal dividendTaxCostRon,
                             Long previousMonthGrossUnits, BigDecimal fbeProcessingCostRon,
                             BigDecimal fbeReturnCostRon, BigDecimal fbeWithdrawalCostRon,
                             BigDecimal fbeDisposalCostRon, Double returnRate, Double refusalRate,
                             BigDecimal warehousePreparationCostRon, BigDecimal refusalCostRon,
                             BigDecimal defectiveProductCostRon, BigDecimal parcelPreparationCostRon,
                             BigDecimal geniusCostRon, BigDecimal accountingCostPerUnitRon,
                             BigDecimal employeeCostPerUnitRon, BigDecimal miscellaneousCostPerUnitRon,
                             BigDecimal transitFinancingCostRon, BigDecimal postDeliveryFinancingCostRon) {
    }

    public record Product(String productCode, String name, String pnk, boolean retracted,
                          BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer,
                          String companyName, Overrides overrides, long overrideRevision, Calculated calculated) {
    }

    public record Response(String vendorGroup, String category, List<Product> products) {
    }

    static Response create(String vendorGroup, String category, List<CategoryProduct> products) {
        return create(vendorGroup, category, products, List.of());
    }

    static Response create(String vendorGroup, String category, List<CategoryProduct> products, List<Tariff> tariffs) {
        return create(vendorGroup, category, new CategoryData(products, tariffs));
    }

    static Response create(String vendorGroup, String category, CategoryData data) {
        var ordered = data.products().stream()
                .map(product -> evaluate(product, data))
                .sorted(Comparator.comparing(Product::retracted)
                        .thenComparing(ProfitCalculatorCategory::sortName,
                                ProductPerformanceOptions::compareProductNames)
                        .thenComparing(Product::productCode))
                .toList();
        return new Response(vendorGroup, category, ordered);
    }

    static Response preview(String vendorGroup, String category, List<CategoryProduct> products,
                            List<OverrideChange> changes) {
        return preview(vendorGroup, category, products, changes, List.of());
    }

    static Response preview(String vendorGroup, String category, List<CategoryProduct> products,
                            List<OverrideChange> changes, List<Tariff> tariffs) {
        return preview(vendorGroup, category, new CategoryData(products, tariffs), changes);
    }

    static Response preview(String vendorGroup, String category, CategoryData data, List<OverrideChange> changes) {
        var patches = new HashMap<String, OverrideChange>();
        changes.forEach(change -> patches.put(change.productCode(), change));
        var preview = data.products().stream().map(product -> {
            var patch = patches.remove(product.productCode());
            if (patch == null) return product;
            return new CategoryProduct(product.productCode(), product.name(), product.pnk(), product.retracted(),
                    product.emagSalePriceRon(), product.incomeProfitTax(), product.vatPayer(), product.companyName(),
                    overrides(product).applyChanges(patch.values()), product.overrideRevision(),
                    product.emagCommission(), product.previousMonthGrossUnits(),
                    product.productLengthMm(), product.productWidthMm(), product.productHeightMm(), product.productWeightG());
        }).toList();
        if (!patches.isEmpty()) {
            throw new IllegalArgumentException("A preview product does not belong to the selected category.");
        }
        return create(vendorGroup, category,
                new CategoryData(preview, data.tariffs(), data.returnRatesByPnk(), data.settings()));
    }

    private static Product evaluate(CategoryProduct product, CategoryData data) {
        var overrides = overrides(product);
        boolean eligible = !product.retracted() && product.pnk() != null && !product.pnk().isBlank();
        var price = eligible ? firstKnown(overrides.salePriceRon(), product.emagSalePriceRon()) : null;
        var commission = eligible ? firstKnown(overrides.commissionRate(), product.emagCommission()) : null;
        var purchase = eligible ? multiply(overrides.purchasePriceUsd(), overrides.usdRonRate()) : null;
        var transport = eligible ? multiply(overrides.transportPriceUsd(), overrides.usdRonRate()) : null;
        var regime = product.incomeProfitTax() == null ? null : product.incomeProfitTax().strip();
        // Emit independently known zero costs even if unrelated inputs are missing.
        var advertising = eligible ? BigDecimal.ZERO : null;
        var storage = eligible && "Minulescu".equals(overrides.fulfillmentSource()) ? BigDecimal.ZERO : null;
        var vat = eligible && Boolean.FALSE.equals(product.vatPayer()) ? BigDecimal.ZERO : null;
        var profitTax = eligible && "Venit".equalsIgnoreCase(regime) ? BigDecimal.ZERO : null;
        var fbe = eligible ? ProfitCalculatorFbeCosts.calculate(product.productLengthMm(), product.productWidthMm(),
                product.productHeightMm(), product.productWeightG(), data.tariffs()) : ProfitCalculatorFbeCosts.Costs.unknown();
        var rates = eligible ? data.returnRatesByPnk().getOrDefault(product.pnk(), UNKNOWN_RATES) : UNKNOWN_RATES;
        var settings = data.settings();
        var turnoverTax = eligible ? turnoverTax(regime, price, settings) : null;
        var capitalRate = settings.monthlyCapitalRate();
        var financingCost = eligible && capitalRate != null && capitalRate.signum() == 0 ? BigDecimal.ZERO : null;
        var vatRate = settings.vatRate();
        var vatMultiplier = vatRate == null ? null : BigDecimal.ONE.add(vatRate);
        var warehousePreparation = eligible ? settings.warehousePreparationCostRon() : null;
        var fbePreparation = multiply(fbe.processingCostRon(), vatMultiplier);
        var refusalRate = rates.refusalRate();
        var refusalCost = refusalRate == null ? null
                : refusalRate == 0 ? BigDecimal.ZERO : multiply(fbePreparation, BigDecimal.valueOf(refusalRate));
        var defectiveProductCost = rates.returnRate() != null && rates.returnRate() == 0 ? BigDecimal.ZERO : null;
        var parcelPreparation = switch (overrides.fulfillmentSource()) {
            case "eMAG" -> fbePreparation;
            case "Minulescu" -> warehousePreparation;
            case null, default -> null;
        };
        var geniusCost = eligible ? multiply(multiply(settings.geniusEasyboxRate(), settings.geniusCostRon()), vatMultiplier) : null;
        var miscellaneousCost = eligible ? multiply(settings.miscellaneousCostPerUnitRon(), vatMultiplier) : null;
        return new Product(product.productCode(), product.name(), product.pnk(), product.retracted(),
                eligible ? product.emagSalePriceRon() : null, eligible ? product.incomeProfitTax() : null,
                eligible ? product.vatPayer() : null, eligible ? product.companyName() : null,
                overrides, product.overrideRevision(),
                new Calculated(price, commission, multiply(price, commission), purchase, transport,
                        advertising, advertising, storage, turnoverTax, vat, profitTax, profitTax,
                        eligible ? product.previousMonthGrossUnits() : null, fbe.processingCostRon(),
                        fbe.returnCostRon(), fbe.withdrawalCostRon(), fbe.disposalCostRon(),
                        rates.returnRate(), refusalRate, warehousePreparation, refusalCost,
                        defectiveProductCost, parcelPreparation, geniusCost,
                        eligible ? settings.accountingCostPerUnitRon() : null,
                        eligible ? settings.employeeCostPerUnitRon() : null, miscellaneousCost,
                        financingCost, financingCost));
    }

    private static BigDecimal turnoverTax(String regime, BigDecimal price, CategorySettings settings) {
        if ("Profit".equalsIgnoreCase(regime)) return BigDecimal.ZERO;
        if (!"Venit".equalsIgnoreCase(regime) || price == null || settings.turnoverTaxVatRate() == null
                || settings.courierCostRon() == null || settings.incomeTaxRate() == null) return null;
        var netPrice = price.divide(BigDecimal.ONE.add(settings.turnoverTaxVatRate()), MathContext.DECIMAL128);
        return netPrice.add(settings.courierCostRon()).multiply(settings.incomeTaxRate());
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
