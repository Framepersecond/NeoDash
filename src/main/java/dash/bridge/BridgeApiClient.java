package dash.bridge;

import dash.data.DatabaseManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for NeoDash -> Bridge communication inside target containers.
 */
public class BridgeApiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FAST_REQUEST_TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient httpClient;

    public BridgeApiClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    public BridgeApiClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<BridgeResponse> get(DatabaseManager.ServerRecord server, String endpoint) {
        return sendAuthenticatedRequest(server, endpoint, "GET", null, null, REQUEST_TIMEOUT);
    }

    public CompletableFuture<BridgeResponse> get(DatabaseManager.ServerRecord server, String endpoint, Duration timeout) {
        return sendAuthenticatedRequest(server, endpoint, "GET", null, null, timeout);
    }

    public CompletableFuture<BridgeResponse> postJson(DatabaseManager.ServerRecord server, String endpoint, String body) {
        return sendAuthenticatedRequest(server, endpoint, "POST", body == null ? "" : body, "application/json",
                REQUEST_TIMEOUT);
    }

    public CompletableFuture<StatsSnapshot> fetchStats(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return CompletableFuture.completedFuture(StatsSnapshot.offline());
        }
        return get(server, "stats", FAST_REQUEST_TIMEOUT)
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return StatsSnapshot.offline();
                    }
                    return parseStatsSnapshot(response.body());
                })
                .exceptionally(ex -> StatsSnapshot.offline());
    }

    public CompletableFuture<HealthSnapshot> fetchHealth(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return CompletableFuture.completedFuture(HealthSnapshot.offline());
        }
        return get(server, "health", FAST_REQUEST_TIMEOUT)
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return HealthSnapshot.offline();
                    }
                    return parseHealthSnapshot(response.body());
                })
                .exceptionally(ex -> HealthSnapshot.offline());
    }

    public CompletableFuture<String> fetchConsoleLogs(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return CompletableFuture.completedFuture("[]");
        }
        return get(server, "console")
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return "[]";
                    }
                    String body = response.body();
                    return body == null || body.isBlank() ? "[]" : body;
                })
                .exceptionally(ex -> "[]");
    }

    public CompletableFuture<Boolean> sendConsoleCommand(DatabaseManager.ServerRecord server, String command) {
        if (server == null || command == null || command.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String payload = "{\"command\":\"" + escapeJson(command.trim()) + "\"}";
        return postJson(server, "console", payload)
                .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                .exceptionally(ex -> false);
    }

    public CompletableFuture<BridgeResponse> sendAuthenticatedRequest(
            DatabaseManager.ServerRecord server,
            String endpoint,
            String method,
            String body,
            String contentType) {
        return sendAuthenticatedRequest(server, endpoint, method, body, contentType, REQUEST_TIMEOUT);
    }

    public CompletableFuture<BridgeResponse> sendAuthenticatedRequest(
            DatabaseManager.ServerRecord server,
            String endpoint,
            String method,
            String body,
            String contentType,
            Duration timeout) {
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Server cannot be null"));
        }
        String secret = normalizeSecretToken(server.bridgeSecret());
        if (secret.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Bridge secret is not configured"));
        }

        BridgeTarget target;
        try {
            target = resolveBridgeTarget(server);
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(ex);
        }

        String normalizedEndpoint = normalizeEndpoint(endpoint);
        URI uri = URI.create("http://" + target.host() + ":" + target.port() + "/api/" + normalizedEndpoint);
        Duration requestTimeout = timeout == null ? REQUEST_TIMEOUT : timeout;

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + secret)
                .header("Accept", "application/json");

        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase();
        if ("POST".equals(normalizedMethod)) {
            String payload = body == null ? "" : body;
            builder.header("Content-Type", contentType == null || contentType.isBlank()
                    ? "application/json"
                    : contentType);
            builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.method(normalizedMethod, HttpRequest.BodyPublishers.noBody());
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(response -> new BridgeResponse(response.statusCode(), response.body()));
    }

    private BridgeTarget resolveBridgeTarget(DatabaseManager.ServerRecord server) {
        int configuredPort = server.bridgeApiPort() == null ? 0 : server.bridgeApiPort();
        String rawHost = server.ipAddress() == null || server.ipAddress().isBlank() ? "127.0.0.1" : server.ipAddress().trim();

        String normalized = rawHost;
        if (normalized.regionMatches(true, 0, "http://", 0, 7)) {
            normalized = normalized.substring(7);
        } else if (normalized.regionMatches(true, 0, "https://", 0, 8)) {
            normalized = normalized.substring(8);
        }

        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(0, slashIndex);
        }

        String host = normalized;
        Integer portFromHost = null;

        if (host.startsWith("[")) {
            int endBracket = host.indexOf(']');
            if (endBracket > 0) {
                String suffix = host.substring(endBracket + 1);
                if (suffix.startsWith(":")) {
                    String maybePort = suffix.substring(1);
                    if (isAllDigits(maybePort)) {
                        portFromHost = Integer.parseInt(maybePort);
                    }
                }
                host = host.substring(0, endBracket + 1);
            }
        } else {
            int firstColon = host.indexOf(':');
            int lastColon = host.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                String maybePort = host.substring(lastColon + 1);
                if (isAllDigits(maybePort)) {
                    portFromHost = Integer.parseInt(maybePort);
                    host = host.substring(0, lastColon);
                }
            }
        }

        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        // Bracket plain IPv6 values for URI host safety.
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }

        int port = portFromHost != null ? portFromHost : configuredPort;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Bridge API port is not configured");
        }

        return new BridgeTarget(host, port);
    }

    private boolean isAllDigits(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private record BridgeTarget(String host, int port) {
    }

    private String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.startsWith("api/")) {
            value = value.substring(4);
        }
        return value;
    }

    private String normalizeSecretToken(String secret) {
        if (secret == null) {
            return "";
        }
        String value = secret.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private StatsSnapshot parseStatsSnapshot(String body) {
        Map<String, String> fields = JsonFieldParser.parseFlatObject(body);
        double tps = firstPresentDouble(fields, "tps").orElse(0.0d);
        double mspt = firstPresentDouble(fields, "mspt").orElse(0.0d);
        double cpuUsage = firstPresentDouble(fields,
                "cpuUsage", "cpu_usage", "cpuPercent", "cpu_percent", "cpu")
                .orElse(0.0d);
        int ramUsedMb = firstPresentInt(fields, "ramUsedMb", "ram_used", "ram_used_mb").orElse(0);
        int ramMaxMb = firstPresentInt(fields, "ramMaxMb", "ram_max", "ram_max_mb").orElse(0);
        String dashVersion = firstPresentString(fields,
                "dashVersion", "dash_version", "pluginVersion", "plugin_version", "version")
                .orElse("");
        return new StatsSnapshot(tps, mspt, cpuUsage, ramUsedMb, ramMaxMb, dashVersion);
    }

    private HealthSnapshot parseHealthSnapshot(String body) {
        Map<String, String> fields = JsonFieldParser.parseFlatObject(body);
        String status = firstPresentString(fields, "status").orElse("offline");
        String uptime = firstPresentString(fields, "uptime", "uptimeSeconds", "uptime_seconds").orElse("0s");
        return new HealthSnapshot(status, uptime);
    }

    private Optional<Double> firstPresentDouble(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String value = fields.get(normalizeFieldName(key));
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return Optional.of(Double.parseDouble(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> firstPresentInt(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String value = fields.get(normalizeFieldName(key));
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return Optional.of(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstPresentString(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String value = fields.get(normalizeFieldName(key));
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private String normalizeFieldName(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    public record StatsSnapshot(double tps, double mspt, double cpuUsage, int ramUsedMb, int ramMaxMb,
            String dashVersion) {
        public static StatsSnapshot offline() {
            return new StatsSnapshot(0.0d, 0.0d, 0.0d, 0, 0, "");
        }
    }

    public record HealthSnapshot(String status, String uptime) {
        public static HealthSnapshot offline() {
            return new HealthSnapshot("offline", "0s");
        }

        public boolean online() {
            return status != null && !status.equalsIgnoreCase("offline");
        }
    }

    private static final class JsonFieldParser {

        private JsonFieldParser() {
        }

        private static Map<String, String> parseFlatObject(String json) {
            java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
            if (json == null) {
                return fields;
            }
            String trimmed = json.trim();
            if (trimmed.length() < 2 || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return fields;
            }

            String body = trimmed.substring(1, trimmed.length() - 1).trim();
            if (body.isEmpty()) {
                return fields;
            }

            for (String pair : splitTopLevel(body)) {
                int sep = indexOfTopLevelColon(pair);
                if (sep <= 0) {
                    continue;
                }
                String rawKey = pair.substring(0, sep).trim();
                if (rawKey.length() < 2 || !rawKey.startsWith("\"") || !rawKey.endsWith("\"")) {
                    continue;
                }
                String key = unescapeJson(rawKey.substring(1, rawKey.length() - 1)).toLowerCase(Locale.ROOT);
                String value = normalizeValue(pair.substring(sep + 1).trim());
                fields.put(key, value);
            }
            return fields;
        }

        private static String normalizeValue(String raw) {
            if (raw == null || raw.isBlank() || "null".equals(raw)) {
                return "";
            }
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
                return unescapeJson(raw.substring(1, raw.length() - 1));
            }
            return raw;
        }

        private static java.util.List<String> splitTopLevel(String body) {
            java.util.ArrayList<String> entries = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inString = false;
            boolean escaped = false;
            int depth = 0;

            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                current.append(c);

                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    current.setLength(current.length() - 1);
                    entries.add(current.toString());
                    current.setLength(0);
                }
            }
            if (!current.isEmpty()) {
                entries.add(current.toString());
            }
            return entries;
        }

        private static int indexOfTopLevelColon(String value) {
            boolean inString = false;
            boolean escaped = false;
            int depth = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                    continue;
                }
                if (c == '}' || c == ']') {
                    depth--;
                    continue;
                }
                if (c == ':' && depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        private static String unescapeJson(String value) {
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c != '\\' || i + 1 >= value.length()) {
                    sb.append(c);
                    continue;
                }
                char next = value.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < value.length()) {
                            String hex = value.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                sb.append('u').append(hex);
                                i += 4;
                            }
                        } else {
                            sb.append('u');
                        }
                    }
                    default -> sb.append(next);
                }
            }
            return sb.toString();
        }
    }

    public record BridgeResponse(int statusCode, String body) {
    }
}

