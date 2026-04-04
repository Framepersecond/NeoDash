package dash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Handles Modrinth V2 .mrpack processing with server-side filtering, hash verification,
 * and overrides extraction.
 */
public class ModpackManager {
    private static final String USER_AGENT = "NeoDash/1.0";

    public record ResolvedModpack(String downloadUrl, String fileName, boolean mrpack) {
    }

    public ResolvedModpack resolveModpack(String modpackIdOrUrl, String mcVersion, String requestedVersion,
            List<String> installLog) throws IOException {
        String raw = modpackIdOrUrl == null ? "" : modpackIdOrUrl.trim();
        if (raw.isBlank()) {
            throw new IOException("Modpack id is empty.");
        }

        if (isHttpUrl(raw)) {
            String fileName = safeFileNameFromUrl(raw);
            boolean mrpack = fileName.toLowerCase(Locale.ROOT).endsWith(".mrpack");
            logStep(installLog, "[Modpack] Using direct modpack URL: " + raw);
            return new ResolvedModpack(raw, fileName, mrpack);
        }

        String endpoint = "https://api.modrinth.com/v2/project/"
                + URLEncoder.encode(raw, StandardCharsets.UTF_8)
                + "/version";
        logStep(installLog, "[Modpack] Resolving Modrinth project id/slug via: " + endpoint);
        JsonArray versions = fetchJsonArray(endpoint);
        if (versions.isEmpty()) {
            throw new IOException("No versions found for Modrinth project: " + raw);
        }

        JsonObject chosenVersion = pickCompatibleVersion(versions, mcVersion, requestedVersion);
        JsonObject chosenFile = pickPreferredFile(chosenVersion);

        String url = getString(chosenFile, "url");
        String fileName = getString(chosenFile, "filename");
        if (url.isBlank()) {
            throw new IOException("Modrinth version has no downloadable file URL.");
        }
        if (fileName.isBlank()) {
            fileName = safeFileNameFromUrl(url);
        }
        boolean mrpack = fileName.toLowerCase(Locale.ROOT).endsWith(".mrpack");
        logStep(installLog, "[Modpack] Resolved modpack file URL: " + url);
        return new ResolvedModpack(url, fileName, mrpack);
    }

    public Path downloadResolvedModpack(ResolvedModpack resolved, Path installDir, List<String> installLog)
            throws IOException {
        Path archivePath = installDir.resolve(safeFileName(resolved.fileName()));
        logStep(installLog, "[Modpack] URL being downloaded: " + resolved.downloadUrl());
        downloadBinary(resolved.downloadUrl(), archivePath, Map.of("User-Agent", USER_AGENT));
        return archivePath;
    }

    public void installFromMrpack(Path mrpackArchive, Path serverRoot, List<String> installLog) throws IOException {
        try {
            if (mrpackArchive == null || !Files.isRegularFile(mrpackArchive)) {
                throw new IOException("Missing .mrpack archive.");
            }
            Files.createDirectories(serverRoot);
            Files.createDirectories(serverRoot.resolve("mods"));

            try (ZipFile zip = new ZipFile(mrpackArchive.toFile())) {
                JsonObject index = readModrinthIndex(zip);
                JsonArray filesArray = index.getAsJsonArray("files");
                logStep(installLog, "[Modpack] Successfully parsed modrinth.index.json");
                logStep(installLog, "[Modpack] Found " + filesArray.size() + " files in modrinth.index.json");

                int downloaded = downloadServerSupportedMods(index, serverRoot, installLog);
                copyOverrideDirectory(zip, "overrides/", serverRoot, installLog);
                copyOverrideDirectory(zip, "server-overrides/", serverRoot, installLog);
                addLog(installLog, "Downloaded " + downloaded + " modpack files from Modrinth index.");
            }
        } catch (Exception e) {
            System.out.println("[Modpack] Installation failed: " + e.getMessage());
            addLog(installLog, "[Modpack] Installation failed: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Modpack installation failed: " + e.getMessage(), e);
        }
    }

