package dash.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

public class DatabaseManager implements AutoCloseable {

    private final Connection connection;

    public DatabaseManager() {
        this(Path.of("neodash_backup.db"));
    }

    public DatabaseManager(Path databasePath) {
        System.out.println("ACHTUNG! Die echte Datenbank liegt hier: " + databasePath.toAbsolutePath().normalize());
        try {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("SQLite Driver not found", e);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
            this.connection.setAutoCommit(true);
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
            }
            initSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database: " + e.getMessage(), e);
        }
    }

    public synchronized void initSchema() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY,
                        username TEXT UNIQUE NOT NULL,
                        password_hash TEXT NOT NULL,
                        global_role TEXT NOT NULL,
                        is_main_admin INTEGER NOT NULL DEFAULT 0
                    )
                    """);

            ensureUserColumnExists(statement, "is_main_admin", "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_single_main_admin ON users(is_main_admin) WHERE is_main_admin = 1");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS roles (
                        name TEXT PRIMARY KEY,
                        role_value INTEGER NOT NULL DEFAULT 100
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS role_permissions (
                        role_name TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        PRIMARY KEY (role_name, permission),
                        FOREIGN KEY (role_name) REFERENCES roles(name) ON DELETE CASCADE
                    )
                    """);

            statement.execute("INSERT OR IGNORE INTO roles(name, role_value) VALUES('ADMIN', 1000)");
            statement.execute("INSERT OR IGNORE INTO roles(name, role_value) VALUES('MODERATOR', 500)");
            statement.execute("INSERT OR IGNORE INTO roles(name, role_value) VALUES('USER', 100)");
            statement.execute("INSERT OR IGNORE INTO role_permissions(role_name, permission) VALUES('ADMIN', '*')");
            statement.execute("INSERT OR IGNORE INTO role_permissions(role_name, permission) VALUES('MODERATOR', 'dash.web.stats.read')");
            statement.execute("INSERT OR IGNORE INTO role_permissions(role_name, permission) VALUES('MODERATOR', 'dash.web.audit.read')");
            statement.execute("INSERT OR IGNORE INTO role_permissions(role_name, permission) VALUES('USER', 'dash.web.stats.read')");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS servers (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        ip_address TEXT DEFAULT '127.0.0.1',
                        path_to_dir TEXT NOT NULL,
                        runner_type TEXT NOT NULL,
                        start_command TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        dash_port INTEGER DEFAULT 8080,
                        bridge_api_port INTEGER,
                        bridge_secret TEXT,
                        use_plugin_interface BOOLEAN
                    )
                    """);

            ensureServerColumnExists(statement, "dash_port", "INTEGER DEFAULT 8080");
            ensureServerColumnExists(statement, "bridge_api_port", "INTEGER");
            ensureServerColumnExists(statement, "bridge_secret", "TEXT");
            ensureServerColumnExists(statement, "use_plugin_interface", "BOOLEAN");
            try {
                statement.execute("ALTER TABLE servers ADD COLUMN ip_address TEXT DEFAULT '127.0.0.1'");
            } catch (SQLException ignored) {
            }

            // Backfill toggle for existing rows using best-effort server type detection.
            statement.executeUpdate("""
                    UPDATE servers
                    SET use_plugin_interface = CASE
                        WHEN LOWER(COALESCE(start_command, '')) LIKE '%paper%' THEN 1
                        WHEN LOWER(COALESCE(start_command, '')) LIKE '%purpur%' THEN 1
                        WHEN LOWER(COALESCE(start_command, '')) LIKE '%spigot%' THEN 1
                        WHEN LOWER(COALESCE(start_command, '')) LIKE '%bukkit%' THEN 1
                        WHEN LOWER(COALESCE(name, '')) LIKE '%paper%' THEN 1
                        WHEN LOWER(COALESCE(name, '')) LIKE '%purpur%' THEN 1
                        WHEN LOWER(COALESCE(name, '')) LIKE '%spigot%' THEN 1
                        WHEN LOWER(COALESCE(name, '')) LIKE '%bukkit%' THEN 1
                        ELSE 0
                    END
                    WHERE use_plugin_interface IS NULL
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_permissions (
                        user_id INTEGER NOT NULL,
                        server_id INTEGER NOT NULL,
                        can_start_stop BOOLEAN NOT NULL DEFAULT 0,
                        can_use_console BOOLEAN NOT NULL DEFAULT 0,
                        can_manage_files BOOLEAN NOT NULL DEFAULT 0,
                        can_start BOOLEAN NOT NULL DEFAULT 0,
                        can_files BOOLEAN NOT NULL DEFAULT 0,
                        can_properties BOOLEAN NOT NULL DEFAULT 0,
                        can_server_settings BOOLEAN NOT NULL DEFAULT 0,
                        server_role TEXT DEFAULT 'VIEWER',
                        PRIMARY KEY (user_id, server_id),
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                        FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE
                    )
                    """);

            ensureServerPermissionColumnExists(statement, "can_start", "BOOLEAN NOT NULL DEFAULT 0");
            ensureServerPermissionColumnExists(statement, "can_files", "BOOLEAN NOT NULL DEFAULT 0");
            ensureServerPermissionColumnExists(statement, "can_properties", "BOOLEAN NOT NULL DEFAULT 0");
            ensureServerPermissionColumnExists(statement, "can_server_settings", "BOOLEAN NOT NULL DEFAULT 0");
            ensureServerPermissionColumnExists(statement, "server_role", "TEXT DEFAULT 'VIEWER'");

            // Backfill new offline capabilities from legacy flags for existing rows.
            statement.executeUpdate("""
                    UPDATE server_permissions
                    SET can_start = CASE WHEN can_start = 1 THEN 1 ELSE can_start_stop END,
                        can_files = CASE WHEN can_files = 1 THEN 1 ELSE can_manage_files END,
                        can_properties = CASE WHEN can_properties = 1 THEN 1 ELSE can_manage_files END,
                        can_server_settings = CASE WHEN can_server_settings = 1 THEN 1 ELSE can_manage_files END
                    """);

            statement.executeUpdate("""
                    UPDATE server_permissions
                    SET server_role = CASE WHEN server_role IS NULL OR TRIM(server_role) = '' THEN 'VIEWER' ELSE UPPER(server_role) END
                    """);

            ensureSingleMainAdmin(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create schema: " + e.getMessage(), e);
        }
    }

    private void ensureUserColumnExists(Statement statement, String columnName, String definition) throws SQLException {
        String sql = "PRAGMA table_info(users)";
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE users ADD COLUMN " + columnName + " " + definition);
    }

    private void ensureSingleMainAdmin(Statement statement) throws SQLException {
        long chosenId = -1L;
        try (ResultSet rs = statement.executeQuery("SELECT id FROM users WHERE is_main_admin = 1 ORDER BY id ASC LIMIT 1")) {
            if (rs.next()) {
                chosenId = rs.getLong("id");
            }
        }
        if (chosenId <= 0) {
            try (ResultSet rs = statement.executeQuery("SELECT id FROM users WHERE global_role = 'ADMIN' ORDER BY id ASC LIMIT 1")) {
                if (rs.next()) {
                    chosenId = rs.getLong("id");
                }
            }
        }
        if (chosenId <= 0) {
            try (ResultSet rs = statement.executeQuery("SELECT id FROM users ORDER BY id ASC LIMIT 1")) {
                if (rs.next()) {
                    chosenId = rs.getLong("id");
                }
            }
        }
        statement.executeUpdate("UPDATE users SET is_main_admin = 0 WHERE is_main_admin IS NULL OR is_main_admin != 0");
        if (chosenId > 0) {
            try (PreparedStatement promote = connection
                    .prepareStatement("UPDATE users SET is_main_admin = 1, global_role = 'ADMIN' WHERE id = ?")) {
                promote.setLong(1, chosenId);
                promote.executeUpdate();
            }
        }
    }

    private void ensureServerColumnExists(Statement statement, String columnName, String definition) throws SQLException {
        String sql = "PRAGMA table_info(servers)";
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE servers ADD COLUMN " + columnName + " " + definition);
    }

    private void ensureServerPermissionColumnExists(Statement statement, String columnName, String definition)
            throws SQLException {
        String sql = "PRAGMA table_info(server_permissions)";
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE server_permissions ADD COLUMN " + columnName + " " + definition);
    }

    public synchronized long countUsers() {
        String sql = "SELECT COUNT(*) AS total FROM users";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("total");
            }
            return 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count users: " + e.getMessage(), e);
        }
    }

    public synchronized long createUser(String username, String passwordHash, String globalRole) {
        return createUser(username, passwordHash, globalRole, false);
    }

    public synchronized long createUser(String username, String passwordHash, String globalRole, boolean mainAdmin) {
        String sql = "INSERT INTO users(username, password_hash, global_role, is_main_admin) VALUES(?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, globalRole);
            ps.setInt(4, mainAdmin ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long createdId = keys.getLong(1);
                    if (mainAdmin) {
                        transferMainAdmin(createdId);
                    }
                    return createdId;
                }
            }
            return -1L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create user: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<UserRecord> getUserById(long userId) {
        String sql = "SELECT id, username, password_hash, global_role, is_main_admin FROM users WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("global_role"),
                        rs.getInt("is_main_admin") == 1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read user by id: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<UserRecord> getUserByUsername(String username) {
        String sql = "SELECT id, username, password_hash, global_role, is_main_admin FROM users WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("global_role"),
                        rs.getInt("is_main_admin") == 1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read user by username: " + e.getMessage(), e);
        }
    }

    public synchronized List<UserRecord> listUsers() {
        String sql = "SELECT id, username, password_hash, global_role, is_main_admin FROM users ORDER BY id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<UserRecord> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new UserRecord(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("global_role"),
                        rs.getInt("is_main_admin") == 1));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list users: " + e.getMessage(), e);
        }
    }

    public synchronized boolean updateUserRole(String username, String globalRole) {
        String sql = "UPDATE users SET global_role = ? WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, globalRole);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update user role: " + e.getMessage(), e);
        }
    }

    public synchronized boolean deleteUserByUsername(String username) {
        String sql = "DELETE FROM users WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete user: " + e.getMessage(), e);
        }
    }

    public synchronized List<RoleRecord> listRoles() {
        String sql = "SELECT name, role_value FROM roles ORDER BY role_value DESC, name ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<RoleRecord> roles = new ArrayList<>();
            while (rs.next()) {
                roles.add(new RoleRecord(rs.getString("name"), rs.getInt("role_value")));
            }
            return roles;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list roles: " + e.getMessage(), e);
        }
    }

    public synchronized Map<String, Integer> getRoleValuesMap() {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (RoleRecord role : listRoles()) {
            values.put(role.name(), role.value());
        }
        return values;
    }

    public synchronized Map<String, Set<String>> getRolePermissionsMap() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (RoleRecord role : listRoles()) {
            result.put(role.name(), new LinkedHashSet<>());
        }

        String sql = "SELECT role_name, permission FROM role_permissions ORDER BY role_name ASC, permission ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String roleName = rs.getString("role_name");
                String permission = rs.getString("permission");
                result.computeIfAbsent(roleName, ignored -> new LinkedHashSet<>()).add(permission);
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list role permissions: " + e.getMessage(), e);
        }
    }

    public synchronized boolean createRole(String roleName, int roleValue, Set<String> defaultPermissions) {
        String normalizedName = normalizeRoleName(roleName);
        if (normalizedName.isBlank() || "MAIN_ADMIN".equals(normalizedName)) {
            return false;
        }

        String insertRoleSql = "INSERT INTO roles(name, role_value) VALUES(?, ?)";
        try (PreparedStatement rolePs = connection.prepareStatement(insertRoleSql)) {
            rolePs.setString(1, normalizedName);
            rolePs.setInt(2, Math.max(0, roleValue));
            int rows = rolePs.executeUpdate();
            if (rows <= 0) {
                return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create role: " + e.getMessage(), e);
        }

        if (defaultPermissions != null && !defaultPermissions.isEmpty()) {
            setRolePermissions(normalizedName, defaultPermissions);
        }
        return true;
    }

    public synchronized boolean updateRoleValue(String roleName, int roleValue) {
        String normalizedName = normalizeRoleName(roleName);
        String sql = "UPDATE roles SET role_value = ? WHERE name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, roleValue));
            ps.setString(2, normalizedName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update role value: " + e.getMessage(), e);
        }
    }

    public synchronized void updateRolePermissions(String roleName, List<String> addPermissions,
            List<String> removePermissions) {
        String normalizedName = normalizeRoleName(roleName);

        if (addPermissions != null) {
            for (String permission : addPermissions) {
                String normalizedPermission = normalizePermission(permission);
                if (normalizedPermission.isBlank()) {
                    continue;
                }
                String sql = "INSERT OR IGNORE INTO role_permissions(role_name, permission) VALUES(?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, normalizedName);
                    ps.setString(2, normalizedPermission);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to add role permission: " + e.getMessage(), e);
                }
            }
        }

        if (removePermissions != null) {
            for (String permission : removePermissions) {
                String normalizedPermission = normalizePermission(permission);
                if (normalizedPermission.isBlank()) {
                    continue;
                }
                String sql = "DELETE FROM role_permissions WHERE role_name = ? AND permission = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, normalizedName);
                    ps.setString(2, normalizedPermission);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to remove role permission: " + e.getMessage(), e);
                }
            }
        }
    }

    public synchronized boolean deleteRole(String roleName) {
        String normalizedName = normalizeRoleName(roleName);
        if ("ADMIN".equals(normalizedName) || "USER".equals(normalizedName) || "MAIN_ADMIN".equals(normalizedName)) {
            return false;
        }

        String reassignSql = "UPDATE users SET global_role = 'USER' WHERE global_role = ?";
        String deletePermsSql = "DELETE FROM role_permissions WHERE role_name = ?";
        String deleteRoleSql = "DELETE FROM roles WHERE name = ?";
        try (PreparedStatement reassignPs = connection.prepareStatement(reassignSql);
                PreparedStatement deletePermsPs = connection.prepareStatement(deletePermsSql);
                PreparedStatement deleteRolePs = connection.prepareStatement(deleteRoleSql)) {
            reassignPs.setString(1, normalizedName);
            reassignPs.executeUpdate();

            deletePermsPs.setString(1, normalizedName);
            deletePermsPs.executeUpdate();

            deleteRolePs.setString(1, normalizedName);
            return deleteRolePs.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete role: " + e.getMessage(), e);
        }
    }

    public synchronized long createServer(String name, String pathToDir, String runnerType, String startCommand, int port) {
        return createServer(name, pathToDir, runnerType, startCommand, port, null, null, "127.0.0.1", 8080);
    }

    public synchronized long createServer(String name, String pathToDir, String runnerType, String startCommand,
            int port, Integer bridgeApiPort, String bridgeSecret) {
        return createServer(name, pathToDir, runnerType, startCommand, port, bridgeApiPort, bridgeSecret,
                "127.0.0.1", 8080);
    }

    public synchronized long createServer(String name, String pathToDir, String runnerType, String startCommand,
            int port, Integer bridgeApiPort, String bridgeSecret, String ipAddress) {
        return createServer(name, pathToDir, runnerType, startCommand, port, bridgeApiPort, bridgeSecret, ipAddress,
                8080);
    }

    public synchronized long createServer(String name, String pathToDir, String runnerType, String startCommand,
            int port, Integer bridgeApiPort, String bridgeSecret, String ipAddress, Integer dashPort) {
        String inferredType = inferServerType(name, pathToDir, startCommand);
        return createServer(name, pathToDir, runnerType, startCommand, port, bridgeApiPort, bridgeSecret, ipAddress,
                dashPort, inferredType, null);
    }

    public synchronized long createServer(String name, String pathToDir, String runnerType, String startCommand,
            int port, Integer bridgeApiPort, String bridgeSecret, String ipAddress, Integer dashPort,
            String serverType) {
        return createServer(name, pathToDir, runnerType, startCommand, port, bridgeApiPort, bridgeSecret, ipAddress,
                dashPort, serverType, null);
    }

    public synchronized long createServer(String name, String pathToDir, String runnerType, String startCommand,
            int port, Integer bridgeApiPort, String bridgeSecret, String ipAddress, Integer dashPort,
            String serverType, Boolean usePluginInterface) {
        String sql = "INSERT INTO servers(name, ip_address, path_to_dir, runner_type, start_command, port, dash_port, bridge_api_port, bridge_secret, use_plugin_interface) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, normalizeIpAddress(ipAddress));
            ps.setString(3, pathToDir);
            ps.setString(4, runnerType);
            ps.setString(5, startCommand);
            ps.setInt(6, port);
            if (dashPort == null || dashPort < 1 || dashPort > 65535) {
                ps.setInt(7, 8080);
            } else {
                ps.setInt(7, dashPort);
            }
            if (bridgeApiPort == null || bridgeApiPort <= 0) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, bridgeApiPort);
            }
            if (bridgeSecret == null || bridgeSecret.isBlank()) {
                ps.setNull(9, java.sql.Types.VARCHAR);
            } else {
                ps.setString(9, bridgeSecret);
            }
            ps.setBoolean(10, resolveUsePluginInterfaceDefault(usePluginInterface, serverType, name, pathToDir, startCommand));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create server: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<ServerRecord> getServerById(long serverId) {
        String sql = "SELECT id, name, ip_address, path_to_dir, runner_type, start_command, port, dash_port, bridge_api_port, bridge_secret, use_plugin_interface FROM servers WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ServerRecord(
                        rs.getLong("id"),
                        rs.getString("name"),
                        normalizeIpAddress(rs.getString("ip_address")),
                        rs.getString("path_to_dir"),
                        rs.getString("runner_type"),
                        rs.getString("start_command"),
                        rs.getInt("port"),
                        getNullableInt(rs, "dash_port"),
                        getNullableInt(rs, "bridge_api_port"),
                        rs.getString("bridge_secret"),
                        rs.getBoolean("use_plugin_interface")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read server by id: " + e.getMessage(), e);
        }
    }

    public synchronized void updateServer(long serverId, String name, String pathToDir, String runnerType,
            String startCommand, int port, int bridgeApiPort, String bridgeSecret, String ipAddress) {
        Integer dashPort = getServerById(serverId).map(ServerRecord::dashPort).orElse(8080);
        updateServer(serverId, name, pathToDir, runnerType, startCommand, port, dashPort, bridgeApiPort, bridgeSecret,
                ipAddress);
    }

    public synchronized void updateServer(long serverId, String name, String pathToDir, String runnerType,
            String startCommand, int port, Integer dashPort, int bridgeApiPort, String bridgeSecret, String ipAddress) {
        boolean defaultUsePluginInterface = getServerById(serverId)
                .map(ServerRecord::usePluginInterface)
                .orElse(resolveUsePluginInterfaceDefault(null, null, name, pathToDir, startCommand));
        updateServer(serverId, name, pathToDir, runnerType, startCommand, port, dashPort, bridgeApiPort,
                bridgeSecret, ipAddress, defaultUsePluginInterface);
    }

    public synchronized void updateServer(long serverId, String name, String pathToDir, String runnerType,
            String startCommand, int port, Integer dashPort, int bridgeApiPort, String bridgeSecret, String ipAddress,
            boolean usePluginInterface) {
        String sql = """
                UPDATE servers
                SET name = ?,
                    ip_address = ?,
                    path_to_dir = ?,
                    runner_type = ?,
                    start_command = ?,
                    port = ?,
                    dash_port = ?,
                    bridge_api_port = ?,
                    bridge_secret = ?,
                    use_plugin_interface = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, normalizeIpAddress(ipAddress));
            ps.setString(3, pathToDir);
            ps.setString(4, runnerType);
            ps.setString(5, startCommand);
            ps.setInt(6, port);
            if (dashPort == null || dashPort < 1 || dashPort > 65535) {
                ps.setInt(7, 8080);
            } else {
                ps.setInt(7, dashPort);
            }
            if (bridgeApiPort <= 0) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, bridgeApiPort);
            }
            if (bridgeSecret == null || bridgeSecret.isBlank()) {
                ps.setNull(9, java.sql.Types.VARCHAR);
            } else {
                ps.setString(9, bridgeSecret);
            }
            ps.setBoolean(10, usePluginInterface);
            ps.setLong(11, serverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update server: " + e.getMessage(), e);
        }
    }

    public synchronized List<ServerRecord> listServers() {
        String sql = "SELECT id, name, ip_address, path_to_dir, runner_type, start_command, port, dash_port, bridge_api_port, bridge_secret, use_plugin_interface FROM servers ORDER BY id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<ServerRecord> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new ServerRecord(
                        rs.getLong("id"),
                        rs.getString("name"),
                        normalizeIpAddress(rs.getString("ip_address")),
                        rs.getString("path_to_dir"),
                        rs.getString("runner_type"),
                        rs.getString("start_command"),
                        rs.getInt("port"),
                        getNullableInt(rs, "dash_port"),
                        getNullableInt(rs, "bridge_api_port"),
                        rs.getString("bridge_secret"),
                        rs.getBoolean("use_plugin_interface")));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list servers: " + e.getMessage(), e);
        }
    }

    public synchronized boolean deleteServer(long serverId) {
        String sql = "DELETE FROM servers WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, serverId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete server: " + e.getMessage(), e);
        }
    }

    public synchronized List<ServerRecord> getServersForUser(long userId) {
        Optional<UserRecord> userOpt = getUserById(userId);
        if (userOpt.isPresent() && "ADMIN".equalsIgnoreCase(userOpt.get().globalRole())) {
            return listServers();
        }

        String sql = """
                SELECT s.id, s.name, s.ip_address, s.path_to_dir, s.runner_type, s.start_command, s.port, s.dash_port, s.bridge_api_port, s.bridge_secret, s.use_plugin_interface
                FROM servers s
                INNER JOIN server_permissions sp ON sp.server_id = s.id
                WHERE sp.user_id = ?
                  AND (sp.can_start_stop = 1 OR sp.can_use_console = 1 OR sp.can_manage_files = 1
                       OR sp.can_start = 1 OR sp.can_files = 1 OR sp.can_properties = 1)
                ORDER BY s.id ASC
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ServerRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new ServerRecord(
                            rs.getLong("id"),
                            rs.getString("name"),
                            normalizeIpAddress(rs.getString("ip_address")),
                            rs.getString("path_to_dir"),
                            rs.getString("runner_type"),
                            rs.getString("start_command"),
                            rs.getInt("port"),
                            getNullableInt(rs, "dash_port"),
                            getNullableInt(rs, "bridge_api_port"),
                            rs.getString("bridge_secret"),
                            rs.getBoolean("use_plugin_interface")));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list servers for user: " + e.getMessage(), e);
        }
    }

    public synchronized void upsertServerPermission(long userId, long serverId,
            boolean canStartStop, boolean canUseConsole, boolean canManageFiles) {
        upsertServerPermission(userId, serverId, canStartStop, canUseConsole, canManageFiles,
                canStartStop, canManageFiles, canManageFiles, canManageFiles);
    }

    public synchronized void upsertServerPermission(long userId, long serverId,
            boolean canStartStop, boolean canUseConsole, boolean canManageFiles,
            boolean canStart, boolean canFiles, boolean canProperties) {
        upsertServerPermission(userId, serverId, canStartStop, canUseConsole, canManageFiles,
                canStart, canFiles, canProperties, canProperties);
    }

    public synchronized void upsertServerPermission(long userId, long serverId,
            boolean canStartStop, boolean canUseConsole, boolean canManageFiles,
            boolean canStart, boolean canFiles, boolean canProperties,
            boolean canServerSettings) {
        String sql = """
                INSERT INTO server_permissions(
                    user_id, server_id, can_start_stop, can_use_console, can_manage_files,
                    can_start, can_files, can_properties, can_server_settings
                )
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, server_id)
                DO UPDATE SET
                    can_start_stop = excluded.can_start_stop,
                    can_use_console = excluded.can_use_console,
                    can_manage_files = excluded.can_manage_files,
                    can_start = excluded.can_start,
                    can_files = excluded.can_files,
                    can_properties = excluded.can_properties,
                    can_server_settings = excluded.can_server_settings
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, serverId);
            ps.setBoolean(3, canStartStop);
            ps.setBoolean(4, canUseConsole);
            ps.setBoolean(5, canManageFiles);
            ps.setBoolean(6, canStart);
            ps.setBoolean(7, canFiles);
            ps.setBoolean(8, canProperties);
            ps.setBoolean(9, canServerSettings);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert server permission: " + e.getMessage(), e);
        }
    }

    public synchronized void assignUserToServer(long userId, long serverId) {
        Optional<ServerPermissionRecord> existing = getServerPermission(userId, serverId);
        if (existing.isPresent()) {
            ServerPermissionRecord permission = existing.get();
            upsertServerPermission(userId, serverId,
                    permission.canStartStop(), permission.canUseConsole(), permission.canManageFiles(),
                    permission.canStart(), permission.canFiles(), permission.canProperties(),
                    permission.canServerSettings());
            return;
        }
        upsertServerPermission(userId, serverId,
                false, false, false,
                false, false, false,
                false);
    }

    public synchronized boolean revokeUserFromServer(long userId, long serverId) {
        String sql = "DELETE FROM server_permissions WHERE user_id = ? AND server_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, serverId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to revoke user server assignment: " + e.getMessage(), e);
        }
    }

    public synchronized List<UserServerAssignment> getServerAssignmentsForUser(long userId) {
        String sql = """
                SELECT s.id, s.name
                FROM server_permissions sp
                JOIN servers s ON s.id = sp.server_id
                WHERE sp.user_id = ?
                ORDER BY s.name COLLATE NOCASE ASC
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<UserServerAssignment> assignments = new ArrayList<>();
                while (rs.next()) {
                    assignments.add(new UserServerAssignment(
                            rs.getLong("id"),
                            rs.getString("name")));
                }
                return assignments;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list user server assignments: " + e.getMessage(), e);
        }
    }

    public synchronized boolean hasPermissionForServer(long userId, long serverId,
            boolean requireStartStop, boolean requireConsole, boolean requireFileManage) {
        String sql = """
                SELECT can_start_stop, can_use_console, can_manage_files, can_start, can_files, can_properties, can_server_settings
                FROM server_permissions
                WHERE user_id = ? AND server_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                boolean canStartStop = rs.getBoolean("can_start_stop");
                boolean canUseConsole = rs.getBoolean("can_use_console");
                boolean canManageFiles = rs.getBoolean("can_manage_files");
                boolean canStart = rs.getBoolean("can_start");
                boolean canFiles = rs.getBoolean("can_files");
                return (!requireStartStop || canStartStop || canStart)
                        && (!requireConsole || canUseConsole)
                        && (!requireFileManage || canManageFiles || canFiles);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check server permission: " + e.getMessage(), e);
        }
    }

    public synchronized boolean hasPermissionForServer(long userId, long serverId) {
        return hasPermissionForServer(userId, serverId, false, false, false)
                || hasPermissionForServer(userId, serverId, true, false, false)
                || hasPermissionForServer(userId, serverId, false, true, false)
                || hasPermissionForServer(userId, serverId, false, false, true)
                || getUserById(userId).map(u -> "ADMIN".equalsIgnoreCase(u.globalRole())).orElse(false);
    }

    public synchronized Optional<ServerPermissionRecord> getServerPermission(long userId, long serverId) {
        String sql = """
                SELECT can_start_stop, can_use_console, can_manage_files, can_start, can_files, can_properties, can_server_settings
                FROM server_permissions
                WHERE user_id = ? AND server_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ServerPermissionRecord(
                        rs.getBoolean("can_start_stop"),
                        rs.getBoolean("can_use_console"),
                        rs.getBoolean("can_manage_files"),
                        rs.getBoolean("can_start"),
                        rs.getBoolean("can_files"),
                        rs.getBoolean("can_properties"),
                        rs.getBoolean("can_server_settings")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read server permission: " + e.getMessage(), e);
        }
    }

    public synchronized List<ServerUserPermissionView> listServerUserPermissions(long serverId) {
        String sql = """
                SELECT u.id,
                       u.username,
                       u.global_role,
                       COALESCE(sp.can_start_stop, 0) AS can_start_stop,
                       COALESCE(sp.can_use_console, 0) AS can_use_console,
                       COALESCE(sp.can_manage_files, 0) AS can_manage_files,
                       COALESCE(sp.can_start, 0) AS can_start,
                       COALESCE(sp.can_files, 0) AS can_files,
                       COALESCE(sp.can_properties, 0) AS can_properties,
                       COALESCE(sp.can_server_settings, 0) AS can_server_settings
                FROM users u
                LEFT JOIN server_permissions sp
                       ON sp.user_id = u.id
                      AND sp.server_id = ?
                ORDER BY CASE WHEN u.is_main_admin = 1 THEN 0 ELSE 1 END, u.id ASC
                """;
        long mainAdminId = getMainAdminId().orElse(-1L);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ServerUserPermissionView> rows = new ArrayList<>();
                while (rs.next()) {
                    long userId = rs.getLong("id");
                    boolean mainAdmin = userId == mainAdminId;
                    boolean canStart = rs.getBoolean("can_start") || rs.getBoolean("can_start_stop") || mainAdmin;
                    boolean canFiles = rs.getBoolean("can_files") || rs.getBoolean("can_manage_files") || mainAdmin;
                    boolean canProperties = rs.getBoolean("can_properties") || rs.getBoolean("can_manage_files")
                            || mainAdmin;
                    boolean canServerSettings = rs.getBoolean("can_server_settings") || mainAdmin;
                    rows.add(new ServerUserPermissionView(
                            userId,
                            rs.getString("username"),
                            rs.getString("global_role"),
                            canStart,
                            canFiles,
                            canProperties,
                            canServerSettings,
                            mainAdmin));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list server user permissions: " + e.getMessage(), e);
        }
    }

    public synchronized boolean hasOfflineStartPermission(long userId, long serverId) {
        return hasOfflinePermission(userId, serverId, OfflinePermissionType.START);
    }

    public synchronized boolean hasOfflineFilesPermission(long userId, long serverId) {
        return hasOfflinePermission(userId, serverId, OfflinePermissionType.FILES);
    }

    public synchronized boolean hasOfflinePropertiesPermission(long userId, long serverId) {
        return hasOfflinePermission(userId, serverId, OfflinePermissionType.PROPERTIES);
    }

    public synchronized boolean hasOfflineServerSettingsPermission(long userId, long serverId) {
        return hasOfflinePermission(userId, serverId, OfflinePermissionType.SERVER_SETTINGS);
    }

    public synchronized Optional<Long> getMainAdminId() {
        String sql = "SELECT id FROM users WHERE is_main_admin = 1 ORDER BY id ASC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(rs.getLong("id"));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve main admin user: " + e.getMessage(), e);
        }
    }

    public synchronized boolean transferMainAdmin(String targetUsername) {
        if (targetUsername == null || targetUsername.isBlank()) {
            return false;
        }
        Optional<UserRecord> targetUser = getUserByUsername(targetUsername);
        if (targetUser.isEmpty()) {
            return false;
        }
        return transferMainAdmin(targetUser.get().id());
    }

    public synchronized boolean transferMainAdmin(long targetUserId) {
        if (targetUserId <= 0) {
            return false;
        }
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement demote = connection.prepareStatement("UPDATE users SET is_main_admin = 0 WHERE is_main_admin = 1");
                    PreparedStatement promote = connection.prepareStatement(
                            "UPDATE users SET is_main_admin = 1, global_role = 'ADMIN' WHERE id = ?")) {
                demote.executeUpdate();
                promote.setLong(1, targetUserId);
                int updated = promote.executeUpdate();
                if (updated <= 0) {
                    connection.rollback();
                    connection.setAutoCommit(previousAutoCommit);
                    return false;
                }
                connection.commit();
                connection.setAutoCommit(previousAutoCommit);
                return true;
            } catch (SQLException ex) {
                connection.rollback();
                connection.setAutoCommit(previousAutoCommit);
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to transfer main admin: " + e.getMessage(), e);
        }
    }

    private boolean hasOfflinePermission(long userId, long serverId, OfflinePermissionType type) {
        if (userId <= 0 || serverId <= 0) {
            return false;
        }
        if (getMainAdminId().map(id -> id == userId).orElse(false)) {
            return true;
        }

        Optional<ServerPermissionRecord> permission = getServerPermission(userId, serverId);
        if (permission.isEmpty()) {
            return false;
        }
        return switch (type) {
            case START -> permission.get().canStart() || permission.get().canStartStop();
            case FILES -> permission.get().canFiles() || permission.get().canManageFiles();
            case PROPERTIES -> permission.get().canProperties() || permission.get().canManageFiles();
            case SERVER_SETTINGS -> permission.get().canServerSettings();
        };
    }

    private void setRolePermissions(String roleName, Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return;
        }
        for (String permission : permissions) {
            String normalizedPermission = normalizePermission(permission);
            if (normalizedPermission.isBlank()) {
                continue;
            }
            String sql = "INSERT OR IGNORE INTO role_permissions(role_name, permission) VALUES(?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, roleName);
                ps.setString(2, normalizedPermission);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to seed role permissions: " + e.getMessage(), e);
            }
        }
    }

    private String normalizeRoleName(String roleName) {
        if (roleName == null) {
            return "";
        }
        return roleName.trim().replace(' ', '_').replace('.', '_').toUpperCase();
    }

    private String normalizePermission(String permission) {
        return permission == null ? "" : permission.trim();
    }

    @Override
    public synchronized void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String normalizeIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "127.0.0.1";
        }
        return ipAddress.trim();
    }

    private boolean resolveUsePluginInterfaceDefault(Boolean explicit, String serverType, String name,
            String pathToDir, String startCommand) {
        if (explicit != null) {
            return explicit;
        }
        String resolvedType = normalizeServerType(serverType);
        if (resolvedType.isBlank()) {
            resolvedType = inferServerType(name, pathToDir, startCommand);
        }
        return switch (resolvedType) {
            case "PAPER", "PURPUR", "SPIGOT", "BUKKIT" -> true;
            default -> false;
        };
    }

    private String inferServerType(String name, String pathToDir, String startCommand) {
        String combined = (name == null ? "" : name) + " "
                + (pathToDir == null ? "" : pathToDir) + " "
                + (startCommand == null ? "" : startCommand);
        String lower = combined.toLowerCase(Locale.ROOT);
        if (lower.contains("paper")) {
            return "PAPER";
        }
        if (lower.contains("purpur")) {
            return "PURPUR";
        }
        if (lower.contains("spigot")) {
            return "SPIGOT";
        }
        if (lower.contains("bukkit")) {
            return "BUKKIT";
        }
        if (lower.contains("fabric")) {
            return "FABRIC";
        }
        if (lower.contains("quilt")) {
            return "QUILT";
        }
        if (lower.contains("vanilla")) {
            return "VANILLA";
        }
        return "UNKNOWN";
    }

    private String normalizeServerType(String serverType) {
        return serverType == null ? "" : serverType.trim().toUpperCase(Locale.ROOT);
    }

    public record UserRecord(long id, String username, String passwordHash, String globalRole, boolean mainAdmin) {
    }

    public record RoleRecord(String name, int value) {
    }

    public record UserServerAssignment(long serverId, String serverName) {
    }

    public record ServerPermissionRecord(
            boolean canStartStop,
            boolean canUseConsole,
            boolean canManageFiles,
            boolean canStart,
            boolean canFiles,
            boolean canProperties,
            boolean canServerSettings) {
    }

    public record ServerUserPermissionView(
            long userId,
            String username,
            String globalRole,
            boolean canStart,
            boolean canFiles,
            boolean canProperties,
            boolean canServerSettings,
            boolean mainAdmin) {
    }

    private enum OfflinePermissionType {
        START,
        FILES,
        PROPERTIES,
        SERVER_SETTINGS
    }

    public record ServerRecord(long id, String name, String ipAddress, String pathToDir, String runnerType,
            String startCommand, int port, Integer dashPort, Integer bridgeApiPort, String bridgeSecret,
            boolean usePluginInterface) {
    }
}

