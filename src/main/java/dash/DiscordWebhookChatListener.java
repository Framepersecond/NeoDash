package dash;

/**
 * Sends player chat lines to Discord webhooks subscribed to the "chat" event.
 * In daemon mode, chat messages may be relayed from bridge-connected servers.
 */
public class DiscordWebhookChatListener {

    /**
     * Called when a player sends a chat message (from bridge relay).
     *
     * @param playerName the name of the player
     * @param message    the chat message
     * @param serverName the server the player is on
     */
    public static void onChatMessage(String playerName, String message, String serverName) {
        DiscordWebhookManager manager = NeoDash.getDiscordWebhookManager();
        if (manager == null) {
            return;
        }

        manager.dispatch(
                DiscordWebhookManager.EVENT_CHAT,
                "[" + serverName + "] <" + playerName + "> " + message);
    }
}
