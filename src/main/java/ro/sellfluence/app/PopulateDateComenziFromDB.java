package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.sheetSupport.Conversions;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ro.sellfluence.sheetSupport.Conversions.isEMAGFbe;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

/**
 * Read orders from our database mirror and put them in a sheet.
 */
public class PopulateDateComenziFromDB {

    private static final String appName = "sellfluence1";
    private static final String spreadSheetName = "Testing Coding 2024 - Date comenzi";
    private static final String sheetName = "Date";

    public static void main(String[] args) throws SQLException, IOException {
        System.out.println("Update product table");
        PopulateProductsTableFromSheets.updateProductTable();
        var drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var sheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        System.out.println("Read from database");
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        System.out.println("Read from spreadsheet");
        var rows = mirrorDB.readForSheet();
        List<List<Object>> sheetData = sheet.getMultipleColumns(sheetName, "A", "B", "F", "X", "Y");
        rows = filterOutExisting(rows, sheetData);
        var lastRowNumber = sheetData.size();
        var nextRow = lastRowNumber + 1;
        var lastRow = lastRowNumber + rows.size();
        System.out.println("Now fixing cell format");
        sheet.formatAsCheckboxes(spreadSheetId, 26, 30, lastRowNumber, lastRow);
        System.out.println("Now adding the rows");
        sheet.updateRanges(rows, "%s!A%d".formatted(sheetName, nextRow), "%s!F%d".formatted(sheetName, nextRow), "%s!M%d".formatted(sheetName, nextRow), "%s!R%d".formatted(sheetName, nextRow), "%s!U%d".formatted(sheetName, nextRow), "%s!X%d".formatted(sheetName, nextRow), "%s!AA%d".formatted(sheetName, nextRow), "%s!AF%d".formatted(sheetName, nextRow));
    }

    private static List<List<List<Object>>> filterOutExisting(List<List<List<Object>>> groupedRowsFromDB, List<List<Object>> sheetData) {
        var sheetOrders = simplify(sheetData);
        var lastDateTime = toLocalDateTime((String) sheetData.getLast().getFirst());
        return groupedRowsFromDB.stream()
                .filter(groupedRow -> {
                    var date = toLocalDateTime((String) groupedRow.get(0).get(0));
                    return date.isAfter(lastDateTime) && Conversions.statusFromString((String) groupedRow.get(0).get(2)) == 4;
                })
                .filter(groupedRow -> {
            var order_id = (String) groupedRow.get(0).get(1);
            var vendor = Vendor.fromSheet((String) groupedRow.get(5).get(0), isEMAGFbe((String) groupedRow.get(5).get(1)));
            var productName = (String) groupedRow.get(1).getFirst();
            if (productName == null || productName.isBlank()) {
                throw new RuntimeException("Could not find product name for order %s (%s)".formatted(order_id, vendor.name()));
            }
            var orderLine = new OrderLine(order_id, vendor, productName);
            return  !sheetOrders.contains(orderLine);
        }).toList();
    }

    record OrderLine(String orderId, Vendor vendor, String productName) {
    }

    private static Set<OrderLine> simplify(List<List<Object>> sheetData) {
        return sheetData.stream().skip(2).map(row -> {
            String vendorName = (String) row.get(3);
            Vendor vendor = Vendor.fromSheet(vendorName, isEMAGFbe((String) row.get(4)));
            return new OrderLine((String) row.get(1), vendor, (String) row.get(2));
        }).collect(Collectors.toSet());
    }
}
