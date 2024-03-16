package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;

import java.io.IOException;
import java.security.GeneralSecurityException;
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

    public static void main(String[] args) {
    }
}
