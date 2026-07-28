package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Brand;
import ro.sellfluence.db.Vendor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JteTemplateEngineTest {
    @Test
    void rendersTemplateWithServerClassLoader() {
        StringOutput output = new StringOutput();

        Server.createJteEngine().render("overview.jte", Map.of(
                "userName", "test-user",
                "userRole", "admin",
                "pageTitle", "Overview"
        ), output);

        assertTrue(output.toString().contains("Select an item from the menu."));
    }

    @Test
    void rendersDbExplorerBrandsTemplate() {
        StringOutput output = new StringOutput();
        var vendorId = UUID.randomUUID();
        var brandId = UUID.randomUUID();

        Server.createJteEngine().render("db-explorer-brands.jte", Map.of(
                "userName", "test-user",
                "userRole", "admin",
                "pageTitle", "DB Explorer",
                "activeSubPage", "brands",
                "brands", List.of(new Brand(brandId, "Acme", vendorId, "Acme Vendor")),
                "vendors", List.of(new Vendor(vendorId, "Acme Vendor", false, "Acme SRL", "main", null)),
                "message", "",
                "error", ""
        ), output);

        var html = output.toString();
        assertTrue(html.contains("Add brand"));
        assertTrue(html.contains("Acme"));
        assertTrue(html.contains("/admin/db-explorer/brands/" + brandId + "/delete"));
    }

    @Test
    void rendersServerLogFilesTemplate() {
        StringOutput output = new StringOutput();
        var model = new HashMap<String, Object>();
        model.put("userName", "test-admin");
        model.put("userRole", "admin");
        model.put("pageTitle", "Server Logs");
        model.put("logSections", List.of(
                new ServerLogFiles.Section(
                        "Supervisor logs",
                        "Files written by the PowerShell application supervisor.",
                        "supervisor",
                        List.of(new ServerLogFiles.Entry(
                                "java app <latest>.log",
                                "java%20app%20%3Clatest%3E.log",
                                123L,
                                Instant.parse("2026-07-28T08:30:00Z")
                        )),
                        null
                ),
                new ServerLogFiles.Section(
                        "Application logs",
                        "Files written by Java logging under java.io.tmpdir/EmagDBLogs.",
                        "application",
                        List.of(new ServerLogFiles.Entry(
                                "Server_0.log",
                                "Server_0.log",
                                456L,
                                Instant.parse("2026-07-28T08:31:00Z")
                        )),
                        null
                )
        ));

        Server.createJteEngine().render("logs.jte", model, output);

        var html = output.toString();
        assertTrue(html.contains("Supervisor logs"));
        assertTrue(html.contains("Application logs"));
        assertTrue(html.contains("java.io.tmpdir/EmagDBLogs"));
        assertTrue(html.contains("java app &lt;latest&gt;.log"));
        assertTrue(html.contains("Server_0.log"));
        assertTrue(html.contains(
                "/admin/logs/view?source=supervisor&amp;file=java%20app%20%3Clatest%3E.log"
        ));
        assertTrue(html.contains(
                "/admin/logs/download?source=application&amp;file=Server_0.log"
        ));
        assertTrue(html.contains("2026-07-28T08:30:00Z"));
        assertTrue(html.contains("Server Logs"));
    }

    @Test
    void rendersLogDirectoryErrorsWithoutHidingTheOtherDirectory() {
        StringOutput output = new StringOutput();
        var model = new HashMap<String, Object>();
        model.put("userName", "test-admin");
        model.put("userRole", "admin");
        model.put("pageTitle", "Server Logs");
        model.put("logSections", List.of(
                new ServerLogFiles.Section(
                        "Supervisor logs",
                        "Supervisor description",
                        "supervisor",
                        List.of(new ServerLogFiles.Entry(
                                "available.log",
                                "available.log",
                                12L,
                                Instant.parse("2026-07-28T08:30:00Z")
                        )),
                        null
                ),
                new ServerLogFiles.Section(
                        "Application logs",
                        "Application description",
                        "application",
                        List.of(),
                        "Application logs are unavailable <retry>."
                )
        ));

        Server.createJteEngine().render("logs.jte", model, output);

        var html = output.toString();
        assertTrue(html.contains("available.log"));
        assertTrue(html.contains("Application logs are unavailable &lt;retry&gt;."));
    }
}
