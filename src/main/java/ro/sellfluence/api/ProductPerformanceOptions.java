package ro.sellfluence.api;

import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.Vendor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Product selector metadata and vendor membership checks, independent of database access. */
public final class ProductPerformanceOptions {
    private ProductPerformanceOptions() {
    }

    public record ProductOption(String productCode, String name, String pnk, String url) {
    }

    public record VendorOption(UUID vendorId, String name, String account, List<ProductOption> products) {
    }

    public record Response(List<VendorOption> vendors, UUID defaultVendorId) {
    }

    static Response create(List<Vendor> vendors, List<ProductInfo> products) {
        var sortedVendors = API.adsVendorOptions(vendors);
        var vendorIds = sortedVendors.vendors().stream().map(API.AdsVendor::vendorId).collect(Collectors.toSet());
        var productsByVendor = new HashMap<UUID, List<ProductOption>>();
        products.stream()
                .filter(product -> product.vendor() != null && vendorIds.contains(product.vendor()))
                .filter(product -> product.productCode() != null && !product.productCode().isBlank())
                .sorted(Comparator.comparing(ProductPerformanceOptions::productName,
                                ProductPerformanceOptions::compareProductNames)
                        .thenComparing(ProductInfo::productCode))
                .forEach(product -> productsByVendor.computeIfAbsent(product.vendor(), ignored -> new ArrayList<>())
                        .add(productOption(product)));

        var options = sortedVendors.vendors().stream()
                .map(vendor -> new VendorOption(vendor.vendorId(), vendor.name(), vendor.account(),
                        List.copyOf(productsByVendor.getOrDefault(vendor.vendorId(), List.of()))))
                .toList();
        return new Response(options, sortedVendors.defaultVendorId());
    }

    static Optional<ProductPerformanceMockData.Product> resolve(
            List<Vendor> vendors, ProductInfo product, UUID vendorId, String productCode) {
        if (vendorId == null || productCode == null || productCode.isBlank() || product == null
                || !vendorId.equals(product.vendor()) || !productCode.equals(product.productCode())) {
            return Optional.empty();
        }
        return vendors.stream()
                .filter(vendor -> vendorId.equals(vendor.id()))
                .findFirst()
                .map(vendor -> new ProductPerformanceMockData.Product(vendorLabel(vendor), productName(product),
                        blankToNull(product.pnk()), productUrl(product)));
    }

    private static ProductOption productOption(ProductInfo product) {
        return new ProductOption(product.productCode(), productName(product), blankToNull(product.pnk()), productUrl(product));
    }

    private static String productName(ProductInfo product) {
        return product.name() == null || product.name().isBlank() ? product.productCode() : product.name();
    }

    private static int compareProductNames(String first, String second) {
        boolean firstSupported = supportsProductNameOrder(first);
        boolean secondSupported = supportsProductNameOrder(second);
        if (firstSupported != secondSupported) return firstSupported ? -1 : 1;
        return firstSupported ? ProductInfo.nameComparatorString.compare(first, second) : first.compareTo(second);
    }

    private static boolean supportsProductNameOrder(String name) {
        // The shared comparator rejects names outside its numbered vendor format.
        // Keep those products selectable, including products displayed by their code.
        try {
            ProductInfo.nameComparatorString.compare(name, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String vendorLabel(Vendor vendor) {
        if (vendor.name() != null && !vendor.name().isBlank()) return vendor.name();
        if (vendor.account() != null && !vendor.account().isBlank()) return vendor.account();
        return vendor.id().toString();
    }

    private static String productUrl(ProductInfo product) {
        var link = blankToNull(product.emagLink());
        if (link != null) return link;
        var pnk = blankToNull(product.pnk());
        return pnk == null ? null : "https://emag.ro/product_details/pd/"
                + URLEncoder.encode(pnk.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
