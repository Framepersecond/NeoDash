package dash.web;

public class PluginsPage {

    public static String render() {
        String content = HtmlTemplate.statsHeader(-1L)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-10 text-center'>"
                + "<h2 class='text-2xl font-bold text-white mb-2'>Plugins</h2>"
                + "<p class='text-slate-300 text-lg'>Feature requires NeoDash Bridge Plugin (Coming Soon)</p>"
                + "<p class='text-slate-500 text-sm mt-2'>Plugin lifecycle controls are unavailable outside a server JVM.</p>"
                + "</section></main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Plugins", "/plugins", content);
    }
}
