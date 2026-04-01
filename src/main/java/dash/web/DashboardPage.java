package dash.web;

import dash.NeoDash;
import dash.GithubUpdater;
import dash.bridge.BridgeApiClient;
import dash.bridge.ServerStateCache;
import dash.data.DatabaseManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class DashboardPage {

    public static String render() {
        return renderForUser(-1L, "", "");
    }

    public static String renderForUser(long userId, String username) {
        return renderForUser(userId, username, "");
    }

    public static String renderForUser(long userId, String username, String message) {
        DatabaseManager databaseManager = NeoDash.getDatabaseManager();
        List<DatabaseManager.ServerRecord> servers = databaseManager == null || userId <= 0
                ? List.of()
                : databaseManager.getServersForUser(userId);

        List<ServerLiveData> liveData = servers.stream()
                .map(server -> new ServerLiveData(server,
                        ServerStateCache.getSnapshotBlocking(server.id(), server, 1200L)))
                .toList();
        String latestDashVersion = normalizeVersionValue(GithubUpdater.LATEST_DASH_VERSION);

        StringBuilder cards = new StringBuilder();
        for (ServerLiveData item : liveData) {
            DatabaseManager.ServerRecord server = item.server();
            String statusBadgeClass = item.online()
                    ? "bg-emerald-500/15 text-emerald-300 border border-emerald-500/30"
                    : "bg-rose-500/15 text-rose-300 border border-rose-500/30";
            String statusLabel = item.online() ? "Online" : "Offline";
            String tpsLabel = String.format(Locale.ROOT, "%.2f", item.snapshot().tps());
            String ramLabel = item.snapshot().ramMaxMb() > 0
                    ? item.snapshot().ramUsedMb() + " / " + item.snapshot().ramMaxMb() + " MB"
                    : item.snapshot().ramUsedMb() + " MB";

            String dashVersion = normalizeVersionValue(item.snapshot().dashVersion());
            String dashDisplay = dashVersion.isBlank() ? "Not reported" : dashVersion;
            boolean updateAvailable = !dashVersion.isBlank()
                    && !latestDashVersion.isBlank()
                    && compareVersionValues(dashVersion, latestDashVersion) < 0;
            String updateBadgeClass = updateAvailable
                    ? "px-2 py-1 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/40 font-semibold"
                    : "hidden px-2 py-1 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/40 font-semibold";

            cards.append("<article data-server-id='").append(server.id())
                    .append("' data-dash-version='").append(escapeHtml(dashVersion))
                    .append("' class='rounded-2xl bg-[#1e293b] border border-slate-700/70 shadow-xl p-5 flex flex-col gap-4 min-h-[260px]'>")
                    .append("<div class='flex items-start justify-between gap-3'>")
                    .append("<div><h3 class='text-white text-lg font-semibold'>").append(escapeHtml(server.name()))
                    .append("</h3><p class='text-slate-400 text-xs mt-1'>ID #").append(server.id())
                    .append(" • ").append(escapeHtml(server.runnerType())).append("</p>")
                    .append("<p class='text-slate-500 text-[11px] mt-1 font-mono break-all'>")
                    .append(escapeHtml(server.ipAddress())).append(":").append(server.port())
                    .append("</p></div>")
                    .append("<span class='px-2 py-1 rounded-full bg-cyan-500/10 text-cyan-300 text-xs font-mono border border-cyan-500/20'>")
                    .append(server.port()).append("</span>")
                    .append("</div>")
                    .append("<div class='flex flex-wrap items-center justify-between gap-2 text-xs'>")
                    .append("<span id='server-status-").append(server.id())
                    .append("' class='px-2 py-1 rounded-full font-semibold ").append(statusBadgeClass).append("'>")
                    .append(statusLabel).append("</span>")
                    .append("<span class='text-slate-300'>TPS <span id='server-tps-").append(server.id())
                    .append("' class='font-mono'>").append(tpsLabel)
                    .append("</span></span>")
                    .append("<span class='text-slate-300'>RAM <span id='server-ram-").append(server.id())
                    .append("' class='font-mono'>").append(ramLabel)
                    .append("</span></span>")
                    .append("<span id='server-dash-version-").append(server.id())
                    .append("' class='text-slate-300'>Current Version <span class='font-mono'>")
                    .append(escapeHtml(dashDisplay)).append("</span></span>")
                    .append("<span id='server-dash-update-").append(server.id())
                    .append("' class='").append(updateBadgeClass).append("'>Dash Update Available</span>")
                    .append("</div>")
                    .append("<div class='text-xs text-slate-400 font-mono break-all'>")
                    .append(escapeHtml(server.pathToDir())).append("</div>")
                    .append("<div class='text-xs text-slate-500'>Start: <span class='text-slate-300 font-mono'>")
                    .append(escapeHtml(server.startCommand())).append("</span></div>")
                    .append("<div class='mt-auto pt-4 flex flex-wrap items-center justify-between gap-2 w-full'>")
                    .append("<div class='flex flex-wrap items-center gap-2 w-full lg:w-auto'>")
                    .append("<a href='/server?id=").append(server.id())
                    .append("' class='inline-flex w-full sm:w-auto justify-center items-center gap-2 px-4 py-2 rounded-lg bg-primary/15 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-semibold'>")
                    .append("Open Dashboard <span class='material-symbols-outlined text-[18px]'>arrow_forward</span></a>")
                    .append("<span class='w-full text-[11px] text-amber-300/90 font-medium' title='Session-only SSO'>Temporary session - expires when this tab is closed.</span>")
                    .append("<a href='/server/settings?id=").append(server.id())
                    .append("' class='inline-flex w-full sm:w-auto justify-center items-center gap-1.5 px-3 py-2 rounded-lg border border-slate-600 bg-[#0f172a] text-cyan-300 hover:text-white hover:border-cyan-400 hover:bg-cyan-500/20 transition-colors text-xs font-semibold' title='Server Settings'>")
                    .append("<i data-lucide='settings' class='w-3.5 h-3.5'></i>Settings")
                    .append("</a>")
                    .append("</div>")
                    .append("<form method='post' action='/server/delete' class='w-full sm:w-auto' onsubmit=\"return confirm('Delete server ")
                    .append(escapeHtml(server.name()))
                    .append("? This cannot be undone.');\">")
                    .append("<input type='hidden' name='id' value='").append(server.id()).append("'>")
                    .append("<button type='submit' class='inline-flex w-full sm:w-auto justify-center items-center gap-1.5 px-3 py-2 rounded-lg border border-rose-500/50 text-rose-300 hover:bg-rose-500/15 transition-colors text-xs font-semibold'>")
                    .append("<i data-lucide='trash-2' class='w-3.5 h-3.5'></i>Delete")
                    .append("</button>")
                    .append("</form>")
                    .append("</div>")
                    .append("</article>");
        }

        if (cards.isEmpty()) {
            cards.append("<div class='col-span-full rounded-2xl border border-slate-700/60 bg-[#1e293b] p-8 text-center'>")
                    .append("<p class='text-slate-300 text-sm'>No assigned servers for this account yet.</p>")
                    .append("<p class='text-slate-500 text-xs mt-2'>Ask an administrator to grant access in server_permissions.</p>")
                    .append("</div>");
        }

        String greeting = username == null || username.isBlank() ? "NeoDash" : "Welcome, " + escapeHtml(username);
        String messageBanner = message == null || message.isBlank()
                ? ""
                : "<div class='mb-4 rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200'>"
                        + escapeHtml(message)
                        + "</div>";
        String updateReadyBanner = GithubUpdater.UPDATE_READY
                ? "<div class=\"bg-blue-600 text-white p-4 rounded-lg shadow mb-6 font-bold text-center\">"
                        + "A new version of NeoDash has been downloaded! Please restart the application to apply the update."
                        + "<button type=\"button\" class=\"ml-4 px-4 py-2 bg-white text-blue-600 rounded-lg font-bold hover:bg-gray-100 transition-colors\" "
                        + "onclick=\"if(this.disabled){return;} const btn=this; const originalText=btn.textContent; btn.disabled=true; btn.textContent='Restarting...'; btn.classList.add('opacity-60','cursor-not-allowed'); if(confirm('NeoDash wird jetzt beendet und neu gestartet. Fortfahren?')) { fetch('/api/restart', {method: 'POST'}).then(() => { alert('Neustart eingeleitet. Bitte lade die Seite in 5 Sekunden neu.'); window.location.reload(); }).catch(() => { btn.disabled=false; btn.textContent=originalText; btn.classList.remove('opacity-60','cursor-not-allowed'); }); } else { btn.disabled=false; btn.textContent=originalText; btn.classList.remove('opacity-60','cursor-not-allowed'); }\">"
                        + "Restart Now"
                        + "</button>"
                        + "</div>"
                : "";

        String content = HtmlTemplate.statsHeader(userId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6 mb-6'>"
                + "<div class='flex flex-wrap items-center justify-between gap-4'>"
                + "<div><h1 class='text-2xl font-bold text-white'>" + greeting + "</h1>"
                + "<p class='text-slate-400 mt-1'>Select a server to open the unified dashboard.</p></div>"
                + "<div class='flex flex-wrap items-center gap-2'>"
                + "<a href='/createServer' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-black hover:bg-primary/80 transition-colors text-sm font-semibold'>"
                + "Create Server <span class='material-symbols-outlined text-[18px]'>add</span></a>"
                + "<a href='/installServer' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-emerald-500 text-black hover:bg-emerald-400 transition-colors text-sm font-semibold'>"
                + "Install Server <i data-lucide='download' class='w-4 h-4'></i></a>"
                + "</div>"
                + "</div>"
                + "</section>"
                + updateReadyBanner
                + messageBanner
                + "<div id='server-card-grid' class='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 w-full'>"
                + cards
                + "</div>"
                + "</main>"
                + renderServerGridPollingScript(latestDashVersion);

        return HtmlTemplate.page("Servers", "/", content);
    }

    private static String renderServerGridPollingScript(String latestDashVersion) {
        return "<script>\n"
                + "(function(){\n"
                + "  if (window.dashboardPollInterval) {\n"
                + "    clearInterval(window.dashboardPollInterval);\n"
                + "    window.dashboardPollInterval = null;\n"
                + "  }\n"
                + "  const onlineClasses = ['bg-emerald-500/15', 'text-emerald-300', 'border-emerald-500/30'];\n"
                + "  const offlineClasses = ['bg-rose-500/15', 'text-rose-300', 'border-rose-500/30'];\n"
                + "  function badgeFor(id) { return document.getElementById('server-status-' + id); }\n"
                + "  function tpsFor(id) { return document.getElementById('server-tps-' + id); }\n"
                + "  function ramFor(id) { return document.getElementById('server-ram-' + id); }\n"
                + "  function dashVersionFor(id) { return document.getElementById('server-dash-version-' + id); }\n"
                + "  function dashUpdateBadgeFor(id) { return document.getElementById('server-dash-update-' + id); }\n"
                + "  const latestDashTag = '" + escapeJsLiteral(latestDashVersion) + "';\n"
                + "  function normalizeVersion(v) {\n"
                + "    if (!v) { return ''; }\n"
                + "    return String(v).trim().replace(/^v/i, '').replace(/[^0-9.]/g, '');\n"
                + "  }\n"
                + "  function compareVersions(a, b) {\n"
                + "    const pa = normalizeVersion(a).split('.').filter(Boolean).map(x => parseInt(x, 10) || 0);\n"
                + "    const pb = normalizeVersion(b).split('.').filter(Boolean).map(x => parseInt(x, 10) || 0);\n"
                + "    const max = Math.max(pa.length, pb.length);\n"
                + "    for (let i = 0; i < max; i++) {\n"
                + "      const av = i < pa.length ? pa[i] : 0;\n"
                + "      const bv = i < pb.length ? pb[i] : 0;\n"
                + "      if (av > bv) return 1;\n"
                + "      if (av < bv) return -1;\n"
                + "    }\n"
                + "    return 0;\n"
                + "  }\n"
                + "  function updateDashBadge(id, currentVersion) {\n"
                + "    const badge = dashUpdateBadgeFor(id);\n"
                + "    if (!badge) { return; }\n"
                + "    if (!latestDashTag || !currentVersion || currentVersion === 'unknown') {\n"
                + "      badge.classList.add('hidden');\n"
                + "      return;\n"
                + "    }\n"
                + "    if (compareVersions(currentVersion, latestDashTag) < 0) {\n"
                + "      badge.classList.remove('hidden');\n"
                + "    } else {\n"
                + "      badge.classList.add('hidden');\n"
                + "    }\n"
                + "  }\n"
                + "  function setOnline(id) {\n"
                + "    const badge = badgeFor(id);\n"
                + "    if (!badge) { return; }\n"
                + "    offlineClasses.forEach(cls => badge.classList.remove(cls));\n"
                + "    onlineClasses.forEach(cls => badge.classList.add(cls));\n"
                + "    badge.textContent = 'Online';\n"
                + "  }\n"
                + "  function setOffline(id) {\n"
                + "    if (window.nativeOverrideActive) {\n"
                + "      return;\n"
                + "    }\n"
                + "    const badge = badgeFor(id);\n"
                + "    if (badge) {\n"
                + "      onlineClasses.forEach(cls => badge.classList.remove(cls));\n"
                + "      offlineClasses.forEach(cls => badge.classList.add(cls));\n"
                + "      badge.textContent = 'Offline';\n"
                + "    }\n"
                + "    const tps = tpsFor(id);\n"
                + "    const ram = ramFor(id);\n"
                + "    if (tps) { tps.textContent = 'N/A'; }\n"
                + "    if (ram) { ram.textContent = 'N/A'; }\n"
                + "  }\n"
                + "  function updateMetrics(id, metrics) {\n"
                + "    const tps = Number(metrics && metrics.tps);\n"
                + "    const ramUsed = Number(metrics && metrics.ramUsed);\n"
                + "    const ramMax = Number(metrics && metrics.ramMax);\n"
                + "    const tpsNode = tpsFor(id);\n"
                + "    const ramNode = ramFor(id);\n"
                + "    if (tpsNode && Number.isFinite(tps)) {\n"
                + "      tpsNode.textContent = tps.toFixed(2);\n"
                + "    }\n"
                + "    if (ramNode && Number.isFinite(ramUsed)) {\n"
                + "      ramNode.textContent = (Number.isFinite(ramMax) && ramMax > 0)\n"
                + "        ? (Math.max(0, Math.round(ramUsed)) + ' / ' + Math.max(0, Math.round(ramMax)) + ' MB')\n"
                + "        : (Math.max(0, Math.round(ramUsed)) + ' MB');\n"
                + "    }\n"
                + "    const dashVersion = String((metrics && metrics.dashVersion) || '').trim();\n"
                + "    const dashNode = dashVersionFor(id);\n"
                + "    if (dashNode) {\n"
                + "      const valueNode = dashNode.querySelector('span');\n"
                + "      const normalized = dashVersion ? normalizeVersion(dashVersion) : '';\n"
                + "      if (valueNode) { valueNode.textContent = normalized || 'Not reported'; }\n"
                + "      updateDashBadge(id, normalized);\n"
                + "    }\n"
                + "  }\n"
                + "  function payloadLooksOnline(data) {\n"
                + "    if (!data || typeof data !== 'object') { return false; }\n"
                + "    const tps = Number(data.tps || 0);\n"
                + "    const ramUsage = Number((data.ramUsed ?? data.ramUsage) || 0);\n"
                + "    const status = String(data.status || '').toLowerCase();\n"
                + "    const online = data.online === true;\n"
                + "    return tps > 0 || ramUsage > 0 || status === 'online' || online;\n"
                + "  }\n"
                + "  async function refreshCard(id) {\n"
                + "    if (!id || !/^\\d+$/.test(String(id))) { return; }\n"
                + "    const controller = new AbortController();\n"
                + "    const timeout = setTimeout(() => controller.abort(), 2200);\n"
                + "    try {\n"
                + "      const response = await fetch('/api/metrics?id=' + encodeURIComponent(id), {\n"
                + "        credentials: 'same-origin',\n"
                + "        signal: controller.signal\n"
                + "      });\n"
                + "      if (!response.ok) { throw new Error('HTTP ' + response.status); }\n"
                + "      const metrics = await response.json();\n"
                + "      updateMetrics(id, metrics);\n"
                + "      if (payloadLooksOnline(metrics)) {\n"
                + "        setOnline(id);\n"
                + "      } else {\n"
                + "        setOffline(id);\n"
                + "      }\n"
                + "    } catch (e) {\n"
                + "      setOffline(id);\n"
                + "    } finally {\n"
                + "      clearTimeout(timeout);\n"
                + "    }\n"
                + "  }\n"
                + "  function tick() {\n"
                + "    const serverCards = document.querySelectorAll('article[data-server-id]');\n"
                + "    if (!serverCards || serverCards.length === 0) {\n"
                + "      clearInterval(window.dashboardPollInterval);\n"
                + "      window.dashboardPollInterval = null;\n"
                + "      return;\n"
                + "    }\n"
                + "    serverCards.forEach(card => {\n"
                + "      const id = (card.getAttribute('data-server-id') || '').trim();\n"
                + "      if (id && /^\\d+$/.test(id)) { refreshCard(id); }\n"
                + "    });\n"
                + "  }\n"
                + "  tick();\n"
                + "  window.dashboardPollInterval = setInterval(tick, 2500);\n"
                + "})();\n"
                + "</script>";
    }

    public static String renderServer(long serverId, DatabaseManager.ServerRecord server) {
        return renderServer(serverId, server, "", true, true, true, false);
    }

    public static String renderServer(long serverId, DatabaseManager.ServerRecord server, String selectedDir) {
        return renderServer(serverId, server, selectedDir, true, true, true, false);
    }

    public static String renderServer(long serverId, DatabaseManager.ServerRecord server, String selectedDir,
            boolean canStart, boolean canFiles, boolean canProperties) {
        return renderServer(serverId, server, selectedDir, canStart, canFiles, canProperties, false);
    }

    public static String renderServer(long serverId, DatabaseManager.ServerRecord server, String selectedDir,
            boolean canStart, boolean canFiles, boolean canProperties, boolean processRunning) {
        if (server == null) {
            String missing = "<main class='flex-1 p-6 overflow-auto'><section class='rounded-3xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-6'>"
                    + "<h2 class='text-xl font-bold text-white'>Server not found</h2>"
                    + "<p class='text-slate-400 mt-2 text-sm'>The requested server record does not exist.</p>"
                    + "</section></main>";
            return HtmlTemplate.page("Dashboard", "/", missing);
        }

        ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(serverId, server);
        boolean socketOnline = isSocketOnline(server.port());
        boolean effectiveOnline = snapshot.online() || socketOnline;
        if (!effectiveOnline) {
            String stateCard = processRunning
                    ? renderBootingAwaitingSetupSection()
                    : renderOfflineStartSection(serverId, canStart);
            String bootingScript = processRunning ? renderBootingRefreshScript(serverId) : "";
            String offlineContent = HtmlTemplate.statsHeader(snapshot)
                    + "<main class='flex-1 p-6 overflow-auto text-white'>"
                    + "  <div class='max-w-7xl mx-auto space-y-6'>"
                    + stateCard
                    + renderOfflineServerPropertiesEditor(serverId, server)
                    + renderOfflineFileRescue(serverId, server, selectedDir)
                    + "  </div>"
                    + "</main>"
                    + bootingScript;
            return HtmlTemplate.page(server.name() + " Dashboard", "/", offlineContent);
        }

        double cpuPercent = Math.max(0.0d, snapshot.cpuUsage());
        List<Double> cpuHistory = new ArrayList<>();
        List<Double> ramHistory = new ArrayList<>();

        String cpuHistoryJson = toJsonArray(cpuHistory);
        String ramHistoryJson = toJsonArray(ramHistory);
        String labelsJson = toIndexLabelsJson(Math.max(cpuHistory.size(), ramHistory.size()));

        String tpsValue = String.format(Locale.ROOT, "%.2f", snapshot.tps());
        String cpuValue = String.format(Locale.ROOT, "%.1f", cpuPercent);
        List<String> players = fetchActivePlayers(server);
        String statusClass = effectiveOnline
                ? "inline-flex items-center px-2 py-1 rounded-full bg-emerald-500/15 text-emerald-300 border border-emerald-500/30"
                : "inline-flex items-center px-2 py-1 rounded-full bg-rose-500/15 text-rose-300 border border-rose-500/30";
        String statusValue = effectiveOnline ? "Online" : "Offline";
        String ramText = snapshot.ramMaxMb() > 0
                ? snapshot.ramUsedMb() + " / " + snapshot.ramMaxMb() + " MB"
                : snapshot.ramUsedMb() + " MB";
        String offlineControls = "";
        String offlineFileRescue = "";

        StringBuilder playerItems = new StringBuilder();
        if (players.isEmpty()) {
            playerItems.append("<li class='text-slate-400 text-sm'>No players online.</li>");
        } else {
            for (String player : players) {
                String safePlayer = escapeHtml(player);
                String avatarUrl = "https://minotar.net/helm/" + urlEncodeSegment(player) + "/32";
                playerItems.append("<li class='flex items-center justify-between rounded-lg bg-slate-900/60 border border-slate-700/50 px-3 py-2'>")
                        .append("<div class='flex items-center gap-3 min-w-0'>")
                        .append("<img src='").append(avatarUrl)
                        .append("' alt='").append(safePlayer)
                        .append(" head' class='w-8 h-8 rounded-md ring-1 ring-slate-700/80 pixelated' loading='lazy' decoding='async'>")
                        .append("<div class='flex items-center gap-2 min-w-0'>")
                        .append("<span class='h-2 w-2 rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(16,185,129,0.6)]'></span>")
                        .append("<span class='text-slate-100 text-sm truncate'>").append(safePlayer).append("</span>")
                        .append("</div>")
                        .append("</div>")
                        .append("<span class='text-[11px] text-emerald-300 uppercase tracking-wider'>Online</span>")
                        .append("</li>");
            }
        }

        String content = HtmlTemplate.statsHeader(snapshot)
                + "<main class='flex-1 p-6 overflow-auto text-white'>"
                + "  <div class='max-w-7xl mx-auto grid grid-cols-1 lg:grid-cols-3 gap-6'>"
                + "    <section class='lg:col-span-2 space-y-6'>"
                + offlineControls
                + offlineFileRescue
                + "      <div class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-5'>"
                + "        <div class='flex items-center justify-between mb-4'>"
                + "          <h2 class='text-xl font-bold'>Performance Metrics</h2>"
                + "          <span class='text-xs text-slate-400'>Live metrics from ServerStateSnapshot</span>"
                + "        </div>"
                + "        <div class='grid grid-cols-2 md:grid-cols-2 xl:grid-cols-4 gap-3 mb-4'>"
                + "          <div class='rounded-xl bg-slate-900/70 border border-slate-700/50 p-3'>"
                + "            <p class='text-[11px] uppercase tracking-wider text-slate-400'>TPS</p>"
                + "            <p id='metric-tps' class='text-2xl font-semibold text-emerald-300'>" + tpsValue + "</p>"
                + "          </div>"
                + "          <div class='rounded-xl bg-slate-900/70 border border-slate-700/50 p-3'>"
                + "            <p class='text-[11px] uppercase tracking-wider text-slate-400'>CPU</p>"
                + "            <p id='metric-cpu' class='text-2xl font-semibold text-cyan-300'>" + cpuValue + "%</p>"
                + "          </div>"
                + "          <div class='rounded-xl bg-slate-900/70 border border-slate-700/50 p-3'>"
                + "            <p class='text-[11px] uppercase tracking-wider text-slate-400'>RAM Used</p>"
                + "            <p id='metric-ram-used' class='text-2xl font-semibold text-teal-300'>" + snapshot.ramUsedMb()
                + " MB</p>"
                + "          </div>"
                + "          <div class='rounded-xl bg-slate-900/70 border border-slate-700/50 p-3'>"
                + "            <p class='text-[11px] uppercase tracking-wider text-slate-400'>RAM Max</p>"
                + "            <p id='metric-ram-max' class='text-2xl font-semibold text-amber-300'>" + snapshot.ramMaxMb()
                + " MB</p>"
                + "          </div>"
                + "        </div>"
                + "      </div>"
                + "      <div class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 overflow-hidden'>"
                + "        <div class='px-4 py-2 bg-slate-900/80 border-b border-slate-700/60 flex items-center justify-between'>"
                + "          <span class='text-sm font-medium text-slate-200 font-mono'>Server Console</span>"
                + "          <div class='flex items-center gap-2'>"
                + "            <span class='h-2.5 w-2.5 rounded-full bg-rose-400/90'></span>"
                + "            <span class='h-2.5 w-2.5 rounded-full bg-amber-300/90'></span>"
                + "            <span class='h-2.5 w-2.5 rounded-full bg-emerald-400/90'></span>"
                + "          </div>"
                + "        </div>"
                + "        <pre id=\"terminal-output\" class=\"bg-black/85 text-emerald-300 p-4 h-[520px] overflow-y-auto font-mono text-[13px] leading-6 shadow-inner\">Loading console logs...</pre>"
                + "        <div class='p-4 border-t border-slate-700/60 bg-slate-900/40 flex gap-4'>"
                + "          <input type=\"text\" id=\"cmd-input\" class=\"flex-1 bg-slate-800/60 border border-slate-700 rounded px-4 py-2 text-white placeholder-gray-400 focus:outline-none focus:border-cyan-500 font-mono\" placeholder=\"Enter command...\">"
                + "          <button id=\"cmd-send\" class=\"bg-slate-800 hover:bg-slate-700 border border-slate-600 px-6 py-2 rounded text-cyan-400 font-medium transition-colors\">Send</button>"
                + "        </div>"
                + "      </div>"
                + "    </section>"
                + "    <aside class='space-y-6'>"
                + "      <section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-5'>"
                + "        <h3 class='text-lg font-semibold mb-4'>Server Information</h3>"
                + "        <dl class='space-y-3 text-sm'>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>Node</dt><dd class='text-slate-200'>"
                + escapeHtml(server.runnerType()) + "</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>IP</dt><dd class='text-slate-200 font-mono'>"
                + escapeHtml(server.ipAddress()) + "</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>Version</dt><dd id='detail-info-version' class='text-slate-200'>Unknown</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>Status</dt><dd id='detail-status-badge' class='"
                + statusClass + " font-semibold'>" + statusValue + "</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>Uptime</dt><dd id='detail-info-uptime' class='text-slate-200'>"
                + escapeHtml(snapshot.uptime()) + "</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>TPS</dt><dd id='detail-info-tps' class='text-cyan-300'>"
                + String.format(Locale.ROOT, "%.2f", snapshot.tps()) + "</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>CPU</dt><dd id='detail-info-cpu' class='text-cyan-300'>"
                + String.format(Locale.ROOT, "%.1f", cpuPercent) + "%</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>Current RAM</dt><dd id='detail-info-ram' class='text-teal-300'>"
                + escapeHtml(ramText) + "</dd></div>"
                + "          <div class='flex justify-between gap-3'><dt class='text-slate-400'>Max RAM</dt><dd id='detail-info-max-ram' class='text-amber-300'>"
                + snapshot.ramMaxMb() + " MB</dd></div>"
                + "        </dl>"
                + "      </section>"
                + "      <section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-5'>"
                + "        <div class='flex items-center justify-between mb-4'>"
                + "          <h3 class='text-lg font-semibold'>Active Players</h3>"
                + "          <span id='players-online-count' class='px-2 py-1 rounded-full bg-primary/15 text-primary text-xs font-mono'>"
                + players.size() + "</span>"
                + "        </div>"
                + "        <ul id='detail-player-list' class='space-y-2'>" + playerItems + "</ul>"
                + "      </section>"
                + "    </aside>"
                + "  </div>"
                + "</main>"
                + renderLiveConsoleScript(serverId, snapshot.ramMaxMb(), labelsJson, cpuHistoryJson, ramHistoryJson,
                        detectServerType(server));

        return HtmlTemplate.page(server.name() + " Dashboard", "/", content);
    }

    private static String renderOfflineStartSection(long serverId, boolean canStart) {
        String actionButton = canStart
                ? "<form method='post' action='/server/action'>"
                        + "<input type='hidden' name='server_id' value='" + serverId + "'>"
                        + "<input type='hidden' name='action' value='start'>"
                        + "<button type='submit' class='inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-emerald-500 text-black font-semibold hover:bg-emerald-400 transition-colors'>"
                        + "&#9654; Start Server"
                        + "</button>"
                        + "</form>"
                : "<p class='text-sm text-slate-400'>You do not have permission to start this server.</p>";
        return "<section class='rounded-2xl bg-emerald-500/10 border border-emerald-500/30 p-5'>"
                + "<div class='flex flex-wrap items-center justify-between gap-3'>"
                + "<div><h3 class='text-lg font-semibold text-emerald-300'>Server is offline</h3>"
                + "<p class='text-sm text-slate-300 mt-1'>Use NeoDash rescue actions to repair files, then start the server.</p></div>"
                + actionButton
                + "</div>"
                + "</section>";
    }

    private static String renderBootingAwaitingSetupSection() {
        return "<section class='rounded-2xl bg-amber-500/10 border border-amber-400/40 p-5'>"
                + "<div class='flex items-start justify-between gap-3'>"
                + "<div><h3 class='text-lg font-semibold text-amber-300'>Booting / Awaiting Setup</h3>"
                + "<p class='text-sm text-slate-200 mt-1'>The server process is running. Please join the Minecraft server to complete the setup, or wait for the plugin to connect.</p></div>"
                + "</div>"
                + "</section>";
    }

    private static String renderBootingRefreshScript(long serverId) {
        return "<script>"
                + "(function(){"
                + "const sid=" + serverId + ";"
                + "async function poll(){"
                + "try{"
                + "const resp=await fetch('/api/server/'+sid+'/status',{credentials:'same-origin'});"
                + "if(!resp.ok){return;}"
                + "const status=await resp.json();"
                + "if(status&&status.online===true){window.location.reload();}"
                + "}catch(_){ }"
                + "}"
                + "poll();"
                + "setInterval(poll,3000);"
                + "})();"
                + "</script>";
    }

    private static String renderOfflineServerPropertiesEditor(long serverId, DatabaseManager.ServerRecord server) {
        Path rootPath;
        try {
            rootPath = Path.of(server.pathToDir()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return "<section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-rose-500/30 p-5'>"
                    + "<h3 class='text-lg font-semibold text-white'>Server Properties</h3>"
                    + "<p class='text-sm text-rose-300 mt-2'>Unable to resolve server path.</p>"
                    + "</section>";
        }

        Path propertiesFile = rootPath.resolve("server.properties").normalize();
        if (!propertiesFile.startsWith(rootPath)) {
            return "<section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-rose-500/30 p-5'>"
                    + "<h3 class='text-lg font-semibold text-white'>Server Properties</h3>"
                    + "<p class='text-sm text-rose-300 mt-2'>Invalid server.properties location.</p>"
                    + "</section>";
        }

        Map<String, String> properties = loadServerProperties(propertiesFile);
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            fields.append("<div class='space-y-1'>")
                    .append("<label class='block text-xs uppercase tracking-wider text-slate-400'>")
                    .append(escapeHtml(entry.getKey()))
                    .append("</label>")
                    .append("<input type='text' name='prop.")
                    .append(escapeHtml(entry.getKey()))
                    .append("' value='")
                    .append(escapeHtml(entry.getValue()))
                    .append("' class='w-full bg-slate-900/60 border border-slate-700/60 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500'>")
                    .append("</div>");
        }

        if (fields.isEmpty()) {
            fields.append("<p class='text-sm text-slate-400 col-span-full'>No properties found yet. Create values in this form and save to append them.</p>")
                    .append("<div class='space-y-1'>")
                    .append("<label class='block text-xs uppercase tracking-wider text-slate-400'>motd</label>")
                    .append("<input type='text' name='prop.motd' value='' class='w-full bg-slate-900/60 border border-slate-700/60 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500'>")
                    .append("</div>");
        }

        return "<section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-5'>"
                + "<div class='flex flex-wrap items-center justify-between gap-2 mb-4'>"
                + "<div><h3 class='text-lg font-semibold text-white'>Server Properties</h3>"
                + "<p class='text-xs text-slate-400 mt-1'>Editing <code class='text-slate-300'>"
                + escapeHtml(propertiesFile.toString()) + "</code></p></div>"
                + "</div>"
                + "<form method='post' action='/server/properties/save' class='space-y-4'>"
                + "<input type='hidden' name='server_id' value='" + serverId + "'>"
                + "<div class='grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3'>"
                + fields
                + "</div>"
                + "<div class='pt-1'>"
                + "<button type='submit' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 hover:bg-cyan-500/30 text-sm font-semibold'>Save Properties</button>"
                + "</div>"
                + "</form>"
                + "</section>";
    }

    private static Map<String, String> loadServerProperties(Path propertiesFile) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (propertiesFile == null || !Files.isRegularFile(propertiesFile)) {
            return properties;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(propertiesFile, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return properties;
        }

        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if (key.isEmpty()) {
                continue;
            }
            String value = line.substring(equals + 1);
            properties.put(key, value);
        }
        return properties;
    }

    private static String renderLiveConsoleScript(long serverId, int ramMaxMb, String labelsJson,
            String cpuHistoryJson, String ramHistoryJson, String serverType) {
        return "<script>\n"
                + "document.addEventListener('DOMContentLoaded', function() {\n"
                + "  if (window.detailPollInterval) clearInterval(window.detailPollInterval);\n"
                + "  if (window.detailStatusInterval) clearInterval(window.detailStatusInterval);\n"
                + "  const sid=" + serverId + ";\n"
                + "  const term=document.getElementById('terminal-output');\n"
                + "  const input=document.getElementById('cmd-input');\n"
                + "  const btn=document.getElementById('cmd-send');\n"
                + "  if(!term||!input||!btn){return;}\n"
                + "  window.nativeServerData={isNative:false,cpu:0,ram:0,tps:0,maxRam:0};\n"
                + "  const onlineClasses=['bg-emerald-500/15','text-emerald-300','border-emerald-500/30'];\n"
                + "  const offlineClasses=['bg-rose-500/15','text-rose-300','border-rose-500/30'];\n"
                + "  function esc(v){return String(v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}\n"
                + "  function setStatus(online){const b=document.getElementById('detail-status-badge'); if(!b) return; if(online){offlineClasses.forEach(c=>b.classList.remove(c)); onlineClasses.forEach(c=>b.classList.add(c)); b.textContent='Online';} else {onlineClasses.forEach(c=>b.classList.remove(c)); offlineClasses.forEach(c=>b.classList.add(c)); b.textContent='Offline';}}\n"
                + "  function apply(data){\n"
                + "    const tps=Number(data&&data.tps); const cpu=Number(data&&data.cpu); const ram=Number(data&&(data.ram??data.ramUsed));\n"
                + "    const maxRam=Number(data&&(data.maxRamObserved??ram));\n"
                + "    window.nativeServerData.maxRam=Math.max(window.nativeServerData.maxRam||0, Number.isFinite(maxRam)?maxRam:0);\n"
                + "    const tpsText=Number.isFinite(tps)&&tps>0?tps.toFixed(2):'N/A';\n"
                + "    const cpuText=Number.isFinite(cpu)&&cpu>=0?cpu.toFixed(1)+'%':'N/A';\n"
                + "    const ramText=Number.isFinite(ram)&&ram>=0?Math.round(ram)+' MB':'N/A';\n"
                + "    const ramMaxText=window.nativeServerData.maxRam>0?Math.round(window.nativeServerData.maxRam)+' MB':'N/A';\n"
                + "    const map=[['metric-tps',tpsText],['metric-cpu',cpuText],['metric-ram-used',ramText],['metric-ram-max',ramMaxText],['tps-val',tpsText],['ram-val',ramText],['detail-info-tps',tpsText],['detail-info-cpu',cpuText],['detail-info-ram',ramText],['detail-info-max-ram',ramMaxText]];\n"
                + "    map.forEach(([id,val])=>{const n=document.getElementById(id); if(n) n.textContent=val;});\n"
                + "    const up=document.getElementById('uptime-val'); if(up){up.textContent='Online (Native)'; up.classList.remove('text-rose-300'); up.classList.add('text-emerald-300');}\n"
                + "    const infoUp=document.getElementById('detail-info-uptime'); if(infoUp) infoUp.textContent='Running';\n"
                + "    const ver=document.getElementById('detail-info-version'); if(ver) ver.textContent=(data&&data.version)?String(data.version):'Unknown';\n"
                + "    const cnt=document.getElementById('players-online-count'); if(cnt) cnt.textContent=String(Number(data&&data.playerCount||0));\n"
                + "    const list=document.getElementById('detail-player-list'); if(list){const players=Array.isArray(data&&data.players)?data.players:[]; list.innerHTML=players.length?players.map(p=>'<li class=\\\"text-xs text-slate-200\\\">'+esc(p)+'</li>').join(''):'<li class=\\\"text-xs text-slate-400\\\">No players online</li>'; }\n"
                + "  }\n"
                + "  async function pollStatus(){\n"
                + "    try{const r=await fetch('/api/server/'+sid+'/status',{credentials:'same-origin'}); if(!r.ok) return; const d=await r.json();\n"
                + "      if(d&&d.online===true){window.nativeServerData.isNative=true; window.nativeServerData.cpu=Number(d.cpu||0); window.nativeServerData.ram=Number((d.ram??d.ramUsed)||0); window.nativeServerData.tps=Number(d.tps||0); setStatus(true); apply(d);\n"
                + "        if (document.getElementById('dash-tps')) document.getElementById('dash-tps').innerText = Number(d.tps || 0).toFixed(2);\n"
                + "        if (document.getElementById('dash-ram')) document.getElementById('dash-ram').innerText = Math.max(0, Math.round(Number((d.ram ?? d.ramUsed) || 0))) + ' MB';\n"
                + "        if (document.getElementById('dash-cpu')) document.getElementById('dash-cpu').innerText = Number(d.cpu || 0).toFixed(1) + '%';\n"
                + "        if (typeof window.maxRamValue === 'undefined') window.maxRamValue = 0;\n"
                + "        window.maxRamValue = Math.max(window.maxRamValue, Number((d.ram ?? d.ramUsed) || 0));\n"
                + "        if (document.getElementById('dash-ram-max')) document.getElementById('dash-ram-max').innerText = Math.round(window.maxRamValue) + ' MB';\n"
                + "        if (document.getElementById('dash-players')) document.getElementById('dash-players').innerText = String(Number(d.playerCount || 0));\n"
                + "        if (document.getElementById('dash-chunks')) document.getElementById('dash-chunks').innerText = String(Number(d.chunks || 0));\n"
                + "      }\n"
                + "      else {window.nativeServerData.isNative=false; setStatus(false);}\n"
                + "    }catch(_){ }\n"
                + "  }\n"
                + "  async function pollLogs(){\n"
                + "    try{const r=await fetch('/api/server/'+sid+'/console/log',{credentials:'same-origin'}); if(!r.ok) return; const d=await r.json(); if(d&&Array.isArray(d.lines)){term.innerHTML=d.lines.map(esc).join('\\n'); term.scrollTop=term.scrollHeight;}}catch(_){ }\n"
                + "  }\n"
                + "  function sendCmd(){const c=input.value.trim(); if(!c) return; const fd=new URLSearchParams(); fd.append('command',c); fetch('/api/server/'+sid+'/console/command',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:fd.toString(),credentials:'same-origin'}).then(()=>{input.value=''; pollLogs();});}\n"
                + "  btn.addEventListener('click',sendCmd);\n"
                + "  input.addEventListener('keypress',e=>{if(e.key==='Enter') sendCmd();});\n"
                + "  pollStatus(); pollLogs();\n"
                + "  window.detailStatusInterval=setInterval(pollStatus,3000);\n"
                + "  window.detailPollInterval=setInterval(pollLogs,2000);\n"
                + "});\n"
                + "</script>";
    }

    private static String detectServerType(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return "UNKNOWN";
        }
        String command = server.startCommand() == null ? "" : server.startCommand().toLowerCase(Locale.ROOT);
        if (command.contains("quilt")) {
            return "QUILT";
        }
        if (command.contains("fabric")) {
            return "FABRIC";
        }
        if (command.contains("vanilla") || command.contains("server.jar")) {
            return "VANILLA";
        }
        if (command.contains("paper")) {
            return "PAPER";
        }
        if (command.contains("purpur")) {
            return "PURPUR";
        }
        if (command.contains("spigot")) {
            return "SPIGOT";
        }
        if (command.contains("bukkit")) {
            return "BUKKIT";
        }
        return "UNKNOWN";
    }

    private static boolean isSocketOnline(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String renderOfflineFileRescue(long serverId, DatabaseManager.ServerRecord server, String selectedDir) {
        StringBuilder rows = new StringBuilder();
        Path rootPath;
        try {
            rootPath = Path.of(server.pathToDir()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return "<section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-rose-500/30 p-5'>"
                    + "<h3 class='text-lg font-semibold text-rose-300'>Local File Rescue</h3>"
                    + "<p class='text-sm text-slate-300 mt-2'>The configured server path is invalid.</p>"
                    + "</section>";
        }

        String normalizedDir = selectedDir == null ? "" : selectedDir.trim().replace('\\', '/');
        if (normalizedDir.startsWith("/")) {
            normalizedDir = normalizedDir.substring(1);
        }

        Path currentDirectory = rootPath;
        if (!normalizedDir.isBlank()) {
            Path candidate = rootPath.resolve(normalizedDir).normalize();
            if (candidate.startsWith(rootPath)) {
                currentDirectory = candidate;
            }
        }

        String currentRelative = currentDirectory.equals(rootPath)
                ? ""
                : rootPath.relativize(currentDirectory).toString().replace('\\', '/');

        List<Path> entries = new ArrayList<>();
        String infoMessage = null;
        if (!Files.isDirectory(currentDirectory)) {
            infoMessage = "Server directory is missing or not accessible.";
        } else {
            try (var stream = Files.list(currentDirectory)) {
                stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .forEach(entries::add);
            } catch (IOException ex) {
                infoMessage = "Failed to read server directory.";
            }
        }

        if (!currentRelative.isBlank()) {
            String parent = "";
            int lastSlash = currentRelative.lastIndexOf('/');
            if (lastSlash > 0) {
                parent = currentRelative.substring(0, lastSlash);
            }
            String upHref = parent.isBlank()
                    ? "/server?id=" + serverId
                    : "/server?id=" + serverId + "&dir=" + encodeQueryValue(parent);
            rows.append("<tr class='border-t border-slate-700/50'>")
                    .append("<td class='py-2.5 pr-3 text-cyan-300 text-sm'><a href='").append(upHref)
                    .append("' class='hover:underline'>Go Up (..)</a></td>")
                    .append("<td class='py-2.5 pr-3 text-slate-400 text-xs'>-</td>")
                    .append("<td class='py-2.5 text-right text-xs text-slate-500'>Parent</td>")
                    .append("</tr>");
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            String rel = rootPath.relativize(entry).toString().replace('\\', '/');
            boolean directory = Files.isDirectory(entry);
            String size = directory ? "Directory" : formatSize(Files.exists(entry) ? safeSize(entry) : 0L);
            String displayName = escapeHtml(name);
            if (directory) {
                String dirHref = "/server?id=" + serverId + "&dir=" + encodeQueryValue(rel);
                displayName = "<a href='" + dirHref + "' class='text-cyan-300 hover:underline'>"
                        + displayName + "/</a>";
            }

            String actionButtons = "<form method='post' action='/server/files/delete' onsubmit=\"return confirm('Delete "
                    + escapeHtml(name)
                    + "?')\" class='inline-block'>"
                    + "<input type='hidden' name='id' value='" + serverId + "'>"
                    + "<input type='hidden' name='file' value='" + escapeHtml(rel) + "'>"
                    + "<button type='submit' class='px-3 py-1.5 rounded-lg bg-rose-500/15 text-rose-300 border border-rose-500/30 hover:bg-rose-500/25 text-xs font-semibold'>Delete</button>"
                    + "</form>";

            if (!directory && isEditableTextFile(name)) {
                actionButtons = "<a href='/server/files/edit?id=" + serverId + "&file=" + encodeQueryValue(rel)
                        + "' class='px-3 py-1.5 rounded-lg bg-cyan-500/15 text-cyan-300 border border-cyan-500/30 hover:bg-cyan-500/25 text-xs font-semibold'>Edit</a> "
                        + actionButtons;
            }

            rows.append("<tr class='border-t border-slate-700/50'>")
                    .append("<td class='py-2.5 pr-3 text-slate-100 font-mono text-xs break-all'>")
                    .append(displayName)
                    .append(directory ? " <span class='text-slate-500'>(dir)</span>" : "")
                    .append("</td>")
                    .append("<td class='py-2.5 pr-3 text-slate-300 text-xs'>").append(escapeHtml(size)).append("</td>")
                    .append("<td class='py-2.5 text-right whitespace-nowrap text-xs'>").append(actionButtons).append("</td>")
                    .append("</tr>");
        }

        if (rows.isEmpty()) {
            rows.append("<tr><td colspan='3' class='py-4 text-slate-400 text-sm'>")
                    .append(escapeHtml(infoMessage == null ? "No files found in this directory." : infoMessage))
                    .append("</td></tr>");
        }

        return "<section class='rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-5'>"
                + "<div class='flex flex-wrap items-center justify-between gap-2 mb-3'>"
                + "<div><h3 class='text-lg font-semibold text-white'>Local File Rescue</h3>"
                + "<p class='text-xs text-slate-400 mt-1'>Root: <code class='text-slate-300'>"
                + escapeHtml(rootPath.toString()) + "</code></p>"
                + "<p class='text-xs text-slate-400 mt-1'>Current: <code class='text-slate-300'>/"
                + escapeHtml(currentRelative) + "</code></p></div>"
                + "</div>"
                + "<div class='overflow-x-auto'>"
                + "<table class='w-full text-left'>"
                + "<thead><tr class='text-[11px] uppercase tracking-wider text-slate-400 border-b border-slate-700/50'>"
                + "<th class='pb-2 pr-3 font-medium'>Name</th>"
                + "<th class='pb-2 pr-3 font-medium'>Size</th>"
                + "<th class='pb-2 font-medium text-right'>Actions</th>"
                + "</tr></thead><tbody>"
                + rows
                + "</tbody></table>"
                + "</div>"
                + "</section>";
    }

    private static long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0d;
        if (mb < 1024.0d) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0d);
    }

    private static boolean isEditableTextFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".properties")
                || lower.endsWith(".json")
                || lower.endsWith(".txt")
                || lower.endsWith(".cfg")
                || lower.endsWith(".conf")
                || lower.endsWith(".log");
    }

    private static double estimateCpuPercent(ServerStateCache.ServerStateSnapshot snapshot) {
        if (snapshot == null || !snapshot.online()) {
            return 0.0d;
        }
        double tps = Math.max(0.0d, Math.min(20.0d, snapshot.tps()));
        return Math.max(0.0d, Math.min(100.0d, ((20.0d - tps) / 20.0d) * 100.0d));
    }

    private static String toJsonArray(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(String.format(Locale.ROOT, "%.2f", values.get(i)));
        }
        out.append(']');
        return out.toString();
    }

    private static String toIndexLabelsJson(int size) {
        int count = Math.max(1, Math.min(size, 30));
        StringBuilder out = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                out.append(',');
            }
            out.append('\"').append(i).append('\"');
        }
        out.append(']');
        return out.toString();
    }

    private static List<String> fetchActivePlayers(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return List.of();
        }
        try {
            BridgeApiClient client = new BridgeApiClient();
            BridgeApiClient.BridgeResponse response = client.get(server, "players").join();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parsePlayerNames(response.body());
        } catch (CompletionException ex) {
            return List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> parsePlayerNames(String json) {
        if (json == null) {
            return List.of();
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of();
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isBlank()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (String entry : splitTopLevel(body)) {
            String item = entry.trim();
            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                names.add(unescapeJson(item.substring(1, item.length() - 1)));
                continue;
            }
            if (item.startsWith("{") && item.endsWith("}")) {
                String name = parseNameFromObject(item);
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            current.append(c);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                current.setLength(current.length() - 1);
                entries.add(current.toString());
                current.setLength(0);
            }
        }

        if (!current.isEmpty()) {
            entries.add(current.toString());
        }
        return entries;
    }

    private static String parseNameFromObject(String objectJson) {
        Map<String, String> fields = parseFlatJsonObject(objectJson);
        String name = fields.get("name");
        if (name == null || name.isBlank()) {
            name = fields.get("player");
        }
        if (name == null || name.isBlank()) {
            name = fields.get("username");
        }
        return name == null ? "" : name;
    }

    private static Map<String, String> parseFlatJsonObject(String objectJson) {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        String inner = objectJson.substring(1, objectJson.length() - 1).trim();
        if (inner.isEmpty()) {
            return fields;
        }
        for (String pair : splitTopLevel(inner)) {
            int idx = indexOfTopLevelColon(pair);
            if (idx <= 0) {
                continue;
            }
            String rawKey = pair.substring(0, idx).trim();
            String rawValue = pair.substring(idx + 1).trim();
            if (rawKey.length() < 2 || !rawKey.startsWith("\"") || !rawKey.endsWith("\"")) {
                continue;
            }
            String key = unescapeJson(rawKey.substring(1, rawKey.length() - 1)).toLowerCase(Locale.ROOT);
            fields.put(key, normalizeJsonValue(rawValue));
        }
        return fields;
    }

    private static int indexOfTopLevelColon(String value) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
                continue;
            }
            if (c == '}' || c == ']') {
                depth--;
                continue;
            }
            if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeJsonValue(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        String value = rawValue.trim();
        if (value.isBlank() || "null".equals(value)) {
            return "";
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return unescapeJson(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private static String unescapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            sb.append('u').append(hex);
                            i += 4;
                        }
                    } else {
                        sb.append('u');
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }

    private static String urlEncodeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "Steve";
        }
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String normalizeVersionValue(String version) {
        if (version == null) {
            return "";
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceAll("[^0-9.]", "");
        return normalized;
    }

    private static int compareVersionValues(String current, String latest) {
        String[] currentParts = normalizeVersionValue(current).split("\\.");
        String[] latestParts = normalizeVersionValue(latest).split("\\.");
        int max = Math.max(currentParts.length, latestParts.length);
        for (int i = 0; i < max; i++) {
            int c = i < currentParts.length ? safeParseVersionPart(currentParts[i]) : 0;
            int l = i < latestParts.length ? safeParseVersionPart(latestParts[i]) : 0;
            if (c < l) {
                return -1;
            }
            if (c > l) {
                return 1;
            }
        }
        return 0;
    }

    private static int safeParseVersionPart(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String escapeJsLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
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

    private record ServerLiveData(
            DatabaseManager.ServerRecord server,
            ServerStateCache.ServerStateSnapshot snapshot) {

        private boolean online() {
            return snapshot != null && snapshot.online();
        }
    }
}
