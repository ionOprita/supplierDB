package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorTemplateTest {
    @Test
    void rendersBothProfitPagesInTheSharedNavigation() {
        for (var page : new String[]{"profit-exposure", "profit-by-category"}) {
            var title = page.equals("profit-exposure") ? "Calc. Expunere & Profit" : "By Category";
            assertEquals(title, Server.toPageTitle(page));
            var output = new StringOutput();
            Server.createJteEngine().render(page + ".jte", Map.of(
                    "userName", "test-user", "userRole", "user", "pageTitle", title
            ), output);
            var html = output.toString();

            assertTrue(html.contains("href=\"/private/profit-exposure\""));
            assertTrue(html.contains("href=\"/private/profit-by-category\""));
            assertTrue(html.contains("id=\"profitVendorGroupSelect\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategorySelect\""));
            assertTrue(html.contains("src=\"/js/profit-calculator.js\""));
            assertFalse(html.contains("2. Trim."));
        }
    }
}
