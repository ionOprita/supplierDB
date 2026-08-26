package ro.sellfluence.app;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLogFilesTest {
    @TempDir
    Path logDirectory;

    @Test
    void listsOnlyDirectRegularFilesNewestFirstThenByName() throws IOException {
        Instant older = Instant.parse("2026-07-27T10:15:30Z");
        Instant newer = Instant.parse("2026-07-28T10:15:30Z");
        Path olderFile = writeFile("older.log", "old", older);
        Path firstNewerFile = writeFile("a newer.log", "new-a", newer);
        Path secondNewerFile = writeFile("b-newer.log", "new-b", newer);
        Path nestedDirectory = Files.createDirectory(logDirectory.resolve("nested"));
        Files.writeString(nestedDirectory.resolve("hidden.log"), "hidden");

        List<ServerLogFiles.Entry> entries = new ServerLogFiles(logDirectory).list();

        assertEquals(
                List.of("a newer.log", "b-newer.log", "older.log"),
                entries.stream().map(ServerLogFiles.Entry::name).toList()
        );
        assertEquals("a%20newer.log", entries.getFirst().encodedName());
        assertEquals(Files.size(firstNewerFile), entries.getFirst().size());
        assertEquals(newer, entries.getFirst().lastModified());
        assertEquals(Files.size(secondNewerFile), entries.get(1).size());
        assertEquals(Files.size(olderFile), entries.getLast().size());
        assertThrows(UnsupportedOperationException.class, () -> entries.add(entries.getFirst()));
    }

    @Test
    void resolvesOnlyAnExistingDirectRegularFile() throws IOException {
        Path file = Files.writeString(logDirectory.resolve("java app.log"), "contents");
        Files.createDirectory(logDirectory.resolve("directory.log"));
        var logFiles = new ServerLogFiles(logDirectory);

        assertEquals(file.toAbsolutePath().normalize(), logFiles.resolve("java app.log").orElseThrow());
        assertTrue(logFiles.resolve("missing.log").isEmpty());
        assertTrue(logFiles.resolve("directory.log").isEmpty());
    }

    @Test
    void rejectsUnsafeFileNames() throws IOException {
        Files.writeString(logDirectory.resolve("available.log"), "contents");
        var logFiles = new ServerLogFiles(logDirectory);
        var unsafeNames = List.of(
                "",
                " ",
                ".",
                "..",
                "../secret.log",
                "nested/secret.log",
                "nested\\secret.log",
                "/absolute.log",
                "C:\\absolute.log",
                "C:drive-relative.log",
                "stream.log:secret",
                "line\nbreak.log"
        );

        assertTrue(logFiles.resolve(null).isEmpty());
        for (String unsafeName : unsafeNames) {
            assertTrue(logFiles.resolve(unsafeName).isEmpty(), unsafeName);
        }
    }

    @Test
    void neitherListsNorResolvesSymbolicLinksWhenSupported() throws IOException {
        Path root = Files.createDirectory(logDirectory.resolve("logs"));
        Path target = Files.writeString(logDirectory.resolve("outside.log"), "secret");
        Path link = root.resolve("linked.log");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("Symbolic links are not supported: " + e.getMessage());
        }

        var logFiles = new ServerLogFiles(root);

        assertFalse(logFiles.list().stream().anyMatch(entry -> entry.name().equals("linked.log")));
        assertTrue(logFiles.resolve("linked.log").isEmpty());
    }

    @Test
    void buildsSafeContentDispositionForUnicodeAndPunctuation() {
        String header = ServerLogFiles.attachmentContentDisposition("java \"Δ\" log.log");

        assertEquals(
                "attachment; filename=\"java_____log.log\"; "
                        + "filename*=UTF-8''java%20%22%CE%94%22%20log.log",
                header
        );
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerLogFiles.attachmentContentDisposition("bad\r\nname.log")
        );
    }

    @Test
    void selectsExplicitDirectoryBeforeAnyFallback() throws IOException {
        Path configuredDirectory = Files.createDirectory(logDirectory.resolve("configured"));
        Path javaServerDirectory = Files.createDirectory(logDirectory.resolve("JavaServer"));
        Path javaTempDirectory = Files.createDirectory(javaServerDirectory.resolve("tmp"));
        Path applicationDirectory = Files.createDirectory(javaServerDirectory.resolve("app"));
        Files.createDirectory(javaServerDirectory.resolve("logs"));
        Path applicationLogDirectory = Files.createDirectory(javaTempDirectory.resolve("EmagDBLogs"));

        assertEquals(
                configuredDirectory.toAbsolutePath().normalize(),
                ServerLogFiles.selectDirectory(
                        configuredDirectory,
                        javaTempDirectory,
                        applicationDirectory,
                        applicationLogDirectory
                )
        );
    }

    @Test
    void discoversSupervisorLogsBesideConfiguredJavaTempDirectory() throws IOException {
        Path javaServerDirectory = Files.createDirectory(logDirectory.resolve("JavaServer"));
        Path javaTempDirectory = Files.createDirectory(javaServerDirectory.resolve("tmp"));
        Path applicationDirectory = Files.createDirectory(javaServerDirectory.resolve("app"));
        Path supervisorLogDirectory = Files.createDirectory(javaServerDirectory.resolve("logs"));
        Path applicationLogDirectory = Files.createDirectory(javaTempDirectory.resolve("EmagDBLogs"));

        assertEquals(
                supervisorLogDirectory.toAbsolutePath().normalize(),
                ServerLogFiles.selectDirectory(
                        null,
                        javaTempDirectory,
                        applicationDirectory,
                        applicationLogDirectory
                )
        );
    }

    @Test
    void usesApplicationLogsWhenSupervisorLayoutIsNotPresent() throws IOException {
        Path javaTempDirectory = Files.createDirectory(logDirectory.resolve("temporary-files"));
        Path applicationDirectory = Files.createDirectory(logDirectory.resolve("app"));
        Files.createDirectory(logDirectory.resolve("logs"));
        Path applicationLogDirectory = Files.createDirectory(javaTempDirectory.resolve("EmagDBLogs"));

        assertEquals(
                applicationLogDirectory.toAbsolutePath().normalize(),
                ServerLogFiles.selectDirectory(
                        null,
                        javaTempDirectory,
                        applicationDirectory,
                        applicationLogDirectory
                )
        );
    }

    private Path writeFile(String name, String contents, Instant modified) throws IOException {
        Path file = Files.writeString(logDirectory.resolve(name), contents);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }
}
