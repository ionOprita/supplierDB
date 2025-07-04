package ro.sellfluence.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.lang.ProcessBuilder.Redirect.INHERIT;

/**
 * Class, which executes all the single applications.
 */
public class EmagBot {
    public static void main(String[] args) {
        System.out.println("Backup the eMag mirror database.");
        backupDB();
        System.out.println("Syncing the eMag mirror database.");
        EmagDBApp.main(new String[0]);
        try {
            System.out.println("Transfer orders from the database to the date comenzi sheet.");
            PopulateDateComenziFromDB.main(new String[0]);
        } catch (IOException | SQLException e) {
            throw new RuntimeException("PopulateDateComenzi endet with an exception ", e);
        }
        System.out.println("Update the sheet used for customer feedback.");
        UpdateEmployeeSheetsFromDB.main(new String[0]);
    }

    /**
     * Create a backup of the emag database.
     */
    private static void backupDB() {
        var pg_dump = findPGDump();
        if (pg_dump == null) {
            System.out.println("Could not find the pg_dump executable. No backup will be performed.");
        } else {
            var backupDir = Paths.get(System.getProperty("user.home")).resolve("Backups");
            try {
                Files.createDirectories(backupDir);
                var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(LocalDateTime.now());
                var backupPath = backupDir.resolve("db_emag_"+timestamp+".dump").toString();
                try {
                    System.out.println("Executing pg_dump to " + backupPath);
                    var process = new ProcessBuilder(pg_dump.toString(), "-Fc", "-f", backupPath, "emag")
                            .redirectOutput(INHERIT)
                            .redirectErrorStream(true)
                            .start();
                    System.out.println("Waiting for the pg_dump executable to finish");
                    process.waitFor();
                    System.out.println("pg_dump finished");
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException("Could not execute pg_dump", e);
                }
            } catch (IOException e) {
                System.out.println("Could not create the backup directory " + backupDir);
            }
        }
    }

    /**
     * Search for the pg_dump executable.
     *
     * @return the path to the pg_dump executable or null if not found.
     */
    private static Path findPGDump() {
        Path postgresDir = Paths.get("C:\\Program Files", "PostgreSQL");
        try (var stream = Files.list(postgresDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().matches("\\d+"))
                    .sorted()
                    .reduce((_, second) -> second)
                    .map(p -> p.resolve("bin").resolve("pg_dump.exe"))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

}