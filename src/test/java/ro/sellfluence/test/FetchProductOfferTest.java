package ro.sellfluence.test;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.ProductOffer;
import ro.sellfluence.support.UserPassword;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchProductOfferTest {
    private static final String FIRST_PAGE = """
            {"isError":false,"messages":[],"results":[
              {"id":243409,"part_number_key":"ES0NKBBBM","name":"Ofertă eMAG","sale_price":51.6477000,"status":1}
            ]}
            """;
    private static final String EMPTY_PAGE = "{\"isError\":false,\"messages\":[],\"results\":[]}\n";

    @TempDir
    Path directory;

    @Test
    void savesRawUtf8EnvelopesBeforeDecodingAndPrintsOnlyCompletedRecords() throws Exception {
        var output = directory.resolve("nested/product-offers.json");
        var stdout = new ByteArrayOutputStream();
        var api = new StubApi(output, stdout, List.of(FIRST_PAGE, EMPTY_PAGE), null);

        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            assertEquals(1, FetchProductOffer.fetch(api, output, printer));
        }

        var archive = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(archive.contains(FIRST_PAGE));
        assertTrue(archive.contains(EMPTY_PAGE));
        assertTrue(archive.contains("51.6477000"));
        assertEquals(2, JsonParser.parseString(archive).getAsJsonArray().size());
        assertEquals(api.parseProductOffers(FIRST_PAGE).results().getFirst() + System.lineSeparator(),
                stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void emptyFetchReplacesTheOldArchiveAndPrintsNoRecords() throws Exception {
        var output = directory.resolve("product-offers.json");
        Files.writeString(output, "previous result");
        var stdout = new ByteArrayOutputStream();
        var api = new StubApi(output, stdout, List.of(EMPTY_PAGE), null);

        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            assertEquals(0, FetchProductOffer.fetch(api, output, printer));
        }

        var archive = Files.readString(output);
        assertFalse(archive.contains("previous result"));
        assertEquals(1, JsonParser.parseString(archive).getAsJsonArray().size());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void applicationErrorIsArchivedWithoutPrintingEarlierOffers() throws Exception {
        var errorPage = "{\"isError\":true,\"messages\":[\"Access denied\"],\"results\":[]}";
        var output = directory.resolve("product-offers.json");
        var stdout = new ByteArrayOutputStream();
        var api = new StubApi(output, stdout, List.of(FIRST_PAGE, errorPage), null);

        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            assertThrows(IllegalArgumentException.class, () -> FetchProductOffer.fetch(api, output, printer));
        }

        var archive = Files.readString(output);
        assertTrue(archive.contains(errorPage));
        assertEquals(2, JsonParser.parseString(archive).getAsJsonArray().size());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void malformedBodyIsRetainedVerbatimAndArchiveStillHasClosingBracket() throws Exception {
        var malformedPage = "this response is not JSON";
        var output = directory.resolve("product-offers.json");
        var stdout = new ByteArrayOutputStream();
        var api = new StubApi(output, stdout, List.of(FIRST_PAGE, malformedPage), null);

        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            assertThrows(IllegalArgumentException.class, () -> FetchProductOffer.fetch(api, output, printer));
        }

        var archive = Files.readString(output);
        assertTrue(archive.contains(FIRST_PAGE));
        assertTrue(archive.contains(malformedPage));
        assertTrue(archive.endsWith("\n]\n"));
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void requestFailurePreservesPreviousPagesWithoutPrintingPartialResults() throws Exception {
        var output = directory.resolve("product-offers.json");
        var stdout = new ByteArrayOutputStream();
        var failure = new IOException("eMAG request failed with HTTP 403");
        var api = new StubApi(output, stdout, List.of(FIRST_PAGE), failure);

        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            assertEquals(failure, assertThrows(IOException.class, () -> FetchProductOffer.fetch(api, output, printer)));
        }

        assertEquals(1, JsonParser.parseString(Files.readString(output)).getAsJsonArray().size());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void interruptedFetchAlsoPreservesPagesAndPrintsNoRecords() throws Exception {
        var output = directory.resolve("product-offers.json");
        var stdout = new ByteArrayOutputStream();
        var failure = new InterruptedException("fetch interrupted");
        var api = new StubApi(output, stdout, List.of(FIRST_PAGE), failure);

        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            assertEquals(failure, assertThrows(InterruptedException.class,
                    () -> FetchProductOffer.fetch(api, output, printer)));
        }

        assertEquals(1, JsonParser.parseString(Files.readString(output)).getAsJsonArray().size());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpDoesNotRequireCredentialsOrCreateAnArchive() throws Exception {
        var original = System.out;
        var stdout = new ByteArrayOutputStream();
        var output = directory.resolve("should-not-exist.json");
        try (var printer = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(printer);
            FetchProductOffer.main(new String[]{"--help", "--output=" + output});
        } finally {
            System.setOut(original);
        }

        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("--account=<alias>"));
        assertFalse(Files.exists(output));
    }

    private static final class StubApi extends EmagApi {
        private final Path output;
        private final ByteArrayOutputStream stdout;
        private final List<String> responseBodies;
        private final Exception failure;

        private StubApi(Path output, ByteArrayOutputStream stdout, List<String> responseBodies, Exception failure) {
            super(new UserPassword("test", "test-user", "test-password", null));
            this.output = output;
            this.stdout = stdout;
            this.responseBodies = responseBodies;
            this.failure = failure;
        }

        @Override
        public List<ProductOffer> readProductOffers(Map<String, Object> filters, ProductOfferPageConsumer consumer)
                throws IOException, InterruptedException {
            assertEquals(Map.of(), filters);
            var offers = new ArrayList<ProductOffer>();
            for (int index = 0; index < responseBodies.size(); index++) {
                var body = responseBodies.get(index);
                consumer.accept(index + 1, body);
                assertTrue(Files.readString(output, StandardCharsets.UTF_8).contains(body),
                        "Response must be flushed before decoding starts");
                assertEquals("", stdout.toString(StandardCharsets.UTF_8),
                        "No records may be printed until every page succeeds");
                offers.addAll(parseProductOffers(body).results());
            }
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            if (failure instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            return List.copyOf(offers);
        }
    }
}
