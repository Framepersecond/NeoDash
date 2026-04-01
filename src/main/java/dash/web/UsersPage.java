package dash.web;

import dash.WebAuth;
import dash.data.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UsersPage {

    public static String render(long currentUserId, Map<String, WebAuth.UserInfo> users, Iterable<String> roles, Map<String, Integer> roleValues,
            String actorUsername, boolean actorIsMainAdmin, String generatedCode, String message,
            java.util.List<dash.RegistrationApprovalManager.PendingRegistration> pendingRegistrations,
            List<DatabaseManager.ServerRecord> availableServers,
            Map<String, List<DatabaseManager.UserServerAssignment>> assignmentsByUser) {
        List<String> roleList = new ArrayList<>();
        for (String role : roles) {
            roleList.add(role);
        }

        StringBuilder createRoleOptions = new StringBuilder();
        for (String role : roleList) {
            createRoleOptions.append("<option value='").append(escapeHtml(role)).append("'>")
                    .append(escapeHtml(role)).append("</option>");
        }

        int highestRoleValue = 0;
        for (Integer value : roleValues.values()) {
            if (value != null) {
                highestRoleValue = Math.max(highestRoleValue, value);
            }
        }

        int actorRoleValue = 0;
        WebAuth.UserInfo actorInfo = users.get(actorUsername);
        if (actorIsMainAdmin) {
            actorRoleValue = highestRoleValue + 1;
        } else if (actorInfo != null) {
            actorRoleValue = roleValues.getOrDefault(actorInfo.role().toUpperCase(), 0);
        }

        StringBuilder usersGrid = new StringBuilder();
        for (WebAuth.UserInfo user : users.values()) {
            String selectedRole = "MAIN_ADMIN".equalsIgnoreCase(user.role()) ? "ADMIN" : user.role();
            int userRoleValue = user.mainAdmin() ? highestRoleValue + 1 : roleValues.getOrDefault(selectedRole.toUpperCase(), 0);
            boolean canManageThisUser = actorIsMainAdmin || (!user.mainAdmin() && userRoleValue < actorRoleValue);
            String userName = escapeHtml(user.username());
            String roleName = escapeHtml(user.role());

            StringBuilder perUserRoleOptions = new StringBuilder();
            for (String role : roleList) {
                int candidateValue = roleValues.getOrDefault(role.toUpperCase(), 0);
                boolean assignable = actorIsMainAdmin || candidateValue < actorRoleValue;
                boolean selected = role.equalsIgnoreCase(selectedRole);
                perUserRoleOptions.append("<option value='").append(role).append("'")
                        .append(assignable ? "" : " disabled")
                        .append(selected ? " selected" : "")
                        .append(">")
                        .append(role)
                        .append(" (v=").append(candidateValue).append(")")
                        .append("</option>");
            }

            String disableClass = canManageThisUser ? "" : " opacity-50 cursor-not-allowed";
            String roleBadgeClass = user.mainAdmin()
                    ? "bg-amber-500/20 text-amber-300 border border-amber-500/40"
                    : "bg-cyan-500/15 text-cyan-300 border border-cyan-500/35";

            usersGrid.append("<article class='rounded-2xl bg-[#1e293b] border border-slate-700/70 p-5 shadow-lg'>")
                    .append("<div class='flex items-center justify-between gap-3 mb-4'>")
                    .append("<h3 class='text-lg font-semibold text-white truncate'>").append(userName).append("</h3>")
                    .append("<span class='px-2.5 py-1 rounded-full text-[11px] font-semibold uppercase tracking-wider ")
                    .append(roleBadgeClass).append("'>")
                    .append(user.mainAdmin() ? "MAIN ADMIN" : roleName)
                    .append("</span>")
                    .append("</div>")
                    .append("<form action='/users/updateRole' method='post' class='space-y-3'>")
                    .append("<input type='hidden' name='username' value='").append(userName).append("'>")
                    .append("<label class='block text-xs uppercase tracking-wider text-slate-400'>Global Role</label>")
                    .append("<select name='role'").append(canManageThisUser ? "" : " disabled")
                    .append(" class='w-full bg-[#0f172a] border border-slate-600 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500")
                    .append(disableClass).append("'>")
                    .append(perUserRoleOptions)
                    .append("</select>")
                    .append("<div class='flex flex-col sm:flex-row gap-3 mt-6 w-full'>")
                    .append("<button type='submit'").append(canManageThisUser ? "" : " disabled")
                    .append(" class='w-full sm:w-auto rounded-lg px-4 py-2 bg-cyan-900/50 text-cyan-300 border border-cyan-700/40 hover:bg-cyan-800/50 text-sm font-semibold transition-colors")
                    .append(disableClass).append("'>Update Role</button>")
                    .append("</div>")
                    .append("</form>")
                    .append("<form action='/users/delete' method='post' class='mt-3' onsubmit=\"return confirm('Delete user account?');\">")
                    .append("<input type='hidden' name='username' value='").append(userName).append("'>")
                    .append("<div class='flex flex-col sm:flex-row gap-3 mt-6 w-full'>")
                    .append("<button type='submit'").append(canManageThisUser ? "" : " disabled")
                    .append(" class='w-full sm:w-auto rounded-lg px-4 py-2 border border-rose-500/50 text-rose-300 hover:bg-rose-500/10 text-sm font-semibold transition-colors")
                    .append(disableClass).append("'>Delete User</button>")
                    .append("</div>")
                    .append("</form>")
                    .append(renderServerAssignmentSection(user, canManageThisUser, availableServers, assignmentsByUser))
                    .append(canManageThisUser ? ""
                            : "<p class='text-[11px] text-slate-500 mt-3'>You can only manage users below your role rank.</p>")
                    .append("</article>");
        }

        String alertBox = "";
        if (generatedCode != null && !generatedCode.isBlank()) {
            alertBox += "<div class='mb-4 p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 text-sm'>"
                    + "Generated invite code: <span class='font-mono font-bold'>" + escapeHtml(generatedCode)
                    + "</span></div>";
        }
        if (message != null && !message.isBlank()) {
            alertBox += "<div class='mb-4 p-3 rounded-lg border text-sm " + messageBannerClasses(message) + "'>"
                    + escapeHtml(message) + "</div>";
        }

        String content = HtmlTemplate.statsHeader(currentUserId)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='max-w-7xl mx-auto space-y-6'>"
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700/70 p-6 shadow-xl'>"
                + "<h1 class='text-2xl font-bold text-white'>User & Role Management</h1>"
                + "<p class='text-sm text-slate-400 mt-1'>Create users and manage global role assignments.</p>"
                + "</section>"
                + alertBox
                + "<section class='rounded-2xl bg-[#1e293b] border border-slate-700/70 p-6 shadow-lg'>"
                + "<h2 class='text-lg font-semibold text-white mb-4'>Create User</h2>"
                + "<form action='/users/create' method='post' class='w-full'>"
                + "<div class='flex flex-col gap-2 w-full mb-4'>"
                + "<label for='create-username' class='text-xs uppercase tracking-wider text-slate-400'>Username</label>"
                + "<input id='create-username' name='username' required placeholder='Username' class='w-full bg-[#0f172a] border border-slate-600 rounded-lg px-3 py-3 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500'>"
                + "</div>"
                + "<div class='flex flex-col gap-2 w-full mb-4'>"
                + "<label for='create-password' class='text-xs uppercase tracking-wider text-slate-400'>Password</label>"
                + "<input id='create-password' type='password' name='password' required placeholder='Password' class='w-full bg-[#0f172a] border border-slate-600 rounded-lg px-3 py-3 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500'>"
                + "</div>"
                + "<div class='flex flex-col gap-2 w-full mb-4'>"
                + "<label for='create-role' class='text-xs uppercase tracking-wider text-slate-400'>Role</label>"
                + "<select id='create-role' name='role' class='w-full bg-[#0f172a] border border-slate-600 rounded-lg px-3 py-3 text-sm text-slate-100 focus:outline-none focus:border-cyan-500'>"
                + createRoleOptions
                + "</select>"
                + "</div>"
                + "<div class='flex flex-col sm:flex-row gap-3 mt-6 w-full'>"
                + "<button type='submit' class='w-full sm:w-auto rounded-lg bg-cyan-500 hover:bg-cyan-400 text-black font-bold px-4 py-3 transition-colors'>Create User</button>"
                + "</div>"
                + "</form>"
                + "</section>"
                + "<section>"
                + "<div class='grid grid-cols-1 md:grid-cols-2 gap-4'>"
                + usersGrid
                + "</div>"
                + "</section>"
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript();

        return HtmlTemplate.page("Users", "/users", content);
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

    private static String messageBannerClasses(String message) {
        if (message == null || message.isBlank()) {
            return "bg-sky-900/40 text-sky-300 border-sky-700/50";
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(lower, "success", "created", "updated", "deleted")) {
            return "bg-emerald-900/40 text-emerald-400 border-emerald-700/50";
        }
        if (containsAny(lower, "error", "failed", "cannot", "invalid")) {
            return "bg-red-900/40 text-red-400 border-red-700/50";
        }
        return "bg-sky-900/40 text-sky-300 border-sky-700/50";
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String renderServerAssignmentSection(WebAuth.UserInfo user, boolean canManageThisUser,
            List<DatabaseManager.ServerRecord> availableServers,
            Map<String, List<DatabaseManager.UserServerAssignment>> assignmentsByUser) {
        String disableClass = canManageThisUser ? "" : " opacity-50 cursor-not-allowed";
        String disabledAttr = canManageThisUser ? "" : " disabled";

        StringBuilder serverOptions = new StringBuilder();
        for (DatabaseManager.ServerRecord server : availableServers == null ? List.<DatabaseManager.ServerRecord>of() : availableServers) {
            serverOptions.append("<option value='").append(server.id()).append("'>")
                    .append(escapeHtml(server.name())).append(" (#").append(server.id()).append(")")
                    .append("</option>");
        }
        if (serverOptions.length() == 0) {
            serverOptions.append("<option value=''>No servers available</option>");
        }

        StringBuilder assigned = new StringBuilder();
        List<DatabaseManager.UserServerAssignment> assignments = assignmentsByUser == null
                ? List.of()
                : assignmentsByUser.getOrDefault(user.username(), List.of());
        if (assignments.isEmpty()) {
            assigned.append("<p class='text-xs text-slate-500'>No server assignments yet.</p>");
        } else {
            for (DatabaseManager.UserServerAssignment assignment : assignments) {
                assigned.append("<div class='flex items-center justify-between gap-2 rounded-lg bg-[#0f172a] border border-slate-700/60 px-3 py-2'>")
                        .append("<div class='text-xs'>")
                        .append("<p class='text-slate-200'>").append(escapeHtml(assignment.serverName())).append("</p>")
                        .append("</div>")
                        .append("<form action='/users/revokeServer' method='post'>")
                        .append("<input type='hidden' name='username' value='").append(escapeHtml(user.username())).append("'>")
                        .append("<input type='hidden' name='server_id' value='").append(assignment.serverId()).append("'>")
                        .append("<button type='submit'").append(disabledAttr)
                        .append(" class='px-2.5 py-1 rounded-md border border-rose-500/50 text-rose-300 text-[11px] hover:bg-rose-500/10")
                        .append(disableClass).append("'>Revoke</button>")
                        .append("</form>")
                        .append("</div>");
            }
        }

        return "<div class='mt-4 pt-4 border-t border-slate-700/60'>"
                + "<p class='text-xs uppercase tracking-wider text-slate-400 mb-2'>Server Assignment</p>"
                + "<form action='/users/assignServer' method='post' class='grid grid-cols-1 md:grid-cols-[1fr_110px] gap-2'>"
                + "<input type='hidden' name='username' value='" + escapeHtml(user.username()) + "'>"
                + "<select name='server_id'" + disabledAttr
                + " class='bg-[#0f172a] border border-slate-600 rounded-lg px-3 py-2 text-xs text-slate-100" + disableClass + "'>"
                + serverOptions
                + "</select>"
                + "<div class='flex flex-col sm:flex-row gap-3 mt-6 w-full md:mt-0'>"
                + "<button type='submit'" + disabledAttr
                + " class='w-full sm:w-auto rounded-lg bg-cyan-900/50 text-cyan-300 border border-cyan-700/40 text-xs font-semibold px-3 py-2 hover:bg-cyan-800/50" + disableClass + "'>Assign</button>"
                + "</div>"
                + "</form>"
                + "<div class='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 w-full mt-2'>"
                + assigned
                + "</div>"
                + "</div>";
    }
}

