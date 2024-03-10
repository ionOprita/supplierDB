package ro.koppel.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Access user passwords by an alias.
 *
 * <p>The values are stored in a file named <code>Secrets/userpws.txt</code> in the users home directory.
 * Obviously, the file should have read permission for the owner only.</p>
 * <p>
 * The file contains lines with three fields separated by a tabulator character.
 * The first field is considered to be an alias by which the values can be retrieved.
 * The second field is the username associated with the alias.
 * The third field is the password associated with the alias.
 */
public class UserPassword {
    private String alias;
    private String username;
    private String password;

    private static final Path path = Paths.get(System.getProperty("user.home"))
            .resolve("Secrets")
            .resolve("userpws.txt");

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Read the username associated with the given alias.
     *
     * @param alias of the key.
     * @return ApiKey or null if the alias is not present.
     * @throws RuntimeException if the alias is found on more than one line.
     */
    public static UserPassword findAlias(String alias) {
        if (alias == null) {
            return null;
        }
        var values = getAll()
                .filter(apiKey -> apiKey.alias.equals(alias))
                .toList();
        var count = values.size();
        if (count == 1) {
            return values.getFirst();
        } else if (count == 0) {
            return null;
        } else {
            throw new RuntimeException(
                    "%s contains %d keys with the same alias '%s'"
                            .formatted(path, count, alias)
            );
        }
    }

    private UserPassword(String alias, String username, String password) {
        this.alias = alias;
        this.username = username;
        this.password = password;
    }

    /**
     * Convert a line from the file into an UserPassword.
     *
     * @param line string with two tabulator characters separating it into three fields.
     * @return UserPassword created from the line.
     * @throws IllegalArgumentException if the line does not have two tabulator characters separating it into three fields.
     * @throws NullPointerException     if the line is null.
     */
    private static UserPassword fromString(String line) {
        Objects.requireNonNull(line);
        var fields = line.trim().split("\\t");
        if (fields.length != 3) {
            throw new IllegalArgumentException("Line must have exactly three fields spearated by a TAB character");
        }
        return new UserPassword(fields[0], fields[1], fields[2]);
    }

    /**
     * Read all lines from the file and convert them into a list of ApiKey instances.
     *
     * @return stream of ApiKey or empty stream if the file is not readable.
     */
    private static Stream<UserPassword> getAll() {
        try {
            return Files.readAllLines(path).stream()
                    .filter(s -> !s.isBlank())
                    .map(UserPassword::fromString);
        } catch (IOException e) {
            return Stream.empty();
        }
    }
}
