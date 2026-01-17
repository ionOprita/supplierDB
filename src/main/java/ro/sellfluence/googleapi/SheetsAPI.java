package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchGetValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesResponse;
import com.google.api.services.sheets.v4.model.BooleanCondition;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.DataValidationRule;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.NumberFormat;
import com.google.api.services.sheets.v4.model.ProtectedRange;
import com.google.api.services.sheets.v4.model.RepeatCellRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.UpdateValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.jspecify.annotations.Nullable;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.googleapi.Credentials.getCredentials;

/**
 * This class represents a spreadsheet in Google-Drive.
 */
public class SheetsAPI {

    private static final Logger logger = Logs.getConsoleAndFileLogger("SheetsAPI", INFO, 10, 10_000_000);

    public static final String COLUMNS = "COLUMNS";
    public static final String ROWS = "ROWS";
    public static final String UNFORMATTED_VALUE = "UNFORMATTED_VALUE";

    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    public static final String USER_ENTERED = "USER_ENTERED";
    private final String appName;
    private final String spreadSheetId;
    private final String spreadSheetName;

    private List<SheetMetaData> sheetMetaData = null;

    /**
     * Initialise the sheets API.
     *
     * @param appName name of the application
     *                as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    private SheetsAPI(String appName, String spreadSheetId, String spreadSheetName) {
        this.appName = appName;
        this.spreadSheetId = spreadSheetId;
        this.spreadSheetName = spreadSheetName;
    }

    /**
     * Cache spreadsheets by app name and ID.
     */
    private static final Map<String, SheetsAPI> spreadSheets = new HashMap<>();

    /**
     * Get a spreadsheet by its ID.
     *
     * @param appName       name of the application
     *                      as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     * @param spreadSheetId ID of the spreadsheet.
     * @return instance of this class representing the spreadsheet, or null if not found.
     */
    public static SheetsAPI getSpreadSheetById(String appName, String spreadSheetId) {
        requireNonNull(appName);
        if (spreadSheetId == null) {
            return null;
        }
        var name = DriveAPI.getDriveAPI(appName).getNameForId(spreadSheetId);
        if (name == null) {
            return null;
        }
        var key = "%s\t%s".formatted(appName, spreadSheetId);
        return spreadSheets.computeIfAbsent(key, _ -> new SheetsAPI(appName, spreadSheetId, name));
    }

    /**
     * Get a spreadsheet by its name.
     *
     * @param appName         name of the application
     *                        as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     * @param spreadSheetName name of the spreadsheet.
     * @return instance of this class representing the spreadsheet, or null if not found.
     */
    public static @Nullable SheetsAPI getSpreadSheetByName(String appName, String spreadSheetName) {
        requireNonNull(appName);
        if (spreadSheetName == null) {
            return null;
        }
        var id = DriveAPI.getDriveAPI(appName).getFileId(spreadSheetName);
        if (id == null) {
            return null;
        }
        var key = "%s\t%s".formatted(appName, id);
        return spreadSheets.computeIfAbsent(key, _ -> new SheetsAPI(appName, id, spreadSheetName));
    }

    public String getSpreadSheetName() {
        return spreadSheetName;
    }

    public String getSpreadSheetId() {
        return spreadSheetId;
    }

    public List<ProtectedRange> getProtectedRanges(String name) {
        requireNonNull(name);
        var spreadsheets = getSheetsService().spreadsheets();
        var get = repeatCellRequest(4, "get(%s)".formatted(spreadSheetId), () -> spreadsheets.get(spreadSheetId));
        var response = repeatCellRequest(4, "", get::execute);
        var matchingSheets = response.getSheets().stream()
                .filter(sheet -> name.equals(sheet.getProperties().getTitle())).toList();
        if (matchingSheets.isEmpty()) {
            return null;
        } else if (matchingSheets.size() == 1) {
            return matchingSheets.getFirst().getProtectedRanges();
        } else {
            throw new RuntimeException("More than one sheet matches %s.".formatted(name));
        }
    }

    public String getTitle() {
        return spreadSheetId;
    }

    public record SheetMetaData(int index, int sheetId, String title) {
    }

    public List<SheetMetaData> getSheetProperties() {
        if (sheetMetaData == null) {
            var spreadsheets = getSheetsService().spreadsheets();
            var get = repeatCellRequest(
                    4,
                    "get(%s)".formatted(spreadSheetId),
                    () -> spreadsheets.get(spreadSheetId)
            );
            var response = repeatCellRequest(
                    5,
                    "getSheetProperties()",
                    get::execute
            );
            sheetMetaData = response.getSheets().stream()
                    .map(sheet -> {
                        var properties = sheet.getProperties();
                        return new SheetMetaData(properties.getIndex(), properties.getSheetId(), properties.getTitle());
                    }).toList();
        }
        return sheetMetaData;
    }

