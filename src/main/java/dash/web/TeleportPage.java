package dash.web;

public class TeleportPage {

    public static String render(String targetPlayerName) {
        String content = HtmlTemplate.statsHeader(-1L)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-10 text-center'>"
                + "<h2 class='text-2xl font-bold text-white mb-2'>Teleport</h2>"
                + "<p class='text-slate-300 text-lg'>Feature requires NeoDash Bridge Plugin (Coming Soon)</p>"
                + "<p class='text-slate-500 text-sm mt-2'>Requested player: " + escapeHtml(targetPlayerName) + "</p>"
                + "</section></main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Teleport", "/players", content);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
