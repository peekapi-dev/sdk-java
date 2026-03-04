package dev.peekapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import dev.peekapi.middleware.PeekApiFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeekApiFilterTest {

    @TempDir java.nio.file.Path tempDir;

    private String tmpStoragePath() {
        return tempDir.resolve("peekapi-filter-test.jsonl").toString();
    }

    private HttpServletRequest mockRequest(String method, String path) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(path);
        when(req.getContentLength()).thenReturn(0);
        return req;
    }

    private HttpServletResponse mockResponse() throws IOException {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ServletOutputStream sos =
                new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        baos.write(b);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {}
                };
        when(resp.getOutputStream()).thenReturn(sos);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(resp.getStatus()).thenReturn(200);
        return resp;
    }

    // --- Basic filter behavior ---

    @Test
    void filterTracksRequest() throws Exception {
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

        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint(url)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1)
                        .storagePath(tmpStoragePath())
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("GET", "/api/v1/users");
            HttpServletResponse resp = mockResponse();
            FilterChain chain = (r, re) -> {};

            filter.doFilter(req, resp, chain);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertFalse(payloads.isEmpty());
            assertTrue(payloads.get(0).contains("\"method\":\"GET\""));
            assertTrue(payloads.get(0).contains("\"/api/v1/users\""));
        } finally {
            filter.destroy();
            server.stop(0);
        }
    }

    @Test
    void filterCapturesStatusCode() throws Exception {
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

        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint(url)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1)
                        .storagePath(tmpStoragePath())
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("POST", "/api/v1/orders");
            HttpServletResponse resp = mockResponse();

            FilterChain chain =
                    (r, re) -> {
                        ((HttpServletResponse) re).setStatus(201);
                    };

            filter.doFilter(req, resp, chain);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(payloads.get(0).contains("\"status_code\":201"));
        } finally {
            filter.destroy();
            server.stop(0);
        }
    }

    @Test
    void filterRecordsTiming() throws Exception {
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

        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint(url)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1)
                        .storagePath(tmpStoragePath())
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("GET", "/api/v1/users");
            HttpServletResponse resp = mockResponse();

            FilterChain chain =
                    (r, re) -> {
                        try {
                            Thread.sleep(50); // At least 50ms
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };

            filter.doFilter(req, resp, chain);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(payloads.get(0).contains("\"response_time_ms\":"));
        } finally {
            filter.destroy();
            server.stop(0);
        }
    }

    // --- Consumer identification ---

    @Test
    void filterUsesXApiKeyForConsumerId() throws Exception {
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

        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint(url)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1)
                        .storagePath(tmpStoragePath())
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("GET", "/api/v1/users");
            when(req.getHeader("x-api-key")).thenReturn("ak_consumer_acme");
            HttpServletResponse resp = mockResponse();
            FilterChain chain = (r, re) -> {};

            filter.doFilter(req, resp, chain);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(payloads.get(0).contains("\"consumer_id\":\"ak_consumer_acme\""));
        } finally {
            filter.destroy();
            server.stop(0);
        }
    }

    @Test
    void filterHashesAuthorizationHeader() throws Exception {
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

        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint(url)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1)
                        .storagePath(tmpStoragePath())
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("GET", "/api/v1/users");
            when(req.getHeader("Authorization")).thenReturn("Bearer my-secret-token");
            HttpServletResponse resp = mockResponse();
            FilterChain chain = (r, re) -> {};

            filter.doFilter(req, resp, chain);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(payloads.get(0).contains("\"consumer_id\":\"hash_"));
        } finally {
            filter.destroy();
            server.stop(0);
        }
    }

    @Test
    void filterUsesCustomIdentifyConsumer() throws Exception {
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

        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint(url)
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1)
                        .storagePath(tmpStoragePath())
                        .identifyConsumer(req -> req.getHeader("x-custom-id"))
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("GET", "/api/v1/users");
            when(req.getHeader("x-custom-id")).thenReturn("custom-consumer");
            HttpServletResponse resp = mockResponse();
            FilterChain chain = (r, re) -> {};

            filter.doFilter(req, resp, chain);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(payloads.get(0).contains("\"consumer_id\":\"custom-consumer\""));
        } finally {
            filter.destroy();
            server.stop(0);
        }
    }

    // --- Null client passthrough ---

    @Test
    void filterPassesThroughWithNullClient() throws Exception {
        PeekApiFilter filter = new PeekApiFilter(null, null);
        HttpServletRequest req = mockRequest("GET", "/test");
        HttpServletResponse resp = mockResponse();

        boolean[] called = {false};
        FilterChain chain = (r, re) -> called[0] = true;

        filter.doFilter(req, resp, chain);
        assertTrue(called[0]);
    }

    // --- Exception safety ---

    @Test
    void filterDoesNotBreakResponseOnTrackingError() throws Exception {
        // Create client with unreachable endpoint — tracking will fail but shouldn't break the chain
        PeekApiOptions options =
                PeekApiOptions.builder("ak_test")
                        .endpoint("http://localhost:19999")
                        .flushInterval(Duration.ofHours(1))
                        .batchSize(1000)
                        .storagePath(tmpStoragePath())
                        .build();
        PeekApiClient client = new PeekApiClient(options);
        PeekApiFilter filter = new PeekApiFilter(client, options);

        try {
            HttpServletRequest req = mockRequest("GET", "/test");
            HttpServletResponse resp = mockResponse();

            boolean[] called = {false};
            FilterChain chain = (r, re) -> called[0] = true;

            // Should not throw even though tracking will eventually fail
            filter.doFilter(req, resp, chain);
            assertTrue(called[0]);
        } finally {
            filter.destroy();
        }
    }

    // --- Query string ---

    @Test
    void sortQueryParamsSortsAlphabetically() {
        assertEquals("a=1&b=2&c=3", PeekApiFilter.sortQueryParams("c=3&a=1&b=2"));
    }

    @Test
    void sortQueryParamsHandlesEmpty() {
        assertEquals("", PeekApiFilter.sortQueryParams(""));
        assertEquals("", PeekApiFilter.sortQueryParams(null));
    }

    @Test
    void sortQueryParamsHandlesSingleParam() {
        assertEquals("key=value", PeekApiFilter.sortQueryParams("key=value"));
    }

    // --- Response size counting ---

    @Test
    void countingResponseWrapperTracksOutputStreamBytes() throws Exception {
        HttpServletResponse resp = mockResponse();
        PeekApiFilter.CountingResponseWrapper wrapper =
                new PeekApiFilter.CountingResponseWrapper(resp);

        wrapper.getOutputStream().write("Hello".getBytes());
        assertEquals(5, wrapper.getByteCount());

        wrapper.getOutputStream().write(" World".getBytes());
        assertEquals(11, wrapper.getByteCount());
    }

    @Test
    void countingResponseWrapperTracksStatus() throws Exception {
        HttpServletResponse resp = mockResponse();
        PeekApiFilter.CountingResponseWrapper wrapper =
                new PeekApiFilter.CountingResponseWrapper(resp);

        assertEquals(200, wrapper.getStatus());
        wrapper.setStatus(404);
        assertEquals(404, wrapper.getStatus());
    }

    @Test
    void countingResponseWrapperTracksSendError() throws Exception {
        HttpServletResponse resp = mockResponse();
        PeekApiFilter.CountingResponseWrapper wrapper =
                new PeekApiFilter.CountingResponseWrapper(resp);

        wrapper.sendError(500);
        assertEquals(500, wrapper.getStatus());
    }
}
