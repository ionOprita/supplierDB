package ro.sellfluence.app;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
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
import io.javalin.http.Context;
import io.javalin.rendering.template.JavalinJte;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.validation.Validator;
import ro.sellfluence.api.API;
import ro.sellfluence.api.MyCredentialRepo;
import ro.sellfluence.api.WebAuthnServer;
import ro.sellfluence.apphelper.BackgroundJob;
import ro.sellfluence.db.Brand;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmployeeDataTable.EmployeeColumn;
import ro.sellfluence.db.EmployeeDataTable.EmployeeInfo;
import ro.sellfluence.db.PassKey;
import ro.sellfluence.db.PassKey.User;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.Vendor;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static io.javalin.http.HttpStatus.FORBIDDEN;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.db.PassKey.Role.admin;
import static ro.sellfluence.db.PassKey.Role.nobody;
import static ro.sellfluence.db.PassKey.Role.user;

/**
 * Server for the EmagMirror app.
 */
public class Server {
    private static final Path certsDir = Paths.get(System.getProperty("user.home")).resolve("Secrets").resolve("Certs");
    private static final ObjectMapper mapper = (new ObjectMapper());
    private static final User unsafeUser = new User("unsafe-without-authentication", admin);
    private static final boolean withoutAuthenticationAndTotalyUnsafe = true;
    private static final Set<String> MULTILINE_PRODUCT_FIELDS = Set.of(
            "emagTitle",
            "otherComments",
            "reviewCaller"
    );
    private static final List<ProductFormField> PRODUCT_FORM_FIELDS = buildProductFormFields();
    private static final List<ProductFormField> PRODUCT_TABLE_FIELDS = buildProductTableFields(PRODUCT_FORM_FIELDS);
    private static final Set<String> MULTILINE_EMPLOYEE_FIELDS = Set.of(
            "updated_sections",
            "cv_and_references",
            "family_doctor_medical_certificate",
            "dependents_declaration",
            "contribution_stage_certificate",
            "health_insurance_house_declaration",
            "work_safety_training_file",
            "equipment_handover_minutes",
            "gift_or_documents_address",
            "computer_equipment",
            "shipped_products",
            "bank_details",
            "working_document_link"
    );
    private static final List<EmployeeFormField> EMPLOYEE_TABLE_FIELDS = buildEmployeeTableFields();

    public record ProductTableRow(
            String productCode,
            String vendorName,
            Map<String, String> values
    ) {
    }

    public record ProductFormField(
            String name,
            String label,
            String inputType,
            boolean required
    ) {
    }

    public record ProductSaveRequest(List<ProductSaveRow> rows) {
    }

    public record ProductSaveRow(
            String mode,
            String productCode,
            Map<String, String> values
    ) {
    }

    public record ProductSaveResponse(String message, int inserted, int updated) {
    }

    public record ProductSaveError(String error) {
    }

    public record EmployeeTableRow(
            int sourceRowNumber,
            String fullName,
            String company,
            String department,
            Map<String, String> values
    ) {
    }

    public record EmployeeFormField(
            String name,
            String label,
            String inputType,
            boolean required
    ) {
    }

    public record EmployeeSaveRequest(List<EmployeeSaveRow> rows) {
    }

    public record EmployeeSaveRow(
            String mode,
            String sourceRowNumber,
            Map<String, String> values
    ) {
    }

    public record EmployeeSaveResponse(String message, int inserted, int updated) {
    }

