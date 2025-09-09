package ro.sellfluence.app;

import org.jspecify.annotations.NonNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.support.UsefulMethods.findColumnMatchingMonth;

public class PopulateStornoAndReturns {
    private static final Logger logger = Logs.getConsoleLogger("PopulateStornoAndReturns", INFO);
    private static final String spreadSheetName = "Cent. - Ret. Sto. Ref. Inl.";
    private static final String stornoSheetName = "(GLB) Sto./M.";
    private static final String returnsSheetName = "(GLB) Ret./M.";

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
        YearMonth month = YearMonth.now();
        logger.log(INFO, "--- Update Stornos for month %s --------------------------".formatted(month));
        updateSheet(sheet, stornoSheetName, month, mirrorDB.countStornoByMonth(month));
        logger.log(INFO, "--- Update Returns for month %s ------------------------".formatted(month));
        updateSheet(sheet, returnsSheetName, month, mirrorDB.countReturnByMonth(month));
    }

    private static void updateSheet(SheetsAPI sheet, final String sheetName, YearMonth month, @NonNull final Map<String, Integer> valuesByPNK) {
        var pnksInSheet = sheet.getColumn(sheetName, "D");
        var monthsInSheet = sheet.getRowAsDates(sheetName, 2);
        var columnIdentifier = findColumnMatchingMonth(monthsInSheet, month);
        var columnData = new ArrayList<Integer>();
        var rowNumber = 0;
        Integer startRow = null;
        for (var pnk: pnksInSheet) {
            rowNumber++;
            var stornoCount = valuesByPNK.get(pnk);
            // Detect first valid row.
            if (startRow==null && stornoCount != null) {
                startRow = rowNumber;
            }
            if (startRow!=null) {
                columnData.add(stornoCount);
            }
        }
        if (startRow!=null) {
            updateSheetColumn(sheet, sheetName, columnData, startRow, columnIdentifier);
        }
    }

    private static void updateSheetColumn(SheetsAPI sheet, String sheetName, ArrayList<Integer> columnData, Integer startRow, String columnIdentifier) {
        var values = columnData.stream().skip(startRow - 1).map(it -> {
            var o = it != null ? (Object) it : (Object) "";
            return List.of(o);
        }).toList();
        sheet.updateRange(
                "'%s'!%s%d:%s%d".formatted(sheetName, columnIdentifier, startRow, columnIdentifier, startRow + columnData.size() - 1),
                values
        );
    }
}