package ro.sellfluence.app;

import com.google.gson.Gson;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.db.StockImport.Stored;
import ro.sellfluence.db.StockImportCosts;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Run with -DstockImports.browser=true when the local Playwright Chromium runtime is available. */
@EnabledIfSystemProperty(named = "stockImports.browser", matches = "true")
class StockImportBrowserTest {
    private static final String BASE = "http://stock.test";
    private static final String ID = "e8038aae-0481-4756-951b-6d534be5aa05";
    private static final Gson JSON = new Gson();

    @Test
    void employeesCanFindCreateAndEditCompanyScopedRecords() {
        var html = new StringOutput();
        Server.createJteEngine().render("importuri.jte", Map.of("userName", "Ana", "userRole", "user", "pageTitle", "Importuri"), html);
        var saved = new AtomicReference<Document>();
        try (var playwright = Playwright.create(); var browser = playwright.chromium().launch()) {
            var desktop = browser.newPage(new Browser.NewPageOptions().setViewportSize(1440, 1000));
            desktop.setDefaultTimeout(5000);
            route(desktop, html.toString(), saved);
            desktop.navigate(BASE + "/private/importuri?group=S");
            desktop.locator("#importSearch").fill("absent");
            desktop.getByText("Niciun import pentru această căutare.").waitFor();
            desktop.locator("#importSearch").fill("49");
            desktop.getByRole(com.microsoft.playwright.options.AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Import 49A")).waitFor();
            desktop.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/importuri-register-desktop.png")));
            desktop.locator("#newImport").click();
            desktop.locator("[name='reference']").fill("50B");
            desktop.locator("[name='method']").selectOption("SEA");
            desktop.locator("summary", new Page.LocatorOptions().setHasText("Produse")).click();
            desktop.getByPlaceholder("Caută model, PNK sau cod produs").fill("ABC");
            desktop.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Generator · ABC · PNK1")).click();
            desktop.locator("[name='quantity']").fill("5");
            desktop.locator("[name='unitUsd']").fill("0");
            desktop.getByText("Ambalare și coduri").click();
            assertEquals("2", desktop.locator("[name='piecesPerCarton']").inputValue());
            desktop.locator("[name='transitDays']").fill("99");
            desktop.locator("[name='method']").selectOption("AIR");
            assertEquals("", desktop.locator("[name='transitDays']").inputValue());
            desktop.getByText("Ambalare și coduri").click();
            assertEquals("4", desktop.locator("[name='piecesPerCarton']").inputValue());
            desktop.locator("#saveImport").click();
            desktop.waitForURL("**/private/importuri?group=S&import=" + ID);
            assertEquals("50B", saved.get().fields().get("reference"));
            assertEquals("0.21", saved.get().fields().get("vatRate"));
            assertEquals(0, saved.get().lines().getFirst().unitUsd().signum());
            desktop.locator("[name='model']").scrollIntoViewIfNeeded();
            desktop.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/importuri-editor-desktop.png")));
            desktop.locator("[name='reference']").fill("Modificare locală");
            desktop.locator("#saveImport").click();
            desktop.locator("#importSaveStatus a").waitFor();
            assertEquals("Modificare locală", desktop.locator("[name='reference']").inputValue());
            desktop.onDialog(Dialog::dismiss);
            desktop.locator("#importGroup").selectOption("K");
            assertEquals("S", desktop.locator("#importGroup").inputValue());
            assertTrue(desktop.url().contains("group=S"));

            var mobile = browser.newPage(new Browser.NewPageOptions().setViewportSize(390, 844).setIsMobile(true));
            route(mobile, html.toString(), saved);
            mobile.navigate(BASE + "/private/importuri?group=S&import=" + ID);
            mobile.locator("summary", new Page.LocatorOptions().setHasText("Produse")).click();
            mobile.locator("[name='model']").scrollIntoViewIfNeeded();
            assertEquals(true, mobile.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));
            assertEquals(true, mobile.locator("[name='unitUsd']").evaluate("el => el.getBoundingClientRect().right <= window.innerWidth"));
            mobile.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/importuri-editor-mobile.png")));
            mobile.locator("#importGroup").selectOption("K");
            mobile.getByText("Această companie nu are încă importuri.").waitFor();
            assertTrue(mobile.url().endsWith("group=K"));
        }
    }

    private static void route(Page page, String html, AtomicReference<Document> saved) {
        page.route("**/*", route -> {
            var request = route.request();
            String path = URI.create(request.url()).getPath();
            var response = new Route.FulfillOptions();
            if (path.equals("/private/importuri")) {
                route.fulfill(response.setContentType("text/html").setBody(html));
                return;
            }
            if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png")) {
                route.fulfill(response.setPath(Path.of("src/main/resources/static").resolve(path.substring(1))));
                return;
            }
            response.setContentType("application/json");
            String body = switch (path) {
                case "/app/stocuri/groups" -> """
                        [{"key":"S","companies":["Sellfusion SRL"]},{"key":"K","companies":["Koppel SRL"]}]
                        """;
                case "/app/stocuri/K/imports" -> "[]";
                case "/app/stocuri/S/products" -> """
                        [{"productCode":"ABC","model":"Generator","pnk":"PNK1","dutyRate":"0.03",
                          "packing":{"SEA":{"piecesPerCarton":"2"},"AIR":{"piecesPerCarton":"4"}}}]
                        """;
                case "/app/stocuri/S/calculate" -> JSON.toJson(StockImportCosts.calculate(JSON.fromJson(request.postData(), Document.class)));
                case "/app/stocuri/S/imports" -> {
                    if (request.method().equals("GET")) yield """
                            [{"id":"%s","reference":"49A","sellingCompany":"Sellfusion SRL","status":"Livrat","units":0}]
                            """.formatted(ID);
                    var doc = JSON.fromJson(request.postData(), Document.class).version(UUID.fromString(ID), 1);
                    saved.set(doc);
                    yield JSON.toJson(new Stored(doc, StockImportCosts.calculate(doc)));
                }
                case "/app/stocuri/S/imports/" + ID -> {
                    if (request.method().equals("PUT")) {
                        response.setStatus(409);
                        yield "{\"error\":\"Alt utilizator a modificat acest import.\"}";
                    }
                    yield JSON.toJson(new Stored(saved.get(), StockImportCosts.calculate(saved.get())));
                }
                default -> { response.setStatus(404); yield "{}"; }
            };
            route.fulfill(response.setBody(body));
        });
    }
}
