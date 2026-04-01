package dash.web;

public class FilesPage {

    public static String render(String path) {
        return placeholder("File Manager");
    }

    public static String renderEditor(String path) {
        return placeholder("File Editor");
    }

    private static String placeholder(String title) {
        String content = HtmlTemplate.statsHeader(-1L)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-10 text-center'>"
                + "<h2 class='text-2xl font-bold text-white mb-2'>" + title + "</h2>"
                + "<p class='text-slate-300 text-lg'>This view moved to server-scoped routes.</p>"
                + "<p class='text-slate-500 text-sm mt-2'>Use /files?id=&lt;serverId&gt; to open the daemon file manager.</p>"
                + "</section></main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page(title, "/files", content);
    }
}
