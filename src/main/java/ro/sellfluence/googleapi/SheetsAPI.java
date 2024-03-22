package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendCellsRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class SheetsAPI {

    public static final String COLUMNS = "COLUMNS";
    public static final String ROWS = "ROWS";
    public static final String UNFORMATTED_VALUE = "UNFORMATTED_VALUE";

    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private final String appName;
    private final String spreadSheetId;

    private Sheets sheetService = null;

    private List<SheetMetaData> sheetMetaData = null;

    /**
     * Initialize the sheets API.
     *
     * @param appName name of the app as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    public SheetsAPI(String appName, String spreadSheetId) {
        this.appName = appName;
        this.spreadSheetId = spreadSheetId;
    }

    public record SheetMetaData(int index, int sheetId, String title) {
    }

    public List<SheetMetaData> getSheetProperties() {
        if (sheetMetaData == null) {
            try {
                sheetMetaData = getSheetsService().spreadsheets().get(spreadSheetId).execute().getSheets().stream()
                        .map(sheet -> {
                            var properties = sheet.getProperties();
                            return new SheetMetaData(properties.getIndex(), properties.getSheetId(), properties.getTitle());
                        }).toList();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't retrieve sheet properties.", e);
            }
        }
        return sheetMetaData;
    }

    public Integer getSheetId(String name) {
        Objects.requireNonNull(name);
        var matchingIds = getSheetProperties().stream().filter(metaData -> name.equals(metaData.title)).toList();
        if (matchingIds.isEmpty()) {
            return null;
        } else if (matchingIds.size() == 1) {
            return matchingIds.getFirst().sheetId;
        } else {
            throw new RuntimeException("More than one file with matches %s.".formatted(name));
        }
    }

    public List<String> getColumn(String sheetName, String columnName) {
        try {
            var range = "%1$s!%2$s:%2$s".formatted(sheetName, columnName);
            var result = getSheetsService().spreadsheets().values().get(spreadSheetId, range).setMajorDimension(COLUMNS).execute().getValues();
            return result.getFirst().stream().map(o -> (String) o).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<List<String>> getRowsInColumnRange(String sheetName, String firstColumn, String lastColumns) {
        try {
            var range = "%1$s!%2$s:%3$s".formatted(sheetName, firstColumn, lastColumns);
            var result = getSheetsService().spreadsheets().values().get(spreadSheetId, range).setMajorDimension(ROWS).execute().getValues();
            if (result != null) {
                return result.stream().map(objects -> objects.stream().map(o -> (String) o).toList()).toList();
            } else {
                return List.of(List.of());
            }
        } catch (IOException e) {
            throw new RuntimeException("Issue in getRowsInColumnRange(%s,%s,%s)".formatted(sheetName, firstColumn, lastColumns), e);
        }
    }

    public List<ValueRange> getMultipleColumns(String sheetName, String... columns) {
        var ranges = Arrays.stream(columns).map(c -> "%1$s!%2$s:%2$s".formatted(sheetName, c)).toList();
        try {
            var result = getSheetsService().spreadsheets().values().batchGet(spreadSheetId).setRanges(ranges).setMajorDimension(COLUMNS).setValueRenderOption(UNFORMATTED_VALUE).execute().getValueRanges();
            if (result != null) {
                var r = result.stream().map(valueRange -> valueRange.getValues()).toList();
                System.out.println(r);
            } else {
            }
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Append the values at the bottom of the sheet.
     *
     * @param spreadSheetName name of the sheet to which to append the rows.
     * @param values          organized as a list of rows holding a list of cells.
     * @return
     */
    public BatchUpdateSpreadsheetResponse append(String spreadSheetName, List<RowData> values) {
        var body = new BatchUpdateSpreadsheetRequest()
                .setRequests(List.of(
                        new Request().setAppendCells(
                                new AppendCellsRequest()
                                        .setSheetId(getSheetId(spreadSheetName))
                                        .setRows(values)
                                        .setFields("*")
                        )
                ));
        try {
            return getSheetsService().spreadsheets().batchUpdate(spreadSheetId, body).execute();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't append to sheet ", e);
        }
    }

    /**
     * Converts from a Java type to cell data.
     *
     * @param value plain Java value
     * @return wrapped data
     */
    public static CellData toCellData(Object value) {
        if (value instanceof Number n) {
            return new CellData().setUserEnteredValue(new ExtendedValue().setNumberValue(n.doubleValue()));
        } else if (value instanceof Boolean b) {
            return new CellData().setUserEnteredValue(new ExtendedValue().setBoolValue(b));
        } else {
            return new CellData().setUserEnteredValue(new ExtendedValue().setStringValue(value.toString()));
        }
    }

    private Sheets getSheetsService() {
        if (sheetService == null) {
            try {
                final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
                Sheets.Builder builder = new Sheets.Builder(
                        httpTransport,
                        jsonFactory,
                        getCredentials(httpTransport)
                );
                builder.setHttpRequestInitializer(setHttpTimeout(builder.getHttpRequestInitializer()));
                sheetService = builder
                        .setApplicationName(appName)
                        .build();
            } catch (GeneralSecurityException | IOException e) {
                throw new RuntimeException("Couldn't set up sheets service", e);
            }
        }
        return sheetService;
    }
    private static HttpRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest httpRequest) throws IOException {
                requestInitializer.initialize(httpRequest);
                httpRequest.setConnectTimeout(60_000);  // 1 minute connect timeout
                httpRequest.setReadTimeout(120_000);  // 2 minutes read timeout
            }
        };
    }

}
