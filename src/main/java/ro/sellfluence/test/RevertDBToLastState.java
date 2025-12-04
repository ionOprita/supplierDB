package ro.sellfluence.test;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Revert the database to an earlier backup.
 */
public class RevertDBToLastState {
    static void main() throws Exception {
        var backupDir = Paths.get(System.getProperty("user.home")).resolve("Backups").resolve("postgres");
        var backupFile = Files.list(backupDir).filter(p -> p.getFileName().toString().startsWith("db_emag_2025-11-26T05")).sorted().toList().getLast();
        IO.println("Reverting emag to %s".formatted(backupFile));
        var rc= new ProcessBuilder("psql", "-c", "DROP DATABASE emag").inheritIO().start().waitFor();
        IO.println("RC=%d".formatted(rc));
        rc= new ProcessBuilder("psql", "-c", "CREATE DATABASE emag WITH OWNER = emag TEMPLATE template0").inheritIO().start().waitFor();
        IO.println("RC=%d".formatted(rc));
        rc= new ProcessBuilder("pg_restore", "-d", "emag", "-1", "-c", "-O", "--if-exists", "--role=emag", backupFile.toString()).inheritIO().start().waitFor();
        IO.println("RC=%d".formatted(rc));
    }
}