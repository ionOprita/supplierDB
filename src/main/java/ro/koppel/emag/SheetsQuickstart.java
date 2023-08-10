package ro.koppel.emag;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SheetsQuickstart {
    private static final String APPLICATION_NAME = "emagapp";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    /**
     * Global instance of the scopes required by this quickstart.
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES =
            Collections.singletonList(SheetsScopes.SPREADSHEETS);
    private static final String CREDENTIALS_FILE_PATH = "C:\\Users\\Oprita\\Desktop\\supplierDB\\ExcelEMAG\\client_secret_2_871267262769-9htv70p5fe3k8ndji15d0ki65l114b7o.apps.googleusercontent.com.json";
    public static final int sheetId = 0;

    /**
     * Creates an authorized Credential object.
     *
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT)
            throws IOException {
        InputStream in = new FileInputStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private static HttpRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest httpRequest) throws IOException {
                requestInitializer.initialize(httpRequest);
                httpRequest.setConnectTimeout(3 * 60000);  // 3 minutes connect timeout
                httpRequest.setReadTimeout(3 * 60000);  // 3 minutes read timeout
            }
        };
    }

    public static Sheets setupSheetsService() throws GeneralSecurityException, IOException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Sheets.Builder builder = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT));
        builder.setHttpRequestInitializer(setHttpTimeout(builder.getHttpRequestInitializer()));
        return builder.setApplicationName(APPLICATION_NAME)
                .build();
    }

    private static BlockDimension getSize(List<RowData> values) {
        var rowCount = values.size();
        if (rowCount == 0) {
            throw new IllegalArgumentException("You must supply at least one row");
        }
        var columnCount = values.get(0).getValues().size();
        if (columnCount == 0) {
            throw new IllegalArgumentException("Rows must have at least one cell");
        }
        for (var row : values) {
            if (row.getValues().size() != columnCount) {
                throw new IllegalArgumentException("All rows must have the same number of cells");
            }
        }
        return new BlockDimension(rowCount, columnCount);
    }

    /**
     * Append the values at the bottom of the sheet.
     *
     * @param spreadSheetId name of the sheet to which to append the rows.
     * @param values  organized as a list of rows holding a list of cells.
     */
    public static void append(String spreadSheetId, List<RowData> values) throws GeneralSecurityException, IOException {
        var service = setupSheetsService();
        var requests = new ArrayList<Request>();
        requests.add(
                new Request().setAppendCells(
                        new AppendCellsRequest().setSheetId(sheetId).setRows(values).setFields("*")
                )
        );
        var body =
                new BatchUpdateSpreadsheetRequest().setRequests(requests);
        var response = service.spreadsheets().batchUpdate(spreadSheetId, body).execute();
        System.out.println(response);
    }

    /**
     * Insert the values between lines 1 and 2 of the sheet, i.e. after the title but before the
     * first line having data.
     *
     * @param spreadSheetId name of the sheet into which to insert the rows.
     * @param values  organized as a list of rows holding a list of cells.
     */
    public static void insertAtTop(String spreadSheetId, List<RowData> values) throws GeneralSecurityException, IOException {
        var dimension = getSize(values);
        var service = setupSheetsService();
        var requests = new ArrayList<Request>();
        GridRange gridRange = new GridRange()
                .setSheetId(sheetId)
                .setStartColumnIndex(0)
                .setEndColumnIndex(dimension.columnCount())
                .setStartRowIndex(1)
                .setEndRowIndex(dimension.rowCount()+1);
        requests.add(
                new Request().setInsertRange(
                        new InsertRangeRequest().setShiftDimension("ROWS").setRange(gridRange)
                )
        );
        requests.add(
                new Request().setUpdateCells(
                        new UpdateCellsRequest().setRange(gridRange).setRows(values).setFields("*")
                )
        );
        var body =
                new BatchUpdateSpreadsheetRequest().setRequests(requests);
        var response = service.spreadsheets().batchUpdate(spreadSheetId, body).execute();
        System.out.println(response);
    }

    public static record BlockDimension(int rowCount, int columnCount) {
    }
}