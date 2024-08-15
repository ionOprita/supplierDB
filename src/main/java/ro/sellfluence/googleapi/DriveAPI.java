package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Objects;

import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class DriveAPI {
    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private final String appName;

    /**
     * Initialize the drive API.
     *
     * @param appName name of the application
     *                as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    public DriveAPI(String appName) {
        this.appName = appName;
    }

    /**
     * Find the d of a file given its name.
     *
     * @param name of the file.
     * @return the ID of the file.
     * @throws RuntimeException if something goes wrong.
     */
    public String getFileId(String name) {
        Objects.requireNonNull(name);
        try {
            var matchingFiles = new HashSet<String>();
            String pageToken = null;
            do {
                FileList fileList = setupDriveService().files().list().setPageToken(pageToken).execute();
                pageToken = fileList.getNextPageToken();
                var files = fileList.getFiles();
                files.stream()
                        .filter(f -> name.equals(f.getName()))
                        .map(File::getId).forEach(matchingFiles::add);

            } while (pageToken != null);
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
                    getCredentials(httpTransport)
            )
                    .setApplicationName(appName)
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Couldn't set up driver service", e);
        }
    }
}
