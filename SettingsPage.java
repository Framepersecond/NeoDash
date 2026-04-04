package dash.web;

public class SettingsPage {

    public static String render(String sessionUser, boolean isMainAdmin, String message) {
        String messageHtml = (message == null || message.isBlank())
                ? ""
                : "<p class='text-slate-400 text-sm mt-3'>" + escapeHtml(message) + "</p>";

        String content = HtmlTemplate.statsHeader(-1L)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-10 text-center'>"
                + "<h2 class='text-2xl font-bold text-white mb-2'>Settings</h2>"
                + "<p class='text-slate-300 text-lg'>Feature requires NeoDash Bridge Plugin (Coming Soon)</p>"
                + "<p class='text-slate-500 text-sm mt-2'>Server-specific settings are being migrated to bridge-backed controls.</p>"
                + messageHtml
                + "</section></main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Settings", "/settings", content);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
