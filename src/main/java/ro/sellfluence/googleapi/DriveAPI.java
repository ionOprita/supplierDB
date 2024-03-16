package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.google.api.services.drive.DriveScopes.DRIVE_METADATA_READONLY;
import static com.google.api.services.drive.DriveScopes.DRIVE_READONLY;
import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class DriveAPI {
    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private static final List<String> scopes = List.of(DRIVE_READONLY, DRIVE_METADATA_READONLY);
    private final String appName;

    /**
     * Initialize the drive API.
     *
     * @param appName name of the app as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    public DriveAPI(String appName) {
        this.appName = appName;
    }

    /**
     * Find the d of a file given its name.
     *
     * @param name of the file.
     * @return the id of the file.
     * @throws Exception if something goes wrong.
     */
    public String getFileId(String name) {
        Objects.requireNonNull(name);
        try {
            var matchingFiles = setupDriveService().files().list().execute().getFiles().stream()
                    .filter(f -> name.equals(f.getName()))
                    .map(File::getId)
                    .collect(Collectors.toSet());
            if (matchingFiles.isEmpty()) {
                return null;
            } else if (matchingFiles.size() == 1) {
                return matchingFiles.iterator().next();
            } else {
                throw new RuntimeException("More than one file with matches %s.".formatted(name));
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't retrieve the list of files.", e);
        }
    }

    private Drive setupDriveService() {
        try {
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            return new Drive.Builder(
                    httpTransport,
                    jsonFactory,
                    getCredentials(httpTransport, scopes)
            )
                    .setApplicationName(appName)
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Couldn't set up driver service",e);
        }
    }
}
