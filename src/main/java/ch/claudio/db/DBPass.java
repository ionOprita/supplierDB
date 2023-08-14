package ch.claudio.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class DBPass {
    public final String alias;
    public final String connect;
    public final String user;
    public final String pw;

    private DBPass(String alias, String connect, String user, String pw) {
        this.alias = alias;
        this.connect = connect;
        this.user = user;
        this.pw = pw;
    }

    /**
     * Create an instance from a line in the password file.
     */
    private static DBPass fromString(String line) {
        String[] fields = line.trim().split("\t");
        int count = fields.length;
        if (count <= 1) {
            throw new IllegalArgumentException("At least 2 fields expected");
        }
        return new DBPass(fields[0], fields[1], count > 2 ? fields[2] : null, count > 3 ? fields[3] : null);
    }

    /**
     * Uses [getAll] and then filters the line for the database with given [alias].
     * <p>
     * It will throw an exception if the alias is not present or if the file
     * has more than one line with the same alias.
     */
    public static DBPass findDB(String alias) throws IOException {
        List<DBPass> allDBPasses = getAll();
        return allDBPasses.stream()
                .filter(dbPass -> dbPass.alias.equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This alias was not found: " + alias));
    }

    /**
     * Return all entries from the file ~/dbpass.txt
     */
    public static List<DBPass> getAll() throws IOException {
        Path dbPassFilePath = Path.of(System.getProperty("user.home")).resolve("dbpass.txt");
        try (var stream = Files.lines(dbPassFilePath)) {
            return stream
                    .filter(line -> !line.trim().isEmpty())
                    .map(DBPass::fromString)
                    .toList();
        }
    }
}

