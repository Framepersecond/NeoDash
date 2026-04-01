package dash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

public class ModrinthService {

    private static final String API_BASE = "https://api.modrinth.com/v2";

    public String getModpackDownloadUrl(String slug, String mcVersion) {
        return getModpackFile(slug, mcVersion, "latest")
                .map(ModpackFile::downloadUrl)
                .orElseThrow(() -> new IllegalArgumentException("No compatible Modrinth file found for slug: " + slug));
    }

    public Optional<ModpackFile> getModpackFile(String slug, String mcVersion, String preferredVersion) {
        String normalizedSlug = trimToEmpty(slug);
        if (normalizedSlug.isBlank()) {
            return Optional.empty();
        }

        String requestedMcVersion = trimToEmpty(mcVersion);
        String requestedVersion = trimToEmpty(preferredVersion);
        boolean latest = requestedVersion.isBlank() || "latest".equalsIgnoreCase(requestedVersion);

        JsonArray versions = fetchVersions(normalizedSlug);
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject versionObj = element.getAsJsonObject();
            if (!isVersionCompatible(versionObj, requestedMcVersion)) {
                continue;
            }
            if (!latest) {
                String versionNumber = getString(versionObj, "version_number");
                String versionName = getString(versionObj, "name");
                String lowerRequested = requestedVersion.toLowerCase(Locale.ROOT);
                if (!versionNumber.toLowerCase(Locale.ROOT).contains(lowerRequested)
                        && !versionName.toLowerCase(Locale.ROOT).contains(lowerRequested)) {
                    continue;
                }
            }

            Optional<ModpackFile> file = selectPreferredFile(versionObj);
            if (file.isPresent()) {
                return file;
            }
        }
        return Optional.empty();
    }

    private JsonArray fetchVersions(String slug) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(API_BASE + "/project/" + URLEncoder.encode(slug, StandardCharsets.UTF_8)
                    + "/version");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "NeoDash/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Modrinth API returned HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(body);
                if (!root.isJsonArray()) {
                    throw new IllegalStateException("Unexpected Modrinth response shape.");
                }
                return root.getAsJsonArray();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to query Modrinth: " + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isVersionCompatible(JsonObject versionObj, String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return true;
        }
        JsonArray gameVersions = versionObj.has("game_versions") && versionObj.get("game_versions").isJsonArray()
                ? versionObj.getAsJsonArray("game_versions")
                : new JsonArray();
        for (JsonElement element : gameVersions) {
            if (mcVersion.equalsIgnoreCase(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private Optional<ModpackFile> selectPreferredFile(JsonObject versionObj) {
        if (!versionObj.has("files") || !versionObj.get("files").isJsonArray()) {
            return Optional.empty();
        }
        JsonArray files = versionObj.getAsJsonArray("files");
        ModpackFile fallbackZip = null;

        for (JsonElement fileElement : files) {
            if (!fileElement.isJsonObject()) {
                continue;
            }
            JsonObject fileObj = fileElement.getAsJsonObject();
            String filename = getString(fileObj, "filename");
            String url = getString(fileObj, "url");
            if (filename.isBlank() || url.isBlank()) {
                continue;
            }

            String lower = filename.toLowerCase(Locale.ROOT);
            String versionNumber = getString(versionObj, "version_number");
            if (lower.endsWith(".mrpack")) {
                return Optional.of(new ModpackFile(url, filename, versionNumber, true));
            }
            if (lower.endsWith(".zip") && fallbackZip == null) {
                fallbackZip = new ModpackFile(url, filename, versionNumber, false);
            }
        }

        return Optional.ofNullable(fallbackZip);
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record ModpackFile(String downloadUrl, String fileName, String versionLabel, boolean mrpack) {
    }
}

