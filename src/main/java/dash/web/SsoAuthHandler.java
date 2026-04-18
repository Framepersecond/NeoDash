package dash.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dash.AdminWebServer;
import dash.WebAuth;
import dash.bridge.BridgeSecurity;
import dash.data.DatabaseManager;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Handles inbound SSO authentication requests from bridge servers (Dash / FabricDash).
 *
 * URL format: GET /sso?user=USERNAME&timestamp=EPOCH_SECONDS&signature=HMAC_HEX
 *
 * The HMAC-SHA256 signature is verified against every known bridge_secret from
 * the servers table, plus the optional global SSO secret in global_settings.
 */
public class SsoAuthHandler implements HttpHandler {

    private static final long MAX_AGE_SECONDS = 300L;
    private static final ConcurrentHashMap<String, Long> USED_SIGNATURES = new ConcurrentHashMap<>();

    private final WebAuth auth;
    private final AdminWebServer webServer;
    private final DatabaseManager databaseManager;

    public SsoAuthHandler(WebAuth auth, AdminWebServer webServer, DatabaseManager databaseManager) {
        this.auth = auth;
        this.webServer = webServer;
        this.databaseManager = databaseManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String ssoEnabled = databaseManager.getGlobalSetting("sso_enabled", "true");
        if ("false".equalsIgnoreCase(ssoEnabled)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String username = query.get("user");
        String timestampRaw = query.get("timestamp");
        String signature = query.get("signature");

        if (signature == null || username == null || timestampRaw == null) {
            redirect(exchange, "/login");
            return;
        }

        if (username.isBlank()) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }
        String normalizedUser = username.trim();

        long incomingTimestamp;
        try {
            incomingTimestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException ex) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - incomingTimestamp) > MAX_AGE_SECONDS) {
            redirect(exchange, "/login?error=sso_expired");
            return;
        }

        String localHmacInput = normalizedUser.toLowerCase(Locale.ROOT) + ":" + timestampRaw;
        String normalizedSignature = BridgeSecurity.normalizeHex(signature);

        if (!verifyAgainstKnownSecrets(localHmacInput, normalizedSignature)) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        if (isReplay(normalizedSignature, System.currentTimeMillis())) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        WebAuth.BridgeSsoResult result = auth.getOrCreateBridgeUserForSso(normalizedUser);
        String sessionUser = result.username() == null ? normalizedUser : result.username();
        if (!result.approved() || !isApprovedBridgeUser(sessionUser)) {
            redirect(exchange, "/waiting-room?user=" + encode(normalizedUser));
            return;
        }

        webServer.createAuthenticatedSession(exchange, sessionUser);
        redirect(exchange, "/");
    }

    private boolean verifyAgainstKnownSecrets(String hmacInput, String normalizedSignature) {
        // Try global SSO secret first
        String globalSecret = databaseManager.getGlobalSetting("sso_secret", "").trim();
        if (!globalSecret.isBlank() && matchesSecret(hmacInput, normalizedSignature, globalSecret)) {
            return true;
        }

        // Try each server's bridge secret
        List<String> secrets = databaseManager.getAllBridgeSecrets();
        for (String secret : secrets) {
            if (matchesSecret(hmacInput, normalizedSignature, secret)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSecret(String hmacInput, String normalizedSignature, String secret) {
        String expected = hmacSha256Hex(hmacInput, secret);
        if (expected == null || normalizedSignature.length() != expected.length()) {
            return false;
        }
        return BridgeSecurity.equalsConstantTime(expected, normalizedSignature);
    }

    private boolean isReplay(String signature, long now) {
        cleanupReplayCache(now);
        Long existing = USED_SIGNATURES.putIfAbsent(signature, now);
        if (existing == null) {
            return false;
        }
        return (now - existing) <= (MAX_AGE_SECONDS * 1000L);
    }

    private void cleanupReplayCache(long now) {
        USED_SIGNATURES.entrySet().removeIf(entry -> (now - entry.getValue()) > (MAX_AGE_SECONDS * 1000L));
    }

    private boolean isApprovedBridgeUser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        WebAuth.UserInfo info = auth.getUsers().get(username);
        return info != null && info.bridgeUser() && info.bridgeApproved();
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                values.put(
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
