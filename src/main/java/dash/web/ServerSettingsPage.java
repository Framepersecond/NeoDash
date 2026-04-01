package dash.web;

import dash.data.DatabaseManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ServerSettingsPage {

    public static String render(long currentUserId, long serverId, DatabaseManager.ServerRecord server, boolean success, String errorMessage,
            List<DatabaseManager.ServerUserPermissionView> userPermissions) {
        if (server == null) {
            String content = "<main class='flex-1 p-6 overflow-auto'>"
                    + "<section class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6'>"
                    + "<h1 class='text-2xl font-bold text-white'>Server Settings</h1>"
                    + "<p class='text-rose-300 mt-3'>Server not found.</p>"
                    + "<a href='/' class='inline-flex mt-4 px-4 py-2 rounded-lg bg-primary/15 text-primary hover:bg-primary hover:text-black transition-colors'>Back to Dashboard</a>"
                    + "</section></main>";
            return HtmlTemplate.page("Server Settings", "/settings", content);
        }

        String banner = "";
        if (errorMessage != null && !errorMessage.isBlank()) {
            banner = "<div class='mb-5 px-4 py-3 rounded-xl text-sm font-medium bg-rose-500/20 text-rose-300 border border-rose-500/30'>"
                    + escapeHtml(errorMessage)
                    + "</div>";
        } else if (success) {
            banner = "<div class='mb-5 px-4 py-3 rounded-xl text-sm font-medium bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'>"
                    + "Server settings saved successfully."
                    + "</div>";
        }

        String runnerType = server.runnerType() == null ? "SCREEN" : server.runnerType().trim().toUpperCase();
        String dockerSelected = "DOCKER".equals(runnerType) ? " selected" : "";
        String screenSelected = "SCREEN".equals(runnerType) ? " selected" : "";

        String bridgePortValue = server.bridgeApiPort() == null ? "" : String.valueOf(server.bridgeApiPort());
        String dashPortValue = server.dashPort() == null ? "8080" : String.valueOf(server.dashPort());
        String bridgeSecretValue = server.bridgeSecret() == null ? "" : server.bridgeSecret();
        String bridgeHint = "Shared secret used to sign SSO redirects to the Dash plugin.";
        String propertiesNotice = "";
        try {
            Path propertiesPath = Path.of(server.pathToDir()).resolve("server.properties").toAbsolutePath().normalize();
            if (!Files.exists(propertiesPath)) {
                propertiesNotice = "<div class='mb-5 px-4 py-3 rounded-xl text-sm font-medium bg-amber-500/20 text-amber-300 border border-amber-500/30'>"
                        + "server.properties is not present yet. NeoDash will use defaults until first boot creates or updates it."
                        + "</div>";
            }
        } catch (Exception ignored) {
            propertiesNotice = "<div class='mb-5 px-4 py-3 rounded-xl text-sm font-medium bg-amber-500/20 text-amber-300 border border-amber-500/30'>"
                    + "server.properties path is not readable yet. Showing default editable values."
                    + "</div>";
        }
        String content = HtmlTemplate.statsHeader(currentUserId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-6 mb-6'>"
                + "<div class='flex flex-wrap items-center justify-between gap-3'>"
                + "<div><h1 class='text-2xl font-bold text-white'>Server Settings</h1>"
                + "<p class='text-slate-400 text-sm mt-1'>Edit configuration for server #" + serverId + "</p></div>"
                + "<a href='/server?id=" + serverId + "' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-800/50 border border-slate-700/60 text-slate-200 hover:bg-slate-700/60 transition-colors'>"
                + "<span class='material-symbols-outlined text-[18px]'>arrow_back</span>Back"
                + "</a>"
                + "</div>"
                + "</section>"
                + banner
                + propertiesNotice
                + "<form method='post' action='/server/settings?id=" + serverId + "' class='space-y-6'>"
                + "<input type='hidden' name='id' value='" + serverId + "'>"
                + "<input type='hidden' name='server_id' value='" + serverId + "'>"
                + "<section class='rounded-3xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-6'>"
                + "<div class='grid grid-cols-1 md:grid-cols-2 gap-5'>"
                + field("Server Name", "name", "text", escapeHtml(server.name()), "Name shown in the dashboard", true)
                + field("Server IP Address", "ip_address", "text", escapeHtml(server.ipAddress()), "Host/IP where NeoBridge is reachable", true)
                + field("Base Directory Path", "path_to_dir", "text", escapeHtml(server.pathToDir()), "Absolute server root path on this host", true)
                + selectField(runnerType, dockerSelected, screenSelected)
                + field("Start Command", "start_command", "text", escapeHtml(server.startCommand()), "Container id (DOCKER) or startup script/command (SCREEN)", true)
                + field("Server Port", "port", "number", String.valueOf(server.port()), "Main Minecraft server port", true)
                + field("Dash Plugin Port", "dash_port", "number", dashPortValue, "Dash plugin web/SSO port on the target server", true)
                + field("Bridge API Port", "bridge_api_port", "number", bridgePortValue, "Bridge plugin HTTP API port (optional)", false)
                + passwordField("Bridge Secret", "bridge_secret", bridgeSecretValue, bridgeHint)
                + toggleField("Use Plugin Interface (NeoBridge)", "use_plugin_interface", server.usePluginInterface(),
                        "When enabled, the homescreen routes this server to the plugin Dash interface; otherwise NeoDash stays on the native dashboard.")
                + "</div>"
                + "<div class='mt-6 flex justify-end'>"
                + "<button type='submit' class='inline-flex items-center gap-2 px-8 py-3 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 font-semibold'>"
                + "<span class='material-symbols-outlined text-[20px]'>save</span>"
                + "Save Settings"
                + "</button>"
                + "</div>"
                + "</section>"
                + renderUserManagementSection(userPermissions)
                + "</form>"
                + "</main>";

        return HtmlTemplate.page("Server Settings", "/settings", content);
    }

    private static String field(String label, String name, String type, String value, String help, boolean required) {
        return "<div>"
                + "<label class='block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2'>" + label + "</label>"
                + "<input type='" + type + "' name='" + name + "' value='" + value + "'"
                + (required ? " required" : "")
                + " class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none'>"
                + "<p class='mt-1 text-xs text-slate-500'>" + escapeHtml(help) + "</p>"
                + "</div>";
    }

    private static String selectField(String runnerType, String dockerSelected, String screenSelected) {
        return "<div>"
                + "<label class='block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2'>Runner Type</label>"
                + "<select name='runner_type' class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none'>"
                + "<option value='DOCKER'" + dockerSelected + ">DOCKER</option>"
                + "<option value='SCREEN'" + screenSelected + ">SCREEN</option>"
                + "</select>"
                + "<p class='mt-1 text-xs text-slate-500'>Current: " + escapeHtml(runnerType) + "</p>"
                + "</div>";
    }

    private static String passwordField(String label, String name, String value, String help) {
        return "<div class='md:col-span-2'>"
                + "<label class='block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2'>" + label + "</label>"
                + "<input type='password' name='" + name + "' value='" + escapeHtml(value)
                + "' autocomplete='off'"
                + " class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none'"
                + " placeholder='Enter bridge secret'>"
                + "<p class='mt-1 text-xs text-slate-500'>" + escapeHtml(help) + "</p>"
                + "</div>";
    }

    private static String toggleField(String label, String name, boolean checked, String help) {
        String checkedAttr = checked ? " checked" : "";
        return "<div class='md:col-span-2 rounded-xl border border-slate-700/50 bg-[#0f172a] px-4 py-3'>"
                + "<label class='flex items-center justify-between gap-3'>"
                + "<span class='text-sm font-semibold text-slate-200'>" + escapeHtml(label) + "</span>"
                + "<input type='checkbox' class='peer sr-only' name='" + escapeHtml(name) + "' value='1'" + checkedAttr + ">"
                + "<div class='relative h-6 w-11 rounded-full bg-slate-700 transition-colors peer-checked:bg-cyan-500 after:absolute after:start-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:bg-white after:transition-all peer-checked:after:translate-x-full'></div>"
                + "</label>"
                + "<p class='mt-2 text-xs text-slate-500'>" + escapeHtml(help) + "</p>"
                + "</div>";
    }

    private static String renderUserManagementSection(List<DatabaseManager.ServerUserPermissionView> userPermissions) {
        StringBuilder rows = new StringBuilder();
        if (userPermissions == null || userPermissions.isEmpty()) {
            rows.append("<p class='text-sm text-slate-400'>No users available.</p>");
        } else {
            for (DatabaseManager.ServerUserPermissionView userPermission : userPermissions) {
                String userLabel = escapeHtml(userPermission.username())
                        + " <span class='text-xs text-slate-500'>"
                        + escapeHtml(userPermission.globalRole()) + "</span>";
                rows.append("<div class='rounded-2xl border border-slate-700/60 bg-slate-900/40 p-4'>")
                        .append("<div class='flex items-center justify-between gap-3 mb-4'>")
                        .append("<p class='text-sm font-semibold text-slate-100'>").append(userLabel).append("</p>")
                        .append(userPermission.mainAdmin()
                                ? "<span class='px-2 py-1 rounded-full text-[11px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'>Main-Admin</span>"
                                : "")
                        .append("</div>")
                        .append("<div class='grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4'>")
                        .append(toggle("Starten", "perm_start_" + userPermission.userId(),
                                userPermission.canStart()))
                        .append(toggle("Files zugreifen", "perm_files_" + userPermission.userId(),
                                userPermission.canFiles()))
                        .append(toggle("Server Properties bearbeiten", "perm_props_" + userPermission.userId(),
                                userPermission.canProperties()))
                        .append(toggle("Server Settings bearbeiten", "perm_settings_" + userPermission.userId(),
                                userPermission.canServerSettings()))
                        .append("</div>")
                        .append("</div>");
            }
        }

        return "<section class='rounded-3xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-6'>"
                + "<div class='mb-4'>"
                + "<h2 class='text-xl font-bold text-white'>User Management</h2>"
                + "<p class='text-sm text-slate-400 mt-1'>Granulare Offline-Rechte pro Server konfigurieren.</p>"
                + "</div>"
                + rows
                + "</section>";
    }

    private static String toggle(String label, String name, boolean checked) {
        String checkedAttr = checked ? " checked" : "";
        return "<label class='flex items-center justify-between gap-3 rounded-xl border border-slate-700/50 bg-[#0f172a] px-3 py-3'>"
                + "<span class='text-xs font-medium uppercase tracking-wider text-slate-300'>" + escapeHtml(label)
                + "</span>"
                + "<input type='checkbox' class='peer sr-only' name='" + escapeHtml(name) + "' value='1'"
                + checkedAttr + ">"
                + "<div class='relative h-6 w-11 rounded-full bg-slate-700 transition-colors peer-checked:bg-cyan-500 peer-disabled:opacity-60 after:absolute after:start-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:bg-white after:transition-all peer-checked:after:translate-x-full'></div>"
                + "</label>";
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

