package dash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

public class GithubUpdater {

    public static volatile boolean UPDATE_READY = false;
    public static volatile String LATEST_DASH_VERSION = "unknown";
    public static volatile String LATEST_NEODASH_VERSION = "unknown";
    public static volatile boolean UPDATE_AVAILABLE = false;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final Logger logger;
    private final Executor executor;
    private final boolean enabled;
    private final String repository;
    private final String token;
    private final String dashToken;

    public GithubUpdater(Logger logger, Executor executor, boolean enabled, String repository, String token,
            String dashToken) {
        this.logger = logger;
        this.executor = executor;
        this.enabled = enabled;
        this.repository = repository == null ? "" : repository.trim();
        this.token = token == null ? "" : token.trim();
        this.dashToken = dashToken == null ? "" : dashToken.trim();
    }

    public CompletableFuture<Void> checkForUpdates(String currentVersion) {
        return CompletableFuture.runAsync(() -> {
            if (!enabled) {
                UPDATE_AVAILABLE = false;
                return;
            }
            if (repository.isBlank()) {
                logger.warning("[Updater] updater.github-repo is not configured. Skipping update check.");
                UPDATE_AVAILABLE = false;
                return;
            }
            try {
                ReleaseFetchResult result = fetchLatestRelease(repository, token);
                JsonObject release = result.release();
                if (release == null) {
                    return;
                }

                String latestTag = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
                String latestVersion = normalizeVersion(latestTag);
                String runningVersion = normalizeVersion(currentVersion);
                LATEST_NEODASH_VERSION = latestVersion.isBlank() ? "unknown" : latestVersion;

                UPDATE_AVAILABLE = isNewerVersion(latestVersion, runningVersion);
                if (!UPDATE_AVAILABLE) {
                    logger.info("[Updater] NeoDash is up to date (current=" + runningVersion + ", latest=" + latestVersion
                            + ").");
                }
            } catch (Exception ex) {
                logger.warning("[Updater] Update check failed: " + ex.getMessage());
                UPDATE_AVAILABLE = false;
            }
        }, executor);
    }

    public synchronized DownloadResult downloadLatestNeoDashUpdate(String currentVersion) {
        if (!enabled) {
            return new DownloadResult(false, "Updater is disabled.");
        }
        if (repository.isBlank()) {
            return new DownloadResult(false, "updater.github-repo is not configured.");
        }
        try {
            ReleaseFetchResult result = fetchLatestRelease(repository, token);
            JsonObject release = result.release();
            String latestTag = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
            String latestVersion = normalizeVersion(latestTag);
            String runningVersion = normalizeVersion(currentVersion);
            LATEST_NEODASH_VERSION = latestVersion.isBlank() ? "unknown" : latestVersion;
            UPDATE_AVAILABLE = isNewerVersion(latestVersion, runningVersion);

            if (!UPDATE_AVAILABLE) {
                return new DownloadResult(false, "NeoDash is already up to date.");
            }

            String assetApiUrl = findJarAssetApiUrl(release);
            if (assetApiUrl == null || assetApiUrl.isBlank()) {
                return new DownloadResult(false, "Latest release has no .jar asset to download.");
            }

            Path runningArtifact = resolveRunningArtifactPath();
            if (runningArtifact == null
                    || !runningArtifact.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    || !Files.isRegularFile(runningArtifact)) {
                logger.warning("[Updater] Running artifact is not a .jar file (Path: "
                        + (runningArtifact == null ? "unknown" : runningArtifact)
                        + "). Skipping self-replace.");
                return new DownloadResult(false, "Running artifact is not a .jar file.");
            }

            Path tempJar = runningArtifact.getParent().resolve("NeoDash-update.jar");
            String authToken = result.usedAuthenticatedRequest() ? token : "";
            downloadJarAsset(assetApiUrl, tempJar, authToken);
            Files.move(tempJar, runningArtifact, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            UPDATE_READY = true;
            return new DownloadResult(true, "Update downloaded and applied! Please restart NeoDash.");
        } catch (Exception ex) {
            return new DownloadResult(false, "Download failed: " + ex.getMessage());
        }
    }

    public String getLatestDashVersion() {
        try {
            ReleaseFetchResult result = fetchLatestRelease("Framepersecond/Dash", dashToken);
            JsonObject release = result.release();
            String tag = release != null && release.has("tag_name") && !release.get("tag_name").isJsonNull()
                    ? release.get("tag_name").getAsString()
                    : "";
            String normalized = normalizeVersion(tag);
            LATEST_DASH_VERSION = normalized.isBlank() ? "unknown" : normalized;
        } catch (Exception ex) {
            logger.fine("[Updater] Failed to refresh latest Dash version: " + ex.getMessage());
            LATEST_DASH_VERSION = "unknown";
        }
        return LATEST_DASH_VERSION;
    }

    public static boolean isKnownVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String normalized = normalizeForCompare(version);
        return !normalized.isBlank() && !"unknown".equalsIgnoreCase(version.trim());
    }

    public static boolean isVersionOutdated(String current, String latest) {
        if (!isKnownVersion(current) || !isKnownVersion(latest)) {
            return false;
        }
        return compareVersions(current, latest) < 0;
    }

    public void downloadLatestDashJar(Path destination) {
        if (destination == null) {
            throw new IllegalArgumentException("Destination path is required");
        }

        try {
            ReleaseFetchResult result = fetchLatestRelease("Framepersecond/Dash", dashToken);
            JsonObject release = result.release();
            String assetApiUrl = findJarAssetApiUrl(release);
            if (assetApiUrl == null || assetApiUrl.isBlank()) {
                throw new IllegalStateException("Latest Framepersecond/Dash release has no .jar asset");
            }
            String authToken = result.usedAuthenticatedRequest() ? dashToken : "";
            downloadJarAsset(assetApiUrl, destination, authToken);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to download latest Dash.jar: " + ex.getMessage(), ex);
        }
    }

