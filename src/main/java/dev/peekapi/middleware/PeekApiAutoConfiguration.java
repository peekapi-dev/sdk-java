package dev.peekapi.middleware;

import dev.peekapi.PeekApiClient;
import dev.peekapi.PeekApiOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for PeekAPI. Reads {@code peekapi.*} properties from {@code
 * application.properties} and registers the {@link PeekApiFilter} on all URL patterns.
 *
 * <p>Required property: {@code peekapi.api-key}
 *
 * <p>Optional properties:
 *
 * <ul>
 *   <li>{@code peekapi.endpoint} — ingest endpoint URL
 *   <li>{@code peekapi.debug} — enable debug logging
 *   <li>{@code peekapi.collect-query-string} — include sorted query params in path
 *   <li>{@code peekapi.flush-interval-seconds} — flush interval in seconds
 *   <li>{@code peekapi.batch-size} — events per flush batch
 * </ul>
 */
@Configuration
@ConditionalOnClass(PeekApiFilter.class)
@ConditionalOnProperty(name = "peekapi.api-key")
public class PeekApiAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "peekapi")
    public PeekApiProperties peekApiProperties() {
        return new PeekApiProperties();
    }

    @Bean
    public PeekApiClient peekApiClient(PeekApiProperties props) {
        PeekApiOptions.Builder builder = PeekApiOptions.builder(props.getApiKey());

        if (props.getEndpoint() != null) {
            builder.endpoint(props.getEndpoint());
        }
        if (props.getFlushIntervalSeconds() > 0) {
            builder.flushInterval(java.time.Duration.ofSeconds(props.getFlushIntervalSeconds()));
        }
        if (props.getBatchSize() > 0) {
            builder.batchSize(props.getBatchSize());
        }
        builder.debug(props.isDebug());
        builder.collectQueryString(props.isCollectQueryString());

        return new PeekApiClient(builder.build());
    }

    @Bean
    public PeekApiFilter peekApiFilter(PeekApiClient client, PeekApiProperties props) {
        PeekApiOptions.Builder builder = PeekApiOptions.builder(props.getApiKey());
        builder.collectQueryString(props.isCollectQueryString());
        return new PeekApiFilter(client, builder.build());
    }

    @Bean
    public FilterRegistrationBean<PeekApiFilter> peekApiFilterRegistration(
            PeekApiFilter peekApiFilter) {
        FilterRegistrationBean<PeekApiFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(peekApiFilter);
        registration.addUrlPatterns("/*");
        registration.setName("peekApiFilter");
        registration.setOrder(1);
        return registration;
    }

    public static class PeekApiProperties {
        private String apiKey;
        private String endpoint;
        private boolean debug = false;
        private boolean collectQueryString = false;
        private int flushIntervalSeconds = 0;
        private int batchSize = 0;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isDebug() {
            return debug;
        }

        public void setDebug(boolean debug) {
            this.debug = debug;
        }

        public boolean isCollectQueryString() {
            return collectQueryString;
        }

        public void setCollectQueryString(boolean collectQueryString) {
            this.collectQueryString = collectQueryString;
        }

        public int getFlushIntervalSeconds() {
            return flushIntervalSeconds;
        }

        public void setFlushIntervalSeconds(int flushIntervalSeconds) {
            this.flushIntervalSeconds = flushIntervalSeconds;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
