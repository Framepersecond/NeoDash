package dash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dash.util.YamlConfiguration;
import dash.bridge.BridgeApiClient;
import dash.bridge.MetricsCache;
import dash.bridge.ServerStateCache;
import dash.data.DatabaseManager;
import dash.process.DockerRunner;
import dash.process.ScreenRunner;
import dash.process.ServerProcessRunner;
import dash.web.DashboardPage;
import dash.web.CreateServerPage;
import dash.web.InstallServerPage;
import dash.web.HtmlTemplate;
import dash.web.LoginPage;
import dash.web.PermissionsPage;
import dash.web.PlayersPage;
import dash.web.ServerSettingsPage;
import dash.web.SetupPage;
import dash.web.UpdatesPage;
import dash.web.UsersPage;
import dash.web.AuditLogPage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AdminWebServer {

    private static final long SESSION_TTL_MS = Duration.ofDays(7).toMillis();
    private static final int MAX_CONSOLE_LINES = 1000;
    private static final String PACKWIZ_INSTALLER_URL =
            "https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest/download/packwiz-installer-bootstrap.jar";
    private static final Set<String> SUPPORTED_INSTALL_VERSIONS = Set.of(
            "1.16.5", "1.17.1", "1.18.2", "1.19.4", "1.20", "1.20.1", "1.20.2", "1.20.4", "1.20.6",
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "latest");
    private static final Path SETUP_COMPLETED_FLAG = Path
            .of(System.getProperty("neodash.setupFlag", "setup.completed"))
            .toAbsolutePath()
            .normalize();
    private static final ThreadLocal<String> REQUEST_URI_CONTEXT = new ThreadLocal<>();

    private final Logger logger;
    private final int port;
    private final Path serverRoot;
    private final ExecutorService httpExecutor;
    private final ExecutorService actionExecutor;
    private final ScheduledExecutorService scheduler;
    private final ServerProcessRunner runner;
    private final WebAuth auth;
    private final DatabaseManager databaseManager;
    private final HttpServer server;
    private final Gson gson = new Gson();
    private final BridgeApiClient bridgeApiClient = new BridgeApiClient();
    private final ModpackManager modpackManager = new ModpackManager();
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<Long, ServerProcessRunner> runners = new ConcurrentHashMap<>();
    private final Map<Long, Deque<String>> consoleLinesByServer = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> installProgressByUser = new ConcurrentHashMap<>();
    private final Map<Long, NativeLogState> nativeLogStates = new ConcurrentHashMap<>();
    private static final Pattern MC_VERSION_PATTERN = Pattern.compile("Starting minecraft server version\\s+(.+)$");
    private static final Pattern PLAYER_JOIN_PATTERN = Pattern.compile("\\]:\\s*([a-zA-Z0-9_]{3,16}) joined the game");
    private static final Pattern PLAYER_LEAVE_PATTERN = Pattern.compile("\\]:\\s*([a-zA-Z0-9_]{3,16}) left the game");
    private static final Pattern VERSION_FALLBACK_PATTERN = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    public AdminWebServer(
            Logger logger,
            int port,
            Path serverRoot,
            ExecutorService httpExecutor,
            ExecutorService actionExecutor,
            ScheduledExecutorService scheduler,
            ServerProcessRunner runner,
            WebAuth auth,
            DatabaseManager databaseManager) {
        this.logger = logger;
        this.port = port;
        this.serverRoot = serverRoot == null ? Path.of(".").toAbsolutePath().normalize() : serverRoot.toAbsolutePath().normalize();
        this.httpExecutor = httpExecutor;
        this.actionExecutor = actionExecutor;
        this.scheduler = scheduler;
        this.runner = runner;
        this.auth = auth;
        this.databaseManager = databaseManager;
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create admin HTTP server on port " + port, ex);
        }
        registerRoutes();
        this.server.setExecutor(httpExecutor);
    }

    public void start() {
        server.start();
        logger.info("NeoDash admin web server started on port " + port);
    }

    public void stop() {
        try {
            server.stop(0);
        } catch (Exception ignored) {
        }
    }

    private void registerRoutes() {
        server.createContext("/setup", withRequestLogging(new SetupHandler()));
        server.createContext("/health", withRequestLogging(new HealthHandler()));
        server.createContext("/logout", withSetupGate(new LogoutHandler()));
        server.createContext("/", withSetupGate(new PageHandler()));
        server.createContext("/server", withSetupGate(new PageHandler()));
        server.createContext("/login", withSetupGate(new PageHandler()));
        server.createContext("/action", withSetupGate(new ActionHandler()));

        server.createContext("/api/console", withSetupGate(new ConsoleApiHandler()));
        server.createContext("/api/proxy/console", withSetupGate(new ProxyConsoleApiHandler()));
        server.createContext("/api/server", withSetupGate(new NativeServerApiHandler()));
        server.createContext("/api/servers", withSetupGate(new ServersApiHandler()));
        server.createContext("/api/stats", withSetupGate(new StatsApiHandler()));
        server.createContext("/api/metrics", withSetupGate(new MetricsApiHandler()));
        server.createContext("/api/files/upload", withSetupGate(new FileUploadApiHandler()));
        server.createContext("/api/install/progress", withSetupGate(new InstallProgressApiHandler()));
        server.createContext("/api/restart", withSetupGate(new RestartApiHandler()));

        server.createContext("/server/settings", withSetupGate(new ServerSettingsHandler()));
        server.createContext("/server/create", withSetupGate(new ServerCreateHandler()));
        server.createContext("/server/install", withSetupGate(new ServerInstallHandler()));
        server.createContext("/createServer", withSetupGate(new ServerCreateHandler()));
        server.createContext("/installServer", withSetupGate(new ServerInstallHandler()));
        server.createContext("/server/delete", withSetupGate(new ServerDeleteHandler()));
        server.createContext("/server/action", withSetupGate(new ServerActionHandler()));
        server.createContext("/server/files/edit", withSetupGate(new ServerFileEditHandler()));
        server.createContext("/server/files/delete", withSetupGate(new ServerFileDeleteHandler()));
        server.createContext("/server/properties/save", withSetupGate(new ServerPropertiesSaveHandler()));

        server.createContext("/users/create", withSetupGate(new UsersCreateHandler()));
        server.createContext("/users/update-role", withSetupGate(new UsersUpdateRoleHandler()));
        server.createContext("/users/delete", withSetupGate(new UsersDeleteHandler()));
        server.createContext("/users/assign-server", withSetupGate(new UsersAssignServerHandler()));
        server.createContext("/users/revoke-server", withSetupGate(new UsersRevokeServerHandler()));

        server.createContext("/permissions/create", withSetupGate(new PermissionsCreateHandler()));
        server.createContext("/permissions/update", withSetupGate(new PermissionsUpdateHandler()));
        server.createContext("/permissions/delete", withSetupGate(new PermissionsDeleteHandler()));

        server.createContext("/updates/download", withSetupGate(new UpdatesDownloadHandler()));
        server.createContext("/updates/apply", withSetupGate(new UpdatesApplyHandler()));
        server.createContext("/save", withSetupGate(new FileSaveHandler()));
    }

    private HttpHandler withSetupGate(HttpHandler delegate) {
        return exchange -> {
            setRequestContextAndLog(exchange);
            if (ensureSetupGate(exchange)) {
                REQUEST_URI_CONTEXT.remove();
                return;
            }
            try {
                delegate.handle(exchange);
            } finally {
                REQUEST_URI_CONTEXT.remove();
            }
        };
    }

    private HttpHandler withRequestLogging(HttpHandler delegate) {
        return exchange -> {
            setRequestContextAndLog(exchange);
            try {
                delegate.handle(exchange);
            } finally {
                REQUEST_URI_CONTEXT.remove();
            }
        };
    }

    private void setRequestContextAndLog(HttpExchange exchange) {
        String uri = exchange.getRequestURI() == null ? "" : exchange.getRequestURI().toString();
        REQUEST_URI_CONTEXT.set(uri);
        // Intentionally quiet in normal operation to avoid request log spam.
    }

    private void logQueryKeys(HttpExchange exchange) {
        if (exchange == null || exchange.getRequestURI() == null) {
            return;
        }
        // Intentionally quiet in normal operation to avoid request log spam.
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String part : query.split("&")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (!key.isBlank()) {
                map.put(key, value);
            }
        }
        return map;
    }

    private void sendMissingRequiredId(HttpExchange exchange, String handlerClass, Map<String, String> knownParams)
            throws IOException {
        Map<String, String> params = knownParams == null ? parseQueryParams(exchange.getRequestURI().getRawQuery()) : knownParams;
        logger.warning("[Debug] Handler '" + handlerClass + "' failed with missing ID! URI: "
                + exchange.getRequestURI() + ", Known Params: " + params);
        sendResponseWithStatus(exchange, 400,
                "<html><body style='background:#0b1020;color:#fff'>Missing required id parameter.</body></html>");
    }

    private boolean ensureSetupGate(HttpExchange exchange) throws IOException {
        if (!isSetupRequired()) {
            return false;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/setup".equals(path) || "/health".equals(path)) {
            return false;
        }
        redirect(exchange, "/setup");
        return true;
    }

    private record SessionInfo(long userId, String username, long expiresAt) {
    }

    private record ServerContext(long serverId, DatabaseManager.ServerRecord server, Path rootPath,
            ServerProcessRunner runner) {
    }

    private boolean isSetupRequired() {
        return !isSetupCompleted();
    }

    private boolean isSetupCompleted() {
        return Files.exists(SETUP_COMPLETED_FLAG);
    }

    private boolean hasAnyAdminUser() {
        if (databaseManager == null) {
            return false;
        }
        try {
            for (DatabaseManager.UserRecord user : databaseManager.listUsers()) {
                if ("ADMIN".equalsIgnoreCase(user.globalRole())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void markSetupCompleted() {
        try {
            Path parent = SETUP_COMPLETED_FLAG.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(SETUP_COMPLETED_FLAG, "completed=true\n", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.warning("Failed to persist setup completion flag: " + ex.getMessage());
        }
    }

    private String getClientIp(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean isAuthenticated(HttpExchange exchange) {
        return getSession(exchange).isPresent();
    }

    private Optional<SessionInfo> getSession(HttpExchange exchange) {
        String token = extractSessionToken(exchange);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SessionInfo session = sessions.get(token);
        if (session == null || session.expiresAt <= System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private String extractSessionToken(HttpExchange exchange) {
        if (exchange == null) {
            return null;
        }

        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (header == null) {
                    continue;
                }
                String value = header.trim();
                if (value.regionMatches(true, 0, "Bearer ", 0, 7) && value.length() > 7) {
                    return value.substring(7).trim();
                }
            }
        }

        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String cookieHeader : cookieHeaders) {
            if (cookieHeader == null || cookieHeader.isBlank()) {
                continue;
            }
            String neoSession = extractCookieValue(cookieHeader, "neo_session");
            if (neoSession != null && !neoSession.isBlank()) {
                return neoSession;
            }
            // Backward compatibility for older cookie name.
            String legacySession = extractCookieValue(cookieHeader, "session");
            if (legacySession != null && !legacySession.isBlank()) {
                return legacySession;
            }
        }
        return null;
    }

    private String extractCookieValue(String cookieHeader, String cookieName) {
        if (cookieHeader == null || cookieName == null || cookieName.isBlank()) {
            return null;
        }
        String prefix = cookieName + "=";
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String item = part == null ? "" : part.trim();
            if (item.startsWith(prefix)) {
                return item.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private long getSessionUserId(HttpExchange exchange) {
        return getSession(exchange).map(s -> s.userId).orElse(-1L);
    }

    private String getSessionUser(HttpExchange exchange) {
        return getSession(exchange).map(s -> s.username).orElse(null);
    }

    private void setSession(HttpExchange exchange, long userId, String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new SessionInfo(userId, username, System.currentTimeMillis() + SESSION_TTL_MS));
        exchange.getResponseHeaders().add("Set-Cookie",
                "neo_session=" + token + "; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax");
    }

    private void clearSession(HttpExchange exchange) {
        String token = extractSessionToken(exchange);
        if (token != null && !token.isBlank()) {
            sessions.remove(token);
        }
        exchange.getResponseHeaders().add("Set-Cookie", "neo_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        exchange.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private boolean ensureAuthenticated(HttpExchange exchange, boolean jsonResponse) throws IOException {
        if (isAuthenticated(exchange)) {
            return true;
        }
        if (jsonResponse) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Forbidden\"}");
        } else {
            redirect(exchange, "/login");
        }
        return false;
    }

    private Optional<ServerContext> resolveServerContext(long serverId) {
        if (serverId <= 0 || databaseManager == null) {
            return Optional.empty();
        }
        Optional<DatabaseManager.ServerRecord> server = databaseManager.getServerById(serverId);
        if (server.isEmpty()) {
            return Optional.empty();
        }

        Path rootPath = Path.of(server.get().pathToDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
        } catch (IOException ignored) {
        }

        ServerProcessRunner runner = getRunnerForServer(server.get());
        return Optional.of(new ServerContext(serverId, server.get(), rootPath, runner));
    }

    private ServerProcessRunner getRunnerForServer(DatabaseManager.ServerRecord server) {
        return runners.computeIfAbsent(server.id(), id -> {
            String type = server.runnerType() == null ? "SCREEN" : server.runnerType().trim().toUpperCase(Locale.ROOT);
            if ("DOCKER".equals(type)) {
                String containerId = server.startCommand() == null || server.startCommand().isBlank()
                        ? server.name()
                        : server.startCommand().trim();
                return new DockerRunner(containerId);
            }
            return new ScreenRunner(buildScreenSessionName(server.id(), server.name()), server.startCommand(),
                    Path.of(server.pathToDir()).toAbsolutePath().normalize());
        });
    }

    private String buildScreenSessionName(long serverId, String serverName) {
        String safeName = trimToEmpty(serverName)
                .replace(' ', '_')
                .replaceAll("[^A-Za-z0-9_-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safeName.isBlank()) {
            safeName = "Server";
        }
        return serverId + "." + safeName;
    }

    private boolean ensureServerAccess(HttpExchange exchange, long serverId, boolean jsonResponse) throws IOException {
        if (!ensureAuthenticated(exchange, jsonResponse)) {
            return false;
        }
        long userId = getSessionUserId(exchange);
        boolean allowed = databaseManager != null && databaseManager.hasPermissionForServer(userId, serverId);
        if (allowed) {
            return true;
        }
        if (jsonResponse) {
            sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Access denied\"}");
        } else {
            sendResponseWithStatus(exchange, 403,
                    "<html><body style='background:#0b1020;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403</h1><p>Access denied for server #"
                            + serverId + "</p><a href='/' style='color:#93c5fd'>Back</a></div></body></html>");
        }
        return false;
    }

    private boolean ensureServerCapability(HttpExchange exchange, long serverId,
            boolean requireStartStop, boolean requireConsole, boolean requireFiles, boolean jsonResponse)
            throws IOException {
        if (!ensureAuthenticated(exchange, jsonResponse)) {
            return false;
        }
        long userId = getSessionUserId(exchange);
        boolean allowed = databaseManager != null
                && databaseManager.hasPermissionForServer(userId, serverId, requireStartStop, requireConsole,
                        requireFiles);
        if (allowed) {
            return true;
        }
        if (jsonResponse) {
            sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Access denied\"}");
        } else {
            sendResponseWithStatus(exchange, 403,
                    "<html><body style='background:#0b1020;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403</h1><p>Missing permission for server #"
                            + serverId + "</p></div></body></html>");
        }
        return false;
    }

    private long parseServerId(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private long serverIdFromQuery(HttpExchange exchange) {
        return parseServerId(getQueryParam(exchange.getRequestURI().getQuery(), "id"));
    }

    private long serverIdFromParams(Map<String, String> params) {
        String raw = params.get("id");
        if (raw == null) {
            raw = params.get("serverId");
        }
        if (raw == null) {
            raw = params.get("server_id");
        }
        return parseServerId(raw);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveAssignableRole(String requestedRole) {
        String normalized = trimToEmpty(requestedRole).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "USER";
        }

        for (String available : availableAssignableRoles()) {
            if (available.equalsIgnoreCase(normalized)) {
                return available;
            }
        }
        return normalized;
    }

    private boolean isKnownAssignableRole(String role) {
        String normalized = trimToEmpty(role).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        return availableAssignableRoles().contains(normalized);
    }

    private Set<String> availableAssignableRoles() {
        Set<String> roles = new java.util.LinkedHashSet<>();
        if (databaseManager != null) {
            Map<String, Integer> fromDb = databaseManager.getRoleValuesMap();
            if (fromDb != null) {
                for (String roleName : fromDb.keySet()) {
                    String normalized = trimToEmpty(roleName).toUpperCase(Locale.ROOT);
                    if (!normalized.isBlank()) {
                        roles.add(normalized);
                    }
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add("ADMIN");
            roles.add("USER");
        }
        return roles;
    }

    private String normalizeServerRole(String role) {
        if (role == null || role.isBlank()) {
            return "VIEWER";
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!"VIEWER".equals(normalized) && !"OPERATOR".equals(normalized) && !"ADMIN".equals(normalized)) {
            return "VIEWER";
        }
        return normalized;
    }

    private String sanitizeServerDirectoryParam(DatabaseManager.ServerRecord server, String rawDir) {
        if (server == null) {
            return null;
        }

        String normalizedDir = rawDir == null ? "" : rawDir.trim().replace('\\', '/');
        if (normalizedDir.startsWith("/")) {
            normalizedDir = normalizedDir.substring(1);
        }
        if (normalizedDir.isBlank()) {
            return "";
        }

        try {
            Path rootPath = Path.of(server.pathToDir()).toAbsolutePath().normalize();
            Path resolved = resolveSafePath(rootPath, normalizedDir);
            if (resolved == null || !Files.isDirectory(resolved)) {
                return null;
            }
            return rootPath.relativize(resolved).toString().replace('\\', '/');
        } catch (Exception ex) {
            return null;
        }
    }

    private final class SetupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isSetupCompleted()) {
                redirect(exchange, "/login");
                return;
            }

            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
                return;
            }
            sendResponseWithStatus(exchange, 405, "Method Not Allowed");
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            int step = parseStep(getQueryParam(exchange.getRequestURI().getQuery(), "step"));
            String message = getQueryParam(exchange.getRequestURI().getQuery(), "message");
            sendResponse(exchange, SetupPage.render(step, message));
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            if (databaseManager == null) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Database unavailable.</body></html>");
                return;
            }

            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(requestBody);

            int step = parseStep(params.get("step"));
            String intent = trimToEmpty(params.get("intent")).toLowerCase(Locale.ROOT);

            switch (step) {
                case 1 -> handleSetupStepAdmin(exchange, params, intent);
                case 2 -> handleSetupStepServer(exchange, params, intent);
                default -> redirect(exchange, "/setup?step=1");
            }
        }

        private void handleSetupStepAdmin(HttpExchange exchange, Map<String, String> params, String intent)
                throws IOException {
            if (!"create_admin".equals(intent)) {
                redirect(exchange, "/setup?step=1");
                return;
            }

            String username = trimToEmpty(params.get("admin_username"));
            String password = trimToEmpty(params.get("admin_password"));
            String passwordConfirm = trimToEmpty(params.get("admin_password_confirm"));

            if (username.isBlank() || password.isBlank()) {
                redirectSetupMessage(exchange, 1, "Username and password are required.");
                return;
            }
            if (password.length() < 6) {
                redirectSetupMessage(exchange, 1, "Password must be at least 6 characters.");
                return;
            }
            if (!password.equals(passwordConfirm)) {
                redirectSetupMessage(exchange, 1, "Passwords do not match.");
                return;
            }
            if (databaseManager.getUserByUsername(username).isPresent()) {
                redirectSetupMessage(exchange, 1, "Username already exists.");
                return;
            }

            long adminId;
            try {
                adminId = databaseManager.createUser(username, WebAuth.hashPassword(password), "ADMIN");
            } catch (Exception ex) {
                redirectSetupMessage(exchange, 1, "Failed to create admin account.");
                return;
            }

            setSession(exchange, adminId, username);
            WebActionLogger.log("SETUP_ADMIN_CREATED", "username=" + username + " from " + getClientIp(exchange));
            redirectSetupMessage(exchange, 2, "Main-Admin account created.");
        }

        private void handleSetupStepServer(HttpExchange exchange, Map<String, String> params, String intent)
                throws IOException {
            if ("finish".equals(intent) || "skip".equals(intent)) {
                markSetupCompleted();
                redirect(exchange, "/login");
                return;
            }
            if (!"create_server".equals(intent)) {
                redirect(exchange, "/setup?step=2");
                return;
            }

            String name = trimToEmpty(params.get("name"));
            String pathToDir = trimToEmpty(params.get("path_to_dir"));
            String startCommand = trimToEmpty(params.get("start_command"));
            String bridgeHost = trimToEmpty(params.get("bridge_host"));
            if (bridgeHost.isBlank()) {
                bridgeHost = "127.0.0.1";
            }
            String bridgeSecret = trimToEmpty(params.get("bridge_secret"));
            String portRaw = trimToEmpty(params.get("port"));
            String bridgePortRaw = trimToEmpty(params.get("bridge_port"));
            int port;
            try {
                port = portRaw.isBlank() ? 25565 : Integer.parseInt(portRaw);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException("port-range");
                }
            } catch (NumberFormatException ex) {
                redirectSetupMessage(exchange, 2, "Server port must be between 1 and 65535.");
                return;
            }

            Integer bridgeApiPort;
            try {
                if (bridgePortRaw.isBlank()) {
                    bridgeApiPort = 8081;
                } else {
                    int parsedBridgePort = Integer.parseInt(bridgePortRaw);
                    if (parsedBridgePort < 1 || parsedBridgePort > 65535) {
                        throw new NumberFormatException("bridge-port-range");
                    }
                    bridgeApiPort = parsedBridgePort;
                }
            } catch (NumberFormatException ex) {
                redirectSetupMessage(exchange, 2, "Bridge port must be between 1 and 65535.");
                return;
            }

            if (name.isBlank() || pathToDir.isBlank() || startCommand.isBlank()) {
                redirectSetupMessage(exchange, 2, "Name, path and start command are required.");
                return;
            }

            try {
                long userId = getSessionUserId(exchange);
                logParsedBridgeTarget(exchange, userId, bridgeHost, bridgeApiPort);

                long serverId = databaseManager.createServer(
                        name,
                        pathToDir,
                        "SCREEN",
                        startCommand,
                        port,
                        bridgeApiPort,
                        bridgeSecret.isBlank() ? null : bridgeSecret,
                        bridgeHost,
                        8080);

                for (DatabaseManager.UserRecord user : databaseManager.listUsers()) {
                    if ("ADMIN".equalsIgnoreCase(user.globalRole())) {
                        databaseManager.upsertServerPermission(user.id(), serverId, true, true, true);
                    }
                }
                WebActionLogger.log("SETUP_SERVER_CREATED",
                        "serverId=" + serverId + " name=" + name + " from " + getClientIp(exchange));
            } catch (Exception ex) {
                redirectSetupMessage(exchange, 2, "Failed to create server.");
                return;
            }

            markSetupCompleted();
            redirect(exchange, "/login");
        }

        private int parseStep(String rawStep) {
            try {
                int step = Integer.parseInt(trimToEmpty(rawStep));
                return Math.max(1, Math.min(step, 2));
            } catch (NumberFormatException ex) {
                return 1;
            }
        }

        private String trimToEmpty(String value) {
            return value == null ? "" : value.trim();
        }

        private void redirectSetupMessage(HttpExchange exchange, int step, String message) throws IOException {
            redirect(exchange, "/setup?step=" + step + "&message=" + encodeForQuery(message));
        }
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(exchange, "{\"status\":\"ok\"}");
        }
    }

    private final class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            cleanupKnownBridgeSessions(getSessionUserId(exchange));
            clearSession(exchange);
            WebActionLogger.logLogout(getClientIp(exchange));
            redirect(exchange, "/login");
        }
    }

    private final class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getQuery();

                if ("/login".equals(path)) {
                    if (isAuthenticated(exchange)) {
                        redirect(exchange, "/");
                    } else {
                        sendResponse(exchange, renderLoginPage());
                    }
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, renderLoginPage());
                    return;
                }

                if ("/".equals(path)) {
                    long userId = getSessionUserId(exchange);
                    warmServerStatesForUser(userId);
                    String message = getQueryParam(query, "message");
                    sendResponse(exchange,
                            DashboardPage.renderForUser(userId, getSessionUser(exchange), message));
                    return;
                }

                if ("/createServer".equals(path)) {
                    String message = getQueryParam(query, "message");
                    String error = getQueryParam(query, "error");
                    sendResponse(exchange, CreateServerPage.render(getSessionUserId(exchange), message, error));
                    return;
                }

                if ("/installServer".equals(path)) {
                    String message = getQueryParam(query, "message");
                    String error = getQueryParam(query, "error");
                    String warning = getQueryParam(query, "warning");
                    sendResponse(exchange, InstallServerPage.render(getSessionUserId(exchange), message, error, warning));
                    return;
                }

                if ("/users".equals(path)) {
                    String actorUsername = getSessionUser(exchange);
                    boolean actorIsMainAdmin = auth != null && auth.isMainAdmin(actorUsername);
                    String generatedCode = getQueryParam(query, "code");
                    String message = getQueryParam(query, "message");
                    List<RegistrationApprovalManager.PendingRegistration> pendingRegistrations =
                            NeoDash.getRegistrationApprovalManager() == null
                                    ? List.of()
                                    : NeoDash.getRegistrationApprovalManager().listPending();

                    Map<String, List<DatabaseManager.UserServerAssignment>> assignmentsByUser = new HashMap<>();
                    for (DatabaseManager.UserRecord user : databaseManager.listUsers()) {
                        assignmentsByUser.put(user.username(), databaseManager.getServerAssignmentsForUser(user.id()));
                    }

                    sendResponse(exchange, UsersPage.render(
                            getSessionUserId(exchange),
                            auth == null ? Map.of() : auth.getUsers(),
                            auth == null ? List.of("ADMIN", "USER") : auth.getRoleNames(),
                            auth == null ? Map.of("ADMIN", 1000, "USER", 100) : auth.getRoleValues(),
                            actorUsername,
                            actorIsMainAdmin,
                            generatedCode,
                            message,
                            pendingRegistrations,
                            databaseManager.listServers(),
                            assignmentsByUser));
                    return;
                }

                if ("/permissions".equals(path)) {
                    String selectedRole = getQueryParam(query, "role");
                    String message = getQueryParam(query, "message");
                    String actorUsername = getSessionUser(exchange);
                    boolean actorIsMainAdmin = auth != null && auth.isMainAdmin(actorUsername);
                    int actorRoleValue = auth == null ? 0 : auth.getActorRoleValue(actorUsername);

                    sendResponse(exchange, PermissionsPage.render(
                            getSessionUserId(exchange),
                            auth == null ? Map.of("ADMIN", List.of("*"), "USER", List.of()) : rolesWithPermissionsAsLists(),
                            auth == null ? Map.of("ADMIN", 1000, "USER", 100) : auth.getRoleValues(),
                            selectedRole,
                            message,
                            actorIsMainAdmin,
                            actorRoleValue));
                    return;
                }

                if ("/audit".equals(path)) {
                    String search = getQueryParam(query, "q");
                    sendResponse(exchange, AuditLogPage.render(getSessionUserId(exchange), search));
                    return;
                }

                if ("/updates".equals(path)) {
                    long currentUserId = getSessionUserId(exchange);
                    String message = getQueryParam(query, "message");
                    String error = getQueryParam(query, "error");
                    List<DatabaseManager.ServerRecord> servers = databaseManager == null
                            ? List.of()
                            : databaseManager.getServersForUser(currentUserId);
                    sendResponse(exchange, UpdatesPage.render(
                            currentUserId,
                            resolveCurrentNeoDashVersion(),
                            servers,
                            message,
                            error));
                    return;
                }

                if (!isServerContextPagePath(path)) {
                    sendResponse(exchange, DashboardPage.renderForUser(getSessionUserId(exchange), getSessionUser(exchange)));
                    return;
                }

                long serverId = serverIdFromQuery(exchange);
                if (serverId <= 0) {
                    sendMissingRequiredId(exchange, "PageHandler", null);
                    return;
                }
                if (!ensureServerAccess(exchange, serverId, false)) {
                    return;
                }

                String html;
                if ("/server".equals(path)) {
                    Optional<DatabaseManager.ServerRecord> server = databaseManager.getServerById(serverId);
                    if (server.isEmpty()) {
                        sendResponseWithStatus(exchange, 404,
                                "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                        return;
                    }
                    DatabaseManager.ServerRecord serverRecord = server.get();
                    String selectedDir = sanitizeServerDirectoryParam(serverRecord,
                            getQueryParam(exchange.getRequestURI().getQuery(), "dir"));
                    if (selectedDir == null) {
                        sendResponseWithStatus(exchange, 403,
                                "<html><body style='background:#0b1020;color:#fff'>Invalid directory path.</body></html>");
                        return;
                    }
                    ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(serverId, serverRecord);
                    String forceNativeRaw = getQueryParam(query, "native");
                    boolean forceNative = "1".equals(forceNativeRaw)
                            || "true".equalsIgnoreCase(forceNativeRaw);
                    if (!forceNative && serverRecord.usePluginInterface() && snapshot.online()) {
                        String ssoUrl = buildSsoRedirectUrl(exchange, serverRecord, getSessionUser(exchange));
                        if (ssoUrl != null) {
                            redirect(exchange, ssoUrl);
                            return;
                        }
                    }
                    long currentUserId = getSessionUserId(exchange);
                    boolean canStart = databaseManager.hasOfflineStartPermission(currentUserId, serverId);
                    boolean canFiles = databaseManager.hasOfflineFilesPermission(currentUserId, serverId);
                    boolean canProperties = databaseManager.hasOfflinePropertiesPermission(currentUserId, serverId);
                    boolean processRunning = isLocalServerProcessRunning(serverRecord);
                    html = DashboardPage.renderServer(serverId, serverRecord, selectedDir, canStart, canFiles,
                            canProperties, processRunning);
                } else if ("/files".equals(path)) {
                    if (!ensureServerCapability(exchange, serverId, false, false, true, false)) {
                        return;
                    }
                    String rel = getQueryParam(query, "path");
                    html = renderFilesPage(serverId, rel == null ? "" : rel);
                } else if ("/files/edit".equals(path)) {
                    if (!ensureServerCapability(exchange, serverId, false, false, true, false)) {
                        return;
                    }
                    String rel = getQueryParam(query, "path");
                    if (rel == null || rel.isBlank()) {
                        redirect(exchange, "/files?id=" + serverId);
                        return;
                    }
                    html = renderFileEditor(serverId, rel);
                } else if ("/settings".equals(path)) {
                    html = renderBridgePlaceholder("Settings", serverId);
                } else if ("/players".equals(path)) {
                    Optional<DatabaseManager.ServerRecord> server = databaseManager.getServerById(serverId);
                    if (server.isEmpty()) {
                        sendResponseWithStatus(exchange, 404,
                                "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                        return;
                    }
                    html = PlayersPage.render(serverId, server.get());
                } else if ("/plugins".equals(path)) {
                    html = renderBridgePlaceholder("Plugins", serverId);
                } else {
                    html = DashboardPage.renderForUser(getSessionUserId(exchange), getSessionUser(exchange));
                }
                sendResponse(exchange, html);
            } catch (Exception e) {
                e.printStackTrace();
                String errorHtml = "<h1>Dashboard Render Error</h1><pre>" + String.valueOf(e.getMessage()) + "</pre>";
                sendResponseWithStatus(exchange, 500, errorHtml);
            }
        }
    }

    private boolean isServerContextPagePath(String path) {
        // Pages listed here require a ?id= server parameter.
        // /installServer, /createServer, and other top-level pages must NOT be listed here.
        return "/server".equals(path)
                || "/files".equals(path)
                || "/files/edit".equals(path)
                || "/settings".equals(path)
                || "/players".equals(path)
                || "/plugins".equals(path);
    }

    private final class ConsoleApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long serverId = serverIdFromQuery(exchange);
            if (serverId <= 0) {
                sendResponseWithStatus(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, true, false, true)) {
                return;
            }
            startLogPumpForServer(serverId);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            sendResponse(exchange, getConsoleOutput(serverId));
        }
    }

    private final class ProxyConsoleApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
                return;
            }
            sendResponseWithStatus(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            long serverId = serverIdFromQuery(exchange);
            if (serverId <= 0) {
                sendProxyJson(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, true, false, true)) {
                return;
            }

            Optional<DatabaseManager.ServerRecord> server = databaseManager.getServerById(serverId);
            if (server.isEmpty()) {
                sendProxyJson(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }

            try {
                String json = bridgeApiClient.fetchConsoleLogs(server.get()).join();
                sendProxyJson(exchange, 200, normalizeConsoleArrayPayload(json));
            } catch (Exception ex) {
                sendProxyJson(exchange, 200, "[]");
            }
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            long serverId = serverIdFromParams(params);
            if (serverId <= 0) {
                serverId = serverIdFromQuery(exchange);
            }
            if (serverId <= 0) {
                sendProxyJson(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, true, false, true)) {
                return;
            }

            Optional<DatabaseManager.ServerRecord> server = databaseManager.getServerById(serverId);
            if (server.isEmpty()) {
                sendProxyJson(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }

            String command = params.getOrDefault("command", "").trim();
            if (command.isBlank()) {
                sendProxyJson(exchange, 400, "{\"success\":false,\"error\":\"Missing command\"}");
                return;
            }

            boolean ok;
            try {
                ok = bridgeApiClient.sendConsoleCommand(server.get(), command).join();
            } catch (Exception ex) {
                ok = false;
            }

            if (ok) {
                sendProxyJson(exchange, 200, "{\"success\":true}");
            } else {
                sendProxyJson(exchange, 502, "{\"success\":false,\"error\":\"Bridge command failed\"}");
            }
        }

        private void sendProxyJson(HttpExchange exchange, int status, String json) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponseWithStatus(exchange, status, json == null ? "[]" : json);
        }

        private String normalizeConsoleArrayPayload(String json) {
            if (json == null) {
                return "[]";
            }
            String trimmed = json.trim();
            if (trimmed.isEmpty()) {
                return "[]";
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return trimmed;
            }
            return "[]";
        }
    }

    private final class NativeServerApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI() == null ? "" : exchange.getRequestURI().getPath();
            // Expected path: /api/server/{id}/...
            String prefix = "/api/server/";
            if (path == null || !path.startsWith(prefix)) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Not Found\"}");
                return;
            }

            String remainder = path.substring(prefix.length());
            String[] segments = remainder.split("/");
            if (segments.length < 2) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid server API path\"}");
                return;
            }

            long serverId = parseServerId(segments[0]);
            if (serverId <= 0) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }

            if (segments.length >= 3 && "console".equals(segments[1]) && "log".equals(segments[2])) {
                handleNativeConsoleLog(exchange, serverId);
                return;
            }
            if (segments.length >= 3 && "console".equals(segments[1]) && "command".equals(segments[2])) {
                handleNativeConsoleCommand(exchange, serverId);
                return;
            }
            if (segments.length >= 2 && "status".equals(segments[1])) {
                handleNativeStatus(exchange, serverId);
                return;
            }

            sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Unknown native server API route\"}");
        }

        private void handleNativeConsoleLog(HttpExchange exchange, long serverId) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, true, false, true)) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }

            Path root = contextOpt.get().rootPath();
            List<String> lines = readNativeConsoleLines(root, 100);
            StringBuilder json = new StringBuilder();
            json.append("{\"success\":true,\"lines\":[");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(escapeJson(lines.get(i))).append('"');
            }
            json.append("]}");
            sendJsonResponse(exchange, 200, json.toString());
        }

        private void handleNativeConsoleCommand(HttpExchange exchange, long serverId) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, true, false, true)) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String command = trimToEmpty(params.get("command"));
            if (command.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing command\"}");
                return;
            }

            String sanitized = command.replace("\r", " ").replace("\n", " ").trim();
            String screenSessionName = buildScreenSessionName(serverId, contextOpt.get().server().name());
            ProcessBuilder pb = new ProcessBuilder(
                    "screen",
                    "-S",
                    screenSessionName,
                    "-p",
                    "0",
                    "-X",
                    "stuff",
                    sanitized + "\r");
            try {
                Process process = pb.start();
                int exit = process.waitFor();
                if (exit == 0) {
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                    return;
                }
                sendJsonResponse(exchange, 502,
                        "{\"success\":false,\"error\":\"Failed to send command to screen session\"}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                sendJsonResponse(exchange, 500,
                        "{\"success\":false,\"error\":\"Command dispatch interrupted\"}");
            } catch (IOException ex) {
                sendJsonResponse(exchange, 500,
                        "{\"success\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        private void handleNativeStatus(HttpExchange exchange, long serverId) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }
            if (!ensureServerAccess(exchange, serverId, true)) {
                return;
            }

            Optional<DatabaseManager.ServerRecord> serverOpt = databaseManager.getServerById(serverId);
            if (serverOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }

            DatabaseManager.ServerRecord server = serverOpt.get();
            NativeStatusData statusData = collectNativeStatusData(server.id(), server);
            boolean online = statusData.online();
            String status = online ? "ONLINE" : "OFFLINE";

            String json = String.format(Locale.ROOT,
                    "{\"success\":true,\"status\":\"%s\",\"online\":%s,\"pid\":%d,\"tps\":%.2f,\"cpu\":%.2f,\"ram\":%d,\"maxRamObserved\":%d,\"version\":\"%s\",\"playerCount\":%d,\"chunks\":%d,\"players\":%s}",
                    status,
                    online,
                    statusData.pid(),
                    statusData.tps(),
                    Math.max(0.0d, statusData.cpu()),
                    Math.max(0L, statusData.ramMb()),
                    Math.max(0L, statusData.maxRamObserved()),
                    escapeJson(statusData.version()),
                    statusData.playerCount(),
                    statusData.chunks(),
                    toJsonArray(statusData.players()));
            sendJsonResponse(exchange, 200, json);
        }
    }

    private final class FileUploadApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }

            String query = exchange.getRequestURI() == null ? "" : exchange.getRequestURI().getQuery();
            long serverId = parseServerId(getQueryParam(query, "serverId"));
            if (serverId <= 0) {
                serverId = parseServerId(getQueryParam(query, "id"));
            }
            if (serverId <= 0) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, false, true, true)) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }

            String pathParam = getQueryParam(query, "path");
            if (pathParam == null || pathParam.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing path\"}");
                return;
            }

            Path serverDir = contextOpt.get().rootPath();
            Path targetPath = resolveSafePath(serverDir, pathParam);
            if (targetPath == null) {
                sendJsonResponse(exchange, 403, "{\"success\":false,\"error\":\"Forbidden upload path\"}");
                return;
            }

            try {
                Path parent = targetPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream os = Files.newOutputStream(
                        targetPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    try (InputStream is = exchange.getRequestBody()) {
                        is.transferTo(os);
                    }
                }
            } catch (IOException ex) {
                sendJsonResponse(exchange, 500,
                        "{\"success\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            sendResponseWithStatus(exchange, 200, "Uploaded");
        }
    }

    private final class ServerSettingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
                return;
            }
            sendResponseWithStatus(exchange, 405, "Method Not Allowed");
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            try {
                long serverId = serverIdFromQuery(exchange);
                if (serverId <= 0) {
                    sendMissingRequiredId(exchange, "ServerSettingsHandler", null);
                    return;
                }
                if (!ensureServerAccess(exchange, serverId, false)) {
                    return;
                }
                if (!ensureServerSettingsPermission(exchange, serverId, false)) {
                    return;
                }

                Optional<DatabaseManager.ServerRecord> server = databaseManager.getServerById(serverId);
                if (server.isEmpty()) {
                    sendResponseWithStatus(exchange, 404,
                            "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                    return;
                }

                try {
                    DatabaseManager.ServerRecord record = server.get();
                    String pathToDir = record.pathToDir();
                    if (pathToDir != null && !pathToDir.isBlank()) {
                        Path serverDir = Path.of(pathToDir).toAbsolutePath().normalize();
                        Files.createDirectories(serverDir);
                        Path propertiesPath = serverDir.resolve("server.properties").toAbsolutePath().normalize();
                        Path parent = propertiesPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        if (!Files.exists(propertiesPath)) {
                            Files.writeString(propertiesPath, "server-port=" + record.port() + "\n",
                                    StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception ignored) {
                    ignored.printStackTrace();
                    // Settings page remains available even if server.properties cannot be prepared yet.
                }

                String successRaw = getQueryParam(exchange.getRequestURI().getQuery(), "success");
                boolean success = "true".equalsIgnoreCase(successRaw) || "1".equals(successRaw);
                String error = getQueryParam(exchange.getRequestURI().getQuery(), "error");

                List<DatabaseManager.ServerUserPermissionView> userPermissions = databaseManager
                        .listServerUserPermissions(serverId);
                sendResponse(exchange,
                        ServerSettingsPage.render(getSessionUserId(exchange), serverId, server.get(), success,
                                error, userPermissions));
            } catch (Exception ex) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Error loading settings: "
                                + escapeHtml(ex.getMessage()) + "</body></html>");
            }
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            long serverId = serverIdFromParams(params);
            if (serverId <= 0) {
                serverId = serverIdFromQuery(exchange);
            }
            if (serverId <= 0) {
                String referer = exchange.getRequestHeaders().getFirst("Referer");
                if (referer != null && referer.contains("id=")) {
                    String after = referer.substring(referer.indexOf("id=") + 3);
                    String idRaw = after.contains("&") ? after.substring(0, after.indexOf('&')) : after;
                    serverId = parseServerId(idRaw);
                }
            }
            if (serverId <= 0) {
                sendMissingRequiredId(exchange, "ServerSettingsHandler", params);
                return;
            }
            if (!ensureServerAccess(exchange, serverId, false)) {
                return;
            }
            if (!ensureServerSettingsPermission(exchange, serverId, false)) {
                return;
            }

            Optional<DatabaseManager.ServerRecord> existing = databaseManager.getServerById(serverId);
            if (existing.isEmpty()) {
                sendResponseWithStatus(exchange, 404,
                        "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                return;
            }

            String name = trimToEmpty(params.get("name"));
            String ipAddress = trimToEmpty(params.get("ip_address"));
            if (ipAddress.isBlank()) {
                ipAddress = "127.0.0.1";
            }
            String pathToDir = trimToEmpty(params.get("path_to_dir"));
            String runnerType = trimToEmpty(params.get("runner_type")).toUpperCase(Locale.ROOT);
            String startCommand = trimToEmpty(params.get("start_command"));
            String portRaw = trimToEmpty(params.get("port"));
            String dashPortRaw = trimToEmpty(params.get("dash_port"));
            String bridgeApiPortRaw = trimToEmpty(params.get("bridge_api_port"));
            String bridgeSecretRaw = params.get("bridge_secret");
            boolean usePluginInterface = "1".equals(params.get("use_plugin_interface"))
                    || "true".equalsIgnoreCase(trimToEmpty(params.get("use_plugin_interface")))
                    || "on".equalsIgnoreCase(trimToEmpty(params.get("use_plugin_interface")));

            if (name.isBlank() || pathToDir.isBlank() || startCommand.isBlank()) {
                redirectSettingsError(exchange, serverId, "Name, path, and start command are required.");
                return;
            }

            if ("127.0.0.1".equalsIgnoreCase(ipAddress) || "localhost".equalsIgnoreCase(ipAddress)) {
                String pathError = validateServerDirectory(pathToDir);
                if (pathError != null) {
                    redirectSettingsError(exchange, serverId, pathError);
                    return;
                }
            }

            if (!"DOCKER".equals(runnerType) && !"SCREEN".equals(runnerType)) {
                redirectSettingsError(exchange, serverId, "Runner type must be DOCKER or SCREEN.");
                return;
            }

            int port;
            try {
                port = parsePort(portRaw, "Server Port", true);
            } catch (IllegalArgumentException ex) {
                redirectSettingsError(exchange, serverId, ex.getMessage());
                return;
            }

            int bridgeApiPort;
            try {
                bridgeApiPort = parsePort(bridgeApiPortRaw, "Bridge API Port", false);
            } catch (IllegalArgumentException ex) {
                redirectSettingsError(exchange, serverId, ex.getMessage());
                return;
            }

            int dashPort;
            try {
                dashPort = parsePort(dashPortRaw, "Dash Plugin Port", false);
            } catch (IllegalArgumentException ex) {
                redirectSettingsError(exchange, serverId, ex.getMessage());
                return;
            }
            if (dashPort <= 0) {
                dashPort = existing.get().dashPort() == null ? 8080 : existing.get().dashPort();
            }

            String bridgeSecret = bridgeSecretRaw == null ? "" : bridgeSecretRaw.trim();
            if (bridgeSecret.isEmpty()) {
                bridgeSecret = existing.get().bridgeSecret();
            }

            databaseManager.updateServer(serverId, name, pathToDir, runnerType, startCommand, port, dashPort,
                    bridgeApiPort, bridgeSecret, ipAddress, usePluginInterface);

            if (isAdminSession(exchange)) {
                long mainAdminId = databaseManager.getMainAdminId().orElse(-1L);
                for (DatabaseManager.UserRecord user : databaseManager.listUsers()) {
                    long targetUserId = user.id();
                    boolean isMainAdmin = targetUserId == mainAdminId;

                    boolean requestedStart = "1".equals(params.get("perm_start_" + targetUserId));
                    boolean requestedFiles = "1".equals(params.get("perm_files_" + targetUserId));
                    boolean requestedProperties = "1".equals(params.get("perm_props_" + targetUserId));
                    boolean requestedSettings = "1".equals(params.get("perm_settings_" + targetUserId));

                    boolean canStart = isMainAdmin || requestedStart;
                    boolean canFiles = isMainAdmin || requestedFiles;
                    boolean canProperties = isMainAdmin || requestedProperties;
                    boolean canServerSettings = isMainAdmin || requestedSettings;

                    DatabaseManager.ServerPermissionRecord existingPermission = databaseManager
                            .getServerPermission(targetUserId, serverId)
                            .orElse(new DatabaseManager.ServerPermissionRecord(
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false));

                    databaseManager.upsertServerPermission(
                            targetUserId,
                            serverId,
                            existingPermission.canStartStop(),
                            existingPermission.canUseConsole(),
                            existingPermission.canManageFiles(),
                            canStart,
                            canFiles,
                            canProperties,
                            canServerSettings);
                }
            }
            redirect(exchange, "/server/settings?id=" + serverId + "&success=true");
        }

        private int parsePort(String raw, String fieldName, boolean required) {
            if (raw == null || raw.isBlank()) {
                if (required) {
                    throw new IllegalArgumentException(fieldName + " is required.");
                }
                return 0;
            }
            try {
                int port = Integer.parseInt(raw);
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException(fieldName + " must be between 1 and 65535.");
                }
                return port;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(fieldName + " must be a valid number.");
            }
        }

        private String trimToEmpty(String value) {
            return value == null ? "" : value.trim();
        }

        private String validateServerDirectory(String rawPath) {
            try {
                Path path = Path.of(rawPath).toAbsolutePath().normalize();
                if (!Files.exists(path)) {
                    return "Base directory path does not exist.";
                }
                if (!Files.isDirectory(path)) {
                    return "Base directory path must point to a directory.";
                }
                if (!Files.isReadable(path) || !Files.isWritable(path)) {
                    return "Base directory must be readable and writable by NeoDash.";
                }
                return null;
            } catch (Exception ex) {
                return "Base directory path is invalid.";
            }
        }

        private void redirectSettingsError(HttpExchange exchange, long serverId, String message) throws IOException {
            redirect(exchange, "/server/settings?id=" + serverId + "&error=" + encodeForQuery(message));
        }
    }

    private final class ServerCreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/server/create".equals(path) && "GET".equalsIgnoreCase(method)) {
                redirect(exchange, "/createServer");
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                String message = getQueryParam(exchange.getRequestURI().getQuery(), "message");
                String error = getQueryParam(exchange.getRequestURI().getQuery(), "error");
                sendResponse(exchange, CreateServerPage.render(getSessionUserId(exchange), message, error));
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (databaseManager == null) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Database unavailable.</body></html>");
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                String name = trimToEmpty(params.get("name"));
                if (name.isBlank()) {
                    throw new IllegalArgumentException("Server name is required.");
                }
                String pathToDir = trimToEmpty(params.get("path_to_dir"));
                if (pathToDir.isBlank()) {
                    throw new IllegalArgumentException("Base directory path is required.");
                }
                String startCommand = trimToEmpty(params.get("start_command"));
                if (startCommand.isBlank()) {
                    throw new IllegalArgumentException("Start command is required.");
                }

                int serverPort = 25565;
                if (serverPort < 1 || serverPort > 65535) {
                    throw new IllegalArgumentException("Invalid server port.");
                }

                String ipAddress = trimToEmpty(params.get("ip_address"));
                if (ipAddress.isBlank()) {
                    ipAddress = "127.0.0.1";
                }

                String runnerType = trimToEmpty(params.get("runner_type")).toUpperCase(Locale.ROOT);
                if (runnerType.isBlank()) {
                    runnerType = "SCREEN";
                }
                if (!"SCREEN".equals(runnerType) && !"DOCKER".equals(runnerType)) {
                    throw new IllegalArgumentException("Runner type must be SCREEN or DOCKER.");
                }

                String bridgePortRaw = trimToEmpty(
                        params.getOrDefault("bridge_port", params.getOrDefault("bridge_api_port", "8081")));
                Integer bridgeApiPort = null;
                if (!bridgePortRaw.isBlank()) {
                    int parsedBridgePort = Integer.parseInt(bridgePortRaw);
                    if (parsedBridgePort < 1 || parsedBridgePort > 65535) {
                        throw new IllegalArgumentException("Invalid bridge port.");
                    }
                    bridgeApiPort = parsedBridgePort;
                } else {
                    bridgeApiPort = 8081;
                }

                String dashPortRaw = trimToEmpty(params.get("dash_port"));
                if (dashPortRaw.isBlank()) {
                    throw new IllegalArgumentException("Dash Plugin Port is required.");
                }
                int dashPort = Integer.parseInt(dashPortRaw);
                if (dashPort < 1 || dashPort > 65535) {
                    throw new IllegalArgumentException("Invalid Dash Plugin Port.");
                }

                String bridgeSecret = trimToEmpty(params.getOrDefault("bridge_secret", ""));
                if (bridgeSecret.isBlank()) {
                    bridgeSecret = null;
                }

                long userId = getSessionUserId(exchange);
                logParsedBridgeTarget(exchange, userId, ipAddress, bridgeApiPort);
                long newServerId = databaseManager.createServer(
                        name,
                        pathToDir,
                        runnerType,
                        startCommand,
                        serverPort,
                        bridgeApiPort,
                        bridgeSecret,
                        ipAddress,
                        dashPort);
                if (newServerId <= 0) {
                    throw new IllegalStateException("Failed to create server.");
                }

                if (userId > 0) {
                    databaseManager.upsertServerPermission(userId, newServerId, true, true, true);
                }

                WebActionLogger.log("SERVER_CREATE", "serverId=" + newServerId + " created by userId=" + userId
                        + " from " + getClientIp(exchange));
                warmServerStatesForUser(userId);
                redirect(exchange, "/?message=" + encodeForQuery("Server created"));
            } catch (Exception ex) {
                logger.warning("Failed to create server: " + ex.getMessage());
                redirect(exchange, "/createServer?error=" + encodeForQuery(ex.getMessage()));
            }
        }

        private String trimToEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final class ServerInstallHandler implements HttpHandler {
        private static final String PACKWIZ_INSTALLER_URL = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest/download/packwiz-installer-bootstrap.jar";
        private static final String BUILD_TOOLS_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar";
        private static final String PAPER_USER_AGENT = "NeoDash/1.0";
        private static final String GITHUB_INSTALLER_USER_AGENT = "NeoDash-Installer";
        private static final Pattern MAJOR_MINOR_VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+).*");
        private static final Set<String> SUPPORTED_INSTALL_VERSIONS = Set.of(
                "1.21.11", "1.21", "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2",
                "1.8.8");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/server/install".equals(path) && "GET".equalsIgnoreCase(method)) {
                redirect(exchange, "/installServer");
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                String message = getQueryParam(exchange.getRequestURI().getQuery(), "message");
                String error = getQueryParam(exchange.getRequestURI().getQuery(), "error");
                String warning = getQueryParam(exchange.getRequestURI().getQuery(), "warning");
                sendResponse(exchange, InstallServerPage.render(getSessionUserId(exchange), message, error, warning));
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (databaseManager == null) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Database unavailable.</body></html>");
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            System.out.println("--- DEBUG: INSTALL REQUEST RECEIVED ---");
            params.forEach((key, value) -> System.out.println("Param: " + key + " = [" + value + "]"));
            System.out.println("---------------------------------------");

            try {
                long userId = getSessionUserId(exchange);
                List<String> installLog = new CopyOnWriteArrayList<>();
                installProgressByUser.put(userId, installLog);
                appendInstallLog(installLog, "Step 1: Validating installer parameters...");

                String name = trimToEmpty(params.get("name"));
                String installDirRaw = trimToEmpty(params.get("install_dir"));
                String serverType = trimToEmpty(params.get("server_type")).toUpperCase(Locale.ROOT);
                String version = trimToEmpty(params.get("version"));
                String customDownloadUrl = trimToEmpty(params.get("custom_download_url"));
                // Keep parameter name aligned with InstallServerPage: ram_mb
                int ramMb = parsePortLikeValue(trimToEmpty(params.get("ram_mb")), "RAM (MB)", 512, 262144);
                int serverPort = parsePortLikeValue(trimToEmpty(params.get("port")), "Port", 1, 65535);
                String bridgePortRaw = trimToEmpty(params.get("bridge_port"));
                int bridgePort = bridgePortRaw.isBlank()
                        ? 8081
                        : parsePortLikeValue(bridgePortRaw, "Bridge Port", 1, 65535);
                String bridgeSecret = trimToEmpty(params.get("bridge_secret"));
                String modpackId = trimToEmpty(params.get("modpack_id"));
                String modpackVersion = trimToEmpty(params.get("modpack_version"));

                if (name.isBlank()) {
                    throw new IllegalArgumentException("Server name is required.");
                }
                if (installDirRaw.isBlank()) {
                    throw new IllegalArgumentException("Install dir is required.");
                }
                if (version.isBlank()) {
                    throw new IllegalArgumentException("Version is required.");
                }
                if (!"PAPER".equals(serverType)
                        && !"PURPUR".equals(serverType)
                        && !"SPIGOT".equals(serverType)
                        && !"BUKKIT".equals(serverType)
                        && !"FABRIC".equals(serverType)
                        && !"QUILT".equals(serverType)
                        && !"VANILLA".equals(serverType)) {
                    throw new IllegalArgumentException("Type must be PAPER, PURPUR, SPIGOT, BUKKIT, FABRIC, QUILT, or VANILLA.");
                }

                Path installDir = Path.of(installDirRaw).toAbsolutePath().normalize();
                if (installDir.getNameCount() == 0) {
                    throw new IllegalArgumentException("Install dir is invalid.");
                }
                if (Files.exists(installDir) && Files.isDirectory(installDir)) {
                    sendJsonError(exchange, 400,
                            "Directory already exists. Please choose a different server name or delete the existing one.");
                    return;
                }

                appendInstallLog(installLog, "Step 2: Detecting Linux distribution...");
                String distro = detectLinuxDistro();
                logger.info("Server installer detected distro: " + distro);
                appendInstallLog(installLog, "Detected OS: " + distro);

                appendInstallLog(installLog, "Step 3: Creating installation directory...");
                Files.createDirectories(installDir);

                String effectiveServerType = serverType;
                if (!modpackId.isBlank() && (!"FABRIC".equals(effectiveServerType) && !"QUILT".equals(effectiveServerType))) {
                    throw new IllegalArgumentException("Modpacks are only supported on Fabric/Quilt.");
                }

                String resolvedJavaBinary = requiresBuildTools(effectiveServerType)
                        ? resolveJdkBinaryForVersion(version)
                        : resolvePreferredJavaBinary(version);

                appendInstallLog(installLog, "Step 4: Downloading base server files for " + effectiveServerType + "...");
                Path serverJar = installDir.resolve("server.jar");
                if (!customDownloadUrl.isBlank()) {
                    appendInstallLog(installLog, "Fetching from custom download URL...");
                    downloadWithCurlOrWget(customDownloadUrl, serverJar);
                } else {
                    installBaseServerBinary(effectiveServerType, version, installDir, serverJar, installLog,
                            resolvedJavaBinary);
                }
                if (!new File(installDir.toFile(), "server.jar").exists()) {
                    throw new IllegalStateException("Server jar download failed.");
                }
                appendInstallLog(installLog, "Base server JAR downloaded.");

                String startJarTarget = "server.jar";
                if (!modpackId.isBlank()) {
                    appendInstallLog(installLog, "Step 5: Resolving Modrinth modpack...");
                    try {
                        ModpackManager.ResolvedModpack modpackFile = modpackManager
                                .resolveModpack(modpackId, version, modpackVersion, installLog);

                        appendInstallLog(installLog, "Step 6: Downloading modpack archive...");
                        Path modpackArchive = modpackManager.downloadResolvedModpack(modpackFile, installDir,
                                installLog);

                        if (modpackFile.mrpack()) {
                            appendInstallLog(installLog, "Step 7: Processing .mrpack with Modrinth V2 logic...");
                            modpackManager.installFromMrpack(modpackArchive, installDir, installLog);
                        } else {
                            appendInstallLog(installLog, "Step 7: Extracting modpack ZIP...");
                            runCommand(
                                    List.of("unzip", "-o", modpackArchive.toString(), "-d", installDir.toString()),
                                    null,
                                    "Failed to extract modpack zip");
                            downloadModpackDependencies(modpackArchive, installDir, installLog);
                        }

                        startJarTarget = detectPreferredStartJar(installDir);
                        appendInstallLog(installLog, "Modpack installation completed.");
                    } catch (Exception modrinthEx) {
                        logger.warning("Modrinth install failed for slug=" + modpackId + ": " + modrinthEx.getMessage());
                        appendInstallLog(installLog,
                                "Modrinth install failed; continuing with standard installation.");
                    }
                }

                boolean supportsDashBridge = supportsDashBridge(effectiveServerType);
                if (supportsDashBridge) {
                    Path pluginsDir = installDir.resolve("plugins");
                    Files.createDirectories(pluginsDir);
                    try {
                        installLatestDashPlugin(pluginsDir, installLog);
                    } catch (Exception dashJarEx) {
                        logger.warning("[Installer] Failed to auto-fetch Dash.jar: " + dashJarEx.getMessage());
                        appendInstallLog(installLog,
                                "Step 8: Dash.jar auto-download failed (installer continues): " + dashJarEx.getMessage());
                    }
                } else {
                    appendInstallLog(installLog,
                            "Step 8: Skipping Dash plugin injection for " + effectiveServerType + " servers.");
                }

                appendInstallLog(installLog, "Step 10: Writing EULA and start script...");
                Files.writeString(installDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
                Path startScriptPath = installDir.resolve("start.sh");
                Path startJarPath = installDir.resolve(startJarTarget);
                String taggedServerDir = installDir.toString()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
                String startScriptContent = "#!/bin/bash\n" +
                        resolvedJavaBinary
                        + " -Dneodash.server.dir=\"" + taggedServerDir + "\""
                        + " -Xmx" + ramMb + "M -jar " + startJarPath.getFileName().toString() + " nogui\n";

                Files.writeString(startScriptPath, startScriptContent, java.nio.charset.StandardCharsets.UTF_8);
                runCommand(List.of("chmod", "+x", startScriptPath.toString()), null, "Failed to set start.sh executable.");

                if (!modpackId.isBlank()) {
                    String effectiveModpackVersion = modpackVersion.isBlank() ? "latest" : modpackVersion;
                    Files.writeString(installDir.resolve("MODPACK_INFO.txt"),
                            "modpackId=" + modpackId
                                    + "\nmodpackVersion=" + effectiveModpackVersion
                                    + "\nmcVersion=" + version
                                    + "\n",
                            StandardCharsets.UTF_8);
                }

                appendInstallLog(installLog, "Step 11: Pre-generating server config files...");
                writeServerPropertiesDefaults(installDir, serverPort);
                if (supportsDashBridge) {
                    writeDashPluginConfig(installDir, bridgePort, bridgeSecret);
                }

                appendInstallLog(installLog, "Step 12: Registering server in database...");
                String bridgeSecretDb = bridgeSecret.isBlank() ? null : bridgeSecret;
                Integer bridgePortDb = bridgePort;
                long serverId = databaseManager.createServer(
                        name,
                        installDir.toString(),
                        "SCREEN",
                        "./start.sh",
                        serverPort,
                        bridgePortDb,
                        bridgeSecretDb,
                        "127.0.0.1",
                        bridgePort,
                        effectiveServerType);
                if (serverId <= 0) {
                    throw new IllegalStateException("Failed to register server in database.");
                }

                if (userId > 0) {
                    databaseManager.upsertServerPermission(userId, serverId, true, true, true);
                }

                appendInstallLog(installLog, "Installation complete.");

                WebActionLogger.log("SERVER_INSTALL",
                        "serverId=" + serverId
                                + " name=" + name
                                + " type=" + effectiveServerType
                                + " version=" + version
                                + " modpack=" + modpackId
                                + " bridgePort=" + bridgePort
                                + " userId=" + userId
                                + " ip=" + getClientIp(exchange));
                warmServerStatesForUser(userId);
                redirect(exchange, "/?message=" + encodeForQuery(
                        "Server installed. NOTE: Minecraft 1.21.11 requires Java 21. Run 'sudo pacman -S jre21-openjdk' on your Manjaro system if it fails to start."));
            } catch (Exception ex) {
                logger.warning("Server installation failed: " + ex.getMessage());
                redirect(exchange, "/installServer?error=" + encodeForQuery(ex.getMessage()));
            }
        }

        private void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            String safe = message == null ? "Request failed." : message;
            sendResponseWithStatus(exchange, status, "{\"error\":\"" + escapeJson(safe) + "\"}");
        }

        private boolean supportsDashBridge(String serverType) {
            if (serverType == null) {
                return false;
            }
            return switch (serverType.toUpperCase(Locale.ROOT)) {
                case "PAPER", "PURPUR", "SPIGOT", "BUKKIT" -> true;
                default -> false;
            };
        }

        private String resolvePreferredJavaBinary(String version) {
            return resolveJavaBinaryForVersion(version);
        }

        private boolean requiresBuildTools(String type) {
            String normalized = trimToEmpty(type).toUpperCase(Locale.ROOT);
            return "SPIGOT".equals(normalized) || "BUKKIT".equals(normalized);
        }

        private String resolveJavaBinaryForVersion(String mcVersion) {
            int targetMajor = resolvePreferredJavaMajor(mcVersion);
            List<String> candidates = new ArrayList<>();

            if (targetMajor >= 25) {
                candidates.add("/home/maximt/.jdks/temurin-25.0.2/bin/java");
            } else if (targetMajor >= 21) {
                candidates.add("/home/maximt/.jdks/temurin-21.0.2/bin/java");
                candidates.add("/home/maximt/.jdks/temurin-21.0.7/bin/java");
            }

            candidates.add("/usr/lib/jvm/java-" + targetMajor + "-openjdk/bin/java");
            candidates.add("/usr/lib/jvm/temurin-" + targetMajor + "-jdk/bin/java");
            candidates.add("/usr/lib/jvm/java-" + targetMajor + "-temurin/bin/java");

            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                File javaFile = new File(candidate);
                if (javaFile.exists() && javaFile.canExecute()) {
                    return javaFile.getAbsolutePath();
                }
            }

            logger.warning("Installer Java resolver fallback: no Java " + targetMajor
                    + " binary found for Minecraft " + mcVersion + ". Falling back to system 'java'.");
            return "java";
        }

        private String resolveJdkBinaryForVersion(String mcVersion) {
            int targetMajor = resolvePreferredJavaMajor(mcVersion);
            List<Path> javaCandidates = new ArrayList<>();

            javaCandidates.addAll(findTemurinJdkJavaCandidates(targetMajor));
            javaCandidates.add(Path.of("/usr/lib/jvm/java-" + targetMajor + "-openjdk/bin/java"));
            javaCandidates.add(Path.of("/usr/lib/jvm/temurin-" + targetMajor + "-jdk/bin/java"));
            javaCandidates.add(Path.of("/usr/lib/jvm/java-" + targetMajor + "-temurin/bin/java"));

            boolean foundJreWithoutCompiler = false;
            for (Path javaPath : javaCandidates) {
                if (javaPath == null || !Files.isRegularFile(javaPath) || !Files.isExecutable(javaPath)) {
                    continue;
                }
                Path javacPath = javaPath.getParent() == null ? null : javaPath.getParent().resolve("javac");
                if (javacPath != null && Files.isRegularFile(javacPath) && Files.isExecutable(javacPath)) {
                    return javaPath.toString();
                }
                foundJreWithoutCompiler = true;
            }

            if (foundJreWithoutCompiler) {
                throw new IllegalStateException(
                        "BuildTools requires a JDK (Java Development Kit), but only a JRE was found.");
            }

            throw new IllegalStateException("Missing JDK " + targetMajor
                    + " for Minecraft " + mcVersion + ". Install a matching JDK and retry.");
        }

        private List<Path> findTemurinJdkJavaCandidates(int targetMajor) {
            Path jdksRoot = Path.of("/home/maximt/.jdks");
            if (!Files.isDirectory(jdksRoot)) {
                return List.of();
            }
            String prefix = "temurin-" + targetMajor + ".";
            List<Path> matches = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(jdksRoot)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                        .map(path -> path.resolve("bin").resolve("java"))
                        .forEach(matches::add);
            } catch (IOException ignored) {
            }
            return matches;
        }

        private int resolvePreferredJavaMajor(String version) {
            String normalized = trimToEmpty(version);
            if (normalized.isBlank()) {
                return 25;
            }
            Matcher matcher = MAJOR_MINOR_VERSION_PATTERN.matcher(normalized);
            if (!matcher.matches()) {
                return 25;
            }
            int major;
            int minor;
            try {
                major = Integer.parseInt(matcher.group(1));
                minor = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                return 25;
            }
            if (major > 26 || (major == 26 && minor >= 1)) {
                return 25;
            }
            if (major == 1 && minor >= 20) {
                int patch = 0;
                String[] split = normalized.split("\\.");
                if (split.length >= 3) {
                    try {
                        patch = Integer.parseInt(split[2].replaceAll("[^0-9].*$", ""));
                    } catch (NumberFormatException ignored) {
                        patch = 0;
                    }
                }
                if (minor == 20 && patch >= 5) {
                    return 21;
                }
                if (minor == 21) {
                    return 21;
                }
            }
            return 25;
        }

        private String buildScreenSessionName(String serverName) {
            String normalized = trimToEmpty(serverName)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_-]", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            if (normalized.isBlank()) {
                normalized = "server";
            }
            if (normalized.length() > 24) {
                normalized = normalized.substring(0, 24);
            }
            return "mc-" + normalized;
        }

        private record DownloadSpec(String url, String sha256) {
        }

        private void installLatestDashPlugin(Path pluginsDir, List<String> installLog) throws IOException {
            String repo = trimToEmpty(NeoDash.getDashGithubRepo());
            if (repo.isBlank()) {
                repo = "Framepersecond/Dash";
            }
            String token = trimToEmpty(NeoDash.getDashGithubToken());
            if (token.isBlank() || "YOUR_TOKEN_HERE".equals(token)) {
                String skipMessage = "Dash plugin download skipped: No GitHub token in config.";
                logger.warning(skipMessage);
                appendInstallLog(installLog, "Step 8: " + skipMessage);
                return;
            }

            String endpoint = "https://api.github.com/repos/" + repo + "/releases/latest";
            JsonObject release = fetchJsonObject(endpoint, Map.of(
                    "Authorization", "Bearer " + token,
                    "User-Agent", GITHUB_INSTALLER_USER_AGENT,
                    "Accept", "application/vnd.github+json"));

            if (!release.has("assets") || !release.get("assets").isJsonArray()) {
                throw new IllegalStateException("No downloadable assets found in latest release for " + repo + ".");
            }

            JsonObject jarAsset = null;
            for (JsonElement element : release.getAsJsonArray("assets")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject asset = element.getAsJsonObject();
                String url = asset.has("browser_download_url") && !asset.get("browser_download_url").isJsonNull()
                        ? asset.get("browser_download_url").getAsString()
                        : "";
                String name = asset.has("name") && !asset.get("name").isJsonNull()
                        ? asset.get("name").getAsString()
                        : "";
                if (url.toLowerCase(Locale.ROOT).endsWith(".jar") || name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    jarAsset = asset;
                    break;
                }
            }
            if (jarAsset == null) {
                throw new IllegalStateException("No .jar asset found in latest release for " + repo + ".");
            }

            String downloadUrl = jarAsset.get("browser_download_url").getAsString();
            String assetName = jarAsset.has("name") && !jarAsset.get("name").isJsonNull()
                    ? safeFileName(jarAsset.get("name").getAsString())
                    : "Dash.jar";
            Files.createDirectories(pluginsDir);
            // Keep a stable file name to avoid multiple versioned Dash jars in plugins/.
            Path target = pluginsDir.resolve("Dash.jar");
            downloadBinary(downloadUrl, target, Map.of(
                    "Authorization", "Bearer " + token,
                    "User-Agent", GITHUB_INSTALLER_USER_AGENT,
                    "Accept", "application/octet-stream"), null);
            appendInstallLog(installLog,
                    "Step 8: Downloaded latest Dash plugin from GitHub release into plugins directory (asset="
                            + assetName + ", savedAs=Dash.jar).");
        }

        private void installBuildToolsServerJar(String type, String version, Path installDir, Path serverJar,
                List<String> installLog, String resolvedJdkBinary) throws IOException {
            appendInstallLog(installLog, "Preparing BuildTools workspace...");
            Path buildToolsDir = Files.createTempDirectory(installDir, "buildtools-");
            ensureGitInstalled(installLog);

            Path buildToolsJar = buildToolsDir.resolve("BuildTools.jar");
            downloadBinary(BUILD_TOOLS_URL, buildToolsJar, Map.of("User-Agent", PAPER_USER_AGENT), null);
            appendInstallLog(installLog, "Running BuildTools (this can take several minutes)...");
            runBuildToolsWithLiveLogs(resolvedJdkBinary, buildToolsDir, version, type, installLog);

            String artifactPrefix = "BUKKIT".equalsIgnoreCase(type) ? "craftbukkit-" : "spigot-";
            Path builtJar = findBuildToolsOutput(buildToolsDir, artifactPrefix, version);
            Files.move(builtJar, serverJar, StandardCopyOption.REPLACE_EXISTING);
        }

        private void runBuildToolsWithLiveLogs(String javaBinary, Path workingDir, String mcVersion, String serverType,
                List<String> installLog) throws IOException {
            List<String> command = new ArrayList<>(List.of(
                    javaBinary,
                    "-jar",
                    "BuildTools.jar",
                    "--rev",
                    mcVersion));
            if ("BUKKIT".equalsIgnoreCase(serverType)) {
                command.addAll(List.of("--compile", "craftbukkit"));
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.inheritIO();
            try {
                Process process = builder.start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    Deque<String> tail = readBuildToolsLogTail(workingDir, 40);
                    throw classifyBuildToolsFailure(exitCode, mcVersion, javaBinary, tail);
                }
                appendInstallLog(installLog, "BuildTools finished successfully.");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("BuildTools failed: interrupted while running installer.", ex);
            }
        }

        private Deque<String> readBuildToolsLogTail(Path workingDir, int maxLines) {
            Deque<String> tail = new ArrayDeque<>();
            Path[] candidates = new Path[] {
                    workingDir.resolve("BuildTools.log.txt"),
                    workingDir.resolve("BuildTools.log")
            };
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                try (BufferedReader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isBlank()) {
                            continue;
                        }
                        tail.addLast(trimmed);
                        if (tail.size() > maxLines) {
                            tail.removeFirst();
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            return tail;
        }

        private IOException classifyBuildToolsFailure(int exitCode, String mcVersion, String javaBinary,
                Deque<String> tail) {
            String tailText = String.join("\n", tail);
            String lower = tailText.toLowerCase(Locale.ROOT);

            if (lower.contains("requires java versions between")
                    || lower.contains("no compiler is provided")
                    || lower.contains("unsupported class file major version")
                    || lower.contains("java version") && lower.contains("required")) {
                int requiredJava = resolvePreferredJavaMajor(mcVersion);
                String currentJava = detectJavaRuntimeLabel(javaBinary);
                return new IOException("BuildTools failed: Java version mismatch. Minecraft " + mcVersion
                        + " requires Java " + requiredJava + ", but found " + currentJava + ".");
            }

            if (lower.contains("git")
                    && (lower.contains("not found")
                            || lower.contains("fatal:")
                            || lower.contains("repository")
                            || lower.contains("unable to access"))) {
                return new IOException("BuildTools failed: Git not found or repository error.");
            }

            return new IOException("BuildTools failed with exit code " + exitCode + ". Check terminal for details.");
        }

        private String detectJavaRuntimeLabel(String javaBinary) {
            ProcessBuilder builder = new ProcessBuilder(javaBinary, "-version");
            builder.redirectErrorStream(true);
            try {
                Process process = builder.start();
                String firstLine = "";
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    firstLine = trimToEmpty(reader.readLine());
                }
                process.waitFor();
                if (!firstLine.isBlank()) {
                    return javaBinary + " (" + firstLine + ")";
                }
            } catch (Exception ignored) {
            }
            return javaBinary;
        }

        private void ensureGitInstalled(List<String> installLog) {
            try {
                runCommand(List.of("git", "--version"), null, "git check failed");
            } catch (Exception ex) {
                String warning = "BuildTools prerequisite warning: git is not installed on the host.";
                logger.warning(warning);
                appendInstallLog(installLog, warning);
            }
        }

        private Path findBuildToolsOutput(Path buildToolsDir, String artifactPrefix, String version) throws IOException {
            String expectedName = artifactPrefix + version + ".jar";
            Path exact = buildToolsDir.resolve(expectedName);
            if (Files.exists(exact)) {
                return exact;
            }
            try (java.util.stream.Stream<Path> stream = Files.list(buildToolsDir)) {
                Optional<Path> prefixed = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(artifactPrefix))
                        .max(Comparator.comparing(path -> path.getFileName().toString()));
                if (prefixed.isPresent()) {
                    return prefixed.get();
                }
            }
            try (java.util.stream.Stream<Path> stream = Files.list(buildToolsDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .filter(path -> {
                            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return lower.contains("spigot") || lower.contains("craftbukkit");
                        })
                        .max(Comparator.comparing(path -> path.getFileName().toString()))
                        .orElseThrow(() -> new IllegalStateException(
                                "BuildTools did not produce expected artifact " + expectedName + "."));
            }
        }

        private void downloadBinary(String url, Path target, Map<String, String> headers, String expectedSha256)
                throws IOException {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", PAPER_USER_AGENT);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }

                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    String body = readErrorBody(connection);
                    throw new IOException("HTTP " + status + " from " + url + (body.isBlank() ? "" : " - " + body));
                }

                try (InputStream in = connection.getInputStream();
                        OutputStream out = Files.newOutputStream(target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            String expected = trimToEmpty(expectedSha256);
            if (!expected.isBlank()) {
                verifySha256(target, expected);
            }
        }

        private void verifySha256(Path file, String expectedSha256) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
                String actual = bytesToHex(digest.digest());
                if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                    throw new IllegalStateException("SHA-256 mismatch for " + file.getFileName() + ": expected "
                            + expectedSha256 + " but got " + actual);
                }
            } catch (NoSuchAlgorithmException | IOException ex) {
                throw new IllegalStateException("Failed SHA-256 verification for " + file.getFileName() + ": "
                        + ex.getMessage(), ex);
            }
        }

        private String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        private String extractSha256(JsonObject node) {
            if (node == null) {
                return null;
            }
            if (node.has("sha256") && !node.get("sha256").isJsonNull()) {
                return node.get("sha256").getAsString();
            }
            if (node.has("checksum") && !node.get("checksum").isJsonNull()) {
                return node.get("checksum").getAsString();
            }
            if (node.has("checksums") && node.get("checksums").isJsonObject()) {
                JsonObject checksums = node.getAsJsonObject("checksums");
                if (checksums.has("sha256") && !checksums.get("sha256").isJsonNull()) {
                    return checksums.get("sha256").getAsString();
                }
            }
            return null;
        }

        private void writeDashPluginConfig(Path installDir, Integer bridgePort, String bridgeSecret)
                throws IOException {
            if (bridgePort == null) {
                throw new IllegalArgumentException("Bridge Port is required.");
            }
            bridgeSecret = bridgeSecret == null ? "" : bridgeSecret;
            Path configPath = installDir.toAbsolutePath().normalize()
                    .resolve("plugins")
                    .resolve("Dash")
                    .resolve("config.yml")
                    .toAbsolutePath()
                    .normalize();
            try {
                Path parent = configPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                YamlConfiguration config = new YamlConfiguration();

                config.set("port", bridgePort);
                config.set("bridge.enabled", true);
                config.set("bridge.secret", bridgeSecret);
                config.set("updater.github-token",
                        "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");
                config.set("updater.github-repo", "Framepersecond/Dash-Updates");

                // Cleanup legacy duplicate/root keys so only bridge/updater blocks hold these values.
                config.set("bridge-secret", null);
                config.set("github-token", null);
                config.set("github-repo", null);

                Files.writeString(configPath, config.saveToString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
                throw e;
            }
        }

        private void appendInstallLog(List<String> installLog, String line) {
            if (installLog == null || line == null || line.isBlank()) {
                return;
            }
            installLog.add(line);
        }

        private String detectLinuxDistro() {
            StringBuilder output = new StringBuilder();
            try {
                Process process = Runtime.getRuntime().exec(new String[] { "cat", "/etc/os-release" });
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.fine("Unable to read /etc/os-release: " + ex.getMessage());
            }

            String raw = output.toString();
            for (String line : raw.split("\\R")) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return line.substring("PRETTY_NAME=".length()).replace("\"", "").trim();
                }
            }
            return raw.isBlank() ? "Unknown Linux" : raw.lines().findFirst().orElse("Unknown Linux");
        }

        private void installBaseServerBinary(String type, String version, Path installDir, Path serverJar,
                List<String> installLog, String resolvedJavaBinary) throws IOException {
            String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
            switch (normalizedType) {
                case "PAPER" -> {
                    appendInstallLog(installLog, "Fetching from official PAPER API...");
                    downloadOfficialServerJar(version, resolveOfficialPaperDownloadSpec(version), serverJar);
                }
                case "PURPUR" -> {
                    appendInstallLog(installLog, "Fetching from official PURPUR API...");
                    downloadOfficialServerJar(version, resolveOfficialPurpurDownloadSpec(version), serverJar);
                }
                case "FABRIC" -> {
                    appendInstallLog(installLog, "Fetching from official FABRIC API...");
                    Path fabricJar = installDir.resolve("fabric-server.jar");
                    downloadOfficialServerJar(version, resolveOfficialFabricServerSpec(version), fabricJar);
                    Files.copy(fabricJar, serverJar, StandardCopyOption.REPLACE_EXISTING);
                }
                case "QUILT" -> {
                    appendInstallLog(installLog, "Fetching from official QUILT API...");
                    Path quiltJar = installDir.resolve("quilt-server.jar");
                    downloadOfficialServerJar(version, resolveOfficialQuiltServerSpec(version), quiltJar);
                    Files.copy(quiltJar, serverJar, StandardCopyOption.REPLACE_EXISTING);
                }
                case "VANILLA" -> {
                    appendInstallLog(installLog, "Fetching from official VANILLA API...");
                    downloadOfficialServerJar(version, resolveOfficialVanillaServerSpec(version), serverJar);
                }
                case "SPIGOT" -> {
                    appendInstallLog(installLog, "Building SPIGOT server with BuildTools...");
                    installBuildToolsServerJar("SPIGOT", version, installDir, serverJar, installLog,
                            resolvedJavaBinary);
                }
                case "BUKKIT" -> {
                    appendInstallLog(installLog, "Building BUKKIT server with BuildTools...");
                    installBuildToolsServerJar("BUKKIT", version, installDir, serverJar, installLog,
                            resolvedJavaBinary);
                }
                default -> throw new IOException("No official download source configured for " + normalizedType);
            }
        }

        private void downloadOfficialServerJar(String version, DownloadSpec spec, Path target) throws IOException {
            try {
                downloadBinary(spec.url(), target, Map.of(), spec.sha256());
            } catch (Exception ex) {
                String detail = trimToEmpty(ex.getMessage());
                if (detail.isBlank()) {
                    detail = "Version " + version + " not found on official API.";
                }
                throw new IOException(detail, ex);
            }
        }

        private DownloadSpec resolveOfficialPaperDownloadSpec(String version) {
            String versionEndpoint = "https://api.papermc.io/v2/projects/paper/versions/" + encodeForQuery(version);
            fetchJsonObject(versionEndpoint, Map.of("User-Agent", PAPER_USER_AGENT));

            String buildsEndpoint = versionEndpoint + "/builds";
            JsonObject buildsRoot = fetchJsonObject(buildsEndpoint, Map.of("User-Agent", PAPER_USER_AGENT));
            if (!buildsRoot.has("builds") || !buildsRoot.get("builds").isJsonArray()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }

            JsonObject latestStable = null;
            for (JsonElement buildElement : buildsRoot.getAsJsonArray("builds")) {
                if (!buildElement.isJsonObject()) {
                    continue;
                }
                JsonObject buildObj = buildElement.getAsJsonObject();
                if (!isPaperStableBuild(buildObj)) {
                    continue;
                }
                if (latestStable == null || comparePaperBuildNumber(buildObj, latestStable) > 0) {
                    latestStable = buildObj;
                }
            }
            if (latestStable == null) {
                throw new IllegalStateException("Version " + version + " has no stable Paper build.");
            }

            int buildNumber = latestStable.get("build").getAsInt();
            JsonObject downloads = latestStable.has("downloads") && latestStable.get("downloads").isJsonObject()
                    ? latestStable.getAsJsonObject("downloads")
                    : null;
            JsonObject app = downloads != null && downloads.has("application") && downloads.get("application").isJsonObject()
                    ? downloads.getAsJsonObject("application")
                    : null;
            String name = app != null && app.has("name") && !app.get("name").isJsonNull()
                    ? app.get("name").getAsString()
                    : "paper-" + version + "-" + buildNumber + ".jar";
            String sha256 = app != null ? extractSha256(app) : null;
            String url = versionEndpoint
                    + "/builds/" + buildNumber
                    + "/downloads/" + encodeForQuery(name);
            return new DownloadSpec(url, sha256);
        }

        private boolean isPaperStableBuild(JsonObject buildObj) {
            if (!buildObj.has("build") || buildObj.get("build").isJsonNull()) {
                return false;
            }
            String channel = buildObj.has("channel") && !buildObj.get("channel").isJsonNull()
                    ? buildObj.get("channel").getAsString()
                    : "";
            return !"experimental".equalsIgnoreCase(channel);
        }

        private int comparePaperBuildNumber(JsonObject left, JsonObject right) {
            int leftBuild = left.has("build") ? left.get("build").getAsInt() : -1;
            int rightBuild = right.has("build") ? right.get("build").getAsInt() : -1;
            return Integer.compare(leftBuild, rightBuild);
        }

        private DownloadSpec resolveOfficialPurpurDownloadSpec(String version) {
            String endpoint = "https://api.purpurmc.org/v2/purpur/" + encodeForQuery(version) + "/latest";
            JsonObject latest = fetchJsonObject(endpoint, Map.of("User-Agent", PAPER_USER_AGENT));
            String downloadUrl = "https://api.purpurmc.org/v2/purpur/" + encodeForQuery(version) + "/latest/download";
            return new DownloadSpec(downloadUrl, extractSha256(latest));
        }

        private DownloadSpec resolveOfficialFabricServerSpec(String version) {
            JsonElement root = fetchJsonElement(
                    "https://meta.fabricmc.net/v2/versions/loader/" + encodeForQuery(version),
                    Map.of("User-Agent", PAPER_USER_AGENT));
            if (!root.isJsonArray()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }

            JsonArray entries = root.getAsJsonArray();
            if (entries.isEmpty() || !entries.get(0).isJsonObject()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }

            JsonObject first = entries.get(0).getAsJsonObject();
            if (!first.has("loader") || !first.get("loader").isJsonObject()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }
            JsonObject loader = first.getAsJsonObject("loader");
            if (!loader.has("version") || loader.get("version").isJsonNull()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }
            String loaderVersion = loader.get("version").getAsString();

            JsonElement installersRoot = fetchJsonElement(
                    "https://meta.fabricmc.net/v2/versions/installer",
                    Map.of("User-Agent", PAPER_USER_AGENT));
            if (!installersRoot.isJsonArray() || installersRoot.getAsJsonArray().isEmpty()
                    || !installersRoot.getAsJsonArray().get(0).isJsonObject()) {
                throw new IllegalStateException("Unable to resolve Fabric installer version from official API.");
            }
            JsonObject installerObj = installersRoot.getAsJsonArray().get(0).getAsJsonObject();
            if (!installerObj.has("version") || installerObj.get("version").isJsonNull()) {
                throw new IllegalStateException("Unable to resolve Fabric installer version from official API.");
            }
            String installerVersion = installerObj.get("version").getAsString();
            String url = "https://meta.fabricmc.net/v2/versions/loader/"
                    + encodeForQuery(version)
                    + "/" + encodeForQuery(loaderVersion)
                    + "/" + encodeForQuery(installerVersion)
                    + "/server/jar";
            return new DownloadSpec(url, extractSha256(first));
        }

        private DownloadSpec resolveOfficialQuiltServerSpec(String version) {
            JsonElement root = fetchJsonElement(
                    "https://meta.quiltmc.org/v3/versions/loader/" + encodeForQuery(version),
                    Map.of("User-Agent", PAPER_USER_AGENT));
            if (!root.isJsonArray()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }
            JsonArray entries = root.getAsJsonArray();
            if (entries.isEmpty() || !entries.get(0).isJsonObject()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }

            JsonObject first = entries.get(0).getAsJsonObject();
            String loaderVersion = extractQuiltVersion(first, "loader");
            String installerVersion = extractQuiltVersion(first, "installer");

            String url = "https://meta.quiltmc.org/v3/versions/loader/"
                    + encodeForQuery(version)
                    + "/" + encodeForQuery(loaderVersion)
                    + "/" + encodeForQuery(installerVersion)
                    + "/server/jar";
            return new DownloadSpec(url, extractSha256(first));
        }

        private String extractQuiltVersion(JsonObject root, String key) {
            if (!root.has(key) || !root.get(key).isJsonObject()) {
                throw new IllegalStateException("Unable to resolve Quilt " + key + " version from official API.");
            }
            JsonObject node = root.getAsJsonObject(key);
            if (!node.has("version") || node.get("version").isJsonNull()) {
                throw new IllegalStateException("Unable to resolve Quilt " + key + " version from official API.");
            }
            return node.get("version").getAsString();
        }

        /** Returns true if the path exists, is larger than 10 KB, and starts with the PK ZIP magic bytes. */
        private boolean isValidJarFile(Path path) {
            try {
                if (!Files.exists(path) || Files.size(path) < 10_240L) {
                    return false;
                }
                byte[] header = new byte[4];
                try (InputStream is = Files.newInputStream(path)) {
                    int read = is.read(header);
                    if (read < 4) {
                        return false;
                    }
                }
                // ZIP/JAR magic: PK\x03\x04
                return header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
            } catch (IOException ex) {
                return false;
            }
        }

        private DownloadSpec resolveOfficialVanillaServerSpec(String version) {
            JsonObject manifest = fetchJsonObject("https://launchermeta.mojang.com/mc/game/version_manifest.json");
            if (!manifest.has("versions") || !manifest.get("versions").isJsonArray()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }

            String versionDetailsUrl = null;
            for (JsonElement element : manifest.getAsJsonArray("versions")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String id = entry.has("id") && !entry.get("id").isJsonNull()
                        ? entry.get("id").getAsString()
                        : "";
                if (!version.equals(id)) {
                    continue;
                }
                versionDetailsUrl = entry.has("url") && !entry.get("url").isJsonNull()
                        ? entry.get("url").getAsString()
                        : null;
                break;
            }
            if (versionDetailsUrl == null || versionDetailsUrl.isBlank()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }

            JsonObject details = fetchJsonObject(versionDetailsUrl);
            if (!details.has("downloads") || !details.get("downloads").isJsonObject()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }
            JsonObject downloads = details.getAsJsonObject("downloads");
            if (!downloads.has("server") || !downloads.get("server").isJsonObject()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }
            JsonObject serverNode = downloads.getAsJsonObject("server");
            if (!serverNode.has("url") || serverNode.get("url").isJsonNull()) {
                throw new IllegalStateException("Version " + version + " not found on official API.");
            }
            String sha256 = extractSha256(serverNode);
            return new DownloadSpec(serverNode.get("url").getAsString(), sha256);
        }

        private JsonObject fetchJsonObject(String endpoint) {
            JsonElement root = fetchJsonElement(endpoint, Map.of());
            if (!root.isJsonObject()) {
                throw new IllegalStateException("Unexpected JSON object response from " + endpoint);
            }
            return root.getAsJsonObject();
        }

        private JsonObject fetchJsonObject(String endpoint, Map<String, String> requestHeaders) {
            JsonElement root = fetchJsonElement(endpoint, requestHeaders);
            if (!root.isJsonObject()) {
                throw new IllegalStateException("Unexpected JSON object response from " + endpoint);
            }
            return root.getAsJsonObject();
        }

        private JsonElement fetchJsonElement(String endpoint) {
            return fetchJsonElement(endpoint, Map.of());
        }

        private JsonElement fetchJsonElement(String endpoint, Map<String, String> requestHeaders) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(endpoint);
                // Enable redirect following globally and per-connection so CDN/API redirects work.
                HttpURLConnection.setFollowRedirects(true);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", PAPER_USER_AGENT);
                for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }

                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    String body = readErrorBody(connection);
                    throw new IllegalStateException("HTTP " + status + " from " + endpoint + (body.isBlank() ? "" : " - " + body));
                }

                try (InputStream in = connection.getInputStream()) {
                    String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return JsonParser.parseString(body);
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed API request: " + ex.getMessage(), ex);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private String readErrorBody(HttpURLConnection connection) {
            if (connection == null) {
                return "";
            }
            try (InputStream in = connection.getErrorStream()) {
                if (in == null) {
                    return "";
                }
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return body.length() > 300 ? body.substring(0, 300) + "..." : body;
            } catch (Exception ignored) {
                return "";
            }
        }

        private void downloadModpackDependencies(Path modpackArchive, Path installDir, List<String> installLog)
                throws IOException {
            if (modpackArchive == null || !Files.isRegularFile(modpackArchive)) {
                return;
            }
            Path modsDir = installDir.resolve("mods");
            Files.createDirectories(modsDir);

            try (ZipFile zip = new ZipFile(modpackArchive.toFile())) {
                int downloaded = downloadModrinthIndexMods(zip, modsDir, installLog)
                        + downloadCurseManifestMods(zip, modsDir, installLog);
                if (downloaded > 0) {
                    appendInstallLog(installLog, "Downloaded " + downloaded + " mod dependencies into mods/.");
                }
            } catch (IOException ex) {
                appendInstallLog(installLog, "Modpack dependency download skipped: " + ex.getMessage());
            }
        }

        private int downloadModrinthIndexMods(ZipFile zip, Path modsDir, List<String> installLog) throws IOException {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) {
                indexEntry = zip.getEntry("index.json");
            }
            if (indexEntry == null) {
                return 0;
            }

            JsonElement root;
            try (InputStream in = zip.getInputStream(indexEntry)) {
                root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!root.isJsonObject()) {
                return 0;
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("files") || !obj.get("files").isJsonArray()) {
                return 0;
            }

            int downloaded = 0;
            for (JsonElement fileEl : obj.getAsJsonArray("files")) {
                if (!fileEl.isJsonObject()) {
                    continue;
                }
                JsonObject fileObj = fileEl.getAsJsonObject();
                String downloadUrl = null;
                if (fileObj.has("downloads") && fileObj.get("downloads").isJsonArray()) {
                    for (JsonElement dl : fileObj.getAsJsonArray("downloads")) {
                        if (dl.isJsonNull()) {
                            continue;
                        }
                        String candidate = dl.getAsString();
                        if (candidate != null && !candidate.isBlank()) {
                            downloadUrl = candidate;
                            break;
                        }
                    }
                }
                if (downloadUrl == null || downloadUrl.isBlank()) {
                    continue;
                }
                String fileName = resolveDownloadFileName(fileObj, downloadUrl);
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                Path target = modsDir.resolve(fileName);
                downloadBinary(downloadUrl, target, Map.of(), null);
                downloaded++;
            }
            return downloaded;
        }

        private int downloadCurseManifestMods(ZipFile zip, Path modsDir, List<String> installLog) throws IOException {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null) {
                return 0;
            }

            JsonElement root;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!root.isJsonObject()) {
                return 0;
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("files") || !obj.get("files").isJsonArray()) {
                return 0;
            }

            int downloaded = 0;
            for (JsonElement fileEl : obj.getAsJsonArray("files")) {
                if (!fileEl.isJsonObject()) {
                    continue;
                }
                JsonObject fileObj = fileEl.getAsJsonObject();
                String url = null;
                if (fileObj.has("downloadUrl") && !fileObj.get("downloadUrl").isJsonNull()) {
                    url = fileObj.get("downloadUrl").getAsString();
                } else if (fileObj.has("url") && !fileObj.get("url").isJsonNull()) {
                    url = fileObj.get("url").getAsString();
                }
                if (url == null || url.isBlank()) {
                    continue;
                }
                String fileName = resolveDownloadFileName(fileObj, url);
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                Path target = modsDir.resolve(fileName);
                downloadBinary(url, target, Map.of(), null);
                downloaded++;
            }
            return downloaded;
        }

        private String resolveDownloadFileName(JsonObject fileObj, String url) {
            String byPath = fileObj != null && fileObj.has("path") && !fileObj.get("path").isJsonNull()
                    ? fileObj.get("path").getAsString()
                    : "";
            if (!byPath.isBlank()) {
                String name = Path.of(byPath).getFileName().toString();
                if (!name.isBlank()) {
                    return safeFileName(name);
                }
            }
            String normalized = url == null ? "" : url;
            int q = normalized.indexOf('?');
            if (q >= 0) {
                normalized = normalized.substring(0, q);
            }
            int slash = normalized.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < normalized.length()) {
                return safeFileName(normalized.substring(slash + 1));
            }
            return "mod-" + UUID.randomUUID() + ".jar";
        }

        private void writeServerPropertiesDefaults(Path installDir, int serverPort) throws IOException {
            Path propertiesPath = installDir.toAbsolutePath().normalize()
                    .resolve("server.properties")
                    .toAbsolutePath()
                    .normalize();
            try {
                Path parent = propertiesPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(propertiesPath)) {
                    ensureJmxMonitoringEnabled(installDir);
                    return;
                }
                String defaults = "server-port=" + serverPort + "\n"
                        + "enable-jmx-monitoring=true\n";
                Files.writeString(propertiesPath, defaults, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
                throw e;
            }
        }


        private void downloadWithCurlOrWget(String url, Path target) {
            try {
                Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to prepare download directory: " + ex.getMessage(), ex);
            }
            try {
                runCommand(List.of("curl", "-fL", url, "-o", target.toString()), null,
                        "curl download failed");
                return;
            } catch (Exception ignored) {
                // Fallback for distros without curl installed.
            }
            runCommand(List.of("wget", "--max-redirect=20", "-O", target.toString(), url), null,
                    "wget download failed");
        }

        private String detectPreferredStartJar(Path installDir) {
            try (java.util.stream.Stream<Path> stream = Files.list(installDir)) {
                List<String> jars = stream
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .filter(name -> !name.equalsIgnoreCase("packwiz-installer-bootstrap.jar"))
                        .toList();

                for (String jar : jars) {
                    String lower = jar.toLowerCase(Locale.ROOT);
                    if (lower.contains("fabric") || lower.contains("quilt")) {
                        return jar;
                    }
                }
                return jars.stream()
                        .filter(name -> name.equalsIgnoreCase("server.jar"))
                        .findFirst()
                        .orElse("server.jar");
            } catch (IOException ex) {
                return "server.jar";
            }
        }

        private String safeFileName(String rawName) {
            if (rawName == null || rawName.isBlank()) {
                return "modpack.zip";
            }
            String normalized = Path.of(rawName).getFileName().toString();
            return normalized.isBlank() ? "modpack.zip" : normalized;
        }

        private int parsePortLikeValue(String raw, String field, int min, int max) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(field + " is required.");
            }
            try {
                int value = Integer.parseInt(raw.trim());
                if (value < min || value > max) {
                    throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ".");
                }
                return value;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(field + " must be a number.");
            }
        }

        private void runCommand(List<String> command, Path workingDir, String errorPrefix) {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (workingDir != null) {
                    builder.directory(workingDir.toFile());
                }
                Process process = builder.start();
                String stderr;
                try (InputStream err = process.getErrorStream()) {
                    stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                int exit = process.waitFor();
                if (exit != 0) {
                    String detail = stderr.isBlank() ? "exit code " + exit : stderr;
                    throw new IllegalStateException(errorPrefix + ": " + detail);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(errorPrefix + ": interrupted", ex);
            } catch (IOException ex) {
                throw new IllegalStateException(errorPrefix + ": " + ex.getMessage(), ex);
            }
        }

        private String trimToEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final class ServerDeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long serverId = parseServerId(params.get("id"));
            if (serverId <= 0) {
                serverId = parseServerId(params.get("server_id"));
            }
            if (serverId <= 0) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing required server id.</body></html>");
                return;
            }

            try {
                boolean removed = databaseManager != null && databaseManager.deleteServer(serverId);
                if (removed) {
                    runners.remove(serverId);
                    consoleLinesByServer.remove(serverId);
                    WebActionLogger.log("SERVER_DELETE",
                            "serverId=" + serverId + " by user=" + getSessionUser(exchange) + " ip="
                                    + getClientIp(exchange));
                }
            } catch (Exception ex) {
                logger.warning("Failed to delete server #" + serverId + ": " + ex.getMessage());
            }

            redirect(exchange, "/");
        }
    }

    private final class UsersCreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = trimToEmpty(params.get("username"));
                String password = trimToEmpty(params.get("password"));
                String role = resolveAssignableRole(trimToEmpty(params.get("role")));
                if (username.isBlank() || password.isBlank()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Username and password are required."));
                    return;
                }
                if (password.length() < 6) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Password must be at least 6 characters."));
                    return;
                }
                if (!isKnownAssignableRole(role)) {
                    role = "USER";
                }
                if (databaseManager.getUserByUsername(username).isPresent()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Username already exists."));
                    return;
                }

                databaseManager.createUser(username, WebAuth.hashPassword(password), role);
                WebActionLogger.log("USER_CREATE", "username=" + username + " role=" + role + " by "
                        + getSessionUser(exchange) + " ip=" + getClientIp(exchange));
                redirect(exchange, "/users?message=" + encodeForQuery("User created."));
            } catch (Exception ex) {
                logger.warning("Failed to create user: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to create user.</body></html>");
            }
        }
    }

    private final class UsersUpdateRoleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = trimToEmpty(params.get("username"));
                String role = resolveAssignableRole(trimToEmpty(params.get("role")));
                if (username.isBlank() || role.isBlank()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Username and role are required."));
                    return;
                }
                if (!isKnownAssignableRole(role)) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Invalid role."));
                    return;
                }

                Optional<DatabaseManager.UserRecord> userOpt = databaseManager.getUserByUsername(username);
                if (userOpt.isEmpty()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("User not found."));
                    return;
                }

                long mainAdminId = databaseManager.getMainAdminId().orElse(-1L);
                if (userOpt.get().id() == mainAdminId && !"ADMIN".equals(role)) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Main-Admin role cannot be downgraded."));
                    return;
                }

                databaseManager.updateUserRole(username, role);
                WebActionLogger.log("USER_ROLE_UPDATE", "username=" + username + " role=" + role + " by "
                        + getSessionUser(exchange) + " ip=" + getClientIp(exchange));
                redirect(exchange, "/users?message=" + encodeForQuery("Role updated."));
            } catch (Exception ex) {
                logger.warning("Failed to update user role: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to update role.</body></html>");
            }
        }
    }

    private final class UsersDeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = trimToEmpty(params.get("username"));
                if (username.isBlank()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Username is required."));
                    return;
                }

                Optional<DatabaseManager.UserRecord> userOpt = databaseManager.getUserByUsername(username);
                if (userOpt.isEmpty()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("User not found."));
                    return;
                }

                long mainAdminId = databaseManager.getMainAdminId().orElse(-1L);
                if (userOpt.get().id() == mainAdminId) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Main-Admin cannot be deleted."));
                    return;
                }

                long actorUserId = getSessionUserId(exchange);
                if (userOpt.get().id() == actorUserId) {
                    redirect(exchange, "/users?message=" + encodeForQuery("You cannot delete your own account."));
                    return;
                }

                databaseManager.deleteUserByUsername(username);
                WebActionLogger.log("USER_DELETE", "username=" + username + " by " + getSessionUser(exchange)
                        + " ip=" + getClientIp(exchange));
                redirect(exchange, "/users?message=" + encodeForQuery("User deleted."));
            } catch (Exception ex) {
                logger.warning("Failed to delete user: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to delete user.</body></html>");
            }
        }
    }

    private final class UsersAssignServerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = trimToEmpty(params.get("username"));
                long serverId = parseServerId(params.get("server_id"));

                if (username.isBlank() || serverId <= 0) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Username and server are required."));
                    return;
                }

                Optional<DatabaseManager.UserRecord> userOpt = databaseManager.getUserByUsername(username);
                if (userOpt.isEmpty()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("User not found."));
                    return;
                }
                if (databaseManager.getServerById(serverId).isEmpty()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Server not found."));
                    return;
                }

                databaseManager.assignUserToServer(userOpt.get().id(), serverId);
                WebActionLogger.log("USER_ASSIGN_SERVER",
                        "username=" + username + " serverId=" + serverId + " by "
                                + getSessionUser(exchange) + " ip=" + getClientIp(exchange));
                redirect(exchange, "/users?message=" + encodeForQuery("Server access assigned."));
            } catch (Exception ex) {
                logger.warning("Failed to assign user to server: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to assign server access.</body></html>");
            }
        }
    }

    private final class UsersRevokeServerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = trimToEmpty(params.get("username"));
                long serverId = parseServerId(params.get("server_id"));
                if (username.isBlank() || serverId <= 0) {
                    redirect(exchange, "/users?message=" + encodeForQuery("Username and server are required."));
                    return;
                }

                Optional<DatabaseManager.UserRecord> userOpt = databaseManager.getUserByUsername(username);
                if (userOpt.isEmpty()) {
                    redirect(exchange, "/users?message=" + encodeForQuery("User not found."));
                    return;
                }

                databaseManager.revokeUserFromServer(userOpt.get().id(), serverId);
                WebActionLogger.log("USER_REVOKE_SERVER",
                        "username=" + username + " serverId=" + serverId + " by " + getSessionUser(exchange)
                                + " ip=" + getClientIp(exchange));
                redirect(exchange, "/users?message=" + encodeForQuery("Server access revoked."));
            } catch (Exception ex) {
                logger.warning("Failed to revoke user server access: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to revoke server access.</body></html>");
            }
        }
    }

    private final class PermissionsCreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                    return;
                }
                if (!ensureAuthenticated(exchange, false)) {
                    return;
                }
                if (!isAdminSession(exchange)) {
                    sendResponseWithStatus(exchange, 403,
                            "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                    return;
                }

                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String roleName = trimToEmpty(params.get("role_name"));
                String preset = trimToEmpty(params.get("preset")).toUpperCase(Locale.ROOT);
                int roleValue = "ADMIN".equals(preset) ? 900 : 200;

                if (roleName.isBlank()) {
                    redirect(exchange, "/permissions?message=" + encodeForQuery("Role name is required."));
                    return;
                }

                Optional<DatabaseManager.UserRecord> actor = databaseManager.getUserById(getSessionUserId(exchange));
                if (actor.isEmpty()) {
                    sendResponseWithStatus(exchange, 403,
                            "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                    return;
                }

                Map<String, Integer> roleValues = databaseManager.getRoleValuesMap();
                int actorRoleValue = roleValues.getOrDefault(
                        trimToEmpty(actor.get().globalRole()).toUpperCase(Locale.ROOT),
                        "ADMIN".equalsIgnoreCase(actor.get().globalRole()) ? 1000 : 100);
                if (roleValue >= actorRoleValue) {
                    redirect(exchange, "/permissions?message=" + encodeForQuery(
                            "Hierarchy violation: You cannot modify users/roles equal to or higher than your own rank"));
                    return;
                }

                Set<String> defaults = new java.util.LinkedHashSet<>();
                defaults.add("dash.web.stats.read");
                defaults.add("dash.web.audit.read");
                if ("ADMIN".equals(preset)) {
                    defaults.add("*");
                }

                boolean created = databaseManager.createRole(roleName, roleValue, defaults);
                if (!created) {
                    redirect(exchange, "/permissions?message=" + encodeForQuery("Invalid or duplicate role name."));
                    return;
                }

                WebActionLogger.log("ROLE_CREATE", "role=" + roleName + " preset=" + preset + " by "
                        + getSessionUser(exchange) + " ip=" + getClientIp(exchange));
                redirect(exchange, "/permissions?role=" + encodeForQuery(roleName)
                        + "&message=" + encodeForQuery("Role created successfully."));
            } catch (Exception ex) {
                ex.printStackTrace();
                logger.warning("Failed to create role: " + ex.getMessage());
                String details = trimToEmpty(ex.getMessage());
                if (details.isBlank()) {
                    details = ex.getClass().getSimpleName();
                }
                redirect(exchange, "/permissions?message=" + encodeForQuery("Failed to create role: " + details));
            }
        }
    }

    private final class PermissionsUpdateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String role = trimToEmpty(params.get("role"));
                if (role.isBlank()) {
                    redirect(exchange, "/permissions?message=" + encodeForQuery("Role is required."));
                    return;
                }

                String valueRaw = trimToEmpty(params.get("value"));
                if (!valueRaw.isBlank()) {
                    int roleValue = Integer.parseInt(valueRaw);
                    databaseManager.updateRoleValue(role, roleValue);
                }

                List<String> addPermissions = splitCsv(params.get("add_permissions"));
                List<String> removePermissions = splitCsv(params.get("remove_permissions"));
                databaseManager.updateRolePermissions(role, addPermissions, removePermissions);

                WebActionLogger.log("ROLE_UPDATE", "role=" + role + " by " + getSessionUser(exchange) + " ip="
                        + getClientIp(exchange));
                redirect(exchange, "/permissions?role=" + encodeForQuery(role)
                        + "&message=" + encodeForQuery("Role updated successfully."));
            } catch (Exception ex) {
                logger.warning("Failed to update role: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to update role.</body></html>");
            }
        }
    }

    private final class PermissionsDeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            try {
                Map<String, String> params = parseFormData(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String role = trimToEmpty(params.get("role"));
                if (role.isBlank()) {
                    redirect(exchange, "/permissions?message=" + encodeForQuery("Role is required."));
                    return;
                }

                boolean deleted = databaseManager.deleteRole(role);
                if (!deleted) {
                    redirect(exchange, "/permissions?role=" + encodeForQuery(role)
                            + "&message=" + encodeForQuery("Cannot delete this role."));
                    return;
                }

                WebActionLogger.log("ROLE_DELETE", "role=" + role + " by " + getSessionUser(exchange) + " ip="
                        + getClientIp(exchange));
                redirect(exchange, "/permissions?message=" + encodeForQuery("Role deleted successfully."));
            } catch (Exception ex) {
                logger.warning("Failed to delete role: " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to delete role.</body></html>");
            }
        }
    }

    private final class ServerActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long serverId = parseServerId(params.get("server_id"));
            if (serverId <= 0) {
                serverId = parseServerId(params.get("id"));
            }
            String action = params.getOrDefault("action", "").trim().toLowerCase(Locale.ROOT);

            if (serverId <= 0 || action.isBlank()) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing server_id or action.</body></html>");
                return;
            }
            if (!ensureServerAccess(exchange, serverId, false)) {
                return;
            }

            if (!"start".equals(action)) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Unsupported action.</body></html>");
                return;
            }
            if (!ensureOfflinePermission(exchange, serverId, "start")) {
                return;
            }

            Optional<DatabaseManager.ServerRecord> serverOpt = databaseManager.getServerById(serverId);
            if (serverOpt.isEmpty()) {
                sendResponseWithStatus(exchange, 404,
                        "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                return;
            }

            DatabaseManager.ServerRecord server = serverOpt.get();
            if (server.startCommand() == null || server.startCommand().isBlank()) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Start command is not configured.</body></html>");
                return;
            }

            Path workingDir;
            try {
                workingDir = Path.of(server.pathToDir()).toAbsolutePath().normalize();
            } catch (Exception ex) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Invalid server working directory.</body></html>");
                return;
            }
            if (!Files.isDirectory(workingDir)) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Server working directory does not exist.</body></html>");
                return;
            }

            try {
                ensureJmxMonitoringEnabled(workingDir);
                rewriteStartScriptForBoot(workingDir);
                ServerProcessRunner serverRunner = getRunnerForServer(server);
                serverRunner.start();
                WebActionLogger.log("SERVER_START", "serverId=" + serverId + " action=start by user="
                        + getSessionUser(exchange) + " ip=" + getClientIp(exchange));
            } catch (Exception ex) {
                logger.warning("Failed to start server #" + serverId + ": " + ex.getMessage());
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to start server.</body></html>");
                return;
            }

            redirect(exchange, "/server?id=" + serverId);
        }
    }

    private List<String> buildStartShellCommand(String startCommand) {
        String command = startCommand == null ? "" : startCommand.trim();
        if (command.isBlank()) {
            return List.of();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("sh", "-lc", command);
    }

    private boolean isAdminSession(HttpExchange exchange) {
        long userId = getSessionUserId(exchange);
        if (userId <= 0 || databaseManager == null) {
            return false;
        }
        return databaseManager.getUserById(userId)
                .map(user -> "ADMIN".equalsIgnoreCase(user.globalRole()))
                .orElse(false);
    }

    private boolean hasOfflinePermission(HttpExchange exchange, long serverId, String capability) {
        long userId = getSessionUserId(exchange);
        if (userId <= 0 || databaseManager == null) {
            return false;
        }
        return switch (capability) {
            case "start" -> databaseManager.hasOfflineStartPermission(userId, serverId);
            case "files" -> databaseManager.hasOfflineFilesPermission(userId, serverId);
            case "properties" -> databaseManager.hasOfflinePropertiesPermission(userId, serverId);
            default -> false;
        };
    }

    private boolean ensureOfflinePermission(HttpExchange exchange, long serverId, String capability) throws IOException {
        if (hasOfflinePermission(exchange, serverId, capability)) {
            return true;
        }
        sendResponseWithStatus(exchange, 403,
                "<html><body style='background:#0b1020;color:#fff'>Forbidden. Missing offline permission: "
                        + escapeHtml(capability) + ".</body></html>");
        return false;
    }

    private boolean ensureServerSettingsPermission(HttpExchange exchange, long serverId, boolean jsonResponse)
            throws IOException {
        long userId = getSessionUserId(exchange);
        if (userId <= 0 || databaseManager == null) {
            if (jsonResponse) {
                sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Forbidden\"}");
            } else {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Forbidden. Missing server settings permission.</body></html>");
            }
            return false;
        }
        boolean isMainAdmin = databaseManager.getMainAdminId().map(id -> id == userId).orElse(false);
        if (isMainAdmin || databaseManager.hasOfflineServerSettingsPermission(userId, serverId)) {
            return true;
        }
        if (jsonResponse) {
            sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Forbidden\"}");
        } else {
            sendResponseWithStatus(exchange, 403,
                    "<html><body style='background:#0b1020;color:#fff'>Forbidden. Missing server settings permission.</body></html>");
        }
        return false;
    }

    private final class ServerFileEditHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
                return;
            }
            sendResponseWithStatus(exchange, 405, "Method Not Allowed");
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            long serverId = serverIdFromQuery(exchange);
            if (serverId <= 0) {
                sendMissingRequiredId(exchange, "ServerFileEditHandler", null);
                return;
            }
            if (!ensureServerAccess(exchange, serverId, false)) {
                return;
            }
            if (!ensureOfflinePermission(exchange, serverId, "files")) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendResponseWithStatus(exchange, 404,
                        "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                return;
            }

            String relPath = getQueryParam(exchange.getRequestURI().getQuery(), "file");
            if (relPath == null || relPath.isBlank()) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing required file parameter.</body></html>");
                return;
            }

            Path file = resolveSafePath(contextOpt.get().rootPath(), relPath);
            if (file == null) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Forbidden file path.</body></html>");
                return;
            }
            if (Files.isDirectory(file)) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Cannot edit directories.</body></html>");
                return;
            }

            String content = "";
            try {
                if (Files.exists(file)) {
                    content = Files.readString(file, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
            }

            String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Edit File</title>"
                    + "<style>body{font-family:system-ui;background:#0b1020;color:#e2e8f0;margin:0;padding:16px}textarea{width:100%;height:72vh;background:#050b18;color:#e2e8f0;border:1px solid #334155;border-radius:10px;padding:12px;font-family:ui-monospace,monospace;font-size:13px}button{padding:8px 12px;border-radius:8px;border:1px solid #14b8a6;background:#14b8a6;color:#001015;font-weight:600}a{color:#93c5fd}</style></head><body>"
                    + "<p><a href='/server?id=" + serverId + "'>Back to server</a></p>"
                    + "<h3>Edit <code>" + escapeHtml(relPath) + "</code></h3>"
                    + "<form method='post' action='/server/files/edit'>"
                    + "<input type='hidden' name='id' value='" + serverId + "'>"
                    + "<input type='hidden' name='file' value='" + escapeHtml(relPath) + "'>"
                    + "<textarea name='content'>" + escapeHtml(content) + "</textarea><br><br>"
                    + "<button type='submit'>Save File</button>"
                    + "</form></body></html>";
            sendResponse(exchange, html);
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long serverId = serverIdFromParams(params);
            if (serverId <= 0) {
                serverId = serverIdFromQuery(exchange);
            }
            if (serverId <= 0) {
                sendMissingRequiredId(exchange, "ServerFileEditHandler", params);
                return;
            }
            if (!ensureServerAccess(exchange, serverId, false)) {
                return;
            }
            if (!ensureOfflinePermission(exchange, serverId, "files")) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendResponseWithStatus(exchange, 404,
                        "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                return;
            }

            String relPath = params.get("file");
            String content = params.get("content");
            if (relPath == null || relPath.isBlank() || content == null) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing required parameters.</body></html>");
                return;
            }

            Path file = resolveSafePath(contextOpt.get().rootPath(), relPath);
            if (file == null) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Forbidden file path.</body></html>");
                return;
            }
            if (isProtectedLockFile(file)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Protected lock file.</body></html>");
                return;
            }
            if (Files.isDirectory(file)) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Cannot save directory content.</body></html>");
                return;
            }

            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
                WebActionLogger.log("FILE_RESCUE_EDIT",
                        "serverId=" + serverId + " path=" + relPath + " by " + getSessionUser(exchange)
                                + " from " + getClientIp(exchange));
                redirect(exchange, "/server?id=" + serverId);
            } catch (IOException ex) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to save file.</body></html>");
            }
        }
    }

    private final class ServerFileDeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long serverId = serverIdFromParams(params);
            if (serverId <= 0) {
                serverId = serverIdFromQuery(exchange);
            }
            if (serverId <= 0) {
                sendMissingRequiredId(exchange, "ServerFileDeleteHandler", params);
                return;
            }
            if (!ensureServerAccess(exchange, serverId, false)) {
                return;
            }
            if (!ensureOfflinePermission(exchange, serverId, "files")) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendResponseWithStatus(exchange, 404,
                        "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                return;
            }

            String relPath = params.get("file");
            if (relPath == null || relPath.isBlank()) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing required file parameter.</body></html>");
                return;
            }

            Path rootPath = contextOpt.get().rootPath();
            Path target = resolveSafePath(rootPath, relPath);
            if (target == null) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Forbidden file path.</body></html>");
                return;
            }
            if (target.equals(rootPath)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Cannot delete server root.</body></html>");
                return;
            }
            if (isProtectedLockFile(target)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Protected lock file.</body></html>");
                return;
            }
            if (!Files.exists(target)) {
                redirect(exchange, "/server?id=" + serverId);
                return;
            }

            try {
                if (Files.isDirectory(target)) {
                    try (var walk = Files.walk(target)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                            try {
                                if (!path.equals(rootPath) && !isProtectedLockFile(path)) {
                                    Files.deleteIfExists(path);
                                }
                            } catch (IOException ignored) {
                            }
                        });
                    }
                } else {
                    Files.deleteIfExists(target);
                }
                WebActionLogger.log("FILE_RESCUE_DELETE",
                        "serverId=" + serverId + " path=" + relPath + " by " + getSessionUser(exchange)
                                + " from " + getClientIp(exchange));
                redirect(exchange, "/server?id=" + serverId);
            } catch (IOException ex) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to delete path.</body></html>");
            }
        }
    }

    private final class ServerPropertiesSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Admin access required.</body></html>");
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            long serverId = parseServerId(params.get("server_id"));
            if (serverId <= 0) {
                serverId = serverIdFromParams(params);
            }
            if (serverId <= 0) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing required server_id.</body></html>");
                return;
            }
            if (!ensureServerAccess(exchange, serverId, false)) {
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendResponseWithStatus(exchange, 404,
                        "<html><body style='background:#0b1020;color:#fff'>Server not found.</body></html>");
                return;
            }

            Path propertiesFile = resolveSafePath(contextOpt.get().rootPath(), "server.properties");
            if (propertiesFile == null) {
                sendResponseWithStatus(exchange, 403,
                        "<html><body style='background:#0b1020;color:#fff'>Forbidden properties path.</body></html>");
                return;
            }

            Map<String, String> submitted = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String rawKey = entry.getKey();
                if (rawKey == null || !rawKey.startsWith("prop.")) {
                    continue;
                }
                String key = sanitizePropertyKey(rawKey.substring("prop.".length()));
                if (key.isBlank()) {
                    continue;
                }
                submitted.put(key, sanitizePropertyValue(entry.getValue()));
            }

            try {
                Path parent = propertiesFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                List<String> existingLines = Files.exists(propertiesFile)
                        ? Files.readAllLines(propertiesFile, StandardCharsets.UTF_8)
                        : List.of();

                Map<String, String> pending = new LinkedHashMap<>(submitted);
                List<String> merged = new ArrayList<>();

                for (String line : existingLines) {
                    String parsedKey = extractPropertyKey(line);
                    if (parsedKey == null) {
                        merged.add(line);
                        continue;
                    }
                    if (pending.containsKey(parsedKey)) {
                        merged.add(parsedKey + "=" + pending.remove(parsedKey));
                    } else {
                        merged.add(line);
                    }
                }

                for (Map.Entry<String, String> entry : pending.entrySet()) {
                    merged.add(entry.getKey() + "=" + entry.getValue());
                }

                String newContents = String.join("\n", merged);
                if (!merged.isEmpty()) {
                    newContents += "\n";
                }
                Files.writeString(propertiesFile, newContents, StandardCharsets.UTF_8);

                WebActionLogger.log("SERVER_PROPERTIES_SAVE",
                        "serverId=" + serverId + " keys=" + submitted.size() + " by " + getSessionUser(exchange)
                                + " from " + getClientIp(exchange));
                redirect(exchange, "/server?id=" + serverId);
            } catch (IOException ex) {
                sendResponseWithStatus(exchange, 500,
                        "<html><body style='background:#0b1020;color:#fff'>Failed to save server.properties.</body></html>");
            }
        }

        private String extractPropertyKey(String line) {
            if (line == null) {
                return null;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                return null;
            }

            int separator = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '=' || c == ':') {
                    separator = i;
                    break;
                }
            }
            if (separator <= 0) {
                return null;
            }

            String key = line.substring(0, separator).trim();
            return sanitizePropertyKey(key);
        }

        private String sanitizePropertyKey(String key) {
            if (key == null) {
                return "";
            }
            String sanitized = key.trim();
            if (sanitized.isEmpty() || sanitized.contains("\n") || sanitized.contains("\r") || sanitized.contains("=")
                    || sanitized.contains(":")) {
                return "";
            }
            return sanitized;
        }

        private String sanitizePropertyValue(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\r", "").replace("\n", " ");
        }
    }

    private final class StatsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long serverId = serverIdFromQuery(exchange);
            if (serverId <= 0) {
                sendResponseWithStatus(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }
            if (!ensureServerAccess(exchange, serverId, true)) {
                return;
            }

            long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
            long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
            long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
            long usedMem = totalMem - freeMem;
            long uptimeSec = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
            long hours = uptimeSec / 3600;
            long mins = (uptimeSec % 3600) / 60;

            double cpuPercent = 0.0;
            try {
                java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory
                        .getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                    // Use whole-system CPU load, not per-process load.
                    double load = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                    cpuPercent = (load < 0.0) ? 0.0 : (load * 100.0);
                }
            } catch (Exception ignored) {
            }

            String json = String.format(Locale.ROOT,
                    "{\"tps\":%.2f,\"ram_used\":%d,\"ram_max\":%d,\"uptime\":\"%dh %dm\",\"cpuUsage\":%.2f}",
                    20.0,
                    usedMem,
                    maxMem,
                    hours,
                    mins,
                    cpuPercent);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponseWithStatus(exchange, 200, json);
        }
    }

    private final class ServersApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }
            if (!ensureAuthenticated(exchange, true)) {
                return;
            }
            if (databaseManager == null) {
                sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"Database unavailable\"}");
                return;
            }

            long userId = getSessionUserId(exchange);
            List<DatabaseManager.ServerRecord> servers = userId <= 0 ? List.of() : databaseManager.getServersForUser(userId);
            int onlineCount = 0;
            StringBuilder serverRows = new StringBuilder();

            boolean first = true;
            for (DatabaseManager.ServerRecord server : servers) {
                NativeStatusData nativeData = collectNativeStatusData(server.id(), server);
                ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(server.id(), server);
                boolean online = nativeData.online() || snapshot.online();
                if (online) {
                    onlineCount++;
                }

                if (!first) {
                    serverRows.append(',');
                }
                first = false;
                serverRows.append("{\"id\":").append(server.id())
                        .append(",\"name\":\"").append(escapeJson(server.name())).append("\"")
                        .append(",\"usePluginInterface\":").append(server.usePluginInterface())
                        .append(",\"online\":").append(online)
                        .append(",\"status\":\"").append(online ? "online" : "offline").append("\"")
                        .append(",\"tps\":").append(String.format(Locale.ROOT, "%.2f", nativeData.tps()))
                        .append(",\"cpu\":").append(String.format(Locale.ROOT, "%.2f", nativeData.cpu()))
                        .append(",\"ramUsed\":").append(nativeData.ramMb())
                        .append(",\"ramMax\":").append(Math.max(nativeData.maxRamObserved(), snapshot.ramMaxMb()))
                        .append('}');
            }
            String json = "{\"success\":true,\"onlineCount\":" + onlineCount
                    + ",\"total\":" + servers.size()
                    + ",\"servers\":[" + serverRows + "]}";
            sendJsonResponse(exchange, 200, json);
        }
    }

    private final class InstallProgressApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }
            if (!ensureAuthenticated(exchange, true)) {
                return;
            }

            long userId = getSessionUserId(exchange);
            List<String> lines = installProgressByUser.getOrDefault(userId, List.of());
            StringBuilder json = new StringBuilder("{\"success\":true,\"lines\":[");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(escapeJson(lines.get(i))).append('"');
            }
            json.append("]}");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponseWithStatus(exchange, 200, json.toString());
        }
    }

    private final class RestartApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                sendResponseWithStatus(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }
            if (!ensureAuthenticated(exchange, true)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Forbidden\"}");
                return;
            }
            if (!GithubUpdater.UPDATE_READY) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                sendResponseWithStatus(exchange, 409,
                        "{\"success\":false,\"error\":\"Restart is only available after update download.\"}");
                return;
            }

            logger.info("[System] Restart requested via Web-UI.");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponseWithStatus(exchange, 200, "{\"success\":true}");
            scheduleRestartExit();
        }
    }

    private final class UpdatesDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                redirect(exchange, "/updates?error=" + encodeForQuery("Only admins can download updates."));
                return;
            }

            GithubUpdater updater = NeoDash.getGithubUpdater();
            if (updater == null) {
                redirect(exchange, "/updates?error=" + encodeForQuery("Updater is unavailable."));
                return;
            }

            GithubUpdater.DownloadResult result = updater.downloadLatestNeoDashUpdate(resolveCurrentNeoDashVersion());
            if (result.downloaded()) {
                redirect(exchange, "/updates?message=" + encodeForQuery(result.message()));
            } else {
                redirect(exchange, "/updates?error=" + encodeForQuery(result.message()));
            }
        }
    }

    private final class UpdatesApplyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!ensureAuthenticated(exchange, false)) {
                return;
            }
            if (!isAdminSession(exchange)) {
                redirect(exchange, "/updates?error=" + encodeForQuery("Only admins can apply updates."));
                return;
            }
            if (!GithubUpdater.UPDATE_READY) {
                redirect(exchange, "/updates?error=" + encodeForQuery("No downloaded update is ready to apply."));
                return;
            }

            logger.info("[System] Restart requested via Web-UI.");
            sendResponseWithStatus(exchange, 200,
                    "<html><body style='background:#0b1020;color:#e2e8f0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h2>Restarting NeoDash...</h2><p>Please reload this page in a few seconds.</p></div></body></html>");
            scheduleRestartExit();
        }
    }

    private void scheduleRestartExit() {
        scheduler.schedule(() -> System.exit(0), 1, TimeUnit.SECONDS);
    }

    private final class MetricsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMetricsJson(exchange, 405, Map.of("success", false, "error", "Method Not Allowed"));
                return;
            }

            long serverId = serverIdFromQuery(exchange);
            if (serverId <= 0) {
                sendMetricsJson(exchange, 400, Map.of("success", false, "error", "Missing id"));
                return;
            }
            if (!ensureServerAccess(exchange, serverId, true)) {
                return;
            }

            Optional<DatabaseManager.ServerRecord> serverOpt = databaseManager.getServerById(serverId);
            if (serverOpt.isEmpty()) {
                sendMetricsJson(exchange, 404, Map.of("success", false, "error", "Server not found"));
                return;
            }

            DatabaseManager.ServerRecord server = serverOpt.get();
            ServerStateCache.ServerStateSnapshot snapshot = ServerStateCache.getSnapshot(serverId, server);
            double cpu = Math.max(0.0d, snapshot.cpuUsage());
            boolean processRunning = isLocalServerProcessRunning(server);
            boolean tcpReachable = isTcpPortReachable("127.0.0.1", server.port(), 1000);
            MetricsCache.pushSample(serverId, cpu, snapshot.ramUsedMb());

            PlayerCounts players = fetchPlayerCounts(server);
            Map<String, Object> payload = new HashMap<>();
            payload.put("tps", snapshot.tps());
            payload.put("cpu", cpu);
            payload.put("ramUsed", snapshot.ramUsedMb());
            payload.put("ramMax", snapshot.ramMaxMb());
            payload.put("uptime", snapshot.uptime());
            payload.put("dashVersion", snapshot.dashVersion());
            payload.put("playersOnline", players.online());
            payload.put("playersMax", players.max());
            payload.put("processRunning", processRunning);
            payload.put("bridgeConnected", snapshot.online());
            payload.put("socketOnline", tcpReachable);
            payload.put("online", snapshot.online() || tcpReachable);
            payload.put("status", (snapshot.online() || tcpReachable) ? "online" : "offline");

            sendMetricsJson(exchange, 200, payload);
        }

        private void sendMetricsJson(HttpExchange exchange, int status, Object payload) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponseWithStatus(exchange, status, gson.toJson(payload));
        }
    }

    private PlayerCounts fetchPlayerCounts(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return new PlayerCounts(0, 0);
        }
        try {
            BridgeApiClient.BridgeResponse response = bridgeApiClient.get(server, "players").join();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new PlayerCounts(0, 0);
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return new PlayerCounts(0, 0);
            }

            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                return new PlayerCounts(array.size(), 0);
            }
            if (!root.isJsonObject()) {
                return new PlayerCounts(0, 0);
            }

            JsonObject object = root.getAsJsonObject();
            int playersOnline = firstInt(object,
                    "playersOnline", "online", "count", "current", "onlinePlayers");
            int playersMax = firstInt(object,
                    "playersMax", "maxPlayers", "max", "limit", "capacity");

            if (playersOnline <= 0 && object.has("players") && object.get("players").isJsonArray()) {
                playersOnline = object.getAsJsonArray("players").size();
            }
            if (playersOnline < 0) {
                playersOnline = 0;
            }
            if (playersMax < 0) {
                playersMax = 0;
            }
            return new PlayerCounts(playersOnline, playersMax);
        } catch (Exception ignored) {
            return new PlayerCounts(0, 0);
        }
    }

    private int firstInt(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                continue;
            }
            try {
                JsonElement value = object.get(key);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    return value.getAsInt();
                }
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    return Integer.parseInt(value.getAsString().trim());
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private boolean isLocalServerProcessRunning(DatabaseManager.ServerRecord server) {
        if (server == null) {
            return false;
        }
        String runnerType = trimToEmpty(server.runnerType()).toUpperCase(Locale.ROOT);
        if (!"SCREEN".equals(runnerType)) {
            return false;
        }
        return isScreenSessionRunning(String.valueOf(server.id()));
    }

    private boolean isScreenSessionRunning(String sessionName) {
        if (sessionName == null || sessionName.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("screen", "-ls")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor(2, TimeUnit.SECONDS);
            Pattern p = Pattern.compile("\\\\." + Pattern.quote(sessionName) + "\\\\b");
            return p.matcher(output).find();
        } catch (Exception ex) {
            return false;
        }
    }

    private double estimateCpuPercent(double tps, boolean online) {
        if (!online) {
            return 0.0d;
        }
        double clampedTps = Math.max(0.0d, Math.min(20.0d, tps));
        return Math.max(0.0d, Math.min(100.0d, ((20.0d - clampedTps) / 20.0d) * 100.0d));
    }

    private record PlayerCounts(int online, int max) {
    }

    private final class FileSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "{\"success\":false}");
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long serverId = serverIdFromParams(params);
            if (serverId <= 0) {
                serverId = serverIdFromQuery(exchange);
            }
            if (serverId <= 0) {
                sendResponseWithStatus(exchange, 400, "{\"success\":false,\"error\":\"Missing id\"}");
                return;
            }
            if (!ensureServerCapability(exchange, serverId, false, false, true, true)) {
                return;
            }

            String relPath = params.get("path");
            String content = params.get("content");
            if (relPath == null || content == null) {
                sendResponseWithStatus(exchange, 400, "{\"success\":false,\"error\":\"Missing parameters\"}");
                return;
            }

            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                sendResponseWithStatus(exchange, 404, "{\"success\":false,\"error\":\"Server not found\"}");
                return;
            }
            Path file = resolveSafePath(contextOpt.get().rootPath(), relPath);
            if (file == null) {
                sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Access denied\"}");
                return;
            }
            if (isProtectedLockFile(file)) {
                sendResponseWithStatus(exchange, 403, "{\"success\":false,\"error\":\"Protected lock file\"}");
                return;
            }

            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(file)) {
                    Files.copy(file, Path.of(file + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
                WebActionLogger.logFileEdit(relPath, getClientIp(exchange));
                sendResponse(exchange, "{\"success\":true}");
            } catch (Exception e) {
                sendResponseWithStatus(exchange, 500,
                        "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private final class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponseWithStatus(exchange, 405, "");
                return;
            }

            Map<String, String> params = parseFormData(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String action = params.get("action");
            String clientIp = getClientIp(exchange);

            if ("login".equals(action)) {
                String username = params.getOrDefault("username", "");
                String password = params.getOrDefault("password", "");
                Optional<WebAuth.AuthResult> authResult = auth == null
                        ? Optional.empty()
                        : auth.authenticate(username, password);
                if (authResult.isPresent()) {
                    setSession(exchange, authResult.get().userId(), username);
                    warmServerStatesForUser(authResult.get().userId());
                    WebActionLogger.logLogin(username, clientIp);
                    redirect(exchange, "/");
                    return;
                }
                WebActionLogger.log("LOGIN_FAILED", "User '" + username + "' failed from " + clientIp);
                sendResponse(exchange, renderLoginPageWithError("Invalid credentials"));
                return;
            }

            if ("logout".equals(action)) {
                cleanupKnownBridgeSessions(getSessionUserId(exchange));
                clearSession(exchange);
                WebActionLogger.logLogout(clientIp);
                redirect(exchange, "/login");
                return;
            }

            if (!ensureAuthenticated(exchange, false)) {
                return;
            }

            long serverId = serverIdFromParams(params);
            if (serverId <= 0) {
                String referer = exchange.getRequestHeaders().getFirst("Referer");
                if (referer != null && referer.contains("id=")) {
                    String after = referer.substring(referer.indexOf("id=") + 3);
                    String idRaw = after.contains("&") ? after.substring(0, after.indexOf('&')) : after;
                    serverId = parseServerId(idRaw);
                }
            }

            if (serverId <= 0 && !"logout".equals(action)) {
                sendResponseWithStatus(exchange, 400,
                        "<html><body style='background:#0b1020;color:#fff'>Missing id parameter.</body></html>");
                return;
            }

            if (serverId > 0 && !ensureServerAccess(exchange, serverId, false)) {
                return;
            }

            switch (action == null ? "" : action) {
                case "command" -> {
                    if (!ensureServerCapability(exchange, serverId, false, true, false, false)) {
                        return;
                    }
                    String cmd = params.get("cmd");
                    if (cmd != null && !cmd.isBlank()) {
                        long finalServerId = serverId;
                        actionExecutor.submit(() -> runRunnerAction(finalServerId,
                                runner -> runner.sendCommand(cmd), "COMMAND", cmd, clientIp));
                    }
                }
                case "start" -> {
                    if (!ensureServerCapability(exchange, serverId, true, false, false, false)) {
                        return;
                    }
                    long finalServerId = serverId;
                    actionExecutor.submit(
                            () -> runRunnerAction(finalServerId, ServerProcessRunner::start, "START", "start issued", clientIp));
                }
                case "stop" -> {
                    if (!ensureServerCapability(exchange, serverId, true, false, false, false)) {
                        return;
                    }
                    long finalServerId = serverId;
                    actionExecutor.submit(
                            () -> runRunnerAction(finalServerId, ServerProcessRunner::stop, "STOP", "stop issued", clientIp));
                }
                case "kill" -> {
                    if (!ensureServerCapability(exchange, serverId, true, false, false, false)) {
                        return;
                    }
                    long finalServerId = serverId;
                    actionExecutor.submit(
                            () -> runRunnerAction(finalServerId, ServerProcessRunner::kill, "KILL", "kill issued", clientIp));
                }
                case "file_delete" -> {
                    if (!ensureServerCapability(exchange, serverId, false, false, true, false)) {
                        return;
                    }
                    String relPath = params.get("path");
                    if (relPath != null && !relPath.isBlank()) {
                        long finalServerId = serverId;
                        actionExecutor.submit(() -> deleteFileOrDirectory(finalServerId, relPath, clientIp));
                    }
                }
                case "file_rename" -> {
                    if (!ensureServerCapability(exchange, serverId, false, false, true, false)) {
                        return;
                    }
                    String relPath = params.get("path");
                    String newName = params.get("new_name");
                    if (relPath != null && newName != null) {
                        long finalServerId = serverId;
                        actionExecutor.submit(() -> renamePath(finalServerId, relPath, newName, clientIp));
                    }
                }
                default -> appendConsoleLine(serverId, "[NeoDash] Unsupported action: " + action);
            }

            String referer = exchange.getRequestHeaders().getFirst("Referer");
            redirect(exchange, referer == null || referer.isBlank() ? "/" : referer);
        }
    }

    @FunctionalInterface
    private interface ServerIOAction {
        void run(ServerProcessRunner runner) throws Exception;
    }

    private void runRunnerAction(long serverId, ServerIOAction action, String logAction, String detail, String clientIp) {
        try {
            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty() || contextOpt.get().runner() == null) {
                appendConsoleLine(serverId, "[NeoDash] No process runner configured for server #" + serverId);
                return;
            }
            action.run(contextOpt.get().runner());
            WebActionLogger.log(logAction, "serverId=" + serverId + " " + detail + " from " + clientIp);
        } catch (Exception e) {
            appendConsoleLine(serverId, "[NeoDash] Runner action failed: " + e.getMessage());
            WebActionLogger.log(logAction + "_FAILED",
                    "serverId=" + serverId + " " + detail + " from " + clientIp + " error=" + e.getMessage());
        }
    }

    private void startLogPumpForServer(long serverId) {
        consoleLinesByServer.computeIfAbsent(serverId, id -> new ArrayDeque<>());
        // Idempotent enough for now: multiple consumers simply keep one live follower per server in practice.
        if (Boolean.TRUE.equals(logPumpStarted.putIfAbsent(serverId, true))) {
            return;
        }
        actionExecutor.submit(() -> {
            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty() || contextOpt.get().runner() == null) {
                appendConsoleLine(serverId, "[NeoDash] Runner unavailable for server #" + serverId);
                return;
            }
            try (InputStream in = contextOpt.get().runner().getLogStream()) {
                StringBuilder lineBuffer = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    char c = (char) b;
                    if (c == '\n') {
                        appendConsoleLine(serverId, lineBuffer.toString());
                        lineBuffer.setLength(0);
                    } else if (c != '\r') {
                        lineBuffer.append(c);
                    }
                }
            } catch (Exception e) {
                appendConsoleLine(serverId, "[NeoDash] Log stream ended: " + e.getMessage());
            }
        });
    }

    private final Map<Long, Boolean> logPumpStarted = new ConcurrentHashMap<>();

    private void appendConsoleLine(long serverId, String line) {
        String safe = line == null ? "" : line;
        Deque<String> queue = consoleLinesByServer.computeIfAbsent(serverId, id -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(safe);
            while (queue.size() > MAX_CONSOLE_LINES) {
                queue.removeFirst();
            }
        }
    }

    private String getConsoleOutput(long serverId) {
        Deque<String> queue = consoleLinesByServer.computeIfAbsent(serverId, id -> new ArrayDeque<>());
        synchronized (queue) {
            return String.join("\n", queue);
        }
    }

    private void deleteFileOrDirectory(long serverId, String relPath, String clientIp) {
        try {
            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                return;
            }
            Path target = resolveSafePath(contextOpt.get().rootPath(), relPath);
            if (target == null || isProtectedLockFile(target) || !Files.exists(target)) {
                return;
            }
            if (Files.isDirectory(target)) {
                try (var walk = Files.walk(target)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            if (!isProtectedLockFile(path)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(target);
            }
            WebActionLogger.log("FILE_DELETE", "serverId=" + serverId + " path=" + relPath + " from " + clientIp);
        } catch (Exception ignored) {
        }
    }

    private void renamePath(long serverId, String relPath, String newName, String clientIp) {
        try {
            Optional<ServerContext> contextOpt = resolveServerContext(serverId);
            if (contextOpt.isEmpty()) {
                return;
            }
            String sanitized = newName.trim();
            if (sanitized.isBlank() || sanitized.contains("/") || sanitized.contains("\\") || sanitized.contains("..")) {
                return;
            }
            Path source = resolveSafePath(contextOpt.get().rootPath(), relPath);
            if (source == null || !Files.exists(source) || isProtectedLockFile(source)) {
                return;
            }
            Path parent = source.getParent();
            if (parent == null) {
                return;
            }
            Path target = parent.resolve(sanitized).normalize();
            if (!target.startsWith(contextOpt.get().rootPath())) {
                return;
            }
            Files.move(source, target);
            WebActionLogger.log("FILE_RENAME",
                    "serverId=" + serverId + " path=" + relPath + " to=" + sanitized + " from " + clientIp);
        } catch (Exception ignored) {
        }
    }

    private Path resolveSafePath(Path rootPath, String relativePath) {
        String rel = (relativePath == null ? "" : relativePath).replace('\\', '/');
        if (rel.contains("..")) {
            return null;
        }
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path candidate = rootPath.resolve(rel).normalize();
        if (!candidate.startsWith(rootPath)) {
            return null;
        }
        return candidate;
    }

    private boolean isProtectedLockFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return "session.lock".equals(name)
                || name.endsWith(".lock")
                || name.endsWith(".lck")
                || name.endsWith(".pid");
    }

    private void ensureJmxMonitoringEnabled(Path serverDir) {
        if (serverDir == null) {
            return;
        }
        Path propertiesPath = serverDir.toAbsolutePath().normalize().resolve("server.properties");
        try {
            Path parent = propertiesPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<String> lines = Files.exists(propertiesPath)
                    ? new ArrayList<>(Files.readAllLines(propertiesPath, StandardCharsets.UTF_8))
                    : new ArrayList<>();

            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("enable-jmx-monitoring=")) {
                    lines.set(i, "enable-jmx-monitoring=true");
                    found = true;
                    break;
                }
            }
            if (!found) {
                lines.add("enable-jmx-monitoring=true");
            }

            Files.write(propertiesPath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            logger.fine("Failed to enforce enable-jmx-monitoring in server.properties: " + ex.getMessage());
        }
    }

    private void rewriteStartScriptForBoot(Path serverDir) throws IOException {
        if (serverDir == null) {
            throw new IOException("Server directory is missing.");
        }
        Path root = serverDir.toAbsolutePath().normalize();
        Path startScript = root.resolve("start.sh").normalize();
        if (!startScript.startsWith(root)) {
            throw new IOException("Invalid start.sh path.");
        }

        String taggedDir = root.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        String content = "#!/bin/bash\n"
                + "java -Dneodash.server.dir=\"" + taggedDir + "\" -Xmx4096M -jar server.jar nogui\n";

        Files.writeString(startScript, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        File scriptFile = startScript.toFile();
        if (!scriptFile.setExecutable(true, false) && !scriptFile.canExecute()) {
            throw new IOException("Failed to mark start.sh as executable.");
        }
    }

    private String renderServerManagePage(long serverId) {
        Optional<DatabaseManager.ServerRecord> server = databaseManager == null
                ? Optional.empty()
                : databaseManager.getServerById(serverId);
        if (server.isEmpty()) {
            return "<html><body>Server not found.</body></html>";
        }

        ServerStateCache.ServerStateSnapshot headerStats = ServerStateCache.getSnapshot(serverId, server.get());
        String statusBadge = headerStats.online()
                ? "bg-emerald-500/15 text-emerald-300 border border-emerald-500/30"
                : "bg-rose-500/15 text-rose-300 border border-rose-500/30";
        String statusLabel = headerStats.online() ? "Online" : "Offline";

        String content = HtmlTemplate.statsHeader(headerStats)
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<section class='rounded-3xl bg-[#0f172a]/70 backdrop-blur border border-slate-700/60 p-6 mb-6'>"
                + "<div class='flex flex-wrap items-center justify-between gap-4'>"
                + "<div><h2 class='text-2xl font-bold text-white'>" + escapeHtml(server.get().name()) + "</h2>"
                + "<p class='text-slate-400 text-sm mt-1'>Server #" + serverId + "</p></div>"
                + "<span class='px-3 py-1 rounded-full text-xs font-semibold " + statusBadge + "'>" + statusLabel
                + "</span>"
                + "</div>"
                + "</section>"
                + "<section class='grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4'>"
                + actionCard("Console", "Live logs and command terminal", "terminal", "/console?id=" + serverId)
                + actionCard("Files", "Browse and edit server files", "folder", "/files?id=" + serverId)
                + actionCard("Settings", "Runner, ports, bridge credentials", "settings", "/server/settings?id=" + serverId)
                + actionCard("Players", "Online players and connection data", "group", "/players?id=" + serverId)
                + actionCard("Plugins", "Manage plugin-specific tools", "extension", "/plugins?id=" + serverId)
                + "</section>"
                + "</main>";
        return HtmlTemplate.page("Server Overview", "/", content);
    }

    private String actionCard(String title, String description, String icon, String href) {
        return "<article class='rounded-2xl bg-slate-800/50 backdrop-blur border border-slate-700/60 p-5 flex flex-col gap-3'>"
                + "<div class='flex items-center justify-between gap-3'>"
                + "<h3 class='text-lg font-semibold text-white'>" + escapeHtml(title) + "</h3>"
                + "<span class='material-symbols-outlined text-cyan-400'>" + escapeHtml(icon) + "</span>"
                + "</div>"
                + "<p class='text-sm text-slate-400'>" + escapeHtml(description) + "</p>"
                + "<div class='pt-1 mt-auto'>"
                + "<a href='" + href
                + "' class='inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-cyan-500/15 text-cyan-300 hover:bg-cyan-400 hover:text-black transition-colors text-sm font-semibold'>"
                + "Open <span class='material-symbols-outlined text-[18px]'>arrow_forward</span></a>"
                + "</div>"
                + "</article>";
    }


    private String renderBridgePlaceholder(String featureName, long serverId) {
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><title>" + featureName + "</title>"
                + "<style>body{font-family:system-ui;background:#0b1020;color:#e2e8f0;padding:16px}.card{max-width:900px;margin:24px auto;background:#121a31;border:1px solid #1f2a44;border-radius:16px;padding:28px;text-align:center}a{color:#93c5fd}</style></head><body>"
                + "<p><a href='/server?id=" + serverId + "'>Back</a></p>"
                + "<div class='card'><h2>" + escapeHtml(featureName) + "</h2><p style='font-size:18px;color:#cbd5e1'>Feature requires NeoDash Bridge Plugin (Coming Soon)</p></div>"
                + "</body></html>";
    }

    private String renderFilesPage(long serverId, String relativeDir) {
        Optional<ServerContext> contextOpt = resolveServerContext(serverId);
        if (contextOpt.isEmpty()) {
            return "<html><body>Server not found.</body></html>";
        }
        Path rootPath = contextOpt.get().rootPath();

        Path directory = resolveSafePath(rootPath, relativeDir);
        if (directory == null || !Files.isDirectory(directory)) {
            directory = rootPath;
        }

        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(entries::add);
        } catch (IOException ignored) {
        }

        String rel = rootPath.relativize(directory).toString().replace('\\', '/');
        String parent = "";
        if (!rel.isBlank()) {
            int idx = rel.lastIndexOf('/');
            parent = idx >= 0 ? rel.substring(0, idx) : "";
        }

        StringBuilder rows = new StringBuilder();
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            String entryRel = rootPath.relativize(entry).toString().replace('\\', '/');
            boolean dir = Files.isDirectory(entry);
            rows.append("<tr><td>").append(dir ? "[DIR] " : "[FILE] ").append(escapeHtml(name)).append("</td><td>");
            if (dir) {
                rows.append("<a href='/files?id=").append(serverId).append("&path=").append(encodeForQuery(entryRel))
                        .append("'>Open</a>");
            } else {
                rows.append("<a href='/files/edit?id=").append(serverId).append("&path=").append(encodeForQuery(entryRel))
                        .append("'>Edit</a>");
            }
            rows.append("</td><td><form method='post' action='/action' onsubmit=\"return confirm('Delete?')\'>")
                    .append("<input type='hidden' name='id' value='").append(serverId).append("'>")
                    .append("<input type='hidden' name='action' value='file_delete'>")
                    .append("<input type='hidden' name='path' value='").append(escapeHtml(entryRel)).append("'>")
                    .append("<button type='submit'>Delete</button></form></td></tr>");
        }

        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Files</title>"
                + "<style>body{font-family:system-ui;background:#0b1020;color:#e2e8f0;margin:0;padding:16px}table{width:100%;border-collapse:collapse}td,th{border-bottom:1px solid #1f2a44;padding:8px}a{color:#93c5fd}button{padding:6px 8px;border-radius:6px;border:1px solid #334155;background:#1d4ed8;color:#fff}</style></head><body>"
                + "<p><a href='/server?id=" + serverId + "'>Back</a></p>"
                + "<h2>File Manager (Server #" + serverId + ")</h2><p>Root: <code>" + escapeHtml(rootPath.toString())
                + "</code></p>"
                + "<p>Current: <code>/" + escapeHtml(rel) + "</code>"
                + (!rel.isBlank()
                        ? " | <a href='/files?id=" + serverId + "&path=" + encodeForQuery(parent) + "'>Up</a>"
                        : "")
                + "</p>"
                + "<div style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:12px 0'>"
                + "<label style='display:inline-flex;align-items:center;gap:6px;padding:6px 10px;border-radius:8px;border:1px solid #334155;background:#111827;cursor:pointer'>"
                + "Upload File <input id='upload-file-input' type='file' multiple style='display:none'></label>"
                + "<label style='display:inline-flex;align-items:center;gap:6px;padding:6px 10px;border-radius:8px;border:1px solid #334155;background:#111827;cursor:pointer'>"
                + "Upload Folder <input id='upload-folder-input' type='file' webkitdirectory directory multiple style='display:none'></label>"
                + "<span id='upload-status' style='font-size:12px;color:#94a3b8'></span>"
                + "</div>"
                + "<table><thead><tr><th>Name</th><th>Open</th><th>Delete</th></tr></thead><tbody>"
                + rows
                + "</tbody></table>"
                + "<script>"
                + "(function(){"
                + "const sid='" + serverId + "';"
                + "const fileInput=document.getElementById('upload-file-input');"
                + "const folderInput=document.getElementById('upload-folder-input');"
                + "const status=document.getElementById('upload-status');"
                + "async function sendFiles(files, useRelative){"
                + "  if(!files||files.length===0){return;}"
                + "  status.textContent='Uploading '+files.length+' file(s)...';"
                + "  for(let i=0;i<files.length;i++){"
                + "    const file=files[i];"
                + "    const rel=(useRelative && file.webkitRelativePath)?file.webkitRelativePath:file.name;"
                + "    const safeRel=String(rel||'').replace(/^\\/+/, '');"
                + "    const uploadUrl='/api/files/upload?serverId='+encodeURIComponent(sid)+'&path='+encodeURIComponent(safeRel);"
                + "    const resp=await fetch(uploadUrl,{method:'POST',body:file,credentials:'same-origin'});"
                + "    if(!resp.ok){"
                + "      let msg='Upload failed';"
                + "      try{const data=await resp.text(); if(data){msg=data;}}catch(_){ }"
                + "      status.textContent=msg;"
                + "      return;"
                + "    }"
                + "    status.textContent='Uploaded '+(i+1)+'/'+files.length;"
                + "  }"
                + "  status.textContent='Upload complete. Reloading...';"
                + "  window.location.reload();"
                + "}"
                + "if(fileInput){fileInput.addEventListener('change',()=>sendFiles(fileInput.files,false));}"
                + "if(folderInput){folderInput.addEventListener('change',()=>sendFiles(folderInput.files,true));}"
                + "})();"
                + "</script>"
                + "</body></html>";
    }

    private String renderFileEditor(long serverId, String relPath) {
        Optional<ServerContext> contextOpt = resolveServerContext(serverId);
        if (contextOpt.isEmpty()) {
            return "<html><body>Server not found.</body></html>";
        }
        Path file = resolveSafePath(contextOpt.get().rootPath(), relPath);
        if (file == null || Files.isDirectory(file)) {
            return "<html><body>Invalid file path. <a href='/files?id=" + serverId + "'>Back</a></body></html>";
        }

        String content = "";
        try {
            content = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException ignored) {
        }

        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Edit File</title>"
                + "<style>body{font-family:system-ui;background:#0b1020;color:#e2e8f0;margin:0;padding:16px}textarea{width:100%;height:70vh;background:#0a0f1f;color:#e2e8f0;border:1px solid #334155;border-radius:8px;padding:10px;font-family:ui-monospace,monospace}button{padding:8px 12px;border-radius:8px;border:1px solid #1d4ed8;background:#1d4ed8;color:#fff}</style></head><body>"
                + "<h3>Edit <code>" + escapeHtml(relPath) + "</code></h3>"
                + "<form method='post' action='/api/files/save'>"
                + "<input type='hidden' name='id' value='" + serverId + "'>"
                + "<input type='hidden' name='path' value='" + escapeHtml(relPath) + "'>"
                + "<textarea name='content'>" + escapeHtml(content) + "</textarea><br>"
                + "<button type='submit'>Save</button> <a href='/files?id=" + serverId + "'>Back</a>"
                + "</form></body></html>";
    }

    private String renderLoginPage() {
        return LoginPage.render();
    }

    private String renderLoginPageWithError(String error) {
        return LoginPage.render(error);
    }

    private void warmServerStatesForUser(long userId) {
        if (databaseManager == null || userId <= 0) {
            return;
        }
        try {
            for (DatabaseManager.ServerRecord server : databaseManager.getServersForUser(userId)) {
                ServerStateCache.getSnapshotBlocking(server.id(), server, 1200L);
            }
        } catch (Exception ex) {
            logger.fine("Server state warmup skipped: " + ex.getMessage());
        }
    }

    private String getQueryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private Map<String, List<String>> rolesWithPermissionsAsLists() {
        if (auth == null) {
            return Map.of();
        }
        Map<String, List<String>> converted = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : auth.getRolesWithPermissions().entrySet()) {
            converted.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return converted;
    }

    private Map<String, String> parseFormData(String formData) {
        Map<String, String> map = new HashMap<>();
        if (formData != null && !formData.isBlank()) {
            for (String pair : formData.split("&")) {
                String[] keyVal = pair.split("=", 2);
                if (keyVal.length == 2) {
                    map.put(URLDecoder.decode(keyVal[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(keyVal[1], StandardCharsets.UTF_8));
                } else if (keyVal.length == 1) {
                    map.put(URLDecoder.decode(keyVal[0], StandardCharsets.UTF_8), "");
                }
            }
        }
        String requestUri = REQUEST_URI_CONTEXT.get();
        if (requestUri != null) {
            logger.info("[HTTP-Params] Extracted keys for " + requestUri + ": " + map.keySet());
        }
        return map;
    }

    private String extractMultipartBoundary(String contentType) {
        if (contentType == null) {
            return "";
        }
        for (String part : contentType.split(";")) {
            String token = part.trim();
            if (token.startsWith("boundary=")) {
                String boundary = token.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return "";
    }

    private List<MultipartPart> parseMultipartFormData(byte[] payload, String boundary) {
        if (payload == null || payload.length == 0 || boundary == null || boundary.isBlank()) {
            return List.of();
        }
        String delimiter = "--" + boundary;
        String raw = new String(payload, StandardCharsets.ISO_8859_1);
        String[] chunks = raw.split(java.util.regex.Pattern.quote(delimiter));
        List<MultipartPart> parts = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String piece = chunk;
            if (piece.startsWith("\r\n")) {
                piece = piece.substring(2);
            }
            if (piece.endsWith("\r\n")) {
                piece = piece.substring(0, piece.length() - 2);
            }
            if (piece.equals("--") || piece.isBlank()) {
                continue;
            }

            int headerEnd = piece.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = piece.substring(0, headerEnd);
            String body = piece.substring(headerEnd + 4);

            String name = "";
            String filename = null;
            for (String line : headers.split("\r\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("content-disposition:")) {
                    continue;
                }
                for (String attr : line.split(";")) {
                    String token = attr.trim();
                    if (token.startsWith("name=")) {
                        name = stripQuotes(token.substring(5));
                    } else if (token.startsWith("filename=")) {
                        filename = stripQuotes(token.substring(9));
                    }
                }
            }

            byte[] data = body.getBytes(StandardCharsets.ISO_8859_1);
            parts.add(new MultipartPart(name, filename, data));
        }
        return parts;
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String firstPartValue(List<MultipartPart> parts, String name) {
        for (MultipartPart part : parts) {
            if (name.equals(part.name()) && (part.fileName() == null || part.fileName().isBlank())) {
                return new String(part.data(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private List<String> allPartValues(List<MultipartPart> parts, String name) {
        List<String> values = new ArrayList<>();
        for (MultipartPart part : parts) {
            if (name.equals(part.name()) && (part.fileName() == null || part.fileName().isBlank())) {
                values.add(new String(part.data(), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private String normalizeUploadRelativePath(String relativePath, String fallbackFileName) {
        String rel = trimToEmpty(relativePath).replace('\\', '/');
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isBlank()) {
            rel = trimToEmpty(fallbackFileName);
        }
        rel = rel.replace("..", "");
        return rel;
    }

    private List<String> readNativeConsoleLines(Path serverRoot, int maxLines) {
        if (serverRoot == null) {
            return List.of();
        }
        Path latestLog = serverRoot.resolve("logs").resolve("latest.log");
        Path fallbackLog = serverRoot.resolve("server.log");
        Path logFile = Files.isRegularFile(latestLog) ? latestLog : fallbackLog;
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > maxLines) {
                    tail.removeFirst();
                }
            }
        } catch (IOException ignored) {
        }
        return new ArrayList<>(tail);
    }

    private boolean isTcpPortReachable(String host, int port, int timeoutMillis) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (SocketTimeoutException | java.net.ConnectException ex) {
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    private NativeStatusData collectNativeStatusData(long serverId, DatabaseManager.ServerRecord server) {
        if (server == null) {
            return new NativeStatusData(false, -1L, 0.0d, 0.0d, 0L, 0L, "Unknown", List.of(), 0);
        }

        boolean online = isTcpPortReachable("127.0.0.1", server.port(), 1000);
        long pid = -1L;
        double tps = 0.0d;
        double cpu = 0.0d;
        long ramMb = 0L;
        long maxRamObserved = 0L;

        if (online) {
            Optional<Long> pidOpt = NativeMetricsCollector.findPidByDirectory(server.pathToDir());
            if (pidOpt.isPresent()) {
                pid = pidOpt.get();
                NativeMetricsCollector.JvmMetrics jvmMetrics = NativeMetricsCollector.getJvmMetrics(pid);
                tps = Math.max(0.0d, jvmMetrics.tps());
                cpu = Math.max(0.0d, jvmMetrics.cpuUsage());
                ramMb = Math.max(0L, jvmMetrics.usedRamMb());
            }
        }

        Path rootPath;
        try {
            rootPath = Path.of(server.pathToDir()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            rootPath = null;
        }
        NativeLogState logState = updateNativeLogState(serverId, rootPath);
        if (ramMb > logState.maxRamObserved()) {
            logState.setMaxRamObserved(ramMb);
        }
        maxRamObserved = logState.maxRamObserved();

        if (logState.version() == null || "Unknown".equalsIgnoreCase(logState.version())) {
            String guessed = inferVersionFromProcess(pid, server.startCommand());
            if (!guessed.isBlank()) {
                logState.setVersion(guessed);
            }
        }
        int playerCount = logState.onlinePlayers().size();
        int chunks = 256 + (playerCount * 441);

        return new NativeStatusData(
                online,
                pid,
                tps,
                cpu,
                ramMb,
                maxRamObserved,
                logState.version(),
                new ArrayList<>(logState.onlinePlayers()),
                chunks);
    }

    private NativeLogState updateNativeLogState(long serverId, Path serverRoot) {
        NativeLogState state = nativeLogStates.computeIfAbsent(serverId,
                key -> new NativeLogState("Unknown", 0L, "", 0L, new java.util.LinkedHashSet<>()));
        if (serverRoot == null) {
            return state;
        }

        Path latestLog = serverRoot.resolve("logs").resolve("latest.log");
        Path fallbackLog = serverRoot.resolve("server.log");
        Path logFile = Files.isRegularFile(latestLog) ? latestLog : fallbackLog;
        if (!Files.isRegularFile(logFile)) {
            return state;
        }

        String logPath = logFile.toAbsolutePath().normalize().toString();
        if (!logPath.equals(state.logFilePath())) {
            state = new NativeLogState(state.version(), 0L, logPath, state.maxRamObserved(),
                    new java.util.LinkedHashSet<>(state.onlinePlayers()));
        }

        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long fileLength = raf.length();
            long offset = state.offset();
            if (offset > fileLength) {
                // Log rotated/truncated.
                offset = 0L;
                state.onlinePlayers().clear();
            }

            raf.seek(offset);
            String line;
            while ((line = raf.readLine()) != null) {
                String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

                Matcher versionMatcher = MC_VERSION_PATTERN.matcher(decoded);
                if (versionMatcher.find()) {
                    String version = versionMatcher.group(1).trim();
                    if (!version.isBlank()) {
                        state.setVersion(version);
                    }
                }

                Matcher joinMatcher = PLAYER_JOIN_PATTERN.matcher(decoded);
                if (joinMatcher.find()) {
                    state.onlinePlayers().add(joinMatcher.group(1));
                }

                Matcher leaveMatcher = PLAYER_LEAVE_PATTERN.matcher(decoded);
                if (leaveMatcher.find()) {
                    state.onlinePlayers().remove(leaveMatcher.group(1));
                }
            }

            state.setOffset(raf.getFilePointer());
            state.setLogFilePath(logPath);
        } catch (IOException ex) {
            logger.fine("Native log parser skipped for server #" + serverId + ": " + ex.getMessage());
        }

        nativeLogStates.put(serverId, state);
        return state;
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private String inferVersionFromProcess(long pid, String fallbackText) {
        try {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                String cmd = handle.get().info().commandLine().orElse("");
                Matcher matcher = VERSION_FALLBACK_PATTERN.matcher(cmd);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception ignored) {
        }

        String fallback = trimToEmpty(fallbackText);
        Matcher matcher = VERSION_FALLBACK_PATTERN.matcher(fallback);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        sendResponseWithStatus(exchange, status, json == null ? "{}" : json);
    }

    private record MultipartPart(String name, String fileName, byte[] data) {
    }

    private record NativeStatusData(boolean online, long pid, double tps, double cpu, long ramMb, long maxRamObserved,
            String version,
            List<String> players, int chunks) {
        int playerCount() {
            return players == null ? 0 : players.size();
        }
    }

    private static final class NativeLogState {
        private String version;
        private long offset;
        private String logFilePath;
        private long maxRamObserved;
        private final java.util.Set<String> onlinePlayers;

        private NativeLogState(String version, long offset, String logFilePath, long maxRamObserved,
                java.util.Set<String> onlinePlayers) {
            this.version = version == null || version.isBlank() ? "Unknown" : version;
            this.offset = Math.max(0L, offset);
            this.logFilePath = logFilePath == null ? "" : logFilePath;
            this.maxRamObserved = Math.max(0L, maxRamObserved);
            this.onlinePlayers = onlinePlayers == null ? new java.util.LinkedHashSet<>() : onlinePlayers;
        }

        private String version() {
            return version;
        }

        private void setVersion(String version) {
            this.version = version == null || version.isBlank() ? this.version : version;
        }

        private long offset() {
            return offset;
        }

        private void setOffset(long offset) {
            this.offset = Math.max(0L, offset);
        }

        private String logFilePath() {
            return logFilePath;
        }

        private void setLogFilePath(String logFilePath) {
            this.logFilePath = logFilePath == null ? "" : logFilePath;
        }

        private long maxRamObserved() {
            return maxRamObserved;
        }

        private void setMaxRamObserved(long maxRamObserved) {
            this.maxRamObserved = Math.max(this.maxRamObserved, Math.max(0L, maxRamObserved));
        }

        private java.util.Set<String> onlinePlayers() {
            return onlinePlayers;
        }
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String item = part == null ? "" : part.trim();
            if (!item.isBlank()) {
                values.add(item);
            }
        }
        return values;
    }

    private String encodeForQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String resolveCurrentNeoDashVersion() {
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

    private String buildSsoRedirectUrl(HttpExchange exchange, DatabaseManager.ServerRecord server, String username) {
        if (server == null || username == null || username.isBlank()) {
            return null;
        }

        String ipAddress = server.ipAddress() == null ? "" : server.ipAddress().trim();
        ipAddress = resolveSsoHost(exchange, ipAddress);
        String bridgeSecret = server.bridgeSecret() == null ? "" : server.bridgeSecret().trim();
        Integer dashPort = server.dashPort();
        if (ipAddress.isBlank() || bridgeSecret.isBlank() || dashPort == null || dashPort < 1 || dashPort > 65535) {
            return null;
        }

        long timestamp = java.time.Instant.now().getEpochSecond();

        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        String ssoUser = "nd_" + normalizedUsername;
        String hmacInput = ssoUser + ":" + timestamp;
        String secretForHmac = bridgeSecret.trim();
        String signature = hmacSha256(hmacInput, secretForHmac);
        if (signature == null || signature.isBlank()) {
            return null;
        }

        String encodedUser = encodeForQuery(ssoUser);
        String encodedSignature = encodeForQuery(signature);

        String targetUrl = "http://" + ipAddress + ":" + dashPort + "/";
        String finalUrl = targetUrl
                + "?user=" + encodedUser
                + "&timestamp=" + timestamp
                + "&signature=" + encodedSignature;
        return finalUrl;
    }

    private String hmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            logger.warning("Failed to generate HMAC-SHA256 SSO signature: " + ex.getMessage());
            return null;
        }
    }

    private String resolveSsoHost(HttpExchange exchange, String configuredHost) {
        String host = configuredHost == null ? "" : configuredHost.trim();
        String normalized = host.toLowerCase(Locale.ROOT);
        boolean local = normalized.isBlank()
                || "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "0.0.0.0".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
        if (!local) {
            return host;
        }

        String hostHeader = exchange == null ? null : exchange.getRequestHeaders().getFirst("Host");
        if (hostHeader == null || hostHeader.isBlank()) {
            return host;
        }

        String raw = hostHeader.trim();
        if (raw.startsWith("[")) {
            int endBracket = raw.indexOf(']');
            if (endBracket > 0) {
                return raw.substring(0, endBracket + 1);
            }
            return host;
        }

        int colon = raw.lastIndexOf(':');
        if (colon > 0 && raw.indexOf(':') == colon) {
            return raw.substring(0, colon).trim();
        }
        return raw;
    }

    private void cleanupKnownBridgeSessions(long userId) {
        if (databaseManager == null || userId <= 0) {
            return;
        }
        try {
            List<DatabaseManager.ServerRecord> servers = databaseManager.getServersForUser(userId);
            for (DatabaseManager.ServerRecord server : servers) {
                if (server == null || server.bridgeSecret() == null || server.bridgeSecret().isBlank()) {
                    continue;
                }
                revokeBridgeSessionBestEffort(server, "sso/logout");
                revokeBridgeSessionBestEffort(server, "session/revoke");
                revokeBridgeSessionBestEffort(server, "auth/logout");
            }
        } catch (Exception ex) {
            logger.fine("Bridge session cleanup skipped: " + ex.getMessage());
        }
    }

    private void revokeBridgeSessionBestEffort(DatabaseManager.ServerRecord server, String endpoint) {
        try {
            bridgeApiClient.sendAuthenticatedRequest(
                    server,
                    endpoint,
                    "POST",
                    "{}",
                    "application/json",
                    Duration.ofSeconds(1)).join();
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void logParsedBridgeTarget(HttpExchange exchange, long userId, String bridgeHost, Integer bridgePort) {
        String host = (bridgeHost == null || bridgeHost.isBlank()) ? "127.0.0.1" : bridgeHost.trim();
        int port = bridgePort == null ? 0 : bridgePort;
        WebActionLogger.log(
                "SERVER_CREATE_BRIDGE_PARSED",
                "userId=" + userId + " host=" + host + " bridgePort=" + port + " ip=" + getClientIp(exchange));
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void sendResponse(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendResponseWithStatus(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
