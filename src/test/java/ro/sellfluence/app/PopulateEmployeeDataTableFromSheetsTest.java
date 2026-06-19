package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.EmployeeDataTable.EmployeeColumn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static ro.sellfluence.db.EmployeeDataTable.SOURCE_COLUMN_COUNT;

class PopulateEmployeeDataTableFromSheetsTest {
    @Test
    void toEmployeeInfosMapsRowsAndPreservesSourceValues() {
        var rows = new ArrayList<List<String>>();
        rows.add(emptyRow());
        rows.add(emptyRow());

        var employee = emptyRow();
        set(employee, EmployeeColumn.SHEET_INDEX, "1");
        set(employee, EmployeeColumn.FULL_NAME, " Blaga Lucian Daniel\r");
        set(employee, EmployeeColumn.COMPANY, "Koppel SRL");
        set(employee, EmployeeColumn.DEPARTMENT, "1. Conducere");
        set(employee, EmployeeColumn.BIRTHDAY, "25 Nov 1994");
        set(employee, EmployeeColumn.WORKING_DOCUMENT_LINK, "https://example.com/report");
        employee.set(119, "Blaga Lucian Daniel");
        rows.add(employee);

        var emptyEmployeeSlot = emptyRow();
        set(emptyEmployeeSlot, EmployeeColumn.SHEET_INDEX, "27");
        set(emptyEmployeeSlot, EmployeeColumn.FULL_NAME, " \r");
        rows.add(emptyEmployeeSlot);

        var employees = PopulateEmployeeDataTableFromSheets.toEmployeeInfos(rows);

        assertEquals(1, employees.size());
        var info = employees.getFirst();
        assertEquals(3, info.sourceRowNumber());
        assertEquals("Blaga Lucian Daniel", info.fullName());
        assertEquals("Koppel SRL", info.value(EmployeeColumn.COMPANY));
        assertEquals("1. Conducere", info.value(EmployeeColumn.DEPARTMENT));
        assertEquals("25 Nov 1994", info.value(EmployeeColumn.BIRTHDAY));
        assertEquals("https://example.com/report", info.value(EmployeeColumn.WORKING_DOCUMENT_LINK));
        assertEquals("Blaga Lucian Daniel", info.sourceValues().get(119));
        assertEquals(SOURCE_COLUMN_COUNT, info.sourceValues().size());
    }

    @Test
    void toEmployeeInfosPadsShortRows() {
        var rows = List.of(
                List.<String>of(),
                List.<String>of(),
                List.of("1", "Ana Popescu")
        );

        var employees = PopulateEmployeeDataTableFromSheets.toEmployeeInfos(rows);

        assertEquals(1, employees.size());
        var info = employees.getFirst();
        assertEquals("Ana Popescu", info.fullName());
        assertNull(info.value(EmployeeColumn.COMPANY));
        assertEquals(SOURCE_COLUMN_COUNT, info.sourceValues().size());
    }

    private static ArrayList<String> emptyRow() {
        return new ArrayList<>(Collections.nCopies(SOURCE_COLUMN_COUNT, ""));
    }

    private static void set(ArrayList<String> row, EmployeeColumn column, String value) {
        row.set(column.sheetIndex() - 1, value);
    }
}
