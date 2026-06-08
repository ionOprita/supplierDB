package ro.sellfluence.test;

import ro.sellfluence.db.CategoryDataTable.CategoryColumn;
import ro.sellfluence.db.CategoryDataTable.CategoryInfo;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

/**
 * Verify that category fields duplicated in product match the canonical category_sheet_data rows.
 */
public class VerifyCategory {

    private static final List<FieldMapping> FIELD_MAPPINGS = List.of(
            new FieldMapping("subcategory_country", CategoryColumn.SUBCATEGORY_COUNTRY, ProductInfo::category),
            new FieldMapping("big_category", CategoryColumn.BIG_CATEGORY, ProductInfo::bigCategory),
            new FieldMapping("division", CategoryColumn.DIVISION, ProductInfo::division),
            new FieldMapping("supracategory", CategoryColumn.SUPRACATEGORY, ProductInfo::supracategory),
            new FieldMapping("category", CategoryColumn.CATEGORY, ProductInfo::categoryName),
            new FieldMapping("subcategory", CategoryColumn.SUBCATEGORY, ProductInfo::subcategory),
            new FieldMapping("subsubcategory", CategoryColumn.SUBSUBCATEGORY, ProductInfo::subsubcategory),
            new FieldMapping("supracategory_country", CategoryColumn.SUPRACATEGORY_COUNTRY, ProductInfo::supracategoryCountry),
            new FieldMapping("category_country", CategoryColumn.CATEGORY_COUNTRY, ProductInfo::categoryCountry),
            new FieldMapping("category_id", CategoryColumn.CATEGORY_ID, product -> stringValue(product.categoryId())),
            new FieldMapping("scm_id", CategoryColumn.SCM_ID, product -> stringValue(product.scmId())),
            new FieldMapping("doc_id", CategoryColumn.DOC_ID, product -> stringValue(product.docId()))
    );

    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var databaseAlias = arguments.getOption(databaseOptionName, defaultDatabase);
        System.out.printf("Verifying product/category consistency in database %s...%n", databaseAlias);

        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(databaseAlias);
        var products = mirrorDB.readProducts();
        var categories = mirrorDB.readCategoryData();

        var verifier = new VerifyCategory();
        var result = verifier.verify(products, categories);
        result.print();

