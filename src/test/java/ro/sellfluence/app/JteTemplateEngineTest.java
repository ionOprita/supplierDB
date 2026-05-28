package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
