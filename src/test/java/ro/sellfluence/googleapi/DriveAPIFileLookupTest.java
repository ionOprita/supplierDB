package ro.sellfluence.googleapi;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DriveAPIFileLookupTest {
    private static final String NAME = "S. Stocuri";

    @Test void lookupCountsExactNonTrashedFilesAcrossTypesAndPages() throws IOException {
        String name = "O'Brien\\Stocuri";
        var drive = new MockDrive();
        drive.page(Map.of("nextPageToken", "p +/=", "files", List.of(
                Map.of("id", "wrong-case", "name", name.toLowerCase(java.util.Locale.ROOT)),
                Map.of("id", "in-trash", "name", name, "trashed", true))));
        drive.page(Map.of("files", List.of(
                Map.of("id", "folder", "name", name, "mimeType", "application/vnd.google-apps.folder"))));

        assertEquals("folder", drive.api.getFileId(name));
        assertEquals(2, drive.requests.size());
        for (URI request : drive.requests) {
            assertEquals("trashed = false and name = 'O\\'Brien\\\\Stocuri'", parameter(request, "q"));
            assertEquals("allDrives", parameter(request, "corpora"));
            assertEquals("true", parameter(request, "includeItemsFromAllDrives"));
            assertEquals("true", parameter(request, "supportsAllDrives"));
        }
        assertNull(parameter(drive.requests.getFirst(), "pageToken"));
        assertEquals("p +/=", parameter(drive.requests.getLast(), "pageToken"));
    }

    @Test void subsequentLookupDetectsANewDuplicateAndLaterDeletion() {
        var drive = new MockDrive();
        var api = drive.api;
        Map<String, Object> owned = Map.of("id", "existing", "name", NAME, "ownedByMe", true,
                "mimeType", "application/vnd.google-apps.spreadsheet");
        Map<String, Object> shared = Map.of("id", "later", "name", NAME, "ownedByMe", false,
                "mimeType", "application/pdf");
        drive.page(Map.of("files", List.of(owned)));
        assertEquals("existing", api.getFileId(NAME));
        assertEquals(NAME, api.getNameForId("existing"));

        drive.page(Map.of("files", List.of(owned), "nextPageToken", "next"));
        drive.page(Map.of("files", List.of(shared)));
        var conflict = assertThrows(IllegalArgumentException.class, () -> api.getFileId(NAME));
        assertTrue(conflict.getMessage().contains(NAME));

        drive.page(Map.of("files", List.of()));
        assertNull(api.getFileId(NAME));
        assertEquals(4, drive.requests.size());
    }

    @Test void repeatedFileIdAcrossPagesIsOneFile() throws IOException {
        var drive = new MockDrive();
        var match = Map.of("id", "one", "name", NAME);
        drive.page(Map.of("files", List.of(match), "nextPageToken", "second"));
        drive.page(Map.of("files", List.of(match)));
        assertEquals("one", drive.api.getFileId(NAME));
    }

    @Test void anIncompleteSearchCannotEstablishUniquenessOrAbsence() {
        for (var files : List.of(List.of(), List.of(Map.of("id", "one", "name", NAME)))) {
            var drive = new MockDrive();
            drive.page(Map.of("files", files, "incompleteSearch", true));
            var failure = assertThrows(IllegalArgumentException.class, () -> drive.api.getFileId(NAME));
            assertTrue(failure.getMessage().contains(NAME));
            assertTrue(failure.getMessage().contains("incomplete"));
        }
    }

    private static String parameter(URI request, String key) {
        return Arrays.stream(request.getRawQuery().split("&"))
                .filter(part -> part.startsWith(key + "="))
                .map(part -> URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8))
                .findFirst().orElse(null);
    }

    private static final class MockDrive extends MockHttpTransport {
        final DriveAPI api = new DriveAPI("offline-drive-lookup", this, null);
        final List<URI> requests = new ArrayList<>();
        final ArrayDeque<String> pages = new ArrayDeque<>();

        void page(Map<String, ?> response) {
            pages.add(new Gson().toJson(response));
        }

        @Override public LowLevelHttpRequest buildRequest(String method, String url) {
            assertEquals("GET", method);
            var uri = URI.create(url);
            assertEquals("/drive/v3/files", uri.getPath());
            requests.add(uri);
            return new MockLowLevelHttpRequest(url) {
                @Override public LowLevelHttpResponse execute() {
                    return new MockLowLevelHttpResponse().setContentType("application/json")
                            .setContent(pages.removeFirst());
                }
            };
        }
    }
}
