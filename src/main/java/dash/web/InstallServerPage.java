package dash.web;

import java.nio.file.Path;
import java.util.List;

public class InstallServerPage {

    private static final List<String> VERSION_SUGGESTIONS = List.of(
            "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3",
            "1.21.2", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1", "1.19.4");

    public static String render(long currentUserId, String message, String error, String warning) {
        return render(currentUserId, message, error, warning, null);
    }

    public static String render(long currentUserId, String message, String error, String warning, Path configuredServerRoot) {
        // Derive a sensible install dir placeholder from the configured server root
        String installDirPlaceholder;
        String pathInfoBanner = "";
        if (configuredServerRoot != null) {
            installDirPlaceholder = configuredServerRoot.toString() + "/my-server";
            pathInfoBanner = "<div class='mb-5 rounded-xl border border-cyan-500/35 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-200'>"
                    + "<strong>📁 Server directory:</strong> Your servers must be placed inside "
                    + "<code class='bg-black/30 px-1 rounded'>" + escapeHtml(configuredServerRoot.toString()) + "</code>. "
                    + "Paths outside this directory will not be visible or persistent on the host."
                    + "</div>";
        } else {
            installDirPlaceholder = System.getProperty("user.home", "/home/user") + "/servers/my-server";
        }

        String messageBlock = (message == null || message.isBlank())
                ? ""
                : "<div class='mb-5 rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200'>"
                        + escapeHtml(message)
                        + "</div>";
        String errorBlock = (error == null || error.isBlank())
                ? ""
                : "<div class='mb-5 rounded-xl border border-rose-500/35 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'>"
                        + escapeHtml(error)
                        + "</div>";
        String warningBlock = (warning == null || warning.isBlank())
                ? ""
                : "<div class='mb-5 rounded-xl border border-amber-500/35 bg-amber-500/10 px-4 py-3 text-sm text-amber-200'>"
                        + escapeHtml(warning)
                        + "</div>";

        String content = HtmlTemplate.statsHeader(currentUserId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='max-w-3xl mx-auto'>"
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-2xl p-8 sm:p-10'>"
                + "<div class='mb-7'>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white tracking-tight'>Install Server</h1>"
                + "<p class='text-sm text-slate-400 mt-2'>Provision a Linux game server and add it to NeoDash automatically.</p>"
                + "</div>"
                + pathInfoBanner
                + messageBlock
                + warningBlock
                + errorBlock
                + "<div id='install-error-box' class='hidden mb-5 rounded-xl border border-rose-500/35 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'></div>"
                + "<form id='install-server-form' method='post' action='/installServer' class='space-y-4'>"
                + inputField("Server Name", "name", "My Paper Server", true)
                + inputField("Install Dir", "install_dir", installDirPlaceholder, true)
                + selectField("Type", "server_type", List.of("PAPER", "PURPUR", "SPIGOT", "BUKKIT", "FABRIC", "QUILT", "VANILLA"), "PAPER")
                + versionField("Version", "version", "1.21.11")
                + textField("Custom Download URL (Optional)", "custom_download_url", "https://example.com/server.jar", false)
                + ramField("RAM (MB)", "ram_mb", "4096", 512, 65536)
                + numberField("Port", "port", "25565", 1, 65535)
                + "<div id='modpack-section'>"
                + modpackInstructionsBlock()
                + textField("Search Modpack (Modrinth Slug)", "modpack_id", "e.g. fabulously-optimized", false)
                + textField("Modpack Version", "modpack_version", "latest", false)
                + "<p class='-mt-2 text-xs text-slate-500'>Optional: leave empty for a standard server install without a modpack.</p>"
                + "</div>"
                + "<div id='neobridge-section'>"
                + numberField("Bridge Port", "bridge_port", "8081", 1, 65535)
                + passwordField("Bridge Secret", "bridge_secret", "Optional shared secret")
                + toggleField("Enable Dash/FabricDash Bridge", "use_plugin_interface", true)
                + "<p class='-mt-2 text-xs text-slate-500'>NeoDash auto-installs Dash for Bukkit-family servers and FabricDash for Fabric/Quilt servers.</p>"
                + "</div>"
                + "<div class='flex flex-wrap items-center gap-3 pt-2'>"
                + "<button id='install-submit-btn' type='submit' class='rounded-xl bg-emerald-500 hover:bg-emerald-400 text-black font-bold px-6 py-3 transition-colors'>Install Server</button>"
                + "<a href='/' class='rounded-xl border border-slate-600 bg-[#0f172a] text-slate-200 font-semibold px-6 py-3 hover:border-cyan-400 hover:text-cyan-300 transition-colors'>Cancel</a>"
                + "</div>"
                + "</form>"
                + "<div id='install-progress-modal' class='hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm items-center justify-center p-4'>"
                + "<div class='w-full max-w-xl rounded-2xl border border-slate-700 bg-[#0f172a] p-6 shadow-2xl'>"
                + "<div class='flex items-center gap-3 mb-3'>"
                + "<span class='h-5 w-5 rounded-full border-2 border-emerald-300 border-t-transparent animate-spin'></span>"
                + "<h3 class='text-white text-lg font-semibold'>Please wait, your server is being prepared...</h3>"
                + "</div>"
                + "<p class='text-slate-400 text-sm mb-3'>This can take a few minutes for modpack downloads and extraction.</p>"
                + "<pre id='install-progress-lines' class='h-44 overflow-y-auto rounded-xl border border-slate-700 bg-black/30 p-3 text-xs text-emerald-200 font-mono'>Waiting to start...</pre>"
                + "</div></div>"
                + "</section>"
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript()
                + bridgeCompatibilityScript()
                + installProgressScript();

        return HtmlTemplate.page("Install Server", "/", content);
    }

    private static String inputField(String label, String name, String placeholder, boolean required) {
        return textField(label, name, placeholder, required);
    }

    private static String textField(String label, String name, String placeholder, boolean required) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='text' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "'"
                + (required ? " required" : "")
                + "></div>";
    }

    private static String numberField(String label, String name, String placeholder, int min, int max) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='number' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "' min='"
                + min
                + "' max='"
                + max
                + "' required></div>";
    }

