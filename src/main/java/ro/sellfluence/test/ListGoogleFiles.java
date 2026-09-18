package ro.sellfluence.test;

import ro.sellfluence.googleapi.DriveAPI;

import java.util.Comparator;

import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;

public class ListGoogleFiles {
    static void main() {
        var driveAPI = DriveAPI.getDriveAPI(defaultGoogleApp);
        var files = driveAPI.findFiles().stream().sorted(Comparator.comparing(DriveAPI.DetailedFileResult::fullPath)).toList();
        for (var file : files) {
            IO.println("%s %-20s %-50s %s".formatted(file.owner(), file.currentUserPermission(), file.fullPath(), file.sharedWith()));
        }
    }
}
