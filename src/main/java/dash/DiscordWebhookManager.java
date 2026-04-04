package dash;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class DiscordWebhookManager {

    public static final String EVENT_AUDIT = "audit";
    public static final String EVENT_CHAT = "chat";
    public static final String EVENT_SERVER_START_STOP = "server_start_stop";
    public static final String EVENT_CONSOLE_WARNINGS = "console_warnings";

    public static final String[] ALL_EVENTS = {
            EVENT_AUDIT, EVENT_CHAT, EVENT_SERVER_START_STOP, EVENT_CONSOLE_WARNINGS
    };

    private final Logger logger;
    private final List<WebhookEntry> webhooks = new CopyOnWriteArrayList<>();

    public DiscordWebhookManager() {
        this(Logger.getLogger("NeoDash"));
    }

    public DiscordWebhookManager(Logger logger) {
        this.logger = logger;
    }

    public List<WebhookEntry> getWebhooks() {
        return Collections.unmodifiableList(webhooks);
    }

    public void saveWebhooks(List<WebhookEntry> entries) {
        webhooks.clear();
        if (entries != null) {
            webhooks.addAll(entries);
        }
    }

    public void dispatch(String event, String message) {
        if (webhooks.isEmpty()) {
            return;
        }
        String normalizedEvent = event == null ? "" : event.toLowerCase(Locale.ROOT);
        for (WebhookEntry entry : webhooks) {
            if (entry.events().contains(normalizedEvent)) {
                sendPayload(entry.url(), message);
            }
        }
    }

    public void dispatchEmbed(String event, String title, String description, int color) {
        if (webhooks.isEmpty()) {
            return;
        }
        String normalizedEvent = event == null ? "" : event.toLowerCase(Locale.ROOT);
        String payload = "{\"embeds\":[{\"title\":\"" + escapeJson(title)
                + "\",\"description\":\"" + escapeJson(description)
                + "\",\"color\":" + color + "}]}";
        for (WebhookEntry entry : webhooks) {
            if (entry.events().contains(normalizedEvent)) {
                sendRawPayload(entry.url(), payload);
            }
        }
    }

    private void sendPayload(String webhookUrl, String message) {
        String payload = "{\"content\":\"" + escapeJson(message) + "\"}";
        sendRawPayload(webhookUrl, payload);
    }

    private void sendRawPayload(String webhookUrl, String jsonPayload) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                logger.warning("[Webhook] Non-2xx response (" + status + ") from: " + webhookUrl);
            }
        } catch (Exception ex) {
            logger.warning("[Webhook] Failed to send: " + ex.getMessage());
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    public record WebhookEntry(String url, List<String> events) {
        public WebhookEntry {
            events = events == null ? List.of() : List.copyOf(events);
        }

        public boolean subscribedTo(String event) {
            return events.contains(event == null ? "" : event.toLowerCase(Locale.ROOT));
        }
    }
}