    private ReleaseFetchResult fetchLatestRelease(String repo, String authToken) throws IOException {
        String endpoint = "https://api.github.com/repos/" + repo + "/releases/latest";
        boolean shouldUseAuth = isConfiguredToken(authToken);
        if (shouldUseAuth) {
            try {
                JsonObject release = requestLatestRelease(endpoint, authToken, true);
                return new ReleaseFetchResult(release, true);
            } catch (HttpStatusException ex) {
                if (ex.statusCode == 401) {
                    logger.warning(
                            "Configured GitHub token is invalid/revoked. Falling back to unauthenticated public API request.");
                    JsonObject fallback = requestLatestRelease(endpoint, "", false);
                    return new ReleaseFetchResult(fallback, false);
                }
                throw ex;
            }
        }
        JsonObject release = requestLatestRelease(endpoint, "", false);
        return new ReleaseFetchResult(release, false);
    }

    private JsonObject requestLatestRelease(String endpoint, String authToken, boolean withAuth) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
            if (withAuth && isConfiguredToken(authToken)) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setRequestProperty("User-Agent", "NeoDash-Updater");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readBody(connection.getErrorStream());
                if (!withAuth && code == 403) {
                    throw new IOException(
                            "GitHub API rate limit exceeded. Please wait or update your config with a valid read-only GitHub token.");
                }
                throw new HttpStatusException(code,
                        "GitHub releases/latest returned HTTP " + code + " (" + safe(errorBody) + ")");
            }

            String body;
            try (InputStream in = connection.getInputStream()) {
                body = readBody(in);
            }
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException("GitHub releases/latest returned invalid JSON");
            }
            return parsed.getAsJsonObject();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String findJarAssetApiUrl(JsonObject release) {
        if (release == null || !release.has("assets") || !release.get("assets").isJsonArray()) {
            return null;
        }

        JsonArray assets = release.getAsJsonArray("assets");
        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) {
                continue;
            }
            JsonObject asset = assetElement.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (name.toLowerCase(Locale.ROOT).endsWith(".jar") && asset.has("url")) {
                return asset.get("url").getAsString();
            }
        }
        return null;
    }

    private void downloadJarAsset(String assetApiUrl, Path outputFile) throws IOException {
        downloadJarAsset(assetApiUrl, outputFile, token);
    }

    private void downloadJarAsset(String assetApiUrl, Path outputFile, String authToken) throws IOException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        boolean withAuth = isConfiguredToken(authToken);
        try {
            downloadJarAssetOnce(assetApiUrl, outputFile, authToken, withAuth);
        } catch (HttpStatusException ex) {
            if (withAuth && ex.statusCode == 401) {
                logger.warning(
                        "Configured GitHub token is invalid/revoked. Falling back to unauthenticated public API request.");
                downloadJarAssetOnce(assetApiUrl, outputFile, "", false);
                return;
            }
            if (!withAuth && ex.statusCode == 403) {
                throw new IOException(
                        "GitHub API rate limit exceeded. Please wait or update your config with a valid read-only GitHub token.");
            }
            throw ex;
        }
    }

    private void downloadJarAssetOnce(String assetApiUrl, Path outputFile, String authToken, boolean withAuth)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(assetApiUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
            if (withAuth && isConfiguredToken(authToken)) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "NeoDash-Updater");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readBody(connection.getErrorStream());
                throw new HttpStatusException(code,
                        "GitHub asset download returned HTTP " + code + " (" + safe(errorBody) + ")");
            }

            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, outputFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Path resolveRunningArtifactPath() {
        try {
            URL location = GithubUpdater.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            return Path.of(location.toURI()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? "0" : normalized;
    }

    private static String normalizeForCompare(String version) {
        if (version == null) {
            return "";
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized.replaceAll("[^0-9.]", "");
    }

    private static int compareVersions(String a, String b) {
        String[] aParts = normalizeForCompare(a).split("\\.");
        String[] bParts = normalizeForCompare(b).split("\\.");
        int max = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < max; i++) {
            int av = i < aParts.length ? parseComparablePart(aParts[i]) : 0;
            int bv = i < bParts.length ? parseComparablePart(bParts[i]) : 0;
            if (av < bv) {
                return -1;
            }
            if (av > bv) {
                return 1;
            }
        }
        return 0;
    }

    private static int parseComparablePart(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean isNewerVersion(String latest, String current) {
        String[] latestParts = latest.split("[.-]");
        String[] currentParts = current.split("[.-]");
        int max = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < max; i++) {
            int lv = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
            int cv = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            if (lv > cv) {
                return true;
            }
            if (lv < cv) {
                return false;
            }
        }
        return false;
    }

    private int parseVersionPart(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String safe(String text) {
        if (text == null || text.isBlank()) {
            return "no details";
        }
        return text.length() > 180 ? text.substring(0, 180) + "..." : text;
    }

    private boolean isConfiguredToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        String value = candidate.trim();
        return !value.isBlank() && !"YOUR_PAT_HERE".equals(value) && !"YOUR_TOKEN_HERE".equals(value);
    }

    private record ReleaseFetchResult(JsonObject release, boolean usedAuthenticatedRequest) {
    }

    private static final class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    public record DownloadResult(boolean downloaded, String message) {
    }
}

