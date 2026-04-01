package dash;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FreezeManager {

    private static final Set<UUID> FROZEN = ConcurrentHashMap.newKeySet();

    private FreezeManager() {
    }

    public static boolean toggleFreeze(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (FROZEN.contains(uuid)) {
            FROZEN.remove(uuid);
            return false;
        }
        FROZEN.add(uuid);
        return true;
    }

    public static boolean isFrozen(UUID uuid) {
        return uuid != null && FROZEN.contains(uuid);
    }
}
