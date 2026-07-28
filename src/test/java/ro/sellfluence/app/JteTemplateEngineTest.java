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
        model.put("logFiles", List.of(new ServerLogFiles.Entry(
                "java app <latest>.log",
                "java%20app%20%3Clatest%3E.log",
                123L,
                Instant.parse("2026-07-28T08:30:00Z")
        )));
        model.put("error", null);

        Server.createJteEngine().render("logs.jte", model, output);

        var html = output.toString();
        assertTrue(html.contains("java app &lt;latest&gt;.log"));
        assertTrue(html.contains("/admin/logs/view?file=java%20app%20%3Clatest%3E.log"));
        assertTrue(html.contains("/admin/logs/download?file=java%20app%20%3Clatest%3E.log"));
        assertTrue(html.contains("2026-07-28T08:30:00Z"));
        assertTrue(html.contains("Server Logs"));
    }
}
