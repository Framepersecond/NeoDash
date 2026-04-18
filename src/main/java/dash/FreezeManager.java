package dash;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks frozen players. In daemon mode, actual freeze enforcement
 * is delegated to the bridge-connected server's Dash plugin.
 */
public final class FreezeManager {

    private static final Set<UUID> FROZEN = ConcurrentHashMap.newKeySet();

    private FreezeManager() {
    }

    public static Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(FROZEN);
    }

    public static boolean isFrozen(UUID uuid) {
        return uuid != null && FROZEN.contains(uuid);
    }

    public static void freeze(UUID uuid) {
        if (uuid != null) FROZEN.add(uuid);
    }

    public static void unfreeze(UUID uuid) {
        if (uuid != null) FROZEN.remove(uuid);
    }

    public static boolean toggleFreeze(UUID uuid) {
        if (uuid == null) return false;
        if (FROZEN.contains(uuid)) {
            FROZEN.remove(uuid);
            return false;
        }
        FROZEN.add(uuid);
        return true;
    }

    public static void onDisconnect(UUID uuid) {
        if (uuid != null) FROZEN.remove(uuid);
    }
}
