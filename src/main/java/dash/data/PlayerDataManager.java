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
import java.util.UUID;

public class PlayerDataManager {

    private Connection connection;

    public PlayerDataManager() {
        this(Path.of("playerdata.db"));
    }

    public PlayerDataManager(Path dbPath) {
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
                        CREATE TABLE IF NOT EXISTS players (
                            uuid TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            first_join INTEGER NOT NULL,
                            last_join INTEGER NOT NULL,
                            total_playtime INTEGER DEFAULT 0
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NOT NULL,
                            join_time INTEGER NOT NULL,
                            leave_time INTEGER,
                            ip_address TEXT,
                            FOREIGN KEY (uuid) REFERENCES players(uuid)
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NOT NULL,
                            admin_name TEXT NOT NULL,
                            note TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            FOREIGN KEY (uuid) REFERENCES players(uuid)
                        )
                        """);
            }
        } catch (Exception ignored) {
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    public void upsertPlayer(UUID uuid, String name, String ip) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            try (PreparedStatement stmt = connection.prepareStatement("""
                    INSERT INTO players (uuid, name, first_join, last_join, total_playtime)
                    VALUES (?, ?, ?, ?, 0)
                    ON CONFLICT(uuid) DO UPDATE SET name = ?, last_join = ?
                    """)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, name);
                stmt.setLong(3, now);
                stmt.setLong(4, now);
                stmt.setString(5, name);
                stmt.setLong(6, now);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO sessions (uuid, join_time, ip_address) VALUES (?, ?, ?)")) {
                stmt.setString(1, uuid.toString());
                stmt.setLong(2, now);
                stmt.setString(3, ip == null ? "" : ip);
                stmt.executeUpdate();
            }
        } catch (SQLException ignored) {
        }
    }

    public PlayerInfo getPlayerInfo(String uuidOrName) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM players WHERE uuid = ? OR name = ? COLLATE NOCASE")) {
            stmt.setString(1, uuidOrName);
            stmt.setString(2, uuidOrName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new PlayerInfo(
                            rs.getString("uuid"),
                            rs.getString("name"),
                            rs.getLong("first_join"),
                            rs.getLong("last_join"),
                            rs.getLong("total_playtime"));
                }
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public List<SessionInfo> getPlayerSessions(String uuid, int limit) {
        List<SessionInfo> sessions = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM sessions WHERE uuid = ? ORDER BY join_time DESC LIMIT ?")) {
            stmt.setString(1, uuid);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    sessions.add(new SessionInfo(
                            rs.getLong("join_time"),
                            rs.getLong("leave_time"),
                            rs.getString("ip_address")));
                }
            }
        } catch (SQLException ignored) {
        }
        return sessions;
    }

    public List<NoteInfo> getPlayerNotes(String uuid) {
        List<NoteInfo> notes = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM notes WHERE uuid = ? ORDER BY created_at DESC")) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notes.add(new NoteInfo(
                            rs.getInt("id"),
                            rs.getString("admin_name"),
                            rs.getString("note"),
                            rs.getLong("created_at")));
                }
            }
        } catch (SQLException ignored) {
        }
        return notes;
    }

    public void addNote(String uuid, String adminName, String note) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO notes (uuid, admin_name, note, created_at) VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, uuid);
            stmt.setString(2, adminName);
            stmt.setString(3, note);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public void deleteNote(int noteId) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM notes WHERE id = ?")) {
            stmt.setInt(1, noteId);
            stmt.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public List<PlayerInfo> getAllPlayers(int limit, int offset) {
        List<PlayerInfo> players = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM players ORDER BY last_join DESC LIMIT ? OFFSET ?")) {
            stmt.setInt(1, limit);
            stmt.setInt(2, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    players.add(new PlayerInfo(
                            rs.getString("uuid"),
                            rs.getString("name"),
                            rs.getLong("first_join"),
                            rs.getLong("last_join"),
                            rs.getLong("total_playtime")));
                }
            }
        } catch (SQLException ignored) {
        }
        return players;
    }

    public record PlayerInfo(String uuid, String name, long firstJoin, long lastJoin, long totalPlaytime) {
        public String getFormattedPlaytime() {
            long hours = totalPlaytime / 3600000;
            long minutes = (totalPlaytime % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }

    public record SessionInfo(long joinTime, long leaveTime, String ipAddress) {
        public String getDuration() {
            if (leaveTime == 0) {
                return "Online";
            }
            long duration = leaveTime - joinTime;
            long hours = duration / 3600000;
            long minutes = (duration % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }

    public record NoteInfo(int id, String adminName, String note, long createdAt) {
    }
}
