package dash;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.sun.management.OperatingSystemMXBean;

public class StatsCollector {

    private static final int MAX_SAMPLES = 360;
    private static final long SAMPLE_INTERVAL_SECONDS = 10L;

    private final List<StatsSample> history = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;

    private volatile ScheduledFuture<?> task;

    public StatsCollector() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "neodash-stats-collector");
            t.setDaemon(true);
            return t;
        }), true);
    }

    public StatsCollector(ScheduledExecutorService scheduler) {
        this(scheduler, false);
    }

    private StatsCollector(ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean detectedOsBean = null;
        try {
            java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
            if (base instanceof OperatingSystemMXBean casted) {
                detectedOsBean = casted;
            }
        } catch (Throwable ignored) {
        }
        this.osBean = detectedOsBean;
    }

    public void start() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::collectSample, 0, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void collectSample() {
        double cpuLoadPercent = -1.0;
        long hostRamTotalMb = 0;
        long hostRamUsedMb = 0;

        if (osBean != null) {
            try {
                // Report overall host CPU usage, not only this JVM process.
                double systemCpuLoad = osBean.getSystemCpuLoad();
                double cpuPercent = systemCpuLoad < 0.0 ? 0.0 : (systemCpuLoad * 100.0);
                cpuLoadPercent = Math.min(100.0, cpuPercent);
            } catch (Throwable ignored) {
            }

            try {
                long total = osBean.getTotalMemorySize();
                long free = osBean.getFreeMemorySize();
                if (total > 0) {
                    hostRamTotalMb = total / 1024 / 1024;
                    hostRamUsedMb = (total - Math.max(0L, free)) / 1024 / 1024;
                }
            } catch (Throwable ignored) {
            }
        }

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long heapUsedMb = heapUsage.getUsed() / 1024 / 1024;
        long heapMaxMb = heapUsage.getMax() > 0 ? heapUsage.getMax() / 1024 / 1024
                : Runtime.getRuntime().maxMemory() / 1024 / 1024;

        if (hostRamTotalMb == 0) {
            long total = Runtime.getRuntime().totalMemory() / 1024 / 1024;
            long free = Runtime.getRuntime().freeMemory() / 1024 / 1024;
            hostRamTotalMb = Math.max(total, heapMaxMb);
            hostRamUsedMb = total - free;
        }

        // Keep legacy fields stable for existing front-end widgets until chart keys are fully migrated.
        double tps = 20.0;
        double mspt = cpuLoadPercent < 0 ? 0.0 : Math.max(0.0, cpuLoadPercent / 2.0);

        StatsSample sample = new StatsSample(
                System.currentTimeMillis(),
                tps,
                mspt,
                hostRamUsedMb,
                hostRamTotalMb,
                0,
                0,
                0,
                cpuLoadPercent,
                heapUsedMb,
                heapMaxMb);

        synchronized (history) {
            history.add(sample);
            while (history.size() > MAX_SAMPLES) {
                history.remove(0);
            }
        }
    }

    public List<StatsSample> getHistory() {
        return new ArrayList<>(history);
    }

    public StatsSample getLatest() {
        if (history.isEmpty()) {
            return new StatsSample(System.currentTimeMillis(), 20.0, 0, 0, 0, 0, 0, 0, -1.0, 0, 0);
        }
        return history.get(history.size() - 1);
    }

    public String getHistoryJson() {
        StringBuilder json = new StringBuilder("[");
        List<StatsSample> samples = getHistory();
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0)
                json.append(",");
            json.append(samples.get(i).toJson());
        }
        json.append("]");
        return json.toString();
    }

    public static class StatsSample {
        public final long timestamp;
        public final double tps;
        public final double mspt;
        public final long ramUsed;
        public final long ramMax;
        public final int overworldChunks;
        public final int netherChunks;
        public final int endChunks;
        public final double cpuLoadPercent;
        public final long heapUsedMb;
        public final long heapMaxMb;

        public StatsSample(long timestamp, double tps, double mspt, long ramUsed, long ramMax,
                int overworldChunks, int netherChunks, int endChunks,
                double cpuLoadPercent, long heapUsedMb, long heapMaxMb) {
            this.timestamp = timestamp;
            this.tps = tps;
            this.mspt = mspt;
            this.ramUsed = ramUsed;
            this.ramMax = ramMax;
            this.overworldChunks = overworldChunks;
            this.netherChunks = netherChunks;
            this.endChunks = endChunks;
            this.cpuLoadPercent = cpuLoadPercent;
            this.heapUsedMb = heapUsedMb;
            this.heapMaxMb = heapMaxMb;
        }

        public String toJson() {
            return String.format(Locale.ROOT,
                    "{\"t\":%d,\"tps\":%.2f,\"mspt\":%.2f,\"ram\":%d,\"ramMax\":%d,\"ow\":%d,\"nether\":%d,\"end\":%d,\"cpuUsage\":%.2f,\"heapUsed\":%d,\"heapMax\":%d}",
                    timestamp, tps, mspt, ramUsed, ramMax, overworldChunks, netherChunks, endChunks,
                    cpuLoadPercent, heapUsedMb, heapMaxMb);
        }
    }
}
