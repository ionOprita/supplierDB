package ro.sellfluence.emagapi;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EmagApiTimeoutTest {
    private static final UserPassword CREDENTIALS = new UserPassword("timeout-test", "user", "password", null);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(500);
    private static final List<Long> RETRY_DELAYS = List.of(10_000L, 20_000L, 40_000L, 80_000L);

    @ParameterizedTest
    @EnumSource(StallPoint.class)
    void stopsGenericReadAfterFourRetriesOfAStalledResponse(StallPoint stallPoint) throws Exception {
        try (var endpoint = new StalledEndpoint(stallPoint, false)) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri(), RESPONSE_TIMEOUT);

            var failure = assertThrows(RuntimeException.class,
                    () -> api.emagRequest(endpoint.uri().toString(), true, Map.of(), null, Map.class));

            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            assertEquals(List.of(1, 1, 1, 1, 1), endpoint.pages);
            assertEquals(RETRY_DELAYS, delays);
        }
    }

    @ParameterizedTest
    @EnumSource(StallPoint.class)
    void stopsProductOfferReadAfterFourRetriesOfAStalledResponse(StallPoint stallPoint) throws Exception {
        try (var endpoint = new StalledEndpoint(stallPoint, false)) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri(), RESPONSE_TIMEOUT);

            var failure = assertThrows(IOException.class,
                    () -> api.readProductOffersRaw(Map.of(), 3, 100));

            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            assertEquals(List.of(3, 3, 3, 3, 3), endpoint.pages);
            assertEquals(RETRY_DELAYS, delays);
        }
    }

    @Test
    void retriesTheSamePageSuccessfullyAfterAStalledBody() throws Exception {
        try (var endpoint = new StalledEndpoint(StallPoint.IN_BODY, true)) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri(), RESPONSE_TIMEOUT);

            assertTrue(api.emagRequest(endpoint.uri().toString(), true, Map.of(), null, Map.class).isEmpty());

            assertEquals(List.of(1, 1), endpoint.pages);
            assertEquals(List.of(10_000L), delays);
        }
    }

    @Test
    void interruptionAbortsAnInFlightRequestWithoutRetrying() throws Exception {
        try (var endpoint = new StalledEndpoint(StallPoint.IN_BODY, false)) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri(), Duration.ofSeconds(10));
            var failure = new AtomicReference<Throwable>();
            var caller = new Thread(() -> {
                try {
                    api.emagRequest(endpoint.uri().toString(), true, Map.of(), null, Map.class);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            caller.start();
            try {
                assertTrue(endpoint.requestReceived.await(5, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(2_000);

                assertFalse(caller.isAlive());
                assertInstanceOf(InterruptedException.class, failure.get());
                assertEquals(List.of(1), endpoint.pages);
                assertEquals(List.of(), delays);
            } finally {
                caller.interrupt();
                caller.join(2_000);
            }
        }
    }

    private enum StallPoint { BEFORE_HEADERS, IN_BODY }

    private static final class StalledEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch requestReceived = new CountDownLatch(1);
        private final List<Integer> pages = new CopyOnWriteArrayList<>();

        private StalledEndpoint(StallPoint stallPoint, boolean recoverAfterFirstRequest) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/read", exchange -> handle(exchange, stallPoint, recoverAfterFirstRequest));
            server.start();
        }

        private void handle(HttpExchange exchange, StallPoint stallPoint, boolean recoverAfterFirstRequest)
                throws IOException {
            try (exchange) {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                pages.add(JsonParser.parseString(body).getAsJsonObject().get("currentPage").getAsInt());
                requestReceived.countDown();
                if (recoverAfterFirstRequest && pages.size() > 1) {
                    var response = "{\"isError\":false,\"messages\":[],\"errors\":[],\"results\":[]}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    return;
                }
                if (stallPoint == StallPoint.IN_BODY) {
                    exchange.sendResponseHeaders(200, 1_000);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                }
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:%d/read".formatted(server.getAddress().getPort()));
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
