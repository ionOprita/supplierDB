package ro.sellfluence.app;

import ro.sellfluence.apphelper.PopulateProductsTableFromSheets;
import ro.sellfluence.apphelper.Vendor;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ro.sellfluence.sheetSupport.Conversions.isEMAGFbe;

/**
 * Read orders from our database mirror and put them in a sheet.
 */
public class PopulateDateComenziFromDB {

    private static final String appName = "sellfluence1";
    private static final int year = 2025;
    private static final String spreadSheetName = "Testing Coding " + year + " - Date comenzi";

    private static final String sheetName = "Date";

    public static void main(String[] args) throws SQLException, IOException {
        System.out.println("Update product table");
        PopulateProductsTableFromSheets.updateProductTable();
        var drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var sheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        System.out.println("Read from the database");
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        var rows = mirrorDB.readForSheet(year);
        System.out.println("Read from the spreadsheet");
        List<List<Object>> sheetData = sheet.getMultipleColumns(sheetName, "A", "B", "F", "X", "Y");
        //TODO: The filter does not notice changed orders.
        rows = filterOutExisting(rows, sheetData).subList(0, 19); // TODO: For test only limit to 20 rows.
        var lastRowNumber = sheetData.size();
        var nextRow = lastRowNumber + 1;
        var lastRow = lastRowNumber + rows.size();
        System.out.println("Now fixing cell format");
        sheet.formatDate(spreadSheetId, 0, 1, lastRowNumber, lastRow);
        sheet.formatAsCheckboxes(spreadSheetId, 27, 31, lastRowNumber, lastRow);
        System.out.println("Now adding the rows");
        sheet.updateRanges(rows, "%s!A%d".formatted(sheetName, nextRow), "%s!Y%d".formatted(sheetName, nextRow), "%s!AB%d".formatted(sheetName, nextRow), "%s!AG%d".formatted(sheetName, nextRow));
    }

    /**
     * Check with what we already have in the database and reduce the entries retrieved from the database to
     * a shorter list, which does not include orders already in the spreadsheet.
     *
     * @param groupedRowsFromDB input from the database.
     * @param sheetData orders from the spreadsheet.
     * @return reduced list without the orders already found in the spreadsheet.
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
                throw new RuntimeException("Could not find product name for order %s (%s)".formatted(order_id, vendor.name()));
            }
            var orderLine = new OrderLine(order_id, /*vendor,*/ productName);
            return  !sheetOrders.contains(orderLine);
        }).toList();
    }

    /**
     * Helper record, which contains only those elements of an order necessary to find duplicates.
     *
     * @param orderId ID of the order.
     * //@param vendor the vendor, to distinguish orders with same ID but different vendors.
     * @param productName name of the product
     */
    record OrderLine(String orderId, /*Vendor vendor,*/ String productName) {
    }

    /**
     * Extract from the spreadsheet lines just the order and product information
     * and store it in a set.
     *
     * @param sheetData cells as read from spreadsheet.
     * @return set of {@link  }
     */
    private static Set<OrderLine> simplify(List<List<Object>> sheetData) {
        return sheetData.stream().skip(3).map(row -> {
            //String vendorName = (String) row.get(3);
            //Vendor vendor = Vendor.fromSheet(vendorName, isEMAGFbe((String) row.get(4)));
            return new OrderLine(row.get(1).toString(), /*vendor, */(String) row.get(2));
        }).collect(Collectors.toSet());
    }
}