        if (result.hasErrors()) {
            System.exit(1);
        }
    }

    VerificationResult verify(List<ProductInfo> products, List<CategoryInfo> categories) {
        var result = new VerificationResult(products.size(), categories.size());
        var categoriesByScmId = indexCategoriesByScmId(categories, result);

        for (var product : products) {
            var scmId = product.scmId();
            if (scmId == null) {
                result.productsWithoutScmId.add(productRef(product));
                continue;
            }

            var matchingCategories = categoriesByScmId.get(scmId);
            if (matchingCategories == null || matchingCategories.isEmpty()) {
                result.productsWithoutCategory.add("%s has scm_id=%d".formatted(productRef(product), scmId));
                continue;
            }
            if (matchingCategories.size() > 1) {
                result.productsWithAmbiguousCategory.add("%s has scm_id=%d matching category source rows %s".formatted(
                        productRef(product),
                        scmId,
                        matchingCategories.stream()
                                .map(CategoryInfo::sourceRowNumber)
                                .map(Objects::toString)
                                .collect(Collectors.joining(", "))
                ));
                continue;
            }

            compare(product, matchingCategories.getFirst(), result);
            result.matchedProducts++;
        }

        return result;
    }

    private static Map<Integer, List<CategoryInfo>> indexCategoriesByScmId(List<CategoryInfo> categories, VerificationResult result) {
        var categoriesByScmId = new LinkedHashMap<Integer, List<CategoryInfo>>();
        for (var category : categories) {
            var scmId = integerValue(category.value(CategoryColumn.SCM_ID), "category row " + category.sourceRowNumber() + " scm_id");
            if (scmId == null) {
                result.categoriesWithoutScmId.add(categoryRef(category));
                continue;
            }
            categoriesByScmId.computeIfAbsent(scmId, _ -> new ArrayList<>()).add(category);
        }
        for (var entry : categoriesByScmId.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.duplicateCategoryScmIds.add("scm_id=%d appears in category source rows %s".formatted(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(CategoryInfo::sourceRowNumber)
                                .map(Objects::toString)
                                .collect(Collectors.joining(", "))
                ));
            }
        }
        return categoriesByScmId;
    }

    private static void compare(ProductInfo product, CategoryInfo category, VerificationResult result) {
        for (var mapping : FIELD_MAPPINGS) {
            var productValue = normalize(mapping.productValue.apply(product));
            var categoryValue = normalize(category.value(mapping.categoryColumn));
            if (Objects.equals(productValue, categoryValue)) {
                continue;
            }
            result.mismatches.add(new Mismatch(
                    productRef(product),
                    categoryRef(category),
                    mapping.label,
                    productValue,
                    categoryValue
            ));
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integerValue(String value, String label) {
        var normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer but was '" + value + "'.", e);
        }
    }

    private static String productRef(ProductInfo product) {
        return "product_code=%s name=%s".formatted(display(product.productCode()), display(product.name()));
    }

    private static String categoryRef(CategoryInfo category) {
        return "source_row_number=%d subcategory_country=%s".formatted(
                category.sourceRowNumber(),
                display(category.subcategoryCountry())
        );
    }

    private static String display(String value) {
        var normalized = normalize(value);
        return normalized == null ? "<blank>" : normalized;
    }

    private record FieldMapping(
            String label,
            CategoryColumn categoryColumn,
            Function<ProductInfo, String> productValue
    ) {
    }

    private record Mismatch(
            String product,
            String category,
            String field,
            String productValue,
            String categoryValue
    ) {
        void print() {
            System.out.printf(
                    "Mismatch %-24s product=[%s] category=[%s] product_value=%s category_value=%s%n",
                    field,
                    product,
                    category,
                    display(productValue),
                    display(categoryValue)
            );
        }
    }

    static class VerificationResult {
        private final int productCount;
        private final int categoryCount;
        private int matchedProducts;
        private final List<String> categoriesWithoutScmId = new ArrayList<>();
        private final List<String> duplicateCategoryScmIds = new ArrayList<>();
        private final List<String> productsWithoutScmId = new ArrayList<>();
        private final List<String> productsWithoutCategory = new ArrayList<>();
        private final List<String> productsWithAmbiguousCategory = new ArrayList<>();
        private final List<Mismatch> mismatches = new ArrayList<>();

        VerificationResult(int productCount, int categoryCount) {
            this.productCount = productCount;
            this.categoryCount = categoryCount;
        }

        boolean hasErrors() {
            return !duplicateCategoryScmIds.isEmpty()
                   || !productsWithoutScmId.isEmpty()
                   || !productsWithoutCategory.isEmpty()
                   || !productsWithAmbiguousCategory.isEmpty()
                   || !mismatches.isEmpty();
        }

        void print() {
            System.out.printf("Products: %d, categories: %d, matched products: %d.%n", productCount, categoryCount, matchedProducts);
            printSection("Category rows without scm_id", categoriesWithoutScmId);
            printSection("Duplicate category scm_id values", duplicateCategoryScmIds);
            printSection("Products without scm_id", productsWithoutScmId);
            printSection("Products without matching category", productsWithoutCategory);
            printSection("Products with ambiguous matching category", productsWithAmbiguousCategory);

            if (!mismatches.isEmpty()) {
                System.out.printf("Field mismatches: %d%n", mismatches.size());
                mismatches.stream().filter(it -> it.productValue()!=null).forEach(Mismatch::print);
            } else {
                System.out.println("Field mismatches: 0");
            }

            if (hasErrors()) {
                System.out.println("Category verification failed.");
            } else {
                System.out.println("Category verification passed.");
            }
        }

        private static void printSection(String title, List<String> lines) {
            System.out.printf("%s: %d%n", title, lines.size());
            lines.forEach(line -> System.out.println("  " + line));
        }
    }
}