    public record EmployeeSaveError(String error) {
    }

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
        config.fileRenderer(new JavalinJte(createJteEngine()));
        config.http.defaultContentType = "application/json";
        config.staticFiles.add("/static");
        config.validation.register(YearMonth.class, YearMonth::parse);
    }

    private static void configureRoutes(JavalinDefaultRoutingApi app,
                                        EmagMirrorDB mirrorDB,
                                        API api,
                                        RelyingParty rp) {
        if (withoutAuthenticationAndTotalyUnsafe) {
            logger.log(WARNING, "withoutAuthenticationAndTotalyUnsafe=true. Authentication and role checks are disabled.");
            app.get("/", ctx -> ctx.redirect("/private/overview"));
            app.get("/index.html", ctx -> ctx.redirect("/private/overview"));
        }

        configureAPI(app, api);
        configurePasskey(app, mirrorDB, rp);

        app.before("/private/*", ctx -> checkRole(ctx, user));
        app.get("/private/products", ctx -> renderProductsPage(ctx, mirrorDB));
        app.get("/private/employees", ctx -> renderEmployeesPage(ctx, mirrorDB));
        app.post("/private/employees/save", ctx -> saveEmployeeTableChanges(ctx, mirrorDB));
        app.get("/private/products/edit", ctx -> renderEditProductPage(ctx, mirrorDB, ctx.queryParam("productCode"), null, null));
        app.post("/private/products/update", ctx -> updateProduct(ctx, mirrorDB));
        app.get("/private/products/new", ctx -> renderNewProductPage(ctx, mirrorDB, null, null));
        app.post("/private/products/insert", ctx -> insertProduct(ctx, mirrorDB));
        app.post("/private/products/save", ctx -> saveProductTableChanges(ctx, mirrorDB));
        app.get("/private/{page}", ctx -> renderPage(ctx, mirrorDB, ctx.pathParam("page")));

        app.before("/admin/*", ctx -> checkRole(ctx, admin));
        app.get("/admin/db-explorer", ctx -> ctx.redirect("/admin/db-explorer/products"));
        app.get("/admin/db-explorer/{subPage}", ctx -> renderDBExplorerSubPage(ctx, mirrorDB, ctx.pathParam("subPage")));
        app.post("/admin/db-explorer/brands", ctx -> insertBrand(ctx, mirrorDB));
        app.post("/admin/db-explorer/brands/{brandId}/delete", ctx -> deleteBrand(ctx, mirrorDB));
        app.get("/admin/{page}", ctx -> renderPage(ctx, mirrorDB, ctx.pathParam("page")));
        app.post("/admin/users/{userId}/role", ctx -> changeUserRole(ctx, mirrorDB));
        app.post("/admin/users/{userId}/delete", ctx -> deleteUser(ctx, mirrorDB));

        app.before("/public/*", ctx -> checkRole(ctx, nobody));
        app.before("/static/*", ctx -> checkRole(ctx, null));

        app.get("/welcome.html", ctx -> renderPage(ctx, mirrorDB, "welcome"));
        app.get("/health", ctx -> ctx.result("ok"));
        app.post("/logout", ctx -> {
            ctx.req().getSession().invalidate();
            ctx.redirect("/");
        });
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
        int securePort = Integer.parseInt(System.getProperty(configNameSecurePort,
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
        //scheduleWithRestart(scheduler, backgroundJob);

        var app = Javalin.create(config -> {
            configure(config, port, securePort);
            configureRoutes(config.routes, mirrorDB, api, rp);
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

    private static void configurePasskey(JavalinDefaultRoutingApi app, EmagMirrorDB mirrorDB, RelyingParty rp) {
        app.post("/webauthn/register/options", ctx -> {
            String username = ctx.queryParam("username");
            if (username == null || !username.matches("\\w+")) {
                ctx.status(400)
                        .result("Invalid username. Use letters, numbers and underscores only.");
                return;
            }
            username = username.toLowerCase();
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
            if (username == null || !username.matches("\\w+")) {
                ctx.status(400)
                        .result("Invalid username. Use letters, numbers and underscores only.");
                return;
            }
            username = username.toLowerCase();
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
            var uid = result.getCredential().getUserHandle();
            if (uid.isEmpty()) {
                ctx.redirect("/");
            }
            var userOpt = mirrorDB.getUserForUserHandle(uid);
            if (userOpt.isEmpty()) {
                ctx.redirect("/");
            } else {
                session.setAttribute("uid", uid); // or store your app user id
                session.setAttribute("user", userOpt.get());
                ctx.status(204);
            }
        });
    }

    private static void configureAPI(JavalinDefaultRoutingApi app, API api) {
        app.before("/app/*", ctx -> checkRole(ctx, user)); // TODO: Need to protect admin calls
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

        app.get("/app/rrr-smoothed/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var rrr = api.getCohortSmoothedRRR(id);
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

        app.get("/app/storno/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var storno = api.getStorno(id);
            if (storno == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(storno);
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
            var returns = api.getStornoByProductAndMonth();
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
        app.get("/app/stornoRateTable", ctx -> {
            var returns = api.getStornoRateByProductAndMonth();
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/returnRateTable", ctx -> {
            var returns = api.getReturnRateByProductAndMonth();
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/monthStats", ctx -> {
            var aggregateMonths = ctx.queryParamAsClass("aggregateMonths", Integer.class);
            var confidenceLevel = ctx.queryParamAsClass("confidenceLevel", Double.class);
            var startMonth = ctx.queryParamAsClass("startMonth", YearMonth.class);
            var endMonth = ctx.queryParamAsClass("endMonth", YearMonth.class);
            var returns = api.getMonthStats(startMonth.get(), endMonth.get(), aggregateMonths.get(), confidenceLevel.get());
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/monthStatsByCategory", ctx -> {
            var aggregateMonths = ctx.queryParamAsClass("aggregateMonths", Integer.class);
            var confidenceLevel = ctx.queryParamAsClass("confidenceLevel", Double.class);
            var startMonth = ctx.queryParamAsClass("startMonth", YearMonth.class);
            var endMonth = ctx.queryParamAsClass("endMonth", YearMonth.class);
            var returns = api.getMonthStatsByCategory(startMonth.get(), endMonth.get(), aggregateMonths.get(), confidenceLevel.get());
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });
        app.get("/app/currentRatesTable", ctx -> {
            var returns = api.getCurrentMonthRatesTable();
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
    }

    private static void renderProductsPage(Context ctx, EmagMirrorDB mirrorDB) {
        var currentUser = resolveCurrentUser(ctx);
        if (currentUser == null) {
            return;
        }

        var model = new HashMap<String, Object>();
        model.put("userName", currentUser.username());
        model.put("userRole", currentUser.role().name());
        model.put("pageTitle", "Products");
        model.put("message", ctx.queryParam("message"));

        try {
            var vendors = mirrorDB.getAllVendors();
            var brands = mirrorDB.getAllBrands();
            model.put("vendors", vendors);
            model.put("brands", brands);
            model.put("fields", PRODUCT_TABLE_FIELDS);
            model.put("blankValues", blankProductFormValues());
            model.put("products", buildProductTableRows(mirrorDB.readProducts(), vendors, brands));
            ctx.render("products.jte", model);
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to load products page.", e);
            ctx.status(500).result("Failed to load products.");
        }
    }

    private static void renderEmployeesPage(Context ctx, EmagMirrorDB mirrorDB) {
        var currentUser = resolveCurrentUser(ctx);
        if (currentUser == null) {
            return;
        }

        var model = new HashMap<String, Object>();
        model.put("userName", currentUser.username());
        model.put("userRole", currentUser.role().name());
        model.put("pageTitle", "Employees");
        model.put("message", ctx.queryParam("message"));

        try {
            var employees = mirrorDB.readEmployeeData();
            var rows = buildEmployeeTableRows(employees);
            model.put("fields", EMPLOYEE_TABLE_FIELDS);
            model.put("blankValues", blankEmployeeFormValues(mirrorDB.nextEmployeeDataSourceRowNumber()));
            model.put("departments", employeeDepartments(rows));
            model.put("employees", rows);
            ctx.render("employees.jte", model);
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to load employees page.", e);
            ctx.status(500).result("Failed to load employees.");
        }
    }

    private static void renderEditProductPage(Context ctx,
                                              EmagMirrorDB mirrorDB,
                                              String productCode,
                                              String error,
                                              Map<String, String> values) {
        if (productCode == null || productCode.isBlank()) {
            ctx.status(400).result("Missing product code.");
            return;
        }

        try {
            var formValues = values;
            if (formValues == null) {
                var product = mirrorDB.readProduct(productCode)
                        .orElse(null);
                if (product == null) {
                    ctx.status(404).result("Product not found.");
                    return;
                }
                formValues = productToFormValues(product);
            }
            renderProductFormPage(ctx, mirrorDB, "Update Product", "/private/products/update", "Update", true, error, formValues);
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to load product " + productCode + ".", e);
            ctx.status(500).result("Failed to load product.");
        }
    }

    private static void renderNewProductPage(Context ctx,
                                             EmagMirrorDB mirrorDB,
                                             String error,
                                             Map<String, String> values) {
        var formValues = values == null ? blankProductFormValues() : values;
        renderProductFormPage(ctx, mirrorDB, "New Product", "/private/products/insert", "Insert", false, error, formValues);
    }

    private static void updateProduct(Context ctx, EmagMirrorDB mirrorDB) {
        var values = readProductFormValues(ctx);
        try {
            var vendors = mirrorDB.getAllVendors();
            var brands = mirrorDB.getAllBrands();
            var product = productInfoFromValues(values, vendors, brands);
            var updatedRows = mirrorDB.updateProduct(product);
            if (updatedRows > 0) {
                redirectWithProductMessage(ctx, displayProductName(product) + " has been updated.");
            } else {
                renderProductFormPage(ctx, mirrorDB, "Update Product", "/private/products/update", "Update", true, "Product not found.", values);
            }
        } catch (IllegalArgumentException e) {
            renderProductFormPage(ctx, mirrorDB, "Update Product", "/private/products/update", "Update", true, e.getMessage(), values);
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to update product.", e);
            renderProductFormPage(ctx, mirrorDB, "Update Product", "/private/products/update", "Update", true, "Database update failed: " + e.getMessage(), values);
        }
    }

    private static void insertProduct(Context ctx, EmagMirrorDB mirrorDB) {
        var values = readProductFormValues(ctx);
        try {
            var vendors = mirrorDB.getAllVendors();
            var brands = mirrorDB.getAllBrands();
            var product = productInfoFromValues(values, vendors, brands);
            var insertedRows = mirrorDB.insertProduct(product);
            if (insertedRows > 0) {
                redirectWithProductMessage(ctx, displayProductName(product) + " was inserted");
            } else {
                renderNewProductPage(ctx, mirrorDB, "Product could not be inserted. It may already exist.", values);
            }
        } catch (IllegalArgumentException e) {
            renderNewProductPage(ctx, mirrorDB, e.getMessage(), values);
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to insert product.", e);
            renderNewProductPage(ctx, mirrorDB, "Database insert failed: " + e.getMessage(), values);
        }
    }

    private static void saveProductTableChanges(Context ctx, EmagMirrorDB mirrorDB) {
        final ProductSaveRequest request;
        try {
            request = mapper.readValue(ctx.body(), ProductSaveRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400).json(new ProductSaveError("Invalid product changes payload."));
            return;
        }

        if (request.rows() == null || request.rows().isEmpty()) {
            ctx.json(new ProductSaveResponse("No product changes to save.", 0, 0));
            return;
        }

        try {
            var vendors = mirrorDB.getAllVendors();
            var brands = mirrorDB.getAllBrands();
            var writes = new ArrayList<EmagMirrorDB.ProductWrite>();
            for (var row : request.rows()) {
                if (row == null) {
                    continue;
                }
                var mode = row.mode() == null ? "" : row.mode();
                var values = normalizeProductFormValues(row.values());
                var product = productInfoFromValues(values, vendors, brands);

                switch (mode) {
                    case "update" -> {
                        var originalProductCode = blankToNull(row.productCode());
                        if (originalProductCode == null) {
                            throw new IllegalArgumentException("Missing original product_code for an edited row.");
                        }
                        if (!originalProductCode.equals(product.productCode())) {
                            throw new IllegalArgumentException("product_code cannot be changed for existing products.");
                        }
                        writes.add(new EmagMirrorDB.ProductWrite(product, false));
                    }
                    case "insert" -> writes.add(new EmagMirrorDB.ProductWrite(product, true));
                    default -> throw new IllegalArgumentException("Unknown product save mode: " + mode + ".");
                }
            }

            if (writes.isEmpty()) {
                ctx.json(new ProductSaveResponse("No product changes to save.", 0, 0));
                return;
            }

            var result = mirrorDB.saveProductChanges(writes);
            ctx.json(new ProductSaveResponse(productSaveMessage(result), result.inserted(), result.updated()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ProductSaveError(e.getMessage()));
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to save product table changes.", e);
            ctx.status(500).json(new ProductSaveError("Database save failed: " + e.getMessage()));
        }
    }

    private static void saveEmployeeTableChanges(Context ctx, EmagMirrorDB mirrorDB) {
        final EmployeeSaveRequest request;
        try {
            request = mapper.readValue(ctx.body(), EmployeeSaveRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400).json(new EmployeeSaveError("Invalid employee changes payload."));
            return;
        }

        if (request.rows() == null || request.rows().isEmpty()) {
            ctx.json(new EmployeeSaveResponse("No employee changes to save.", 0, 0));
            return;
        }

        try {
            var writes = new ArrayList<EmagMirrorDB.EmployeeWrite>();
            for (var row : request.rows()) {
                if (row == null) {
                    continue;
                }
                var mode = row.mode() == null ? "" : row.mode();
                var values = normalizeEmployeeFormValues(row.values());
                var employee = employeeInfoFromValues(values);

                switch (mode) {
                    case "update" -> {
                        var originalSourceRowNumber = parseRequiredPositiveInteger(row.sourceRowNumber(), "source_row_number");
                        if (originalSourceRowNumber != employee.sourceRowNumber()) {
                            throw new IllegalArgumentException("source_row_number cannot be changed for existing employee rows.");
                        }
                        writes.add(new EmagMirrorDB.EmployeeWrite(employee, false));
                    }
                    case "insert" -> writes.add(new EmagMirrorDB.EmployeeWrite(employee, true));
                    default -> throw new IllegalArgumentException("Unknown employee save mode: " + mode + ".");
                }
            }

            if (writes.isEmpty()) {
                ctx.json(new EmployeeSaveResponse("No employee changes to save.", 0, 0));
                return;
            }

            var result = mirrorDB.saveEmployeeDataChanges(writes);
            ctx.json(new EmployeeSaveResponse(employeeSaveMessage(result), result.inserted(), result.updated()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new EmployeeSaveError(e.getMessage()));
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to save employee table changes.", e);
            ctx.status(500).json(new EmployeeSaveError("Database save failed: " + e.getMessage()));
        }
    }

    private static void renderProductFormPage(Context ctx,
                                              EmagMirrorDB mirrorDB,
                                              String pageTitle,
                                              String action,
                                              String submitLabel,
                                              boolean readOnlyProductCode,
                                              String error,
                                              Map<String, String> values) {
        var currentUser = resolveCurrentUser(ctx);
        if (currentUser == null) {
            return;
        }

        try {
            var brands = mirrorDB.getAllBrands();
            var normalizedValues = normalizeProductFormValues(values);
            normalizedValues.put("brand", brandInputValue(normalizedValues.get("brand"), normalizedValues.get("vendor"), brands));
            var model = new HashMap<String, Object>();
            model.put("userName", currentUser.username());
            model.put("userRole", currentUser.role().name());
            model.put("pageTitle", pageTitle);
            model.put("action", action);
            model.put("submitLabel", submitLabel);
            model.put("readOnlyProductCode", readOnlyProductCode);
            model.put("error", error);
            model.put("fields", PRODUCT_FORM_FIELDS);
            model.put("values", normalizedValues);
            model.put("vendors", mirrorDB.getAllVendors());
            model.put("brands", brands);
            ctx.render("product-form.jte", model);
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to render product form.", e);
            ctx.status(500).result("Failed to render product form.");
        }
    }

    private static List<ProductTableRow> buildProductTableRows(List<ProductInfo> products, List<Vendor> vendors, List<Brand> brands) {
        var vendorNames = new HashMap<UUID, String>();
        for (var vendor : vendors) {
            if (vendor.id() != null) {
                vendorNames.put(vendor.id(), vendor.name());
            }
        }

        return sortProducts(products, "name").stream()
                .map(product -> new ProductTableRow(
                        product.productCode(),
                        product.vendor() == null ? "" : vendorNames.getOrDefault(product.vendor(), ""),
                        productToFormValues(product, brands)
                ))
                .toList();
    }

    private static List<EmployeeTableRow> buildEmployeeTableRows(List<EmployeeInfo> employees) {
        return employees.stream()
                .map(employee -> {
                    var values = employeeToFormValues(employee);
                    return new EmployeeTableRow(
                            employee.sourceRowNumber(),
                            values.getOrDefault("full_name", ""),
                            values.getOrDefault("company", ""),
                            values.getOrDefault("department", ""),
                            values
                    );
                })
                .toList();
    }

    private static List<String> employeeDepartments(List<EmployeeTableRow> rows) {
        return rows.stream()
                .map(EmployeeTableRow::department)
                .filter(department -> department != null && !department.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<ProductFormField> buildProductFormFields() {
        return Arrays.stream(ProductInfo.class.getRecordComponents())
                .map(component -> new ProductFormField(
                        component.getName(),
                        productFieldLabel(component.getName()),
                        productInputType(component),
                        "productCode".equals(component.getName()) || "name".equals(component.getName())
                ))
                .toList();
    }

    private static List<ProductFormField> buildProductTableFields(List<ProductFormField> formFields) {
        var stickyFields = List.of("name", "pnk", "productCode");
        var tableFields = new ArrayList<ProductFormField>();
        for (var stickyField : stickyFields) {
            for (var field : formFields) {
                if (stickyField.equals(field.name())) {
                    tableFields.add(field);
                    break;
                }
            }
        }
        for (var field : formFields) {
            if (!stickyFields.contains(field.name())) {
                tableFields.add(field);
            }
        }
        return List.copyOf(tableFields);
    }

    private static List<EmployeeFormField> buildEmployeeTableFields() {
        var fields = new ArrayList<EmployeeFormField>();
        fields.add(new EmployeeFormField("source_row_number", "source_row_number", "integer", true));
        var stickyFields = List.of("full_name", "company", "department");
        for (var stickyField : stickyFields) {
            for (var column : EmployeeColumn.values()) {
                if (stickyField.equals(column.dbColumn())) {
                    fields.add(employeeFormField(column));
                    break;
                }
            }
        }
        for (var column : EmployeeColumn.values()) {
            if (!stickyFields.contains(column.dbColumn())) {
                fields.add(employeeFormField(column));
            }
        }
        return List.copyOf(fields);
    }

    private static EmployeeFormField employeeFormField(EmployeeColumn column) {
        return new EmployeeFormField(
                column.dbColumn(),
                column.dbColumn(),
                MULTILINE_EMPLOYEE_FIELDS.contains(column.dbColumn()) ? "textarea" : "text",
                column == EmployeeColumn.FULL_NAME
        );
    }

    private static String productInputType(RecordComponent component) {
        if ("vendor".equals(component.getName())) {
            return "vendor";
        }
        if ("brand".equals(component.getName())) {
            return "brand";
        }
        var type = component.getType();
        if (type == boolean.class) {
            return "boolean";
        }
        if (type == Boolean.class) {
            return "boolean";
        }
        if (type == BigDecimal.class) {
            return "decimal";
        }
        if (type == Integer.class || type == Long.class) {
            return "integer";
        }
        if (MULTILINE_PRODUCT_FIELDS.contains(component.getName())) {
            return "textarea";
        }
        return "text";
    }

    private static String productFieldLabel(String name) {
        return switch (name) {
            case "pnk" -> "emag_pnk";
            case "productCode" -> "product_code";
            default -> toSnakeCase(name);
        };
    }

    private static String toSnakeCase(String name) {
        var result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                result.append('_').append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static Map<String, String> productToFormValues(ProductInfo product) {
        var values = new LinkedHashMap<String, String>();
        for (var component : ProductInfo.class.getRecordComponents()) {
            try {
                var value = component.getAccessor().invoke(product);
                values.put(component.getName(), value == null ? "" : value.toString());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to read product field " + component.getName(), e);
            }
        }
        return values;
    }

    private static Map<String, String> productToFormValues(ProductInfo product, List<Brand> brands) {
        var values = productToFormValues(product);
        values.put("brand", brandInputValue(product.brand(), product.vendor(), brands));
        return values;
    }

    private static Map<String, String> employeeToFormValues(EmployeeInfo employee) {
        var values = new LinkedHashMap<String, String>();
        values.put("source_row_number", String.valueOf(employee.sourceRowNumber()));
        for (var column : EmployeeColumn.values()) {
            var value = employee.value(column);
            values.put(column.dbColumn(), value == null ? "" : value);
        }
        return values;
    }

    private static String brandInputValue(String value, String vendorValue, List<Brand> brands) {
        return brandInputValue(value, parseUuidOrNull(vendorValue), brands);
    }

    private static String brandInputValue(String value, UUID vendor, List<Brand> brands) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return "";
        }
        var brandId = parseUuidOrNull(normalized);
        if (brandId != null && brands.stream().anyMatch(brand -> brandId.equals(brand.id()))) {
            return normalized;
        }
        return brands.stream()
                .filter(brand -> normalized.equals(brand.name()))
                .filter(brand -> vendor == null || vendor.equals(brand.vendor()))
                .map(brand -> brand.id() == null ? "" : brand.id().toString())
                .filter(id -> !id.isBlank())
                .findFirst()
                .orElse("");
    }

    private static UUID parseUuidOrNull(String value) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> blankProductFormValues() {
        var values = new LinkedHashMap<String, String>();
        for (var field : PRODUCT_FORM_FIELDS) {
            values.put(field.name(), "boolean".equals(field.inputType()) ? "false" : "");
        }
        return values;
    }

    private static Map<String, String> blankEmployeeFormValues(int sourceRowNumber) {
        var values = new LinkedHashMap<String, String>();
        for (var field : EMPLOYEE_TABLE_FIELDS) {
            values.put(field.name(), "");
        }
        values.put("source_row_number", String.valueOf(sourceRowNumber));
        return values;
    }

    private static Map<String, String> readProductFormValues(Context ctx) {
        var values = new LinkedHashMap<String, String>();
        for (var field : PRODUCT_FORM_FIELDS) {
            var value = ctx.formParam(field.name());
            if (value == null && "boolean".equals(field.inputType())) {
                value = "false";
            }
            values.put(field.name(), value == null ? "" : value);
        }
        return values;
    }

    private static Map<String, String> normalizeProductFormValues(Map<String, String> values) {
        var normalized = blankProductFormValues();
        if (values != null) {
            normalized.putAll(values);
        }
        return normalized;
    }

    private static Map<String, String> normalizeEmployeeFormValues(Map<String, String> values) {
        var normalized = new LinkedHashMap<String, String>();
        for (var field : EMPLOYEE_TABLE_FIELDS) {
            normalized.put(field.name(), "");
        }
        if (values != null) {
            normalized.putAll(values);
        }
        return normalized;
    }

    private static EmployeeInfo employeeInfoFromValues(Map<String, String> values) {
        var sourceRowNumber = parseRequiredPositiveInteger(values.get("source_row_number"), "source_row_number");
        var sourceValues = new ArrayList<String>();
        for (int i = 0; i < ro.sellfluence.db.EmployeeDataTable.SOURCE_COLUMN_COUNT; i++) {
            sourceValues.add(null);
        }
        for (var column : EmployeeColumn.values()) {
            var value = blankToNull(values.get(column.dbColumn()));
            if (column == EmployeeColumn.FULL_NAME && value == null) {
                throw new IllegalArgumentException("full_name is required.");
            }
            sourceValues.set(column.sheetIndex() - 1, value);
        }
        return new EmployeeInfo(sourceRowNumber, sourceValues);
    }

    private static ProductInfo productInfoFromValues(Map<String, String> values, List<Vendor> vendors, List<Brand> brands) {
        var components = ProductInfo.class.getRecordComponents();
        var parameterTypes = Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        var arguments = new Object[components.length];
        var selectedVendor = parseVendor(values.get("vendor"), vendors);
        for (int i = 0; i < components.length; i++) {
            arguments[i] = parseProductValue(components[i], values.get(components[i].getName()), vendors, brands, selectedVendor);
        }
        try {
            return ProductInfo.class.getDeclaredConstructor(parameterTypes).newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to create product from form values.", e);
        }
    }

    private static Object parseProductValue(RecordComponent component, String value, List<Vendor> vendors, List<Brand> brands, UUID selectedVendor) {
        var name = component.getName();
        if ("vendor".equals(name)) {
            return selectedVendor;
        }
        if ("brand".equals(name)) {
            return parseBrand(value, brands, selectedVendor);
        }

        var label = productFieldLabel(name);
        var type = component.getType();
        if (type == String.class) {
            var stringValue = blankToNull(value);
            if ("productCode".equals(name) && stringValue == null) {
                throw new IllegalArgumentException("product_code is required.");
            }
            if ("name".equals(name)) {
                if (stringValue == null) {
                    throw new IllegalArgumentException("name is required.");
                }
                if (!ProductInfo.hasValidName(stringValue)) {
                    throw new IllegalArgumentException("name must match " + ProductInfo.NAME_PATTERN_REGEX + ".");
                }
            }
            return stringValue;
        }
        if (type == boolean.class) {
            return parseBoolean(value, label, false);
        }
        if (type == Boolean.class) {
            return parseBoolean(value, label, true);
        }
        if (type == BigDecimal.class) {
            return parseBigDecimal(value, label);
        }
        if (type == Integer.class) {
            return parseInteger(value, label);
        }
        if (type == Long.class) {
            return parseLong(value, label);
        }
        throw new IllegalArgumentException("Unsupported product field type for " + label + ".");
    }

    private static String parseBrand(String value, List<Brand> brands, UUID selectedVendor) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        final UUID brandId;
        try {
            brandId = UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Select an existing brand.");
        }
        var selectedBrand = brands.stream()
                .filter(brand -> brandId.equals(brand.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select an existing brand."));
        if (selectedVendor == null) {
            throw new IllegalArgumentException("A product brand requires a vendor.");
        }
        if (!selectedVendor.equals(selectedBrand.vendor())) {
            throw new IllegalArgumentException("Select a brand for the selected vendor.");
        }
        return selectedBrand.name();
    }

    private static UUID parseVendor(String value, List<Vendor> vendors) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        final UUID vendorId;
        try {
            vendorId = UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Select an existing vendor.");
        }
        var exists = vendors.stream().anyMatch(vendor -> vendorId.equals(vendor.id()));
        if (!exists) {
            throw new IllegalArgumentException("Select an existing vendor.");
        }
        return vendorId;
    }

    private static Boolean parseBoolean(String value, String label, boolean nullable) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return nullable ? null : false;
        }
        return switch (normalized.toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(label + " must be true or false.");
        };
    }

    private static BigDecimal parseBigDecimal(String value, String label) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a decimal number.");
        }
    }

    private static Integer parseInteger(String value, String label) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer.");
        }
    }

    private static int parseRequiredPositiveInteger(String value, String label) {
        var parsed = parseInteger(value, label);
        if (parsed == null || parsed <= 0) {
            throw new IllegalArgumentException(label + " is required and must be a positive integer.");
        }
        return parsed;
    }

    private static Long parseLong(String value, String label) {
        var normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String displayProductName(ProductInfo product) {
        var name = blankToNull(product.name());
        return name == null ? product.productCode() : name;
    }

    private static String productSaveMessage(EmagMirrorDB.ProductWriteResult result) {
        var parts = new ArrayList<String>();
        if (result.updated() > 0) {
            parts.add(result.updated() + " updated");
        }
        if (result.inserted() > 0) {
            parts.add(result.inserted() + " inserted");
        }
        return parts.isEmpty() ? "No product changes to save." : "Saved product changes: " + String.join(", ", parts) + ".";
    }

    private static String employeeSaveMessage(EmagMirrorDB.EmployeeWriteResult result) {
        var parts = new ArrayList<String>();
        if (result.updated() > 0) {
            parts.add(result.updated() + " updated");
        }
        if (result.inserted() > 0) {
            parts.add(result.inserted() + " inserted");
        }
        return parts.isEmpty() ? "No employee changes to save." : "Saved employee changes: " + String.join(", ", parts) + ".";
    }

    private static void redirectWithProductMessage(Context ctx, String message) {
        ctx.redirect("/private/products?message=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static void insertBrand(Context ctx, EmagMirrorDB mirrorDB) {
        try {
            var name = requiredBrandName(ctx.formParam("name"));
            var vendor = parseVendor(ctx.formParam("vendor"), mirrorDB.getAllVendors());
            if (vendor == null) {
                throw new IllegalArgumentException("Select a vendor.");
            }
            var insertedRows = mirrorDB.insertBrand(name, vendor);
            if (insertedRows > 0) {
                redirectWithBrandMessage(ctx, "message", name + " was inserted.");
            } else {
                redirectWithBrandMessage(ctx, "error", name + " already exists for this vendor.");
            }
        } catch (IllegalArgumentException e) {
            redirectWithBrandMessage(ctx, "error", e.getMessage());
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to insert brand.", e);
            redirectWithBrandMessage(ctx, "error", "Database insert failed: " + e.getMessage());
        }
    }

    private static void deleteBrand(Context ctx, EmagMirrorDB mirrorDB) {
        final UUID brandId;
        try {
            brandId = UUID.fromString(ctx.pathParam("brandId"));
        } catch (IllegalArgumentException e) {
            redirectWithBrandMessage(ctx, "error", "Invalid brand id.");
            return;
        }

        try {
            var deletedRows = mirrorDB.deleteBrand(brandId);
            if (deletedRows > 0) {
                redirectWithBrandMessage(ctx, "message", "Brand deleted.");
            } else {
                redirectWithBrandMessage(ctx, "error", "Brand not found.");
            }
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to delete brand " + brandId + ".", e);
            redirectWithBrandMessage(ctx, "error", "Database delete failed: " + e.getMessage());
        }
    }

    private static String requiredBrandName(String name) {
        var normalized = blankToNull(name);
        if (normalized == null) {
            throw new IllegalArgumentException("Brand name is required.");
        }
        return normalized;
    }

    private static void redirectWithBrandMessage(Context ctx, String parameter, String message) {
        ctx.redirect("/admin/db-explorer/brands?" + parameter + "=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static void renderPage(Context ctx, EmagMirrorDB mirrorDB, String page) {
        if (!page.matches("[a-zA-Z0-9_-]+")) {
            ctx.status(400).result("Invalid page name");
            return;
        }
        if ("db-explorer".equals(page) && ctx.path().startsWith("/admin/")) {
            ctx.redirect("/admin/db-explorer/products");
            return;
        }
        var currentUser = resolveCurrentUser(ctx);
        if (currentUser == null) {
            return;
        }
        String username = currentUser.username();
        PassKey.Role role = currentUser.role();

        boolean adminOnlyPage = "users".equals(page) || "db-explorer".equals(page);
        boolean isAdminArea = ctx.path().startsWith("/admin/");
        if (adminOnlyPage && (role != admin || !isAdminArea)) {
            ctx.status(FORBIDDEN);
            return;
        }
        var model = new HashMap<String, Object>();
        model.put("userName", username);
        model.put("userRole", role.name());
        model.put("pageTitle", toPageTitle(page));
        if ("users".equals(page)) {
            try {
                model.put("users", mirrorDB.listUsers());
            } catch (SQLException e) {
                logger.log(SEVERE, "Failed to load users page.", e);
                ctx.status(500).result("Failed to load users.");
                return;
            }
            model.put("message", ctx.queryParam("message"));
            model.put("error", ctx.queryParam("error"));
        }
        ctx.render(page + ".jte", model);
    }

    private static void renderDBExplorerSubPage(Context ctx, EmagMirrorDB mirrorDB, String subPage) {
        if (!subPage.matches("[a-zA-Z0-9_-]+")) {
            ctx.status(400).result("Invalid sub-page name");
            return;
        }
        var currentUser = resolveCurrentUser(ctx);
        if (currentUser == null) {
            ctx.redirect("/");
            return;
        }
        String username = currentUser.username();
        PassKey.Role role = currentUser.role();
        if (role != admin) {
            ctx.status(FORBIDDEN);
            return;
        }

        var model = new HashMap<String, Object>();
        model.put("userName", username);
        model.put("userRole", role.name());
        model.put("pageTitle", "DB Explorer");

        try {
            switch (subPage) {
                case "products" -> {
                    String sort = normalizeProductSort(ctx.queryParam("sort"));
                    model.put("activeSubPage", "products");
                    model.put("activeSort", sort);
                    model.put("currentMonth", YearMonth.now().toString());
                    model.put("products", sortProducts(mirrorDB.readProducts(), sort));
                    ctx.render("db-explorer-products.jte", model);
                }
                case "vendors" -> {
                    model.put("activeSubPage", "vendors");
                    model.put("vendors", mirrorDB.getAllVendors());
                    ctx.render("db-explorer-vendors.jte", model);
                }
                case "brands" -> {
                    model.put("activeSubPage", "brands");
                    model.put("brands", mirrorDB.getAllBrands());
                    model.put("vendors", mirrorDB.getAllVendors());
                    model.put("message", ctx.queryParam("message"));
                    model.put("error", ctx.queryParam("error"));
                    ctx.render("db-explorer-brands.jte", model);
                }
                case "order-counts-by-products" -> renderOrderCountsByProductsPage(ctx, mirrorDB, model);
                default -> ctx.status(404).result("Unknown DB Explorer page.");
            }
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to load DB Explorer page " + subPage + ".", e);
            ctx.status(500).result("Failed to load DB Explorer page.");
        }
    }

    private static void renderOrderCountsByProductsPage(Context ctx,
                                                        EmagMirrorDB mirrorDB,
                                                        HashMap<String, Object> model) throws SQLException {
        var products = sortProducts(mirrorDB.readProducts(), "name");
        var selectedProduct = resolveSelectedProduct(products, ctx.queryParam("productCode"));

        var availableMonths = mirrorDB.getSalesDailyDateRange()
                .map(Server::buildSalesDailyMonths)
                .orElse(List.of());

        var currentMonth = YearMonth.now();
        var selectedMonth = resolveSelectedMonth(
                availableMonths,
                parseYearMonth(ctx.queryParam("month")),
                currentMonth
        );

        var soldQtyByDay = new HashMap<LocalDate, Long>();
        if (selectedProduct != null && selectedProduct.productCode() != null && !selectedProduct.productCode().isBlank()) {
            soldQtyByDay.putAll(mirrorDB.countSalesByDayForProductAndMonth(selectedProduct.productCode(), selectedMonth));
        }

        model.put("pageTitle", "Order counts by products");
        model.put("activeSubPage", "order-counts-by-products");
        model.put("products", products);
        model.put("months", availableMonths);
        model.put("selectedProductCode", selectedProduct == null ? "" : selectedProduct.productCode());
        model.put("selectedMonth", selectedMonth.toString());
        model.put("monthDays", buildMonthDays(selectedMonth));
        model.put("soldQtyByDay", soldQtyByDay);
        ctx.render("db-explorer-order-counts-by-products.jte", model);
    }

    private static ProductInfo resolveSelectedProduct(List<ProductInfo> products, String productCode) {
        var defaultProduct = products.stream()
                .filter(product -> product.productCode() != null && !product.productCode().isBlank())
                .findFirst()
                .orElse(null);
        if (productCode == null || productCode.isBlank()) {
            return defaultProduct;
        }
        return products.stream()
                .filter(product -> productCode.equals(product.productCode()))
                .findFirst()
                .orElse(defaultProduct);
    }

    private static YearMonth resolveSelectedMonth(List<YearMonth> availableMonths,
                                                  YearMonth requestedMonth,
                                                  YearMonth currentMonth) {
        var selectedMonth = requestedMonth == null ? currentMonth : requestedMonth;
        if (availableMonths.isEmpty()) {
            return selectedMonth;
        }
        if (availableMonths.contains(selectedMonth)) {
            return selectedMonth;
        }
        if (availableMonths.contains(currentMonth)) {
            return currentMonth;
        }
        return availableMonths.get(availableMonths.size() - 1);
    }

    private static YearMonth parseYearMonth(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(text);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static List<YearMonth> buildSalesDailyMonths(EmagMirrorDB.SalesDailyDateRange range) {
        var start = YearMonth.from(range.startDate());
        var end = YearMonth.from(range.endDate());
        var months = new ArrayList<YearMonth>();
        for (var month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    private static List<LocalDate> buildMonthDays(YearMonth month) {
        var days = new ArrayList<LocalDate>(month.lengthOfMonth());
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            days.add(month.atDay(day));
        }
        return days;
    }

    private static String normalizeProductSort(String sort) {
        if (sort == null) {
            return "name";
        }
        return switch (sort) {
            case "name", "product_code", "emag_pnk" -> sort;
            default -> "name";
        };
    }

    private static List<ProductInfo> sortProducts(List<ProductInfo> products, String sort) {
        var nameComparator = Comparator.nullsLast(ProductInfo.nameComparatorString);
        Comparator<ProductInfo> comparator = switch (sort) {
            case "product_code" ->
                    Comparator.comparing(ProductInfo::productCode, Comparator.nullsFirst(String::compareTo))
                            .thenComparing(ProductInfo::name, nameComparator);
            case "emag_pnk" -> Comparator.comparing(ProductInfo::pnk, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(ProductInfo::name, nameComparator);
            case "name" -> Comparator.comparing(ProductInfo::name, nameComparator)
                    .thenComparing(ProductInfo::productCode, Comparator.nullsFirst(String::compareTo));
            default -> throw new IllegalArgumentException("Unknown sort option: " + sort);
        };
        return products.stream().sorted(comparator).toList();
    }

    private static void changeUserRole(Context ctx, EmagMirrorDB mirrorDB) {
        final long userId;
        try {
            userId = Long.parseLong(ctx.pathParam("userId"));
        } catch (NumberFormatException e) {
            ctx.status(400).result("Invalid user id.");
            return;
        }

        String roleParam = ctx.formParam("role");
        PassKey.Role newRole = switch (roleParam) {
            case "user" -> user;
            case "admin" -> admin;
            case "nobody" -> nobody;
            case null, default -> null;
        };
        if (newRole == null) {
            ctx.status(400).result("Invalid role.");
            return;
        }

        try {
            if (!withoutAuthenticationAndTotalyUnsafe) {
                Object sessionUser = ctx.sessionAttribute("user");
                if (!(sessionUser instanceof User(String currentUsername, PassKey.Role _))) {
                    ctx.status(FORBIDDEN);
                    return;
                }
                var currentUserId = mirrorDB.findUserIdByUsername(currentUsername);
                if (currentUserId.isPresent() && currentUserId.get() == userId) {
                    ctx.redirect("/admin/users?error=You+cannot+change+your+own+role");
                    return;
                }
            }

            int updated = mirrorDB.updateUserRole(userId, newRole);
            if (updated > 0) {
                ctx.redirect("/admin/users?message=Role+updated");
            } else {
                ctx.redirect("/admin/users?error=User+not+found");
            }
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to update role for user id " + userId, e);
            ctx.redirect("/admin/users?error=Failed+to+update+role");
        }
    }

    private static void deleteUser(Context ctx, EmagMirrorDB mirrorDB) {
        final long userId;
        try {
            userId = Long.parseLong(ctx.pathParam("userId"));
        } catch (NumberFormatException e) {
            ctx.status(400).result("Invalid user id.");
            return;
        }

        try {
            if (!withoutAuthenticationAndTotalyUnsafe) {
                Object sessionUser = ctx.sessionAttribute("user");
                if (!(sessionUser instanceof User(String currentUsername, PassKey.Role _))) {
                    ctx.status(FORBIDDEN);
                    return;
                }
                var currentUserId = mirrorDB.findUserIdByUsername(currentUsername);
                if (currentUserId.isPresent() && currentUserId.get() == userId) {
                    ctx.redirect("/admin/users?error=You+cannot+delete+your+own+account");
                    return;
                }
            }

            int deleted = mirrorDB.deleteUser(userId);
            if (deleted > 0) {
                ctx.redirect("/admin/users?message=User+deleted");
            } else {
                ctx.redirect("/admin/users?error=User+not+found");
            }
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to delete user id " + userId, e);
            ctx.redirect("/admin/users?error=Failed+to+delete+user");
        }
    }

    private static String toPageTitle(String page) {
        if ("db-explorer".equals(page)) {
            return "DB Explorer";
        }
        var words = page.split("-");
        return Arrays.stream(words)
                .map(String::trim)
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static User resolveCurrentUser(Context ctx) {
        Object userAttribute = ctx.sessionAttribute("user");
        if (userAttribute instanceof User appUser) {
            return appUser;
        }
        if (withoutAuthenticationAndTotalyUnsafe) {
            return unsafeUser;
        }
        return null;
    }

    private static void checkRole(Context ctx, PassKey.Role minimalRole) {
        if (withoutAuthenticationAndTotalyUnsafe) {
            return;
        }
        var session = ctx.req().getSession(false);
        if (minimalRole != null) {  // No checks if no role is required.
            if (session == null) {
                ctx.redirect("/");
            } else if (session.getAttribute("user") instanceof User(String username, PassKey.Role role)) {
                if (role == nobody) {
                    ctx.redirect("/welcome.html");
                } else if (role.ordinal() < minimalRole.ordinal()) {
                    ctx.status(FORBIDDEN);
                } else {
                    logger.log(INFO, "User = " + username);
                }
            }
        }
    }

    static gg.jte.TemplateEngine createJteEngine() {
        return gg.jte.TemplateEngine.create(
                new gg.jte.resolve.DirectoryCodeResolver(Paths.get("src/main/jte")),
                Paths.get("target", "jte-classes"),
                gg.jte.ContentType.Html,
                Server.class.getClassLoader()
        );
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
                logger.log(SEVERE, "BackgroundJob failed.", e);
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
