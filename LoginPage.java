package dash.web;

public class LoginPage {

    public static String render() {
        return render("");
    }

    public static String render(String errorMessage) {
        String errorBlock = (errorMessage == null || errorMessage.isBlank())
                ? ""
                : "<div class='rounded-xl border border-rose-500/40 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'>"
                        + escapeHtml(errorMessage)
                        + "</div>";

        String content = "<div class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-2xl p-8 sm:p-10'>"
                + "<div class='mb-8'>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white tracking-tight'>NeoDash Panel Login</h1>"
                + "<p class='text-sm text-slate-400 mt-2'>Melde dich an, um dein Server-Dashboard zu verwalten.</p>"
                + "</div>"
                + errorBlock
                + "<form action='/action' method='post' class='mt-6 space-y-4'>"
                + "<input type='hidden' name='action' value='login'>"
                + "<div>"
                + "<label for='username' class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>Username</label>"
                + "<input id='username' type='text' name='username' required autocomplete='username'"
                + " class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'"
                + " placeholder='Dein Benutzername'>"
                + "</div>"
                + "<div>"
                + "<label for='password' class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>Password</label>"
                + "<input id='password' type='password' name='password' required autocomplete='current-password'"
                + " class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'"
                + " placeholder='Dein Passwort'>"
                + "</div>"
                + "<button type='submit' class='w-full rounded-xl bg-cyan-500 hover:bg-cyan-400 text-black font-bold py-3 transition-colors'>Anmelden</button>"
                + "</form>"
                + "</div>";

        return HtmlTemplate.authPage("Login", content);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

