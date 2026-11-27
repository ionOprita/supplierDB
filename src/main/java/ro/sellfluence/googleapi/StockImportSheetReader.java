package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.auth.http.HttpCredentialsAdapter;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;

/** A service-account client with only the spreadsheets.readonly OAuth scope. */
public final class StockImportSheetReader {
    public record Range(int firstColumn, List<List<Object>> rows) {}
    public record Snapshot(List<Range> imports, List<List<Object>> products, List<List<Object>> cartons,
                           List<List<Object>> transit, List<List<Object>> vat) {}

    private final Sheets sheets;

    public StockImportSheetReader() throws IOException, GeneralSecurityException {
        var credentials = Credentials.scopedCredentials(List.of(SheetsScopes.SPREADSHEETS_READONLY));
        var adapter = new HttpCredentialsAdapter(credentials);
        sheets = new Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(),
                request -> {
                    adapter.initialize(request);
                    request.setConnectTimeout(10_000);
                    request.setReadTimeout(100_000);
                }).setApplicationName(defaultGoogleApp).build();
    }

    public Snapshot read(String bookId, int tabId) throws IOException {
        var api = sheets.spreadsheets();
        var metadata = api.get(bookId).setFields("sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)))")
                .execute();
        Map<String,SheetProperties> tabs = new HashMap<>();
        for (var tab : metadata.getSheets()) tabs.put(tab.getProperties().getTitle(), tab.getProperties());
        var imports = required(tabs, "Importuri", 88, 17);
        if (imports.getSheetId() != tabId)
            throw new IllegalArgumentException("The selected gid does not identify the Importuri tab.");
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
        int lastColumn = imports.getGridProperties().getColumnCount();
        for (int first = 7; first <= lastColumn; first += 440) {
            starts.add(first);
            ranges.add("'Importuri'!" + columnName(first) + "1:" + columnName(Math.min(first+439,lastColumn)) + "88");
        }
        ranges.add("'Date Prod.'!A1:M" + products.getGridProperties().getRowCount());
        ranges.add("'Tabel Meas. CTN.'!A1:AD" + cartons.getGridProperties().getRowCount());
        ranges.add("'Setari'!B23:C27");
        ranges.add("'Cons. Date Secundare'!E2:E3");
        var response = api.values().batchGet(bookId).setRanges(ranges).setMajorDimension("ROWS")
                .setValueRenderOption("UNFORMATTED_VALUE").setDateTimeRenderOption("SERIAL_NUMBER").execute();
        var values = response.getValueRanges();
        if (values == null || values.size() != ranges.size()) throw new IOException("Incomplete sheet response.");
        List<List<List<Object>>> grids = values.stream()
                .map(value -> value.getValues() == null ? List.<List<Object>>of() : value.getValues()).toList();
        var blocks = new ArrayList<Range>();
        for (int i = 0; i < starts.size(); i++) blocks.add(new Range(starts.get(i), grids.get(i)));
        int i = blocks.size();
        return new Snapshot(blocks, grids.get(i), grids.get(i+1), grids.get(i+2), grids.get(i+3));
    }

    private static SheetProperties required(Map<String,SheetProperties> tabs, String title, int rows, int cols) {
        var tab = tabs.get(title);
        if (tab == null || tab.getGridProperties() == null || tab.getGridProperties().getRowCount() < rows
                || tab.getGridProperties().getColumnCount() < cols)
            throw new IllegalArgumentException("Missing or incomplete source tab: " + title);
        return tab;
    }

    public static String columnName(int column) {
        var name = new StringBuilder();
        for (int n = column; n > 0; n = (n-1)/26) name.append((char) ('A' + (n-1)%26));
        return name.reverse().toString();
    }
}
