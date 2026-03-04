package dev.peekapi;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeekApiClientTest {

    @TempDir Path tempDir;

    private String tmpStoragePath() {
        return tempDir.resolve("peekapi-test.jsonl").toString();
    }

    private PeekApiClient newTestClient(String serverUrl) {
        return new PeekApiClient(
                PeekApiOptions.builder("ak_test_key")
                        .endpoint(serverUrl)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1000)
                        .storagePath(tmpStoragePath())
                        .build());
    }

    private RequestEvent testEvent() {
        return new RequestEvent("GET", "/api/v1/users", 200, 5.25, 0, 128, null, null);
    }

    // --- Constructor validation ---

    @Test
    void constructorRejectsNullApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PeekApiClient(
                                PeekApiOptions.builder(null)
                                        .endpoint("http://localhost:9999")
                                        .storagePath(tmpStoragePath())
                                        .build()));
    }

    @Test
    void constructorRejectsEmptyApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PeekApiClient(
                                PeekApiOptions.builder("")
                                        .endpoint("http://localhost:9999")
                                        .storagePath(tmpStoragePath())
                                        .build()));
    }

    @Test
    void constructorRejectsApiKeyWithCrlf() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PeekApiClient(
                                PeekApiOptions.builder("key\r\ninjection")
                                        .endpoint("http://localhost:9999")
                                        .storagePath(tmpStoragePath())
                                        .build()));
    }

    @Test
    void constructorRejectsHttpNonLocalhost() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PeekApiClient(
                                PeekApiOptions.builder("ak_test")
                                        .endpoint("http://example.com/ingest")
                                        .storagePath(tmpStoragePath())
                                        .build()));
    }

    @Test
    void constructorAllowsHttpLocalhost() {
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("http://localhost:9999/ingest")
                                .flushInterval(Duration.ofHours(1))
                                .storagePath(tmpStoragePath())
                                .build());
        client.shutdown();
    }

    @Test
    void constructorAllowsHttps() {
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("https://ingest.peekapi.dev/v1/events")
                                .flushInterval(Duration.ofHours(1))
                                .storagePath(tmpStoragePath())
                                .build());
        client.shutdown();
    }

    @Test
    void constructorRejectsInvalidUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PeekApiClient(
                                PeekApiOptions.builder("ak_test")
                                        .endpoint("not-a-url")
                                        .storagePath(tmpStoragePath())
                                        .build()));
    }

    // --- Buffer management ---

    @Test
    void trackAddsEventToBuffer() {
        PeekApiClient client = newTestClient("http://localhost:9999");
        try {
            client.track(testEvent());
            assertEquals(1, client.getBufferSize());

            client.track(testEvent());
            assertEquals(2, client.getBufferSize());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void trackSanitizesLongFields() {
        PeekApiClient client = newTestClient("http://localhost:9999");
        try {
            RequestEvent event = new RequestEvent();
            event.setMethod("X".repeat(100));
            event.setPath("/" + "x".repeat(3000));
            event.setConsumerId("c".repeat(500));
            event.setStatusCode(200);

            client.track(event);
            assertEquals(1, client.getBufferSize());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void trackUppercasesMethod() throws Exception {
        List<String> payloads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    payloads.add(new String(exchange.getRequestBody().readAllBytes()));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    latch.countDown();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1)
                                .storagePath(tmpStoragePath())
                                .build());
        try {
            client.track(
                    new RequestEvent("get", "/api/test", 200, 1.0, 0, 0, null, null));
            latch.await(5, TimeUnit.SECONDS);

            assertFalse(payloads.isEmpty());
            assertTrue(payloads.get(0).contains("\"method\":\"GET\""));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void trackDropsOversizedEvent() {
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("http://localhost:9999")
                                .flushInterval(Duration.ofHours(1))
                                .storagePath(tmpStoragePath())
                                .maxEventBytes(50) // Very small limit
                                .build());
        try {
            client.track(testEvent());
            assertEquals(0, client.getBufferSize());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void trackIgnoresNullEvent() {
        PeekApiClient client = newTestClient("http://localhost:9999");
        try {
            client.track(null);
            assertEquals(0, client.getBufferSize());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void bufferDropsOldestWhenFull() {
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("http://localhost:9999")
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .maxBufferSize(3)
                                .storagePath(tmpStoragePath())
                                .build());
        try {
            for (int i = 0; i < 5; i++) {
                client.track(testEvent());
            }
            assertEquals(3, client.getBufferSize());
        } finally {
            client.shutdown();
        }
    }

    // --- Flush / send ---

    @Test
    void batchSizeTriggersFlush() throws Exception {
        AtomicInteger flushCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    flushCount.incrementAndGet();
                    latch.countDown();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(3)
                                .storagePath(tmpStoragePath())
                                .build());
        try {
            client.track(testEvent());
            client.track(testEvent());
            assertEquals(0, flushCount.get());

            client.track(testEvent()); // Triggers batch flush
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, flushCount.get());
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void flushSendsCorrectHeaders() throws Exception {
        List<String> sdkHeaders = Collections.synchronizedList(new ArrayList<>());
        List<String> apiKeyHeaders = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    sdkHeaders.add(exchange.getRequestHeaders().getFirst("x-peekapi-sdk"));
                    apiKeyHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key"));
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    latch.countDown();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test_headers")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1)
                                .storagePath(tmpStoragePath())
                                .build());
        try {
            client.track(testEvent());
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            assertEquals("java/" + PeekApiClient.SDK_VERSION, sdkHeaders.get(0));
            assertEquals("ak_test_headers", apiKeyHeaders.get(0));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void flushSuccessResetsFailureCount() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int count = requestCount.incrementAndGet();
                    // First request fails, second succeeds
                    if (count == 1) {
                        exchange.sendResponseHeaders(500, -1);
                    } else {
                        exchange.sendResponseHeaders(200, -1);
                    }
                    exchange.close();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(tmpStoragePath())
                                .build());
        try {
            client.track(testEvent());
            client.flush(); // Fails (500)
            assertEquals(1, client.getConsecutiveFailures());

            // Wait for backoff to expire
            Thread.sleep(1500);

            client.flush(); // Succeeds
            assertEquals(0, client.getConsecutiveFailures());
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    // --- Retryable vs non-retryable ---

    @Test
    void nonRetryableErrorPersistsToDisk() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(401, -1); // Non-retryable
                    exchange.close();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        String storage = tmpStoragePath();
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(storage)
                                .build());
        try {
            client.track(testEvent());
            client.flush();

            // Should not increment failure counter for non-retryable
            assertEquals(0, client.getConsecutiveFailures());
            // Events should be persisted to disk
            assertTrue(
                    Files.exists(Path.of(storage)) || Files.exists(Path.of(storage + ".recovering")));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void maxConsecutiveFailuresPersistsToDisk() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(500, -1); // Retryable
                    exchange.close();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        String storage = tmpStoragePath();
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(storage)
                                .build());
        try {
            for (int i = 0; i < PeekApiClient.MAX_CONSECUTIVE_FAILURES; i++) {
                client.track(testEvent());
                client.flush();
                // Reset backoff for next attempt
                Thread.sleep(
                        (long) (PeekApiClient.BASE_BACKOFF_MS * Math.pow(2, i) + 100));
            }

            // After MAX failures, counter should be reset to 0
            assertEquals(0, client.getConsecutiveFailures());
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    // --- Shutdown ---

    @Test
    void shutdownFlushesBuffer() throws Exception {
        AtomicInteger eventsSent = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    // Count events by counting "method" occurrences
                    int count = 0;
                    int idx = 0;
                    while ((idx = body.indexOf("\"method\"", idx)) != -1) {
                        count++;
                        idx++;
                    }
                    eventsSent.addAndGet(count);
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    latch.countDown();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(tmpStoragePath())
                                .build());

        for (int i = 0; i < 5; i++) {
            client.track(testEvent());
        }
        assertEquals(5, client.getBufferSize());

        client.shutdown();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(5, eventsSent.get());
        server.stop(0);
    }

    @Test
    void shutdownIsIdempotent() {
        PeekApiClient client = newTestClient("http://localhost:9999");
        client.shutdown();
        client.shutdown(); // Should not throw
    }

    @Test
    void trackAfterShutdownIsIgnored() {
        PeekApiClient client = newTestClient("http://localhost:9999");
        client.shutdown();
        client.track(testEvent());
        assertEquals(0, client.getBufferSize());
    }

    // --- Disk persistence ---

    @Test
    void shutdownPersistsUnsentEvents() throws IOException {
        String storage = tmpStoragePath();
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("http://localhost:19999") // Unreachable
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(storage)
                                .build());

        client.track(testEvent());
        client.track(testEvent());
        client.shutdown();

        assertTrue(Files.exists(Path.of(storage)));
        String content = Files.readString(Path.of(storage));
        assertTrue(content.contains("\"method\":\"GET\""));
    }

    @Test
    void newClientRecoversDiskEvents() throws IOException {
        String storage = tmpStoragePath();

        // First client: track events and shutdown (they persist to disk)
        PeekApiClient client1 =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("http://localhost:19999")
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(storage)
                                .build());
        client1.track(testEvent());
        client1.track(testEvent());
        client1.shutdown();

        assertTrue(Files.exists(Path.of(storage)));

        // Second client: should recover events from disk
        PeekApiClient client2 =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint("http://localhost:19999")
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(storage)
                                .build());
        try {
            assertEquals(2, client2.getBufferSize());
        } finally {
            client2.shutdown();
        }
    }

    // --- onError callback ---

    @Test
    void onErrorCallbackCalledOnFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(tmpStoragePath())
                                .onError(errors::add)
                                .build());
        try {
            client.track(testEvent());
            client.flush();

            assertFalse(errors.isEmpty());
            assertTrue(errors.get(0).getMessage().contains("500"));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    // --- JSON payload format ---

    @Test
    void flushSendsJsonArray() throws Exception {
        List<String> payloads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    payloads.add(new String(exchange.getRequestBody().readAllBytes()));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    latch.countDown();
                });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();

        PeekApiClient client =
                new PeekApiClient(
                        PeekApiOptions.builder("ak_test")
                                .endpoint(url)
                                .flushInterval(Duration.ofHours(1))
                                .batchSize(1000)
                                .storagePath(tmpStoragePath())
                                .build());
        try {
            client.track(
                    new RequestEvent(
                            "POST", "/api/v1/orders", 201, 12.34, 50, 200, "acme_corp", null));
            client.flush();
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            String payload = payloads.get(0);
            assertTrue(payload.startsWith("["));
            assertTrue(payload.endsWith("]"));
            assertTrue(payload.contains("\"method\":\"POST\""));
            assertTrue(payload.contains("\"path\":\"/api/v1/orders\""));
            assertTrue(payload.contains("\"status_code\":201"));
            assertTrue(payload.contains("\"response_time_ms\":12.34"));
            assertTrue(payload.contains("\"consumer_id\":\"acme_corp\""));
            assertTrue(payload.contains("\"timestamp\":"));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }
}
