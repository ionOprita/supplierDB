package ro.sellfluence.app;

import gg.jte.output.StringOutput;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.Vendor;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthlyGMVTemplateTest {
    @Test
    void rendersIdentitiesMonthlyValuesAndUuidVendorFiltersWithoutEditableCells() {
        var firstVendor = vendor("00000000-0000-0000-0000-000000000001", "Same vendor name");
        var secondVendor = vendor("00000000-0000-0000-0000-000000000002", "Same vendor name");
        var month = YearMonth.of(2026, 1);
        var first = product("PNK-A", "CODE-A", "S. 2 - Same product", firstVendor.id());
        var second = product("PNK-B", "CODE-B", "S. 2 - Same product", null);
        var report = MonthlyGMVPage.from(Map.of(
                first, Map.of(month.minusMonths(1), BigDecimal.ZERO, month, new BigDecimal("-12.34")),
                second, Map.of()
        ));

        var html = render(report, List.of(firstVendor, secondVendor));
        var table = table(html);
        var expectedHeaders = new ArrayList<>(List.of("Product", "PNK", "Product code"));
        expectedHeaders.addAll(report.months().stream().map(YearMonth::toString).toList());
        assertEquals(expectedHeaders, elementTexts(table, "th"));
        var cells = elementTexts(table, "td");
        assertTrue(cells.containsAll(List.of("S. 2 - Same product", "PNK-A", "CODE-A", "PNK-B", "CODE-B")));
        assertTrue(cells.containsAll(List.of("0.00", "-12.34", "—")));
        assertTrue(table.contains("data-vendor-id=\"" + firstVendor.id() + "\""));
        assertTrue(table.contains("data-vendor-id=\"\""));
        assertTrue(html.contains("id=\"gmvVendorFilterSelect\""));
        assertTrue(html.contains("<option value=\"" + firstVendor.id() + "\">Same vendor name</option>"));
        assertTrue(html.contains("<option value=\"" + secondVendor.id() + "\">Same vendor name</option>"));
        assertTrue(html.contains("id=\"gmvNoRows\""));
        assertTrue(html.contains("href=\"/private/gmv\""));
        assertFalse(Pattern.compile("<(?:input|select|textarea|button)\\b|contenteditable\\s*=", Pattern.CASE_INSENSITIVE)
                .matcher(table).find());
        assertFalse(html.contains("No stored GMV available."));
    }

    @Test
    void escapesProductAndVendorMetadata() {
        var vendor = vendor("00000000-0000-0000-0000-000000000003", "<Vendor & Co>");
        var product = product("PNK<&", "CODE<&", "S. 1 - <script>alert('x')</script>", vendor.id());
        var report = MonthlyGMVPage.from(Map.of(product, Map.of(YearMonth.of(2026, 1), BigDecimal.ONE)));

        var html = render(report, List.of(vendor));

        assertTrue(html.contains("PNK&lt;&amp;"));
        assertTrue(html.contains("CODE&lt;&amp;"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;/script&gt;"));
        assertTrue(html.contains("&lt;Vendor &amp; Co&gt;"));
        assertFalse(html.contains("<script>alert('x')</script>"));
        assertFalse(html.contains("<Vendor & Co>"));
    }

    @Test
    void explainsWhenProductsHaveNoStoredGmv() {
        var product = product("PNK-EMPTY", "CODE-EMPTY", "S. 1 - Empty", null);

        var html = render(MonthlyGMVPage.from(Map.of(product, Map.of())), List.of());

        assertTrue(html.contains("No stored GMV available."));
    }

    @Test
    void rendersAnEmptyCatalog() {
        var html = render(MonthlyGMVPage.from(Map.of()), List.of());

        assertTrue(html.contains("No stored GMV available."));
        assertTrue(html.contains("href=\"/private/gmv\""));
    }

    private static String render(MonthlyGMVPage report, List<Vendor> vendors) {
        var output = new StringOutput();
        Server.createJteEngine().render("gmv.jte", Map.of(
                "userName", "test-user",
                "userRole", "user",
                "pageTitle", "GMV",
                "vendors", vendors,
                "report", report
        ), output);
        return output.toString();
    }

    private static String table(String html) {
        var matcher = Pattern.compile("<table\\b[^>]*\\bid=\"monthlyGmvTable\"[^>]*>(.*?)</table>", Pattern.DOTALL)
                .matcher(html);
        assertTrue(matcher.find(), "Monthly GMV table should be rendered");
        return matcher.group(1);
    }

    private static List<String> elementTexts(String html, String element) {
        var matcher = Pattern.compile("<" + element + "(?:\\s[^>]*)?>(.*?)</" + element + ">", Pattern.DOTALL)
                .matcher(html);
        var texts = new ArrayList<String>();
        while (matcher.find()) {
            texts.add(matcher.group(1).replaceAll("<[^>]*>", "").strip());
        }
        return texts;
    }

    private static ProductInfo product(String pnk, String code, String name, UUID vendor) {
        return new ProductInfo(pnk, code, name, vendor, false, false,
                null, null, null, null);
    }

    private static Vendor vendor(String id, String name) {
        return new Vendor(UUID.fromString(id), name, false, null, "account", null);
    }
}
