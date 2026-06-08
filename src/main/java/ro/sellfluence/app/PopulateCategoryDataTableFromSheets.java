package ro.sellfluence.app;

import ro.sellfluence.db.CategoryDataTable.CategoryColumn;
import ro.sellfluence.db.CategoryDataTable.CategoryInfo;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.db.CategoryDataTable.SOURCE_COLUMN_COUNT;
import static ro.sellfluence.db.CategoryDataTable.normalizeIntegerValue;
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheetByName;

/**
 * Copies the category data from the "Setari Cat." sheet into the database.
 */
public class PopulateCategoryDataTableFromSheets {

    private static final Logger infos = Logs.getConsoleAndFileLogger("populateCategoryDataTableInfos", INFO, 10, 1_000_000);

    private static final String categorySpreadsheetName = "2025 - Date produse & angajati";
    private static final String categorySheetName = "Setari Cat.";
    private static final String lastSourceColumn = "O";

    public static int updateCategoryDataTable(EmagMirrorDB mirrorDB) throws SQLException {
        Objects.requireNonNull(mirrorDB);
        var sheet = getSpreadSheetByName(defaultGoogleApp, categorySpreadsheetName);
        if (sheet == null) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(categorySpreadsheetName));
        }
        var categories = populateFrom(sheet, categorySheetName);
        var inserted = mirrorDB.replaceCategoryData(categories);
        infos.log(INFO, () -> "Replaced category_sheet_data with %d rows from %s / %s.".formatted(
                inserted,
                categorySpreadsheetName,
                categorySheetName
        ));
        return inserted;
    }

    static List<CategoryInfo> populateFrom(SheetsAPI spreadSheet, String sheetName) {
        Objects.requireNonNull(spreadSheet);
        Objects.requireNonNull(sheetName);
        return toCategoryInfos(spreadSheet.getRowsInColumnRange(sheetName, "A", lastSourceColumn));
    }

    static List<CategoryInfo> toCategoryInfos(List<List<String>> rows) {
        Objects.requireNonNull(rows);
        var categories = new ArrayList<CategoryInfo>();
        for (int i = 2; i < rows.size(); i++) {
            var sourceValues = normalizeRow(rows.get(i));
            if (value(sourceValues, CategoryColumn.SUBCATEGORY_COUNTRY) == null) {
                continue;
            }
            categories.add(new CategoryInfo(i + 1, sourceValues));
        }
        return categories;
    }

    private static List<String> normalizeRow(List<String> row) {
        var normalized = new ArrayList<String>(SOURCE_COLUMN_COUNT);
        for (int i = 0; i < SOURCE_COLUMN_COUNT; i++) {
            var column = CategoryColumn.fromSheetIndex(i + 1);
            normalized.add(i < row.size() ? cleanCell(row.get(i), column) : null);
        }
        return normalized;
    }

    private static String value(List<String> row, CategoryColumn column) {
        return row.get(column.sheetIndex() - 1);
    }

    private static String cleanCell(String value, CategoryColumn column) {
        if (value == null) {
            return null;
        }
        var cleaned = value.replace("\r", "").trim();
        if (cleaned.isBlank()) {
            return null;
        }
        return column.integerColumn() ? normalizeIntegerValue(cleaned, column.dbColumn()) : cleaned;
    }

    static void main(String[] args) throws SQLException, IOException {
        updateCategoryDataTable(EmagMirrorDB.getEmagMirrorDB(new Arguments(args).getOption(databaseOptionName, defaultDatabase)));
    }
}
