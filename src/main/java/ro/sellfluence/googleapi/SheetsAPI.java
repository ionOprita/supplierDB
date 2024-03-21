package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class SheetsAPI {
    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private final String appName;
    private final String spreadSheetId;
    private static final List<String> scopes = Collections.singletonList(SheetsScopes.SPREADSHEETS);

    public static final String COLUMNS = "COLUMNS";
    public static final String ROWS = "ROWS";
    public static final String UNFORMATTED_VALUE = "UNFORMATTED_VALUE";

    /**
     * Initialize the sheets API.
     *
     * @param appName name of the app as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    public SheetsAPI(String appName, String spreadSheetId) {
        this.appName = appName;
        this.spreadSheetId = spreadSheetId;
    }

    public Sheets setupSheetsService() {
        try {
            final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            return new Sheets.Builder(
                    httpTransport,
                    jsonFactory,
                    getCredentials(httpTransport, scopes)
            )
                    .setApplicationName(appName)
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Couldn't set up sheets service", e);
        }
    }

    public Integer getSheetId(String name) {
        Objects.requireNonNull(name);
        try {
            var matchingIds = setupSheetsService().spreadsheets().get(spreadSheetId).execute().getSheets().stream()
                    .filter(sheet -> name.equals(sheet.getProperties().getTitle()))
                    .map(sheet -> sheet.getProperties().getSheetId())
                    .collect(Collectors.toSet());
            if (matchingIds.isEmpty()) {
                return null;
            } else if (matchingIds.size() == 1) {
                return matchingIds.iterator().next();
            } else {
                throw new RuntimeException("More than one file with matches %s.".formatted(name));
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't retrieve spreadsheet file.", e);
        }
    }

    public List<String> getColumn(String sheetName, String columnName) {
        try {
            var range ="%1$s!%2$s:%2$s".formatted(sheetName, columnName);
            var result = setupSheetsService().spreadsheets().values().get(spreadSheetId, range).setMajorDimension(COLUMNS).execute().getValues();
            return result.getFirst().stream().map(o -> (String)o).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<List<String>> getRowsInColumnRange(String sheetName, String firstColumn, String lastColumns) {
        try {
            var range ="%1$s!%2$s:%3$s".formatted(sheetName, firstColumn, lastColumns);
            var result = setupSheetsService().spreadsheets().values().get(spreadSheetId, range).setMajorDimension(ROWS).execute().getValues();
            if (result!=null) {
                return result.stream().map(objects -> objects.stream().map(o -> (String)o).toList()).toList();
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
            var result = setupSheetsService().spreadsheets().values().batchGet(spreadSheetId).setRanges(ranges).setMajorDimension(COLUMNS).setValueRenderOption(UNFORMATTED_VALUE).execute().getValueRanges();
            if (result!=null) {
                var r=result.stream().map(valueRange -> valueRange.getValues()).toList();
                System.out.println(r);
            } else {
            }
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
