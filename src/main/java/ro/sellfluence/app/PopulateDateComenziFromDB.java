package ro.sellfluence.app;

import ro.sellfluence.apphelper.PopulateProductsTableFromSheets;
import ro.sellfluence.apphelper.Vendor;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmagMirrorDB.ProductWithID;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.sheetSupport.Conversions.isEMAGFbe;

/**
 * Read orders from our database mirror and put them in a sheet.
 */
public class PopulateDateComenziFromDB {

    private static final Logger logger = java.util.logging.Logger.getLogger(PopulateDateComenziFromDB.class.getName());
    private static final String appName = "sellfluence1";
    private static final int year = 2025;

    /**
     * Target spreadsheet.
     */
    private static final String spreadSheetName = "Testing Coding " + year + " - Date comenzi";

    /**
     * Target sheet for the orders.
     */
    private static final String dateSheetName = "Date";

    /**
     * Target sheet for the GMVs.
     */
    private static final String gmvSheetName = "T. GMW/M.";

    public static void main(String[] args) throws SQLException, IOException {
        System.out.println("Update product table");
        PopulateProductsTableFromSheets.updateProductTable();
        var drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var sheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        System.out.println("--- Update GMVs --------------------------");
        updateGMVs(mirrorDB, sheet);
        System.out.println("--- Update orders ------------------------");
        updateOrders(mirrorDB, sheet, spreadSheetId);
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
        updateGMVForMonth(mirrorDB, sheet, month);
        updateGMVForMonth(mirrorDB, sheet, month.minusMonths(1));
    }

    private static void updateGMVForMonth(EmagMirrorDB mirrorDB, SheetsAPI sheet, YearMonth month) throws SQLException {
        System.out.printf("Read %s from the database", month);
        var gmvs = mirrorDB.readGMVByMonth(month);
        System.out.println("Read from the spreadsheet");
        var products = mirrorDB.readProducts().stream()
                .collect(Collectors.toMap(it -> it.product().name(), ProductWithID::product));
        var productsInSheet = sheet.getColumn(gmvSheetName, "B");
        var monthsInSheet = sheet.getRowAsDates(gmvSheetName, 2);
        String columnIdentifier = null;
        var columnNumber = 1;
        for (Object it : monthsInSheet) {
            if (it instanceof BigDecimal dateSerial) {
                LocalDate localDate = fromExcelSerialBigDecimal(dateSerial);
                if (YearMonth.from(localDate).equals(month)) {
                    columnIdentifier = toColumnName(columnNumber);
                }
            }
            columnNumber++;
        }
        var startRow = 8;
        var rowNumber = -startRow;
        int productCount = productsInSheet.size() - 8;
        var gmvColumn = new BigDecimal[productCount];
        for (String productName : productsInSheet) {
            rowNumber++;
            if (rowNumber < 0) continue;
            var gmv = gmvs.remove(productName);
            var productInfo = products.get(productName);
            var retracted = productInfo != null && productInfo.retracted();
            var continueToSell = productInfo != null && productInfo.continueToSell();
            if (gmv == null) {
                if (!retracted) {
                    logger.log(continueToSell ? WARNING : INFO, "No GMV for the product %s and the month %s".formatted(productName, month));
                }
            } else {
                gmvColumn[rowNumber] = gmv;
            }
        }
        sheet.updateRange(
                "'%s'!%s%d:%s%d".formatted(gmvSheetName, columnIdentifier, startRow, columnIdentifier, startRow + productCount - 1),
                Arrays.stream(gmvColumn).map(it -> {
                    var o = it != null ? (Object) it : (Object) "";
                    return List.of(o);
                }).toList()
        );
        if (!gmvs.isEmpty()) {
            logger.log(WARNING, "Could not add products %s because lines for them are missing in the sheet.".formatted(gmvs.keySet()));
        }
    }

    /**
     * Transfer new orders from the database to the spreadsheet.
     *
     * @param mirrorDB source database.
     * @param sheet target sheet.
     * @param spreadSheetId id of the spreadsheet.
     * @throws SQLException if something goes wrong.
     */
    private static void updateOrders(EmagMirrorDB mirrorDB, SheetsAPI sheet, String spreadSheetId) throws SQLException {
        System.out.println("Read from the database");
        var rows = mirrorDB.readForSheet(year);
        System.out.println("Read from the spreadsheet");
        List<List<Object>> sheetData = sheet.getMultipleColumns(dateSheetName, "A", "B", "F", "X", "Y");
        //TODO: The filter does not notice changed orders.
        rows = filterOutExisting(rows, sheetData);
        if (!rows.isEmpty()) {
            var lastRowNumber = sheetData.size();
            var nextRow = lastRowNumber + 1;
            var lastRow = lastRowNumber + rows.size();
            System.out.println("Now fixing cell format");
            sheet.formatDate(spreadSheetId, 0, 1, lastRowNumber, lastRow);
            sheet.formatAsCheckboxes(spreadSheetId, 27, 31, lastRowNumber, lastRow);
            System.out.println("Now adding the rows");
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
                throw new RuntimeException("Could not find the product name for order %s (%s)".formatted(order_id, vendor.name()));
            }
            var orderLine = new OrderLine(order_id, /*vendor,*/ productName);
            return  !sheetOrders.contains(orderLine);
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
     * @return set of {@link  }
     */
    private static Set<OrderLine> simplify(List<List<Object>> sheetData) {
        return sheetData.stream()
                .skip(3)
                .map(row -> new OrderLine(row.get(1).toString(), (String) row.get(2))).collect(Collectors.toSet());
    }

    // Helper function to convert a column number to its corresponding letters.
    public static String toColumnName(int columnNumber) {
        StringBuilder columnName = new StringBuilder();

        while (columnNumber > 0) {
            int modulo = (columnNumber - 1) % 26;
            columnName.insert(0, (char) ('A' + modulo));
            columnNumber = (columnNumber - modulo - 1) / 26;
        }

        return columnName.toString();
    }

    /**
     * Reference date of Google Sheets serial numbers.
     */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /**
     * Convert a Google Sheets serial number to a LocalDate.
     *
     * @param serial serial number as read from the spreadsheet.
     * @return LocalDate.
     */
    public static LocalDate fromExcelSerialBigDecimal(BigDecimal serial) {
        if (serial == null) {
            return null;
        }
        long serialDays = serial.longValue();
        return EXCEL_EPOCH.plusDays(serialDays);
    }

}