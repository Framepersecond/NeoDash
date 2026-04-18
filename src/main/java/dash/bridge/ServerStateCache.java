package dash.bridge;

import dash.data.DatabaseManager;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight per-server state cache used by page headers.
 */
public final class ServerStateCache {

    private static final long REFRESH_TTL_MS = 3000L;
    private static final Map<Long, CachedEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, CompletableFuture<Void>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final BridgeApiClient BRIDGE_CLIENT = new BridgeApiClient();

    private ServerStateCache() {
    }

    public static ServerStateSnapshot getSnapshot(long serverId, DatabaseManager.ServerRecord server) {
        if (serverId <= 0 || server == null) {
            return ServerStateSnapshot.offline();
        }

        long now = System.currentTimeMillis();
        CachedEntry cached = CACHE.get(serverId);
        if (cached == null) {
            ServerStateSnapshot fallback = ServerStateSnapshot.offline();
            CACHE.putIfAbsent(serverId, new CachedEntry(fallback, 0L));
            triggerRefreshIfNeeded(serverId, server, now);
            return fallback;
        }

        if ((now - cached.timestampMs()) >= REFRESH_TTL_MS) {
            triggerRefreshIfNeeded(serverId, server, now);
        }
        return cached.snapshot();
    }

    public static ServerStateSnapshot getSnapshotBlocking(long serverId, DatabaseManager.ServerRecord server,
            long waitTimeoutMs) {
        ServerStateSnapshot initial = getSnapshot(serverId, server);
        CompletableFuture<Void> refresh = IN_FLIGHT.get(serverId);
        if (refresh == null) {
            return initial;
        }
        long timeout = Math.max(1L, waitTimeoutMs);
        try {
            refresh.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return initial;
        }
        CachedEntry updated = CACHE.get(serverId);
        return updated == null ? initial : updated.snapshot();
    }

    private static void triggerRefreshIfNeeded(long serverId, DatabaseManager.ServerRecord server, long ignoredNow) {
        IN_FLIGHT.compute(serverId, (id, running) -> {
            if (running != null && !running.isDone()) {
                return running;
            }

            return fetchSnapshotAsync(server)
                    .exceptionally(ex -> ServerStateSnapshot.offline())
                    .thenAccept(snapshot -> CACHE.put(id, new CachedEntry(snapshot, System.currentTimeMillis())))
                    .whenComplete((unused, ex) -> IN_FLIGHT.remove(id));
        });
    }

    private static CompletableFuture<ServerStateSnapshot> fetchSnapshotAsync(DatabaseManager.ServerRecord server) {
        return BRIDGE_CLIENT.fetchHealth(server)
                .thenCombine(BRIDGE_CLIENT.fetchStats(server), ServerStateCache::mergeSnapshots)
                .exceptionally(ex -> ServerStateSnapshot.offline());
    }

    private static ServerStateSnapshot mergeSnapshots(
            BridgeApiClient.HealthSnapshot health,
            BridgeApiClient.StatsSnapshot stats) {
        boolean online = health != null && health.online();
        String status = health == null || health.status() == null || health.status().isBlank()
                ? "offline"
                : health.status();
        String uptime = online
                ? (health.uptime() == null || health.uptime().isBlank() ? "--" : health.uptime())
                : "Offline";

        return new ServerStateSnapshot(
                online,
                status,
                stats == null ? 0.0d : stats.tps(),
                stats == null ? 0.0d : stats.cpuUsage(),
                stats == null ? 0 : stats.ramUsedMb(),
                stats == null ? 0 : stats.ramMaxMb(),
                uptime,
                stats == null ? "" : stats.dashVersion());
    }

    private record CachedEntry(ServerStateSnapshot snapshot, long timestampMs) {
    }

    public record ServerStateSnapshot(
            boolean online,
            String status,
            double tps,
            double cpuUsage,
            int ramUsedMb,
            int ramMaxMb,
            String uptime,
            String dashVersion) {

        public static ServerStateSnapshot offline() {
            return new ServerStateSnapshot(false, "offline", 0.0d, 0.0d, 0, 0, "Offline", "");
        }
    }
}

