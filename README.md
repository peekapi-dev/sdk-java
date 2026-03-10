# PeekAPI — Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/dev.peekapi/peekapi)](https://central.sonatype.com/artifact/dev.peekapi/peekapi)
[![license](https://img.shields.io/github/license/peekapi-dev/sdk-java)](./LICENSE)
[![CI](https://github.com/peekapi-dev/sdk-java/actions/workflows/ci.yml/badge.svg)](https://github.com/peekapi-dev/sdk-java/actions/workflows/ci.yml)

Zero-dependency Java SDK for [PeekAPI](https://peekapi.dev). Jakarta Servlet Filter for any servlet container, with Spring Boot auto-configuration via `application.properties`.

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.peekapi:peekapi:0.1.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.peekapi</groupId>
    <artifactId>peekapi</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quick Start

### Spring Boot (auto-configuration)

Add your API key to `application.properties` — the filter registers automatically on all routes:

```properties
peekapi.api-key=ak_live_xxx
```

Optional properties:

```properties
peekapi.endpoint=https://ingest.peekapi.dev/v1/events
peekapi.debug=false
peekapi.collect-query-string=false
peekapi.flush-interval-seconds=15
peekapi.batch-size=250
```

### Spring Boot (programmatic)

Register the filter bean manually for full control over options:

```java
import dev.peekapi.PeekApiClient;
import dev.peekapi.PeekApiOptions;
import dev.peekapi.middleware.PeekApiFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PeekApiConfig {

    @Bean
    public PeekApiClient peekApiClient() {
        PeekApiOptions options = PeekApiOptions.builder("ak_live_xxx")
                .debug(true)
                .build();
        return new PeekApiClient(options);
    }

    @Bean
    public FilterRegistrationBean<PeekApiFilter> peekApiFilter(PeekApiClient client) {
        PeekApiOptions options = PeekApiOptions.builder("ak_live_xxx").build();
        FilterRegistrationBean<PeekApiFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new PeekApiFilter(client, options));
        reg.addUrlPatterns("/*");
        return reg;
    }
}
```

### Servlet Container (web.xml)

```xml
<filter>
    <filter-name>peekapi</filter-name>
    <filter-class>dev.peekapi.middleware.PeekApiFilter</filter-class>
    <init-param>
        <param-name>apiKey</param-name>
        <param-value>ak_live_xxx</param-value>
    </init-param>
</filter>
<filter-mapping>
    <filter-name>peekapi</filter-name>
    <url-pattern>/*</url-pattern>
</filter-mapping>
```

### Standalone Client

```java
import dev.peekapi.PeekApiClient;
import dev.peekapi.PeekApiOptions;
import dev.peekapi.RequestEvent;

PeekApiOptions options = PeekApiOptions.builder("ak_live_xxx").build();
PeekApiClient client = new PeekApiClient(options);

client.track(new RequestEvent("GET", "/api/users", 200, 42.0, 0, 1024, "consumer_123", null));

// Graceful shutdown (flushes remaining events)
client.shutdown();
```

## Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `apiKey` (required) | `String` | — | Your PeekAPI API key |
| `endpoint` | `String` | `https://ingest.peekapi.dev/v1/events` | Ingestion endpoint URL |
| `flushInterval` | `Duration` | `15s` | Time between automatic flushes |
| `batchSize` | `int` | `250` | Events per batch (triggers flush when reached) |
| `maxBufferSize` | `int` | `10,000` | Maximum events held in memory |
| `maxStorageBytes` | `long` | `5 MB` | Maximum disk fallback file size |
| `maxEventBytes` | `int` | `64 KB` | Per-event size limit (drops if exceeded) |
| `storagePath` | `String` | temp dir | Path for JSONL fallback file |
| `debug` | `boolean` | `false` | Enable debug logging to stderr |
| `collectQueryString` | `boolean` | `false` | Include sorted query params in path |
| `identifyConsumer` | `Function<HttpServletRequest, String>` | auto | Custom consumer identification callback |
| `onError` | `Consumer<Exception>` | `null` | Callback for background flush errors |
| `sslContext` | `SSLContext` | `null` | Custom TLS/SSL configuration |

## How It Works

1. Your application handles requests through the servlet filter (or you call `client.track()` directly)
2. The filter captures method, path, status code, response time, request/response size, and consumer ID
3. Events are buffered in memory and flushed every 15 seconds (or when `batchSize` is reached) via a daemon thread backed by `ScheduledExecutorService`
4. Batches are sent as JSON arrays to the PeekAPI ingest endpoint using `java.net.http.HttpClient`
5. On failure, events are re-inserted into the buffer with exponential backoff (up to 5 retries)
6. After max retries, events are persisted to a JSONL file on disk and recovered on the next flush cycle
7. On JVM shutdown, a shutdown hook flushes remaining events and persists any leftovers to disk

## Consumer Identification

By default, consumers are identified by:

1. `X-API-Key` header — stored as-is
2. `Authorization` header — hashed with SHA-256 (stored as `hash_<hex>`)

Override with the `identifyConsumer` option to use any header or request attribute:

```java
PeekApiOptions options = PeekApiOptions.builder("ak_live_xxx")
        .identifyConsumer(req -> req.getHeader("X-Tenant-ID"))
        .build();
```

The callback receives an `HttpServletRequest` and should return a consumer ID string or `null`.

## Features

- **Zero runtime dependencies** — built on `java.net.http` and `java.security` (JDK 17+)
- **Jakarta Servlet Filter** — drop-in for any servlet container (Tomcat, Jetty, Undertow)
- **Spring Boot auto-configuration** — just set `peekapi.api-key` in `application.properties`
- **Background flush** — daemon thread via `ScheduledExecutorService`, never blocks your requests
- **Disk persistence** — undelivered events saved to JSONL, recovered automatically on next startup
- **Exponential backoff** — retries with jitter on transient failures, gives up after 5 consecutive errors
- **SSRF protection** — validates endpoint host against private IP ranges at construction time
- **Input sanitization** — path (2048), method (16), consumer ID (256) character limits enforced
- **Per-event size limit** — events exceeding `maxEventBytes` are dropped (metadata stripped first)
- **Graceful shutdown** — JVM shutdown hook flushes buffer and persists remaining events to disk

## Requirements

- Java >= 17
- Jakarta Servlet API >= 6.0 (provided by your servlet container or Spring Boot 3.x)

## Contributing

Bug reports and feature requests: [peekapi-dev/community](https://github.com/peekapi-dev/community/issues)

1. Fork & clone the repo
2. Run tests — `./gradlew test`
3. Lint — `./gradlew spotlessCheck`
4. Format — `./gradlew spotlessApply`
5. Submit a PR

## Support

If you find PeekAPI useful, give this repo a star — it helps others discover the project.

### Badge

Show that your API is monitored by PeekAPI:

```markdown
[![Monitored by PeekAPI](https://img.shields.io/badge/monitored%20by-PeekAPI-06b6d4)](https://peekapi.dev)
```

## License

MIT
