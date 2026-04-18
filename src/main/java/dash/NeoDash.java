package dash;

import dash.data.AuditDataManager;
import dash.data.BackupManager;
import dash.data.DatabaseManager;
import dash.data.PlayerDataManager;
import dash.data.ScheduledTaskManager;
import dash.process.DockerRunner;
import dash.process.ScreenRunner;
import dash.process.ServerProcessRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Standalone daemon bootstrap entrypoint for NeoDash.
 */
public final class NeoDash {

    private static final Logger LOGGER = Logger.getLogger("NeoDash");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile int webPort = Integer.getInteger("neodash.port", 8080);
    private static volatile Path serverRoot = resolveServerRoot();

    /** Resolves the base server directory: NEODASH_SERVER_PATH env var > neodash.serverDir JVM property > CWD. */
    private static Path resolveServerRoot() {
        String envPath = System.getenv("NEODASH_SERVER_PATH");
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("neodash.serverDir", ".")).toAbsolutePath().normalize();
    }

    private static volatile StatsCollector statsCollector;
    private static volatile DatabaseManager databaseManager;
    private static volatile PlayerDataManager playerDataManager;
    private static volatile BackupManager backupManager;
    private static volatile RegistrationManager registrationManager;
    private static volatile RegistrationApprovalManager registrationApprovalManager;
    private static volatile DiscordWebhookManager discordWebhookManager;
    private static volatile AuditDataManager auditDataManager;
    private static volatile ScheduledTaskManager scheduledTaskManager;
    private static volatile GithubUpdater githubUpdater;
    private static volatile String dashGithubRepo = "Framepersecond/Dash";
    private static volatile String dashGithubToken = "";
    private static volatile String fabricDashGithubRepo = "Framepersecond/FabricDash";
    private static volatile String fabricDashGithubToken = "";

    private final ExecutorService workerPool;
    private final ScheduledExecutorService scheduler;
    private AdminWebServer webServer;

    public NeoDash() {
        this.workerPool = Executors.newCachedThreadPool();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public static void main(String[] args) {
        NeoDash daemon = new NeoDash();
        daemon.start();
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::stop, "neodash-shutdown"));
    }

    public void start() {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }

        WebActionLogger.init(LOGGER);
        registrationManager = new RegistrationManager();
        registrationApprovalManager = new RegistrationApprovalManager();
        databaseManager = new DatabaseManager(Path.of(System.getProperty("neodash_v2.db", "neodash_v2.db")));
        auditDataManager = new AuditDataManager(Path.of(System.getProperty("neodash.audit.db", "audit.db")), LOGGER);
        WebActionLogger.setAuditManager(auditDataManager);

        String runnerType = System.getProperty("neodash.runner", "SCREEN").trim().toUpperCase();
        ServerProcessRunner processRunner = buildRunner(runnerType);
        WebAuth webAuth = new WebAuth(databaseManager);

        discordWebhookManager = new DiscordWebhookManager(LOGGER, databaseManager);
        scheduledTaskManager = new ScheduledTaskManager(
                Path.of(System.getProperty("neodash.tasks.db", "scheduled_tasks.db")), LOGGER);

        webServer = new AdminWebServer(
                LOGGER,
                webPort,
                serverRoot,
                workerPool,
                workerPool,
                scheduler,
                processRunner,
                webAuth,
                databaseManager);
        DashInstallerConfig dashInstallerConfig = loadDashInstallerConfigFromYaml();
        dashGithubRepo = dashInstallerConfig.githubRepo();
        dashGithubToken = dashInstallerConfig.githubToken();
        FabricDashInstallerConfig fabricDashInstallerConfig = loadFabricDashInstallerConfigFromYaml();
        fabricDashGithubRepo = fabricDashInstallerConfig.githubRepo();
        fabricDashGithubToken = fabricDashInstallerConfig.githubToken();
        webServer.start();

        UpdaterConfig updaterConfig = loadUpdaterConfigFromYaml();
        githubUpdater = new GithubUpdater(
                LOGGER,
                workerPool,
                updaterConfig.enabled(),
                updaterConfig.githubRepo(),
                updaterConfig.githubToken(),
                updaterConfig.dashToken());
        String currentVersion = resolveCurrentVersion();

        // Read configured update interval from database (min 20 minutes, default 120)
        long configuredPeriodMinutes = 120L;
        try {
            String storedInterval = databaseManager.getGlobalSetting("update_interval_minutes", "120");
            long parsed = Long.parseLong(storedInterval);
            configuredPeriodMinutes = Math.max(20L, parsed);
        } catch (Exception ignored) {}
        long periodSeconds = TimeUnit.MINUTES.toSeconds(configuredPeriodMinutes);

        long initialDelaySeconds = computeDelayUntilNextEvenHourScanSeconds();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                GithubUpdater updater = githubUpdater;
                if (updater != null) {
                    updater.checkForUpdates(currentVersion);
                    updater.getLatestDashVersion();
                    updater.getLatestFabricDashVersion();
                }
            } catch (Exception ex) {
                LOGGER.fine("[Updater] Update scan failed: " + ex.getMessage());
            }
        }, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);
        LOGGER.info("[Updater] Scheduled update scans every " + configuredPeriodMinutes + " minutes. Next scan in "
                + initialDelaySeconds + " seconds.");

        scheduler.schedule(() -> LOGGER.info("NeoDash daemon started (port=" + webPort + ")"), 0, TimeUnit.SECONDS);
    }

    private String resolveCurrentVersion() {
        String fromProperty = System.getProperty("neodash.version", "").trim();
        if (!fromProperty.isBlank()) {
            return fromProperty;
        }

        Package pkg = NeoDash.class.getPackage();
        if (pkg != null) {
            String implVersion = pkg.getImplementationVersion();
            if (implVersion != null && !implVersion.isBlank()) {
                return implVersion.trim();
            }
        }
        return "0.0.0";
    }

    private long computeDelayUntilNextEvenHourScanSeconds() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime next = now.withMinute(0).withSecond(0).withNano(0);

        if (now.getMinute() != 0 || now.getSecond() != 0) {
            next = next.plusHours(1);
        }
        if ((next.getHour() % 2) != 0) {
            next = next.plusHours(1);
        }
        if (!next.isAfter(now)) {
            next = next.plusHours(2);
        }

        return Math.max(1L, Duration.between(now, next).getSeconds());
    }

    private UpdaterConfig loadUpdaterConfigFromYaml() {
        final boolean defaultEnabled = true;
        final String defaultRepo = "Framepersecond/NeoDash";
        final String defaultToken = "";
        final String defaultDashToken = "";

        Path workingDirConfig = Path.of(System.getProperty("user.dir", "."), "config.yml").toAbsolutePath().normalize();
        Path configuredPath = Path.of(System.getProperty("neodash.config", "config.yml")).toAbsolutePath().normalize();

        Map<String, String> updaterValues = parseUpdaterSection(workingDirConfig);
        if (updaterValues.isEmpty() && !workingDirConfig.equals(configuredPath)) {
            updaterValues = parseUpdaterSection(configuredPath);
        }
        if (updaterValues.isEmpty()) {
            LOGGER.info("[Updater] updater.* not found in runtime config files; using defaults or bundled resource.");
        }

        boolean enabled = parseBooleanOrDefault(updaterValues.get("enabled"), defaultEnabled);
        String githubRepo = nonBlankOrDefault(updaterValues.get("github-repo"), defaultRepo);
        String githubToken = nonBlankOrDefault(updaterValues.get("github-token"), defaultToken);
        String dashToken = nonBlankOrDefault(updaterValues.get("dash-token"), defaultDashToken);
        return new UpdaterConfig(enabled, githubRepo, githubToken, dashToken);
    }

    private Map<String, String> parseUpdaterSection(Path configPath) {
        try {
            if (java.nio.file.Files.isRegularFile(configPath)) {
                LOGGER.info("[Updater] Reading updater settings from " + configPath);
                return parseUpdaterSection(java.nio.file.Files.newInputStream(configPath));
            }
        } catch (Exception ex) {
            LOGGER.warning("[Updater] Failed reading config file " + configPath + ": " + ex.getMessage());
        }

        try (InputStream in = NeoDash.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                LOGGER.info("[Updater] Using bundled config.yml for updater settings.");
                return parseUpdaterSection(in);
            }
        } catch (Exception ex) {
            LOGGER.warning("[Updater] Failed reading bundled config.yml: " + ex.getMessage());
        }

        return Map.of();
    }

    private Map<String, String> parseUpdaterSection(InputStream inputStream) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (inputStream == null) {
            return values;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            boolean inUpdater = false;
            int updaterIndent = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                String raw = line.replace("\t", "  ");
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int indent = leadingSpaces(raw);
                if (!inUpdater) {
                    if ("updater:".equals(trimmed)) {
                        inUpdater = true;
                        updaterIndent = indent;
                    }
                    continue;
                }

                if (indent <= updaterIndent) {
                    break;
                }

                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, colon).trim();
                if (!"enabled".equals(key)
                        && !"github-repo".equals(key)
                        && !"github-token".equals(key)
                        && !"dash-token".equals(key)) {
                    continue;
                }
                String value = stripYamlQuotes(trimmed.substring(colon + 1).trim());
                values.put(key, value);
            }
        }
        return values;
    }

    private int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private String stripYamlQuotes(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        int comment = v.indexOf('#');
        if (comment >= 0) {
            v = v.substring(0, comment).trim();
        }
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1).trim();
        }
        return v.trim();
    }

    private boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String nonBlankOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }


    private record UpdaterConfig(boolean enabled, String githubRepo, String githubToken, String dashToken) {
    }

    private DashInstallerConfig loadDashInstallerConfigFromYaml() {
        final String defaultRepo = "Framepersecond/Dash";
        final String defaultToken = "";

        Path workingDirConfig = Path.of(System.getProperty("user.dir", "."), "config.yml").toAbsolutePath().normalize();
        Path configuredPath = Path.of(System.getProperty("neodash.config", "config.yml")).toAbsolutePath().normalize();

        Map<String, String> dashValues = parseDashSection(workingDirConfig);
        if (dashValues.isEmpty() && !workingDirConfig.equals(configuredPath)) {
            dashValues = parseDashSection(configuredPath);
        }
        if (dashValues.isEmpty()) {
            LOGGER.info("[Installer] dash.* not found in runtime config files; using defaults or bundled resource.");
        }

        String githubRepo = nonBlankOrDefault(firstNonBlank(
                dashValues.get("github-repo"),
                dashValues.get("github_repo"),
                dashValues.get("repo")), defaultRepo);
        if ("Framepersecond/Dash-Updates".equalsIgnoreCase(githubRepo.trim())) {
            githubRepo = defaultRepo;
        }
        String githubToken = nonBlankOrDefault(firstNonBlank(
                dashValues.get("github-token"),
                dashValues.get("github_token"),
                dashValues.get("github-pat"),
                dashValues.get("github_pat"),
                dashValues.get("pat")), defaultToken);
        return new DashInstallerConfig(githubRepo, githubToken);
    }

    private Map<String, String> parseDashSection(Path configPath) {
        try {
            if (java.nio.file.Files.isRegularFile(configPath)) {
                LOGGER.info("[Installer] Reading dash settings from " + configPath);
                return parseDashSection(java.nio.file.Files.newInputStream(configPath));
            }
        } catch (Exception ex) {
            LOGGER.warning("[Installer] Failed reading config file " + configPath + ": " + ex.getMessage());
        }

        try (InputStream in = NeoDash.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                LOGGER.info("[Installer] Using bundled config.yml for dash settings.");
                return parseDashSection(in);
            }
        } catch (Exception ex) {
            LOGGER.warning("[Installer] Failed reading bundled config.yml: " + ex.getMessage());
        }

        return Map.of();
    }

    private Map<String, String> parseDashSection(InputStream inputStream) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (inputStream == null) {
            return values;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            boolean inDash = false;
            int dashIndent = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                String raw = line.replace("\t", "  ");
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int indent = leadingSpaces(raw);
                if (!inDash) {
                    if ("dash:".equals(trimmed)) {
                        inDash = true;
                        dashIndent = indent;
                    }
                    continue;
                }

                if (indent <= dashIndent) {
                    break;
                }

                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, colon).trim();
                if (!"github-repo".equals(key)
                        && !"github_repo".equals(key)
                        && !"repo".equals(key)
                        && !"github-token".equals(key)
                        && !"github_token".equals(key)
                        && !"github-pat".equals(key)
                        && !"github_pat".equals(key)
                        && !"pat".equals(key)) {
                    continue;
                }
                String value = stripYamlQuotes(trimmed.substring(colon + 1).trim());
                values.put(key, value);
            }
        }
        return values;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record DashInstallerConfig(String githubRepo, String githubToken) {
    }

    private FabricDashInstallerConfig loadFabricDashInstallerConfigFromYaml() {
        final String defaultRepo = "Framepersecond/FabricDash";
        final String defaultToken = "";

        Path workingDirConfig = Path.of(System.getProperty("user.dir", "."), "config.yml").toAbsolutePath().normalize();
        Path configuredPath = Path.of(System.getProperty("neodash.config", "config.yml")).toAbsolutePath().normalize();

        Map<String, String> values = parseFabricDashSection(workingDirConfig);
        if (values.isEmpty() && !workingDirConfig.equals(configuredPath)) {
            values = parseFabricDashSection(configuredPath);
        }
        if (values.isEmpty()) {
            LOGGER.info("[Installer] fabricdash.* not found in runtime config files; using defaults or bundled resource.");
        }

        String githubRepo = nonBlankOrDefault(firstNonBlank(
                values.get("github-repo"),
                values.get("github_repo"),
                values.get("repo")), defaultRepo);
        String githubToken = nonBlankOrDefault(firstNonBlank(
                values.get("github-token"),
                values.get("github_token"),
                values.get("github-pat"),
                values.get("github_pat"),
                values.get("pat")), defaultToken);
        return new FabricDashInstallerConfig(githubRepo, githubToken);
    }

    private Map<String, String> parseFabricDashSection(Path configPath) {
        try {
            if (java.nio.file.Files.isRegularFile(configPath)) {
                LOGGER.info("[Installer] Reading fabricdash settings from " + configPath);
                return parseFabricDashSection(java.nio.file.Files.newInputStream(configPath));
            }
        } catch (Exception ex) {
            LOGGER.warning("[Installer] Failed reading config file " + configPath + ": " + ex.getMessage());
        }

        try (InputStream in = NeoDash.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                LOGGER.info("[Installer] Using bundled config.yml for fabricdash settings.");
                return parseFabricDashSection(in);
            }
        } catch (Exception ex) {
            LOGGER.warning("[Installer] Failed reading bundled config.yml: " + ex.getMessage());
        }

        return Map.of();
    }

    private Map<String, String> parseFabricDashSection(InputStream inputStream) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (inputStream == null) {
            return values;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            boolean inSection = false;
            int sectionIndent = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                String raw = line.replace("\t", "  ");
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int indent = leadingSpaces(raw);
                if (!inSection) {
                    if ("fabricdash:".equals(trimmed)) {
                        inSection = true;
                        sectionIndent = indent;
                    }
                    continue;
                }

                if (indent <= sectionIndent) {
                    break;
                }

                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, colon).trim();
                if (!"github-repo".equals(key)
                        && !"github_repo".equals(key)
                        && !"repo".equals(key)
                        && !"github-token".equals(key)
                        && !"github_token".equals(key)
                        && !"github-pat".equals(key)
                        && !"github_pat".equals(key)
                        && !"pat".equals(key)) {
                    continue;
                }
                String value = stripYamlQuotes(trimmed.substring(colon + 1).trim());
                values.put(key, value);
            }
        }
        return values;
    }

    private record FabricDashInstallerConfig(String githubRepo, String githubToken) {
    }

    private ServerProcessRunner buildRunner(String runnerType) {
        try {
            if ("DOCKER".equals(runnerType)) {
                String containerId = System.getProperty("neodash.container", "minecraft");
                LOGGER.info("Using Docker runner for container: " + containerId);
                return new DockerRunner(containerId);
            }

            String serverId = System.getProperty("neodash.serverId", "minecraft");
            String startScript = System.getProperty("neodash.startScript", "./start.sh");
            LOGGER.info("Using Screen runner (serverId=" + serverId + ", startScript=" + startScript + ")");
            return new ScreenRunner(serverId, startScript, serverRoot);
        } catch (Exception ex) {
            LOGGER.severe("Failed to initialize process runner: " + ex.getMessage());
            return null;
        }
    }

    public void stop() {
        if (!RUNNING.compareAndSet(true, false)) {
            return;
        }

        if (webServer != null) {
            webServer.stop();
        }
        if (discordWebhookManager != null) {
            discordWebhookManager.shutdown();
        }
        if (scheduledTaskManager != null) {
            scheduledTaskManager.close();
        }
        if (auditDataManager != null) {
            auditDataManager.close();
        }
        if (backupManager != null) {
            backupManager.stop();
        }
        if (playerDataManager != null) {
            playerDataManager.close();
        }
        if (statsCollector != null) {
            statsCollector.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        githubUpdater = null;

        scheduler.shutdownNow();
        workerPool.shutdownNow();
        LOGGER.info("NeoDash daemon stopped");
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }

    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public static PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public static BackupManager getBackupManager() {
        return backupManager;
    }

    public static RegistrationManager getRegistrationManager() {
        return registrationManager;
    }

    public static RegistrationApprovalManager getRegistrationApprovalManager() {
        return registrationApprovalManager;
    }

    public static DiscordWebhookManager getDiscordWebhookManager() {
        return discordWebhookManager;
    }

    public static AuditDataManager getAuditDataManager() {
        return auditDataManager;
    }

    public static ScheduledTaskManager getScheduledTaskManager() {
        return scheduledTaskManager;
    }

    public static GithubUpdater getGithubUpdater() {
        return githubUpdater;
    }

    public static int getWebPort() {
        return webPort;
    }

    public static Path getServerRoot() {
        return serverRoot;
    }

    public static String getDashGithubRepo() {
        return dashGithubRepo;
    }

    public static String getDashGithubToken() {
        return dashGithubToken;
    }

    public static String getFabricDashGithubRepo() {
        return fabricDashGithubRepo;
    }

    public static String getFabricDashGithubToken() {
        return fabricDashGithubToken;
    }
}
