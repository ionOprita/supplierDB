package ro.sellfluence.apphelper;

import io.javalin.Javalin;
import io.javalin.http.Context;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static ro.sellfluence.support.UsefulMethods.homeDirectory;

/** Serves HTTP-01 challenges for certificate issuance and renewal. */
public final class ACME {
    private static final String CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

    private ACME() {
    }

    public static Javalin startAcmeListener(Arguments arguments) {
        int port = Integer.parseInt(System.getProperty("PORT",
                System.getenv().getOrDefault("PORT", arguments.getOption("port", "8080"))));
        String webRoot = System.getProperty("ACME_CHALLENGE_WEBROOT");
        if (webRoot == null || webRoot.isBlank()) {
            webRoot = System.getenv("ACME_CHALLENGE_WEBROOT");
        }
        Path root = webRoot == null || webRoot.isBlank()
                ? homeDirectory().resolve("Secrets").resolve("Certs").resolve("acme-challenge-webroot")
                : Paths.get(webRoot);
        return startAcmeListener(root, port);
    }

    public static Javalin startAcmeListener(Path webRoot, int port) {
        return Javalin.create(config -> config.routes.get(CHALLENGE_PREFIX + "{token}",
                ctx -> serveChallenge(ctx, webRoot))).start(port);
    }

    private static void serveChallenge(Context ctx, Path webRoot) throws IOException {
        String token = ctx.pathParam("token");
        if (!token.matches("[A-Za-z0-9_-]+")) {
            ctx.status(404);
            return;
        }

        Path challengeDirectory = webRoot.resolve(".well-known").resolve("acme-challenge").normalize();
        Path challengeFile = challengeDirectory.resolve(token).normalize();
        if (!challengeFile.startsWith(challengeDirectory) || !Files.isRegularFile(challengeFile)
                || !challengeFile.toRealPath().startsWith(challengeDirectory.toRealPath())) {
            ctx.status(404);
            return;
        }

        ctx.contentType("text/plain").result(Files.readString(challengeFile, StandardCharsets.UTF_8));
    }
}