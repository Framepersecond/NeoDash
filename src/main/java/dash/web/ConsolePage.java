package dash.web;

import dash.bridge.ServerStateCache;
import dash.data.DatabaseManager;

public class ConsolePage {
    public static String render(long serverId, DatabaseManager.ServerRecord server) {
        ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(serverId, server);
        String statsHeader = HtmlTemplate.statsHeader(snapshot);

        String html = "<main class=\"flex-1 p-6 text-white\">\n" +
                "  <div class=\"max-w-7xl mx-auto space-y-6\">\n" +
                "    <h2 class=\"text-2xl font-bold\">Live Console</h2>\n" +
                "    <pre id=\"terminal-output\" class=\"bg-black/90 text-gray-300 p-4 rounded h-[600px] overflow-y-auto font-mono text-sm shadow-inner\">Loading console logs...</pre>\n" +
                "    <div class=\"flex gap-4\">\n" +
                "      <input type=\"text\" id=\"cmd-input\" class=\"flex-1 bg-slate-800/50 border border-slate-700 rounded px-4 py-2 text-white placeholder-gray-400 focus:outline-none focus:border-cyan-500\" placeholder=\"Enter command...\">\n" +
                "      <button id=\"cmd-send\" class=\"bg-slate-800 hover:bg-slate-700 border border-slate-600 px-6 py-2 rounded text-cyan-400 font-medium transition-colors\">Send</button>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</main>\n" +
                "<script>\n" +
                "  document.addEventListener('DOMContentLoaded', function() {\n" +
                "    const term = document.getElementById('terminal-output');\n" +
                "    const input = document.getElementById('cmd-input');\n" +
                "    const btn = document.getElementById('cmd-send');\n" +
                "    const sid = " + serverId + ";\n" +
                "    function loadLogs() {\n" +
                "      fetch('/api/proxy/console?id=' + sid)\n" +
                "        .then(r => r.ok ? r.json() : Promise.reject('HTTP ' + r.status))\n" +
                "        .then(data => {\n" +
                "          if(Array.isArray(data)) {\n" +
                "            term.textContent = data.join('\\n');\n" +
                "            term.scrollTop = term.scrollHeight;\n" +
                "          } else {\n" +
                "            term.textContent = 'Error: Invalid JSON format received.';\n" +
                "          }\n" +
                "        })\n" +
                "        .catch(e => term.textContent = 'Bridge Error: ' + e);\n" +
                "    }\n" +
                "    setInterval(loadLogs, 2000);\n" +
                "    loadLogs();\n" +
                "    function sendCmd() {\n" +
                "      if(!input.value.trim()) return;\n" +
                "      const fd = new URLSearchParams();\n" +
                "      fd.append('id', sid);\n" +
                "      fd.append('command', input.value);\n" +
                "      fetch('/api/proxy/console', { method: 'POST', body: fd })\n" +
                "        .then(() => { input.value = ''; loadLogs(); });\n" +
                "    }\n" +
                "    btn.addEventListener('click', sendCmd);\n" +
                "    input.addEventListener('keypress', e => { if(e.key === 'Enter') sendCmd(); });\n" +
                "  });\n" +
                "</script>";

        return HtmlTemplate.page("Console", "console", statsHeader + html);    }
}
