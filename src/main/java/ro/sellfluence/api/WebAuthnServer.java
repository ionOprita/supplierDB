package ro.sellfluence.api;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import java.util.Set;

public final class WebAuthnServer {
    public static RelyingParty create(MyCredentialRepo repo) {
        var env = WebAuthnEnv.load();

        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(env.rpId)         // "localhost" OR "abc123.ngrok.io" OR "example.com"
                        .name("Server")
                        .build())
                .credentialRepository(repo)
                .origins(Set.of(env.origin)) // "https://localhost:8443" OR your tunnel/prod origin
                .allowUntrustedAttestation(false)
                .build();
    }
}
