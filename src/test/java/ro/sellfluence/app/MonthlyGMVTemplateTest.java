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
        assertTrue(table.contains("data-product-code=\"CODE-A\""));
        assertTrue(table.contains("data-product-code=\"CODE-B\""));
        assertTrue(html.contains("id=\"gmvVendorFilterSelect\""));
        assertTrue(html.contains("<option value=\"" + firstVendor.id() + "\">Same vendor name</option>"));
        assertTrue(html.contains("<option value=\"" + secondVendor.id() + "\">Same vendor name</option>"));
        assertTrue(html.contains("id=\"gmvNoRows\""));
        assertTrue(html.contains("href=\"/private/gmv\""));
        var products = elementContent(html, "select", "gmvProductSelect");
        assertTrue(products.contains("<option value=\"\" selected>All products</option>"));
        assertTrue(products.contains("<option value=\"CODE-A\" data-vendor-id=\"" + firstVendor.id()
                + "\">S. 2 - Same product (CODE-A)</option>"));
        assertTrue(products.contains("<option value=\"CODE-B\" data-vendor-id=\"\">S. 2 - Same product (CODE-B)</option>"));
        assertFalse(Pattern.compile("<(?:input|select|textarea)\\b|contenteditable\\s*=", Pattern.CASE_INSENSITIVE)
                .matcher(table).find());
        var tableButtons = Pattern.compile("<button\\b[^>]*>").matcher(table).results().toList();
        assertEquals(2, tableButtons.size());
        for (var button : tableButtons) {
            assertTrue(button.group().contains("gmv-details-trigger"));
            assertTrue(button.group().contains("type=\"button\""));
        }
        assertFalse(html.contains("No stored GMV available."));
    }

    @Test
    void rendersPeriodCalculatorWithRequiredCalendarDefaultsAndAccessibleStatus() {
        var html = render(MonthlyGMVPage.from(Map.of()), List.of());

        assertTrue(html.contains(">GMV for a period</h2>"));
        assertTrue(html.contains(">Monthly GMV</h2>"));
        assertTrue(html.indexOf("id=\"gmvVendorFilterSelect\"") < html.indexOf("id=\"gmvPeriodForm\""));
        assertTrue(html.indexOf("id=\"gmvPeriodForm\"") < html.indexOf(">Monthly GMV</h2>"));
        assertTrue(openingTag(html, "select", "gmvProductSelect").contains("required"));
        var start = openingTag(html, "input", "gmvStartDate");
        assertTrue(start.contains("type=\"date\""));
        assertTrue(start.contains("value=\"2026-09-17\""));
        assertTrue(start.contains("required"));
        var end = openingTag(html, "input", "gmvEndDate");
        assertTrue(end.contains("type=\"date\""));
        assertTrue(end.contains("value=\"2026-10-16\""));
        assertTrue(end.contains("required"));
        assertTrue(openingTag(html, "button", "gmvCalculateButton").contains("disabled"));
        assertTrue(html.contains(">Calculate GMV</button>"));
        var result = openingTag(html, "button", "gmvPeriodResult");
        assertTrue(result.contains("type=\"button\""));
        assertTrue(result.contains("hidden"));
        assertTrue(result.contains("aria-label=\"View contributing orders\""));
        assertTrue(result.contains("title=\"View contributing orders\""));
        assertEquals("", elementContent(html, "span", "gmvPeriodContext"));
        var status = openingTag(html, "p", "gmvPeriodStatus");
        assertTrue(status.contains("role=\"status\""));
        assertTrue(status.contains("aria-live=\"polite\""));
        assertTrue(html.contains("<label for=\"gmvProductSelect\">Product</label>"));
        assertTrue(html.contains("<label for=\"gmvStartDate\">Start date</label>"));
        assertTrue(html.contains("<label for=\"gmvEndDate\">End date</label>"));
        assertTrue(html.contains("Both dates included. Prices include VAT."));
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
        assertTrue(elementContent(html, "select", "gmvProductSelect").contains("value=\"CODE&lt;&amp;\""));
        assertTrue(table(html).contains("data-product-code=\"CODE&lt;&amp;\""));
        assertTrue(table(html).contains("data-product-name=\"S. 1 - &lt;script>alert(&#39;x&#39;)&lt;/script>\""));
        assertFalse(html.contains("<script>alert('x')</script>"));
        assertFalse(html.contains("<Vendor & Co>"));
    }

    @Test
    void onlyStoredMonthlyValuesIncludingZeroAreDrilldownButtons() {
        var product = product("PNK", " exact/code? & + ", "S. 1 - Product", null);
        var report = MonthlyGMVPage.from(Map.of(product, Map.of(
                YearMonth.of(2026, 1), BigDecimal.ZERO,
                YearMonth.of(2026, 2), new BigDecimal("9007199254740993.12")
        )));

        var table = table(render(report, List.of()));
        var buttons = Pattern.compile("<button\\b[^>]*>(.*?)</button>", Pattern.DOTALL)
                .matcher(table).results().toList();

        assertEquals(2, buttons.size());
        assertTrue(buttons.getFirst().group().contains("data-month=\"2026-01\""));
        assertTrue(buttons.getFirst().group().contains("data-gmv=\"0.00\""));
        assertTrue(buttons.getFirst().group().contains(">0.00</button>"));
        assertTrue(buttons.getLast().group().contains("data-month=\"2026-02\""));
        assertTrue(buttons.getLast().group().contains("data-gmv=\"9007199254740993.12\""));
        assertTrue(buttons.getLast().group().contains(">9007199254740993.12</button>"));
        for (var button : buttons) {
            assertTrue(button.group().contains("data-product-code=\" exact/code? &amp; + \""));
            assertTrue(button.group().contains("data-product-name=\"S. 1 - Product\""));
            assertTrue(button.group().contains("type=\"button\""));
        }
        assertEquals(22, Pattern.compile("<td class=\"numeric\">\\s*—\\s*</td>").matcher(table).results().count());
    }

    @Test
    void rendersSharedAccessibleDetailsDialogWithReadOnlyColumnsAndSeparateTotal() {
        var html = render(MonthlyGMVPage.from(Map.of()), List.of());

        var dialogTag = openingTag(html, "dialog", "gmvDetailsDialog");
        assertTrue(dialogTag.contains("aria-labelledby=\"gmvDetailsTitle\""));
        var dialog = elementContent(html, "dialog", "gmvDetailsDialog");
        assertTrue(openingTag(dialog, "table", "gmvDetailsTable").contains("class=\"gmv-details-table\""));
        assertEquals(List.of("Order ID", "Date", "Status", "Quantity used", "Unit price incl. VAT", "Seller voucher adjustment", "GMV contribution"),
                elementTexts(dialog, "th"));
        assertEquals("Contributing orders", elementContent(dialog, "h2", "gmvDetailsTitle"));
        assertEquals("", elementContent(dialog, "p", "gmvDetailsContext"));
        assertEquals("", elementContent(dialog, "p", "gmvDetailsReference"));
        assertEquals("", elementContent(dialog, "tbody", "gmvDetailsBody"));
        var status = openingTag(dialog, "p", "gmvDetailsStatus");
        assertTrue(status.contains("role=\"status\""));
        assertTrue(status.contains("aria-live=\"polite\""));
        assertTrue(openingTag(dialog, "p", "gmvDetailsMismatch").contains("hidden"));
        assertEquals("—", elementContent(dialog, "strong", "gmvDetailsTotal"));
        assertTrue(dialog.indexOf("id=\"gmvDetailsTotal\"") > dialog.indexOf("</table>"));
        var download = openingTag(dialog, "button", "gmvDetailsDownloadCsv");
        assertTrue(download.contains("type=\"button\""));
        assertTrue(download.contains("disabled"));
        assertFalse(download.contains("hidden"));
        assertEquals("Download CSV", elementContent(dialog, "button", "gmvDetailsDownloadCsv"));
        assertTrue(dialog.indexOf("id=\"gmvDetailsDownloadCsv\"") < dialog.indexOf("id=\"gmvDetailsRetry\""));
        var retry = openingTag(dialog, "button", "gmvDetailsRetry");
        assertTrue(retry.contains("type=\"button\""));
        assertTrue(retry.contains("hidden"));
        var close = openingTag(dialog, "button", "gmvDetailsClose");
        assertTrue(close.contains("type=\"button\""));
        assertTrue(close.contains("autofocus"));
        assertEquals("Close", elementContent(dialog, "button", "gmvDetailsClose"));
        assertFalse(Pattern.compile("<(?:input|select|textarea)\\b|contenteditable\\s*=", Pattern.CASE_INSENSITIVE)
                .matcher(dialog).find());
    }

    @Test
    void explainsWhenProductsHaveNoStoredGmv() {
        var product = product("PNK-EMPTY", "CODE-EMPTY", "S. 1 - Empty", null);

        var html = render(MonthlyGMVPage.from(Map.of(product, Map.of())), List.of());

        assertTrue(html.contains("No stored GMV available."));
        assertTrue(html.indexOf("No stored GMV available.") > html.indexOf(">Monthly GMV</h2>"));
        assertTrue(elementContent(html, "select", "gmvProductSelect").contains(
                "<option value=\"CODE-EMPTY\" data-vendor-id=\"\">S. 1 - Empty (CODE-EMPTY)</option>"));
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
                "report", report,
                "defaultStartDate", "2026-09-17",
                "defaultEndDate", "2026-10-16"
        ), output);
        return output.toString();
    }

    private static String table(String html) {
        return elementContent(html, "table", "monthlyGmvTable");
    }

    private static String elementContent(String html, String element, String id) {
        var matcher = Pattern.compile("<" + element + "\\b[^>]*\\bid=\"" + id + "\"[^>]*>(.*?)</" + element + ">", Pattern.DOTALL)
                .matcher(html);
        assertTrue(matcher.find(), "Expected " + element + "#" + id);
        return matcher.group(1);
    }

    private static String openingTag(String html, String element, String id) {
        var matcher = Pattern.compile("<" + element + "\\b[^>]*\\bid=\"" + id + "\"[^>]*>").matcher(html);
        assertTrue(matcher.find(), "Expected " + element + "#" + id);
        return matcher.group();
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
