package dash.web;


public class SetupPage {

    public static String render(int step, String message) {
        int currentStep = Math.max(1, Math.min(step, 2));
        String msgBox = (message == null || message.isBlank())
                ? ""
                : "<div class='mb-5 rounded-xl border border-cyan-500/35 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-200'>"
                        + escapeHtml(message)
                        + "</div>";

        String content = "<div class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-2xl p-8 sm:p-10'>"
                + renderHeader(currentStep)
                + msgBox
                + renderStep(currentStep)
                + "</div>";

        return HtmlTemplate.authPage("Setup Wizard", content);
    }

    private static String renderHeader(int step) {
        String[] labels = {
                "Main-Admin",
                "First Server"
        };
        StringBuilder progress = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            boolean active = (i + 1) == step;
            boolean done = (i + 1) < step;
            String badgeClass = done
                    ? "bg-emerald-500/20 text-emerald-300 border-emerald-500/40"
                    : (active
                            ? "bg-cyan-500/20 text-cyan-300 border-cyan-500/40"
                            : "bg-[#0f172a] text-slate-400 border-slate-700");
            progress.append("<div class='flex items-center gap-2 text-xs'>")
                    .append("<span class='inline-flex items-center justify-center w-6 h-6 rounded-full border ")
                    .append(badgeClass)
                    .append("'>")
                    .append(i + 1)
                    .append("</span><span class='")
                    .append(active ? "text-slate-200" : "text-slate-400")
                    .append("'>")
                    .append(labels[i])
                    .append("</span></div>");
        }

        return "<div class='mb-7'>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white tracking-tight'>NeoDash First-Time Setup</h1>"
                + "<p class='text-sm text-slate-400 mt-2'>Configure your panel in a few steps. Optional steps can be skipped.</p>"
                + "<div class='mt-5 grid grid-cols-2 sm:grid-cols-4 gap-2'>"
                + progress
                + "</div>"
                + "</div>";
    }

    private static String renderStep(int step) {
        return switch (step) {
            case 1 -> renderAdminStep();
            case 2 -> renderServerStep();
            default -> renderAdminStep();
        };
    }

    private static String renderAdminStep() {
        return "<form method='post' action='/setup' class='space-y-4'>"
                + "<input type='hidden' name='step' value='1'>"
                + "<input type='hidden' name='intent' value='create_admin'>"
                + inputField("Main-Admin Username", "admin_username", "z. B. owner")
                + passwordField("Main-Admin Password", "admin_password")
                + passwordField("Password bestätigen", "admin_password_confirm")
                + primaryButton("Admin erstellen und weiter")
                + "</form>";
    }

    private static String renderServerStep() {
        return "<form method='post' action='/setup' class='space-y-4'>"
                + "<input type='hidden' name='step' value='2'>"
                + "<input type='hidden' name='intent' value='create_server'>"
                + inputField("Server Name", "name", "My Survival")
                + inputField("Server-Pfad (pathToDir)", "path_to_dir", "/home/mc/servers/survival")
                + inputField("Start Command", "start_command", "./start.sh")
                + inputField("Server Port", "port", "25565")
                + inputField("Server Host / IP", "bridge_host", "127.0.0.1")
                + numberField("Bridge Port (Dash/FabricDash)", "bridge_port", "8081")
                + passwordField("Bridge Secret (Dash/FabricDash)", "bridge_secret")
                + "<p class='text-xs text-slate-500'>Du kannst diesen Schritt uberspringen und Server spater im Dashboard hinzufugen.</p>"
                + primaryButton("Server anlegen & Setup abschließen")
                + "</form>"
                + "<form method='post' action='/setup' class='mt-2'>"
                + "<input type='hidden' name='step' value='2'><input type='hidden' name='intent' value='finish'>"
                + ghostButton("Ohne Server abschließen")
                + "</form>";
    }

    private static String inputField(String label, String name, String placeholder) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='text' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "'></div>";
    }

    private static String numberField(String label, String name, String placeholder) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='number' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "'></div>";
    }

    private static String passwordField(String label, String name) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='password' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='********'></div>";
    }

    private static String primaryButton(String label) {
        return "<button type='submit' class='w-full rounded-xl bg-cyan-500 hover:bg-cyan-400 text-black font-bold py-3 transition-colors'>"
                + escapeHtml(label)
                + "</button>";
    }


    private static String ghostButton(String label) {
        return "<button type='submit' class='w-full rounded-xl border border-slate-600 bg-[#0f172a] text-slate-200 font-semibold py-3 hover:border-cyan-400 hover:text-cyan-300 transition-colors'>"
                + escapeHtml(label)
                + "</button>";
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
