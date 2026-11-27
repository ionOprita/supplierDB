package ro.sellfluence.googleapi;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
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

class StockImportSheetReaderTest {
    private static final String NAME = "S. Stocuri";
    private static final String BOOK = "resolved-book";
    private static final int TAB_ID = 7123;
    private static final String SHEET = "application/vnd.google-apps.spreadsheet";
    private static final List<String> EXPECTED_RANGES = List.of(
            "'Importuri'!A1:F88", "'Importuri'!G1:QD88", "'Importuri'!QE1:QO88",
            "'Date Prod.'!A1:M25", "'Tabel Meas. CTN.'!A1:AD30", "'Setari'!B23:C27", "'Cons. Date Secundare'!E2:E3"
    );

    @Test
    void openingReadOnlyKeepsItsCredentialsSeparateFromTheCachedWorkbook() throws IOException {
        var api = new Fixture();
        api.page(Map.of("files", List.of(file(BOOK, SHEET, false))));
        var file = new DriveAPI.FileResult(NAME, BOOK);
        String appName = "read-only-cache-test";
        var cached = SheetsAPI.getSpreadSheetByFileResult(appName, file);
        var readOnly = SheetsAPI.getReadOnlySpreadSheetByName(appName, NAME, api, null);

        assertNotNull(readOnly);
        assertEquals(cached.getSpreadSheetId(), readOnly.getSpreadSheetId());
        assertNotSame(cached, readOnly);
        assertSame(cached, SheetsAPI.getSpreadSheetByFileResult(appName, file));
    }

    @Test
    void usesResolvedFileAndTabIdsAndReadsAllTypedRangesInOneBatch() throws IOException {
        var api = new Fixture();
        api.page(Map.of("files", List.of(), "nextPageToken", "next +/="));
        api.page(Map.of("files", List.of(file(BOOK, SHEET, false))));
        var result = api.reader().read(NAME);

        assertEquals(BOOK, result.spreadsheetId());
        assertEquals(TAB_ID, result.tabId());
        assertEquals(List.of("lookup", "lookup", "metadata", "values"), api.requests);
        assertEquals(List.of("next +/="), params(api.driveRequests.getLast(), "pageToken"));
        assertFalse(params(api.driveRequests.getFirst(), "q").getFirst().contains("mimeType"));

        var snapshot = result.snapshot();
        assertEquals(List.of(1, 7, 447), snapshot.imports().stream().map(StockImportSheetReader.Range::firstColumn).toList());
        var header = snapshot.imports().getFirst().rows().getFirst();
        assertEquals(0, ((Number) header.get(0)).intValue());
        assertEquals(46143, ((Number) header.get(1)).intValue());
        assertEquals("", header.get(2));
        assertEquals(false, header.get(3));
        assertTrue(snapshot.imports().get(1).rows().isEmpty(), "A Google range without values stays empty");
        assertEquals(List.of(List.of("grid 2")), snapshot.imports().getLast().rows());
        assertEquals(List.of(List.of("grid 3")), snapshot.products());
        assertEquals(List.of(List.of("grid 4")), snapshot.cartons());
        assertEquals(List.of(List.of("grid 5")), snapshot.transit());
        assertEquals(List.of(List.of("grid 6")), snapshot.vat());
    }

    @Test
    void duplicateWithDifferentTypeAndOwnershipStopsBeforeOpeningSheets() {
        var api = new Fixture();
        api.page(Map.of("files", List.of(file("owned", SHEET, true)), "nextPageToken", "p2"));
        api.page(Map.of("files", List.of(file("other", "application/pdf", false))));
        var error = assertThrows(IllegalArgumentException.class, () -> api.reader().read(NAME));
        assertTrue(error.getMessage().contains(NAME));
        assertEquals(List.of("lookup", "lookup"), api.requests);
    }

    @Test
    void missingWorkbookNamesTheSourceAndExplainsViewerAccess() {
        var api = new Fixture();
        api.page(Map.of("files", List.of()));
        var error = assertThrows(IllegalArgumentException.class, () -> api.reader().read(NAME));
        assertTrue(error.getMessage().contains(NAME));
        assertTrue(error.getMessage().contains("Viewer"));
        assertEquals(List.of("lookup"), api.requests);
    }

    @Test
    void incompleteDriveSearchStopsBeforeOpeningSheets() {
        var api = new Fixture();
        api.page(Map.of("files", List.of(file(BOOK, SHEET, true)), "incompleteSearch", true));
        var error = assertThrows(IllegalArgumentException.class, () -> api.reader().read(NAME));
        assertTrue(error.getMessage().contains(NAME));
        assertEquals(List.of("lookup"), api.requests);
    }

    @Test
    void aUniqueNonSpreadsheetReachesSheetsAndPropagatesItsCheckedError() {
        var api = new Fixture();
        api.page(Map.of("files", List.of(file(BOOK, "application/pdf", true))));
        api.failingRequest = "metadata";
        api.failureCode = 400;
        var error = assertThrows(GoogleJsonResponseException.class, () -> api.reader().read(NAME));
        assertEquals(400, error.getStatusCode());
        assertEquals(List.of("lookup", "metadata"), api.requests);
    }

