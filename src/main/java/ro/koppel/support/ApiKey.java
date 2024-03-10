package ro.koppel.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Access API keys by an alias.
 *
 * <p>The API keys are stored in a file named <code>Secrets/apikeys.txt</code> in the users home directory.
 * Obviously, the file should have read permission for the owner only.</p>
 * <p>
 * The file contains lines with two fields separated by a tabulator character.
 * The first field is considered to be an alias by which the key can be retrieved.
 * The second field is the key associated with the alias.
 */
public class ApiKey {
    private String alias;
    private String key;

    private static final Path path = Paths.get(System.getProperty("user.home"))
            .resolve("Secrets")
            .resolve("apikeys.txt");

    /**
     * Private constructor.
     *
     * @param alias
     * @param key
     */
    private ApiKey(String alias, String key) {
        this.alias = alias;
        this.key = key;
    }

    /**
     * Convert a line from the file into an ApiKey.
     *
     * @param line string with one tabulator character separating it into two fields.
     * @return ApiKey created from the line.
     * @throws IllegalArgumentException if the line does not have a single tabulator character separating it into two fields.
     * @throws NullPointerException     if the line is null.
     */
    private static ApiKey fromString(String line) {
        Objects.requireNonNull(line);
        var fields = line.trim().split("\\t");
        if (fields.length != 2) {
            throw new IllegalArgumentException("Line must have exactly two fields spearated by a TAB character");
        }
        return new ApiKey(fields[0], fields[1]);
    }

    /**
     * Read the key associated with the given alias.
     *
     * @param alias of the key.
     * @return ApiKey or null if the alias is not present.
     * @throws RuntimeException if the alias is found on more than one line.
     */
    public static String getKey(String alias) {
        if (alias == null) {
            return null;
        }
        var keys = getAll()
                .filter(apiKey -> apiKey.alias.equals(alias))
                .toList();
        var count = keys.size();
        if (count == 1) {
            return keys.getFirst().key;
        } else if (count == 0) {
            return null;
        } else {
            throw new RuntimeException(
                    "%s contains %d keys with the same alias '%s'"
                            .formatted(path, count, alias)
            );
        }
    }

    /**
     * Read all lines from the file and convert them into a list of ApiKey instances.
     *
     * @return stream of ApiKey or empty stream if the file is not readable.
     */
    private static Stream<ApiKey> getAll() {
        try {
            return Files.readAllLines(path).stream()
                    .filter(s -> !s.isBlank())
                    .map(ApiKey::fromString);
        } catch (IOException e) {
            return Stream.empty();
        }
    }
}
