package dash;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import dash.data.DatabaseManager;

public class SetupNotifier {

    private static final Logger LOGGER = Logger.getLogger("NeoDash");

    public SetupNotifier() {
    }

    /**
     * Sends a Discord webhook notification when setup is still required,
     * if a setup-discord-webhook-url is configured in global settings.
     */
    public void sendDiscordSetupNotificationIfConfigured() {
        DatabaseManager db = NeoDash.getDatabaseManager();
        if (db == null) return;

        String webhookUrl = db.getGlobalSetting("setup_discord_webhook_url", "").trim();
        if (webhookUrl.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String setupUrl = buildSetupUrl(null, null);
                String payload = "{\"content\":\"NeoDash setup required. Open: "
                        + escapeJson(setupUrl) + "\"}";

                HttpURLConnection connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                WebActionLogger.log("SETUP_DISCORD_NOTIFY", "Webhook status=" + status);
            } catch (Exception ex) {
                LOGGER.warning("[SetupNotifier] Failed to send setup webhook: " + ex.getMessage());
            }
        });
    }

    public static String buildSetupUrl(String panelUrl, String code) {
        String base;
        if (panelUrl == null || panelUrl.isBlank()) {
            DatabaseManager db = NeoDash.getDatabaseManager();
            String stored = db != null ? db.getGlobalSetting("panel_url", "").trim() : "";
            base = stored.isEmpty() ? "http://localhost:" + NeoDash.getWebPort() : stored;
        } else {
            base = panelUrl;
        }

        String setupBase = base.endsWith("/") ? base + "setup" : base + "/setup";

        if (code == null || code.isBlank()) {
            return setupBase;
        }
        return setupBase + (setupBase.contains("?") ? "&" : "?") + "code=" + code;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
