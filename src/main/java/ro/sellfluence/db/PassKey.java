package ro.sellfluence.db;

import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class PassKey {

    private static final SecureRandom random = new SecureRandom();

    public enum Role {
        nobody, user, admin;

        static Role fromString(String r) {
            return switch (r) {
                case "user" -> user;
                case "admin" -> admin;
                case null, default -> nobody;
            };
        }
    }

    public record User(String username, Role role) {
    }

    public record AdminUser(long id, String username, Role role) {
    }

    /**
     * Return credential descriptors for the given username (used to build allowCredentials).
     */
    public static Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(Connection db, String username) throws SQLException {
        final String sql =
                """
                        select wc.credential_id
                        from webauthn_credential wc
                        join app_user u on u.id = wc.user_id
                        where u.username = ?
                        and wc.is_revoked = false
                        """;

        var result = new HashSet<PublicKeyCredentialDescriptor>();
        try (var ps = db.prepareStatement(sql)) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] idBytes = rs.getBytes("credential_id");
                    if (idBytes != null) {
                        var id = new ByteArray(idBytes);
                        result.add(
                                PublicKeyCredentialDescriptor.builder()
                                        .id(id)
                                        .build()
                        );
                    }
                }
            }
        }
        return result;
    }

    /**
     * Map username -> userHandle (opaque stable byte array).
     */
    public static Optional<ByteArray> getUserHandleForUsername(Connection db, String username) {
        final String sql =
                "select user_handle from app_user where username = ?";

        try (var ps = db.prepareStatement(sql)) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] handle = rs.getBytes("user_handle");
                    if (handle != null) return Optional.of(new ByteArray(handle));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in getUserHandleForUsername", e);
        }
        return Optional.empty();
    }

    /**
     * Map userHandle -> username.
     */
    public static Optional<String> getUsernameForUserHandle(Connection db, ByteArray userHandle) {
        final String sql =
                "select username from app_user where user_handle = ?";

        try (var ps = db.prepareStatement(sql)) {
            ps.setBytes(1, userHandle.getBytes());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("username"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in getUsernameForUserHandle", e);
        }
        return Optional.empty();
    }

    /**
     * Map userHandle -> username.
     */
    public static Optional<User> getUserForUserHandle(Connection db, ByteArray userHandle) {
        final String sql =
                "select username, role from app_user where user_handle = ?";

        try (var ps = db.prepareStatement(sql)) {
            ps.setBytes(1, userHandle.getBytes());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new User(
                                    rs.getString("username"),
                                    Role.fromString(rs.getString("role"))
                            )
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in getUsernameForUserHandle", e);
        }
        return Optional.empty();
    }

    public static List<AdminUser> listUsers(Connection db) {
        final String sql = "select id, username, role from app_user order by username";
        var users = new ArrayList<AdminUser>();
        try (var ps = db.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(new AdminUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        Role.fromString(rs.getString("role"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in listUsers", e);
        }
        return users;
    }

    public static int updateUserRole(Connection db, long userId, Role role) {
        final String sql =
                """
                        update app_user
                        set role = ?, updated_at = now()
                        where id = ?
                        """;
        try (var ps = db.prepareStatement(sql)) {
            if (role == Role.nobody) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, role.name());
            }
            ps.setLong(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error in updateUserRole", e);
        }
    }

    public static int deleteUser(Connection db, long userId) {
        final String sql = "delete from app_user where id = ?";
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error in deleteUser", e);
        }
    }

    /**
     * Look up a single credential by (credentialId, userHandle) — used during assertion verification.
     */
    public static Optional<RegisteredCredential> lookup(Connection db, ByteArray credentialId, ByteArray userHandle) {
        final String sql =
                """
                        select wc.public_key_cose, wc.sign_count, u.user_handle
                        from webauthn_credential wc
                        join app_user u on u.id = wc.user_id
                        where wc.credential_id = ?
                          and u.user_handle = ?
                          and wc.is_revoked = false
                        """;

        try (var ps = db.prepareStatement(sql)) {
            ps.setBytes(1, credentialId.getBytes());
            ps.setBytes(2, userHandle.getBytes());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] pk = rs.getBytes("public_key_cose");
                    long signCount = rs.getLong("sign_count");
                    byte[] uh = rs.getBytes("user_handle");

                    return Optional.of(
                            RegisteredCredential.builder()
                                    .credentialId(credentialId)
                                    .userHandle(new ByteArray(uh))
                                    .publicKeyCose(new ByteArray(pk))
                                    .signatureCount(signCount)
                                    .build()
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in lookup", e);
        }
        return Optional.empty();
    }

    /**
     * Look up all credentials with a given credentialId (rare; multi-user sharing not expected, but API requires it).
     */
    public static Set<RegisteredCredential> lookupAll(Connection db, ByteArray credentialId) {
        final String sql =
                """
                        select wc.public_key_cose, wc.sign_count, u.user_handle
                        from webauthn_credential wc
                        join app_user u on u.id = wc.user_id
                        where wc.credential_id = ?
                          and wc.is_revoked = false
                        """;

        var out = new HashSet<RegisteredCredential>();
        try (var ps = db.prepareStatement(sql)) {
            ps.setBytes(1, credentialId.getBytes());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] pk = rs.getBytes("public_key_cose");
                    long signCount = rs.getLong("sign_count");
                    byte[] uh = rs.getBytes("user_handle");

                    out.add(
                            RegisteredCredential.builder()
                                    .credentialId(credentialId)
                                    .userHandle(new ByteArray(uh))
                                    .publicKeyCose(new ByteArray(pk))
                                    .signatureCount(signCount)
                                    .build()
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in lookupAll", e);
        }
        return out;
    }

    /* ---------- Convenience write helpers (optional but handy) ---------- */

    public static int insertCredential(
            Connection db,
            long userId,
            ByteArray credentialId,
            ByteArray publicKeyCose,
            long signCount,
            String label
    ) {
        final String sql =
                """
                        insert into webauthn_credential
                            (user_id, credential_id, public_key_cose, sign_count, label)
                        values
                            (?, ?, ?, ?, ?)
                        """;
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setBytes(2, credentialId.getBytes());
            ps.setBytes(3, publicKeyCose.getBytes());
            ps.setLong(4, signCount);
            ps.setString(5, label);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error in insertCredential", e);
        }
    }

    public static int updateSignCountAndLastUsed(Connection db, ByteArray credentialId, long newSignCount) {
        final String sql =
                """
                        update webauthn_credential
                           set sign_count = ?, last_used_at = now()
                         where credential_id = ?
                        """;
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, newSignCount);
            ps.setBytes(2, credentialId.getBytes());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error in updateSignCountAndLastUsed", e);
        }
    }

    public static Optional<Long> insertUser(Connection db, String username) {
        byte[] userHandle = new byte[32];
        random.nextBytes(userHandle);
        String sql = """
                insert into app_user (username, user_handle)
                values (?, ?)
                returning id
                """;
        try (var ps = db.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setBytes(2, userHandle);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return Optional.of(rs.getLong(1));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public static Optional<Long> findUserIdByUsername(Connection db, String username) {
        try (var s = db.prepareStatement("select id from app_user where username = ?")) {
            s.setString(1, username);
            try (var rs = s.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getLong("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in findUserIdByUsername", e);
        }
        return Optional.empty();
    }

    public static byte[] findUserHandleByUserID(Connection db, long userID) {
        final String sql = "select user_handle from app_user where id = ?";
        byte[] handle;
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, userID);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) handle = rs.getBytes(1);
                else throw new RuntimeException("No user found for id " + userID);
                if (rs.next()) throw new RuntimeException("Found multiple handles for id " + userID);
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in findUserIdByUsername", e);
        }
        return handle;
    }

    // Save registration options JSON
    public static long insertRegistrationOptions(Connection db, long userId, String rpId, String origin, String optionsJson, java.time.Instant expiresAt) throws SQLException {
        String sql = """
                insert into webauthn_challenge(flow, user_id, rp_id, origin, options_json, created_at, expires_at, is_used)
                values ('registration', ?, ?, ?, ?::jsonb, now(), ?, false)
                returning id
                """;
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, rpId);
            ps.setString(3, origin);
            ps.setString(4, optionsJson);
            ps.setObject(5, java.sql.Timestamp.from(expiresAt));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public static Optional<String> findOptionsJson(Connection db, long id, String flow) throws SQLException {
        String sql = "select options_json from webauthn_challenge where id = ? and flow = ?::webauthn_flow and is_used = false and now() < expires_at";
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, flow);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
            }
        }
        return Optional.empty();
    }

    public static long findUser(Connection db, long id) throws SQLException {
        String sql = "select user_id from webauthn_challenge where id = ?";
        var userId = -1L;
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) userId = rs.getLong(1);
                else throw new RuntimeException("No user found for challenge id " + id);
                if (rs.next()) throw new RuntimeException("Found multiple users for challenge id " + id);
            }
        }
        return userId;
    }

    public static int markUsed(Connection db, long id) throws SQLException {
        try (var ps = db.prepareStatement("update webauthn_challenge set is_used = true, used_at = now() where id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    // Same pattern for assertion requests:
    public static long insertAssertionRequest(Connection db, Long userIdNullable, String rpId, String origin, String requestJson, java.time.Instant expiresAt) throws SQLException {
        String sql = """
                insert into webauthn_challenge(flow, user_id, rp_id, origin, created_at, expires_at, is_used, options_json)
                values ('authentication', ?, ?, ?, now(), ?, false, ?::jsonb)
                returning id
                """;
        try (var ps = db.prepareStatement(sql)) {
            if (userIdNullable == null) ps.setNull(1, java.sql.Types.BIGINT);
            else ps.setLong(1, userIdNullable);
            ps.setString(2, rpId);
            ps.setString(3, origin);
            ps.setObject(4, java.sql.Timestamp.from(expiresAt));
            ps.setString(5, requestJson);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

}
