package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class SheetsAPI {
    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private static final String appName = "sellfluence1";
    private static final List<String> scopes = Collections.singletonList(SheetsScopes.SPREADSHEETS);

    public static Sheets setupSheetsService() throws GeneralSecurityException, IOException {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Sheets.Builder builder = new Sheets.Builder(httpTransport, jsonFactory, getCredentials(httpTransport, scopes));
        builder.setHttpRequestInitializer(setHttpTimeout(builder.getHttpRequestInitializer()));
        return builder.setApplicationName(appName)
                .build();
    }

    private static HttpRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
        return httpRequest -> {
            requestInitializer.initialize(httpRequest);
            httpRequest.setConnectTimeout(3 * 60000);  // 3 minutes connect timeout
            httpRequest.setReadTimeout(3 * 60000);  // 3 minutes read timeout
        };
    }
}
