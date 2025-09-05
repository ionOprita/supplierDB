package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static java.lang.Boolean.TRUE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheetByName;

/**
 * Provides the method for updating our product information.
 */
public class PopulateProductsTableFromSheets {

    private static final Logger logger = Logs.getConsoleLogger("populateProductsTableWarnings", WARNING);
    private static final Logger infos = Logs.getConsoleAndFileLogger("populateProductsTableInfos", INFO, 10, 1_000_000);

    private static final String productSpreadsheetName = "2025 - Date produse & angajati";

    /**
     * Find all products that are on the main sheet and add any missing product to our database.
     */
    public static void updateProductTable(EmagMirrorDB mirrorDB) {
        var sheet = getSpreadSheetByName(defaultGoogleApp, productSpreadsheetName);
        if (sheet == null) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(productSpreadsheetName));
        }
        var productInfos = populateFrom(sheet, "Cons. Date Prod.");
        for (ProductInfo productInfo : productInfos) {
            try {
                mirrorDB.addOrUpdateProduct(productInfo);
            } catch (SQLException e) {
                logger.log(WARNING, "Could not add the product " + productInfo, e);
            }
        }
    }

    private record PNKMapping(String pnk, String sheetName, String tabName) {
    }

    private static final Map<String, PNKMapping> pnkMapping = new HashMap<>();

    private static final String setariSheetName = "Setari";

    private static void addMappingsFrom(String sheetName) {
        var spreadSheet = getSpreadSheetByName(defaultGoogleApp, sheetName);
        if (spreadSheet == null) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(sheetName));
        }
        // This reads the setari sheet so that we can map from PNK to the tab within the spreadsheet.
        infos.log(INFO, () -> "Read from %s %s columns C and E\n ".formatted(spreadSheet.getSpreadSheetName(), setariSheetName));
        spreadSheet.getMultipleColumns(setariSheetName, "C", "E").stream()
                .skip(2)
                .map(row -> {
                    if (row.get(0) instanceof String pnk && row.get(1) instanceof String tabName) {
                        return new PNKMapping(pnk, sheetName, tabName);
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .forEach(it -> {
                    var oldMapping = pnkMapping.get(it.pnk);
                    if (oldMapping != null && !oldMapping.equals(it)) {
                        logger.log(INFO, "Replaced mapping for PNK %s: from %s to %s".formatted(it.pnk, oldMapping, it));
                    }
                    pnkMapping.put(it.pnk, it);
                });
    }

    private static String getEmployeeSheetTabFor(String pnk, String employeeSheetName) {
        if (employeeSheetName == null) {
            return null;
        }
        var mapping = pnkMapping.get(pnk);
        if (mapping == null || !mapping.sheetName.equals(employeeSheetName)) {
            addMappingsFrom(employeeSheetName);
            mapping = pnkMapping.get(pnk);
        }
        if (mapping == null) {
            logger.log(WARNING, "No mapping found for PNK %s".formatted(pnk));
            return null;
        }
        if (!mapping.sheetName.equals(employeeSheetName)) {
            logger.log(WARNING, "Mapping for PNK %s is %s, but we were asked for %s.".formatted(pnk, mapping, employeeSheetName));
            return null;
        }
        return mapping.tabName;
    }


    /**
     * Read from the Google spreadsheet our product information.
     *
     * @param spreadSheet from which to read the product data.
     * @param overviewSheetName name of the tab holding the product data.
     * @return list of record needed for populating the database.
     */
    private static List<ProductInfo> populateFrom(SheetsAPI spreadSheet, String overviewSheetName) {
        Objects.requireNonNull(spreadSheet);
        Objects.requireNonNull(overviewSheetName);
        var productsData = spreadSheet.getMultipleColumns(overviewSheetName, "C", "K", "U", "V", "BH", "CN", "DW", "EI").stream()
                .skip(3).toList();
        return productsData.stream()
                .<ProductInfo>mapMulti((row, nextConsumer) -> {
                            var name = row.get(0).toString();
                            var productCode = row.get(1).toString();
                            var continueToSell = TRUE.equals(row.get(2));
                            var retracted = TRUE.equals(row.get(3));
                            var pnk = row.get(4).toString();
                            var category = row.get(5).toString();
                            var messageKeyword = row.get(6).toString();
                            var employeeSheetName = row.get(7).toString();
                            if (employeeSheetName.equals("0") || employeeSheetName.isBlank()) {
                                employeeSheetName = null;
                            }
                            if (continueToSell && retracted) {
                                logger.log(
                                        WARNING,
                                        "Product %s (%s) has both 'continue to sell' and 'retracted' set, which doesn't make sense. Dropping retracted."
                                                .formatted(name, pnk)
                                );
                                retracted = false;
                            }
                            var employeeSheetTab = getEmployeeSheetTabFor(pnk, employeeSheetName);
                            if (!productCode.isBlank()) {
                                nextConsumer.accept(new ProductInfo(pnk, productCode, name, continueToSell, retracted, category, messageKeyword, employeeSheetName, employeeSheetTab));
                            }
                        }
                ).toList();
    }

    public static void main(String[] args) throws SQLException, IOException {
        updateProductTable(EmagMirrorDB.getEmagMirrorDB(new Arguments(args).getOption(databaseOptionName, defaultDatabase)));
    }
}