    @Test
    void deniedReadAccessRemainsAvailableToTheImportersViewerDiagnostic() {
        for (var request : List.of("lookup", "metadata", "values")) {
            var api = new Fixture();
            api.page(Map.of("files", List.of(file(BOOK, SHEET, false))));
            api.failingRequest = request;
            var error = assertThrows(GoogleJsonResponseException.class, () -> api.reader().read(NAME), request);
            assertEquals(403, error.getStatusCode());
            assertEquals(request, api.requests.getLast());
        }
    }

    @Test
    void tabNameAndMinimumGridDimensionsAreCheckedBeforeReadingValues() {
        for (String problem : List.of("renamed", "too narrow")) {
            var api = new Fixture();
            api.page(Map.of("files", List.of(file(BOOK, SHEET, false))));
            api.importTab = "renamed".equals(problem) ? tab("Previous imports", TAB_ID, 100, 457)
                    : tab("Importuri", TAB_ID, 100, 16);
            var error = assertThrows(IllegalArgumentException.class, () -> api.reader().read(NAME));
            assertTrue(error.getMessage().contains("Importuri"));
            assertEquals(List.of("lookup", "metadata"), api.requests);
        }
    }

    @Test
    void truncatedBatchCannotShiftLookupTablesIntoTheWrongInputs() {
        var api = new Fixture();
        api.page(Map.of("files", List.of(file(BOOK, SHEET, false))));
        api.truncateBatch = true;
        assertThrows(IOException.class, () -> api.reader().read(NAME));
        assertEquals(List.of("lookup", "metadata", "values"), api.requests);
    }

    private static Map<String, Object> file(String id, String mime, boolean owned) {
        return Map.of("id", id, "name", NAME, "mimeType", mime, "ownedByMe", owned, "trashed", false);
    }

    private static Map<String, Object> tab(String title, int id, int rows, int cols) {
        return Map.of("properties", Map.of("title", title, "sheetId", id,
                "gridProperties", Map.of("rowCount", rows, "columnCount", cols)));
    }

    private static List<String> params(URI uri, String key) {
        return Arrays.stream(uri.getRawQuery().split("&")).filter(part -> part.startsWith(key + "="))
                .map(part -> URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8)).toList();
    }

    private static class Fixture extends MockHttpTransport {
        final List<String> requests = new ArrayList<>();
        final List<URI> driveRequests = new ArrayList<>();
        final ArrayDeque<Map<String, ?>> pages = new ArrayDeque<>();
        final Gson json = new Gson();
        Map<String, Object> importTab = tab("Importuri", TAB_ID, 100, 457);
        String failingRequest;
        int failureCode = 403;
        boolean truncateBatch;

        void page(Map<String, ?> content) {
            pages.add(content);
        }

        StockImportSheetReader reader() {
            return new StockImportSheetReader(name ->
                    SheetsAPI.getReadOnlySpreadSheetByName("offline-lookup-test", name, this, null));
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            assertEquals("GET", method);
            var uri = URI.create(url);
            String request;
            Object payload;
            switch (uri.getPath()) {
                case "/drive/v3/files" -> {
                    request = "lookup";
                    driveRequests.add(uri);
                    payload = pages.removeFirst();
                }
                case "/v4/spreadsheets/" + BOOK -> {
                    request = "metadata";
                    payload = Map.of("sheets", List.of(tab("Date Prod.", 2, 25, 13), importTab,
                            tab("Tabel Meas. CTN.", 3, 30, 30), tab("Setari", 4, 30, 3),
                            tab("Cons. Date Secundare", 5, 3, 5)));
                }
                case "/v4/spreadsheets/" + BOOK + "/values:batchGet" -> {
                    request = "values";
                    assertEquals(EXPECTED_RANGES, params(uri, "ranges"));
                    assertEquals(List.of("ROWS"), params(uri, "majorDimension"));
                    assertEquals(List.of("UNFORMATTED_VALUE"), params(uri, "valueRenderOption"));
                    assertEquals(List.of("SERIAL_NUMBER"), params(uri, "dateTimeRenderOption"));
                    var values = new ArrayList<Map<String, Object>>();
                    values.add(Map.of("values", List.of(List.of(0, 46143, "", false))));
                    values.add(Map.of("range", EXPECTED_RANGES.get(1)));
                    int count = EXPECTED_RANGES.size() - (truncateBatch ? 1 : 0);
                    for (int i = 2; i < count; i++) values.add(Map.of("values", List.of(List.of("grid " + i))));
                    payload = Map.of("valueRanges", values);
                }
                default -> throw new AssertionError("Unexpected Google request: " + uri.getPath());
            }
            requests.add(request);
            boolean fail = request.equals(failingRequest);
            var body = json.toJson(fail ? Map.of("error", Map.of("code", failureCode, "message", "Read failed")) : payload);
            return new MockLowLevelHttpRequest(url) {
                @Override public LowLevelHttpResponse execute() {
                    return new MockLowLevelHttpResponse().setStatusCode(fail ? failureCode : 200)
                            .setContentType("application/json").setContent(body);
                }
            };
        }
    }
}
