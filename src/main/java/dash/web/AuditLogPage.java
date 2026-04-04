package dash.web;

import dash.NeoDash;
import dash.data.AuditDataManager;
import dash.data.AuditDataManager.AuditEntry;

import java.util.List;

public class AuditLogPage {

    public static String render(long currentUserId, String searchQuery) {
        AuditDataManager auditMgr = NeoDash.getAuditDataManager();
        String normalizedSearch = searchQuery == null ? "" : searchQuery.trim();
        List<AuditEntry> entries;
        int totalCount = 0;
        String sourceWarning = "";

        if (auditMgr != null) {
            if (!normalizedSearch.isBlank()) {
                entries = auditMgr.searchLogs(normalizedSearch, 100);
            } else {
                entries = auditMgr.getRecentLogs(100);
            }
            totalCount = auditMgr.countLogs();
        } else {
            entries = List.of();
            sourceWarning = "<div class='mx-6 mt-4 mb-0 px-4 py-3 rounded-xl text-sm font-medium bg-amber-500/15 text-amber-200 border border-amber-500/30'>"
                    + "Audit storage is not initialized. Start NeoDash with audit DB enabled to capture entries."
                    + "</div>";
        }

        StringBuilder rows = new StringBuilder();
        for (AuditEntry entry : entries) {
            rows.append("<tr class='border-b border-slate-800/70 hover:bg-slate-800/40 transition-colors'>")
                    .append("<td class='px-4 py-3 text-xs text-slate-400 font-mono whitespace-nowrap'>")
                    .append(entry.getFormattedTime()).append("</td>")
                    .append("<td class='px-4 py-3 text-sm text-slate-100 font-medium'>")
                    .append(escapeHtml(entry.username())).append("</td>")
                    .append("<td class='px-4 py-3'><span class='px-2.5 py-1 rounded-full text-[11px] font-semibold uppercase tracking-wider ")
                    .append(actionBadgeClass(entry.action())).append("'>")
                    .append(escapeHtml(entry.action())).append("</span></td>")
                    .append("<td class='px-4 py-3 text-sm text-slate-300 max-w-xl truncate' title='")
                    .append(escapeHtml(entry.details())).append("'>")
                    .append(escapeHtml(truncate(entry.details(), 120))).append("</td>")
                    .append("<td class='px-4 py-3 text-xs text-slate-500 font-mono'>")
                    .append(escapeHtml(entry.ipAddress())).append("</td>")
                    .append("</tr>\n");
        }

        if (entries.isEmpty()) {
            rows.append("<tr><td colspan='5' class='px-4 py-10 text-center text-slate-500'>No audit entries found.</td></tr>");
        }

        String content = HtmlTemplate.statsHeader(currentUserId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='max-w-7xl mx-auto rounded-2xl bg-[#1e293b] border border-slate-700/70 shadow-xl overflow-hidden'>"
                + "<div class='flex flex-wrap items-center justify-between gap-3 px-6 py-4 border-b border-slate-700/70'>"
                + "<div class='flex items-center gap-3'>"
                + "<h1 class='text-xl font-bold text-white'>Audit Log</h1>"
                + "<span class='px-2.5 py-1 rounded-full bg-cyan-900/40 text-cyan-300 text-xs font-mono border border-cyan-700/40'>"
                + totalCount + " Entries</span>"
                + "</div>"
                + "<form method='get' action='/audit' class='flex items-center gap-2'>"
                + "<input type='text' name='q' value='" + escapeHtml(normalizedSearch)
                + "' placeholder='Search user/action/details...' class='w-72 bg-[#0f172a] border border-slate-600 rounded-lg px-4 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500'>"
                + "<button type='submit' class='px-3.5 py-2 rounded-lg bg-cyan-900/50 text-cyan-300 border border-cyan-700/40 hover:bg-cyan-800/50 transition-colors text-sm font-semibold'>Search</button>"
                + "</form>"
                + "</div>"
                + sourceWarning
                + "<div class='overflow-x-auto'>"
                + "<table class='w-full text-left'>"
                + "<thead>"
                + "<tr class='border-b border-slate-700/70'>"
                + "<th class='px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider'>TIME</th>"
                + "<th class='px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider'>USER</th>"
                + "<th class='px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider'>ACTION</th>"
                + "<th class='px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider'>DETAILS</th>"
                + "<th class='px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider'>IP</th>"
                + "</tr>"
                + "</thead>"
                + "<tbody>"
                + rows
                + "</tbody>"
                + "</table>"
                + "</div>"
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript();

        return HtmlTemplate.page("Audit Log", "/audit", content);
    }

    private static String actionBadgeClass(String action) {
        if (action == null) {
            return "bg-slate-700/50 text-slate-300 border border-slate-600";
        }
        String a = action.toUpperCase();
        if (a.contains("LOGIN") || a.contains("REGISTER") || a.contains("APPROVE")) {
            return "bg-emerald-500/15 text-emerald-300 border border-emerald-500/30";
        }
        if (a.contains("DENIED") || a.contains("FAILED") || a.contains("DELETE") || a.contains("BAN")) {
            return "bg-rose-500/15 text-rose-300 border border-rose-500/30";
        }
        if (a.contains("COMMAND") || a.contains("RESTART") || a.contains("STOP") || a.contains("START")) {
            return "bg-amber-500/15 text-amber-300 border border-amber-500/30";
        }
        if (a.contains("SETTING") || a.contains("ROLE") || a.contains("PERMISSION")) {
            return "bg-cyan-500/15 text-cyan-300 border border-cyan-500/30";
        }
        return "bg-slate-700/50 text-slate-300 border border-slate-600";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
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

