package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendCellsRequest;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.ProtectedRange;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.UpdateProtectedRangeRequest;
import com.google.api.services.sheets.v4.model.UpdateValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class SheetsAPI {

    public static final String COLUMNS = "COLUMNS";
    public static final String ROWS = "ROWS";
    public static final String UNFORMATTED_VALUE = "UNFORMATTED_VALUE";

    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    public static final String USER_ENTERED = "USER_ENTERED";
    public static final String INSERT_ROWS = "INSERT_ROWS";
    private final String appName;
    private final String spreadSheetId;

    private Sheets sheetService = null;

    private List<SheetMetaData> sheetMetaData = null;

    /**
     * Initialize the sheets API.
     *
     * @param appName name of the application
     *                as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    private SheetsAPI(String appName, String spreadSheetId) {
        this.appName = appName;
        this.spreadSheetId = spreadSheetId;
    }

    private static final Map<String, SheetsAPI> spreadSheets = new HashMap<>();

    public static SheetsAPI getSpreadSheet(String appName, String spreadSheetId) {
        var key = "%s\t%s".formatted(appName, spreadSheetId);
        return spreadSheets.computeIfAbsent(key, _ -> new SheetsAPI(appName, spreadSheetId));
    }

    public List<ProtectedRange> getProtectedRanges(String name) {
        Objects.requireNonNull(name);
        try {
            var matchingSheets = getSheetsService().spreadsheets().get(spreadSheetId).execute().getSheets().stream()
                    .filter(sheet -> name.equals(sheet.getProperties().getTitle())).toList();
            if (matchingSheets.isEmpty()) {
                return null;
            } else if (matchingSheets.size() == 1) {
                return matchingSheets.getFirst().getProtectedRanges();
            } else {
                throw new RuntimeException("More than one sheet matches %s.".formatted(name));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getTitle() {
        return spreadSheetId;
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

    /**
     * Given an index returns the name of the sheet.
     *
     * @param index starting with 1.
     * @return name of the n-th sheet.
     */
    public String getNameFromIndex(int index) {
        return getSheetProperties().stream().filter(m -> m.index() == index - 1).toList().getFirst().title();
    }

    public int getLastRow(String sheetName, String columnName) {
        return getColumn(sheetName, columnName).size();
    }

    public List<String> getColumn(String sheetName, String columnName) {
        try {
            var range = "%1$s!%2$s:%2$s".formatted(sheetName, columnName);
            var result = getSheetsService().spreadsheets().values().get(spreadSheetId, range).setMajorDimension(COLUMNS).execute().getValues().getFirst();
            return result.stream().map(o -> (String) o).toList();
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

    public UpdateValuesResponse updateRange(String range, List<List<Object>> values) {
        try {
            ValueRange content = new ValueRange().setValues(values).setMajorDimension(ROWS).setRange(range);
            return getSheetsService().spreadsheets().values().update(spreadSheetId, range, content)
                    .setValueInputOption(USER_ENTERED)
                    .setFields("*").execute();
        } catch (IOException e) {
            throw new RuntimeException("Issue in updateRange(%s,%s)".formatted(range, values), e);
        }
    }

    public List<List<Object>> getMultipleColumns(String sheetName, String... columns) {
        var ranges = Arrays.stream(columns).map(c -> "%1$s!%2$s:%2$s".formatted(sheetName, c)).toList();
        try {
            var result = getSheetsService().spreadsheets().values().batchGet(spreadSheetId).setRanges(ranges).setMajorDimension(COLUMNS).setValueRenderOption(UNFORMATTED_VALUE).execute().getValueRanges();
            if (result != null && result.size() == columns.length) {
                var maxColumn = columns.length;
                var rows = new ArrayList<List<Object>>();
                int[] columnSizes = result.stream().mapToInt(valueRange -> valueRange.getValues().getFirst().size()).toArray();
                var maxRow = Arrays.stream(columnSizes).max().getAsInt();
                for (int rowNumber = 0; rowNumber < maxRow; rowNumber++) {
                    var row = new ArrayList<>();
                    for (int columnNumber = 0; columnNumber < maxColumn; columnNumber++) {
                        if (rowNumber < columnSizes[columnNumber]) {
                            row.add(result.get(columnNumber).getValues().getFirst().get(rowNumber));
                        } else {
                            row.add(0);
                        }
                    }
                    rows.add(row);
                }
                return rows;
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Append the values at the bottom of the sheet.
     *
     * @param range  where to append the data, for example, sheetName!A:P
     * @param values organized as a list of rows holding a list of cells.
     * @return response
     */
    public AppendValuesResponse append(String range, List<List<Object>> values) {
        var content = new ValueRange().setValues(values);
        try {
            return getSheetsService().spreadsheets().values().append(spreadSheetId, range, content)
                    .setValueInputOption(USER_ENTERED)
                    .setInsertDataOption(INSERT_ROWS)
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't append to sheet ", e);
        }
    }

    /**
     * Append the values at the bottom of the sheet.
     *
     * @param spreadSheetName name of the sheet to which to append the rows.
     * @param values          organized as a list of rows holding a list of cells.
     * @return result
     */
    public BatchUpdateSpreadsheetResponse appendX(String spreadSheetName, List<RowData> values) {
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
     * Append the values at the bottom of the sheet.
     *
     * @param spreadSheetName name of the sheet to which to append the rows.
     * @param columnNumber    used to find the correct protection range.
     * @param set             true to set it to warningOnly, false to reset it to block.
     * @return result
     */
    public BatchUpdateSpreadsheetResponse setRangeWarningOnly(String spreadSheetName, int columnNumber, boolean set) {
        var matchingRanges = getProtectedRanges(spreadSheetName).stream()
                .filter(pr -> pr.getRange().getStartColumnIndex() <= columnNumber && columnNumber <= pr.getRange().getEndColumnIndex())
                .toList();
        try {
            if (matchingRanges.isEmpty()) {
                return null;
            } else if (matchingRanges.size() == 1) {
                var protectedRange = matchingRanges.getFirst();
                protectedRange.setWarningOnly(set);
                var body = new BatchUpdateSpreadsheetRequest()
                        .setRequests(List.of(
                                new Request().setUpdateProtectedRange(
                                        new UpdateProtectedRangeRequest()
                                                .setProtectedRange(protectedRange)
                                                .setFields("*")
                                )

                        ));
                return getSheetsService().spreadsheets().batchUpdate(spreadSheetId, body).execute();
            } else {
                throw new RuntimeException("More than one protection range for column " + columnNumber);
            }
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
        // Caching disabled, because thought of causing the connection reset error.
        if (true /*sheetService == null*/) {
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
        return httpRequest -> {
            requestInitializer.initialize(httpRequest);
            httpRequest.setConnectTimeout(60_000);  // 1 minute connect timeout
            httpRequest.setReadTimeout(120_000);  // 2 minutes read timeout
        };
    }

}
