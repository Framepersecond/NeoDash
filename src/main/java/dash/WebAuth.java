package dash;

import dash.data.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class WebAuth {

    private final DatabaseManager databaseManager;

    public WebAuth(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public boolean isRegistered() {
        return databaseManager != null && databaseManager.countUsers() > 0;
    }

    public boolean isSetupRequired() {
        return !isRegistered();
    }

    public Optional<AuthResult> authenticate(String username, String password) {
        if (databaseManager == null || username == null || password == null) {
            return Optional.empty();
        }

        Optional<DatabaseManager.UserRecord> user = databaseManager.getUserByUsername(username);
        if (user.isEmpty()) {
            return Optional.empty();
        }

        if (!verifyPassword(password, user.get().passwordHash())) {
            return Optional.empty();
        }

        return Optional.of(new AuthResult(true, "ok", user.get().id()));
    }

    public boolean check(String username, String password) {
        return authenticate(username, password).isPresent();
    }

    public Optional<DatabaseManager.UserRecord> getUserById(long userId) {
        if (databaseManager == null) {
            return Optional.empty();
        }
        return databaseManager.getUserById(userId);
    }

    public Optional<DatabaseManager.UserRecord> getUserByUsername(String username) {
        if (databaseManager == null) {
            return Optional.empty();
        }
        return databaseManager.getUserByUsername(username);
    }

    public boolean isMainAdmin(String username) {
        if (username == null || databaseManager == null) {
            return false;
        }
        return databaseManager.getUserByUsername(username)
                .map(DatabaseManager.UserRecord::mainAdmin)
                .orElse(false);
    }

    public boolean userHasPermission(String username, String permission) {
        // Temporary compatibility shim: ADMIN has broad rights until fine-grained RBAC migration lands.
        return isMainAdmin(username);
    }

    public Set<String> getEffectivePermissions(String username) {
        if (!isMainAdmin(username)) {
            return Set.of();
        }
        return Set.of("*");
    }

    public Map<String, UserInfo> getUsers() {
        if (databaseManager == null) {
            return Map.of();
        }

        Map<String, UserInfo> users = new LinkedHashMap<>();
        for (DatabaseManager.UserRecord user : databaseManager.listUsers()) {
            users.put(user.username(), new UserInfo(
                    user.username(),
                    user.globalRole(),
                    "N/A",
                    user.mainAdmin()));
        }
        return users;
    }

    public List<String> getRoleNames() {
        if (databaseManager == null) {
            return List.of("ADMIN", "USER");
        }
        List<String> roles = new java.util.ArrayList<>();
        for (DatabaseManager.RoleRecord role : databaseManager.listRoles()) {
            roles.add(role.name());
        }
        if (roles.isEmpty()) {
            return List.of("ADMIN", "USER");
        }
        return roles;
    }

    public Map<String, Integer> getRoleValues() {
        if (databaseManager == null) {
            Map<String, Integer> values = new LinkedHashMap<>();
            values.put("ADMIN", 1000);
            values.put("USER", 100);
            return values;
        }
        Map<String, Integer> values = databaseManager.getRoleValuesMap();
        if (values.isEmpty()) {
            values = new LinkedHashMap<>();
            values.put("ADMIN", 1000);
            values.put("USER", 100);
        }
        return values;
    }

    public Map<String, Set<String>> getRolesWithPermissions() {
        if (databaseManager == null) {
            Map<String, Set<String>> roles = new LinkedHashMap<>();
            roles.put("ADMIN", new LinkedHashSet<>(Set.of("*")));
            roles.put("USER", new LinkedHashSet<>());
            return roles;
        }
        Map<String, Set<String>> roles = databaseManager.getRolePermissionsMap();
        if (roles.isEmpty()) {
            roles.put("ADMIN", new LinkedHashSet<>(Set.of("*")));
            roles.put("USER", new LinkedHashSet<>());
        }
        return roles;
    }

    public int getActorRoleValue(String username) {
        if (username == null || username.isBlank() || databaseManager == null) {
            return 0;
        }
        Optional<DatabaseManager.UserRecord> user = databaseManager.getUserByUsername(username);
        if (user.isEmpty()) {
            return 0;
        }
        if (user.get().mainAdmin()) {
            int highest = 0;
            for (Integer value : getRoleValues().values()) {
                if (value != null) {
                    highest = Math.max(highest, value);
                }
            }
            return highest + 1;
        }
        return getRoleValues().getOrDefault(user.get().globalRole().toUpperCase(), 0);
    }

    public String getCurrentOwner2faCode() {
        return "N/A";
    }

    public int getOwner2faSecondsRemaining() {
        return 0;
    }

    public boolean verifyOwner2faCode(String code) {
        return true;
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean verifyPassword(String password, String expectedHash) {
        if (password == null || expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        String candidate = hashPassword(password);
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    public record UserInfo(String username, String role, String linkedPlayer, boolean mainAdmin) {
    }

    public record AuthResult(boolean success, String message, long userId) {
    }
}
