package ro.sellfluence.db;

import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class PassKey {
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

    public static void insertCredential(
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
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error in insertCredential", e);
        }
    }

    public static void updateSignCountAndLastUsed(Connection db, ByteArray credentialId, long newSignCount) {
        final String sql =
                """
                        update webauthn_credential
                           set sign_count = ?, last_used_at = now()
                         where credential_id = ?
                        """;
        try (var ps = db.prepareStatement(sql)) {
            ps.setLong(1, newSignCount);
            ps.setBytes(2, credentialId.getBytes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error in updateSignCountAndLastUsed", e);
        }
    }

    public static Optional<Long> findUserIdByUsername(Connection db, String username) {
        final String sql = "select id from app_user where username = ?";
        try (var ps = db.prepareStatement(sql)) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getLong("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error in findUserIdByUsername", e);
        }
        return Optional.empty();
    }

}
