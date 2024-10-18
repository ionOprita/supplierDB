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
        var drive = new DriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var sheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        var rows = mirrorDB.readForSheet();
        List<String> firstColumn = sheet.getColumn(sheetName, "A");
        var lastRowNumber = firstColumn.size();
        System.out.println(System.getProperty("java.io.tmpdir"));
        sheet.updateRange(("%s!A%d:Z%d").formatted(sheetName, lastRowNumber + 1, lastRowNumber + rows.size()), rows);
    }
}
