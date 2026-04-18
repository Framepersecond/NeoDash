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
        if (username == null || permission == null) {
            return false;
        }
        if (isMainAdmin(username)) {
            return true;
        }
        Set<String> effective = getEffectivePermissions(username);
        return matchesPermission(effective, permission);
    }

    public Set<String> getEffectivePermissions(String username) {
        if (username == null) {
            return Set.of();
        }
        if (isMainAdmin(username)) {
            return Set.of("dash.web.*", "*");
        }
        if (databaseManager == null) {
            return Set.of();
        }

        Optional<DatabaseManager.UserRecord> user = databaseManager.getUserByUsername(username);
        if (user.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<String> effective = new LinkedHashSet<>();
        String role = user.get().globalRole();
        if (role != null && !role.isBlank()) {
            Map<String, Set<String>> rolePerms = databaseManager.getRolePermissionsMap();
            Set<String> perms = rolePerms.get(role.toUpperCase());
            if (perms != null) {
                effective.addAll(perms);
            }
        }
        return Set.copyOf(effective);
    }

    private static boolean matchesPermission(Set<String> grants, String requested) {
        if (grants.contains("dash.web.*") || grants.contains("*")) {
            return true;
        }
        for (String grant : grants) {
            if (grant.equalsIgnoreCase(requested)) {
                return true;
            }
            if (grant.endsWith(".*")) {
                String prefix = grant.substring(0, grant.length() - 1);
                if (requested.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
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
                    user.mainAdmin(),
                    user.bridgeUser(),
                    user.bridgeApproved()));
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

    // ──────────────────── Bridge SSO ────────────────────

    public record BridgeSsoResult(boolean created, boolean approved, String username, String approvalToken) {
    }

    public BridgeSsoResult getOrCreateBridgeUserForSso(String username) {
        if (username == null || username.isBlank() || databaseManager == null) {
            return new BridgeSsoResult(false, false, null, null);
        }
        String normalized = username.trim();

        Optional<DatabaseManager.UserRecord> existing = databaseManager.getUserByUsername(normalized);
        if (existing.isPresent()) {
            DatabaseManager.UserRecord user = existing.get();
            if (!user.bridgeUser()) {
                return new BridgeSsoResult(false, true, normalized, null);
            }
            return new BridgeSsoResult(false, user.bridgeApproved(), normalized, user.approvalToken());
        }

        String approvalToken = java.util.UUID.randomUUID().toString();
        String randomPassword = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
        String hash = hashPassword(randomPassword);
        long id = databaseManager.createBridgeUser(normalized, hash, "USER", approvalToken);
        return new BridgeSsoResult(id > 0, false, normalized, approvalToken);
    }

    public AuthResult approveBridgeUserSafe(String actor, String username, String role) {
        if (!isMainAdmin(actor)) {
            return new AuthResult(false, "only_main_admin", -1);
        }
        if (databaseManager == null) {
            return new AuthResult(false, "no_database", -1);
        }
        Optional<DatabaseManager.UserRecord> user = databaseManager.getUserByUsername(username);
        if (user.isEmpty()) {
            return new AuthResult(false, "user_not_found", -1);
        }
        if (!user.get().bridgeUser()) {
            return new AuthResult(false, "not_bridge_user", -1);
        }
        boolean ok = databaseManager.approveBridgeUser(username, role);
        return ok ? new AuthResult(true, "ok", user.get().id()) : new AuthResult(false, "save_failed", -1);
    }

    public AuthResult denyBridgeUserSafe(String actor, String username) {
        if (!isMainAdmin(actor)) {
            return new AuthResult(false, "only_main_admin", -1);
        }
        if (databaseManager == null) {
            return new AuthResult(false, "no_database", -1);
        }
        Optional<DatabaseManager.UserRecord> user = databaseManager.getUserByUsername(username);
        if (user.isEmpty()) {
            return new AuthResult(false, "user_not_found", -1);
        }
        if (!user.get().bridgeUser()) {
            return new AuthResult(false, "not_bridge_user", -1);
        }
        if (user.get().bridgeApproved()) {
            return new AuthResult(false, "already_approved", -1);
        }
        boolean ok = databaseManager.denyBridgeUser(username);
        return ok ? new AuthResult(true, "ok", -1) : new AuthResult(false, "save_failed", -1);
    }

    public List<UserInfo> getPendingBridgeUsers() {
        if (databaseManager == null) {
            return List.of();
        }
        List<UserInfo> result = new java.util.ArrayList<>();
        for (DatabaseManager.UserRecord user : databaseManager.getPendingBridgeUsers()) {
            result.add(new UserInfo(user.username(), user.globalRole(), "N/A", false, true, false));
        }
        return result;
    }

    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            String saltStr = Base64.getEncoder().encodeToString(salt);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return "$" + saltStr + "$" + Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hashPasswordUnsalted(String password) {
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

        // Salted format: $<base64-salt>$<base64-hash>
        if (expectedHash.startsWith("$") && expectedHash.indexOf('$', 1) > 1) {
            int secondDollar = expectedHash.indexOf('$', 1);
            String saltStr = expectedHash.substring(1, secondDollar);
            String storedHash = expectedHash.substring(secondDollar + 1);
            try {
                byte[] salt = Base64.getDecoder().decode(saltStr);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(salt);
                byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
                String candidate = Base64.getEncoder().encodeToString(hashed);
                return MessageDigest.isEqual(
                        candidate.getBytes(StandardCharsets.UTF_8),
                        storedHash.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return false;
            }
        }

        // Legacy unsalted format
        String candidate = hashPasswordUnsalted(password);
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    public record UserInfo(String username, String role, String linkedPlayer, boolean mainAdmin,
            boolean bridgeUser, boolean bridgeApproved) {
        public UserInfo(String username, String role, String linkedPlayer, boolean mainAdmin) {
            this(username, role, linkedPlayer, mainAdmin, false, false);
        }
    }

    public record AuthResult(boolean success, String message, long userId) {
    }
}
