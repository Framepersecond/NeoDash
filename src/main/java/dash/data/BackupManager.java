package dash.data;

import dash.NeoDash;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private final File backupDir;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private int scheduleHours = 0;

    public BackupManager() {
        this(Path.of("backups"));
    }

    public BackupManager(Path backupDirPath) {
        this.backupDir = backupDirPath.toAbsolutePath().normalize().toFile();
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backup-manager");
            t.setDaemon(true);
            return t;
        });
    }

    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduler.shutdownNow();
    }

    public void startSchedule(int hours) {
        this.scheduleHours = Math.max(0, hours);
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        if (scheduleHours > 0) {
            long intervalSeconds = scheduleHours * 3600L;
            scheduledTask = scheduler.scheduleAtFixedRate(this::createBackup, intervalSeconds, intervalSeconds,
                    TimeUnit.SECONDS);
        }
    }

    public void stopSchedule() {
        scheduleHours = 0;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    public int getScheduleHours() {
        return scheduleHours;
    }

    public BackupResult createBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "backup_" + timestamp + ".zip";
        File backupFile = new File(backupDir, fileName);

        try {
            Path serverDir = NeoDash.getServerRoot() == null
                    ? Path.of(".").toAbsolutePath().normalize()
                    : NeoDash.getServerRoot();

            List<String> toBackup = Arrays.asList(
                    "server.properties",
                    "eula.txt",
                    "world",
                    "world_nether",
                    "world_the_end",
                    "plugins",
                    "config");

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
                for (String name : toBackup) {
                    File file = serverDir.resolve(name).toFile();
                    if (file.exists()) {
                        if (file.isDirectory()) {
                            zipDirectory(file, name, zos);
                        } else {
                            zipFile(file, name, zos);
                        }
                    }
                }
            }

            long size = backupFile.length();
            cleanOldBackups(10);
            return new BackupResult(true, fileName, size, null);
        } catch (Exception e) {
            return new BackupResult(false, null, 0, e.getMessage());
        }
    }

    private void zipDirectory(File folder, String parentPath, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String path = parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, path, zos);
            } else {
                zipFile(file, path, zos);
            }
        }
    }

    private void zipFile(File file, String path, ZipOutputStream zos) {
        try {
            if (file.length() > 100L * 1024 * 1024) {
                return;
            }
            String name = file.getName().toLowerCase();
            if (name.equals("session.lock") || name.endsWith(".lock") || name.endsWith(".lck")
                    || name.endsWith(".pid") || name.endsWith(".log") || name.endsWith(".db-journal")) {
                return;
            }
            zos.putNextEntry(new ZipEntry(path));
            Files.copy(file.toPath(), zos);
            zos.closeEntry();
        } catch (IOException ignored) {
        }
    }

    private void cleanOldBackups(int keep) {
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null || backups.length <= keep) {
            return;
        }

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = keep; i < backups.length; i++) {
            backups[i].delete();
        }
    }

    public List<BackupInfo> listBackups() {
        List<BackupInfo> list = new ArrayList<>();
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null) {
            return list;
        }

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());
        for (File backup : backups) {
            list.add(new BackupInfo(backup.getName(), backup.lastModified(), backup.length()));
        }
        return list;
    }

    public File getBackupFile(String name) {
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")) {
            return null;
        }
        File file = new File(backupDir, name);
        return file.exists() ? file : null;
    }

    public boolean deleteBackup(String name) {
        File file = getBackupFile(name);
        return file != null && file.delete();
    }

    public boolean restoreBackup(String name) {
        File backup = getBackupFile(name);
        if (backup == null) {
            return false;
        }

        File restoreDir = new File(backupDir, "restore_" + System.currentTimeMillis());
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(backup);
            zipFile.stream().forEach(entry -> {
                try {
                    File destFile = new File(restoreDir, entry.getName());
                    if (entry.isDirectory()) {
                        destFile.mkdirs();
                    } else {
                        destFile.getParentFile().mkdirs();
                        Files.copy(zipFile.getInputStream(entry), destFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ignored) {
                }
            });
            zipFile.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record BackupInfo(String name, long timestamp, long size) {
        public String getFormattedSize() {
            if (size < 1024) {
                return size + " B";
            }
            if (size < 1024 * 1024) {
                return String.format("%.1f KB", size / 1024.0);
            }
            if (size < 1024 * 1024 * 1024) {
                return String.format("%.1f MB", size / (1024.0 * 1024.0));
            }
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    public record BackupResult(boolean success, String fileName, long size, String error) {
    }
}
