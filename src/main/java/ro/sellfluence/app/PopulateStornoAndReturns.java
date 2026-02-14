package ro.sellfluence.app;

import org.jspecify.annotations.NonNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.support.UsefulMethods.findColumnMatchingMonth;
import static ro.sellfluence.support.UsefulMethods.toColumnName;

public class PopulateStornoAndReturns {
    private static final Logger logger = Logs.getConsoleLogger("PopulateStornoAndReturns", INFO);
    private static final String spreadSheetName = "Cent. - Ret. Sto. Ref. Inl.";
    private static final String stornoSheetName = "(GLB) Sto./M.";
    private static final String returnsSheetName = "(GLB) Ret./M.";
    private static final String percentStornoSheetName = "(GLB) Prod. Sto./M. (%)";
    private static final String percentReturnSheetName = "(GLB) Prod. Ret./M. (%)";

    private static final int monthRow = 2;
    private static final int firstDataRow = 8;

    static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        updateSpreadsheets(mirrorDB);
    }

    public static void updateSpreadsheets(EmagMirrorDB mirrorDB) throws SQLException {
        var sheet = SheetsAPI.getSpreadSheetByName(defaultGoogleApp, spreadSheetName);
        if (sheet == null) {
            throw new RuntimeException("Could not find the spreadsheet %s.".formatted(spreadSheetName));
        }
        var vendors = mirrorDB.readVendorCompanies();
        var products = mirrorDB.readProducts().stream().sorted(ProductTable.ProductInfo.nameComparator).toList();
        YearMonth month = YearMonth.now();
        updateProductColumns(sheet, stornoSheetName, products, vendors);
        updateProductColumns(sheet, returnsSheetName, products, vendors);
        updateProductColumns(sheet, percentStornoSheetName, products, vendors);
        updateProductColumns(sheet, percentReturnSheetName, products, vendors);
        while (month.getYear() >= 2024) {
            Map<String, Integer> orderByPNK = mirrorDB.countOrdersByMonth(month);
            Map<String, Integer> stornoByPNK = mirrorDB.countStornoByMonth(month);
            Map<String, Integer> returnByPNK = mirrorDB.countReturnByMonth(month);
            Map<String, Double> percentStornoByPNK = computePercent(stornoByPNK, orderByPNK);
            Map<String, Double> percentReturnByPNK = computePercent(returnByPNK, orderByPNK);
            logger.log(INFO, "--- Update Percentage Storno for month %s ------------------------".formatted(month));
            updateSheet(sheet, percentStornoSheetName, month, products,percentStornoByPNK);
            logger.log(INFO, "--- Update Percentage Returns for month %s ------------------------".formatted(month));
            updateSheet(sheet, percentReturnSheetName, month, products, percentReturnByPNK);
            logger.log(INFO, "--- Update Storno for month %s --------------------------".formatted(month));
            updateSheet(sheet, stornoSheetName, month, products, stornoByPNK);
            logger.log(INFO, "--- Update Returns for month %s ------------------------".formatted(month));
            updateSheet(sheet, returnsSheetName, month, products, returnByPNK);
            month = month.minusMonths(1);
        }
    }

    private static Map<String, Double> computePercent(Map<String, Integer> partByPNK, Map<String, Integer> totalByPNK) {
        var result = new HashMap<String, Double>();
        for (var entry : totalByPNK.entrySet()) {
            var pnk = entry.getKey();
            var total = entry.getValue();
            var part = (double)partByPNK.getOrDefault(pnk, 0);
            result.put(pnk, total == 0 ? 0.0 : part / total);
        }
        return result;
    }

    private static void updateProductColumns(SheetsAPI sheet, String sheetName, List<ProductTable.ProductInfo> products, Map<UUID, String> vendors) {
        int lineCount = 0;
        var rows = new ArrayList<List<Object>>();
        for (var product : products) {
            lineCount++;
            var row = List.<Object>of(
                    lineCount,
                    product.name(),
                    nullToEmpty(vendors.get(product.vendor())),
                    nullToEmpty(product.pnk()),
                    nullToEmpty(product.category())
            );
            rows.add(row);
        }
        sheet.updateRange("'%s'!%s%d:%s%d".formatted(sheetName, "A", firstDataRow, "E", firstDataRow + rows.size() - 1), rows);
    }

    private static <T> void updateSheet(SheetsAPI sheet, final String sheetName, YearMonth month, @NonNull List<ProductTable.ProductInfo> products, @NonNull final Map<String, T> valuesByPNK) {
        var columnIdentifier = toColumnName((int)YearMonth.of(2023,7).until(month, ChronoUnit.MONTHS));
        var columnData = new ArrayList<T>();
        for (var product : products) {
            var pnk = product.pnk();
            var count = valuesByPNK.get(pnk);
            // Detect first valid row.
            columnData.add(count);
        }
        updateSheetColumn(sheet, sheetName, columnData, columnIdentifier);
    }

    private static <T> void updateSheetColumn(SheetsAPI sheet, String sheetName, ArrayList<T> columnData, String columnIdentifier) {
        var values = columnData.stream().map(it -> {
            var o = it != null ? (Object) it : (Object) "";
            return List.of(o);
        }).toList();
        sheet.updateRange(
                "'%s'!%s%d:%s%d".formatted(sheetName, columnIdentifier, firstDataRow, columnIdentifier, firstDataRow + columnData.size() - 1),
                values
        );
    }
}