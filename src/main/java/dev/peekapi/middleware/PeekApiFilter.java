package dev.peekapi.middleware;

import dev.peekapi.PeekApiClient;
import dev.peekapi.PeekApiOptions;
import dev.peekapi.RequestEvent;
import dev.peekapi.internal.ConsumerIdentifier;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Jakarta Servlet Filter that tracks API requests via PeekAPI. Wraps the response to capture status
 * code and response size, then records the event after the request completes.
 *
 * <p>Usage in web.xml:
 *
 * <pre>
 * &lt;filter&gt;
 *   &lt;filter-name&gt;peekapi&lt;/filter-name&gt;
 *   &lt;filter-class&gt;dev.peekapi.middleware.PeekApiFilter&lt;/filter-class&gt;
 *   &lt;init-param&gt;
 *     &lt;param-name&gt;apiKey&lt;/param-name&gt;
 *     &lt;param-value&gt;your-api-key&lt;/param-value&gt;
 *   &lt;/init-param&gt;
 * &lt;/filter&gt;
 * </pre>
 *
 * <p>Or with Spring Boot via {@link PeekApiAutoConfiguration}.
 */
public class PeekApiFilter implements Filter {

  private PeekApiClient client;
  private PeekApiOptions options;
  private Function<HttpServletRequest, String> identifyConsumer;

  /** Default constructor for servlet container instantiation. */
  public PeekApiFilter() {}

  /**
   * Constructor for programmatic use (e.g., Spring Boot auto-configuration).
   *
   * @param client the PeekAPI client
   * @param options the options (for identifyConsumer and collectQueryString)
   */
  public PeekApiFilter(PeekApiClient client, PeekApiOptions options) {
    this.client = client;
    this.options = options;
    this.identifyConsumer = options != null ? options.getIdentifyConsumer() : null;
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    if (client != null) return; // Already initialized via constructor

    String apiKey = filterConfig.getInitParameter("apiKey");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new ServletException("PeekAPI: apiKey init parameter is required");
    }

    PeekApiOptions.Builder builder = PeekApiOptions.builder(apiKey);

    String endpoint = filterConfig.getInitParameter("endpoint");
    if (endpoint != null && !endpoint.isEmpty()) {
      builder.endpoint(endpoint);
    }

    String debug = filterConfig.getInitParameter("debug");
    if ("true".equalsIgnoreCase(debug)) {
      builder.debug(true);
    }

    String collectQuery = filterConfig.getInitParameter("collectQueryString");
    if ("true".equalsIgnoreCase(collectQuery)) {
      builder.collectQueryString(true);
    }

    this.options = builder.build();
    this.client = new PeekApiClient(this.options);
    this.identifyConsumer = this.options.getIdentifyConsumer();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (client == null
        || !(request instanceof HttpServletRequest)
        || !(response instanceof HttpServletResponse)) {
      chain.doFilter(request, response);
      return;
    }

    HttpServletRequest httpReq = (HttpServletRequest) request;
    HttpServletResponse httpResp = (HttpServletResponse) response;

    long startNanos = System.nanoTime();
    CountingResponseWrapper wrapper = new CountingResponseWrapper(httpResp);

    try {
      chain.doFilter(httpReq, wrapper);
    } finally {
      try {
        trackRequest(httpReq, wrapper, startNanos);
      } catch (Exception e) {
        // Never let analytics break the response
      }
    }
  }

  @Override
  public void destroy() {
    if (client != null) {
      client.shutdown();
    }
  }

  private void trackRequest(
      HttpServletRequest req, CountingResponseWrapper wrapper, long startNanos) {
    long durationNanos = System.nanoTime() - startNanos;
    // Round to 2 decimal places in milliseconds
    double durationMs = Math.round(durationNanos / 10_000.0) / 100.0;

    String consumerId = resolveConsumerId(req);

    String path = req.getRequestURI();
    if (options != null && options.isCollectQueryString()) {
      String query = req.getQueryString();
      if (query != null && !query.isEmpty()) {
        path += "?" + sortQueryParams(query);
      }
    }

    int requestSize = req.getContentLength() > 0 ? req.getContentLength() : 0;

    RequestEvent event =
        new RequestEvent(
            req.getMethod(),
            path,
            wrapper.getStatus(),
            durationMs,
            requestSize,
            wrapper.getByteCount(),
            consumerId,
            null);

    client.track(event);
  }

  private String resolveConsumerId(HttpServletRequest req) {
    // Custom callback takes priority
    if (identifyConsumer != null) {
      try {
        return identifyConsumer.apply(req);
      } catch (Exception e) {
        // Swallow — fall through to default strategy
      }
    }
    // Default: x-api-key or hashed Authorization
    return ConsumerIdentifier.identify(req.getHeader("x-api-key"), req.getHeader("Authorization"));
  }

  public static String sortQueryParams(String query) {
    if (query == null || query.isEmpty()) return "";
    String[] pairs = query.split("&");
    Arrays.sort(pairs);
    Map<String, String> sorted = new TreeMap<>();
    for (String pair : pairs) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        sorted.put(pair.substring(0, eq), pair.substring(eq + 1));
      } else {
        sorted.put(pair, "");
      }
    }
    return sorted.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining("&"));
  }

  /** Response wrapper that captures status code and counts bytes written. */
  public static class CountingResponseWrapper extends HttpServletResponseWrapper {
    private int byteCount = 0;
    private int status = 200;
    private CountingOutputStream countingStream;
    private CountingPrintWriter countingWriter;

    public CountingResponseWrapper(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setStatus(int sc) {
      this.status = sc;
      super.setStatus(sc);
    }

    @Override
    public void sendError(int sc) throws IOException {
      this.status = sc;
      super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      this.status = sc;
      super.sendError(sc, msg);
    }

    @Override
    public int getStatus() {
      return status;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
      if (countingStream == null) {
        countingStream = new CountingOutputStream(super.getOutputStream());
      }
      return countingStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
      if (countingWriter == null) {
        countingWriter = new CountingPrintWriter(super.getWriter());
      }
      return countingWriter;
    }

    public int getByteCount() {
      if (countingStream != null) {
        return countingStream.getByteCount();
      }
      if (countingWriter != null) {
        return countingWriter.getByteCount();
      }
      return byteCount;
    }
  }

  private static class CountingOutputStream extends ServletOutputStream {
    private final ServletOutputStream delegate;
    private int byteCount = 0;

    CountingOutputStream(ServletOutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      byteCount++;
    }

    @Override
    public void write(byte[] b) throws IOException {
      delegate.write(b);
      byteCount += b.length;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      byteCount += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
      delegate.setWriteListener(writeListener);
    }

    int getByteCount() {
      return byteCount;
    }
  }

  private static class CountingPrintWriter extends PrintWriter {
    private int byteCount = 0;

    CountingPrintWriter(PrintWriter delegate) {
      super(delegate, true);
    }

    @Override
    public void write(int c) {
      super.write(c);
      byteCount +=
          Character.toString((char) c).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    @Override
    public void write(char[] buf, int off, int len) {
      super.write(buf, off, len);
      byteCount +=
          new String(buf, off, len).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    @Override
    public void write(String s, int off, int len) {
      super.write(s, off, len);
      byteCount +=
          s.substring(off, off + len).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    int getByteCount() {
      return byteCount;
    }
  }
}
