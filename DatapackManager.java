package dash.data;

import dash.NeoDash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DatapackManager {

    private static Path datapacksRoot() {
        Path root = NeoDash.getServerRoot();
        return root == null ? Path.of(".").toAbsolutePath().normalize().resolve("datapacks")
                : root.resolve("datapacks").toAbsolutePath().normalize();
    }

    public static List<DatapackInfo> listDatapacks() {
        List<DatapackInfo> datapacks = new ArrayList<>();
        Path datapacksFolder = datapacksRoot();
        try {
            Files.createDirectories(datapacksFolder);
            File[] files = datapacksFolder.toFile().listFiles();
            if (files == null) {
                return datapacks;
            }

            for (File file : files) {
                if (file.isDirectory()) {
                    File mcmeta = new File(file, "pack.mcmeta");
                    if (mcmeta.exists()) {
                        String description = readPackDescription(mcmeta.toPath());
                        datapacks.add(new DatapackInfo(file.getName(), description, true, false, file.length()));
                    }
                } else if (file.getName().endsWith(".zip")) {
                    String description = readZipPackDescription(file);
                    String packName = file.getName().replace(".zip", "");
                    datapacks.add(new DatapackInfo(packName, description, true, true, file.length()));
                }
            }
        } catch (Exception ignored) {
        }
        return datapacks;
    }

    private static String readPackDescription(Path mcmetaPath) {
        try {
            String content = Files.readString(mcmetaPath);
            int descStart = content.indexOf("\"description\"");
            if (descStart == -1) {
                return "No description";
            }
            int colonPos = content.indexOf(":", descStart);
            int quoteStart = content.indexOf("\"", colonPos + 1);
            int quoteEnd = content.indexOf("\"", quoteStart + 1);
            if (quoteStart != -1 && quoteEnd != -1) {
                return content.substring(quoteStart + 1, quoteEnd);
            }
        } catch (Exception ignored) {
        }
        return "No description";
    }

    private static String readZipPackDescription(File zipFile) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("pack.mcmeta") || entry.getName().endsWith("/pack.mcmeta")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    String content = baos.toString();
                    int descStart = content.indexOf("\"description\"");
                    if (descStart != -1) {
                        int colonPos = content.indexOf(":", descStart);
                        int quoteStart = content.indexOf("\"", colonPos + 1);
                        int quoteEnd = content.indexOf("\"", quoteStart + 1);
                        if (quoteStart != -1 && quoteEnd != -1) {
                            return content.substring(quoteStart + 1, quoteEnd);
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return "No description";
    }

    public static boolean toggleDatapack(String name, boolean enable) {
        // Daemon mode placeholder: real enable/disable will use bridge/RCON.
        return name != null && !name.isBlank();
    }

    public static boolean uploadDatapack(String fileName, byte[] data) {
        if (fileName == null || fileName.isBlank() || data == null) {
            return false;
        }
        try {
            Path datapacksFolder = datapacksRoot();
            Files.createDirectories(datapacksFolder);

            String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!sanitized.endsWith(".zip")) {
                sanitized += ".zip";
            }
            Files.write(datapacksFolder.resolve(sanitized), data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteDatapack(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            Path datapacksFolder = datapacksRoot();
            Path folder = datapacksFolder.resolve(name).normalize();
            Path zip = datapacksFolder.resolve(name + ".zip").normalize();

            boolean deleted = false;
            if (Files.exists(folder) && Files.isDirectory(folder)) {
                deleteRecursive(folder.toFile());
                deleted = true;
            }
            if (Files.exists(zip)) {
                Files.delete(zip);
                deleted = true;
            }
            return deleted;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    public static void reloadDatapacks() {
        // No-op in daemon mode.
    }

    public record DatapackInfo(String name, String description, boolean enabled, boolean isZip, long size) {
        public String getFormattedSize() {
            if (size < 1024) {
                return size + " B";
            }
            if (size < 1024 * 1024) {
                return String.format("%.1f KB", size / 1024.0);
            }
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }
    }
}
