package ro.sellfluence.emagapi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.sellfluence.support.UserPassword;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmagApiTest {

    private static final String PAGE_WITH_RESULT = """
            {"isError":false,"messages":[],"errors":[],"results":[{"value":"kept"}]}
            """;
    private static final String EMPTY_PAGE = """
            {"isError":false,"messages":[],"errors":[],"results":[]}
            """;

    @Test
    void deserializesStructuredProformsThroughTheProductionParserWithoutRetries() throws Exception {
        var orders = readOrdersWithoutRetries(orderWithProforms("[" + readProformFixture() + "]"));

        assertEquals(1, orders.size());
        assertEquals("534972746", orders.getFirst().id());
        assertEquals(1, orders.getFirst().proforms().size());
        var proform = orders.getFirst().proforms().getFirst();
        assertEquals(3_428_567L, proform.id());
        assertEquals(173_961L, proform.vendor_id());
        assertEquals("Example Vendor", proform.vendor_name());
        assertEquals("Example Bank", proform.vendor_bank());
        assertEquals("RO00EXAMPLE000000000000000", proform.vendor_iban());
        assertEquals("Example Customer", proform.customer_name());
        assertEquals(258_601_974L, proform.vendor_order_id());
        assertEquals(3_428_567L, proform.proforma_number());
        assertEquals(LocalDateTime.of(2026, 10, 8, 12, 54, 12), proform.created());
        assertEquals(LocalDateTime.of(2026, 10, 23, 12, 54, 12), proform.date_expire());
        assertEquals(new BigDecimal("545.4400"), proform.net_value());
        assertEquals(new BigDecimal("659.98"), proform.gross_value());
        assertEquals(1, proform.status());
        assertEquals(0, proform.is_payed());
        assertEquals(LocalDateTime.of(2026, 10, 8, 12, 54, 12), proform.modified());
        assertEquals(534_972_746L, proform.mkt_order_id());
        assertEquals(2, proform.products().size());
        assertProformProduct(proform.products().get(0), 11_726_788L, 315_854_790L, 89_893_979L,
                "Ceas smartwatch dama, Qualtec by Koppel\u00ae, ecran rotund AMOLED 1.32\", inteligent, fitness, sport, rezistent apa 3ATM, smart, notificari, wireless, apel Bluetooth 5.3, microfon HD, limba romana, pentru femei, carcasa metalica, 2 bratari incluse, ELITE, gold");
        assertProformProduct(proform.products().get(1), 11_726_789L, 315_854_791L, 92_760_412L,
                "Ceas smartwatch barbati, Qualtec by Koppel\u00ae, ecran AMOLED 1.43\", rezolutie 466x466, inteligent, fitness, sport, rezistent apa 3ATM, smart, notificari, wireless, apel Bluetooth, microfon HD, limba romana, curea metalica, 2 bratari incluse, AMMO PRO, negru");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"proforms\":null}", "{\"proforms\":[]}"})
    void normalizesMissingNullAndEmptyProforms(String orderJson) throws Exception {
        var orders = readOrdersWithoutRetries(orderJson);

        assertEquals(1, orders.size());
        assertEquals(List.of(), orders.getFirst().proforms());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"products\":null}", "{\"products\":[]}"})
    void normalizesMissingNullAndEmptyProformProducts(String proformJson) throws Exception {
        var orders = readOrdersWithoutRetries(orderWithProforms("[" + proformJson + "]"));

        assertEquals(1, orders.getFirst().proforms().size());
        assertEquals(List.of(), orders.getFirst().proforms().getFirst().products());
    }

    @Test
    void proformsOnlyChangesAreHandledWithoutReportingUnhandledDifferences() throws Exception {
        var before = readOrdersWithoutRetries(orderWithProforms("[]")).getFirst();
        var after = readOrdersWithoutRetries(orderWithProforms("[" + readProformFixture() + "]")).getFirst();

        assertFalse(before.reportUnhandledDifferences(after));
        assertFalse(after.reportUnhandledDifferences(before));
    }

    @Test
    void stillRejectsUnknownProformFieldsWithoutRetrying() throws Exception {
        var proform = JsonParser.parseString(readProformFixture()).getAsJsonObject();
        proform.addProperty("unexpected_field", "unexpected");
        var requestedPages = new CopyOnWriteArrayList<Integer>();
        var server = startServer(List.of(new ScriptedResponse(HTTP_OK,
                pageWithOrder(orderWithProforms("[" + proform + "]")))), requestedPages);
        try {
            var delays = new ArrayList<Long>();
            var emagApi = new EmagApi(new UserPassword("testuser", "user", "password", null), delays::add);

            var exception = assertThrows(RuntimeException.class,
                    () -> emagApi.emagRequest(endpoint(server), true, Map.of(), null, OrderResult.class));

            assertInstanceOf(UnrecognizedPropertyException.class, exception.getCause());
            assertTrue(exception.getMessage().contains("unexpected_field"));
            assertEquals(List.of(1), requestedPages);
            assertEquals(List.of(), delays);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retries500OnTheCurrentPageWithAPageLocalExponentialBackoff() throws Exception {
        var responses = List.of(
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_OK, PAGE_WITH_RESULT),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_OK, EMPTY_PAGE)
        );
        var requestedPages = new CopyOnWriteArrayList<Integer>();
        var server = startServer(responses, requestedPages);
        try {
            var delays = new ArrayList<Long>();
            var emagApi = new EmagApi(new UserPassword("testuser", "user", "password", null), delays::add);

            var result = emagApi.emagRequest(endpoint(server), true, Map.of(), null, Map.class);

            assertEquals(1, result.size());
            assertEquals("kept", result.getFirst().get("value"));
            assertEquals(List.of(1, 1, 2, 2, 2, 2, 2), requestedPages);
            assertEquals(List.of(10_000L, 10_000L, 20_000L, 40_000L, 80_000L), delays);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void givesUpAfterFourRetriesOfA500Response() throws Exception {
        var responses = List.of(
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, "")
        );
        var requestedPages = new CopyOnWriteArrayList<Integer>();
        var server = startServer(responses, requestedPages);
        try {
            var delays = new ArrayList<Long>();
            var emagApi = new EmagApi(new UserPassword("testuser", "user", "password", null), delays::add);

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> emagApi.emagRequest(endpoint(server), true, Map.of(), null, Map.class)
            );

            assertEquals("Emag API error 500", exception.getMessage());
            assertEquals(List.of(1, 1, 1, 1, 1), requestedPages);
            assertEquals(List.of(10_000L, 20_000L, 40_000L, 80_000L), delays);
        } finally {
            server.stop(0);
        }
    }

    private static List<OrderResult> readOrdersWithoutRetries(String orderJson) throws Exception {
        var requestedPages = new CopyOnWriteArrayList<Integer>();
        var server = startServer(List.of(
                new ScriptedResponse(HTTP_OK, pageWithOrder(orderJson)),
                new ScriptedResponse(HTTP_OK, EMPTY_PAGE)
        ), requestedPages);
        try {
            var delays = new ArrayList<Long>();
            var emagApi = new EmagApi(new UserPassword("testuser", "user", "password", null), delays::add);

            var orders = emagApi.emagRequest(endpoint(server), true, Map.of(), null, OrderResult.class);

            assertEquals(List.of(1, 2), requestedPages);
            assertEquals(List.of(), delays);
            return orders;
        } finally {
            server.stop(0);
        }
    }

    private static String readProformFixture() throws IOException {
        try (var resource = EmagApiTest.class.getResourceAsStream("proform.json")) {
            if (resource == null) {
                throw new FileNotFoundException("Test resource not found: proform.json");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String orderWithProforms(String proformsJson) {
        return "{\"id\":\"534972746\",\"status\":1,\"proforms\":" + proformsJson + "}";
    }

    private static String pageWithOrder(String orderJson) {
        var order = new JsonObject();
        order.addProperty("status", 1);
        order.addProperty("has_editable_products", 0);
        order.addProperty("emag_club", 0);
        order.addProperty("weekend_delivery", 0);
        for (var entry : JsonParser.parseString(orderJson).getAsJsonObject().entrySet()) {
            order.add(entry.getKey(), entry.getValue());
        }
        return "{\"isError\":false,\"messages\":[],\"errors\":[],\"results\":[" + order + "]}";
    }

    private static void assertProformProduct(ProformProduct product, long id, long orderProductId,
                                             long productId, String name) {
        assertEquals(id, product.id());
        assertEquals(3_428_567L, product.vendor_proform_id());
        assertEquals(orderProductId, product.vendor_order_product_id());
        assertEquals(productId, product.vendor_product_id());
        assertEquals(name, product.vendor_product_ext_name());
        assertEquals(1, product.vendor_order_product_quantity());
        assertEquals(new BigDecimal("272.7190"), product.vendor_order_product_sale_price());
        assertEquals(new BigDecimal("0.2100"), product.vendor_order_product_vat_rate());
        assertEquals(LocalDateTime.of(2026, 10, 8, 12, 54, 12), product.created());
        assertEquals(LocalDateTime.of(2026, 10, 8, 12, 54, 12), product.modified());
    }

    private static HttpServer startServer(List<ScriptedResponse> responses, List<Integer> requestedPages) throws IOException {
        var responseIndex = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/order/read", exchange -> {
            var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestedPages.add(JsonParser.parseString(requestBody).getAsJsonObject().get("currentPage").getAsInt());
            var index = responseIndex.getAndIncrement();
            if (index >= responses.size()) {
                sendResponse(exchange, 599, "Unexpected request");
                return;
            }
            var response = responses.get(index);
            sendResponse(exchange, response.statusCode(), response.body());
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:%d/order/read".formatted(server.getAddress().getPort());
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        var responseBody = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBody.length);
        try (var output = exchange.getResponseBody()) {
            output.write(responseBody);
        }
    }

    private record ScriptedResponse(int statusCode, String body) {
    }
}
