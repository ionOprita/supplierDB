package ro.sellfluence.app;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserVerificationRequirement;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.validation.Validator;
import org.eclipse.jetty.server.UserIdentity;
import ro.sellfluence.api.API;
import ro.sellfluence.api.MyCredentialRepo;
import ro.sellfluence.api.WebAuthnServer;
import ro.sellfluence.apphelper.BackgroundJob;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.List;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

/**
 * Server for the EmagMirror app.
 */
public class Server {
    private static final Path certsDir = Paths.get(System.getProperty("user.home")).resolve("Secrets/Certs");

    private static void configure(JavalinConfig config, int port, int securePort) {
        SslPlugin sslPlugin = new SslPlugin(ssl -> {
            String password;
            try {
                password = Files.readString(certsDir.resolve("localhost.pw")).trim();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            ssl.keystoreFromPath(certsDir.resolve("localhost.p12").toString(), password);
            // You can adjust ports, whether to enable HTTP (insecure) etc:
            ssl.securePort = securePort;
            ssl.insecurePort = port;
            ssl.insecure = true;
            ssl.secure = true;
        });
        config.registerPlugin(sslPlugin);
        config.bundledPlugins.enableDevLogging();
        config.http.defaultContentType = "application/json";
        config.staticFiles.add("/public");
        config.validation.register(YearMonth.class, YearMonth::parse);

    }

    private static final Logger logger = Logs.getFileLogger("Server", INFO, 10, 1_000_000);

    static void main(String[] args) throws Exception {
        var arguments = new Arguments(args);
        EmagMirrorDB mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        mirrorDB.resetTasks();

        API api = new API(mirrorDB);

        String configNamePort = "PORT";
        String configNameSecurePort = "PORT_SECURE";
        int port = Integer.parseInt(System.getProperty(configNamePort,
                System.getenv().getOrDefault(configNamePort, arguments.getOption("port","8080"))));
        int securePort = Integer.parseInt(System.getProperty(configNamePort,
                System.getenv().getOrDefault(configNameSecurePort, arguments.getOption("secport","8443"))));

        // Setup background job
        BackgroundJob backgroundJob = new BackgroundJob(mirrorDB);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BackgroundJob-Thread");
            thread.setDaemon(true); // Don't prevent JVM shutdown
            return thread;
        });

        var rp = WebAuthnServer.create(new MyCredentialRepo(mirrorDB));

        // Schedule the job with auto-restart on failure
        scheduleWithRestart(scheduler, backgroundJob);

        var app = Javalin.create(config -> configure(config, port, securePort));

        app.get("/app/products", ctx -> {
            String json = api.getProducts();
            if (json == null) {
                ctx.status(500).result("""
                        {"error":"Database error"}""");
            } else {
                ctx.contentType("application/json").result(json);
            }
        });

        app.get("/app/rrr/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var rrr = api.getRRR(id);
            if (rrr == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(rrr);
            }
        });

        app.get("/app/orders/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var orders = api.getOrders(id);
            if (orders == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(orders);
            }
        });

        app.get("/app/stornos/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var stornos = api.getStornos(id);
            if (stornos == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(stornos);
            }
        });

        app.get("/app/returns/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var returns = api.getReturns(id);
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });

        app.get("/app/orderTable", ctx -> {
            var returns = api.getOrdersByProductAndMonth();
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/orderDetails", ctx -> {
            Validator<YearMonth> month = ctx.queryParamAsClass("month", YearMonth.class);
            var returns = api.orderDetails(ctx.queryParam("pnk"), month.get());
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });

        app.get("/app/stornoTable", ctx -> {
            var returns = api.getStornosByProductAndMonth();
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/stornoDetails", ctx -> {
            Validator<YearMonth> month = ctx.queryParamAsClass("month", YearMonth.class);
            var returns = api.stornoDetails(ctx.queryParam("pnk"), month.get());
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/returnTable", ctx -> {
            var returns = api.getReturnsByProductAndMonth();
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/returnDetails", ctx -> {
            Validator<YearMonth> month = ctx.queryParamAsClass("month", YearMonth.class);
            var returns = api.returnDetails(ctx.queryParam("pnk"), month.get());
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/tasks", ctx -> {
            var returns = api.getTasks();
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });

        // Health check (handy)
        app.get("/health", ctx -> ctx.result("ok"));

//        app.post("/webauthn/register/options", ctx -> {
//            var user = loadUser(ctx); // your way to identify the account being registered
//            var userIdentity = UserIdentity.builder()
//                    .name(user.username())
//                    .displayName(user.displayName())
//                    .id(user.userHandle()) // stable opaque bytes
//                    .build();
//
//            var pubKeyCredParams = List.of(
//                    PublicKeyCredentialParameters.ES256, // add algorithms you want
//                    PublicKeyCredentialParameters.RS256
//            );
//
//            var options = rp.startRegistration(StartRegistrationOptions.builder()
//                    .user(userIdentity)
//                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
//                            .residentKey(ResidentKeyRequirement.REQUIRED)    // passkey UX
//                            .userVerification(UserVerificationRequirement.REQUIRED)
//                            .build())
//                    .pubKeyCredParams(pubKeyCredParams)
//                    .build());
//
//            saveExpectedChallenge(user.id(), options); // persist challenge to compare later
//            ctx.json(options); // send to browser
//        });
//
//        app.post("/webauthn/register/verify", ctx -> {
//            var response = ctx.bodyAsClass(RegistrationResponse.class);
//            var request = FinishRegistrationOptions.builder()
//                    .requestId(loadExpectedChallenge(response.userId()))
//                    .response(response.credential())
//                    .build();
//
//            var regResult = rp.finishRegistration(request);
//
//            storeCredential(new StoredCredential(
//                    regResult.getKeyId().getId(), response.userId(),
//                    regResult.getPublicKeyCose(), regResult.getSignatureCount(),
//                    regResult.getAttestationMetadata().map(Attestation::getAaguid).orElse(null)
//            ));
//
//            ctx.status(204);
//        });

        // Missing authenticate/options and /authenticate/verify

        app.before("/app/*", ctx -> {
            var session = ctx.req().getSession(false);
            if (session == null || session.getAttribute("uid") == null) {
                ctx.redirect("/login");
            }
        });

        app.start();

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            //logger.info("Shutting down...");
            backgroundJob.shutdown();
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            app.stop();
        }));
    }

    /**
     * Schedule the job immediately and then every minute.
     *
     * @param scheduler provided.
     * @param job to run.
     */
    private static void scheduleWithRestart(ScheduledExecutorService scheduler, BackgroundJob job) {
        scheduler.schedule(() -> {
            try {
                job.performWork();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "BackgroundJob failed.", e);
            }
            // Schedule the next run in a minute.
            scheduler.schedule(() -> scheduleWithRestart(scheduler, job), 1, TimeUnit.MINUTES);
        }, 0, TimeUnit.SECONDS);
    }
}

/*
                                                       -- Auto-clean job is a good idea:
                                                       -- delete from webauthn_challenge where (is_used or now() > expires_at);

 */