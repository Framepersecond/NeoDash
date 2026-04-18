package dash.web;

import java.util.List;

public class ScanServersPage {

    public record ScannedServer(
            String folderName,
            String directory,
            String serverType,
            String minecraftVersion,
            int port,
            String startCommand,
            String ramMax,
            boolean dashInstalled,
            int dashPort,
            int bridgePort,
            String bridgeSecret,
            boolean alreadyAdded) {
    }

    public static String renderForm(long userId, String message, String error) {
        String messageBlock = banner(message, "emerald");
        String errorBlock = banner(error, "rose");

        String content = HtmlTemplate.statsHeader(userId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='max-w-3xl mx-auto space-y-6'>"

                // ── Scan whole system ──────────────────────────────────────────────
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-2xl p-8 sm:p-10'>"
                + "<div class='mb-6'>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white tracking-tight'>Scan for Servers</h1>"
                + "<p class='text-sm text-slate-400 mt-2'>Automatically search the entire system for Minecraft server installations.</p>"
                + "</div>"
                + messageBlock + errorBlock
                + "<form method='post' action='/scan-servers'>"
                + "<input type='hidden' name='scan_system' value='true'>"
                + "<div class='flex flex-wrap items-center gap-3'>"
                + "<button type='submit' class='rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-bold px-6 py-3 transition-colors inline-flex items-center gap-2'>"
                + "<i data-lucide='globe' class='w-4 h-4'></i>Scan Whole System</button>"
                + "<a href='/' class='rounded-xl border border-slate-600 bg-[#0f172a] text-slate-200 font-semibold px-6 py-3 hover:border-cyan-400 hover:text-cyan-300 transition-colors'>Cancel</a>"
                + "</div>"
                + "</form>"
                + "</section>"

                // ── Scan specific directory ────────────────────────────────────────
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-xl p-8 sm:p-10'>"
                + "<div class='mb-6'>"
                + "<h2 class='text-xl font-bold text-white'>Scan a Specific Directory</h2>"
                + "<p class='text-sm text-slate-400 mt-1'>Recursively search a given path for Minecraft server installations.</p>"
                + "</div>"
                + "<form method='post' action='/scan-servers' class='space-y-4'>"
                + "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>Directory to Scan</label>"
                + "<input type='text' name='scan_dir' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='/home/mc/servers' required></div>"
                + "<div class='flex flex-wrap items-center gap-3 pt-1'>"
                + "<button type='submit' class='rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-bold px-6 py-3 transition-colors inline-flex items-center gap-2'>"
                + "<i data-lucide='search' class='w-4 h-4'></i>Scan Directory</button>"
                + "</div>"
                + "</form>"
                + "</section>"

                + "</div>"
                + "</main>";

        return HtmlTemplate.page("Scan Servers", "/", content);
    }

    public static String renderResults(long userId, String scanDir, List<ScannedServer> servers, String message, String error, List<String> permissionErrors) {
        String messageBlock = banner(message, "emerald");
        String errorBlock = banner(error, "rose");

        StringBuilder cards = new StringBuilder();
        if (servers.isEmpty()) {
            cards.append("<div class='col-span-full text-center py-12'>"
                    + "<i data-lucide='search-x' class='w-12 h-12 mx-auto text-slate-500 mb-4'></i>"
                    + "<p class='text-slate-400 text-lg'>No Minecraft servers found in this directory.</p>"
                    + "<p class='text-slate-500 text-sm mt-1'>Make sure the path contains subdirectories with server.properties or server JAR files.</p>"
                    + "</div>");
        }

        for (int i = 0; i < servers.size(); i++) {
            ScannedServer s = servers.get(i);
            String idx = String.valueOf(i);
            String typeLabel = s.serverType != null && !s.serverType.isBlank() ? esc(s.serverType) : "Unknown";
            String versionLabel = s.minecraftVersion != null && !s.minecraftVersion.isBlank() ? esc(s.minecraftVersion) : "Unknown";
            String dashBadge = s.dashInstalled
                    ? "<span class='px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-400 text-[11px] font-bold'>Bridge Package Detected</span>"
                    : "<span class='px-2 py-0.5 rounded-full bg-slate-600/40 text-slate-400 text-[11px] font-bold'>No Bridge Package</span>";

            String borderClass = s.alreadyAdded()
                    ? "border-emerald-700/50"
                    : "border-slate-700/70";
            cards.append("<article class='relative rounded-2xl bg-[#1e293b] border " + borderClass + " shadow-xl p-5 flex flex-col gap-3'>");

            // Preview section
            cards.append("<div id='scan-preview-").append(idx).append("'>");
            cards.append("<div class='flex items-start justify-between gap-2'>"
                    + "<div><h3 class='text-lg font-bold text-white'>").append(esc(s.folderName)).append("</h3>"
                    + "<p class='text-xs text-slate-400 font-mono mt-0.5'>").append(esc(s.directory)).append("</p></div>"
                    + "<span class='shrink-0 px-2.5 py-1 rounded-full bg-cyan-500/10 text-cyan-300 text-xs font-bold border border-cyan-500/20'>").append(String.valueOf(s.port)).append("</span>"
                    + "</div>");

            cards.append("<div class='flex flex-wrap gap-2 mt-1'>"
                    + "<span class='px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-300 text-[11px] font-bold'>").append(typeLabel).append("</span>"
                    + "<span class='px-2 py-0.5 rounded-full bg-violet-500/15 text-violet-300 text-[11px] font-bold'>").append(versionLabel).append("</span>"
                    + dashBadge);
            if (s.alreadyAdded()) {
                cards.append("<span class='px-2 py-0.5 rounded-full bg-emerald-600/25 text-emerald-300 text-[11px] font-bold border border-emerald-500/40'>✓ Already Added</span>");
            }
            cards.append("</div>");

            if (s.startCommand != null && !s.startCommand.isBlank()) {
                cards.append("<p class='text-xs text-slate-400 mt-1'>Start: <span class='font-mono text-slate-300'>").append(esc(s.startCommand)).append("</span></p>");
            }
            if (s.ramMax != null && !s.ramMax.isBlank()) {
                cards.append("<p class='text-xs text-slate-400'>RAM: <span class='font-mono text-slate-300'>").append(esc(s.ramMax)).append("</span></p>");
            }

            if (s.alreadyAdded()) {
                cards.append("<div class='mt-2 w-full inline-flex justify-center items-center gap-2 px-4 py-2.5 rounded-lg bg-slate-700/40 text-slate-400 border border-slate-600/40 text-sm font-semibold cursor-not-allowed'>"
                        + "<i data-lucide='check-circle' class='w-4 h-4'></i>Already Added to NeoDash</div>");
            } else {
                cards.append("<button type='button' onclick=\"document.getElementById('scan-preview-").append(idx)
                        .append("').style.display='none';document.getElementById('scan-form-").append(idx)
                        .append("').style.display='block';\" "
                        + "class='mt-2 w-full inline-flex justify-center items-center gap-2 px-4 py-2.5 rounded-lg bg-emerald-500/15 text-emerald-400 border border-emerald-500/25 hover:bg-emerald-500 hover:text-black transition-colors text-sm font-semibold'>"
                        + "<i data-lucide='plus' class='w-4 h-4'></i>Add Server</button>");
            }
            cards.append("</div>");

            if (!s.alreadyAdded()) {
                // Add form (hidden by default)
                cards.append("<div id='scan-form-").append(idx).append("' style='display:none'>");
                cards.append("<form method='post' action='/scan-servers/add' class='space-y-3'>");
                cards.append("<input type='hidden' name='scan_dir' value='").append(esc(scanDir)).append("'>");
                cards.append(formInput("Server Name (required)", "name", "", "Enter a name for this server", true));
                cards.append(formInput("Directory", "path_to_dir", s.directory, "", false));
                cards.append(formInput("Server IP Address", "ip_address", "127.0.0.1", "", false));
                cards.append(formSelect("Runner Type", "runner_type", "SCREEN"));
                cards.append(formInput("Start Command", "start_command",
                        s.startCommand != null ? s.startCommand : "./start.sh", "", false));
                cards.append(formInputNumber("Server Port", "server_port", s.port));
                cards.append(formInputNumber("Dash/FabricDash Port", "dash_port", s.dashPort > 0 ? s.dashPort : 8080));
                cards.append(formInputNumber("Bridge API Port", "bridge_port", s.bridgePort > 0 ? s.bridgePort : 8081));
                cards.append(formInput("Bridge Secret", "bridge_secret",
                        s.bridgeSecret != null ? s.bridgeSecret : "", "Shared secret", false));
                cards.append(formToggle("Enable Dash/FabricDash Bridge", "use_plugin_interface", s.dashInstalled()));

                cards.append("<div class='flex flex-wrap items-center gap-2 pt-1'>");
                cards.append("<button type='submit' class='rounded-lg bg-emerald-500 hover:bg-emerald-400 text-black font-bold px-5 py-2.5 transition-colors text-sm inline-flex items-center gap-2'>"
                        + "<i data-lucide='plus' class='w-4 h-4'></i>Add Server</button>");
                cards.append("<button type='button' onclick=\"document.getElementById('scan-form-").append(idx)
                        .append("').style.display='none';document.getElementById('scan-preview-").append(idx)
                        .append("').style.display='block';\" "
                        + "class='rounded-lg border border-slate-600 bg-[#0f172a] text-slate-300 font-semibold px-5 py-2.5 hover:border-rose-400 hover:text-rose-300 transition-colors text-sm'>Cancel</button>");
                cards.append("</div>");
                cards.append("</form>");
                cards.append("</div>");
            }

            cards.append("</article>");
        }

        String content = HtmlTemplate.statsHeader(userId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6 mb-6'>"
                + "<div class='flex flex-wrap items-center justify-between gap-4'>"
                + "<div><h1 class='text-2xl font-bold text-white'>Scan Results</h1>"
                + "<p class='text-slate-400 mt-1'>Found <strong class='text-white'>" + servers.size() + "</strong> server(s)"
                + (scanDir.equals("/") ? " across the whole system" : " in <span class='font-mono text-cyan-300'>" + esc(scanDir) + "</span>")
                + "</p></div>"
                + "<div class='flex flex-wrap items-center gap-2'>"
                + "<a href='/scan-servers' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-600 bg-[#0f172a] text-slate-200 hover:border-cyan-400 hover:text-cyan-300 transition-colors text-sm font-semibold'>"
                + "<i data-lucide='arrow-left' class='w-4 h-4'></i>Scan Again</a>"
                + "<a href='/' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-600 bg-[#0f172a] text-slate-200 hover:border-cyan-400 hover:text-cyan-300 transition-colors text-sm font-semibold'>"
                + "<i data-lucide='home' class='w-4 h-4'></i>Dashboard</a>"
                + "</div>"
                + "</div>"
                + "</section>"
                + messageBlock + errorBlock
                + "<div class='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 w-full'>"
                + cards
                + "</div>"
                + permissionErrorsSection(permissionErrors)
                + "</main>";

        return HtmlTemplate.page("Scan Results", "/", content);
    }

    private static String formInput(String label, String name, String value, String placeholder, boolean required) {
        return "<div><label class='block text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-1'>"
                + esc(label)
                + "</label><input type='text' name='" + esc(name)
                + "' value='" + esc(value)
                + "' placeholder='" + esc(placeholder)
                + "' class='w-full rounded-lg bg-[#0f172a] border border-slate-600 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'"
                + (required ? " required" : "")
                + "></div>";
    }

    private static String formInputNumber(String label, String name, int value) {
        return "<div><label class='block text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-1'>"
                + esc(label)
                + "</label><input type='number' name='" + esc(name)
                + "' value='" + value
                + "' class='w-full rounded-lg bg-[#0f172a] border border-slate-600 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'"
                + " min='1' max='65535'></div>";
    }

    private static String formSelect(String label, String name, String selected) {
        return "<div><label class='block text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-1'>"
                + esc(label)
                + "</label><select name='" + esc(name)
                + "' class='w-full rounded-lg bg-[#0f172a] border border-slate-600 px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'>"
                + "<option value='SCREEN'" + ("SCREEN".equals(selected) ? " selected" : "") + ">SCREEN</option>"
                + "<option value='DOCKER'" + ("DOCKER".equals(selected) ? " selected" : "") + ">DOCKER</option>"
                + "</select></div>";
    }

    private static String formToggle(String label, String name, boolean checked) {
        return "<div class='rounded-lg border border-slate-700/50 bg-[#0f172a] px-3 py-2'>"
                + "<label class='flex items-center justify-between gap-3 cursor-pointer'>"
                + "<span class='text-[11px] font-semibold text-slate-400 uppercase tracking-wider'>" + esc(label) + "</span>"
                + "<input type='checkbox' class='peer sr-only' name='" + esc(name) + "' value='1'"
                + (checked ? " checked" : "") + ">"
                + "<div class='relative h-5 w-9 rounded-full bg-slate-700 transition-colors peer-checked:bg-cyan-500 after:absolute after:start-[2px] after:top-[2px] after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-all peer-checked:after:translate-x-full'></div>"
                + "</label>"
                + "</div>";
    }

    private static String permissionErrorsSection(List<String> errors) {
        if (errors == null || errors.isEmpty()) return "";
        final int PAGE = 10;
        int total = errors.size();

        StringBuilder items = new StringBuilder();
        for (int i = 0; i < total; i++) {
            String hidden = i >= PAGE ? " style='display:none'" : "";
            items.append("<li class='text-xs text-slate-400 font-mono py-1.5 border-b border-slate-700/30 last:border-0 break-all'" + hidden + ">")
                 .append(esc(errors.get(i)))
                 .append("</li>");
        }

        String showMoreBtn = total > PAGE
                ? "<div class='flex items-center gap-3 pt-3'>"
                + "<button type='button' id='perm-show-more' onclick='ndShowMorePerm()' "
                + "class='text-xs text-amber-300 hover:text-amber-200 border border-amber-700/40 rounded-lg px-3 py-1.5 transition-colors'>"
                + "Show all (" + (total - PAGE) + " more)</button>"
                + "<button type='button' id='perm-show-less' onclick='ndShowLessPerm()' style='display:none' "
                + "class='text-xs text-slate-400 hover:text-slate-300 border border-slate-600/40 rounded-lg px-3 py-1.5 transition-colors'>"
                + "Show less</button>"
                + "</div>"
                : "";

        return "<section class='mt-6 rounded-2xl bg-[#1e293b] border border-amber-700/40 shadow-lg overflow-hidden'>"
                + "<div class='px-6 py-4 border-b border-amber-700/40 flex items-center justify-between gap-4 cursor-pointer select-none' onclick='ndTogglePermErrors()'>"
                + "<div class='flex items-center gap-3'>"
                + "<i data-lucide='lock' class='w-5 h-5 text-amber-400 shrink-0'></i>"
                + "<div><h2 class='text-base font-semibold text-amber-200'>Inaccessible Paths (" + total + ")</h2>"
                + "<p class='text-xs text-slate-400 mt-0.5'>These paths were skipped due to permission errors. No servers are missed — only unreadable locations.</p></div>"
                + "</div>"
                + "<i data-lucide='chevron-down' class='w-4 h-4 text-slate-400 transition-transform' id='perm-chevron'></i>"
                + "</div>"
                + "<div id='perm-list' class='hidden p-4'>"
                + "<ul class='space-y-0' id='perm-ul'>" + items + "</ul>"
                + showMoreBtn
                + "</div>"
                + "</section>"
                + "<script>"
                + "function ndTogglePermErrors(){"
                + "var l=document.getElementById('perm-list'),c=document.getElementById('perm-chevron');"
                + "var h=l.classList.toggle('hidden');"
                + "c.style.transform=h?'':'rotate(180deg)';}"
                + "function ndShowMorePerm(){"
                + "document.querySelectorAll('#perm-ul li').forEach(function(e){e.style.display=''});"
                + "document.getElementById('perm-show-more').style.display='none';"
                + "document.getElementById('perm-show-less').style.display='';}"
                + "function ndShowLessPerm(){"
                + "var p=" + PAGE + ";"
                + "document.querySelectorAll('#perm-ul li').forEach(function(e,i){e.style.display=(i<p)?'':'none'});"
                + "document.getElementById('perm-show-less').style.display='none';"
                + "document.getElementById('perm-show-more').style.display='';}"
                + "</script>";
    }

    private static String banner(String text, String color) {
        if (text == null || text.isBlank()) return "";
        return "<div class='mb-5 rounded-xl border border-" + color + "-500/35 bg-" + color + "-500/10 px-4 py-3 text-sm text-" + color + "-200'>"
                + esc(text) + "</div>";
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
