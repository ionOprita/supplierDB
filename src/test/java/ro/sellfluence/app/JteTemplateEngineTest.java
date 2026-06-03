package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Brand;
import ro.sellfluence.db.Vendor;

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
}
