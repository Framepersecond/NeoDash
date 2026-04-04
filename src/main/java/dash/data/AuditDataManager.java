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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class AuditDataManager {

    private final Logger logger;
    private final ExecutorService writer;
    private Connection connection;

    public AuditDataManager() {
        this(Path.of("audit.db"), Logger.getLogger("NeoDash"));
    }

    public AuditDataManager(Path dbPath, Logger logger) {
        this.logger = logger;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "audit-db-writer");
            t.setDaemon(true);
            return t;
        });
        initDatabase(dbPath);
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
                        CREATE TABLE IF NOT EXISTS audit_log (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            timestamp INTEGER NOT NULL,
                            username TEXT NOT NULL,
                            action TEXT NOT NULL,
                            details TEXT,
                            ip_address TEXT
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action)");
            }
        } catch (Exception e) {
            logger.warning("Audit DB init failed: " + e.getMessage());
        }
    }

    public void insertLog(String username, String action, String details, String ipAddress) {
        writer.submit(() -> {
            if (connection == null) {
                return;
            }
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO audit_log (timestamp, username, action, details, ip_address) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, username != null ? username : "SYSTEM");
                stmt.setString(3, action == null ? "UNKNOWN" : action);
                stmt.setString(4, details == null ? "" : details);
                stmt.setString(5, ipAddress != null ? ipAddress : "");
                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.warning("Failed to insert audit log: " + e.getMessage());
            }
        });
    }

    public List<AuditEntry> getRecentLogs(int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        if (connection == null) {
            return entries;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, timestamp, username, action, details, ip_address FROM audit_log ORDER BY timestamp DESC LIMIT ?")) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(
                            rs.getInt("id"),
                            rs.getLong("timestamp"),
                            rs.getString("username"),
                            rs.getString("action"),
                            rs.getString("details"),
                            rs.getString("ip_address")));
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to query audit logs: " + e.getMessage());
        }
        return entries;
    }

    public List<AuditEntry> searchLogs(String query, int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        if (connection == null) {
            return entries;
        }
        String sql = "SELECT id, timestamp, username, action, details, ip_address FROM audit_log "
                + "WHERE username LIKE ? OR action LIKE ? OR details LIKE ? ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            String pattern = "%" + (query == null ? "" : query) + "%";
            stmt.setString(1, pattern);
            stmt.setString(2, pattern);
            stmt.setString(3, pattern);
            stmt.setInt(4, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(
                            rs.getInt("id"),
                            rs.getLong("timestamp"),
                            rs.getString("username"),
                            rs.getString("action"),
                            rs.getString("details"),
                            rs.getString("ip_address")));
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to search audit logs: " + e.getMessage());
        }
        return entries;
    }

    public int countLogs() {
        if (connection == null) {
            return 0;
        }
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logger.warning("Failed to count audit logs: " + e.getMessage());
            return 0;
        }
    }

    public void close() {
        writer.shutdownNow();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    public record AuditEntry(int id, long timestamp, String username, String action, String details, String ipAddress) {
        public String getFormattedTime() {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
            return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}
