package dash.web;

import dash.NeoDash;
import dash.GithubUpdater;
import dash.NativeMetricsCollector;
import dash.bridge.ServerStateCache;
import dash.data.DatabaseManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HtmlTemplate {

    private static final String[][] NAV_ITEMS = {
            { "layout-dashboard", "Dashboard", "/", "dash.web.stats.read" },
            { "users", "Users", "/users", "dash.web.users.manage" },
            { "shield", "Permissions", "/permissions", "dash.web.users.manage" },
            { "clipboard-list", "Audit Log", "/audit", "dash.web.audit.read" },
            { "download", "Updates", "/updates", "dash.web.stats.read" }
    };

    private static final ThreadLocal<Set<String>> UI_PERMISSIONS = ThreadLocal.withInitial(Set::of);

    public static void setUiPermissions(Set<String> permissions) {
        UI_PERMISSIONS.set(permissions == null ? Set.of() : permissions);
    }

    public static void clearUiPermissions() {
        UI_PERMISSIONS.remove();
    }

    public static boolean can(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        Set<String> grants = UI_PERMISSIONS.get();
        if (isPrivilegedUiUser(grants)) {
            return true;
        }
        // Temporary compatibility fallback while UI permission wiring is incomplete.
        if (grants == null || grants.isEmpty()) {
            return true;
        }
        if (grants.contains("*") || grants.contains("dash.web.*")) {
            return true;
        }
        if (grants.contains(permission)) {
            return true;
        }
        for (String grant : grants) {
            if (grant.endsWith(".*")) {
                String prefix = grant.substring(0, grant.length() - 1);
                if (permission.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPrivilegedUiUser(Set<String> grants) {
        if (grants == null || grants.isEmpty()) {
            return false;
        }
        for (String grant : grants) {
            if (grant == null) {
                continue;
            }
            String value = grant.trim().toUpperCase(Locale.ROOT);
            if ("MAIN_ADMIN".equals(value)
                    || "ROLE:MAIN_ADMIN".equals(value)
                    || "ROLE=MAIN_ADMIN".equals(value)
                    || "ROLE_MAIN_ADMIN".equals(value)
                    || "GLOBAL_ROLE:MAIN_ADMIN".equals(value)
                    || "GLOBAL_ROLE=MAIN_ADMIN".equals(value)) {
                return true;
            }
            int parsedRoleValue = parseRoleValueHint(value);
            if (parsedRoleValue >= 100) {
                return true;
            }
        }
        return false;
    }

    private static int parseRoleValueHint(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_VALUE") || normalized.startsWith("ROLEVALUE")
                || normalized.startsWith("ROLE-VALUE")) {
            int separator = Math.max(normalized.lastIndexOf(':'), normalized.lastIndexOf('='));
            if (separator >= 0 && separator + 1 < normalized.length()) {
                return safeParseInt(normalized.substring(separator + 1).trim());
            }
        }
        if (normalized.matches("^-?\\d+$")) {
            return safeParseInt(normalized);
        }
        return -1;
    }

    private static int safeParseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static String head(String title) {
        return "<!DOCTYPE html>\n" +
                "<html class=\"dark\" lang=\"en\"><head>\n" +
                "<meta charset=\"utf-8\"/>\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n" +
                "<title>" + title + " - Dash Admin</title>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&display=swap\" rel=\"stylesheet\"/>\n"
                +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap\" rel=\"stylesheet\"/>\n"
                +
                "<script src=\"https://cdn.tailwindcss.com?plugins=forms,container-queries\"></script>\n" +
                "<script src=\"https://unpkg.com/lucide@latest\"></script>\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<script>\n" +
                "tailwind.config = {\n" +
                "  darkMode: 'class',\n" +
                "  theme: {\n" +
                "    extend: {\n" +
                "      colors: {\n" +
                "        'primary': '#0dccf2',\n" +
                "        'background-dark': '#0f172a',\n" +
                "        'glass-border': 'rgba(255, 255, 255, 0.08)',\n" +
                "        'glass-surface': 'rgba(255, 255, 255, 0.03)',\n" +
                "        'glass-highlight': 'rgba(255, 255, 255, 0.08)',\n" +
                "      },\n" +
                "      fontFamily: {\n" +
                "        'display': ['Inter', 'sans-serif'],\n" +
                "        'mono': ['JetBrains Mono', 'monospace'],\n" +
                "      },\n" +
                "      boxShadow: {\n" +
                "        'glow-primary': '0 0 20px -5px rgba(13, 204, 242, 0.4)',\n" +
                "        'glow-danger': '0 0 20px -5px rgba(244, 63, 94, 0.4)',\n" +
                "      },\n" +
                "      backgroundImage: {\n" +
                "        'deep-space': 'radial-gradient(circle at 50% 0%, #1e293b 0%, #0f172a 40%, #020617 100%)',\n" +
                "      }\n" +
                "    },\n" +
                "  },\n" +
                "}\n" +
                "</script>\n" +
                "<style>\n" +
                ".console-scrollbar::-webkit-scrollbar { width: 6px; }\n" +
                ".console-scrollbar::-webkit-scrollbar-track { background: transparent; }\n" +
                ".console-scrollbar::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 9999px; }\n"
                +
                ".console-scrollbar::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.2); }\n" +
                ".pixelated { image-rendering: pixelated; image-rendering: crisp-edges; }\n" +
                "@keyframes ndFadeUp{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:none}}\n" +
                "@keyframes ndSlideLeft{from{opacity:0;transform:translateX(-14px)}to{opacity:1;transform:none}}\n" +
                "@keyframes ndScaleIn{from{opacity:0;transform:scale(.96)}to{opacity:1;transform:none}}\n" +
                "@keyframes ndPageExit{to{opacity:0;transform:translateX(-22px)}}\n" +
                "@keyframes ndPageEnter{from{opacity:0;transform:translateX(22px)}}\n" +
                ".nd-slide-left{animation:ndSlideLeft .35s cubic-bezier(.22,1,.36,1) both}\n" +
                ".nd-scale-in{animation:ndScaleIn .5s cubic-bezier(.22,1,.36,1) both}\n" +
                ".nd-page-exit{animation:ndPageExit .18s ease forwards;pointer-events:none}\n" +
                ".nd-page-enter{animation:ndPageEnter .32s cubic-bezier(.22,1,.36,1) both}\n" +
                "@keyframes ndPageExitFade{to{opacity:0}}\n" +
                "@keyframes ndPageEnterDown{from{opacity:0;transform:translateY(-22px)}}\n" +
                ".nd-page-exit-fade{animation:ndPageExitFade .18s ease forwards;pointer-events:none}\n" +
                ".nd-page-enter-down{animation:ndPageEnterDown .32s cubic-bezier(.22,1,.36,1) both}\n" +
                "</style>\n" +
                "</head>\n";
    }

    public static String bodyStart(String currentPath) {
        String activePath = normalizeNavPath(currentPath);
        StringBuilder nav = new StringBuilder();
        int navIdx = 0;
        for (String[] item : NAV_ITEMS) {
            String icon = item[0];
            String label = item[1];
            String path = item[2];
            String requiredPermission = item[3];
            if (!can(requiredPermission)) {
                continue;
            }
            boolean active = path.equals(activePath);

            String activeClass = active
                    ? "bg-cyan-900/40 text-cyan-400 rounded-lg border-cyan-700/40"
                    : "text-slate-400 border-transparent hover:bg-slate-800/60 hover:text-slate-100";

            nav.append("<a href=\"").append(path)
                    .append("\" class=\"flex items-center gap-3 px-4 py-3 rounded-xl border nd-slide-left ").append(activeClass)
                    .append(" transition-all\" style=\"animation-delay:").append(String.format(java.util.Locale.ROOT, "%.2f", navIdx * 0.06)).append("s\">\n")
                    .append("<i data-lucide=\"").append(icon).append("\" class=\"w-[18px] h-[18px]\"></i>\n")
                    .append("<span class=\"text-sm font-medium\">").append(label).append("</span>\n");
            if ("Updates".equals(label) && hasPendingUpdatesBadge()) {
                nav.append("<span class=\"ml-auto inline-flex h-2.5 w-2.5 rounded-full bg-rose-500\"></span>\n");
            }
            nav.append("</a>\n");
            navIdx++;
        }

        return "<body class=\"bg-deep-space text-slate-200 font-display h-screen min-h-screen flex flex-col md:flex-row overflow-x-hidden selection:bg-primary/30 selection:text-white\">\n"
                +
                "<button id=\"mobile-menu-button\" type=\"button\" class=\"md:hidden fixed top-4 left-4 z-50 inline-flex items-center justify-center rounded-lg bg-slate-900/90 border border-slate-700 p-2 text-slate-200\" aria-label=\"Open menu\" aria-controls=\"sidebar\" aria-expanded=\"false\">\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"h-5 w-5\" fill=\"none\" viewBox=\"0 0 24 24\" stroke=\"currentColor\" stroke-width=\"2\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M4 6h16M4 12h16M4 18h16\" /></svg>\n"
                + "</button>\n"
                + "<div id=\"mobile-menu-overlay\" class=\"md:hidden fixed inset-0 z-40 bg-black/60 opacity-0 pointer-events-none transition-opacity duration-300\"></div>\n"
                + "<nav id=\"sidebar\" class=\"fixed inset-y-0 left-0 z-50 w-64 transform -translate-x-full transition-transform duration-300 ease-in-out md:relative md:translate-x-0 flex-shrink-0 flex flex-col bg-[#0b1329]/95 backdrop-blur-xl border-r border-slate-800/80 p-4 h-screen\">\n"
                +
                "<div class=\"md:hidden flex items-center justify-between px-2 py-2 mb-2\">\n" +
                "<span class=\"text-xs uppercase tracking-wider text-slate-400\">Menu</span>\n" +
                "<button id=\"mobile-menu-close\" type=\"button\" class=\"inline-flex items-center justify-center h-8 w-8 rounded-lg border border-slate-700 bg-slate-900/70 text-slate-200\" aria-label=\"Close menu\">X</button>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-2 px-4 py-4 mb-4 nd-scale-in\">\n" +
                "<i data-lucide=\"layout-dashboard\" class=\"w-[22px] h-[22px] text-primary\"></i>\n" +
                "<span class=\"text-lg font-bold text-white\">Dash</span>\n" +
                "</div>\n" +
                "<div class=\"grid grid-cols-2 md:grid-cols-1 gap-1\">\n" +
                nav.toString() +
                "</div>\n" +
                "<div class=\"mt-4 md:mt-auto pt-4 border-t border-white/5\">\n" +
                "<form action='/action' method='post'>\n" +
                "<input type='hidden' name='action' value='logout'>\n" +
                "<button class=\"w-full flex items-center gap-3 px-4 py-3 rounded-xl text-slate-400 hover:bg-rose-500/10 hover:text-rose-400 transition-all\">\n"
                +
                "<i data-lucide=\"log-out\" class=\"w-[18px] h-[18px]\"></i>\n" +
                "<span class=\"text-sm font-medium\">Logout</span>\n" +
                "</button>\n" +
                "</form>\n" +
                "</div>\n" +
                "</nav>\n" +
                "<div id=\"main-content\" class=\"relative flex-1 min-w-0 h-screen overflow-y-auto\">\n";
    }

    public static String bodyEnd() {
        return "</div>\n<script>\n"
                + "if(window.lucide){lucide.createIcons();}\n"
                + "const mobileMenuBtn=document.getElementById('mobile-menu-button');\n"
                + "const sidebar=document.getElementById('sidebar');\n"
                + "const mobileMenuOverlay=document.getElementById('mobile-menu-overlay');\n"
                + "const mobileMenuCloseBtn=document.getElementById('mobile-menu-close');\n"
                + "function openMobileMenu(){\n"
                + "  if(!sidebar){return;}\n"
                + "  sidebar.classList.remove('-translate-x-full');\n"
                + "  sidebar.classList.add('translate-x-0');\n"
                + "  if(mobileMenuOverlay){mobileMenuOverlay.classList.remove('opacity-0','pointer-events-none');}\n"
                + "  if(mobileMenuBtn){mobileMenuBtn.setAttribute('aria-expanded','true');}\n"
                + "}\n"
                + "function closeMobileMenu(){\n"
                + "  if(!sidebar){return;}\n"
                + "  sidebar.classList.remove('translate-x-0');\n"
                + "  sidebar.classList.add('-translate-x-full');\n"
                + "  if(mobileMenuOverlay){mobileMenuOverlay.classList.add('opacity-0','pointer-events-none');}\n"
                + "  if(mobileMenuBtn){mobileMenuBtn.setAttribute('aria-expanded','false');}\n"
                + "}\n"
                + "window.ndCloseMobileMenu=closeMobileMenu;\n"
                + "if(mobileMenuBtn&&sidebar){mobileMenuBtn.onclick=function(){\n"
                + "  var isOpen=sidebar.classList.contains('translate-x-0');\n"
                + "  if(isOpen){closeMobileMenu();}else{openMobileMenu();}\n"
                + "};}\n"
                + "if(mobileMenuCloseBtn){mobileMenuCloseBtn.addEventListener('click',closeMobileMenu);}\n"
                + "if(mobileMenuOverlay){mobileMenuOverlay.addEventListener('click',closeMobileMenu);}\n"
                + "document.addEventListener('keydown',function(e){if(e.key==='Escape'){closeMobileMenu();}});\n"
                + "(function(){\n"
                + "  const mc=document.getElementById('main-content');\n"
                + "  if(!mc) return;\n"
                + "  const children=Array.from(mc.children);\n"
                + "  children.forEach(function(el,i){\n"
                + "    el.style.opacity='0';\n"
                + "    el.style.transform='translateY(18px)';\n"
                + "    el.style.transition='opacity .45s cubic-bezier(.22,1,.36,1),transform .45s cubic-bezier(.22,1,.36,1)';\n"
                + "    el.style.transitionDelay=(i*0.07)+'s';\n"
                + "  });\n"
                + "  requestAnimationFrame(function(){\n"
                + "    requestAnimationFrame(function(){\n"
                + "      children.forEach(function(el){\n"
                + "        el.style.opacity='1';\n"
                + "        el.style.transform='none';\n"
                + "      });\n"
                + "    });\n"
                + "  });\n"
                + "})();\n"
                + "(function(){\n"
                + "  var _navigating=false;\n"
                + "  function updateNav(pathname){\n"
                + "    var p=pathname.split('?')[0];\n"
                + "    document.querySelectorAll('nav a[href]').forEach(function(a){\n"
                + "      var ap=(a.getAttribute('href')||'').split('?')[0];\n"
                + "      var active=ap===p||(ap!=='/'&&p.startsWith(ap));\n"
                + "      ['bg-cyan-900/40','text-cyan-400','rounded-lg','border-cyan-700/40',\n"
                + "       'text-slate-400','border-transparent','hover:bg-slate-800/60','hover:text-slate-100']\n"
                + "        .forEach(function(c){a.classList.remove(c);});\n"
                + "      if(active){\n"
                + "        ['bg-cyan-900/40','text-cyan-400','rounded-lg','border-cyan-700/40'].forEach(function(c){a.classList.add(c);});\n"
                + "      } else {\n"
                + "        ['text-slate-400','border-transparent','hover:bg-slate-800/60','hover:text-slate-100'].forEach(function(c){a.classList.add(c);});\n"
                + "      }\n"
                + "    });\n"
                + "  }\n"
                + "  async function navigateTo(url,push,dir){\n"
                + "    if(_navigating) return;\n"
                + "    _navigating=true;\n"
                + "    var mc=document.getElementById('main-content');\n"
                + "    if(!mc){window.location.href=url;_navigating=false;return;}\n"
                + "    var exitCls=dir==='down'?'nd-page-exit-fade':'nd-page-exit';\n"
                + "    var enterCls=dir==='down'?'nd-page-enter-down':'nd-page-enter';\n"
                + "    mc.classList.add(exitCls);\n"
                + "    var html;\n"
                + "    try{\n"
                + "      var r=await fetch(url,{headers:{'X-Requested-With':'nd-nav'}});\n"
                + "      if(!r.ok) throw new Error();\n"
                + "      html=await r.text();\n"
                + "    } catch(e){\n"
                + "      window.location.href=url;\n"
                + "      _navigating=false;\n"
                + "      return;\n"
                + "    }\n"
                + "    await new Promise(function(res){setTimeout(res,180);});\n"
                + "    var doc=new DOMParser().parseFromString(html,'text/html');\n"
                + "    var fresh=doc.getElementById('main-content');\n"
                + "    if(!fresh){window.location.href=url;_navigating=false;return;}\n"
                + "    mc.classList.remove(exitCls);\n"
                + "    mc.innerHTML=fresh.innerHTML;\n"
                + "    document.title=doc.title;\n"
                + "    mc.classList.add(enterCls);\n"
                + "    mc.querySelectorAll('script').forEach(function(s){\n"
                + "      var ns=document.createElement('script');\n"
                + "      if(s.src) ns.src=s.src; else ns.textContent=s.textContent;\n"
                + "      s.parentNode.replaceChild(ns,s);\n"
                + "    });\n"
                + "    if(window.lucide) lucide.createIcons();\n"
                + "    var u=new URL(url,window.location.origin);\n"
                + "    updateNav(u.pathname);\n"
                + "    if(push!==false) history.pushState({ndUrl:url,ndDir:dir||''},'',url);\n"
                + "    setTimeout(function(){mc.classList.remove(enterCls);},400);\n"
                + "    _navigating=false;\n"
                + "  }\n"
                + "  window.ndNav={go:function(url){navigateTo(url,true,'');},goDown:function(url){navigateTo(url,true,'down');}};\n"
                + "  document.addEventListener('click',function(e){\n"
                + "    var a=e.target.closest('a[data-nd-nav]');\n"
                + "    if(a){\n"
                + "      var href=a.getAttribute('href');\n"
                + "      if(href&&href.charAt(0)!=='#'&&!href.startsWith('javascript:')){\n"
                + "        e.preventDefault();\n"
                + "        if(window.ndCloseMobileMenu){window.ndCloseMobileMenu();}\n"
                + "        navigateTo(href,true,a.getAttribute('data-nd-nav')||'');\n"
                + "        return;\n"
                + "      }\n"
                + "    }\n"
                + "    var na=e.target.closest('nav a[href]');\n"
                + "    if(!na) return;\n"
                + "    var href=na.getAttribute('href');\n"
                + "    if(!href||href.charAt(0)==='#'||href.startsWith('javascript:')) return;\n"
                + "    if(href.startsWith('http')&&new URL(href).origin!==window.location.origin) return;\n"
                + "    e.preventDefault();\n"
                + "    if(window.ndCloseMobileMenu){window.ndCloseMobileMenu();}\n"
                + "    navigateTo(href);\n"
                + "  });\n"
                + "  window.addEventListener('popstate',function(e){\n"
                + "    var dir=e.state&&e.state.ndDir||'';\n"
                + "    navigateTo(window.location.pathname+window.location.search,false,dir);\n"
                + "  });\n"
                + "  history.replaceState({ndUrl:window.location.href,ndDir:''},'',window.location.href);\n"
                + "})();\n"
                + "</script>\n</body></html>";
    }

    public static String page(String title, String currentPath, String content) {
        return head(title) + bodyStart(currentPath) + content + liveStatsRefreshScript() + bodyEnd();
    }

    public static String authPage(String title, String content) {
        return head(title) +
                "<body class=\"bg-[#0f172a] text-slate-200 font-display min-h-screen flex items-center justify-center px-4\">\n" +
                "<div class=\"absolute inset-0 bg-[radial-gradient(circle_at_18%_12%,rgba(6,182,212,0.14),transparent_34%),radial-gradient(circle_at_82%_0%,rgba(16,185,129,0.08),transparent_36%)]\"></div>\n"
                +
                "<div class=\"relative z-10 w-full max-w-md nd-scale-in\">\n" +
                content +
                "</div>\n" +
                "</body></html>";
    }

    public static String statsHeader(long userId) {
        return buildGlobalStatsHeader(userId);
    }

    private static String buildGlobalStatsHeader(long userId) {
        String cpuTotal = "0.0 %";
        String ramUsageTotal = "0 MB";
        String avgTps = "0.00";
        String onlineServers = "0 / 0 Online";

        DatabaseManager databaseManager = NeoDash.getDatabaseManager();
        List<DatabaseManager.ServerRecord> servers = databaseManager == null || userId <= 0
                ? List.of()
                : databaseManager.getServersForUser(userId);

        double totalTps = 0.0d;
        int totalOnline = 0;
        int totalRamUsed = 0;
        int totalServers = servers.size();
        Set<String> seenInstances = new java.util.HashSet<>();
        java.util.Map<String, Double> hostCpuMap = new java.util.HashMap<>();

        for (DatabaseManager.ServerRecord server : servers) {
            ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(server.id(), server);
            boolean tcpOnline = isSocketOnline(server.port());
            boolean online = snapshot.online() || tcpOnline;
            if (!online) {
                continue;
            }

            String ipAddress = server.ipAddress();
            if (ipAddress == null || ipAddress.isBlank()) {
                ipAddress = "127.0.0.1";
            }

            String instanceKey = ipAddress + ":" + server.port();
            if (seenInstances.add(instanceKey)) {
                totalOnline++;
                double tpsValue = snapshot.online() ? snapshot.tps() : 0.0d;
                long ramUsed = snapshot.online() ? Math.max(0, snapshot.ramUsedMb()) : 0L;
                double cpuValue = Math.max(0.0d, snapshot.cpuUsage());

                if (!snapshot.online()) {
                    java.util.Optional<Long> pidOpt = NativeMetricsCollector.findPidByDirectory(server.pathToDir());
                    if (pidOpt.isPresent()) {
                        NativeMetricsCollector.JvmMetrics nativeMetrics = NativeMetricsCollector.getJvmMetrics(pidOpt.get());
                        tpsValue = Math.max(0.0d, nativeMetrics.tps());
                        ramUsed = Math.max(0L, nativeMetrics.usedRamMb());
                        cpuValue = Math.max(0.0d, nativeMetrics.cpuUsage());
                    }
                }

                totalTps += tpsValue;
                totalRamUsed += (int) ramUsed;
                hostCpuMap.put(ipAddress, cpuValue);
            }
        }

        if (!hostCpuMap.isEmpty()) {
            double avgNetworkCpu = hostCpuMap.values().stream().mapToDouble(Double::doubleValue).average()
                    .orElse(0.0d);
            cpuTotal = String.format(java.util.Locale.ROOT, "%.1f %%", avgNetworkCpu);
        }

        if (totalOnline > 0) {
            avgTps = String.format(Locale.ROOT, "%.2f", totalTps / totalOnline);
        }
        ramUsageTotal = totalRamUsed + " MB";
        onlineServers = totalOnline + " / " + totalServers + " Online";

        return statsHeaderGlobal(cpuTotal, ramUsageTotal, avgTps, onlineServers);
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

    public static String statsHeader(ServerStateCache.ServerStateSnapshot snapshot) {
        String serverActions = "";
        if (can("dash.web.server.control")) {
            serverActions = "<form action='/action' method='post' class='w-full sm:w-auto'><input type='hidden' name='action' value='restart'>\n"
                    +
                    "<button class=\"flex w-full sm:w-auto justify-center items-center gap-2 px-6 py-3 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 group\">\n"
                    +
                    "<span class=\"material-symbols-outlined text-[20px] group-hover:animate-spin\">refresh</span>\n"
                    +
                    "<span class=\"text-sm font-semibold\">Restart</span>\n" +
                    "</button></form>\n" +
                    "<form action='/action' method='post' class='w-full sm:w-auto' onsubmit=\"return confirm('STOP SERVER?');\">\n"
                    +
                    "<input type='hidden' name='action' value='stop'>\n" +
                    "<button class=\"flex w-full sm:w-auto justify-center items-center gap-2 px-6 py-3 rounded-full bg-rose-500/10 border border-rose-500/20 text-rose-400 hover:bg-rose-600 hover:text-white hover:shadow-glow-danger transition-all duration-300\">\n"
                    +
                    "<span class=\"material-symbols-outlined text-[20px]\">power_settings_new</span>\n" +
                    "<span class=\"text-sm font-semibold\">Stop</span>\n" +
                    "</button></form>\n";
        }

        String uptimeValue = "--";
        String tpsValue = "--";
        String ramValue = "--";
        String uptimeAccent = "text-emerald-300";
        if (snapshot != null) {
            uptimeValue = snapshot.online() ? formatUptime(snapshot.uptime()) : "Offline";
            uptimeAccent = snapshot.online() ? "text-emerald-300" : "text-rose-300";
            tpsValue = String.format(Locale.ROOT, "%.2f", snapshot.tps());
            ramValue = snapshot.ramMaxMb() > 0
                    ? snapshot.ramUsedMb() + " / " + snapshot.ramMaxMb() + " MB"
                    : snapshot.ramUsedMb() + " MB";
        }

        return "<div id=\"live-stats-header\">"
                + "<header class=\"w-full px-6 py-4 flex-shrink-0\">\n" +
                "<div class=\"grid grid-cols-1 md:grid-cols-4 gap-4\">\n" +
                "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 hover:bg-slate-800/60 transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">Server Uptime</span>\n" +
                "<span id=\"uptime-val\" class=\"text-xl font-bold tracking-tight " + uptimeAccent + "\">" + uptimeValue
                + "</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-emerald-500/10 flex items-center justify-center text-emerald-400 group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">dns</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 hover:bg-slate-800/60 transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">TPS</span>\n" +
                "<span id=\"tps-val\" class=\"text-xl font-bold text-cyan-400 tracking-tight\">" + tpsValue
                + "</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-primary/10 flex items-center justify-center text-primary group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">speed</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 hover:bg-slate-800/60 transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">RAM Usage</span>\n" +
                "<span id=\"ram-val\" class=\"text-xl font-bold text-teal-400 tracking-tight\">" + ramValue
                + "</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-amber-500/10 flex items-center justify-center text-amber-400 group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">memory</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"flex flex-wrap items-center justify-start md:justify-end gap-3 pl-0 md:pl-4\">\n" +
                serverActions +
                "</div>\n" +
                "</div>\n" +
                "</header>\n"
                + "</div>";
    }

    public static String statsHeaderGlobal(String cpuTotal, String ramTotal, String avgTps, String onlineServers) {
        return "<div id=\"live-stats-header\">"
                + "<header class=\"w-full px-6 py-4 flex-shrink-0\">\n"
                + "<div class=\"grid grid-cols-1 md:grid-cols-4 gap-4\">\n"
                + metricCard("CPU TOTAL", safeText(cpuTotal), "text-cyan-400", "memory")
                + metricCard("RAM USAGE TOTAL", safeText(ramTotal), "text-teal-400", "storage")
                + metricCard("AVG. TPS", safeText(avgTps), "text-cyan-400", "speed")
                + metricCard("ONLINE SERVERS", safeText(onlineServers), "text-emerald-300", "dns")
                + "</div>\n"
                + "</header>\n"
                + "</div>";
    }

    public static String statsScript() {
        return "<script>\n" +
                "function pollStats() {\n" +
                "  fetch('/api/stats').then(r => r.json()).then(d => {\n" +
                "    if(d.error) return;\n" +
                "    document.getElementById('tps-val').innerText = d.tps.toFixed(1);\n" +
                "    document.getElementById('ram-val').innerText = d.ram_used + ' / ' + d.ram_max + ' MB';\n" +
                "    document.getElementById('uptime-val').innerText = d.uptime;\n" +
                "  }).catch(e=>{});\n" +
                "}\n" +
                "setInterval(pollStats, 2000);\n" +
                "window.onload = function() { pollStats(); };\n" +
                "</script>\n";
    }

    private static String safeText(String text) {
        if (text == null || text.isBlank()) {
            return "--";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String metricCard(String label, String value, String accentClass, String icon) {
        return "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 hover:bg-slate-800/60 transition-all duration-300\">\n"
                + "<div class=\"flex flex-col gap-1\">\n"
                + "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">" + safeText(label)
                + "</span>\n"
                + "<span class=\"text-xl font-bold tracking-tight " + accentClass + "\">" + value + "</span>\n"
                + "</div>\n"
                + "<div class=\"h-10 w-10 rounded-full bg-primary/10 flex items-center justify-center text-primary group-hover:scale-110 transition-transform\">\n"
                + "<span class=\"material-symbols-outlined text-[20px]\">" + safeText(icon) + "</span>\n"
                + "</div>\n"
                + "</div>\n";
    }

    private static boolean hasPendingUpdatesBadge() {
        if (GithubUpdater.UPDATE_READY) {
            return true;
        }
        DatabaseManager databaseManager = NeoDash.getDatabaseManager();
        if (databaseManager == null) {
            return false;
        }
        String latestDash = GithubUpdater.LATEST_DASH_VERSION;
        String latestFabricDash = GithubUpdater.LATEST_FABRICDASH_VERSION;
        for (DatabaseManager.ServerRecord server : databaseManager.listServers()) {
            ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(server.id(), server);
            boolean fabricFamily = isFabricFamilyServer(server);
            String latest = fabricFamily ? latestFabricDash : latestDash;
            if (!GithubUpdater.isKnownVersion(latest)) {
                continue;
            }
            if (GithubUpdater.isVersionOutdated(snapshot.dashVersion(), latest)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFabricFamilyServer(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return false;
        }
        String combined = (server.name() == null ? "" : server.name()) + " "
                + (server.pathToDir() == null ? "" : server.pathToDir()) + " "
                + (server.startCommand() == null ? "" : server.startCommand());
        String lower = combined.toLowerCase(Locale.ROOT);
        return lower.contains("fabric") || lower.contains("quilt");
    }

    private static String normalizeNavPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }

        String path = rawPath.trim();
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.startsWith("/server/settings")) {
            return "/server/settings";
        }
        if ("/console".equals(path) || path.startsWith("/server")) {
            return "/";
        }
        if (path.startsWith("/players")) {
            return "/players";
        }
        if (path.startsWith("/updates")) {
            return "/updates";
        }
        if (path.startsWith("/files")) {
            return "/files";
        }
        if (path.startsWith("/plugins")) {
            return "/plugins";
        }
        if (path.startsWith("/users")) {
            return "/users";
        }
        if (path.startsWith("/permissions")) {
            return "/permissions";
        }
        if (path.startsWith("/audit")) {
            return "/audit";
        }
        if (path.startsWith("/scheduled-tasks")) {
            return "/scheduled-tasks";
        }
        if (path.startsWith("/plugin-settings")) {
            return "/plugin-settings";
        }
        if (path.startsWith("/settings")) {
            return "/server/settings";
        }
        return "/";
    }

    private static String formatUptime(String raw) {
        if (raw == null || raw.isBlank()) {
            return "--";
        }
        String value = raw.trim();
        if ("offline".equalsIgnoreCase(value)) {
            return "Offline";
        }

        long millis = -1L;
        if (value.matches("\\d+")) {
            millis = Long.parseLong(value);
        } else if (value.toLowerCase(Locale.ROOT).endsWith("ms") && value.substring(0, value.length() - 2).trim().matches("\\d+")) {
            millis = Long.parseLong(value.substring(0, value.length() - 2).trim());
        } else if (value.toLowerCase(Locale.ROOT).endsWith("s") && value.substring(0, value.length() - 1).trim().matches("\\d+")) {
            millis = Long.parseLong(value.substring(0, value.length() - 1).trim()) * 1000L;
        }

        if (millis < 0) {
            return safeText(value);
        }

        long totalMinutes = Math.max(0L, millis / 60000L);
        long days = totalMinutes / (60L * 24L);
        long hours = (totalMinutes % (60L * 24L)) / 60L;
        long minutes = totalMinutes % 60L;

        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %dh %dm", days, hours, minutes);
        }
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm", minutes);
        }
        return "<1m";
    }

    private static String liveStatsRefreshScript() {
        return "<script>\n"
                + "clearInterval(window._ndLiveInterval);\n"
                + "(function(){\n"
                + "  let isRefreshing = false;\n"
                + "  async function refreshLiveHeader(){\n"
                + "    const current = document.getElementById('live-stats-header');\n"
                + "    if(window.location.pathname === '/server'){ return; }\n"
                + "    if(!current || isRefreshing){ return; }\n"
                + "    isRefreshing = true;\n"
                + "    try {\n"
                + "      const response = await fetch(window.location.href, { headers: { 'X-Requested-With': 'live-stats-refresh' } });\n"
                + "      if(!response.ok){ return; }\n"
                + "      const html = await response.text();\n"
                + "      const doc = new DOMParser().parseFromString(html, 'text/html');\n"
                + "      const fresh = doc.getElementById('live-stats-header');\n"
                + "      if(fresh){\n"
                + "        current.innerHTML = fresh.innerHTML;\n"
                + "      }\n"
                + "    } catch (e) {\n"
                + "    } finally {\n"
                + "      isRefreshing = false;\n"
                + "    }\n"
                + "  }\n"
                + "  window._ndLiveInterval = setInterval(refreshLiveHeader, 2000);\n"
                + "})();\n"
                + "</script>\n";
    }
}
