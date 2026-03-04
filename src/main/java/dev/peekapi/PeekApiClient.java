package dev.peekapi;

import dev.peekapi.internal.DiskPersistence;
import dev.peekapi.internal.SsrfProtection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PeekAPI analytics client. Buffers request events in memory and periodically flushes them to the
 * PeekAPI ingest endpoint. Features exponential backoff, disk persistence for undelivered events,
 * and graceful shutdown.
 */
public class PeekApiClient {

    static final String SDK_VERSION = "0.1.0";
    static final String SDK_HEADER = "java/" + SDK_VERSION;
    static final int MAX_PATH_LENGTH = 2048;
    static final int MAX_METHOD_LENGTH = 16;
    static final int MAX_CONSUMER_ID_LENGTH = 256;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    static final long BASE_BACKOFF_MS = 1000;
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    static final long DISK_RECOVERY_INTERVAL_MS = 60_000;

    private static final int[] RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504};

    private final PeekApiOptions options;
    private final URI endpointUri;
    private final String storagePath;
    private final HttpClient httpClient;

    private final List<RequestEvent> buffer = new ArrayList<>();
    private final ReentrantLock bufferLock = new ReentrantLock();

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> flushTask;
    private Thread shutdownHook;

    private volatile boolean flushInFlight = false;
    private volatile int consecutiveFailures = 0;
    private volatile long backoffUntil = 0;
    private volatile long lastDiskRecovery = 0;
    private volatile boolean shutdown = false;

    /**
     * Creates a new PeekAPI client with the given options.
     *
     * @param options configuration options
     * @throws IllegalArgumentException if options are invalid
     */
    public PeekApiClient(PeekApiOptions options) {
        validateOptions(options);
        this.options = options;
        this.endpointUri = URI.create(options.getEndpoint());
        this.storagePath =
                options.getStoragePath() != null
                        ? options.getStoragePath()
                        : DiskPersistence.defaultStoragePath(options.getEndpoint());

        // Build HTTP client
        HttpClient.Builder clientBuilder =
                HttpClient.newBuilder()
                        .connectTimeout(SEND_TIMEOUT)
                        .version(HttpClient.Version.HTTP_1_1);
        if (options.getSslContext() != null) {
            clientBuilder.sslContext(options.getSslContext());
        }
        this.httpClient = clientBuilder.build();

        // Load persisted events from disk
        loadFromDisk();

        // Start periodic flush
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);
        exec.setRemoveOnCancelPolicy(true);
        exec.setThreadFactory(
                r -> {
                    Thread t = new Thread(r, "peekapi-flush");
                    t.setDaemon(true);
                    return t;
                });
        this.scheduler = exec;

        long intervalMs = options.getFlushInterval().toMillis();
        this.flushTask = scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // Register shutdown hook
        this.shutdownHook =
                new Thread(
                        () -> {
                            shutdown = true;
                            doShutdown();
                        },
                        "peekapi-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        if (options.isDebug()) {
            log("PeekAPI client initialized, endpoint=" + options.getEndpoint());
        }
    }

    /**
     * Tracks a single request event. The event is buffered and will be sent in the next flush
     * cycle.
     *
     * @param event the event to track
     */
    public void track(RequestEvent event) {
        if (shutdown || event == null) return;

        // Sanitize
        if (event.getMethod() != null && event.getMethod().length() > MAX_METHOD_LENGTH) {
            event.setMethod(event.getMethod().substring(0, MAX_METHOD_LENGTH));
        }
        if (event.getPath() != null && event.getPath().length() > MAX_PATH_LENGTH) {
            event.setPath(event.getPath().substring(0, MAX_PATH_LENGTH));
        }
        if (event.getConsumerId() != null
                && event.getConsumerId().length() > MAX_CONSUMER_ID_LENGTH) {
            event.setConsumerId(event.getConsumerId().substring(0, MAX_CONSUMER_ID_LENGTH));
        }
        if (event.getMethod() != null) {
            event.setMethod(event.getMethod().toUpperCase());
        }
        if (event.getTimestamp() == null || event.getTimestamp().isEmpty()) {
            event.setTimestamp(
                    java.time.Instant.now()
                            .atZone(java.time.ZoneOffset.UTC)
                            .format(
                                    java.time.format.DateTimeFormatter.ofPattern(
                                            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")));
        }

        // Check event size
        String json = event.toJson();
        if (json.length() > options.getMaxEventBytes()) {
            // Try stripping metadata first
            event.setMetadata(null);
            json = event.toJson();
            if (json.length() > options.getMaxEventBytes()) {
                if (options.isDebug()) {
                    log("Event too large, dropping (" + json.length() + " bytes)");
                }
                return;
            }
        }

        boolean shouldFlush = false;
        bufferLock.lock();
        try {
            if (buffer.size() >= options.getMaxBufferSize()) {
                if (options.isDebug()) {
                    log("Buffer full (" + options.getMaxBufferSize() + "), dropping oldest event");
                }
                buffer.remove(0);
            }
            buffer.add(event);
            shouldFlush = buffer.size() >= options.getBatchSize();
        } finally {
            bufferLock.unlock();
        }

        if (shouldFlush) {
            scheduler.execute(this::flush);
        }
    }

    /** Flushes buffered events to the ingest endpoint. */
    public void flush() {
        if (flushInFlight || shutdown) return;
        doFlush();
    }

    private void doFlush() {

        // Check backoff
        if (consecutiveFailures > 0 && System.currentTimeMillis() < backoffUntil) {
            return;
        }

        List<RequestEvent> batch;
        bufferLock.lock();
        try {
            if (buffer.isEmpty()) return;
            int count = Math.min(options.getBatchSize(), buffer.size());
            batch = new ArrayList<>(buffer.subList(0, count));
            buffer.subList(0, count).clear();
        } finally {
            bufferLock.unlock();
        }

        flushInFlight = true;
        try {
            send(batch);
            consecutiveFailures = 0;
            backoffUntil = 0;
            DiskPersistence.cleanupRecoveryFile(storagePath);
            if (options.isDebug()) {
                log("Flushed " + batch.size() + " events");
            }
        } catch (SendException e) {
            if (e.isRetryable()) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    // Persist to disk and reset
                    DiskPersistence.persistToDisk(
                            storagePath, batch, options.getMaxStorageBytes());
                    consecutiveFailures = 0;
                    backoffUntil = 0;
                    if (options.isDebug()) {
                        log("Max failures reached, persisted " + batch.size() + " events to disk");
                    }
                } else {
                    // Re-insert events at front of buffer
                    bufferLock.lock();
                    try {
                        int space = options.getMaxBufferSize() - buffer.size();
                        int reinsert = Math.min(batch.size(), space);
                        if (reinsert > 0) {
                            buffer.addAll(0, batch.subList(0, reinsert));
                        }
                    } finally {
                        bufferLock.unlock();
                    }
                    // Compute backoff
                    double jitter = 0.5 + Math.random() * 0.5;
                    long backoff =
                            (long)
                                    (BASE_BACKOFF_MS
                                            * Math.pow(2, consecutiveFailures - 1)
                                            * jitter);
                    backoffUntil = System.currentTimeMillis() + backoff;
                    if (options.isDebug()) {
                        log("Retryable error (attempt "
                                + consecutiveFailures
                                + "), backoff "
                                + backoff
                                + "ms: "
                                + e.getMessage());
                    }
                }
            } else {
                // Non-retryable: persist to disk, don't increment failures
                DiskPersistence.persistToDisk(storagePath, batch, options.getMaxStorageBytes());
                if (options.isDebug()) {
                    log("Non-retryable error, persisted to disk: " + e.getMessage());
                }
            }
            if (options.getOnError() != null) {
                try {
                    options.getOnError().accept(e);
                } catch (Exception ignored) {
                    // Don't let error callback break flush
                }
            }
        } catch (Exception e) {
            // Unexpected error — treat as retryable
            consecutiveFailures++;
            bufferLock.lock();
            try {
                int space = options.getMaxBufferSize() - buffer.size();
                int reinsert = Math.min(batch.size(), space);
                if (reinsert > 0) {
                    buffer.addAll(0, batch.subList(0, reinsert));
                }
            } finally {
                bufferLock.unlock();
            }
            if (options.getOnError() != null) {
                try {
                    options.getOnError().accept(e);
                } catch (Exception ignored) {
                    // Don't let error callback break flush
                }
            }
        } finally {
            flushInFlight = false;
        }
    }

    /**
     * Gracefully shuts down the client. Stops the periodic flush, performs a final flush, and
     * persists any remaining events to disk.
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;

        // Remove shutdown hook (if we're calling shutdown explicitly, not from hook)
        try {
            if (shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }

        doShutdown();
    }

    /** Returns the current number of buffered events. */
    public int getBufferSize() {
        bufferLock.lock();
        try {
            return buffer.size();
        } finally {
            bufferLock.unlock();
        }
    }

    /** Returns the number of consecutive send failures. */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    // --- Internal methods ---

    private void tick() {
        try {
            flush();

            // Periodic disk recovery
            long now = System.currentTimeMillis();
            if (now - lastDiskRecovery >= DISK_RECOVERY_INTERVAL_MS) {
                lastDiskRecovery = now;
                loadFromDisk();
            }
        } catch (Exception e) {
            if (options.isDebug()) {
                log("Tick error: " + e.getMessage());
            }
        }
    }

    private void loadFromDisk() {
        bufferLock.lock();
        try {
            int space = options.getMaxBufferSize() - buffer.size();
            if (space <= 0) return;

            List<RequestEvent> recovered =
                    DiskPersistence.loadFromDisk(storagePath, space);
            if (!recovered.isEmpty()) {
                buffer.addAll(recovered);
                if (options.isDebug()) {
                    log("Recovered " + recovered.size() + " events from disk");
                }
            }
        } finally {
            bufferLock.unlock();
        }
    }

    private void send(List<RequestEvent> events) throws SendException {
        // Build JSON payload
        StringBuilder payload = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) payload.append(",");
            payload.append(events.get(i).toJson());
        }
        payload.append("]");

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(endpointUri)
                            .timeout(SEND_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("x-api-key", options.getApiKey())
                            .header("x-peekapi-sdk", SDK_HEADER)
                            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return; // Success
            }

            boolean retryable = isRetryableStatus(status);
            String body = response.body();
            if (body != null && body.length() > 1024) {
                body = body.substring(0, 1024);
            }
            throw new SendException(
                    "HTTP " + status + ": " + body, retryable);
        } catch (SendException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new SendException("Network error: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendException("Request interrupted", true);
        }
    }

    private void doShutdown() {
        // Stop periodic flush
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Final flush
        flushInFlight = false; // Allow flush to proceed
        doFlush();

        // Persist remaining events
        bufferLock.lock();
        try {
            if (!buffer.isEmpty()) {
                DiskPersistence.persistToDisk(
                        storagePath, new ArrayList<>(buffer), options.getMaxStorageBytes());
                buffer.clear();
                if (options.isDebug()) {
                    log("Persisted remaining events to disk on shutdown");
                }
            }
        } finally {
            bufferLock.unlock();
        }

        if (options.isDebug()) {
            log("Client shut down");
        }
    }

    private static void validateOptions(PeekApiOptions options) {
        if (options.getApiKey() == null || options.getApiKey().isEmpty()) {
            throw new IllegalArgumentException("API key is required");
        }
        // Check for CRLF/null byte injection in API key
        String key = options.getApiKey();
        if (key.contains("\r") || key.contains("\n") || key.contains("\0")) {
            throw new IllegalArgumentException("API key contains invalid characters");
        }

        String endpoint = options.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("Endpoint is required");
        }

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid endpoint URL: " + endpoint);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Endpoint must have a host");
        }

        // HTTPS enforced, except for localhost
        if ("http".equalsIgnoreCase(scheme)) {
            if (!SsrfProtection.isLocalhost(host)) {
                throw new IllegalArgumentException(
                        "HTTPS is required for non-localhost endpoints");
            }
        } else if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "Endpoint must use http or https scheme");
        }

        // SSRF protection — validate host is not a private IP
        if (!"http".equalsIgnoreCase(scheme) || !SsrfProtection.isLocalhost(host)) {
            SsrfProtection.validateHost(host);
        }
    }

    private static boolean isRetryableStatus(int status) {
        for (int code : RETRYABLE_STATUS_CODES) {
            if (status == code) return true;
        }
        return false;
    }

    private void log(String message) {
        System.err.println("[PeekAPI] " + message);
    }

    /** Internal exception for send failures with retry information. */
    static class SendException extends Exception {
        private final boolean retryable;

        SendException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        boolean isRetryable() {
            return retryable;
        }
    }
}
