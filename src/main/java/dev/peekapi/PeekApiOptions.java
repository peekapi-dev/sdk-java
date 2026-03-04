package dev.peekapi;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/** Configuration options for the PeekAPI client. Use the {@link Builder} to construct. */
public class PeekApiOptions {

  static final String DEFAULT_ENDPOINT = "https://ingest.peekapi.dev/v1/events";
  static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(15);
  static final int DEFAULT_BATCH_SIZE = 250;
  static final int DEFAULT_MAX_BUFFER_SIZE = 10_000;
  static final long DEFAULT_MAX_STORAGE_BYTES = 5_242_880L; // 5 MB
  static final int DEFAULT_MAX_EVENT_BYTES = 65_536; // 64 KB

  private final String apiKey;
  private final String endpoint;
  private final Duration flushInterval;
  private final int batchSize;
  private final int maxBufferSize;
  private final boolean debug;
  private final Function<jakarta.servlet.http.HttpServletRequest, String> identifyConsumer;
  private final String storagePath;
  private final long maxStorageBytes;
  private final int maxEventBytes;
  private final boolean collectQueryString;
  private final Consumer<Exception> onError;
  private final SSLContext sslContext;

  private PeekApiOptions(Builder builder) {
    this.apiKey = builder.apiKey;
    this.endpoint = builder.endpoint;
    this.flushInterval = builder.flushInterval;
    this.batchSize = builder.batchSize;
    this.maxBufferSize = builder.maxBufferSize;
    this.debug = builder.debug;
    this.identifyConsumer = builder.identifyConsumer;
    this.storagePath = builder.storagePath;
    this.maxStorageBytes = builder.maxStorageBytes;
    this.maxEventBytes = builder.maxEventBytes;
    this.collectQueryString = builder.collectQueryString;
    this.onError = builder.onError;
    this.sslContext = builder.sslContext;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Duration getFlushInterval() {
    return flushInterval;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public int getMaxBufferSize() {
    return maxBufferSize;
  }

  public boolean isDebug() {
    return debug;
  }

  public Function<jakarta.servlet.http.HttpServletRequest, String> getIdentifyConsumer() {
    return identifyConsumer;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public long getMaxStorageBytes() {
    return maxStorageBytes;
  }

  public int getMaxEventBytes() {
    return maxEventBytes;
  }

  public boolean isCollectQueryString() {
    return collectQueryString;
  }

  public Consumer<Exception> getOnError() {
    return onError;
  }

  public SSLContext getSslContext() {
    return sslContext;
  }

  public static Builder builder(String apiKey) {
    return new Builder(apiKey);
  }

  public static class Builder {
    private final String apiKey;
    private String endpoint = DEFAULT_ENDPOINT;
    private Duration flushInterval = DEFAULT_FLUSH_INTERVAL;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
    private boolean debug = false;
    private Function<jakarta.servlet.http.HttpServletRequest, String> identifyConsumer;
    private String storagePath;
    private long maxStorageBytes = DEFAULT_MAX_STORAGE_BYTES;
    private int maxEventBytes = DEFAULT_MAX_EVENT_BYTES;
    private boolean collectQueryString = false;
    private Consumer<Exception> onError;
    private SSLContext sslContext;

    private Builder(String apiKey) {
      this.apiKey = apiKey;
    }

    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder flushInterval(Duration flushInterval) {
      this.flushInterval = flushInterval;
      return this;
    }

    public Builder batchSize(int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Builder maxBufferSize(int maxBufferSize) {
      this.maxBufferSize = maxBufferSize;
      return this;
    }

    public Builder debug(boolean debug) {
      this.debug = debug;
      return this;
    }

    public Builder identifyConsumer(
        Function<jakarta.servlet.http.HttpServletRequest, String> identifyConsumer) {
      this.identifyConsumer = identifyConsumer;
      return this;
    }

    public Builder storagePath(String storagePath) {
      this.storagePath = storagePath;
      return this;
    }

    public Builder maxStorageBytes(long maxStorageBytes) {
      this.maxStorageBytes = maxStorageBytes;
      return this;
    }

    public Builder maxEventBytes(int maxEventBytes) {
      this.maxEventBytes = maxEventBytes;
      return this;
    }

    public Builder collectQueryString(boolean collectQueryString) {
      this.collectQueryString = collectQueryString;
      return this;
    }

    public Builder onError(Consumer<Exception> onError) {
      this.onError = onError;
      return this;
    }

    public Builder sslContext(SSLContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    public PeekApiOptions build() {
      return new PeekApiOptions(this);
    }
  }
}
