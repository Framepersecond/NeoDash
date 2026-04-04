package dash.web;

public class InventoryPage {
    public static String render(String playerName) {
        return placeholder("Inventory", playerName);
    }

    public static String renderEnderChest(String playerName) {
        return placeholder("Ender Chest", playerName);
    }

    private static String placeholder(String title, String playerName) {
        String content = HtmlTemplate.statsHeader(-1L)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-10 text-center'>"
                + "<h2 class='text-2xl font-bold text-white mb-2'>" + escapeHtml(title) + "</h2>"
                + "<p class='text-slate-300 text-lg'>Feature requires NeoDash Bridge Plugin (Coming Soon)</p>"
                + "<p class='text-slate-500 text-sm mt-2'>Requested profile: " + escapeHtml(playerName) + "</p>"
                + "</section></main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page(title, "/players", content);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
