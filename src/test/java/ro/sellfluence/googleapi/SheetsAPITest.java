package ro.sellfluence.googleapi;

import com.google.api.services.sheets.v4.model.CellData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SheetsAPITest {
    @Test
    void replacementRequestsKeepProtectedEdgesAndWriteLiteralValues() {
        var clear = SheetsAPI.clearValues(42, 2, null, 1, null).getRepeatCell();
        assertEquals("userEnteredValue", clear.getFields());
        assertEquals(2, clear.getRange().getStartRowIndex());
        assertEquals(1, clear.getRange().getStartColumnIndex());
        assertNull(clear.getRange().getEndRowIndex());
        assertNull(clear.getRange().getEndColumnIndex());
        assertNull(clear.getCell().getUserEnteredFormat());

        var rows = List.of(
                List.of(SheetsAPI.toCellData("=1+1"), SheetsAPI.toCellData(45292)),
                List.of(SheetsAPI.toCellData("second"))
        );
        var write = SheetsAPI.writeValues(42, 2, 1, rows, 0, 2, 2).getUpdateCells();
        assertEquals("userEnteredValue", write.getFields());
        assertEquals(2, write.getStart().getRowIndex());
        assertEquals(1, write.getStart().getColumnIndex());
        assertEquals("=1+1", write.getRows().getFirst().getValues().getFirst().getUserEnteredValue().getStringValue());
        assertNull(write.getRows().get(1).getValues().get(1).getUserEnteredValue());

        var date = SheetsAPI.dateFormats(42, 2, 2, Map.of(3, "dd/MM/yyyy")).getFirst().getRepeatCell();
        assertEquals("userEnteredFormat.numberFormat", date.getFields());
        assertEquals(2, date.getRange().getStartRowIndex());
        assertEquals(4, date.getRange().getEndRowIndex());
        assertEquals(2, date.getRange().getStartColumnIndex());
        assertEquals(3, date.getRange().getEndColumnIndex());
        assertEquals("DATE", date.getCell().getUserEnteredFormat().getNumberFormat().getType());
        assertEquals("dd/MM/yyyy", date.getCell().getUserEnteredFormat().getNumberFormat().getPattern());
        assertNull(date.getCell().getUserEnteredValue());
    }

    @Test
    void emptyCellsClearOldValuesWithoutChangingFormatting() {
        List<List<CellData>> rows = List.of(List.of(new CellData()));

        var write = SheetsAPI.writeValues(42, 2, 1, rows, 0, 1, 1).getUpdateCells();

        assertEquals("userEnteredValue", write.getFields());
        assertNull(write.getRows().getFirst().getValues().getFirst().getUserEnteredValue());
        assertNull(write.getRows().getFirst().getValues().getFirst().getUserEnteredFormat());
    }
}
