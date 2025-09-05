package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class DriveAPI {
    private static final Logger warnLogger = Logs.getConsoleLogger("DriveAPI", Level.WARNING);

    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private static final Map<String, DriveAPI> nameToAPI = new HashMap<>();

    private final String appName;

    private final Map<String, String> nameForId = new HashMap<>();

    private record CachedFile(LocalDateTime lastUpdated, String fileId) {
    }

    private final Map<String, CachedFile> driveCache = new HashMap<>();

    /**
     * Initialise the drive API.
     *
     * @param appName name of the application
     *                as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    private DriveAPI(String appName) {
        this.appName = appName;
    }

    public static DriveAPI getDriveAPI(String appName) {
        var api = nameToAPI.get(appName);
        if (api == null) {
            api = new DriveAPI(appName);
            nameToAPI.put(appName, api);
        }
        return api;
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
        var cached = driveCache.get(name);
        if (cached != null && cached.lastUpdated.plusMinutes(10).isAfter(LocalDateTime.now())) {
            return cached.fileId;
        }
        try {
            var matchingFiles = new HashSet<File>();
            String pageToken = null;
            do {
                FileList fileList = setupDriveService().files().list().setQ("name='%s'".formatted(name)).setFields("*").setPageToken(pageToken).execute();
                pageToken = fileList.getNextPageToken();
                var files = fileList.getFiles();
                files.stream()
                        .filter(f -> name.equals(f.getName()))
                        .forEach(matchingFiles::add);
            } while (pageToken != null);
            if (matchingFiles.isEmpty()) {
                return null;
            } else if (matchingFiles.size() == 1) {
                String fileId = matchingFiles.iterator().next().getId();
                updateCaches(name, fileId);
                return fileId;
            } else {
                var myFiles = matchingFiles.stream().filter(File::getOwnedByMe).toList();
                if (myFiles.size() == 1) {
                    var fileId = myFiles.getFirst().getId();
                    warnLogger.log(Level.WARNING, "Found more than one file with the name %s, using the single one owned by me with the ID %s.".formatted(name, fileId));
                    updateCaches(name, fileId);
                    return fileId;
                } else {
                    throw new RuntimeException(
                            "%d files with matches %s, %d files are owned by me."
                                    .formatted(matchingFiles.size(), name, myFiles.size())
                    );
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't retrieve the list of files.", e);
        }
    }

    private void updateCaches(String name, String fileId) {
        nameForId.put(fileId, name);
        driveCache.put(name, new CachedFile(LocalDateTime.now(), fileId));
    }

    /**
     * Return the name for a file ID. This works only if the file was already searched for
     * using this API.
     *
     * @param id to look for
     * @return either the name or the ID if the name is not known.
     */
    public String getNameForId(String id) {
        return nameForId.getOrDefault(id, id);
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