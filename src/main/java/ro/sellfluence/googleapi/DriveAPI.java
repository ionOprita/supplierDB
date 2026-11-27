package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import org.jspecify.annotations.Nullable;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.googleapi.Credentials.getCredentials;

public class DriveAPI {
    private static final Logger logger = Logs.getConsoleAndFileLogger("DriveAPI", INFO, 10, 1_000_000);

    private static final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private static final Map<String, DriveAPI> nameToAPI = new HashMap<>();

    private final String appName;
    private final @Nullable Drive service;

    private final Map<String, String> nameForId = new HashMap<>();

    /**
     * Initialize the drive API.
     *
     * @param appName name of the application
     *                as registered in the <a href="https://console.cloud.google.com/apis/credentials/consent">console</a>
     */
    private DriveAPI(String appName) {
        this.appName = appName;
        this.service = null;
    }

    DriveAPI(String appName, HttpTransport transport, HttpRequestInitializer initializer) {
        this.appName = appName;
        this.service = driveService(appName, transport, initializer);
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
     * Download a file to a given output stream.
     *
     * @param file file as returned by {@link #findFiles(Pattern, String)}.
     * @param out  output stream.
     * @throws IOException if something goes wrong.
     */
    public void download(FileResult file, OutputStream out) throws IOException {
        setupDriveService().files()
                .get(file.fileId)
                .executeMediaAndDownloadTo(out);
    }

    /**
     * Export a file to a given output stream.
     *
     * @param file     file as returned by {@link #findFiles(Pattern, String)}.
     * @param mimeType desired mime type.
     * @param out      output stream.
     * @throws IOException if something goes wrong.
     */
    public void exportAs(FileResult file, String mimeType, OutputStream out) throws IOException {
        setupDriveService().files()
                .export(file.fileId, mimeType)
                .executeMediaAndDownloadTo(out);
    }

    /**
     * Looks up an exact file name across the currently visible, non-trashed Drive files.
     *
     * @param name of the file.
     * @return the unique file ID, or null if no visible file has that name.
     * @throws IllegalArgumentException if multiple files match or Drive's search is incomplete.
     */
    public @Nullable String getFileId(String name) {
        try {
            return lookupFileId(name);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't look up Drive file: " + name, e);
        }
    }

    /** Checked lookup for callers that handle Google's HTTP errors directly. */
    @Nullable String lookupFileId(String name) throws IOException {
        Objects.requireNonNull(name);
        var drive = setupDriveService();
        String escaped = name.replace("\\", "\\\\").replace("'", "\\'");
        String query = "trashed = false and name = '" + escaped + "'";
        var matches = new HashSet<String>();
        String pageToken = null;
        do {
            var page = drive.files().list().setQ(query).setCorpora("allDrives")
                    .setIncludeItemsFromAllDrives(true).setSupportsAllDrives(true)
                    .setFields("nextPageToken,incompleteSearch,files(id,name,trashed)")
                    .setPageToken(pageToken).execute();
            if (Boolean.TRUE.equals(page.getIncompleteSearch())) {
                throw new IllegalArgumentException("Google Drive returned an incomplete search for file: " + name);
            }
            if (page.getFiles() != null) {
                for (var file : page.getFiles()) {
                    if (name.equals(file.getName()) && !Boolean.TRUE.equals(file.getTrashed())) {
                        if (file.getId() == null) throw new IOException("Missing ID for Drive file: " + name);
                        matches.add(file.getId());
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("%d visible files have the exact name: %s".formatted(matches.size(), name));
        }
        if (matches.isEmpty()) {
            logger.log(INFO, "Found no file with the name %s.".formatted(name));
            return null;
        }
        var id = matches.iterator().next();
        nameForId.put(id, name);
        logger.log(INFO, "Found file %s with the ID %s.".formatted(name, id));
        return id;
    }

    public record FileResult(String name, String fileId) {
    }

    /**
     * File information returned by {@link #findFiles()}.
     *
     * @param name      file name.
     * @param fullPath  path including the file name, using {@code /} as the separator.
     * @param owner     owner's email address or display name.
     * @param sharedWith users, groups, domains, or public access entries the file is shared with.
     * @param currentUserPermission the current user's effective permission, such as Owner,
     *                              Editor, Commenter, Viewer, Organizer, or File organizer.
     * @param fileId    Drive file ID.
     */
    public record DetailedFileResult(String name, String fullPath, String owner,
                                     List<String> sharedWith, String currentUserPermission,
                                     String fileId) {
    }

    /**
     * Find every non-trashed file visible to the current Drive user.
     *
     * <p>This overload deliberately has no name pattern or Drive query filter. In addition to
     * the file name and ID, it resolves the parent folders and returns ownership and sharing
     * information.</p>
     *
     * @return all visible non-trashed files.
     * @throws RuntimeException if something goes wrong.
     */
    public List<DetailedFileResult> findFiles() {
        try {
            var drive = setupDriveService();
            var currentUser = drive.about().get()
                    .setFields("user(emailAddress)")
                    .execute()
                    .getUser();
            var currentUserEmail = currentUser == null ? null : currentUser.getEmailAddress();
            var allFiles = new LinkedHashMap<String, File>();
            String pageToken = null;
            do {
                FileList fileList = drive.files().list()
                        .setIncludeItemsFromAllDrives(true)
                        .setSupportsAllDrives(true)
                        .setFields("nextPageToken, files(id, name, mimeType, parents, trashed, ownedByMe, owners(displayName,emailAddress), permissions(type,role,emailAddress,displayName), capabilities(canEdit,canComment))")
                        .setPageToken(pageToken)
                        .execute();
                pageToken = fileList.getNextPageToken();
                for (var file : fileList.getFiles()) {
                    if (!file.getTrashed()) {
                        allFiles.put(file.getId(), file);
                    }
                }
            } while (pageToken != null);

            return allFiles.values().stream()
                    .map(file -> new DetailedFileResult(
                            file.getName(),
                            buildFullPath(file, allFiles),
                            ownerOf(file),
                            sharedWith(file),
                            currentUserPermission(file, currentUserEmail),
                            file.getId()))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't retrieve the list of files.", e);
        }
    }

    private String buildFullPath(File file, Map<String, File> filesById) {
        var path = new ArrayList<String>();
        var current = file;
        var visited = new HashSet<String>();
        while (current != null && visited.add(current.getId())) {
            path.addFirst(current.getName());
            var parents = current.getParents();
            var parentId = parents == null || parents.isEmpty() ? null : parents.getFirst();
            current = parentId == null ? null : filesById.getOrDefault(parentId, new File()
                    .setId(parentId)
                    .setName(parentId));
        }
        return "/" + String.join("/", path);
    }

    private String ownerOf(File file) {
        var owners = file.getOwners();
        if (owners == null || owners.isEmpty()) {
            return null;
        }
        return permissionName(owners.getFirst().getEmailAddress(), owners.getFirst().getDisplayName(), "owner");
    }

    private List<String> sharedWith(File file) {
        var result = new ArrayList<String>();
        if (file.getPermissions() != null) {
            file.getPermissions().stream()
                    .filter(permission -> !"owner".equals(permission.getRole()))
                    .map(permission -> permissionName(permission.getEmailAddress(), permission.getDisplayName(), permission.getType()))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private String currentUserPermission(File file, String currentUserEmail) {
        if (Boolean.TRUE.equals(file.getOwnedByMe())) {
            return "Owner";
        }
        if (currentUserEmail != null && file.getPermissions() != null) {
            var matchingPermission = file.getPermissions().stream()
                    .filter(permission -> currentUserEmail.equalsIgnoreCase(permission.getEmailAddress()))
                    .findFirst();
            if (matchingPermission.isPresent()) {
                return permissionLabel(matchingPermission.get().getRole());
            }
        }
        var capabilities = file.getCapabilities();
        if (capabilities != null) {
            if (Boolean.TRUE.equals(capabilities.getCanEdit())) {
                return "Editor";
            }
            if (Boolean.TRUE.equals(capabilities.getCanComment())) {
                return "Commenter";
            }
        }
        return "Viewer";
    }

    private String permissionLabel(String role) {
        return switch (role == null ? "" : role) {
            case "owner" -> "Owner";
            case "organizer" -> "Organizer";
            case "fileOrganizer" -> "File organizer";
            case "writer" -> "Editor";
            case "commenter" -> "Commenter";
            case "reader" -> "Viewer";
            default -> role;
        };
    }

    private String permissionName(String emailAddress, String displayName, String fallback) {
        if (emailAddress != null && !emailAddress.isBlank()) {
            return emailAddress;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return fallback;
    }

    /**
     * Find the d of a file given its name.
     *
     * @param pattern      for the file name.
     * @param googleFilter optional filter to be passed to setQ(). Can be null.
     * @return the ID of the file.
     * @throws RuntimeException if something goes wrong.
     */
    public List<FileResult> findFiles(Pattern pattern, String googleFilter) {
        Objects.requireNonNull(pattern);
        try {
            var matchingFiles = new HashSet<File>();
            String pageToken = null;
            do {
                var list = setupDriveService().files().list();
                if (googleFilter != null) {
                    list = list.setQ(googleFilter);
                }
                FileList fileList = list.setFields("nextPageToken, files(id, name, trashed)").setPageToken(pageToken).execute();
                pageToken = fileList.getNextPageToken();
                var files = fileList.getFiles();
                files.stream()
                        .filter(f -> pattern.matcher(f.getName()).matches() && !f.getTrashed())
                        .forEach(matchingFiles::add);
            } while (pageToken != null);
            return matchingFiles.stream().map(file -> new FileResult(file.getName(), file.getId())).toList();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't retrieve the list of files.", e);
        }
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
        if (service != null) return service;
        try {
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            HttpRequestInitializer requestInitializer =
                    new HttpCredentialsAdapter(getCredentials(httpTransport));
            return driveService(appName, httpTransport, requestInitializer);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Couldn't set up Drive service", e);
        }
    }

    private static Drive driveService(String appName, HttpTransport transport, HttpRequestInitializer initializer) {
        return new Drive.Builder(transport, jsonFactory, initializer).setApplicationName(appName).build();
    }
}
