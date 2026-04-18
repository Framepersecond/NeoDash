package dash.web;

public class SettingsPage {

    public static String render(String sessionUser, boolean isMainAdmin, String message, int updateIntervalMinutes) {
        return render(sessionUser, isMainAdmin, message, updateIntervalMinutes, true, "");
    }

    public static String render(String sessionUser, boolean isMainAdmin, String message, int updateIntervalMinutes,
            boolean ssoEnabled, String ssoSecret) {
        String infoBanner = "";
        if (message != null && !message.isBlank()) {
            boolean isError = message.startsWith("Error") || message.startsWith("error") || message.toLowerCase().contains("failed");
            String color = isError ? "rose" : "emerald";
            infoBanner = "<div class='mb-5 rounded-xl border border-" + color + "-500/35 bg-" + color + "-500/10 px-4 py-3 text-sm text-" + color + "-200'>"
                    + escapeHtml(message) + "</div>";
        }

        String intervalValue = String.valueOf(Math.max(20, updateIntervalMinutes));

        String ssoSection = "<section class='rounded-3xl bg-[#1e293b] border border-slate-700 p-6 shadow-lg'>"
                + "<h2 class='text-lg font-semibold text-white mb-4'>SSO / Bridge Authentication</h2>"
                + "<p class='text-sm text-slate-400 mb-5'>Allow users to sign in via SSO from Dash/FabricDash bridge servers. "
                + "Bridge users are auto-created and require admin approval. Each server's bridge secret is used for "
                + "signature validation, or you can set a global SSO secret below.</p>"
                + "<form method='post' action='/settings' class='space-y-4'>"
                + "<div class='flex items-center gap-3'>"
                + "<label class='relative inline-flex items-center cursor-pointer'>"
                + "<input type='checkbox' name='sso_enabled' value='true'" + (ssoEnabled ? " checked" : "")
                + " class='sr-only peer'>"
                + "<div class='w-11 h-6 bg-slate-600 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-cyan-500/50 rounded-full peer peer-checked:after:translate-x-full after:content-[\\'\\'] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-cyan-600'></div>"
                + "</label>"
                + "<span class='text-sm text-slate-300'>SSO Enabled</span>"
                + "</div>"
                + "<div>"
                + "<label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>Global SSO Secret (optional)</label>"
                + "<input type='text' name='sso_secret' value='" + escapeHtml(ssoSecret == null ? "" : ssoSecret) + "' placeholder='Leave blank to use per-server bridge secrets'"
                + " class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'>"
                + "<p class='mt-1 text-xs text-slate-500'>If set, this secret will also be checked alongside each server's bridge secret.</p>"
                + "</div>"
                + "<button type='submit' class='rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-bold px-6 py-3 transition-colors inline-flex items-center gap-2'>"
                + "<i data-lucide='save' class='w-4 h-4'></i>Save SSO Settings</button>"
                + "</form>"
                + "</section>";

        String content = HtmlTemplate.statsHeader(-1L)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='max-w-3xl mx-auto space-y-6'>"
                + "<section class='rounded-3xl bg-[#1e293b] border border-slate-700 p-6 shadow-lg'>"
                + "<h1 class='text-2xl font-bold text-white'>Settings</h1>"
                + "<p class='text-sm text-slate-400 mt-1'>Global NeoDash configuration.</p>"
                + "</section>"
                + infoBanner
                + "<section class='rounded-3xl bg-[#1e293b] border border-slate-700 p-6 shadow-lg'>"
                + "<h2 class='text-lg font-semibold text-white mb-4'>Update Check Interval</h2>"
                + "<p class='text-sm text-slate-400 mb-5'>How often NeoDash automatically checks GitHub for NeoDash, Dash, and FabricDash updates. Minimum: 20 minutes. Changes take effect on next restart; use the <a href='/updates' class='text-cyan-400 hover:underline'>Updates page</a> to trigger an immediate check.</p>"
                + "<form method='post' action='/settings' class='flex flex-wrap items-end gap-4'>"
                + "<div>"
                + "<label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>Interval (minutes)</label>"
                + "<input type='number' name='update_interval_minutes' value='" + intervalValue + "' min='20' max='10080'"
                + " class='w-40 rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'>"
                + "<p class='mt-1 text-xs text-slate-500'>Min: 20 min &mdash; Max: 7 days (10080 min)</p>"
                + "</div>"
                + "<button type='submit' class='rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-bold px-6 py-3 transition-colors inline-flex items-center gap-2'>"
                + "<i data-lucide='save' class='w-4 h-4'></i>Save Setting</button>"
                + "</form>"
                + "</section>"
                + ssoSection
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Settings", "/settings", content);
    }

    /** @deprecated Use {@link #render(String, boolean, String, int)} */
    public static String render(String sessionUser, boolean isMainAdmin, String message) {
        return render(sessionUser, isMainAdmin, message, 120);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
