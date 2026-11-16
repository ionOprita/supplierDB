package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorTemplateTest {
    @Test
    void rendersProfitPagesInTheSharedNavigation() {
        for (var page : new String[]{"profit-exposure", "profit-by-category", "profit-fbe-tariffs"}) {
            var title = switch (page) {
                case "profit-exposure" -> "Calc. Expunere & Profit";
                case "profit-by-category" -> "By Category";
                default -> "FBE Tariffs";
            };
            assertEquals(title, Server.toPageTitle(page));
            var output = new StringOutput();
            Server.createJteEngine().render(page + ".jte", Map.of(
                    "userName", "test-user", "userRole", "user", "pageTitle", title
            ), output);
            var html = output.toString();

            assertTrue(html.contains("href=\"/private/profit-exposure\""));
            assertTrue(html.contains("href=\"/private/profit-by-category\""));
            assertTrue(html.contains("href=\"/private/profit-fbe-tariffs\""));
            assertTrue(html.indexOf("href=\"/private/profit-fbe-tariffs\"")
                    > html.indexOf("href=\"/private/profit-by-category\""));
            assertEquals(!page.equals("profit-fbe-tariffs"), html.contains("id=\"profitVendorGroupSelect\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategorySelect\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategoryTable\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategorySummaryBody\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitOverrideDialog\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitOverrideReview\""));
            assertEquals(page.equals("profit-fbe-tariffs"), html.contains("id=\"fbeTariffBody\""));
            assertTrue(html.contains(page.equals("profit-fbe-tariffs")
                    ? "src=\"/js/profit-fbe-tariffs.js\"" : "src=\"/js/profit-calculator.js\""));
            assertFalse(html.contains("2. Trim."));
        }
    }
}
