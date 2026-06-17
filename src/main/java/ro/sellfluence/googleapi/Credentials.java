package ro.sellfluence.googleapi;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.google.api.services.drive.DriveScopes.DRIVE_METADATA_READONLY;
import static com.google.api.services.drive.DriveScopes.DRIVE_READONLY;
import static com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS;

public class Credentials {
    /**
     * Path to the location of the credentials file. This holds the
     */
    private static final Path creddentialsPath = Paths.get(System.getProperty("user.home"))
            .resolve("Secrets")
            .resolve("testionut-416609-fc40756a3b13.json");
    
    private static final Path oauthCreddentialsPath = Paths.get(System.getProperty("user.home"))
            .resolve("Secrets")
            .resolve("googleOAuth2Credentials.json");

    /**
     * Path to the directory holding stored credentials.
     * Delete this directory to recreate fresh credentials.
     */
    public static final Path tokenStorePath = Paths.get(System.getProperty("java.io.tmpdir"))
            .resolve("googleApiTokens");

    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private static final List<String> scopes = List.of(DRIVE_READONLY, DRIVE_METADATA_READONLY, SPREADSHEETS);

    /**
     * Creates an authorized Credential object.
     *
     * @param httpTransport The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    public static GoogleCredentials getCredentials(final NetHttpTransport httpTransport)
            throws IOException {
        final InputStream in = new FileInputStream(creddentialsPath.toFile());
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(in)
                .createScoped(List.of(SPREADSHEETS, DRIVE_READONLY));
        return credentials;
    }

    /**
     * Creates an authorized Credential object.
     *
     * @param httpTransport The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    public static Credential getCredentialsOAuth(final NetHttpTransport httpTransport)
            throws IOException {
        final InputStream in = new FileInputStream(creddentialsPath.toFile());
        final GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(jsonFactory, new InputStreamReader(in));
        // Build flow and trigger user authorization request.
        final GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, jsonFactory, clientSecrets, scopes)
                .setDataStoreFactory(new FileDataStoreFactory(tokenStorePath.toFile()))
                .setAccessType("offline")
                .build();
        final LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }
}
