package dash.web;

import dash.bridge.BridgeApiClient;
import dash.bridge.ServerStateCache;
import dash.data.DatabaseManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class PlayersPage {

    public static String render(long serverId, DatabaseManager.ServerRecord server) {
        List<PlayerView> players = List.of();
        String error = "";

        if (server == null) {
            error = "Server not found.";
        } else {
            try {
                BridgeApiClient client = new BridgeApiClient();
                BridgeApiClient.BridgeResponse response = client.get(server, "players").join();
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    players = parsePlayersArray(response.body());
                } else {
                    error = "Bridge API returned HTTP " + response.statusCode() + ".";
                }
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                error = "Bridge request failed: " + safeMessage(cause.getMessage());
            } catch (Exception ex) {
                error = "Bridge request failed: " + safeMessage(ex.getMessage());
            }
        }

        String playerRows = renderPlayerRows(players);
        String statusBanner = renderStatusBanner(error, players.size());
        ServerStateCache.ServerStateSnapshot headerStats = ServerStateCache.getSnapshot(serverId, server);

        String content = HtmlTemplate.statsHeader(headerStats)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-6 mb-6'>"
                + "<div class='flex flex-wrap items-center justify-between gap-3'>"
                + "<div><h2 class='text-2xl font-bold text-white mb-1'>Online Players</h2>"
                + "<p class='text-slate-400 text-sm'>Server #" + serverId + "</p></div>"
                + "<span class='px-3 py-1 rounded-full bg-primary/15 text-primary text-xs font-mono'>" + players.size()
                + " online</span>"
                + "</div>"
                + statusBanner
                + "</section>"
                + "<section class='rounded-3xl bg-slate-800/50 backdrop-blur border border-slate-700/60 overflow-hidden'>"
                + "<div class='overflow-x-auto'>"
                + "<table class='w-full text-sm'>"
                + "<thead><tr class='text-left text-slate-400 border-b border-white/5'>"
                + "<th class='px-6 py-3 font-medium'>Player</th>"
                + "<th class='px-6 py-3 font-medium'>UUID</th>"
                + "<th class='px-6 py-3 font-medium'>World</th>"
                + "<th class='px-6 py-3 font-medium'>Ping</th>"
                + "</tr></thead>"
                + "<tbody>"
                + playerRows
                + "</tbody>"
                + "</table>"
                + "</div>"
                + "</section>"
                + "</main>";
        return HtmlTemplate.page("Players", "/players", content);
    }

    private static String renderStatusBanner(String error, int playerCount) {
        if (error != null && !error.isBlank()) {
            return "<div class='mt-4 px-4 py-3 rounded-xl text-sm font-medium bg-rose-500/20 text-rose-300 border border-rose-500/30'>"
                    + escapeHtml(error)
                    + "</div>";
        }
        return "<div class='mt-4 px-4 py-3 rounded-xl text-sm font-medium bg-emerald-500/15 text-emerald-300 border border-emerald-500/25'>"
                + "Bridge connection OK. Fetched " + playerCount + " player(s)."
                + "</div>";
    }

    private static String renderPlayerRows(List<PlayerView> players) {
        if (players == null || players.isEmpty()) {
            return "<tr><td colspan='4' class='px-6 py-8 text-center text-slate-400'>No players online.</td></tr>";
        }

        StringBuilder rows = new StringBuilder();
        for (PlayerView player : players) {
            rows.append("<tr class='border-b border-white/5 hover:bg-white/5 transition-colors'>")
                    .append("<td class='px-6 py-3 text-white font-medium'>").append(escapeHtml(player.name())).append("</td>")
                    .append("<td class='px-6 py-3 text-slate-300 font-mono text-xs'>").append(escapeHtml(player.uuid())).append("</td>")
                    .append("<td class='px-6 py-3 text-slate-300'>").append(escapeHtml(player.world())).append("</td>")
                    .append("<td class='px-6 py-3 text-slate-300'>").append(escapeHtml(player.ping())).append("</td>")
                    .append("</tr>");
        }
        return rows.toString();
    }

    private static List<PlayerView> parsePlayersArray(String json) {
        List<PlayerView> players = new ArrayList<>();
        if (json == null) {
            return players;
        }

        String trimmed = json.trim();
        if (trimmed.isBlank() || "[]".equals(trimmed)) {
            return players;
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return players;
        }

        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return players;
        }

        for (String item : splitTopLevelEntries(body)) {
            String entry = item.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("{") && entry.endsWith("}")) {
                players.add(parsePlayerObject(entry));
            } else if (entry.startsWith("\"") && entry.endsWith("\"")) {
                String name = unescapeJsonString(entry.substring(1, entry.length() - 1));
                players.add(new PlayerView(name, "-", "-", "-"));
            }
        }
        return players;
    }

    private static List<String> splitTopLevelEntries(String body) {
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
            if (c == '{') {
                depth++;
            } else if (c == '}') {
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

    private static PlayerView parsePlayerObject(String objectJson) {
        Map<String, String> fields = parseFlatJsonObject(objectJson);

        String name = firstNonBlank(fields.get("name"), fields.get("player"), fields.get("username"), "Unknown");
        String uuid = firstNonBlank(fields.get("uuid"), "-");
        String world = firstNonBlank(fields.get("world"), fields.get("level"), "-");
        String ping = firstNonBlank(fields.get("ping"), fields.get("latency"), "-");

        return new PlayerView(name, uuid, world, ping);
    }

    private static Map<String, String> parseFlatJsonObject(String objectJson) {
        Map<String, String> fields = new LinkedHashMap<>();
        String inner = objectJson.substring(1, objectJson.length() - 1).trim();
        if (inner.isEmpty()) {
            return fields;
        }

        List<String> pairs = splitTopLevelEntries(inner);
        for (String pair : pairs) {
            int sep = indexOfTopLevelColon(pair);
            if (sep <= 0) {
                continue;
            }

            String rawKey = pair.substring(0, sep).trim();
            String rawValue = pair.substring(sep + 1).trim();
            if (rawKey.length() < 2 || !rawKey.startsWith("\"") || !rawKey.endsWith("\"")) {
                continue;
            }

            String key = unescapeJsonString(rawKey.substring(1, rawKey.length() - 1)).toLowerCase();
            String value = normalizeJsonValue(rawValue);
            fields.put(key, value);
        }

        return fields;
    }

    private static int indexOfTopLevelColon(String value) {
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
            if (!inString && c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeJsonValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        String v = rawValue.trim();
        if ("null".equals(v)) {
            return "";
        }
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            return unescapeJsonString(v.substring(1, v.length() - 1));
        }
        return v;
    }

    private static String unescapeJsonString(String value) {
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "Unknown error" : message;
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

    private record PlayerView(String name, String uuid, String world, String ping) {
    }
}
