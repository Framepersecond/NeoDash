package dash.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rolling metrics history used by DashboardPage charts.
 */
public final class MetricsCache {

    // Keep a short rolling window for chart rendering.
    private static final int MAX_POINTS = 30;

    private static final Map<Long, List<Double>> CPU_HISTORY = new ConcurrentHashMap<>();
    private static final Map<Long, List<Double>> RAM_HISTORY = new ConcurrentHashMap<>();

    private MetricsCache() {
    }

    public static void pushSample(long serverId, double cpuPercent, double ramUsedMb) {
        if (serverId <= 0) {
            return;
        }
        append(CPU_HISTORY, serverId, normalize(cpuPercent));
        append(RAM_HISTORY, serverId, Math.max(0.0d, ramUsedMb));
    }

    public static List<Double> cpuHistory(long serverId) {
        return snapshot(CPU_HISTORY, serverId);
    }

    public static List<Double> ramHistory(long serverId) {
        return snapshot(RAM_HISTORY, serverId);
    }

    private static List<Double> snapshot(Map<Long, List<Double>> source, long serverId) {
        if (serverId <= 0) {
            return List.of();
        }
        List<Double> values = source.get(serverId);
        if (values == null) {
            return List.of();
        }
        synchronized (values) {
            return List.copyOf(values);
        }
    }

    private static void append(Map<Long, List<Double>> target, long serverId, double value) {
        List<Double> values = target.computeIfAbsent(serverId,
                key -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (values) {
            values.add(value);
            if (values.size() > MAX_POINTS) {
                values.remove(0);
            }
        }
    }

    private static double normalize(double cpuPercent) {
        if (Double.isNaN(cpuPercent) || Double.isInfinite(cpuPercent)) {
            return 0.0d;
        }
        if (cpuPercent < 0.0d) {
            return 0.0d;
        }
        return Math.min(cpuPercent, 100.0d);
    }
}

