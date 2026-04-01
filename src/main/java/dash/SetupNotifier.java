package dash;

public class SetupNotifier {

    public SetupNotifier() {
    }

    public void sendDiscordSetupNotificationIfConfigured() {
        // No-op in daemon mode; setup bootstrap is handled via default admin creation.
    }

    public static String buildSetupUrl(String panelUrl, String code) {
        String base = panelUrl == null || panelUrl.isBlank() ? "http://localhost:" + NeoDash.getWebPort() : panelUrl;
        return base + "/setup?code=" + code;
    }
}
