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
        for (var page : new String[]{"profit-exposure", "profit-by-category", "profit-fbe-tariffs", "profit-date-calcul"}) {
            var title = switch (page) {
                case "profit-exposure" -> "Calc. Expunere & Profit";
                case "profit-by-category" -> "By Category";
                case "profit-fbe-tariffs" -> "FBE Tariffs";
                default -> "Date Calcul";
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
            assertTrue(html.indexOf("href=\"/private/profit-date-calcul\"")
                    > html.indexOf("href=\"/private/profit-fbe-tariffs\""));
            assertEquals(page.equals("profit-exposure") || page.equals("profit-by-category"),
                    html.contains("id=\"profitVendorGroupSelect\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategorySelect\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategoryTable\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitCategorySummaryBody\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitOverrideDialog\""));
            assertEquals(page.equals("profit-by-category"), html.contains("id=\"profitOverrideReview\""));
            assertEquals(page.equals("profit-fbe-tariffs"), html.contains("id=\"fbeTariffBody\""));
            assertEquals(page.equals("profit-date-calcul"), html.contains("id=\"dateCalculGroup\""));
            assertEquals(page.equals("profit-date-calcul"), html.contains("id=\"dateCalculGeneralFields\""));
            assertEquals(page.equals("profit-date-calcul"), html.contains("id=\"dateCalculGeneralCostsFields\""));
            assertEquals(page.equals("profit-date-calcul"), html.contains("id=\"dateCalculEmployeesFields\""));
            assertEquals(page.equals("profit-date-calcul"), html.contains("id=\"dateCalculMiscellaneousFields\""));
            assertTrue(html.contains(switch (page) {
                case "profit-fbe-tariffs" -> "src=\"/js/profit-fbe-tariffs.js\"";
                case "profit-date-calcul" -> "src=\"/js/profit-date-calcul.js\"";
                default -> "src=\"/js/profit-calculator.js\"";
            }));
            assertFalse(html.contains("2. Trim."));
        }
    }
}
