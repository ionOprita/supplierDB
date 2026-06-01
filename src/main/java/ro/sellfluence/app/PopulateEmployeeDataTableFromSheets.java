package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmployeeDataTable.EmployeeColumn;
import ro.sellfluence.db.EmployeeDataTable.EmployeeInfo;
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
import static ro.sellfluence.db.EmployeeDataTable.SOURCE_COLUMN_COUNT;
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheetByName;

/**
 * Copies the employee data from the "Date Angajati" sheet into the database.
 */
public class PopulateEmployeeDataTableFromSheets {

    private static final Logger infos = Logs.getConsoleAndFileLogger("populateEmployeeDataTableInfos", INFO, 10, 1_000_000);

    private static final String employeeSpreadsheetName = "2025 - Date produse & angajati";
    private static final String employeeSheetName = "Date Angajati";
    private static final String lastSourceColumn = "EJ";

    public static int updateEmployeeDataTable(EmagMirrorDB mirrorDB) throws SQLException {
        Objects.requireNonNull(mirrorDB);
        var sheet = getSpreadSheetByName(defaultGoogleApp, employeeSpreadsheetName);
        if (sheet == null) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(employeeSpreadsheetName));
        }
        var employees = populateFrom(sheet, employeeSheetName);
        var inserted = mirrorDB.replaceEmployeeData(employees);
        infos.log(INFO, () -> "Replaced employee_sheet_data with %d rows from %s / %s.".formatted(
                inserted,
                employeeSpreadsheetName,
                employeeSheetName
        ));
        return inserted;
    }

    static List<EmployeeInfo> populateFrom(SheetsAPI spreadSheet, String sheetName) {
        Objects.requireNonNull(spreadSheet);
        Objects.requireNonNull(sheetName);
        return toEmployeeInfos(spreadSheet.getRowsInColumnRange(sheetName, "A", lastSourceColumn));
    }

    static List<EmployeeInfo> toEmployeeInfos(List<List<String>> rows) {
        Objects.requireNonNull(rows);
        var employees = new ArrayList<EmployeeInfo>();
        for (int i = 2; i < rows.size(); i++) {
            var sourceValues = normalizeRow(rows.get(i));
            if (value(sourceValues, EmployeeColumn.FULL_NAME) == null) {
                continue;
            }
            employees.add(new EmployeeInfo(i + 1, sourceValues));
        }
        return employees;
    }

    private static List<String> normalizeRow(List<String> row) {
        var normalized = new ArrayList<String>(SOURCE_COLUMN_COUNT);
        for (int i = 0; i < SOURCE_COLUMN_COUNT; i++) {
            normalized.add(i < row.size() ? cleanCell(row.get(i)) : null);
        }
        return normalized;
    }

    private static String value(List<String> row, EmployeeColumn column) {
        return row.get(column.sheetIndex() - 1);
    }

    private static String cleanCell(String value) {
        if (value == null) {
            return null;
        }
        var cleaned = value.replace("\r", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    static void main(String[] args) throws SQLException, IOException {
        updateEmployeeDataTable(EmagMirrorDB.getEmagMirrorDB(new Arguments(args).getOption(databaseOptionName, defaultDatabase)));
    }
}
