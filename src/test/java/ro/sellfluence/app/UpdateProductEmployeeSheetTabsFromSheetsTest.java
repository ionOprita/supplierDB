package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductTable.EmployeeSheetTabUpdate;
import ro.sellfluence.db.ProductTable.ProductInfo;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateProductEmployeeSheetTabsFromSheetsTest {

    @Test
    void mappingsFromSkipsHeadersAndMalformedRows() {
        var rows = List.of(
                List.<Object>of("Header C", "Header E"),
                List.<Object>of("Second header C", "Second header E"),
                List.<Object>of("PNK-1", "Tab 1"),
                List.<Object>of("PNK without a tab"),
                List.<Object>of(42, "Tab for a non-string PNK"),
                List.<Object>of("", "Tab for a blank PNK"),
                List.<Object>of("PNK with a blank tab", "  "),
                List.<Object>of("PNK-2", "Tab 2")
        );

        var mappings = UpdateProductEmployeeSheetTabsFromSheets.mappingsFrom(rows);

        assertEquals(Map.of("PNK-1", "Tab 1", "PNK-2", "Tab 2"), mappings);
    }

    @Test
    void mappingsFromUsesTheLastTabForADuplicatePNK() {
        var rows = List.of(
                List.<Object>of("Header C", "Header E"),
                List.<Object>of("Second header C", "Second header E"),
                List.<Object>of("PNK-1", "Old tab"),
                List.<Object>of("PNK-1", "Current tab")
        );

        var mappings = UpdateProductEmployeeSheetTabsFromSheets.mappingsFrom(rows);

        assertEquals(Map.of("PNK-1", "Current tab"), mappings);
    }

    @Test
    void tabUpdatesForClearsMissingMappingsIncludingProductsWithoutAPNK() {
        var products = List.of(
                product("PNK-1", "CODE-1", "Employee sheet", "Old tab 1"),
                product("PNK-2", "CODE-2", "Employee sheet", "Old tab 2"),
                product("", "CODE-3", "Employee sheet", "Old tab 3"),
                product(null, "CODE-4", "Employee sheet", "Old tab 4")
        );

        var updates = UpdateProductEmployeeSheetTabsFromSheets.tabUpdatesFor(
                products,
                Map.of("PNK-1", "New tab 1")
        );

        assertEquals(
                List.of(
                        new EmployeeSheetTabUpdate("CODE-1", "PNK-1", "Employee sheet", "New tab 1"),
                        new EmployeeSheetTabUpdate("CODE-2", "PNK-2", "Employee sheet", null),
                        new EmployeeSheetTabUpdate("CODE-3", "", "Employee sheet", null),
                        new EmployeeSheetTabUpdate("CODE-4", null, "Employee sheet", null)
                ),
                updates
        );
    }

    private static ProductInfo product(String pnk, String productCode, String sheetName, String tabName) {
        return new ProductInfo(
                pnk,
                productCode,
                "Z. 1 - Test product",
                null,
                false,
                false,
                null,
                null,
                sheetName,
                tabName
        );
    }
}
