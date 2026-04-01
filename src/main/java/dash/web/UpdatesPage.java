package dash.web;

import dash.GithubUpdater;
import dash.bridge.ServerStateCache;
import dash.data.DatabaseManager;

import java.util.List;

public final class UpdatesPage {

    private UpdatesPage() {
    }

    public static String render(long currentUserId,
            String currentNeoVersion,
            List<DatabaseManager.ServerRecord> servers,
            String message,
            String error) {
        String latestNeo = safeVersion(GithubUpdater.LATEST_NEODASH_VERSION);
        String currentNeo = safeVersion(currentNeoVersion);
        boolean neoUpdateAvailable = GithubUpdater.isVersionOutdated(currentNeo, latestNeo);
        boolean updateReady = GithubUpdater.UPDATE_READY;

        StringBuilder rows = new StringBuilder();
        for (DatabaseManager.ServerRecord server : servers == null ? List.<DatabaseManager.ServerRecord>of() : servers) {
            ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(server.id(), server);
            String currentDash = safeVersion(snapshot.dashVersion());
            String dashDisplay = currentDash.isBlank() ? "Not reported" : currentDash;
            String latestDash = safeVersion(GithubUpdater.LATEST_DASH_VERSION);
            String latestDashDisplay = latestDash.isBlank() ? "Unknown" : latestDash;
            boolean outdated = GithubUpdater.isVersionOutdated(currentDash, latestDash);

            rows.append("<tr class='border-b border-slate-800/70 hover:bg-slate-800/40 transition-colors'>")
                    .append("<td class='px-4 py-3 text-slate-100'>").append(escapeHtml(server.name())).append("</td>")
                    .append("<td class='px-4 py-3 text-slate-300 font-mono text-xs'>").append(escapeHtml(dashDisplay))
                    .append("</td>")
                    .append("<td class='px-4 py-3 text-slate-300 font-mono text-xs'>")
                    .append(escapeHtml(latestDashDisplay)).append("</td>")
                    .append("<td class='px-4 py-3'>")
                    .append(outdated
                            ? "<span class='px-2 py-1 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/40 text-xs font-semibold'>Update Available</span>"
                            : "<span class='px-2 py-1 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 text-xs font-semibold'>Up to date</span>")
                    .append("</td>")
                    .append("</tr>");
        }

        if (rows.isEmpty()) {
            rows.append("<tr><td colspan='4' class='px-4 py-8 text-center text-slate-500'>No servers available.</td></tr>");
        }

        String infoBanner = "";
        if (message != null && !message.isBlank()) {
            infoBanner = "<div class='mb-4 rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200'>"
                    + escapeHtml(message)
                    + "</div>";
        } else if (error != null && !error.isBlank()) {
            infoBanner = "<div class='mb-4 rounded-xl border border-rose-500/35 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'>"
                    + escapeHtml(error)
                    + "</div>";
        }

        String downloadState = updateReady
                ? "<span class='text-emerald-300 text-sm font-semibold'>Downloaded and ready to apply.</span>"
                : (neoUpdateAvailable
                        ? "<span class='text-amber-300 text-sm font-semibold'>Update available.</span>"
                        : "<span class='text-slate-400 text-sm'>No pending NeoDash update.</span>");

        String downloadButton = "<form method='post' action='/updates/download'>"
                + "<button type='submit' class='rounded-lg px-4 py-2 bg-cyan-500 text-black font-semibold hover:bg-cyan-400 transition-colors'>Download Update</button>"
                + "</form>";

        String applyButton = updateReady
                ? "<form method='post' action='/updates/apply'>"
                        + "<button type='submit' class='rounded-lg px-4 py-2 bg-emerald-500 text-black font-semibold hover:bg-emerald-400 transition-colors'>Apply & Restart</button>"
                        + "</form>"
                : "";

        String content = HtmlTemplate.statsHeader(currentUserId)
                + "<main class='p-4 sm:p-6 flex-1 w-full'>"
                + "<div class='max-w-7xl mx-auto space-y-6'>"
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700/70 p-6 shadow-lg'>"
                + "<h1 class='text-2xl font-bold text-white'>Updates</h1>"
                + "<p class='text-sm text-slate-400 mt-1'>Manual update control for NeoDash and managed Dash plugins.</p>"
                + "</section>"
                + infoBanner
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700/70 p-6 shadow-lg'>"
                + "<h2 class='text-lg font-semibold text-white mb-4'>NeoDash System Update</h2>"
                + "<div class='grid grid-cols-1 md:grid-cols-3 gap-4 mb-4'>"
                + metricBox("Current", currentNeo.isBlank() ? "Unknown" : currentNeo)
                + metricBox("Latest", latestNeo.isBlank() ? "Unknown" : latestNeo)
                + metricBox("Status", updateReady ? "Ready to apply" : (neoUpdateAvailable ? "Update available" : "Up to date"))
                + "</div>"
                + "<div class='flex flex-wrap items-center gap-3'>"
                + downloadButton
                + applyButton
                + downloadState
                + "</div>"
                + "</section>"
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700/70 shadow-lg overflow-hidden'>"
                + "<div class='px-6 py-4 border-b border-slate-700/70'>"
                + "<h2 class='text-lg font-semibold text-white'>Dash Plugin Versions</h2>"
                + "<p class='text-xs text-slate-400 mt-1'>Comparison uses cached latest tag from Framepersecond/Dash.</p>"
                + "</div>"
                + "<div class='w-full overflow-x-auto'>"
                + "<table class='w-full min-w-[800px] text-left'>"
                + "<thead><tr class='border-b border-slate-700/70'>"
                + "<th class='px-4 py-3 text-xs text-slate-400 uppercase tracking-wider whitespace-nowrap'>Server</th>"
                + "<th class='px-4 py-3 text-xs text-slate-400 uppercase tracking-wider whitespace-nowrap'>Reported Dash</th>"
                + "<th class='px-4 py-3 text-xs text-slate-400 uppercase tracking-wider whitespace-nowrap'>Latest Dash</th>"
                + "<th class='px-4 py-3 text-xs text-slate-400 uppercase tracking-wider whitespace-nowrap'>Status</th>"
                + "</tr></thead>"
                + "<tbody>"
                + rows
                + "</tbody>"
                + "</table>"
                + "</div>"
                + "</section>"
                + "</div>"
                + "</main>";

        return HtmlTemplate.page("Updates", "/updates", content);
    }

    private static String metricBox(String label, String value) {
        return "<div class='rounded-xl bg-[#0f172a] border border-slate-700/60 p-4'>"
                + "<p class='text-xs uppercase tracking-wider text-slate-400'>" + escapeHtml(label) + "</p>"
                + "<p class='text-lg font-semibold text-slate-100 mt-1'>" + escapeHtml(value) + "</p>"
                + "</div>";
    }

    private static String safeVersion(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("unknown")) {
            return "";
        }
        return normalized;
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

