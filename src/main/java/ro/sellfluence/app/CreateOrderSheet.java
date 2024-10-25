package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Read orders from our database mirror and put them in a sheet.
 */
public class CreateOrderSheet {

    private static final String appName = "sellfluence1";
    private static final String spreadSheetName = "Testing Coding 2024 - Date comenzi";
    private static final String sheetName = "Date";

    public static void main(String[] args) throws SQLException, IOException {
        var drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var sheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        var rows = mirrorDB.readForSheet();
        List<String> firstColumn = sheet.getColumn(sheetName, "A");
        var lastRowNumber = firstColumn.size();
        var nextRow = lastRowNumber + 1;
        var lastRow = lastRowNumber + rows.size();
        System.out.println(System.getProperty("java.io.tmpdir"));
        System.out.println("Now fixing cell format");
        sheet.formatAsCheckboxes(spreadSheetId, 26, 30, lastRowNumber, lastRow);
        System.out.println("Now adding the rows");
        sheet.updateRanges(rows,
                "%s!A%d".formatted(sheetName, nextRow),
                "%s!F%d".formatted(sheetName, nextRow),
                "%s!M%d".formatted(sheetName, nextRow),
                "%s!R%d".formatted(sheetName, nextRow),
                "%s!U%d".formatted(sheetName, nextRow),
                "%s!X%d".formatted(sheetName, nextRow),
                "%s!AA%d".formatted(sheetName, nextRow),
                "%s!AF%d".formatted(sheetName, nextRow)
        );
    }
}
