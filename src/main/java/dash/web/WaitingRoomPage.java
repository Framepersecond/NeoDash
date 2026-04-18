package dash.web;

public class WaitingRoomPage {

    public static String render(String pendingUser) {
        String content = "<div class='rounded-2xl bg-[#1e293b] border border-slate-700 shadow-2xl p-8 sm:p-10'>"
                + "<div class='flex items-center gap-3 mb-4'>"
                + "<span class='text-3xl'>&#9203;</span>"
                + "<h1 class='text-2xl font-bold text-white'>Approval Pending</h1>"
                + "</div>"
                + "<p class='text-sm text-slate-400 mb-6'>"
                + "Your identity <span class='font-semibold text-cyan-300'>"
                + escapeHtml(pendingUser == null ? "" : pendingUser)
                + "</span> has been verified via SSO. An administrator must approve your access before you can proceed."
                + "</p>"
                + "<a href='/login' class='inline-block rounded-xl bg-cyan-500 hover:bg-cyan-400 text-black font-bold px-5 py-3 transition-colors'>Back to Login</a>"
                + "</div>";

        return HtmlTemplate.authPage("Waiting Room", content);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