    private JsonObject readModrinthIndex(ZipFile zip) throws IOException {
        ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
        if (indexEntry == null) {
            throw new IOException("Invalid .mrpack: missing modrinth.index.json");
        }
        try (InputStream in = zip.getInputStream(indexEntry)) {
            JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Invalid .mrpack: modrinth.index.json is malformed.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("files") || !root.get("files").isJsonArray()) {
                throw new IOException("Invalid .mrpack: files array is missing.");
            }
            return root;
        }
    }

    private int downloadServerSupportedMods(JsonObject index, Path serverRoot, List<String> installLog) throws IOException {
        JsonArray files = index.getAsJsonArray("files");
        Path modsDir = serverRoot.resolve("mods");
        Files.createDirectories(modsDir);
        logStep(installLog, "[Modpack] Ensured mods directory exists: " + modsDir.toAbsolutePath());
        int downloaded = 0;
        int total = files.size();
        int step = 0;
        for (JsonElement element : files) {
            step++;
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            if (isServerUnsupported(file)) {
                String skippedPath = getString(file, "path");
                logStep(installLog, "[Modpack] Skipping client-only/unsupported file " + step + "/" + total + ": " + skippedPath);
                continue;
            }
            String filePath = getString(file, "path");
            if (filePath.isBlank()) {
                continue;
            }
            String downloadUrl = extractFirstDownloadUrl(file);
            if (downloadUrl.isBlank()) {
                continue;
            }

            String lowerPath = filePath.toLowerCase(Locale.ROOT);
            String lowerUrl = downloadUrl.toLowerCase(Locale.ROOT);
            if (!lowerPath.endsWith(".jar") && !lowerUrl.contains(".jar")) {
                logStep(installLog, "[Modpack] Skipping non-jar entry " + step + "/" + total + ": " + filePath);
                continue;
            }

            Path target = modsDir.resolve(safeFileName(Path.of(filePath).getFileName().toString())).normalize();

            String sha512 = extractSha512(file);
            if (sha512.isBlank()) {
                throw new IOException("Missing sha512 for modpack file: " + filePath);
            }

            logStep(installLog,
                    "[Modpack] Downloading mod " + (downloaded + 1) + " (entry " + step + "/" + total + ") -> "
                            + target.getFileName());
            downloadBinary(downloadUrl, target, Map.of("User-Agent", USER_AGENT));
            verifySha512(target, sha512);
            logStep(installLog, "[Modpack] Downloaded mod file: " + target.getFileName());
            downloaded++;
        }
        logStep(installLog, "[Modpack] Finished dependency pass. Downloaded " + downloaded + " mod jars.");
        return downloaded;
    }

    private boolean isServerUnsupported(JsonObject file) {
        if (!file.has("env") || !file.get("env").isJsonObject()) {
            return false;
        }
        JsonObject env = file.getAsJsonObject("env");
        String server = getString(env, "server");
        return "unsupported".equalsIgnoreCase(server);
    }

    private String extractFirstDownloadUrl(JsonObject file) {
        if (!file.has("downloads") || !file.get("downloads").isJsonArray()) {
            return "";
        }
        for (JsonElement dl : file.getAsJsonArray("downloads")) {
            if (dl != null && !dl.isJsonNull()) {
                String url = dl.getAsString();
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
        }
        return "";
    }

    private String extractSha512(JsonObject file) {
        if (!file.has("hashes") || !file.get("hashes").isJsonObject()) {
            return "";
        }
        JsonObject hashes = file.getAsJsonObject("hashes");
        return getString(hashes, "sha512");
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    private void copyOverrideDirectory(ZipFile zip, String prefix, Path serverRoot, List<String> installLog)
            throws IOException {
        int copied = 0;
        for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
            if (entry == null || entry.isDirectory()) {
                continue;
            }
            if (!entry.getName().startsWith(prefix)) {
                continue;
            }
            String relative = entry.getName().substring(prefix.length());
            if (relative.isBlank()) {
                continue;
            }
            Path target = serverRoot.resolve(relative).normalize();
            if (!target.startsWith(serverRoot.normalize())) {
                throw new IOException("Blocked unsafe override path: " + relative);
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry);
                    OutputStream out = Files.newOutputStream(target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
            copied++;
        }
        if (copied > 0) {
            addLog(installLog, "Applied " + copied + " files from " + prefix + " overrides.");
        }
    }

    private JsonArray fetchJsonArray(String endpoint) throws IOException {
        HttpURLConnection connection = null;
        try {
            HttpURLConnection.setFollowRedirects(true);
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " while calling " + endpoint);
            }
            try (InputStream in = connection.getInputStream()) {
                JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!parsed.isJsonArray()) {
                    throw new IOException("Unexpected Modrinth API response for " + endpoint);
                }
                return parsed.getAsJsonArray();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JsonObject pickCompatibleVersion(JsonArray versions, String mcVersion, String requestedVersion)
            throws IOException {
        String requested = requestedVersion == null ? "" : requestedVersion.trim();
        for (JsonElement entry : versions) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject version = entry.getAsJsonObject();
            if (!matchesRequestedVersion(version, requested)) {
                continue;
            }
            if (!supportsGameVersion(version, mcVersion)) {
                continue;
            }
            return version;
        }
        for (JsonElement entry : versions) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject version = entry.getAsJsonObject();
            if (!matchesRequestedVersion(version, requested)) {
                continue;
            }
            return version;
        }
        throw new IOException("No compatible Modrinth version found.");
    }

    private boolean matchesRequestedVersion(JsonObject version, String requestedVersion) {
        if (requestedVersion == null || requestedVersion.isBlank() || "latest".equalsIgnoreCase(requestedVersion)) {
            return true;
        }
        String versionNumber = getString(version, "version_number");
        String versionId = getString(version, "id");
        return requestedVersion.equalsIgnoreCase(versionNumber) || requestedVersion.equalsIgnoreCase(versionId);
    }

    private boolean supportsGameVersion(JsonObject version, String mcVersion) {
        String expected = mcVersion == null ? "" : mcVersion.trim();
        if (expected.isBlank()) {
            return true;
        }
        if (!version.has("game_versions") || !version.get("game_versions").isJsonArray()) {
            return false;
        }
        for (JsonElement gameVersion : version.getAsJsonArray("game_versions")) {
            if (gameVersion != null && !gameVersion.isJsonNull()
                    && expected.equalsIgnoreCase(gameVersion.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private JsonObject pickPreferredFile(JsonObject version) throws IOException {
        if (!version.has("files") || !version.get("files").isJsonArray()) {
            throw new IOException("Modrinth version does not contain files array.");
        }
        JsonArray files = version.getAsJsonArray("files");
        JsonObject first = null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            String filename = getString(file, "filename").toLowerCase(Locale.ROOT);
            if (filename.endsWith(".mrpack")) {
                if (file.has("primary") && !file.get("primary").isJsonNull() && file.get("primary").getAsBoolean()) {
                    return file;
                }
                if (first == null) {
                    first = file;
                }
            }
        }
        if (first != null) {
            return first;
        }
        for (JsonElement element : files) {
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        }
        throw new IOException("No downloadable file entry found for selected Modrinth version.");
    }

    private String safeFileNameFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "modpack.mrpack";
        }
        String normalized = url;
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return safeFileName(normalized.substring(slash + 1));
        }
        return "modpack.mrpack";
    }

    private String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "modpack.mrpack";
        }
        String normalized = Path.of(name).getFileName().toString();
        return normalized.isBlank() ? "modpack.mrpack" : normalized;
    }

    private boolean isHttpUrl(String candidate) {
        if (candidate == null) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private void addLog(List<String> installLog, String line) {
        if (installLog != null && line != null && !line.isBlank()) {
            installLog.add(line);
        }
    }

    private void logStep(List<String> installLog, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        System.out.println(line);
        addLog(installLog, line);
    }

    private void downloadBinary(String url, Path target, Map<String, String> headers) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        HttpURLConnection connection = null;
        try {
            HttpURLConnection.setFollowRedirects(true);
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " while downloading " + url);
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
    }

    private void verifySha512(Path file, String expectedSha512) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            String expected = expectedSha512.trim().toLowerCase(Locale.ROOT);
            if (!actual.equals(expected)) {
                throw new IOException("SHA-512 mismatch for " + file.getFileName());
            }
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-512 digest unavailable", ex);
        }
    }
}


