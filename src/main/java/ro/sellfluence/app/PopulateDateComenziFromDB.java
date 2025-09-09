package ro.sellfluence.app;

import ro.sellfluence.apphelper.Vendor;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.sheetSupport.Conversions.isEMAGFbe;
import static ro.sellfluence.support.UsefulMethods.findColumnMatchingMonth;

/**
 * Read orders from our database mirror and put them in a sheet.
 */
public class PopulateDateComenziFromDB {

    private static final Logger logger = Logs.getConsoleLogger("PopulateDateComenziFromDB", INFO);
    private static final int year = 2025;

    /**
     * Target spreadsheet.
     */
    private static final String spreadSheetName = year + " - Date comenzi";

    /**
     * Target sheet for the orders.
     */
    private static final String dateSheetName = "Date";

    /**
     * Target sheet for the GMVs.
     */
    private static final String gmvSheetName = "T. GMW/M.";

    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        updateSpreadsheets(mirrorDB);
    }

    public static void updateSpreadsheets(EmagMirrorDB mirrorDB) throws SQLException {
        var sheet = SheetsAPI.getSpreadSheetByName(defaultGoogleApp, spreadSheetName);
        if (sheet == null) {
            throw new RuntimeException("Could not find the spreadsheet %s.".formatted(spreadSheetName));
        }
        logger.log(INFO, "--- Update GMVs --------------------------");
        updateGMVs(mirrorDB, sheet);
        logger.log(INFO, "--- Update orders ------------------------");
        updateOrders(mirrorDB, sheet);
    }

    /**
     * Transfer the GMV values from the database to the spreadsheet.
     *
     * @param mirrorDB source database.
     * @param sheet target sheet.
     * @throws SQLException if something goes wrong.
     */
    private static void updateGMVs(EmagMirrorDB mirrorDB, SheetsAPI sheet) throws SQLException {
        var month = YearMonth.from(LocalDate.now());
        while (month.getYear() == year) {
            updateGMVForMonth(mirrorDB, sheet, month);
            month = month.minusMonths(1);
        }
    }

    /**
     * Read for the specific month the data from the gmv table and update the spreadsheet.
     *
     * @param mirrorDB database.
     * @param sheet sheet to update.
     * @param month month to transfer.
     * @throws SQLException on database errors.
     */
    private static void updateGMVForMonth(EmagMirrorDB mirrorDB, SheetsAPI sheet, YearMonth month) throws SQLException {
        logger.log(INFO, "Transfer %s from the database to the sheet.".formatted( month));
        var gmvsByProduct = mirrorDB.readGMVByMonth(month);
        var products = mirrorDB.readProducts().stream()
                .collect(Collectors.groupingBy(ProductInfo::name));
        var productsInSheet = sheet.getColumn(gmvSheetName, "B").stream().toList();
        var monthsInSheet = sheet.getRowAsDates(gmvSheetName, 2).stream().toList();
        var columnIdentifier = findColumnMatchingMonth(monthsInSheet, month);
        Integer startRow = null;
        var rowNumber = 0;
        var gmvColumn = new ArrayList<BigDecimal>();
        for (String productName : productsInSheet) {
            rowNumber++;
            var productInfos = products.get(productName);
            ProductInfo productInfo;
            if (productInfos != null && productInfos.size() > 1) {
                throw new RuntimeException("More than one product matches " + productName + ". I cannot properly associate it with either of " + productInfos);
            }
            if (productInfos == null || productInfos.isEmpty()) {
                logger.log(WARNING, "No entry found for the product " + productName + ". Until you update the table, no GMV is computed for this product.");
                gmvColumn.add(null);
            } else {
                productInfo = productInfos.getFirst();

                // Detect the first row with a valid product.
                if (startRow == null && productInfo != null) {
                    startRow = rowNumber;
                }
                var gmv = gmvsByProduct.remove(productName);
                var retracted = productInfo != null && productInfo.retracted();
                var continueToSell = productInfo != null && productInfo.continueToSell();
                if (continueToSell && retracted) {
                    logger.log(
                            WARNING,
                            "Product %s (%s) has both 'continue to sell' and 'retracted' set, which doesn't make sense. Dropping retracted."
                                    .formatted(productName, productInfo.pnk())
                    );
                    retracted = false;
                }
                if (gmv == null) {
                    if (!retracted) {
                        logger.log(continueToSell ? WARNING : INFO, "No GMV for the product %s and the month %s.".formatted(productName, month));
                    }
                }
                if (startRow != null) {
                    gmvColumn.add(gmv);
                }
            }
        }
        if (startRow != null) {
            sheet.updateSheetColumnFromRow(gmvSheetName, columnIdentifier, startRow, gmvColumn);
        } else {
            logger.log(WARNING, "Could not add GMV because no products are found in the sheet.");
        }
        if (!gmvsByProduct.isEmpty()) {
            logger.log(WARNING, "Could not add products %s because lines for them are missing in the sheet.".formatted(gmvsByProduct.keySet()));
        }
    }

    /**
     * Transfer new orders from the database to the spreadsheet.
     *
     * @param mirrorDB source database.
     * @param sheet target sheet.
     * @throws SQLException if something goes wrong.
     */
    private static void updateOrders(EmagMirrorDB mirrorDB, SheetsAPI sheet) throws SQLException {
        var spreadSheetId = sheet.getSpreadSheetId();
        logger.log(INFO, "Read from the database.");
        var rows = mirrorDB.readForSheet(year);
        logger.log(INFO, "Read from the spreadsheet.");
        List<List<Object>> sheetData = sheet.getMultipleColumns(dateSheetName, "A", "B", "F", "X", "Y");
        //TODO: The filter does not notice changed orders.
        logger.log(INFO, "Filter out the orders that are already in the spreadsheet.");
        rows = filterOutExisting(rows, sheetData);
        if (rows.isEmpty()) {
            logger.log(INFO, "No new orders were found.");
        } else {
            logger.log(INFO, "Adding %d new orders to the sheet.".formatted(rows.size()));
            var lastRowNumber = sheetData.size();
            var nextRow = lastRowNumber + 1;
            var lastRow = lastRowNumber + rows.size();
            logger.log(INFO, "Now fixing cell format");
            sheet.formatDate(spreadSheetId, 0, 1, lastRowNumber, lastRow);
            sheet.formatAsCheckboxes(spreadSheetId, 27, 31, lastRowNumber, lastRow);
            logger.log(INFO, "Now adding the rows");
            sheet.updateRanges(rows, "%s!A%d".formatted(dateSheetName, nextRow), "%s!Y%d".formatted(dateSheetName, nextRow), "%s!AB%d".formatted(dateSheetName, nextRow), "%s!AG%d".formatted(dateSheetName, nextRow));
        }
    }

    /**
     * Check with what we already have in the database and reduce the entries retrieved from the database to
     * a shorter list, which does not include orders already in the spreadsheet.
     *
     * @param groupedRowsFromDB input from the database.
     * @param sheetData orders from the spreadsheet.
     * @return the reduced list without the orders already found in the spreadsheet.
     */
    private static List<List<List<Object>>> filterOutExisting(List<List<List<Object>>> groupedRowsFromDB, List<List<Object>> sheetData) {
        var sheetOrders = simplify(sheetData);
        //   var lastDateTime = toLocalDateTime((String) sheetData.getLast().getFirst());
        return groupedRowsFromDB.stream()
                .filter(groupedRow -> {
                    var order_id = (String) groupedRow.get(0).get(1);
                    var vendor = Vendor.fromSheet((String) groupedRow.get(1).get(0), isEMAGFbe((String) groupedRow.get(1).get(1)));
                    var productName = (String) groupedRow.get(0).get(5);
                    if (productName == null || productName.isBlank()) {
                        throw new RuntimeException("Could not find the product name for order %s (%s).".formatted(order_id, vendor.name()));
                    }
                    var orderLine = new OrderLine(order_id, /*vendor,*/ productName);
                    return !sheetOrders.contains(orderLine);
                }).toList();
    }

    /**
     * Helper record, which contains only those elements of an order necessary to find duplicates.
     *
     * @param orderId ID of the order.
     * //@param vendor the vendor, to distinguish orders with the same ID but different vendors.
     * @param productName name of the product
     */
    record OrderLine(String orderId, /*Vendor vendor,*/ String productName) {
    }

    /**
     * Extract from the spreadsheet lines just the order and product information
     * and store it in a set.
     *
     * @param sheetData cells as read from the spreadsheet.
     * @return set of {@link OrderLine}
     */
    private static Set<OrderLine> simplify(List<List<Object>> sheetData) {
        return sheetData.stream()
                .skip(3)
                .map(row -> new OrderLine(row.get(1).toString(), (String) row.get(2))).collect(Collectors.toSet());
    }
}