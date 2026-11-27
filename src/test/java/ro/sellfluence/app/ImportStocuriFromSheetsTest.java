package ro.sellfluence.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImportStocuriFromSheetsTest {
    @Test void defaultIsSourceSAndPreview() {
        var options=ImportStocuriFromSheets.options(new String[0]);
        assertEquals("S",options.group());
        assertEquals(26177680,options.gid());
        assertFalse(options.apply());
    }

    @Test void anotherSourceAndGroupAreExplicitlyPaired() {
        var options=ImportStocuriFromSheets.options(new String[]{"--db=emagTest","--group=Z",
                "--spreadsheet-id=OtherBook","--gid=20","--apply"});
        assertEquals("Z",options.group());
        assertEquals("emagTest",options.database());
        assertTrue(options.apply());
        for (var args:new String[][]{{"--group=Z"},{"--spreadsheet-id=OtherBook"},
                {"--group=Z","--spreadsheet-id="+ImportStocuriFromSheets.DEFAULT_BOOK,"--gid=26177680"},
                {"--apply","--dry-run"},{"--db=emagTest","--db=other"},{"--gid=-1"},{"--appply"}})
            assertThrows(IllegalArgumentException.class,() -> ImportStocuriFromSheets.options(args));
    }
}
