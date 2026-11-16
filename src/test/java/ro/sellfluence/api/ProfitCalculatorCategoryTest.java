package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.Overrides;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfitCalculatorCategoryTest {
    @Test
    void ordersNumberedProductsNaturallyAndKeepsUnexpectedNamesSelectable() {
        var products = List.of(
                new CategoryProduct("ten", "S. 10 - Trimmer", "TEN", false),
                new CategoryProduct("two-b", "S. 2 - Trimmer", "TWO-B", true),
                new CategoryProduct("two-a", "S. 2 - Trimmer", "TWO-A", false),
                new CategoryProduct("custom", "Custom product", "CUSTOM", false),
                new CategoryProduct("unknown-prefix", "A. 1 - Other product", null, false),
                new CategoryProduct("overflow", "S. 9999999999999999 - Unusual product", null, false)
        );

        var response = ProfitCalculatorCategory.create("S", "Trimmers", products);

        assertEquals(List.of("two-a", "two-b", "ten", "unknown-prefix", "custom", "overflow"),
                response.products().stream().map(ProfitCalculatorCategory.Product::productCode).toList());
        assertTrue(response.products().get(1).retracted());
        assertFalse(response.products().getFirst().retracted());
    }

    @Test
    void preservesMissingMetadataAndReturnsOnlyProductFields() {
        var response = ProfitCalculatorCategory.create("S", "Trimmers", List.of(
                new CategoryProduct("missing-name", null, null, true),
                new CategoryProduct("blank-name", "", "", false)
        ));

        assertEquals("S", response.vendorGroup());
        assertEquals("Trimmers", response.category());
        assertEquals("", response.products().getFirst().name());
        assertNull(response.products().getLast().name());
        assertNull(response.products().getLast().pnk());
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(3, json.size());
        assertEquals(11, json.get("products").get(0).size());
        assertTrue(json.get("products").get(0).get("emagSalePriceRon").isNull());
        assertTrue(json.get("products").get(0).get("incomeProfitTax").isNull());
        assertTrue(json.get("products").get(0).get("vatPayer").isNull());
    }

    @Test
    void returnsDatabaseReferenceValuesWithoutLosingZeroFalseOrMissingValues() {
        var zero = new CategoryProduct("zero", "S. 1 - Zero", "ZERO", false,
                BigDecimal.ZERO, "Venit", false);
        var reference = new CategoryProduct("reference", "S. 2 - Reference", "REFERENCE", false,
                new BigDecimal("169.99"), "Profit", true);
        var missing = new CategoryProduct("missing", "S. 3 - Missing", "MISSING", false);

        var response = ProfitCalculatorCategory.create("S", "Trimmers", List.of(missing, reference, zero));

        assertEquals(List.of("zero", "reference", "missing"), response.products().stream()
                .map(ProfitCalculatorCategory.Product::productCode).toList());
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(0, json.get("products").get(0).get("emagSalePriceRon").asInt());
        assertEquals("Venit", json.get("products").get(0).get("incomeProfitTax").asString());
        assertFalse(json.get("products").get(0).get("vatPayer").asBoolean());
        assertTrue(json.get("products").get(1).get("vatPayer").asBoolean());
        assertTrue(json.get("products").get(2).get("vatPayer").isNull());
    }

    @Test
    void blanksAllReferenceValuesForRetractedProductsAndMissingOrBlankPnks() {
        var inputs = List.of(
                new CategoryProduct("retracted", "S. 1 - Retracted", "PNK1", true,
                        new BigDecimal("169.99"), "Profit", true),
                new CategoryProduct("null-pnk", "S. 2 - Null PNK", null, false,
                        BigDecimal.ZERO, "Venit", false),
                new CategoryProduct("empty-pnk", "S. 3 - Empty PNK", "", false,
                        new BigDecimal("179.99"), "Profit", true),
                new CategoryProduct("blank-pnk", "S. 4 - Blank PNK", " \t\u2003", false,
                        new BigDecimal("189.99"), "Profit", true)
        );

        var response = ProfitCalculatorCategory.create("S", "Trimmers", inputs);

        assertEquals(inputs.size(), response.products().size());
        for (var index = 0; index < inputs.size(); index++) {
            var original = inputs.get(index);
            var product = response.products().get(index);
            assertEquals(original.productCode(), product.productCode());
            assertEquals(original.name(), product.name());
            assertEquals(original.pnk(), product.pnk());
            assertEquals(original.retracted(), product.retracted());
            assertNull(product.emagSalePriceRon());
            assertNull(product.incomeProfitTax());
            assertNull(product.vatPayer());
        }
    }

    @Test
    void returnsAnEmptyCategoryWithoutInventingProducts() {
        var response = ProfitCalculatorCategory.create("J", "Empty category", List.of());
        assertEquals("J", response.vendorGroup());
        assertEquals("Empty category", response.category());
        assertTrue(response.products().isEmpty());
    }

    @Test
    void evaluatesPriceCommissionAndUsdConversionsWithoutIntermediateRounding() {
        var overrides = new Overrides(new BigDecimal("123.456"), new BigDecimal("0.14"),
                new BigDecimal("2.555"), new BigDecimal("0.125"), new BigDecimal("4.56789"), "eMAG");
        var source = new CategoryProduct("code", "S. 1 - Product", "PNK", false,
                new BigDecimal("169.99"), "Profit", false, "Selling company", overrides, 4);

        var product = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source)).products().getFirst();

        assertEquals("Selling company", product.companyName());
        assertEquals(overrides, product.overrides());
        assertEquals(4, product.overrideRevision());
        assertEquals(new BigDecimal("169.99"), product.emagSalePriceRon(), "Reference price is unchanged");
        assertEquals(new BigDecimal("123.456"), product.calculated().effectiveSalePriceRon());
        assertEquals(new BigDecimal("0.14"), product.calculated().effectiveCommissionRate());
        assertEquals(new BigDecimal("17.28384"), product.calculated().commissionCostRon());
        assertEquals(new BigDecimal("11.67095895"), product.calculated().purchaseCostRon());
        assertEquals(new BigDecimal("0.57098625"), product.calculated().transportCostRon());
    }

    @Test
    void leavesUnknownCostsBlankAndPreservesZeroOverrides() {
        var source = new CategoryProduct("code", "S. 1 - Product", "PNK", false,
                new BigDecimal("169.99"), "Profit", true, "Company",
                new Overrides(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2"), null, null), 1);
        var product = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source)).products().getFirst();
        assertEquals(BigDecimal.ZERO, product.calculated().effectiveSalePriceRon());
        assertEquals(BigDecimal.ZERO, product.calculated().effectiveCommissionRate());
        assertEquals(BigDecimal.ZERO, product.calculated().commissionCostRon());
        assertNull(product.calculated().purchaseCostRon(), "A zero USD amount still requires a known exchange rate");
        assertNull(product.calculated().transportCostRon());

        var fallback = ProfitCalculatorCategory.create("S", "Trimmers", List.of(new CategoryProduct(
                "fallback", "S. 2 - Product", "PNK", false, new BigDecimal("199.99"), "Profit", true)))
                .products().getFirst();
        assertEquals(new BigDecimal("199.99"), fallback.calculated().effectiveSalePriceRon());
        assertNull(fallback.calculated().effectiveCommissionRate());
        assertNull(fallback.calculated().commissionCostRon());
    }

    @Test
    void usesEachProductsStoredCommissionFractionWithoutIntermediateRounding() {
        var first = new CategoryProduct("first", "S. 1 - Product", "PNK1", false,
                new BigDecimal("169.99"), "Profit", true, "Company", Overrides.empty(), 0,
                new BigDecimal("0.14"), 12L);
        var second = new CategoryProduct("second", "S. 2 - Product", "PNK2", false,
                new BigDecimal("123.456"), "Profit", true, "Company", Overrides.empty(), 0,
                new BigDecimal("0.1425"), 4L);
        var products = ProfitCalculatorCategory.create("S", "Trimmers", List.of(second, first)).products();

        assertEquals(new BigDecimal("0.14"), products.getFirst().calculated().effectiveCommissionRate());
        assertEquals(new BigDecimal("23.7986"), products.getFirst().calculated().commissionCostRon());
        assertEquals(12L, products.getFirst().calculated().previousMonthGrossUnits());
        assertEquals(new BigDecimal("0.1425"), products.getLast().calculated().effectiveCommissionRate());
        assertEquals(new BigDecimal("17.5924800"), products.getLast().calculated().commissionCostRon());
        assertEquals(4L, products.getLast().calculated().previousMonthGrossUnits());
    }

    @Test
    void distinguishesZeroCommissionAndGrossUnitsFromMissingValues() {
        var zero = new CategoryProduct("zero", "S. 1 - Product", "PNK1", false,
                new BigDecimal("169.99"), "Profit", true, "Company", Overrides.empty(), 0,
                BigDecimal.ZERO, 0L);
        var missing = new CategoryProduct("missing", "S. 2 - Product", "PNK2", false,
                new BigDecimal("169.99"), "Profit", true, "Company", Overrides.empty(), 0,
                null, null);
        var noPrice = new CategoryProduct("no-price", "S. 3 - Product", "PNK3", false,
                null, "Profit", true, "Company", Overrides.empty(), 0, new BigDecimal("0.18"), 7L);
        var response = ProfitCalculatorCategory.create("S", "Trimmers", List.of(zero, missing, noPrice));

        assertEquals(BigDecimal.ZERO, response.products().getFirst().calculated().effectiveCommissionRate());
        assertEquals(new BigDecimal("0.00"), response.products().getFirst().calculated().commissionCostRon());
        assertNull(response.products().get(1).calculated().effectiveCommissionRate());
        assertNull(response.products().get(1).calculated().commissionCostRon());
        assertEquals(new BigDecimal("0.18"), response.products().getLast().calculated().effectiveCommissionRate());
        assertNull(response.products().getLast().calculated().commissionCostRon());

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response)).get("products");
        assertTrue(json.get(0).get("calculated").get("previousMonthGrossUnits").isIntegralNumber());
        assertEquals(0L, json.get(0).get("calculated").get("previousMonthGrossUnits").asLong());
        assertTrue(json.get(1).get("calculated").get("previousMonthGrossUnits").isNull());
        assertEquals(7L, json.get(2).get("calculated").get("previousMonthGrossUnits").asLong());
    }

    @Test
    void clearingCommissionOverrideRestoresProductDefaultAndPreservesGrossUnitsInPreviews() {
        var saved = new Overrides(null, BigDecimal.ZERO, null, null, null, null);
        var source = new CategoryProduct("code", "Product", "PNK", false,
                new BigDecimal("169.99"), "Profit", true, "Company", saved, 2,
                new BigDecimal("0.18"), 42L);
        var original = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source)).products().getFirst();
        assertEquals(BigDecimal.ZERO, original.calculated().effectiveCommissionRate(), "Zero is a real override");
        assertEquals(new BigDecimal("0.00"), original.calculated().commissionCostRon());

        var clear = new LinkedHashMap<String, String>();
        clear.put("commissionRate", null);
        var fallback = ProfitCalculatorCategory.preview("S", "Trimmers", List.of(source),
                List.of(new OverrideChange("code", 2, clear))).products().getFirst();
        assertEquals(new BigDecimal("0.18"), fallback.calculated().effectiveCommissionRate());
        assertEquals(new BigDecimal("30.5982"), fallback.calculated().commissionCostRon());
        assertEquals(42L, fallback.calculated().previousMonthGrossUnits());

        clear.put("salePriceRon", "200");
        var changedPrice = ProfitCalculatorCategory.preview("S", "Trimmers", List.of(source),
                List.of(new OverrideChange("code", 2, clear))).products().getFirst();
        assertEquals(new BigDecimal("36.00"), changedPrice.calculated().commissionCostRon());
        assertEquals(42L, changedPrice.calculated().previousMonthGrossUnits());
        assertEquals(saved, source.overrides(), "Previews leave saved overrides untouched");
        assertEquals(2, changedPrice.overrideRevision());
    }

    @Test
    void evaluatesIndependentlyKnownZerosWithoutPriceOrOtherCostInputs() {
        var overrides = new Overrides(null, null, null, null, null, "Minulescu");
        var originalRegime = " \tprOFit\u2003";
        var source = new CategoryProduct("code", "Product", "PNK", false,
                null, originalRegime, false, null, overrides, 3);
        var product = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source)).products().getFirst();
        var calculated = product.calculated();

        assertEquals(BigDecimal.ZERO, calculated.googleAdsCostRon());
        assertEquals(BigDecimal.ZERO, calculated.socialMediaAdsCostRon());
        assertEquals(BigDecimal.ZERO, calculated.storageCostRon());
        assertEquals(BigDecimal.ZERO, calculated.turnoverTaxCostRon());
        assertEquals(BigDecimal.ZERO, calculated.vatCostRon());
        assertNull(calculated.profitTaxCostRon(), "Profit tax still needs its unimplemented profit inputs");
        assertNull(calculated.dividendTaxCostRon());
        assertNull(calculated.effectiveSalePriceRon());
        assertNull(calculated.purchaseCostRon());
        assertNull(calculated.transportCostRon());
        assertEquals(originalRegime, product.incomeProfitTax(), "Normalization only applies to the calculation");
    }

    @Test
    void evaluatesVenitTaxExemptionsWithoutInventingTheOtherTaxBranches() {
        var source = new CategoryProduct("code", "Product", "PNK", false,
                null, "\u2003vENIt\t", true, null,
                new Overrides(null, null, null, null, null, "eMAG"), 0);
        var calculated = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source))
                .products().getFirst().calculated();

        assertEquals(BigDecimal.ZERO, calculated.googleAdsCostRon());
        assertEquals(BigDecimal.ZERO, calculated.socialMediaAdsCostRon());
        assertEquals(BigDecimal.ZERO, calculated.profitTaxCostRon());
        assertEquals(BigDecimal.ZERO, calculated.dividendTaxCostRon());
        assertNull(calculated.turnoverTaxCostRon());
        assertNull(calculated.vatCostRon());
        assertNull(calculated.storageCostRon(), "The eMAG storage tariff is not available");
    }

    @Test
    void keepsMissingAndUnrecognizedPredicatesUnknown() {
        for (var source : List.of(
                new CategoryProduct("null", "Product", "PNK", false),
                new CategoryProduct("blank", "Product", "PNK", false, null, " \t\u2003", null),
                new CategoryProduct("other", "Product", "PNK", false, null, "Other", null),
                new CategoryProduct("literal", "Product", "PNK", false, null, "<b>Profit</b>", null))) {
            var calculated = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source))
                    .products().getFirst().calculated();
            assertEquals(BigDecimal.ZERO, calculated.googleAdsCostRon());
            assertEquals(BigDecimal.ZERO, calculated.socialMediaAdsCostRon());
            assertNull(calculated.storageCostRon(), "A missing fulfillment selection is not Minulescu");
            assertNull(calculated.turnoverTaxCostRon());
            assertNull(calculated.vatCostRon(), "Unknown VAT status must not be treated as false");
            assertNull(calculated.profitTaxCostRon());
            assertNull(calculated.dividendTaxCostRon());
        }
    }

    @Test
    void serializesKnownZeroCostsAsNumbersAndUnknownCostsAsNull() {
        var source = new CategoryProduct("code", "Product", "PNK", false,
                null, "Profit", false, null,
                new Overrides(null, null, null, null, null, "Minulescu"), 1);
        var response = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source));
        var mapper = new ObjectMapper();
        var calculated = mapper.readTree(mapper.writeValueAsString(response))
                .get("products").get(0).get("calculated");

        for (var field : List.of("googleAdsCostRon", "socialMediaAdsCostRon", "storageCostRon",
                "turnoverTaxCostRon", "vatCostRon")) {
            assertTrue(calculated.get(field).isNumber(), field);
            assertEquals(0, calculated.get(field).asInt(), field);
        }
        assertTrue(calculated.get("profitTaxCostRon").isNull());
        assertTrue(calculated.get("dividendTaxCostRon").isNull());
    }

    @Test
    void previewsFulfillmentChangesAndClearingWithoutChangingSavedStorage() {
        var saved = new Overrides(null, null, null, null, null, "Minulescu");
        var source = new CategoryProduct("code", "Product", "PNK", false,
                null, "Profit", false, null, saved, 2);
        var changes = new LinkedHashMap<String, String>();
        changes.put("fulfillmentSource", "eMAG");
        var emag = ProfitCalculatorCategory.preview("S", "Trimmers", List.of(source),
                List.of(new OverrideChange("code", 2, changes))).products().getFirst();
        assertNull(emag.calculated().storageCostRon());
        assertEquals("eMAG", emag.overrides().fulfillmentSource());

        changes.put("fulfillmentSource", null);
        var cleared = ProfitCalculatorCategory.preview("S", "Trimmers", List.of(source),
                List.of(new OverrideChange("code", 2, changes))).products().getFirst();
        assertNull(cleared.calculated().storageCostRon());
        assertNull(cleared.overrides().fulfillmentSource());
        assertEquals(saved, source.overrides());
        assertEquals(BigDecimal.ZERO, ProfitCalculatorCategory.create("S", "Trimmers", List.of(source))
                .products().getFirst().calculated().storageCostRon());
        assertEquals(2, cleared.overrideRevision());

        var withoutSource = new CategoryProduct("code", "Product", "PNK", false);
        var minulescu = ProfitCalculatorCategory.preview("S", "Trimmers", List.of(withoutSource),
                List.of(new OverrideChange("code", 0, Map.of("fulfillmentSource", "Minulescu"))))
                .products().getFirst();
        assertEquals(BigDecimal.ZERO, minulescu.calculated().storageCostRon());
        assertNull(withoutSource.overrides().fulfillmentSource());
    }

    @Test
    void previewsOnlyCategoryProductsAndClearingOverridesRestoresTheReferencePrice() {
        var saved = new Overrides(new BigDecimal("149"), new BigDecimal("0.14"),
                new BigDecimal("5"), null, new BigDecimal("4"), "Minulescu");
        var source = new CategoryProduct("code", "S. 1 - Product", "PNK", false,
                new BigDecimal("169.99"), "Profit", true, "Company", saved, 2);
        var clear = new LinkedHashMap<String, String>();
        clear.put("salePriceRon", null);
        clear.put("commissionRate", null);

        var preview = ProfitCalculatorCategory.preview("S", "Trimmers", List.of(source),
                List.of(new OverrideChange("code", 2, clear))).products().getFirst();

        assertEquals(new BigDecimal("169.99"), preview.calculated().effectiveSalePriceRon());
        assertNull(preview.calculated().effectiveCommissionRate());
        assertNull(preview.calculated().commissionCostRon());
        assertEquals(new BigDecimal("20"), preview.calculated().purchaseCostRon());
        assertEquals(saved, source.overrides(), "Preview does not mutate saved values");
        assertEquals(2, preview.overrideRevision());
        assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorCategory.preview("S", "Trimmers",
                List.of(source), List.of(new OverrideChange("foreign", 0, Map.of("salePriceRon", "1")))));
    }

    @Test
    void retainsEditableOverridesButSuppressesOutputsForIneligibleProducts() {
        var saved = new Overrides(new BigDecimal("149"), new BigDecimal("0.14"),
                new BigDecimal("5"), new BigDecimal("1"), new BigDecimal("4"), "Minulescu");
        for (var input : List.of(
                new CategoryProduct("retracted", "Name", "PNK", true,
                        new BigDecimal("169.99"), "Profit", false, "Company", saved, 7,
                        new BigDecimal("0.18"), 42L),
                new CategoryProduct("retracted-venit", "Name", "PNK", true,
                        new BigDecimal("169.99"), "Venit", false, "Company", saved, 7,
                        new BigDecimal("0.18"), 42L),
                new CategoryProduct("missing", "Name", " \t", false,
                        new BigDecimal("169.99"), "Profit", false, "Company", saved, 7,
                        new BigDecimal("0.18"), 42L),
                new CategoryProduct("null-pnk", "Name", null, false,
                        new BigDecimal("169.99"), "Venit", false, "Company", saved, 7,
                        new BigDecimal("0.18"), 42L))) {
            var result = ProfitCalculatorCategory.create("S", "Trimmers", List.of(input)).products().getFirst();
            assertEquals(saved, result.overrides());
            assertEquals(7, result.overrideRevision());
            assertNull(result.companyName());
            assertNull(result.emagSalePriceRon());
            assertNull(result.calculated().effectiveSalePriceRon());
            assertNull(result.calculated().effectiveCommissionRate());
            assertNull(result.calculated().commissionCostRon());
            assertNull(result.calculated().purchaseCostRon());
            assertNull(result.calculated().transportCostRon());
            assertNull(result.calculated().googleAdsCostRon());
            assertNull(result.calculated().socialMediaAdsCostRon());
            assertNull(result.calculated().storageCostRon());
            assertNull(result.calculated().turnoverTaxCostRon());
            assertNull(result.calculated().vatCostRon());
            assertNull(result.calculated().profitTaxCostRon());
            assertNull(result.calculated().dividendTaxCostRon());
            assertNull(result.calculated().previousMonthGrossUnits());
        }
    }

    @Test
    void serializesOverrideDecimalsAsExactStringsForEditingRoundTrips() {
        var exact = "9007199254740993.1234567890123456789";
        var tinyRate = "0.0000000000000000000001";
        var saved = new Overrides(new BigDecimal(exact), new BigDecimal("0.14"),
                null, null, new BigDecimal(tinyRate), null);
        var source = new CategoryProduct("code", "Product", "PNK", false,
                new BigDecimal("169.99"), "Profit", true, "Company", saved, 1);
        var response = ProfitCalculatorCategory.create("S", "Trimmers", List.of(source));
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertTrue(json.get("products").get(0).get("overrides").get("salePriceRon").isTextual());
        assertEquals(exact, json.get("products").get(0).get("overrides").get("salePriceRon").asString());
        assertEquals("0.14", json.get("products").get(0).get("overrides").get("commissionRate").asString());
        assertEquals(tinyRate, json.get("products").get(0).get("overrides").get("usdRonRate").asString());
    }
}
