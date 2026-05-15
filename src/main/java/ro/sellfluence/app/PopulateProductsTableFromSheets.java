package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.Vendor;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

    enum ProductColumn {
        NAME("C"),
        MODEL("D"),
        PRODUCT_LENGTH_MM("E"),
        PRODUCT_WIDTH_MM("F"),
        PRODUCT_HEIGHT_MM("G"),
        PRODUCT_WEIGHT_G("H"),
        EAN("I"),
        BRAND("J"),
        PRODUCT_CODE("K"),
        WARRANTY_MONTHS("L"),
        IMPORT_TAX("M"),
        SUPPLIER_PRODUCT_CODE("N"),
        CONTINUE_TO_SELL("U"),
        RETRACTED("V"),
        AIR_TRANSPORT_PCS_PER_CARTON("W"),
        AIR_TRANSPORT_KG_PER_CARTON("X"),
        AIR_TRANSPORT_LENGTH_CM_PER_CARTON("Y"),
        AIR_TRANSPORT_WIDTH_CM_PER_CARTON("Z"),
        AIR_TRANSPORT_HEIGHT_CM_PER_CARTON("AA"),
        AIR_TRANSPORT_VOLUME_M3_PER_CARTON("AB"),
        RAIL_TRANSPORT_PCS_PER_CARTON("AC"),
        RAIL_TRANSPORT_KG_PER_CARTON("AD"),
        RAIL_TRANSPORT_LENGTH_CM_PER_CARTON("AE"),
        RAIL_TRANSPORT_WIDTH_CM_PER_CARTON("AF"),
        RAIL_TRANSPORT_HEIGHT_CM_PER_CARTON("AG"),
        RAIL_TRANSPORT_VOLUME_M3_PER_CARTON("AH"),
        SEA_TRANSPORT_PCS_PER_CARTON("AI"),
        SEA_TRANSPORT_KG_PER_CARTON("AJ"),
        SEA_TRANSPORT_LENGTH_CM_PER_CARTON("AK"),
        SEA_TRANSPORT_WIDTH_CM_PER_CARTON("AL"),
        SEA_TRANSPORT_HEIGHT_CM_PER_CARTON("AM"),
        SEA_TRANSPORT_VOLUME_M3_PER_CARTON("AN"),
        TRUCK_TRANSPORT_PCS_PER_CARTON("AO"),
        TRUCK_TRANSPORT_KG_PER_CARTON("AP"),
        TRUCK_TRANSPORT_LENGTH_CM_PER_CARTON("AQ"),
        TRUCK_TRANSPORT_WIDTH_CM_PER_CARTON("AR"),
        TRUCK_TRANSPORT_HEIGHT_CM_PER_CARTON("AS"),
        TRUCK_TRANSPORT_VOLUME_M3_PER_CARTON("AT"),
        PNK("BH"),
        EMAG_LINK("BI"),
        EMAG_TITLE("BJ"),
        VENDOR_NAME("BK"),
        INCOME_PROFIT_TAX("BL"),
        VAT_PAYER("BM"),
        EMAG_SALE_PRICE_RON("BN"),
        EMAG_COMMISSION("BO"),
        OFFER_ID_CONCEPT("BP"),
        OFFER_ID_SOLUTIONS("BT"),
        OFFER_ID_SOLUTIONS_FBE("BU"),
        OFFER_ID_JUDIOS_CONCEPT("BV"),
        OFFER_ID_JUDIOS_CONCEPT_FBE("BW"),
        OFFER_ID_JUDY_CREATIVE_STUDIOS_FBE("BX"),
        OFFER_ID_SELLFUSION("CA"),
        OFFER_ID_SELLFUSION_FBE("CB"),
        OFFER_ID_KOPPEL("CC"),
        OFFER_ID_KOPPEL_FBE("CD"),
        CATEGORY("CN"),
        INDEX_CATEGORY("CO"),
        DIVISION("CP"),
        SUPRACATEGORY("CQ"),
        CATEGORY_NAME("CR"),
        SUBCATEGORY("CS"),
        SUBSUBCATEGORY("CT"),
        SUPRACATEGORY_COUNTRY("CU"),
        CATEGORY_COUNTRY("CV"),
        CATEGORY_ID("CX"),
        SCM_ID("CY"),
        DOC_ID("CZ"),
        INDEXED_SUBCATEGORY_COUNTRY("DA"),
        BIG_CATEGORY("DB"),
        EMAG_ADS_AUTO_ID("DC"),
        EMAG_ADS_MANUAL_ID("DD"),
        MESSAGE_KEYWORD("DW"),
        GENDER("DX"),
        MANUAL_VIDEO_LINK("DY"),
        USAGE_GUIDE_LINK("DZ"),
        USAGE_SITE_LINK("EA"),
        USAGE_MANUAL_LINK("EB"),
        OTHER_COMMENTS("EC"),
        REVIEW_CALLER("EH"),
        EMPLOYEE_SHEET_NAME("EI"),
        REPORT_LINK("EJ");

        private final String sheetColumn;

        ProductColumn(String sheetColumn) {
            this.sheetColumn = sheetColumn;
        }
    }

    private static final String[] productDataColumns = Arrays.stream(ProductColumn.values())
            .map(column -> column.sheetColumn)
            .toArray(String[]::new);

    /**
     * Find all products that are on the main sheet and add any missing product to our database.
     */
    public static void updateProductTable(EmagMirrorDB mirrorDB) {
        var sheet = getSpreadSheetByName(defaultGoogleApp, productSpreadsheetName);
        if (sheet == null) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(productSpreadsheetName));
        }
        Map<String, UUID> vendors;
        try {
            vendors = mirrorDB.getAllVendors().stream().filter(Vendor::isFBE).collect(Collectors.toMap(Vendor::companyName, Vendor::id));
        } catch (SQLException e) {
            throw new RuntimeException("Could not read vendor table.");
        }
        var productInfos = populateFrom(sheet, "Cons. Date Prod.", vendors);
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
     * @param spreadSheet       from which to read the product data.
     * @param overviewSheetName name of the tab holding the product data.
     * @param vendors   for mapping vendors to UUID.
     * @return list of record needed for populating the database.
     */
    private static List<ProductInfo> populateFrom(SheetsAPI spreadSheet, String overviewSheetName, Map<String, UUID> vendors) {
        Objects.requireNonNull(spreadSheet);
        Objects.requireNonNull(overviewSheetName);
        var productsData = spreadSheet.getMultipleColumns(overviewSheetName, productDataColumns).stream()
                .skip(3).toList();
        return productsData.stream()
                .map(row -> toProductInfo(row, vendors, PopulateProductsTableFromSheets::getEmployeeSheetTabFor))
                .filter(Objects::nonNull)
                .toList();
    }

    static ProductInfo toProductInfo(List<Object> row, Map<String, UUID> vendors, BiFunction<String, String, String> employeeSheetTabResolver) {
        var productCode = stringCell(row, ProductColumn.PRODUCT_CODE);
        if (productCode == null) {
            return null;
        }

        var name = stringCell(row, ProductColumn.NAME);
        var continueToSell = TRUE.equals(booleanCell(row, ProductColumn.CONTINUE_TO_SELL));
        var retracted = TRUE.equals(booleanCell(row, ProductColumn.RETRACTED));
        var pnk = stringCell(row, ProductColumn.PNK);
        var employeeSheetName = stringCell(row, ProductColumn.EMPLOYEE_SHEET_NAME);
        if (continueToSell && retracted) {
            logger.log(
                    WARNING,
                    "Product %s (%s) has both 'continue to sell' and 'retracted' set, which doesn't make sense. Dropping retracted."
                            .formatted(name, pnk)
            );
            retracted = false;
        }

        var employeeSheetTab = employeeSheetTabResolver.apply(pnk, employeeSheetName);
        return new ProductInfo(
                pnk,
                productCode,
                name,
                vendors.get(stringCell(row, ProductColumn.VENDOR_NAME)),
                continueToSell,
                retracted,
                stringCell(row, ProductColumn.CATEGORY),
                stringCell(row, ProductColumn.MESSAGE_KEYWORD),
                employeeSheetName,
                employeeSheetTab,
                stringCell(row, ProductColumn.MODEL),
                decimalCell(row, ProductColumn.PRODUCT_LENGTH_MM),
                decimalCell(row, ProductColumn.PRODUCT_WIDTH_MM),
                decimalCell(row, ProductColumn.PRODUCT_HEIGHT_MM),
                decimalCell(row, ProductColumn.PRODUCT_WEIGHT_G),
                stringCell(row, ProductColumn.EAN),
                stringCell(row, ProductColumn.BRAND),
                integerCell(row, ProductColumn.WARRANTY_MONTHS),
                decimalCell(row, ProductColumn.IMPORT_TAX),
                stringCell(row, ProductColumn.SUPPLIER_PRODUCT_CODE),
                decimalCell(row, ProductColumn.AIR_TRANSPORT_PCS_PER_CARTON),
                decimalCell(row, ProductColumn.AIR_TRANSPORT_KG_PER_CARTON),
                decimalCell(row, ProductColumn.AIR_TRANSPORT_LENGTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.AIR_TRANSPORT_WIDTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.AIR_TRANSPORT_HEIGHT_CM_PER_CARTON),
                decimalCell(row, ProductColumn.AIR_TRANSPORT_VOLUME_M3_PER_CARTON),
                decimalCell(row, ProductColumn.RAIL_TRANSPORT_PCS_PER_CARTON),
                decimalCell(row, ProductColumn.RAIL_TRANSPORT_KG_PER_CARTON),
                decimalCell(row, ProductColumn.RAIL_TRANSPORT_LENGTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.RAIL_TRANSPORT_WIDTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.RAIL_TRANSPORT_HEIGHT_CM_PER_CARTON),
                decimalCell(row, ProductColumn.RAIL_TRANSPORT_VOLUME_M3_PER_CARTON),
                decimalCell(row, ProductColumn.SEA_TRANSPORT_PCS_PER_CARTON),
                decimalCell(row, ProductColumn.SEA_TRANSPORT_KG_PER_CARTON),
                decimalCell(row, ProductColumn.SEA_TRANSPORT_LENGTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.SEA_TRANSPORT_WIDTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.SEA_TRANSPORT_HEIGHT_CM_PER_CARTON),
                decimalCell(row, ProductColumn.SEA_TRANSPORT_VOLUME_M3_PER_CARTON),
                decimalCell(row, ProductColumn.TRUCK_TRANSPORT_PCS_PER_CARTON),
                decimalCell(row, ProductColumn.TRUCK_TRANSPORT_KG_PER_CARTON),
                decimalCell(row, ProductColumn.TRUCK_TRANSPORT_LENGTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.TRUCK_TRANSPORT_WIDTH_CM_PER_CARTON),
                decimalCell(row, ProductColumn.TRUCK_TRANSPORT_HEIGHT_CM_PER_CARTON),
                decimalCell(row, ProductColumn.TRUCK_TRANSPORT_VOLUME_M3_PER_CARTON),
                stringCell(row, ProductColumn.EMAG_LINK),
                stringCell(row, ProductColumn.EMAG_TITLE),
                stringCell(row, ProductColumn.INCOME_PROFIT_TAX),
                booleanCell(row, ProductColumn.VAT_PAYER),
                decimalCell(row, ProductColumn.EMAG_SALE_PRICE_RON),
                decimalCell(row, ProductColumn.EMAG_COMMISSION),
                longCell(row, ProductColumn.OFFER_ID_CONCEPT),
                longCell(row, ProductColumn.OFFER_ID_SOLUTIONS),
                longCell(row, ProductColumn.OFFER_ID_SOLUTIONS_FBE),
                longCell(row, ProductColumn.OFFER_ID_JUDIOS_CONCEPT),
                longCell(row, ProductColumn.OFFER_ID_JUDIOS_CONCEPT_FBE),
                longCell(row, ProductColumn.OFFER_ID_JUDY_CREATIVE_STUDIOS_FBE),
                longCell(row, ProductColumn.OFFER_ID_SELLFUSION),
                longCell(row, ProductColumn.OFFER_ID_SELLFUSION_FBE),
                longCell(row, ProductColumn.OFFER_ID_KOPPEL),
                longCell(row, ProductColumn.OFFER_ID_KOPPEL_FBE),
                stringCell(row, ProductColumn.INDEX_CATEGORY),
                stringCell(row, ProductColumn.DIVISION),
                stringCell(row, ProductColumn.SUPRACATEGORY),
                stringCell(row, ProductColumn.CATEGORY_NAME),
                stringCell(row, ProductColumn.SUBCATEGORY),
                stringCell(row, ProductColumn.SUBSUBCATEGORY),
                stringCell(row, ProductColumn.SUPRACATEGORY_COUNTRY),
                stringCell(row, ProductColumn.CATEGORY_COUNTRY),
                integerCell(row, ProductColumn.CATEGORY_ID),
                integerCell(row, ProductColumn.SCM_ID),
                integerCell(row, ProductColumn.DOC_ID),
                stringCell(row, ProductColumn.INDEXED_SUBCATEGORY_COUNTRY),
                stringCell(row, ProductColumn.BIG_CATEGORY),
                longCell(row, ProductColumn.EMAG_ADS_AUTO_ID),
                longCell(row, ProductColumn.EMAG_ADS_MANUAL_ID),
                stringCell(row, ProductColumn.GENDER),
                stringCell(row, ProductColumn.MANUAL_VIDEO_LINK),
                stringCell(row, ProductColumn.USAGE_GUIDE_LINK),
                stringCell(row, ProductColumn.USAGE_SITE_LINK),
                stringCell(row, ProductColumn.USAGE_MANUAL_LINK),
                stringCell(row, ProductColumn.OTHER_COMMENTS),
                stringCell(row, ProductColumn.REVIEW_CALLER),
                stringCell(row, ProductColumn.REPORT_LINK)
        );
    }

    private static String stringCell(List<Object> row, ProductColumn column) {
        var value = rawCell(row, column);
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string.isBlank() ? null : string;
        }
        return value.toString();
    }

    private static BigDecimal decimalCell(List<Object> row, ProductColumn column) {
        var value = rawCell(row, column);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String string && !string.isBlank()) {
            return new BigDecimal(string);
        }
        return null;
    }

    private static Integer integerCell(List<Object> row, ProductColumn column) {
        var value = rawCell(row, column);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        return null;
    }

    private static Long longCell(List<Object> row, ProductColumn column) {
        var value = rawCell(row, column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Long.parseLong(string);
        }
        return null;
    }

    private static Boolean booleanCell(List<Object> row, ProductColumn column) {
        var value = rawCell(row, column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && !string.isBlank()) {
            return Boolean.parseBoolean(string);
        }
        return null;
    }

    private static Object rawCell(List<Object> row, ProductColumn column) {
        if (row.size() <= column.ordinal()) {
            return null;
        }
        var value = row.get(column.ordinal());
        if (value instanceof Integer integer && integer == 0) {
            return null;
        }
        return value;
    }

    static void main(String[] args) throws SQLException, IOException {
        updateProductTable(EmagMirrorDB.getEmagMirrorDB(new Arguments(args).getOption(databaseOptionName, defaultDatabase)));
    }
}
