package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorTable.CategoryProduct;
import tools.jackson.databind.ObjectMapper;

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
        assertEquals(4, json.get("products").get(0).size());
    }

    @Test
    void returnsAnEmptyCategoryWithoutInventingProducts() {
        var response = ProfitCalculatorCategory.create("J", "Empty category", List.of());
        assertEquals("J", response.vendorGroup());
        assertEquals("Empty category", response.category());
        assertTrue(response.products().isEmpty());
    }
}
