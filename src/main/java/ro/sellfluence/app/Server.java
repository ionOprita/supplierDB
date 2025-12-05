package ro.sellfluence.app;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.validation.Validator;
import ro.sellfluence.api.API;
import ro.sellfluence.api.MyCredentialRepo;
import ro.sellfluence.api.WebAuthnServer;
import ro.sellfluence.apphelper.BackgroundJob;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;
import tools.jackson.databind.ObjectMapper;

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

import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

/**
 * Server for the EmagMirror app.
 */
public class Server {
    private static final Path certsDir = Paths.get(System.getProperty("user.home")).resolve("Secrets").resolve("Certs");
    private static final ObjectMapper mapper = (new ObjectMapper());

    private static void configure(JavalinConfig config, int port, int securePort) {
        SslPlugin sslPlugin = new SslPlugin(ssl -> {
            String password;
            try {
                password = Files.readString(Server.certsDir.resolve("localhost.pw")).trim();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            ssl.keystoreFromPath(Server.certsDir.resolve("localhost.p12").toString(), password);
            // You can adjust ports, whether to enable HTTP (insecure) etc.
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
                System.getenv().getOrDefault(configNamePort, arguments.getOption("port", "8080"))));
        int securePort = Integer.parseInt(System.getProperty(configNamePort,
                System.getenv().getOrDefault(configNameSecurePort, arguments.getOption("secport", "8443"))));

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

        app.post("/webauthn/register/options", ctx -> {
            String username = ctx.queryParam("username");
            if (!username.matches("\\w+")) {
                ctx.status(400)
                        .result("Invalid username. Use letters, numbers and underscores only.");
                return;
            }
            long userId = mirrorDB.insertUser(username).orElse(-1L);
            if (userId < 0) {
                ctx.status(400)
                        .result("User with this name %s already exists.".formatted(username));
                return;
            }
            // Load user fields for UserIdentity
            byte[] userHandle = mirrorDB.findUserHandleByUserID(userId);

            var userIdentity = UserIdentity.builder()
                    .name(username)
                    .displayName(username)
                    .id(new ByteArray(userHandle))
                    .build();

            var options = rp.startRegistration(StartRegistrationOptions.builder()
                    .user(userIdentity)
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                            .residentKey(ResidentKeyRequirement.REQUIRED)              // passkey UX
                            .userVerification(UserVerificationRequirement.REQUIRED)   // biometrics/PIN
                            .build())
                    .build());

            // Persist the whole options JSON (expires in 5 minutes)
            String optionsJson = mapper.writeValueAsString(options);
            long rowId = mirrorDB.insertRegistrationOptions(userId, rp.getIdentity().getId(), rp.getOrigins().stream().findFirst().get(),
                    optionsJson, java.time.Instant.now().plusSeconds(300));
            // (If you split insert+attach, call attachRegistrationOptionsJson here)

            // Return JSON + a request id so we can look it up later
            var resultJson = mapper.writeValueAsString(new java.util.HashMap<>() {{
                put("requestId", rowId);
                put("publicKey", options);
            }});
            ctx.result(resultJson);
        });

        app.post("/webauthn/register/verify", ctx -> {
            long requestId = Long.parseLong(ctx.queryParam("requestId"));

            // Load saved options JSON
            String optionsJson = mirrorDB.findOptionsJson(requestId, "registration")
                    .orElseThrow(() -> new IllegalStateException("Registration options not found/expired"));

            // Parse browser JSON into Yubico PublicKeyCredential
            String credentialJson = ctx.body(); // raw JSON from fetch()
            var pkc = PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

            // Re-hydrate the original options
            PublicKeyCredentialCreationOptions creationOptions =
                    mapper.readValue(optionsJson, PublicKeyCredentialCreationOptions.class);

            // Finish registration
            RegistrationResult result = rp.finishRegistration(FinishRegistrationOptions.builder()
                    .request(creationOptions)
                    .response(pkc)
                    .build());

            // Persist credential
            var credId = pkc.getId();                          // ByteArray
            var pubKey = result.getPublicKeyCose();            // ByteArray
            long signCount = result.getSignatureCount();

            // We need the userId bound to this options row:
            long userId = mirrorDB.findUser(requestId);

            mirrorDB.insertCredential(userId, credId, pubKey, signCount, /*label*/ "Passkey");
            mirrorDB.markUsed(requestId);

            ctx.status(204);
        });

        app.post("/webauthn/authenticate/options", ctx -> {
            String username = ctx.queryParam("username"); // optional
            if (!username.matches("\\w+")) {
                ctx.status(400)
                        .result("Invalid username. Use letters, numbers and underscores only.");
                return;
            }
            var b = StartAssertionOptions.builder()
                    .userVerification(UserVerificationRequirement.REQUIRED);
            b.username(username); // username-first
            AssertionRequest request = rp.startAssertion(b.build());
            String requestJson = mapper.writeValueAsString(request);
            Long userId = mirrorDB.findUserIdByUsername(username).orElse(null);
            if (userId == null) {
                ctx.status(400)
                        .result("User %s not found.".formatted(username));
                return;
            }
            long rowId = mirrorDB.insertAssertionRequest(userId, rp.getIdentity().getId(), rp.getOrigins().stream().findFirst().get(),
                    requestJson, java.time.Instant.now().plusSeconds(300));
            var resultJson = mapper.writeValueAsString(new java.util.HashMap<>() {{
                put("requestId", rowId);
                put("publicKey", request.getPublicKeyCredentialRequestOptions());
            }});
            ctx.result(resultJson);
        });

        app.post("/webauthn/authenticate/verify", ctx -> {
            long requestId = Long.parseLong(ctx.queryParam("requestId"));

            String requestJson = mirrorDB.findOptionsJson(requestId, "authentication")
                    .orElseThrow(() -> new IllegalStateException("Assertion request not found/expired"));

            String assertionJson = ctx.body();

            // Parse browser JSON to Yubico credential
            var pkc = PublicKeyCredential.parseAssertionResponseJson(assertionJson);

            AssertionRequest assertionRequest =
                    mapper.readValue(requestJson, AssertionRequest.class);

            AssertionResult result = rp.finishAssertion(FinishAssertionOptions.builder()
                    .request(assertionRequest)
                    .response(pkc)
                    .build());

            if (!result.isSuccess()) {
                ctx.status(401);
                return;
            }

            // Update signCount and last_used_at
            mirrorDB.updateSignCountAndLastUsed(result.getCredential().getCredentialId(), result.getSignatureCount());
            mirrorDB.markUsed(requestId);

            // Start session/JWT
            var session = ctx.req().getSession(true);
            session.setAttribute("uid", result.getCredential().getUserHandle().getBytes()); // or store your app user id

            ctx.status(204);
        });

        // Missing authenticate/options and /authenticate/verify

        app.before("/appXXX/*", ctx -> {
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
     * @param job       to run.
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