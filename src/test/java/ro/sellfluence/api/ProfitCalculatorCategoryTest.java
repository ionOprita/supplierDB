package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                response.products().stream().map(CategoryProduct::productCode).toList());
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
        assertEquals(7, json.get("products").get(0).size());
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

        assertEquals(List.of(zero, reference, missing), response.products());
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
}
