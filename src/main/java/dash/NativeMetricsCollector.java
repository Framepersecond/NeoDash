package dash;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

public class NativeMetricsCollector {

    public record JvmMetrics(double tps, double mspt, double cpuUsage, long usedRamMb) {
    }

    // 1. PID DISCOVERY
    public static Optional<Long> findPidByDirectory(String serverDirectoryPath) {
        if (serverDirectoryPath == null || serverDirectoryPath.isBlank()) {
            return Optional.empty();
        }
        String expectedTag = "-Dneodash.server.dir=" + serverDirectoryPath;
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            Optional<Long> tagged = processes.filter(p -> {
                String cmd = p.info().commandLine().orElse("");
                return cmd.contains("java") && cmd.contains(expectedTag);
            }).map(ProcessHandle::pid).findFirst();
            if (tagged.isPresent()) {
                return tagged;
            }
        } catch (Exception e) {
            // fall through to CWD-based scan
        }

        // Fallback: scan /proc/<pid>/cwd for Java processes running from the server directory.
        // This works for scanned servers whose start script has not yet been tagged.
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            return processes.filter(p -> {
                if (p.info().commandLine().map(c -> c.contains("java")).orElse(false)) {
                    try {
                        String cwd = java.nio.file.Files.readSymbolicLink(
                                Paths.get("/proc/" + p.pid() + "/cwd")).toAbsolutePath().normalize().toString();
                        return serverDirectoryPath.equals(cwd);
                    } catch (Exception ignored) {}
                }
                return false;
            }).map(ProcessHandle::pid).findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // 2. LINUX RAM (RSS)
    public static long getResidentMemoryBytes(long pid) {
        try {
            String statm = Files.readString(Paths.get("/proc/" + pid + "/statm"));
            long rssPages = Long.parseLong(statm.split("\\s+")[1]);
            return rssPages * 4096L; // Page size in bytes
        } catch (Exception e) {
            return 0L;
        }
    }

    // 3. LINUX CPU USAGE
    public static double getAverageCpuUsagePercentage(long pid) {
        try {
            String uptimeStats = Files.readString(Paths.get("/proc/uptime")).split("\\s+")[0];
            double systemUptime = Double.parseDouble(uptimeStats);
            String statContent = Files.readString(Paths.get("/proc/" + pid + "/stat"));
            int closingParenthesis = statContent.lastIndexOf(')');
            String[] stats = statContent.substring(closingParenthesis + 2).split("\\s+");

            long utime = Long.parseLong(stats[11]);
            long stime = Long.parseLong(stats[12]);
            long starttime = Long.parseLong(stats[19]);

            double totalTime = utime + stime;
            double processUptimeSeconds = systemUptime - (starttime / 100.0); // 100.0 is standard Linux HERTZ
            if (processUptimeSeconds <= 0) {
                return 0.0;
            }
            double rawCpu = 100.0 * ((totalTime / 100.0) / processUptimeSeconds);
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            double normalized = rawCpu / cores;
            return Math.max(0.0, Math.min(100.0, normalized));
        } catch (Exception e) {
            return 0.0;
        }
    }

