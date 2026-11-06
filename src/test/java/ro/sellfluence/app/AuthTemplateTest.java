package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTemplateTest {
    @Test
    void activationNoticeEscapesUsernameAndRetainsLogoutWithoutAuthorizedNavigation() {
        var output = new StringOutput();
        Server.createJteEngine().render("welcome.jte", Map.of(
                "userName", "ion<img src=x onerror=alert(1)>",
                "userRole", "nobody",
                "pageTitle", "Welcome"
        ), output);
        String html = output.toString();

        assertTrue(html.contains("class=\"auth-pending-card\" aria-labelledby=\"activationTitle\""));
        assertTrue(html.contains("ion&lt;img src=x onerror=alert(1)&gt;"));
        assertFalse(html.contains("ion<img"));
        assertTrue(html.contains("administrator to activate your account"));
        assertTrue(html.contains("sign out and sign in again"));
        assertTrue(html.contains("action=\"/logout\" method=\"post\""));
        assertFalse(html.contains("id=\"appSidebar\""));
        assertFalse(html.contains("id=\"serverStopButton\""));
    }
}
