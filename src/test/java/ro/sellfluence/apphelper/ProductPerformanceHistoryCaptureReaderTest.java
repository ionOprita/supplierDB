package ro.sellfluence.apphelper;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Capture;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Grid;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductPerformanceHistoryCaptureReaderTest {
    @TempDir Path directory;

    @Test void joinsBoundedRectanglesAndPreservesTypedZeroMissingCellsAndDates() throws Exception {
        var reader=reader(List.of(
                capture("A1:B2",List.of(Arrays.asList(0,46143),List.of(199.99))),
                capture("C1:D2",List.of(List.of("",true)))));
        var rows=reader.read("id","tab",1,2,1,4,false);
        assertEquals(BigDecimal.ZERO,rows.getFirst().getFirst());
        assertEquals(new BigDecimal("46143"),rows.getFirst().get(1));
        assertEquals("",rows.getFirst().get(2));
        assertEquals(true,rows.getFirst().get(3));
        assertEquals(new BigDecimal("199.99"),rows.get(1).getFirst());
        assertNull(rows.get(1).get(1));
        assertNull(rows.get(1).get(3));
    }

    @Test void capturedBlankIsKnownButUncapturedCellCannotBecomeBlank() throws Exception {
        var reader=reader(List.of(capture("A1:B2",List.of())));
        assertNull(reader.read("id","tab",1,2,1,2,false).getFirst().getFirst());
        assertThrows(IllegalArgumentException.class,()->reader.read("id","tab",1,2,1,3,false));
        assertThrows(IllegalArgumentException.class,()->reader.read("id","tab",1,1,1,1,true));
        assertThrows(IllegalArgumentException.class,()->reader.metadata("missing"));
    }

    @Test void equalOverlapsAreAcceptedButChangedSourceCellsAreRejected() throws Exception {
        var reader=reader(List.of(capture("A1:B1",List.of(List.of(0,2))),
                capture("B1:C1",List.of(List.of(2.0,3)))));
        assertEquals(new BigDecimal("3"),reader.read("id","tab",1,1,1,3,false).getFirst().get(2));
        var conflicting=reader(List.of(capture("A1:B1",List.of(List.of(0,2))),
                capture("B1:C1",List.of(List.of(4,3)))));
        assertThrows(IllegalArgumentException.class,()->conflicting.read("id","tab",1,1,1,3,false));
    }

    @Test void formulasStaySeparateFromEffectiveValues() throws Exception {
        var reader=reader(List.of(capture("A1:A1",List.of(List.of(0))),
                new Capture("id","tab","A1","FORMULA",List.of(List.of("=1-1")))));
        assertEquals(BigDecimal.ZERO,reader.read("id","tab",1,1,1,1,false).getFirst().getFirst());
        assertEquals("=1-1",reader.read("id","tab",1,1,1,1,true).getFirst().getFirst());
    }

    private ProductPerformanceHistoryCaptureReader reader(List<Capture> captures) throws Exception {
        var bundle=new ProductPerformanceHistoryCaptureReader.Bundle(Map.of("id",List.of(new Grid(1,"tab",5,5))),captures);
        Path file=Files.createTempFile(directory,"capture-",".json");
        Files.writeString(file,new GsonBuilder().serializeNulls().create().toJson(bundle));
        return new ProductPerformanceHistoryCaptureReader(List.of(file));
    }
    private static Capture capture(String range,List<List<Object>> values){
        return new Capture("id","tab",range,"UNFORMATTED_VALUE",values);
    }
}