    // 4. JMX ATTACH API (TPS & MSPT)
    public static double[] getTickMetrics(long pid) {
        // Returns [TPS, MSPT]
        VirtualMachine vm = null;
        JMXConnector connector = null;
        try {
            vm = VirtualMachine.attach(String.valueOf(pid));
            String connectorAddress = vm.startLocalManagementAgent();
            if (connectorAddress == null) {
                Properties props = vm.getAgentProperties();
                connectorAddress = props.getProperty("com.sun.management.jmxremote.localConnectorAddress");
            }
            if (connectorAddress == null || connectorAddress.isBlank()) {
                return new double[] { 0.0, 0.0 };
            }
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            connector = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbeanConn = connector.getMBeanServerConnection();

            ObjectName serverName = new ObjectName("net.minecraft.server:type=Server");
            Object avgTickTimeObj = mbeanConn.getAttribute(serverName, "averageTickTime");

            double avgMspt = 0.0;
            if (avgTickTimeObj instanceof Number) {
                avgMspt = ((Number) avgTickTimeObj).doubleValue();
            } else if (avgTickTimeObj instanceof long[]) {
                long[] times = (long[]) avgTickTimeObj;
                long sum = 0;
                for (long t : times) {
                    sum += t;
                }
                avgMspt = (double) sum / times.length / 1000000.0;
            }

            double tps = (avgMspt > 50.0) ? (1000.0 / avgMspt) : 20.0;
            return new double[] { Math.min(20.0, tps), avgMspt };
        } catch (Exception e) {
            return new double[] { 0.0, 0.0 };
        } finally {
            try {
                if (connector != null) {
                    connector.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (vm != null) {
                    vm.detach();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // 5. JMX ATTACH API (TPS/MSPT + live JVM CPU + heap RAM)
    public static JvmMetrics getJvmMetrics(long pid) {
        VirtualMachine vm = null;
        JMXConnector connector = null;
        try {
            vm = VirtualMachine.attach(String.valueOf(pid));
            String connectorAddress = vm.startLocalManagementAgent();
            if (connectorAddress == null) {
                Properties props = vm.getAgentProperties();
                connectorAddress = props.getProperty("com.sun.management.jmxremote.localConnectorAddress");
            }
            if (connectorAddress == null || connectorAddress.isBlank()) {
                return new JvmMetrics(0.0, 0.0, 0.0, 0L);
            }
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            connector = JMXConnectorFactory.connect(url);
            MBeanServerConnection mbeanConn = connector.getMBeanServerConnection();

            double cpuUsage = 0.0;
            try {
                ObjectName osBean = new ObjectName("java.lang:type=OperatingSystem");
                Object cpuAttribute = mbeanConn.getAttribute(osBean, "ProcessCpuLoad");
                if (cpuAttribute instanceof Number) {
                    double load = ((Number) cpuAttribute).doubleValue();
                    cpuUsage = load >= 0 ? load * 100.0 : 0.0;
                }
            } catch (Exception ignored) {
                cpuUsage = 0.0;
            }

            long usedRamMb = 0L;
            try {
                ObjectName memBean = new ObjectName("java.lang:type=Memory");
                CompositeData heapUsage = (CompositeData) mbeanConn.getAttribute(memBean, "HeapMemoryUsage");
                Object usedObj = heapUsage == null ? null : heapUsage.get("used");
                if (usedObj instanceof Number) {
                    long usedBytes = ((Number) usedObj).longValue();
                    usedRamMb = usedBytes / (1024L * 1024L);
                }
            } catch (Exception ignored) {
                usedRamMb = 0L;
            }

            ObjectName serverName = new ObjectName("net.minecraft.server:type=Server");
            Object avgTickTimeObj = mbeanConn.getAttribute(serverName, "averageTickTime");

            double avgMspt = 0.0;
            if (avgTickTimeObj instanceof Number) {
                avgMspt = ((Number) avgTickTimeObj).doubleValue();
            } else if (avgTickTimeObj instanceof long[]) {
                long[] times = (long[]) avgTickTimeObj;
                long sum = 0;
                for (long t : times) {
                    sum += t;
                }
                avgMspt = (double) sum / times.length / 1000000.0;
            }

            double tps = (avgMspt > 50.0) ? (1000.0 / avgMspt) : 20.0;
            return new JvmMetrics(Math.min(20.0, tps), avgMspt, Math.max(0.0, cpuUsage), Math.max(0L, usedRamMb));
        } catch (Exception e) {
            return new JvmMetrics(0.0, 0.0, 0.0, 0L);
        } finally {
            try {
                if (connector != null) {
                    connector.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (vm != null) {
                    vm.detach();
                }
            } catch (Exception ignored) {
            }
        }
    }
}

