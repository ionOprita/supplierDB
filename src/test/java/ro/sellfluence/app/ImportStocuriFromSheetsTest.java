package ro.sellfluence.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImportStocuriFromSheetsTest {
    @Test void defaultIsSourceSAndPreview() {
        var options=ImportStocuriFromSheets.options(new String[0]);
        assertEquals("S",options.group());
        assertEquals("S. Stocuri", options.spreadsheetName());
        assertFalse(options.apply());
        assertEquals(options, ImportStocuriFromSheets.options(new String[]{"--group=S", "--dry-run"}));
    }

    @Test void anotherSourceAndGroupAreExplicitlyPaired() {
        var options=ImportStocuriFromSheets.options(new String[]{"--db=emagTest","--group=Z",
                "--spreadsheet-name=Z. Stocuri","--apply"});
        assertEquals("Z",options.group());
        assertEquals("emagTest",options.database());
        assertEquals("Z. Stocuri", options.spreadsheetName());
        assertTrue(options.apply());
        for (var args:new String[][]{{"--group=Z"}, {"--spreadsheet-name=Other Book"},
                {"--group=Z","--spreadsheet-name=S. Stocuri"}, {"--group=S","--spreadsheet-name= "},
                {"--apply","--dry-run"},{"--db=emagTest","--db=other"},{"--gid=42"},
                {"--spreadsheet-id=old-file-id"},{"--appply"}})
            assertThrows(IllegalArgumentException.class,() -> ImportStocuriFromSheets.options(args));
    }
}
