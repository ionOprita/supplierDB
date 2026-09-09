package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.Vendor;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPerformanceOptionsTest {
    @Test
    void groupsProductsByVendorWithNaturalNameOrderingAndIncludesEmptyVendors() {
        var empty = vendor("A empty", "empty");
        var other = vendor("B other", "other");
        var sellfusion = vendor("Z sellfusion", "sellfusion");
        var products = List.of(
                product(sellfusion.id(), "ten", "S. 10 - Product", "PNK-10", null),
                product(sellfusion.id(), "two-z", "S. 2 - Product", "PNK-2Z", null),
                product(sellfusion.id(), "two-a", "S. 2 - Product", "PNK-2A", null),
                product(sellfusion.id(), "S. 3 - Fallback", " ", null, null),
                product(sellfusion.id(), "S. 4 - Fallback", null, null, null),
                product(sellfusion.id(), "fallback-code", null, null, null),
                product(other.id(), "other-product", "S. 1 - Product", "OTHER-PNK", null),
                product(null, "unassigned", "Unassigned", null, null),
                product(UUID.randomUUID(), "orphan", "Orphan", null, null),
                product(sellfusion.id(), null, "No code", null, null),
                product(sellfusion.id(), "", "Empty code", null, null),
                product(sellfusion.id(), " \t", "Blank code", null, null)
        );

        var options = ProductPerformanceOptions.create(List.of(sellfusion, other, empty), products);

        assertEquals(List.of(empty.id(), other.id(), sellfusion.id()),
                options.vendors().stream().map(ProductPerformanceOptions.VendorOption::vendorId).toList());
        assertEquals(sellfusion.id(), options.defaultVendorId());
        assertTrue(options.vendors().getFirst().products().isEmpty());
        assertEquals(List.of("other-product"), options.vendors().get(1).products().stream()
                .map(ProductPerformanceOptions.ProductOption::productCode).toList());
        assertEquals(List.of("two-a", "two-z", "S. 3 - Fallback", "S. 4 - Fallback", "ten", "fallback-code"),
                options.vendors().getLast().products().stream()
                        .map(ProductPerformanceOptions.ProductOption::productCode).toList());
        assertEquals("S. 3 - Fallback", options.vendors().getLast().products().get(2).name());
        assertEquals("S. 4 - Fallback", options.vendors().getLast().products().get(3).name());
        assertEquals("fallback-code", options.vendors().getLast().products().getLast().name());

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(options));
        var vendor = json.get("vendors").get(2);
        assertEquals(4, vendor.size());
        assertEquals("sellfusion", vendor.get("account").asString());
        var firstProduct = vendor.get("products").get(0);
        assertEquals(4, firstProduct.size());
        assertEquals("two-a", firstProduct.get("productCode").asString());
        assertEquals("S. 2 - Product", firstProduct.get("name").asString());
        assertEquals("PNK-2A", firstProduct.get("pnk").asString());
        assertEquals("https://emag.ro/product_details/pd/PNK-2A", firstProduct.get("url").asString());
    }

    @Test
    void defaultsToTheFirstVendorWhenSellfusionIsAbsentAndHandlesNoVendors() {
        var first = vendor("A first", "first");
        var second = vendor("B second", "second");
        var options = ProductPerformanceOptions.create(List.of(second, first), List.of());
        assertEquals(first.id(), options.defaultVendorId());
        assertTrue(options.vendors().stream().allMatch(vendor -> vendor.products().isEmpty()));

        var empty = ProductPerformanceOptions.create(List.of(),
                List.of(product(first.id(), "orphan", "Orphan", null, null)));
        assertTrue(empty.vendors().isEmpty());
        assertNull(empty.defaultVendorId());
    }

    @Test
    void resolvesOnlyAnExistingProductWithinTheSelectedVendor() {
        var first = vendor("First", "first");
        var second = vendor("Second", "second");
        var vendors = List.of(first, second);
        var selected = product(first.id(), "code", "Selected product", "PNK", null);

        assertTrue(ProductPerformanceOptions.resolve(vendors, selected, first.id(), "code").isPresent());
        assertTrue(ProductPerformanceOptions.resolve(vendors, selected, second.id(), "code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(vendors, selected, UUID.randomUUID(), "code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(vendors, selected, first.id(), "different-code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(vendors, selected, null, "code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(vendors, null, first.id(), "code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(List.of(second), selected, first.id(), "code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(vendors,
                product(null, "code", "Unassigned", null, null), first.id(), "code").isEmpty());
        assertTrue(ProductPerformanceOptions.resolve(vendors,
                product(first.id(), " ", "Blank code", null, null), first.id(), " ").isEmpty());
    }

    @Test
    void usesActualProductAndVendorMetadataWithoutManufacturingHistory() {
        var vendor = vendor("Actual vendor", "actual-account");
        var product = product(vendor.id(), "ACTUAL-CODE", "Actual product", "ACTUAL-PNK",
                "https://www.emag.ro/actual-product/pd/ACTUAL-PNK");
        var metadata = ProductPerformanceOptions.resolve(List.of(vendor), product, vendor.id(), "ACTUAL-CODE")
                .orElseThrow();
        var response = ProductPerformanceData.create(metadata, ProductPerformanceData.Period.WEEK, List.of());

        assertFalse(response.mock());
        assertEquals(new ProductPerformanceData.Product("Actual vendor", "Actual product", "ACTUAL-PNK",
                "https://www.emag.ro/actual-product/pd/ACTUAL-PNK"), response.product());
        assertTrue(response.rows().isEmpty());
        assertTrue(response.errors().isEmpty());

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertFalse(json.get("mock").asBoolean());
        assertEquals("Actual product", json.get("product").get("name").asString());
        assertEquals("Actual vendor", json.get("product").get("label").asString());
    }

    @Test
    void encodesFallbackPnkUrlsAndLeavesUnavailableLinksNull() {
        var vendor = vendor("Vendor", "account");
        var encoded = product(vendor.id(), "encoded", "Encoded", " PN K/with?#%é ", " ");
        var unavailable = product(vendor.id(), "unavailable", null, " ", null);
        var options = ProductPerformanceOptions.create(List.of(vendor), List.of(encoded, unavailable));

        var encodedOption = options.vendors().getFirst().products().stream()
                .filter(product -> product.productCode().equals("encoded")).findFirst().orElseThrow();
        assertEquals("https://emag.ro/product_details/pd/PN%20K%2Fwith%3F%23%25%C3%A9", encodedOption.url());
        var missing = ProductPerformanceOptions.resolve(List.of(vendor), unavailable, vendor.id(), "unavailable")
                .orElseThrow();
        assertEquals("unavailable", missing.name());
        assertNull(missing.pnk());
        assertNull(missing.url());
    }

    private static Vendor vendor(String name, String account) {
        return new Vendor(UUID.randomUUID(), name, false, name, account, null);
    }

    private static ProductInfo product(UUID vendorId, String code, String name, String pnk, String link) {
        var values = new LinkedHashMap<String, Object>();
        values.put("vendor", vendorId);
        values.put("productCode", code);
        values.put("name", name);
        values.put("pnk", pnk);
        values.put("emagLink", link);
        values.put("continueToSell", false);
        values.put("retracted", false);
        return new ObjectMapper().convertValue(values, ProductInfo.class);
    }
}
