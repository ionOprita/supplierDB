package ro.sellfluence.api;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import ro.sellfluence.db.EmagMirrorDB;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

public class MyCredentialRepo implements CredentialRepository {
    private final EmagMirrorDB mirrorDB;

    public MyCredentialRepo(EmagMirrorDB mirrorDB) {
        this.mirrorDB = mirrorDB;
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        try {
            return mirrorDB.getCredentialIdsForUsername(username);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        try {
            return mirrorDB.getUserHandleForUsername(username);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        try {
            return mirrorDB.getUsernameForUserHandle(userHandle);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        try {
            return mirrorDB.lookup(credentialId, userHandle);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        try {
            return mirrorDB.lookupAll(credentialId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
