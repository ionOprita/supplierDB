package ro.sellfluence.app;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lists and resolves files that may be exposed by the server log administration page.
 */
public final class ServerLogFiles {
    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final Path root;

    public ServerLogFiles(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * Selects the configured directory, or recognizes the layout used by the Windows supervisor.
     *
     * <p>The layout fallback lets a newly pulled Java version find {@code ../logs} even when it was
     * launched by an already-running, pre-update PowerShell process which has only configured
     * {@code java.io.tmpdir=../tmp}.</p>
     */
    static Path selectDirectory(Path configuredDirectory,
                                Path javaTempDirectory,
                                Path applicationWorkingDirectory,
                                Path applicationLogDirectory) {
        if (configuredDirectory != null) {
            return configuredDirectory.toAbsolutePath().normalize();
        }

        Path normalizedTempDirectory =
                Objects.requireNonNull(javaTempDirectory, "javaTempDirectory").toAbsolutePath().normalize();
        Path normalizedWorkingDirectory =
                Objects.requireNonNull(applicationWorkingDirectory, "applicationWorkingDirectory")
                        .toAbsolutePath()
                        .normalize();
        Path tempDirectoryName = normalizedTempDirectory.getFileName();
        Path workingDirectoryName = normalizedWorkingDirectory.getFileName();
        Path deploymentRoot = normalizedTempDirectory.getParent();
        Path deploymentRootName = deploymentRoot == null ? null : deploymentRoot.getFileName();
        boolean legacySupervisorLayout = tempDirectoryName != null
                && tempDirectoryName.toString().equalsIgnoreCase("tmp")
                && workingDirectoryName != null
                && workingDirectoryName.toString().equalsIgnoreCase("app")
                && deploymentRootName != null
                && deploymentRootName.toString().equalsIgnoreCase("JavaServer")
                && deploymentRoot.equals(normalizedWorkingDirectory.getParent());
        if (legacySupervisorLayout) {
            Path supervisorLogDirectory = deploymentRoot.resolve("logs");
            if (Files.isDirectory(supervisorLogDirectory, NO_FOLLOW_LINKS)) {
                return supervisorLogDirectory;
            }
        }

        return Objects.requireNonNull(applicationLogDirectory, "applicationLogDirectory")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Metadata for one directly contained log file.
     */
    public record Entry(String name, String encodedName, long size, Instant lastModified) {
    }

    /**
     * Lists direct, regular, non-symbolic-link children of the configured directory.
     */
    public List<Entry> list() throws IOException {
        var entries = new ArrayList<Entry>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(child, BasicFileAttributes.class, NO_FOLLOW_LINKS);
                } catch (NoSuchFileException ignored) {
                    // A rotated log may disappear between enumerating the directory and reading its metadata.
                    continue;
                }
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    continue;
                }

                String name = child.getFileName().toString();
                entries.add(new Entry(
                        name,
                        urlEncode(name),
                        attributes.size(),
                        attributes.lastModifiedTime().toInstant()
                ));
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }

        entries.sort(Comparator.comparing(Entry::lastModified)
                .reversed()
                .thenComparing(Entry::name));
        return List.copyOf(entries);
    }

    /**
     * Resolves a single existing regular file directly below the configured directory.
     */
    public Optional<Path> resolve(String fileName) {
        if (!isSafeFileName(fileName)) {
            return Optional.empty();
        }

        final Path requested;
        try {
            requested = Path.of(fileName);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }

        if (requested.isAbsolute()
                || requested.getRoot() != null
                || requested.getNameCount() != 1
                || !requested.getFileName().toString().equals(fileName)) {
            return Optional.empty();
        }

        Path candidate = root.resolve(requested).normalize();
        if (!root.equals(candidate.getParent())
                || !Files.isRegularFile(candidate, NO_FOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            return Optional.empty();
        }

        return Optional.of(candidate);
    }

    /**
     * Builds a header value that safely preserves Unicode names where supported.
     */
    public static String attachmentContentDisposition(String fileName) {
        if (!isSafeFileName(fileName)) {
            throw new IllegalArgumentException("Invalid file name");
        }

        var asciiFallback = new StringBuilder(fileName.length());
        fileName.codePoints().forEach(codePoint -> {
            if (codePoint < 128 && (Character.isLetterOrDigit(codePoint)
                    || codePoint == '.'
                    || codePoint == '-'
                    || codePoint == '_')) {
                asciiFallback.appendCodePoint(codePoint);
            } else {
                asciiFallback.append('_');
            }
        });

        return "attachment; filename=\"%s\"; filename*=UTF-8''%s"
                .formatted(asciiFallback, urlEncode(fileName));
    }

    private static boolean isSafeFileName(String fileName) {
        if (fileName == null
                || fileName.isBlank()
                || fileName.equals(".")
                || fileName.equals("..")
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.indexOf(':') >= 0) {
            return false;
        }

        return fileName.codePoints().noneMatch(Character::isISOControl);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
    }
}