    private static String ramField(String label, String name, String placeholder, int min, int max) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='number' id='ram_mb' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "' min='"
                + min
                + "' max='"
                + max
                + "'></div>";
    }

    private static String selectField(String label, String name, List<String> options, String defaultValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>")
                .append(escapeHtml(label))
                .append("</label><select name='")
                .append(escapeHtml(name))
                .append("' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors'>");
        for (String option : options) {
            boolean selected = option.equalsIgnoreCase(defaultValue);
            sb.append("<option value='").append(escapeHtml(option)).append("'")
                    .append(selected ? " selected" : "")
                    .append(">")
                    .append(escapeHtml(option))
                    .append("</option>");
        }
        sb.append("</select></div>");
        return sb.toString();
    }

    private static String versionField(String label, String name, String defaultValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>")
                .append(escapeHtml(label))
                .append("</label><input type='text' name='")
                .append(escapeHtml(name))
                .append("' list='version-suggestions' value='")
                .append(escapeHtml(defaultValue))
                .append("' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='e.g. 1.21.11' required>")
                .append("<datalist id='version-suggestions'>");
        for (String version : VERSION_SUGGESTIONS) {
            sb.append("<option value='").append(escapeHtml(version)).append("'></option>");
        }
        sb.append("</datalist><p class='mt-2 text-xs text-slate-500'>Any valid subversion is supported (for example 1.21.2, 1.21.5, 1.21.11).</p></div>");
        return sb.toString();
    }

    private static String modpackInstructionsBlock() {
        return "<div class='rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>"
                + "<p><strong>How to install a Modpack:</strong> Go to Modrinth.com, find your desired modpack, and copy its slug from the URL "
                + "(for example, for <code>modrinth.com/modpack/fabulously-optimized</code>, enter <code>fabulously-optimized</code>). "
                + "Alternatively, paste the direct <code>.mrpack</code> download URL.</p>"
                + "</div>"
                + "<div class='mt-3 rounded-xl border border-rose-500/50 bg-rose-500/15 px-4 py-3 text-sm text-rose-100'>"
                + "<p>⚠️ <strong>WARNING:</strong> You have full freedom to install any modpack on any server type and version. "
                + "However, we provide <strong>NO GUARANTEE</strong> that the mods will work. It is your sole responsibility to ensure that the Modpack, "
                + "Server Type (for example Fabric/Quilt), and Minecraft Version match exactly. Incompatible combinations will cause the server to crash.</p>"
                + "</div>";
    }

    private static String toggleField(String label, String name, boolean checked) {
        return "<label class='flex items-center gap-3 rounded-xl border border-slate-700 bg-[#0f172a] px-4 py-3'>"
                + "<input type='checkbox' name='"
                + escapeHtml(name)
                + "' value='1' class='h-4 w-4 rounded border-slate-500 bg-slate-800 text-emerald-500 focus:ring-emerald-400'"
                + (checked ? " checked" : "")
                + ">"
                + "<span class='text-sm text-slate-200 font-semibold'>"
                + escapeHtml(label)
                + "</span>"
                + "</label>";
    }

    private static String passwordField(String label, String name, String placeholder) {
        return "<div><label class='block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2'>"
                + escapeHtml(label)
                + "</label><input type='password' name='"
                + escapeHtml(name)
                + "' class='w-full rounded-xl bg-[#0f172a] border border-slate-600 px-4 py-3 text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400 transition-colors' placeholder='"
                + escapeHtml(placeholder)
                + "'></div>";
    }

    private static String installProgressScript() {
        return "<script>"
                + "(function(){"
                + "const form=document.getElementById('install-server-form');"
                + "const modal=document.getElementById('install-progress-modal');"
                + "const out=document.getElementById('install-progress-lines');"
                + "const submit=document.getElementById('install-submit-btn');"
                + "const errorBox=document.getElementById('install-error-box');"
                + "let poller=null;"
                + "function showError(msg){if(!errorBox){return;} errorBox.textContent=msg||'Installation request failed. Please retry.'; errorBox.classList.remove('hidden');}"
                + "function clearError(){if(!errorBox){return;} errorBox.textContent=''; errorBox.classList.add('hidden');}"
                + "function resetInstallUi(resetForm){"
                + "if(poller){clearInterval(poller);poller=null;}"
                + "modal.classList.add('hidden');"
                + "modal.classList.remove('flex');"
                + "if(submit){submit.disabled=false;submit.classList.remove('opacity-70','cursor-not-allowed');}"
                + "if(resetForm&&form){form.reset();}"
                + "}"
                + "function poll(){"
                + "fetch('/api/install/progress',{credentials:'same-origin'})"
                + ".then(r=>r.ok?r.json():null)"
                + ".then(d=>{"
                + "if(!d||!Array.isArray(d.lines)){return;}"
                + "out.textContent=d.lines.join('\\n')||'Preparing install...';"
                + "out.scrollTop=out.scrollHeight;"
                + "if(d.done){"
                + "if(poller){clearInterval(poller);poller=null;}"
                + "if(d.error){resetInstallUi(true);showError(d.error);}"
                + "else if(d.redirect){window.location.href=d.redirect;}"
                + "else{window.location.reload();}"
                + "}"
                + "})"
                + ".catch(()=>{});"
                + "}"
                + "if(!form||!modal||!out){return;}"
                + "form.addEventListener('submit',function(event){"
                + "event.preventDefault();"
                + "clearError();"
                + "const form=event.target;"
                + "const typeInput=form.querySelector('select[name=\"server_type\"],input[name=\"server_type\"]');"
                + "const typeVal=typeInput?String(typeInput.value||'').trim().toUpperCase():'';"
                + "const validTypes=['PAPER','PURPUR','SPIGOT','BUKKIT','FABRIC','QUILT','VANILLA'];"
                + "if(validTypes.indexOf(typeVal)<0){showError('Invalid server type selected.');return;}"
                + "const ramVal=document.getElementById('ram_mb').value.trim();"
                + "if(!ramVal||Number.isNaN(Number(ramVal))){showError('RAM (MB) is required and must be a number.');return;}"
                + "const versionInput=form.querySelector('select[name=\"version\"],input[name=\"version\"]');"
                + "const versionVal=versionInput?String(versionInput.value||'').trim():'';"
                + "if(!versionVal){showError('Version is required.');return;}"
                + "modal.classList.remove('hidden');"
                + "modal.classList.add('flex');"
                + "if(submit){submit.disabled=true;submit.classList.add('opacity-70','cursor-not-allowed');}"
                + "out.textContent='Starting installation...';"
                // Start polling immediately
                + "poll();"
                + "poller=setInterval(poll,1500);"
                + "const data=new URLSearchParams();"
                + "for(const pair of new FormData(form)){data.append(pair[0],pair[1]);}"
                + "data.set('ram_mb',ramVal);"
                + "data.set('version',versionVal);"
                + "fetch('/installServer',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:data.toString(),credentials:'same-origin'})"
                + ".then(async function(resp){"
                // On 200 "started": install is in background, keep polling
                + "if(resp.ok){return;}"
                // On error: parse and show
                + "let msg='Installation request failed. Please retry.';"
                + "try{const payload=await resp.json();if(payload&&payload.error){msg=String(payload.error);}else if(payload&&payload.message){msg=String(payload.message);}}catch(_){"
                + "try{const raw=await resp.text();if(raw){msg=String(raw).replace(/<[^>]*>/g,' ').replace(/\\s+/g,' ').trim()||msg;}}catch(__){}"
                + "}"
                + "resetInstallUi(true);"
                + "showError(msg);"
                + "})"
                + ".catch(function(){resetInstallUi(false);showError('Installation request failed. Please retry.');});"
                + "});"
                + "})();"
                + "</script>";
    }

    private static String bridgeCompatibilityScript() {
        return "<script>"
                + "(function(){"
                + "const typeSelect=document.querySelector('select[name=\\\"server_type\\\"]');"
                + "const bridgeSection=document.getElementById('neobridge-section');"
                + "const modpackSection=document.getElementById('modpack-section');"
                + "if(!typeSelect||!modpackSection||!bridgeSection){return;}"
                + "function setSectionEnabled(section, enabled){"
                + "const fields=section.querySelectorAll('input,select,textarea,button');"
                + "for(const field of fields){field.disabled=!enabled;}"
                + "}"
                + "function update(){"
                + "const type=(typeSelect.value||'').toUpperCase();"
                + "const bridgeAllowed=(type==='PAPER'||type==='PURPUR'||type==='SPIGOT'||type==='BUKKIT'||type==='FABRIC'||type==='QUILT');"
                + "bridgeSection.style.display=bridgeAllowed?'block':'none';"
                + "setSectionEnabled(bridgeSection,bridgeAllowed);"
                + "const modpackAllowed=(type==='FABRIC'||type==='QUILT');"
                + "modpackSection.style.display=modpackAllowed?'block':'none';"
                + "setSectionEnabled(modpackSection,modpackAllowed);"
                + "}"
                + "typeSelect.addEventListener('change',update);"
                + "update();"
                + "})();"
                + "</script>";
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
