package ro.sellfluence.googleapi;

import org.jspecify.annotations.Nullable;
import ro.sellfluence.googleapi.SheetsAPI.SheetGrid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;

/** Reads the Importuri register and its lookup tabs from the named workbook. */
public final class StockImportSheetReader {
    public record Range(int firstColumn, List<List<Object>> rows) {}
    public record Snapshot(List<Range> imports, List<List<Object>> products, List<List<Object>> cartons,
                           List<List<Object>> transit, List<List<Object>> vat) {}
    public record ResolvedSnapshot(String spreadsheetId, int tabId, Snapshot snapshot) {}

    @FunctionalInterface
    interface WorkbookLookup {
        @Nullable SheetsAPI open(String name) throws IOException;
    }

    private final WorkbookLookup workbooks;

    public StockImportSheetReader() {
        this(name -> SheetsAPI.getReadOnlySpreadSheetByName(defaultGoogleApp, name));
    }

    StockImportSheetReader(WorkbookLookup workbooks) {
        this.workbooks = workbooks;
    }

    public ResolvedSnapshot read(String spreadsheetName) throws IOException {
        if (spreadsheetName == null || spreadsheetName.isBlank()) {
            throw new IllegalArgumentException("A workbook name is required.");
        }
        var workbook = workbooks.open(spreadsheetName);
        if (workbook == null) {
            throw new IllegalArgumentException("No visible workbook has the exact name: " + spreadsheetName
                    + ". Check the name and verify the service account has at least Viewer access.");
        }
        Map<String, SheetGrid> tabs = new HashMap<>();
        for (var tab : workbook.readSheetGrids()) {
            tabs.put(tab.title(), tab);
        }
        var imports = required(tabs, "Importuri", 88, 17);
        var products = required(tabs, "Date Prod.", 4, 13);
        var cartons = required(tabs, "Tabel Meas. CTN.", 5, 30);
        required(tabs, "Setari", 27, 3);
        required(tabs, "Cons. Date Secundare", 3, 5);
        // One batch captures effective values for formulas and inputs together. Read the full current
        // block width, including unnumbered templates, to detect partially entered shipments.
        List<String> ranges = new ArrayList<>();
        var starts = new ArrayList<Integer>();
        ranges.add("'Importuri'!A1:F88");
        starts.add(1);
        int lastColumn = imports.columns();
        for (int first = 7; first <= lastColumn; first += 440) {
            starts.add(first);
            ranges.add("'Importuri'!" + columnName(first) + "1:" + columnName(Math.min(first + 439, lastColumn)) + "88");
        }
        ranges.add("'Date Prod.'!A1:M" + products.rows());
        ranges.add("'Tabel Meas. CTN.'!A1:AD" + cartons.rows());
        ranges.add("'Setari'!B23:C27");
        ranges.add("'Cons. Date Secundare'!E2:E3");
        var grids = workbook.getRawValueRanges(ranges);
        var blocks = new ArrayList<Range>();
        for (int i = 0; i < starts.size(); i++) blocks.add(new Range(starts.get(i), grids.get(i)));
        int i = blocks.size();
        var snapshot = new Snapshot(blocks, grids.get(i), grids.get(i + 1), grids.get(i + 2), grids.get(i + 3));
        return new ResolvedSnapshot(workbook.getSpreadSheetId(), imports.sheetId(), snapshot);
    }

    private static SheetGrid required(Map<String, SheetGrid> tabs, String title, int rows, int cols) {
        var tab = tabs.get(title);
        if (tab == null || tab.rows() < rows || tab.columns() < cols) {
            throw new IllegalArgumentException("Missing or incomplete source tab: " + title);
        }
        return tab;
    }

    public static String columnName(int column) {
        var name = new StringBuilder();
        for (int n = column; n > 0; n = (n-1)/26) name.append((char) ('A' + (n-1)%26));
        return name.reverse().toString();
    }
}
