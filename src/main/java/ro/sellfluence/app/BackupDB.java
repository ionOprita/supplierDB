package ro.sellfluence.app;

import ch.claudio.db.DBPass;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.lang.ProcessBuilder.Redirect.INHERIT;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

public class BackupDB {

    public static void main(String[] args) {
        var dbAlias = new Arguments(args).getOption(databaseOptionName, defaultDatabase);
        System.out.printf("Back up the %s database.%n", dbAlias);
        backupDB(dbAlias);
        System.out.println("Backup finished.");
    }

    /**
     * Create a backup of the emag database.
     */
    public static void backupDB(String dbAlias) {
        var pg_dump = findPGDump();
        if (pg_dump == null) {
            System.out.println("Could not find the pg_dump executable. No backup will be performed.");
        } else {
            var backupDir = Paths.get(System.getProperty("user.home"))
                    .resolve("Desktop")
                    .resolve("supplierDB")
                    .resolve("supplierDB")
                    .resolve("Backups");
            try {
                var dbInfo = DBPass.findDB(dbAlias);
                var dbName = dbInfo.dbName();
                Files.createDirectories(backupDir);
                var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(LocalDateTime.now());
                var backupPath = backupDir.resolve("db_%s_%s.dump".formatted(dbName, timestamp)).toString();
                try {
                    System.out.printf("Executing pg_dump of %s to %s%n", dbName, backupPath);
                    var pb = new ProcessBuilder(pg_dump.toString(), "-Fc", "-f", backupPath, "-U", dbInfo.user(), dbName)
                            .redirectOutput(INHERIT)
                            .redirectErrorStream(true);
                    pb.environment().put("PGPASSWORD", dbInfo.pw());
                    var process = pb.start();
                    System.out.println("Waiting for the pg_dump command to finish");
                    process.waitFor();
                    System.out.println("pg_dump finished");
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException("Could not execute pg_dump", e);
                }
            } catch (IOException e) {
                System.out.printf("Could not create the backup directory %s.%n", backupDir);
            }
        }
    }

    /**
     * Search for the pg_dump executable.
     *
     * <p><b>Note:</b> This version works only on Windows.</p>
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