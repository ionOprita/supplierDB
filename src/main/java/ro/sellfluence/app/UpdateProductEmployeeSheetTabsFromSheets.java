package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.EmployeeSheetTabUpdate;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.support.Logs;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheetByName;

/**
 * Updates only the product table's employee-sheet tab mappings from the individual employee spreadsheets.
 */
public class UpdateProductEmployeeSheetTabsFromSheets {

    private static final Logger logger = Logs.getConsoleLogger("updateProductEmployeeSheetTabsWarnings", WARNING);
    private static final Logger infos = Logs.getConsoleAndFileLogger(
            "updateProductEmployeeSheetTabsInfos",
            INFO,
            10,
            1_000_000
    );
    private static final String settingsSheetName = "Setari";

    /**
     * Reads every referenced employee spreadsheet once. Each successfully read spreadsheet is stored independently,
     * so a timeout does not prevent healthy spreadsheets from making progress.
     */
    public static void updateEmployeeSheetTabs(EmagMirrorDB mirrorDB) throws SQLException {
        Objects.requireNonNull(mirrorDB);
        var products = mirrorDB.readProducts();
        var productsByEmployeeSheet = products.stream()
                .filter(product -> hasText(product.productCode()))
                .filter(product -> hasText(product.pnk()))
                .filter(product -> hasText(product.employeeSheetName()))
                .collect(Collectors.groupingBy(
                        ProductInfo::employeeSheetName,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        var attemptedGroups = productsByEmployeeSheet.size();
        var unresolvedProducts = products.stream()
                .filter(product -> hasText(product.productCode()))
                .filter(product -> !hasText(product.pnk()) || !hasText(product.employeeSheetName()))
                .map(product -> tabUpdate(product, null))
                .toList();
        if (!unresolvedProducts.isEmpty()) {
            attemptedGroups++;
        }
        var updatedRows = 0;
        var failures = new ArrayList<Exception>();
        var failedEmployeeSheets = 0;
        try {
            updatedRows += mirrorDB.updateProductEmployeeSheetTabs(unresolvedProducts);
        } catch (Exception e) {
            failures.add(new RuntimeException("Products without a complete employee sheet mapping failed.", e));
            logger.log(WARNING, "Could not clear employee sheet tabs for products without a complete sheet mapping.", e);
        }

        for (var entry : productsByEmployeeSheet.entrySet()) {
            var employeeSheetName = entry.getKey();
            try {
                var spreadSheet = getSpreadSheetByName(defaultGoogleApp, employeeSheetName);
                if (spreadSheet == null) {
                    throw new RuntimeException("Spreadsheet %s not found.".formatted(employeeSheetName));
                }
                infos.log(INFO, () -> "Read from %s %s columns C and E."
                        .formatted(spreadSheet.getSpreadSheetName(), settingsSheetName));
                var mappings = mappingsFrom(spreadSheet.getMultipleColumns(settingsSheetName, "C", "E"));
                var sheetUpdates = tabUpdatesFor(entry.getValue(), mappings);
                for (var update : sheetUpdates) {
                    if (update.tabName() == null) {
                        logger.log(WARNING, "No employee sheet tab mapping found for PNK %s in spreadsheet %s."
                                .formatted(update.pnk(), employeeSheetName));
                    }
                }
                updatedRows += mirrorDB.updateProductEmployeeSheetTabs(sheetUpdates);
            } catch (Exception e) {
                failedEmployeeSheets++;
                failures.add(new RuntimeException("Employee spreadsheet %s failed."
                        .formatted(employeeSheetName), e));
                logger.log(WARNING, "Could not update employee sheet tabs from spreadsheet %s."
                        .formatted(employeeSheetName), e);
            }
        }

        var finalUpdatedRows = updatedRows;
        var finalAttemptedGroups = attemptedGroups;
        var successfulEmployeeSheets = productsByEmployeeSheet.size() - failedEmployeeSheets;
        infos.log(INFO, () -> "Updated employee_sheet_tab for %d products from %d employee spreadsheets."
                .formatted(finalUpdatedRows, successfulEmployeeSheets));
        if (!failures.isEmpty()) {
            var failure = new RuntimeException("Could not update employee sheet tabs for %d of %d groups."
                    .formatted(failures.size(), finalAttemptedGroups));
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    static Map<String, String> mappingsFrom(List<List<Object>> rows) {
        Objects.requireNonNull(rows);
        var mappings = new LinkedHashMap<String, String>();
        for (var row : rows.stream().skip(2).toList()) {
            if (row == null || row.size() < 2
                    || !(row.get(0) instanceof String pnk) || !hasText(pnk)
                    || !(row.get(1) instanceof String tabName) || !hasText(tabName)) {
                continue;
            }
            var oldTabName = mappings.put(pnk, tabName);
            if (oldTabName != null && !oldTabName.equals(tabName)) {
                logger.log(WARNING, "Replaced duplicate employee sheet mapping for PNK %s: from %s to %s."
                        .formatted(pnk, oldTabName, tabName));
            }
        }
        return mappings;
    }

    static List<EmployeeSheetTabUpdate> tabUpdatesFor(List<ProductInfo> products, Map<String, String> mappings) {
        Objects.requireNonNull(products);
        Objects.requireNonNull(mappings);
        return products.stream()
                .filter(Objects::nonNull)
                .filter(product -> hasText(product.productCode()))
                .map(product -> tabUpdate(
                        product,
                        hasText(product.pnk()) ? mappings.get(product.pnk()) : null
                ))
                .toList();
    }

    private static EmployeeSheetTabUpdate tabUpdate(ProductInfo product, String tabName) {
        return new EmployeeSheetTabUpdate(
                product.productCode(),
                product.pnk(),
                product.employeeSheetName(),
                tabName
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