    public Integer getSheetId(String name) {
        requireNonNull(name);
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
            var command = getSheetsService().spreadsheets().values().get(spreadSheetId, range).setMajorDimension(COLUMNS);
            var response = repeatCellRequest(
                    5,
                    "getColumn(%s,%s)".formatted(sheetName, columnName),
                    command::execute
            );
            var result = response.getValues().getFirst();
            return result.stream().map(o -> (String) o).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getColumnInChunks(String sheetName, String columnName, int chunkSize) {
        List<String> allValues = new ArrayList<>();
        int startRow = 1;
        while (true) {
            int endRow = startRow + chunkSize - 1;
            String range = "%1$s!%2$s%3$d:%2$s%4$d".formatted(sheetName, columnName, startRow, endRow);
            var valuesCommand = getSheetsService().spreadsheets().values();
            var get = repeatCellRequest(
                    4,
                    "get(%s,%s)".formatted(spreadSheetName, range),
                    () -> valuesCommand.get(spreadSheetId, range)
            );
            var command = get.setMajorDimension("COLUMNS");
            var response = repeatCellRequest(
                    5,
                    "getColumnInChunks(%s,%s, %d)".formatted(sheetName, columnName, chunkSize),
                    command::execute
            );
            var values = response.getValues();

            // If no data is returned, break
            if (values == null || values.isEmpty() || values.getFirst().isEmpty()) {
                break;
            }

            List<String> chunk = values.getFirst().stream()
                    .map(o -> (String) o)
                    .toList();

            allValues.addAll(chunk);

            // If we got fewer than chunkSize rows, we're done
            if (chunk.size() < chunkSize) {
                break;
            }

            startRow += chunkSize;
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return allValues;
    }

    public List<String> getRow(String sheetName, int rowNumber) {
        var range = "%1$s!%2$s:%2$s".formatted(sheetName, rowNumber);
        var inputValues = getSheetsService().spreadsheets().values();
        var get = repeatCellRequest(
                4,
                "get(%s,%s)".formatted(spreadSheetId, range),
                () -> inputValues.get(spreadSheetId, range)
        );
        var command = get.setMajorDimension(ROWS);
        var result = repeatCellRequest(
                5,
                "getRow(%s,%d)".formatted(sheetName, rowNumber),
                command::execute
        );
        var values = result.getValues();
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.getFirst().stream().map(o -> (String) o).toList();
    }

    public List<Object> getRowAsDates(String sheetName, int rowNumber) {
        var range = "%1$s!A%2$s:AC%2$s".formatted(sheetName, rowNumber);
        var inputValues = getSheetsService().spreadsheets().values();
        var get = repeatCellRequest(
                4,
                "get(%s,%s)".formatted(spreadSheetId, range),
                () -> inputValues.get(spreadSheetId, range)
        );
        var command = get.setMajorDimension("ROWS").setValueRenderOption("UNFORMATTED_VALUE").setFields("values");
        var response = repeatCellRequest(
                5,
                "getRowAsDates(%s,%d)".formatted(sheetName, rowNumber),
                command::execute
        );
        var values = response.getValues();
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.getFirst();
    }

    public List<List<String>> getRowsInColumnRange(String sheetName, String firstColumn, String lastColumns) {
        var range = "%1$s!%2$s:%3$s".formatted(sheetName, firstColumn, lastColumns);
        var inputValues = getSheetsService().spreadsheets().values();
        var getCommand = repeatCellRequest(
                4,
                "get(%s,%s)".formatted(spreadSheetId, range),
                () -> inputValues.get(spreadSheetId, range)
        );
        var command = getCommand.setMajorDimension(ROWS);
        var response = repeatCellRequest(
                5,
                "getRowsInColumnRange(%s,%s,%s)".formatted(sheetName, firstColumn, lastColumns),
                command::execute
        );
        var result = response.getValues();
        if (result != null) {
            return result.stream().map(objects -> objects.stream().map(o -> (String) o).toList()).toList();
        } else {
            return List.of(List.of());
        }
    }

    public UpdateValuesResponse updateRange(String range, List<List<Object>> values) {
        ValueRange content = new ValueRange().setValues(values).setMajorDimension(ROWS).setRange(range);
        var inputValues = getSheetsService().spreadsheets().values();
        var update = repeatCellRequest(
                4,
                "update(%s,%s,%s)".formatted(spreadSheetId, range, content),
                () -> inputValues.update(spreadSheetId, range, content)
        );
        Sheets.Spreadsheets.Values.Update command = update
                .setValueInputOption(USER_ENTERED)
                .setFields("*");
        return repeatCellRequest(
                5,
                "updateRange(%s,%s,%s)".formatted(spreadSheetId, range, content),
                command::execute
        );
    }

    public BatchUpdateValuesResponse updateRanges(List<List<List<Object>>> rows, String... ranges) {
        var groupOfTables = transformList(rows);
        if (groupOfTables.size() != ranges.length) {
            throw new IllegalArgumentException(
                    "The number of cell groups (%d) must match the number of ranges given (%d)".formatted(
                            groupOfTables.size(), ranges.length
                    )
            );
        }
        var updateList = new ArrayList<ValueRange>();
        for (int i = 0; i < ranges.length; i++) {
            updateList.add(new ValueRange().setRange(ranges[i]).setValues(groupOfTables.get(i)));
        }
        var body = new BatchUpdateValuesRequest().setValueInputOption(USER_ENTERED).setData(updateList);
        var inputValues = getSheetsService().spreadsheets().values();
        var update = repeatCellRequest(
                4,
                "batchUpdate(%s,%s)".formatted(spreadSheetId, body),
                () -> inputValues.batchUpdate(spreadSheetId, body)
        );
        return repeatCellRequest(
                5,
                "updateRanges(%s,%s)".formatted(rows, Arrays.toString(ranges)),
                update::execute
        );
    }

    /**
     * Updates part of a sheet column.
     *
     * @param sheetName        the name of the sheet.
     * @param columnIdentifier the name of the column.
     * @param startRow         the first row to modify.
     * @param columnData       the data. Null values are stored as empty strings.
     */
    public void updateSheetColumnFromRow(String sheetName, String columnIdentifier, Integer startRow, List<?> columnData) {
        var values = columnData.stream().skip(startRow - 1).map(it -> {
            var o = it != null ? (Object) it : (Object) "";
            return List.of(o);
        }).toList();
        updateRange(
                "'%s'!%s%d:%s%d".formatted(sheetName, columnIdentifier, startRow, columnIdentifier, startRow + columnData.size() - 1),
                values
        );
    }

    public BatchUpdateSpreadsheetResponse formatDate(String spreadSheetId, int startColumn, int endColumn, int startRow, int endRow) {
        // Create the number format object
        NumberFormat numberFormat = new NumberFormat()
                .setType("DATE_TIME")
                .setPattern("dd/MM/yyyy hh:mm:ss");

        // Create the cell format object
        CellFormat cellFormat = new CellFormat()
                .setNumberFormat(numberFormat);

        var range = new GridRange()
                .setSheetId(getSheetId(spreadSheetId))
                .setStartColumnIndex(startColumn)
                .setEndColumnIndex(endColumn)
                .setStartRowIndex(startRow)
                .setEndRowIndex(endRow);
        // Create a repeat cell request
        RepeatCellRequest repeatCellRequest = new RepeatCellRequest()
                .setCell(new CellData().setUserEnteredFormat(cellFormat))
                .setRange(range)
                .setFields("userEnteredFormat.numberFormat");

        // Create a request to update the spreadsheet
        Request request = new Request().setRepeatCell(repeatCellRequest);
        var updateList = new ArrayList<Request>();
        updateList.add(request);

        var body = new BatchUpdateSpreadsheetRequest()
                .setRequests(updateList);

        // Execute the request
        var spreadsheets = getSheetsService().spreadsheets();
        var batchUpdate = repeatCellRequest(
                4,
                "batchUpdate(%s,%s)".formatted(spreadSheetId, body),
                () -> spreadsheets.batchUpdate(spreadSheetId, body)
        );
        return repeatCellRequest(
                5,
                "formatDate(%s,%d,%d,%d,%d)".formatted(spreadSheetId, startColumn, endColumn, startRow, endRow),
                batchUpdate::execute
        );
    }


    public BatchUpdateSpreadsheetResponse formatAsCheckboxes(String spreadSheetId, int startColumn, int endColumn, int startRow, int endRow) {
        // Create a data validation rule
        DataValidationRule rule = new DataValidationRule()
                .setCondition(new BooleanCondition()
                        .setType("BOOLEAN"));

        // Create a cell style with the data validation rule
        var cellStyle = new CellData().setDataValidation(rule);

        var range = new GridRange()
                .setSheetId(getSheetId(spreadSheetId))
                .setStartColumnIndex(startColumn)
                .setEndColumnIndex(endColumn)
                .setStartRowIndex(startRow)
                .setEndRowIndex(endRow);
        // Create a repeat cell request
        RepeatCellRequest repeatCellRequest = new RepeatCellRequest()
                .setCell(cellStyle)
                .setRange(range)
                .setFields("*");

        // Create a request to update the spreadsheet
        Request request = new Request()
                .setRepeatCell(repeatCellRequest);

        var updateList = new ArrayList<Request>();
        updateList.add(request);
        var body = new BatchUpdateSpreadsheetRequest().setRequests(updateList);
        try {
            var batchUpdate = getSheetsService().spreadsheets().batchUpdate(spreadSheetId, body);
            return repeatCellRequest(
                    4,
                    "formatAsCheckboxes(%s,%d,%d,%d,%d)".formatted(spreadSheetId, startColumn, endColumn, startRow, endRow),
                    batchUpdate::execute
            );
        } catch (IOException cause) {
            throw new RuntimeException("Issue in updateRanges for the sheet %s".formatted(spreadSheetName), cause);
        }
    }

    @FunctionalInterface
    public interface Caller<T> {

        /**
         * Performs the call.
         *
         * @return a result
         * @throws IOException if an I/O error occurs.
         */
        T execute() throws IOException;
    }

    private <T> T repeatCellRequest(int maxRepetition, String callerDescription, Caller<T> caller) {
        var retryCount = maxRepetition;
        var retryDelay = 5_000;
        T result = null;
        while (retryCount > 0) {
            try {
                var t0 = System.currentTimeMillis();
                result = caller.execute();
                var t1 = System.currentTimeMillis();
                if (t1 - t0 > 5_000) {
                    logger.log(WARNING, "Slow call to %s. Time: %d ms".formatted(callerDescription, t1 - t0));
                }
                retryCount = 0;
            } catch (IOException e) {
                if (e instanceof GoogleJsonResponseException g) {
                    if (g.getStatusCode() == 400) {
                        throw new RuntimeException("Bad request. %s".formatted(g.getDetails().getMessage()), e);
                    }
                }
                retryCount--;
                if (retryCount == 0) {
                    throw new RuntimeException("Issue in %s".formatted(callerDescription), e);
                }
                logger.log(WARNING, "IOException. Retrying after %d s. Retry count %d".formatted(retryDelay / 1000, retryCount));
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ex) {
                    // Don't care about interrupts.
                }
                retryDelay *= 2;
            }
        }
        return result;
    }

    public static List<List<List<Object>>> transformList(List<List<List<Object>>> inputList) {
        List<List<List<Object>>> outputList = new ArrayList<>();
        var firstRow = inputList.getFirst();
        var groupCount = firstRow.size();
        for (int groupNumber = 0; groupNumber < groupCount; groupNumber++) {
            var groupToFilter = groupNumber;
            outputList.add(inputList.stream()
                    .map(rowGroups -> rowGroups.get(groupToFilter))
                    .collect(Collectors.toList()));
        }
        return outputList;
    }

    /**
     * Read multiple columns from a sheet.
     *
     * @param sheetName name of the sheet.
     * @param columns   columns to read.
     * @return list of rows.
     */
    public List<List<Object>> getMultipleColumns(String sheetName, String... columns) {
        var ranges = Arrays.stream(columns).map(c -> "%1$s!%2$s:%2$s".formatted(sheetName, c)).toList();
        var inputValues = getSheetsService().spreadsheets().values();
        var batchGet = repeatCellRequest(
                4,
                "batchGet(%s,%s)".formatted(spreadSheetId, String.join(",", columns)),
                () -> inputValues.batchGet(spreadSheetId)
        );
        var command = batchGet.setRanges(ranges).setMajorDimension(COLUMNS).setValueRenderOption(UNFORMATTED_VALUE);
        BatchGetValuesResponse response = repeatCellRequest(
                5,
                "getMultipleColumns(%s,%s)".formatted(spreadSheetId, String.join(",", columns)),
                command::execute
        );
        var result = response.getValueRanges();
        var rows = new ArrayList<List<Object>>();
        if (result != null && result.size() == columns.length) {
            var maxColumn = columns.length;
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
        }
        return rows;
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

    @Override
    public String toString() {
        return "SheetsAPI{" + "appName='" + appName + '\'' + ", spreadSheetName='" + spreadSheetName + '\'' + ", spreadSheetId='" + spreadSheetId + '\'' + '}';
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof SheetsAPI sheetsAPI)) return false;
        return appName.equals(sheetsAPI.appName) && Objects.equals(spreadSheetId, sheetsAPI.spreadSheetId) && spreadSheetName.equals(sheetsAPI.spreadSheetName);
    }

    @Override
    public int hashCode() {
        int result = appName.hashCode();
        result = 31 * result + Objects.hashCode(spreadSheetId);
        result = 31 * result + spreadSheetName.hashCode();
        return result;
    }

    private Sheets getSheetsService() {
        Sheets sheetService;
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
        return sheetService;
    }

    private static HttpRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
        return httpRequest -> {
            requestInitializer.initialize(httpRequest);
            httpRequest.setConnectTimeout(10_000);
            httpRequest.setReadTimeout(100_000);
        };
    }
}