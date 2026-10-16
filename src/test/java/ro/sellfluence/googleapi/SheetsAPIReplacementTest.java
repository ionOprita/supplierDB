package ro.sellfluence.googleapi;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridProperties;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SheetsAPIReplacementTest {
    private static final int TARGET_ID = 42;
    private static final String TARGET_TITLE = "Real data";
    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();

    @Test
    void uploadsAllBatchesBeforeOneCopyWithOffsetsGrowthAndDateFormats() {
        var fixture = new Fixture(5, 4);
        var rows = largeRows();

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, rows, Map.of(4, "dd/MM/yyyy"));

        var created = fixture.requests(request -> request.getAddSheet() != null);
        assertEquals(1, created.size());
        var helper = created.getFirst().getAddSheet().getProperties();
        assertTrue(helper.getHidden());
        assertNotEquals(TARGET_ID, helper.getSheetId());
        assertNotEquals(TARGET_TITLE, helper.getTitle());
        assertEquals(20, helper.getGridProperties().getRowCount());
        assertEquals(6, helper.getGridProperties().getColumnCount());

        var uploads = fixture.requests(request -> request.getUpdateCells() != null);
        assertTrue(uploads.size() > 1, "The fixture must require multiple staging batches");
        int uploadedRows = 0;
        for (var request : uploads) {
            var upload = request.getUpdateCells();
            assertEquals(helper.getSheetId(), upload.getStart().getSheetId());
            assertEquals(uploadedRows, upload.getStart().getRowIndex());
            assertEquals(0, upload.getStart().getColumnIndex());
            assertEquals("userEnteredValue", upload.getFields());
            for (var row : upload.getRows()) {
                assertEquals(6, row.getValues().size());
                for (int column = 0; column < 6; column++) {
                    var expected = column < rows.get(uploadedRows).size() ? rows.get(uploadedRows).get(column) : null;
                    assertEquals(expected == null ? null : expected.getUserEnteredValue(),
                            row.getValues().get(column).getUserEnteredValue());
                    assertNull(row.getValues().get(column).getUserEnteredFormat());
                }
                uploadedRows++;
            }
        }
        assertEquals(rows.size(), uploadedRows);
        assertTrue(fixture.waits.size() >= uploads.size() - 1);
        assertTrue(fixture.waits.stream().allMatch(wait -> wait == 1_000));

        var destinationBatches = fixture.destinationBatches();
        assertEquals(1, destinationBatches.size());
        var finish = destinationBatches.getFirst().getRequests();
        assertEquals(4, finish.size());
        var growth = finish.get(0).getUpdateSheetProperties();
        assertNotNull(growth);
        assertEquals(TARGET_ID, growth.getProperties().getSheetId());
        assertEquals(22, growth.getProperties().getGridProperties().getRowCount());
        assertEquals(7, growth.getProperties().getGridProperties().getColumnCount());
        assertEquals("gridProperties.rowCount,gridProperties.columnCount", growth.getFields());
        assertClear(finish.get(1), 2, 1);
        var copy = finish.get(2).getCopyPaste();
        assertEquals("PASTE_VALUES", copy.getPasteType());
        assertRange(copy.getSource(), helper.getSheetId(), 0, 20, 0, 6);
        assertRange(copy.getDestination(), TARGET_ID, 2, 22, 1, 7);
        var date = finish.get(3).getRepeatCell();
        assertEquals("userEnteredFormat.numberFormat", date.getFields());
        assertRange(date.getRange(), TARGET_ID, 2, 22, 3, 4);
        assertEquals("dd/MM/yyyy", date.getCell().getUserEnteredFormat().getNumberFormat().getPattern());

        int copyIndex = fixture.mutations.indexOf(destinationBatches.getFirst());
        assertTrue(fixture.mutations.subList(0, copyIndex).stream()
                .flatMap(batch -> batch.getRequests().stream())
                .noneMatch(SheetsAPIReplacementTest::targetsDestination));
        assertEquals(uploads.size(), fixture.mutations.subList(0, copyIndex).stream()
                .flatMap(batch -> batch.getRequests().stream()).filter(request -> request.getUpdateCells() != null).count());
        assertEquals(helper.getSheetId(), fixture.mutations.getLast().getRequests().getFirst().getDeleteSheet().getSheetId());
        assertNull(fixture.helper);
        assertTrue(fixture.writeTimeouts.stream().allMatch(timeout -> timeout == 240_000));
        assertTrue(fixture.readTimeouts.stream().allMatch(timeout -> timeout == 100_000));
    }

    @Test
    void clearsStaleRowsAndColumnsInTheSameBatchAsTheFinalCopy() {
        var fixture = new Fixture(100, 20);

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());

        var finish = fixture.destinationBatches().getFirst().getRequests();
        assertEquals(2, finish.size());
        assertClear(finish.getFirst(), 2, 1);
        assertNotNull(finish.getLast().getCopyPaste());
        assertEquals(1, fixture.destinationBatches().size());
    }

    @Test
    void smallReplacementKeepsTheSingleDirectWriteAndLiteralEqualsString() {
        var fixture = new Fixture(100, 20);

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2,
                List.of(List.of(SheetsAPI.toCellData("=1+1"), SheetsAPI.toCellData(45292))),
                Map.of(3, "dd/MM/yyyy"));

        assertEquals(1, fixture.mutations.size());
        var requests = fixture.mutations.getFirst().getRequests();
        assertEquals(3, requests.size());
        assertClear(requests.getFirst(), 2, 1);
        var write = requests.get(1).getUpdateCells();
        assertEquals(TARGET_ID, write.getStart().getSheetId());
        assertEquals("=1+1", write.getRows().getFirst().getValues().getFirst().getUserEnteredValue().getStringValue());
        assertEquals("userEnteredFormat.numberFormat", requests.getLast().getRepeatCell().getFields());
        assertTrue(fixture.waits.isEmpty());
        assertTrue(fixture.writeTimeouts.stream().allMatch(timeout -> timeout == 240_000));
    }

    @Test
    void emptyReplacementOnlyClearsValues() {
        var fixture = new Fixture(100, 20);

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, List.of(), Map.of());

        assertEquals(1, fixture.mutations.size());
        assertEquals(1, fixture.mutations.getFirst().getRequests().size());
        assertClear(fixture.mutations.getFirst().getRequests().getFirst(), 2, 1);
        assertTrue(fixture.waits.isEmpty());
    }

    @Test
    void actualFormulaInputUsesDirectWritesEvenWhenTheDataIsLarge() {
        var fixture = new Fixture(100, 20);
        var rows = largeRows();
        rows.set(0, List.of(rows.getFirst().getFirst(),
                new CellData().setUserEnteredValue(new ExtendedValue().setFormulaValue("=1+1"))));

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, rows, Map.of());

        assertTrue(fixture.requests(request -> request.getAddSheet() != null || request.getCopyPaste() != null).isEmpty());
        var writes = fixture.requests(request -> request.getUpdateCells() != null);
        assertTrue(writes.size() > 1);
        assertTrue(writes.stream().allMatch(request -> request.getUpdateCells().getStart().getSheetId() == TARGET_ID));
        assertEquals("=1+1", writes.getFirst().getUpdateCells().getRows().getFirst()
                .getValues().get(1).getUserEnteredValue().getFormulaValue());
    }

    @Test
    void failedStagingLeavesDestinationUntouchedAndCleansUpHelper() {
        var fixture = new Fixture(5, 4);
        fixture.uploadFailures = 5;

        assertThrows(RuntimeException.class,
                () -> fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of()));

        assertTrue(fixture.destinationBatches().isEmpty());
        assertEquals(5, fixture.requests(request -> request.getUpdateCells() != null).size());
        assertEquals(1, fixture.requests(request -> request.getDeleteSheet() != null).size());
        assertNull(fixture.helper);
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 40_000L), fixture.retryWaits());
    }

    @Test
    void rejectedUploadFailsImmediatelyAndStillCleansUpItsHelper() {
        var fixture = new Fixture(100, 20);
        fixture.uploadFailures = 1;
        fixture.uploadStatus = 400;

        var failure = assertThrows(RuntimeException.class,
                () -> fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of()));

        assertTrue(failure.getMessage().contains("Bad request"));
        assertEquals(1, fixture.requests(request -> request.getUpdateCells() != null).size());
        assertTrue(fixture.destinationBatches().isEmpty());
        assertTrue(fixture.retryWaits().isEmpty());
        assertNull(fixture.helper);
    }

    @Test
    void failedCopyKeepsTheHelperAvailableForInspectionAndInvalidatesCachedMetadata() {
        var fixture = new Fixture(100, 20);
        fixture.api.getSheetProperties();
        fixture.copyFailures = 5;

        assertThrows(RuntimeException.class,
                () -> fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of()));

        assertEquals(5, fixture.requests(request -> request.getCopyPaste() != null).size());
        assertTrue(fixture.requests(request -> request.getDeleteSheet() != null).isEmpty());
        assertNotNull(fixture.helper);
        assertEquals(2, fixture.api.getSheetProperties().size());
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 40_000L), fixture.retryWaits());
    }

    @Test
    void transientStagingFailureRetriesTheSameRangeBeforeCopying() {
        var fixture = new Fixture(100, 20);
        fixture.uploadFailures = 1;

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());

        var uploads = fixture.requests(request -> request.getUpdateCells() != null);
        assertEquals(3, uploads.size());
        assertEquals(uploads.get(0), uploads.get(1));
        assertEquals(1, fixture.requests(request -> request.getCopyPaste() != null).size());
        assertEquals(List.of(5_000L), fixture.retryWaits());
    }

    @Test
    void lostCopyResponseRetriesTheSameCopyWithHelperStillPresent() {
        var fixture = new Fixture(100, 20);
        fixture.loseCopyResponse = true;

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());

        var copies = fixture.requests(request -> request.getCopyPaste() != null);
        assertEquals(2, copies.size());
        assertEquals(copies.get(0), copies.get(1));
        assertEquals(1, fixture.requests(request -> request.getDeleteSheet() != null).size());
        assertEquals(List.of(5_000L), fixture.retryWaits());
        assertNull(fixture.helper);
    }

    @Test
    void lostCreationResponseFindsTheExistingHelperWithoutCreatingAnother() {
        var fixture = new Fixture(100, 20);
        fixture.loseCreationResponse = true;

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());

        assertEquals(1, fixture.requests(request -> request.getAddSheet() != null).size());
        assertEquals(1, fixture.requests(request -> request.getCopyPaste() != null).size());
        assertNull(fixture.helper);
    }

    @Test
    void creationRetryReusesItsOriginalIdAndTitleWhenNoHelperWasCreated() {
        var fixture = new Fixture(100, 20);
        fixture.creationFailures = 1;

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());

        var creates = fixture.requests(request -> request.getAddSheet() != null);
        assertEquals(2, creates.size());
        assertEquals(creates.get(0), creates.get(1));
        assertNull(fixture.helper);
    }

    @Test
    void cleanupFailureDoesNotTurnASuccessfulReplacementIntoFailure() {
        var fixture = new Fixture(100, 20);
        fixture.deleteFailures = 5;

        assertDoesNotThrow(() -> fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of()));

        assertEquals(1, fixture.requests(request -> request.getCopyPaste() != null).size());
        assertEquals(5, fixture.requests(request -> request.getDeleteSheet() != null).size());
        assertNotNull(fixture.helper);
    }

    @Test
    void lostDeletionResponseIsReconciledAndSheetMetadataIsRefreshed() {
        var fixture = new Fixture(100, 20);
        fixture.api.getSheetProperties();
        fixture.loseDeletionResponse = true;

        assertDoesNotThrow(() -> fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of()));

        assertEquals(1, fixture.requests(request -> request.getDeleteSheet() != null).size());
        assertNull(fixture.helper);
        int readsBefore = fixture.readTimeouts.size();
        assertEquals(1, fixture.api.getSheetProperties().size());
        assertTrue(fixture.readTimeouts.size() > readsBefore, "The cached metadata must be invalidated");
    }

    @Test
    void separateReplacementsGetDifferentHelperNames() {
        var fixture = new Fixture(100, 20);

        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());
        fixture.api.replaceCellValuesFrom(TARGET_TITLE, 3, 2, largeRows(), Map.of());

        var creates = fixture.requests(request -> request.getAddSheet() != null);
        assertEquals(2, creates.size());
        assertNotEquals(creates.get(0).getAddSheet().getProperties().getTitle(),
                creates.get(1).getAddSheet().getProperties().getTitle());
    }

    private static List<List<CellData>> largeRows() {
        var rows = new ArrayList<List<CellData>>();
        String payload = "x".repeat(30_000);
        for (int row = 0; row < 20; row++) {
            rows.add(Arrays.asList(SheetsAPI.toCellData(row + payload), SheetsAPI.toCellData("=1+1"),
                    SheetsAPI.toCellData(45292), SheetsAPI.toCellData(true), null, SheetsAPI.toCellData("")));
        }
        rows.set(19, List.of(rows.get(19).getFirst()));
        return rows;
    }

    private static void assertClear(Request request, int row, int column) {
        var clear = request.getRepeatCell();
        assertNotNull(clear);
        assertEquals("userEnteredValue", clear.getFields());
        assertRange(clear.getRange(), TARGET_ID, row, null, column, null);
        assertNull(clear.getCell().getUserEnteredValue());
        assertNull(clear.getCell().getUserEnteredFormat());
    }

    private static void assertRange(GridRange range, int sheetId, int startRow, Integer endRow,
                                    int startColumn, Integer endColumn) {
        assertEquals(sheetId, range.getSheetId());
        assertEquals(startRow, range.getStartRowIndex());
        assertEquals(endRow, range.getEndRowIndex());
        assertEquals(startColumn, range.getStartColumnIndex());
        assertEquals(endColumn, range.getEndColumnIndex());
    }

    private static boolean targetsDestination(Request request) {
        return request.getCopyPaste() != null && request.getCopyPaste().getDestination().getSheetId() == TARGET_ID
                || request.getUpdateCells() != null && request.getUpdateCells().getStart().getSheetId() == TARGET_ID
                || request.getRepeatCell() != null && request.getRepeatCell().getRange().getSheetId() == TARGET_ID
                || request.getUpdateSheetProperties() != null && request.getUpdateSheetProperties().getProperties().getSheetId() == TARGET_ID;
    }

    /** A local HTTP endpoint simulator: all JSON still passes through the production Google client. */
    private static class Fixture extends MockHttpTransport {
        final List<BatchUpdateSpreadsheetRequest> mutations = new ArrayList<>();
        final List<Long> waits = new ArrayList<>();
        final List<Integer> readTimeouts = new ArrayList<>();
        final List<Integer> writeTimeouts = new ArrayList<>();
        final SheetProperties target;
        final SheetsAPI api;
        SheetProperties helper;
        int creationFailures;
        int uploadFailures;
        int uploadStatus = 503;
        int copyFailures;
        int deleteFailures;
        boolean loseCreationResponse;
        boolean loseCopyResponse;
        boolean loseDeletionResponse;

        Fixture(int rows, int columns) {
            target = new SheetProperties().setSheetId(TARGET_ID).setTitle(TARGET_TITLE).setIndex(0)
                    .setGridProperties(new GridProperties().setRowCount(rows).setColumnCount(columns));
            var service = new Sheets.Builder(this, JSON, request -> {
                request.setConnectTimeout(10_000);
                request.setReadTimeout(100_000);
                request.setNumberOfRetries(0);
            }).setApplicationName("offline-replacement-test").build();
            api = new SheetsAPI("offline-replacement-test", "spreadsheet-id", "Spreadsheet", service, waits::add);
        }

        List<Request> requests(Predicate<Request> predicate) {
            return mutations.stream().flatMap(batch -> batch.getRequests().stream()).filter(predicate).toList();
        }

        List<Long> retryWaits() {
            return waits.stream().filter(wait -> wait > 1_000).toList();
        }

        List<BatchUpdateSpreadsheetRequest> destinationBatches() {
            return mutations.stream().filter(batch -> batch.getRequests().stream()
                    .anyMatch(SheetsAPIReplacementTest::targetsDestination)).toList();
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest(url) {
                private int readTimeout;

                @Override
                public void setTimeout(int connectTimeout, int timeout) {
                    readTimeout = timeout;
                }

                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    if (method.equals("GET")) {
                        readTimeouts.add(readTimeout);
                        var sheets = new ArrayList<Sheet>();
                        sheets.add(new Sheet().setProperties(target));
                        if (helper != null) {
                            sheets.add(new Sheet().setProperties(helper));
                        }
                        return response(JSON.toString(new Spreadsheet().setSheets(sheets)));
                    }
                    assertEquals("POST", method);
                    assertTrue(url.endsWith(":batchUpdate"));
                    writeTimeouts.add(readTimeout);
                    var batch = JSON.fromString(getContentAsString(), BatchUpdateSpreadsheetRequest.class);
                    mutations.add(batch);
                    for (var request : batch.getRequests()) {
                        if (request.getAddSheet() != null) {
                            if (creationFailures-- > 0) {
                                return unavailable();
                            }
                            assertNull(helper, "An invocation must not create a second helper");
                            helper = request.getAddSheet().getProperties().clone().setIndex(1);
                            if (loseCreationResponse) {
                                loseCreationResponse = false;
                                throw new IOException("Connection closed after helper creation");
                            }
                        } else if (request.getUpdateCells() != null && request.getUpdateCells().getStart().getSheetId() != TARGET_ID) {
                            assertNotNull(helper);
                            assertEquals(helper.getSheetId(), request.getUpdateCells().getStart().getSheetId());
                            if (uploadFailures-- > 0) {
                                return response("{\"error\":{\"code\":" + uploadStatus
                                        + ",\"message\":\"Upload rejected\"}}")
                                        .setStatusCode(uploadStatus);
                            }
                        } else if (request.getCopyPaste() != null) {
                            assertNotNull(helper, "Copy retries must retain their source helper");
                            assertEquals(helper.getSheetId(), request.getCopyPaste().getSource().getSheetId());
                            if (copyFailures-- > 0) {
                                return unavailable();
                            }
                            if (loseCopyResponse) {
                                loseCopyResponse = false;
                                throw new IOException("Connection closed after copy");
                            }
                        } else if (request.getDeleteSheet() != null) {
                            if (deleteFailures-- > 0) {
                                return unavailable();
                            }
                            assertNotNull(helper, "Deletion should reconcile an already absent helper");
                            assertEquals(helper.getSheetId(), request.getDeleteSheet().getSheetId());
                            helper = null;
                            if (loseDeletionResponse) {
                                loseDeletionResponse = false;
                                throw new IOException("Connection closed after helper deletion");
                            }
                        }
                    }
                    return response("{}");
                }
            };
        }

        private static MockLowLevelHttpResponse response(String body) {
            return new MockLowLevelHttpResponse().setContentType("application/json").setContent(body);
        }

        private static MockLowLevelHttpResponse unavailable() {
            return response("{\"error\":{\"code\":503,\"message\":\"Temporary service failure\"}}")
                    .setStatusCode(503).setReasonPhrase("Service Unavailable");
        }
    }
}
