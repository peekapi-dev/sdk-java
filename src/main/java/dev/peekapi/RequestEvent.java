package dev.peekapi;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Represents a single API request event to be tracked. */
public class RequestEvent {

    private static final DateTimeFormatter ISO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private String method;
    private String path;
    private int statusCode;
    private double responseTimeMs;
    private int requestSize;
    private int responseSize;
    private String consumerId;
    private Map<String, Object> metadata;
    private String timestamp;

    public RequestEvent() {}

    public RequestEvent(
            String method,
            String path,
            int statusCode,
            double responseTimeMs,
            int requestSize,
            int responseSize,
            String consumerId,
            Map<String, Object> metadata) {
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
        this.responseTimeMs = responseTimeMs;
        this.requestSize = requestSize;
        this.responseSize = responseSize;
        this.consumerId = consumerId;
        this.metadata = metadata;
        this.timestamp = ISO_FORMAT.format(Instant.now());
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public double getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(double responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public int getRequestSize() {
        return requestSize;
    }

    public void setRequestSize(int requestSize) {
        this.requestSize = requestSize;
    }

    public int getResponseSize() {
        return responseSize;
    }

    public void setResponseSize(int responseSize) {
        this.responseSize = responseSize;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public void setConsumerId(String consumerId) {
        this.consumerId = consumerId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    /** Serialize this event to a JSON string. Zero-dependency implementation. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{");
        sb.append("\"method\":").append(jsonString(method));
        sb.append(",\"path\":").append(jsonString(path));
        sb.append(",\"status_code\":").append(statusCode);
        sb.append(",\"response_time_ms\":").append(responseTimeMs);
        sb.append(",\"request_size\":").append(requestSize);
        sb.append(",\"response_size\":").append(responseSize);
        if (consumerId != null) {
            sb.append(",\"consumer_id\":").append(jsonString(consumerId));
        }
        if (metadata != null && !metadata.isEmpty()) {
            sb.append(",\"metadata\":").append(mapToJson(metadata));
        }
        sb.append(",\"timestamp\":").append(jsonString(timestamp));
        sb.append("}");
        return sb.toString();
    }

    /** Parse a RequestEvent from a JSON string. Minimal parser for disk recovery. */
    public static RequestEvent fromJson(String json) {
        RequestEvent event = new RequestEvent();
        event.setMethod(extractString(json, "method"));
        event.setPath(extractString(json, "path"));
        event.setStatusCode(extractInt(json, "status_code"));
        event.setResponseTimeMs(extractDouble(json, "response_time_ms"));
        event.setRequestSize(extractInt(json, "request_size"));
        event.setResponseSize(extractInt(json, "response_size"));
        event.setConsumerId(extractString(json, "consumer_id"));
        event.setTimestamp(extractString(json, "timestamp"));
        return event;
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonString(entry.getKey())).append(":");
            Object v = entry.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append(jsonString(v.toString()));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        if (start >= json.length()) return null;
        if (json.charAt(start) == 'n') return null; // null
        if (json.charAt(start) != '"') return null;
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    default:
                        sb.append(next);
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int start = idx + search.length();
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return 0;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0.0;
        int start = idx + search.length();
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end))
                        || json.charAt(end) == '.'
                        || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return 0.0;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
