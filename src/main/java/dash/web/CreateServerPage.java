package dash.web;

public class CreateServerPage {

    public static String render(long currentUserId, String message, String error) {
        String messageBlock = (message == null || message.isBlank())
                ? ""
                : "<div class='mb-5 rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200'>"
                        + escapeHtml(message)
                        + "</div>";
        String errorBlock = (error == null || error.isBlank())
                ? ""
                : "<div class='mb-5 rounded-xl border border-rose-500/35 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'>"
                        + escapeHtml(error)
                        + "</div>";

        String content = HtmlTemplate.statsHeader(currentUserId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='max-w-3xl mx-auto'>"
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-2xl p-8 sm:p-10'>"
                + "<div class='mb-7'>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white tracking-tight'>Create a new Server</h1>"
                + "<p class='text-sm text-slate-400 mt-2'>Add a server instance and return to the dashboard.</p>"
                + "</div>"
                + messageBlock
                + errorBlock
                + "<form method='post' action='/createServer' class='space-y-4'>"
                + inputField("Server Name", "name", "My Survival")
                + inputField("Base Directory Path", "path_to_dir", "/home/mc/servers/survival")
                + inputField("Server IP Address", "ip_address", "127.0.0.1")
                + selectField("Runner Type", "runner_type")
                + inputField("Start Command", "start_command", "./start.sh")
                + numberField("Dash Plugin Port", "dash_port", "8080")
                + bridgePortField("Bridge Port", "bridge_port")
                + passwordField("Bridge Secret", "bridge_secret", "Bridge Secret")
                + "<div class='flex flex-wrap items-center gap-3 pt-2'>"
                + "<button type='submit' class='rounded-xl bg-cyan-500 hover:bg-cyan-400 text-black font-bold px-6 py-3 transition-colors'>Create Server</button>"
                + "<a href='/' class='rounded-xl border border-slate-600 bg-[#0f172a] text-slate-200 font-semibold px-6 py-3 hover:border-cyan-400 hover:text-cyan-300 transition-colors'>Cancel</a>"
                + "</div>"
                + "</form>"
                + "</section>"
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript();

        return HtmlTemplate.page("Create Server", "/", content);
    }

    private static String inputField(String label, String name, String placeholder) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='text' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "' required></div>";
    }

    private static String numberField(String label, String name, String placeholder) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='number' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "' min='1' max='65535' required></div>";
    }

    private static String bridgePortField(String label, String name) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='number' name='"
                + escapeHtml(name)
                + "' value='8081' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='e.g., 8081' min='1' max='65535' required></div>";
    }

    private static String selectField(String label, String name) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><select name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'>"
                + "<option value='SCREEN'>SCREEN</option>"
                + "<option value='DOCKER'>DOCKER</option>"
                + "</select></div>";
    }

    private static String passwordField(String label, String name, String placeholder) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='password' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "'></div>";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

