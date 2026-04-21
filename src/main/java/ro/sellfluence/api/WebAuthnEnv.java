package ro.sellfluence.api;

import java.net.URI;

public final class WebAuthnEnv {
    public final String rpId;
    public final String origin;

    private WebAuthnEnv(String rpId, String origin) {
        this.rpId = rpId;
        this.origin = origin;
    }

    public static WebAuthnEnv load() {
        // Prefer an explicit ORIGIN (e.g., tunnel/prod), else default to local HTTPS.
        String origin = envOr("ORIGIN", "https://localhost:8443");

        // rpId must be the registrable domain (no scheme/port). For localhost, it's literally "localhost".
        String rpId = envOr("RP_ID", deriveRpIdFromOrigin(origin));

        return new WebAuthnEnv(rpId, origin);
    }

    private static String envOr(String key, String def) {
        var v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String deriveRpIdFromOrigin(String origin) {
        return URI.create(origin).getHost();
    }
}
