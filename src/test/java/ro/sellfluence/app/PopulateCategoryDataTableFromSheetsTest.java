package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.CategoryDataTable.CategoryColumn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ro.sellfluence.db.CategoryDataTable.SOURCE_COLUMN_COUNT;

class PopulateCategoryDataTableFromSheetsTest {
    @Test
    void toCategoryInfosMapsRowsAndNormalizesIntegerIds() {
        var rows = new ArrayList<List<String>>();
        rows.add(emptyRow());
        rows.add(emptyRow());

        var category = emptyRow();
        set(category, CategoryColumn.SHEET_INDEX, "1");
        set(category, CategoryColumn.SUBCATEGORY_COUNTRY, " Sonerii electrice\r");
        set(category, CategoryColumn.BIG_CATEGORY, "Home & Garden");
        set(category, CategoryColumn.DIVISION, "DIY");
        set(category, CategoryColumn.CATEGORY_ID, "3,010");
        set(category, CategoryColumn.SCM_ID, "3,109");
        set(category, CategoryColumn.DOC_ID, "3,531");
        rows.add(category);

        var emptyCategorySlot = emptyRow();
        set(emptyCategorySlot, CategoryColumn.SHEET_INDEX, "27");
        set(emptyCategorySlot, CategoryColumn.SUBCATEGORY_COUNTRY, " \r");
        rows.add(emptyCategorySlot);

        var categories = PopulateCategoryDataTableFromSheets.toCategoryInfos(rows);

        assertEquals(1, categories.size());
        var info = categories.getFirst();
        assertEquals(3, info.sourceRowNumber());
        assertEquals("Sonerii electrice", info.subcategoryCountry());
        assertEquals("Home & Garden", info.value(CategoryColumn.BIG_CATEGORY));
        assertEquals("DIY", info.value(CategoryColumn.DIVISION));
        assertEquals("3010", info.value(CategoryColumn.CATEGORY_ID));
        assertEquals("3109", info.value(CategoryColumn.SCM_ID));
        assertEquals("3531", info.value(CategoryColumn.DOC_ID));
        assertEquals(SOURCE_COLUMN_COUNT, info.sourceValues().size());
    }

    @Test
    void toCategoryInfosPadsShortRows() {
        var rows = List.of(
                List.<String>of(),
                List.<String>of(),
                List.of("1", "Casti wireless")
        );

        var categories = PopulateCategoryDataTableFromSheets.toCategoryInfos(rows);

        assertEquals(1, categories.size());
        var info = categories.getFirst();
        assertEquals("Casti wireless", info.subcategoryCountry());
        assertNull(info.value(CategoryColumn.BIG_CATEGORY));
        assertEquals(SOURCE_COLUMN_COUNT, info.sourceValues().size());
    }

    @Test
    void toCategoryInfosRejectsInvalidIntegerIds() {
        var rows = new ArrayList<List<String>>();
        rows.add(emptyRow());
        rows.add(emptyRow());

        var category = emptyRow();
        set(category, CategoryColumn.SUBCATEGORY_COUNTRY, "Sonerii electrice");
        set(category, CategoryColumn.CATEGORY_ID, "not an id");
        rows.add(category);

        assertThrows(IllegalArgumentException.class, () -> PopulateCategoryDataTableFromSheets.toCategoryInfos(rows));
    }

    private static ArrayList<String> emptyRow() {
        return new ArrayList<>(Collections.nCopies(SOURCE_COLUMN_COUNT, ""));
    }

    private static void set(ArrayList<String> row, CategoryColumn column, String value) {
        row.set(column.sheetIndex() - 1, value);
    }
}
