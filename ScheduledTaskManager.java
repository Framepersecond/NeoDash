package dash.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ScheduledTaskManager {

    public static final String TYPE_BROADCAST = "broadcast";
    public static final String TYPE_COMMAND = "command";

    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();
    private Connection connection;

    public ScheduledTaskManager() {
        this(Path.of("scheduled_tasks.db"), Logger.getLogger("NeoDash"));
    }

    public ScheduledTaskManager(Path dbPath, Logger logger) {
        this.logger = logger;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduled-task-manager");
            t.setDaemon(true);
            return t;
        });
        initDatabase(dbPath);
        startAllEnabled();
    }

    private void initDatabase(Path dbPath) {
        try {
            Path parent = dbPath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().normalize());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS scheduled_tasks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            task_type TEXT NOT NULL,
                            interval_minutes INTEGER NOT NULL,
                            payload TEXT NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            created_at INTEGER NOT NULL
                        )
                        """);
            }
        } catch (Exception e) {
            logger.warning("ScheduledTask DB init failed: " + e.getMessage());
        }
    }

    public void startAllEnabled() {
        for (ScheduledTask task : getAllTasks()) {
            if (task.enabled()) {
                scheduleTask(task);
            }
        }
    }

    public void stopAll() {
        for (ScheduledFuture<?> task : runningTasks.values()) {
            task.cancel(false);
        }
        runningTasks.clear();
    }

    public int addTask(String taskType, int intervalMinutes, String payload, boolean enabled) {
        String normalizedType = taskType == null ? "" : taskType.trim().toLowerCase();
        String normalizedPayload = payload == null ? "" : payload.trim();
        if ((!TYPE_BROADCAST.equals(normalizedType) && !TYPE_COMMAND.equals(normalizedType))
                || intervalMinutes < 1 || intervalMinutes > 10080
                || normalizedPayload.isBlank()) {
            return -1;
        }

        int id = -1;
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO scheduled_tasks (task_type, interval_minutes, payload, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, normalizedType);
            stmt.setInt(2, intervalMinutes);
            stmt.setString(3, normalizedPayload);
            stmt.setInt(4, enabled ? 1 : 0);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    id = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to add scheduled task: " + e.getMessage());
            return -1;
        }

        if (id > 0 && enabled) {
            ScheduledTask task = getTask(id);
            if (task != null) {
                scheduleTask(task);
            }
        }
        return id;
    }

    public void setEnabled(int taskId, boolean enabled) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE scheduled_tasks SET enabled = ? WHERE id = ?")) {
            stmt.setInt(1, enabled ? 1 : 0);
            stmt.setInt(2, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Failed to toggle task: " + e.getMessage());
        }

        if (enabled) {
            ScheduledTask task = getTask(taskId);
            if (task != null) {
                scheduleTask(task);
            }
        } else {
            cancelTask(taskId);
        }
    }

    public void deleteTask(int taskId) {
        cancelTask(taskId);
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM scheduled_tasks WHERE id = ?")) {
            stmt.setInt(1, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Failed to delete task: " + e.getMessage());
        }
    }

    private void cancelTask(int taskId) {
        ScheduledFuture<?> existing = runningTasks.remove(taskId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void scheduleTask(ScheduledTask task) {
        cancelTask(task.id());
        long intervalSeconds = Math.max(1L, task.intervalMinutes() * 60L);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> executeTask(task),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
        runningTasks.put(task.id(), future);
    }

    private void executeTask(ScheduledTask task) {
        switch (task.taskType()) {
            case TYPE_BROADCAST -> dash.WebActionLogger.log("SCHEDULED_BROADCAST", task.payload());
            case TYPE_COMMAND -> dash.WebActionLogger.log("SCHEDULED_COMMAND", task.payload());
            default -> logger.warning("Unknown scheduled task type: " + task.taskType());
        }
    }

    public ScheduledTask getTask(int id) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM scheduled_tasks WHERE id = ?")) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return fromRow(rs);
                }
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public List<ScheduledTask> getAllTasks() {
        List<ScheduledTask> tasks = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM scheduled_tasks ORDER BY created_at DESC")) {
            while (rs.next()) {
                tasks.add(fromRow(rs));
            }
        } catch (SQLException ignored) {
        }
        return tasks;
    }

    private ScheduledTask fromRow(ResultSet rs) throws SQLException {
        return new ScheduledTask(
                rs.getInt("id"),
                rs.getString("task_type"),
                rs.getInt("interval_minutes"),
                rs.getString("payload"),
                rs.getInt("enabled") == 1,
                rs.getLong("created_at"));
    }

    public boolean isRunning(int taskId) {
        return runningTasks.containsKey(taskId);
    }

    public void close() {
        stopAll();
        scheduler.shutdownNow();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    public record ScheduledTask(int id, String taskType, int intervalMinutes, String payload, boolean enabled, long createdAt) {
        public String getFormattedType() {
            return switch (taskType) {
                case TYPE_BROADCAST -> "Broadcast";
                case TYPE_COMMAND -> "Console Command";
                default -> taskType;
            };
        }

        public String getFormattedInterval() {
            if (intervalMinutes < 60) {
                return intervalMinutes + " min";
            }
            int h = intervalMinutes / 60;
            int m = intervalMinutes % 60;
            return m == 0 ? h + "h" : h + "h " + m + "m";
        }
    }
}

