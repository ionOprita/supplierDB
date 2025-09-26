package ro.sellfluence.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Update emagTest from the latest backup dron from Ionut database.
 */
public class UpdateTestDB {
    static void main() throws Exception {
        var backupDir = Paths.get(System.getProperty("user.home")).resolve("Backups").resolve("postgres");
        var backupFile = Files.list(backupDir).filter(p -> p.getFileName().toString().startsWith("db_emag_ionut")).sorted().toList().getLast();
        IO.println("Updating emagTest from %s".formatted(backupFile));
        var rc= new ProcessBuilder("psql", "-c", "DROP DATABASE emag_test").inheritIO().start().waitFor();
        IO.println("RC=%d".formatted(rc));
        rc= new ProcessBuilder("psql", "-c", "CREATE DATABASE emag_test WITH OWNER = emag_test TEMPLATE template0").inheritIO().start().waitFor();
        IO.println("RC=%d".formatted(rc));
        rc= new ProcessBuilder("pg_restore", "-d", "emag_test", "-1", "-c", "-O", "--if-exists", "--role=emag_test", backupFile.toString()).inheritIO().start().waitFor();
        IO.println("RC=%d".formatted(rc));
    }
}