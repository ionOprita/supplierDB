package ro.sellfluence.apphelper;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.googleapi.StockImportSheetReader.Range;
import ro.sellfluence.googleapi.StockImportSheetReader.Snapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.*;

class StockImportSheetParserTest {
    private final List<List<Object>> labels = new ArrayList<>();
    private final List<List<Object>> blocks = new ArrayList<>();
    private final List<List<Object>> products = new ArrayList<>();
    private final List<List<Object>> cartons = new ArrayList<>();
    private final List<List<Object>> transit = new ArrayList<>();

    StockImportSheetParserTest() {
        Map.ofEntries(entry(2,"Nr/ Import"),entry(8,"Data plasare comanda"),entry(14,"Firma pe care vindem"),
                entry(24,"Batch number"),entry(34,"Numar factura achizitie"),entry(40,"Transfer achizitie 1"),
                entry(47,"Transfer transport 1"),entry(52,"Transfer taxe vamale"),entry(53,"Transfer TVA"),
                entry(63,"Cod TARIC 6"),entry(65,"Numar NIR"),entry(88,"Observatii"))
                .forEach((row, label) -> set(labels,row,2,label));
        set(products,3,5,"PNK eMAG");
        set(products,3,10,"Taxa import");
        set(products,4,5,"ABC");
        set(products,4,10,new BigDecimal("0.025"));
        set(cartons,4,4,"PNK");
        set(cartons,5,4,"ABC");
        set(transit,1,1,"Metoda transport");
        int i = 0;
        for (String method : List.of("AIR","RAIL","SEA","TRUCK")) {
            int col = 7 + i * 6;
            set(cartons,2,col,method);
            set(cartons,4,col,"Transport (pcs./ctn.)");
            set(cartons,4,col+5,"Transport (m³/ctn.)");
            set(transit,i+2,1,method);
            set(transit,i+2,2, List.of(12,42,90,25).get(i++));
        }
        set(cartons,3,19,1000);
        set(cartons,5,19,20);
        set(cartons,5,20,new BigDecimal("13.5"));
        set(cartons,5,24,new BigDecimal("0.04902"));
    }

    private void template(int col) {
        set(blocks,39,col,"Data transferului");
        set(blocks,57,col,"Cod TARIC");
        set(blocks,68,col,"Nume produs");
        set(blocks,68,col+1,"Cant.");
        set(blocks,68,col+8,"PNK");
        set(blocks,68,col+9,"Cod Produs");
        set(blocks,21,col,false);
        set(blocks,44,col+2,0);
        set(blocks,51,col+2,0);
        set(blocks,52,col+6,0);
        set(blocks,85,col,0);
        set(blocks,86,col,"-");
    }

    private static void set(List<List<Object>> grid, int row, int col, Object value) {
        while (grid.size()<row) grid.add(new ArrayList<>());
        var cells=grid.get(row-1);
        while (cells.size()<col) cells.add("");
        cells.set(col-1,value);
    }

    private StockImportSheetParser.Parsed parse() {
        return StockImportSheetParser.parse(new Snapshot(List.of(new Range(1,labels),new Range(7,blocks)),
                products,cartons,transit,List.of(List.of("TVA"),List.of(new BigDecimal("0.21")))));
    }

    @Test void mapsRealInputsPreservingPrecisionIdentifiersAndIndependentTransfers() {
        template(1);
        set(blocks,2,1,"001A");
        set(blocks,8,1,44980);
        set(blocks,14,1,"Sellfluence SRL");
        set(blocks,16,1,"SEA");
        set(blocks,19,1,"0123 / 0789");
        set(blocks,30,1,true);
        set(blocks,34,1,"0001");
        set(blocks,35,1,new BigDecimal("7010014973"));
        set(blocks,38,1,"F/04");
        set(blocks,40,1,44980);
        set(blocks,40,2,"Wise");
        set(blocks,40,3,new BigDecimal("59301.08"));
        set(blocks,40,4,new BigDecimal("12797.173064"));
        set(blocks,52,3,0);
        set(blocks,53,3,23910);
        set(blocks,53,5,new BigDecimal("4.6046"));
        for (int r : List.of(58,59)) {
            set(blocks,r,1,"0000000123");
            set(blocks,r,2,new BigDecimal("337.5"));
        }
        set(blocks,69,1,"Model A");
        set(blocks,69,2,500);
        set(blocks,69,3,new BigDecimal("29.137125"));
        set(blocks,69,9,"ABC");
        set(blocks,69,10,"000007");
        var parsed = parse();
        var record = parsed.records().getFirst();
        Document doc = record.document();
        assertEquals("001A",record.key());
        assertEquals(7,record.column());
        assertEquals("2023-02-23",doc.fields().get("orderDate"));
        assertEquals("0123 / 0789",doc.fields().get("receiptRequest"));
        assertEquals("false",doc.fields().get("defects"));
        assertEquals("true",doc.fields().get("eTransport"));
        assertEquals("90",doc.fields().get("transitDays"));
        assertEquals("1000",doc.fields().get("densityThreshold"));
        assertEquals("0.21",doc.fields().get("vatRate"));
        assertEquals("sheet",doc.fields().get("costConvention"));
        assertEquals(List.of("0001","7010014973","F/04"),doc.invoices().stream().map(i -> i.reference()).toList());
        assertEquals(3,doc.transfers().size());
        assertEquals(new BigDecimal("12797.173064"),doc.transfers().getFirst().usd());
        assertEquals(BigDecimal.ZERO,doc.transfers().get(1).ron());
        assertNull(doc.transfers().get(1).usd());
        assertEquals(new BigDecimal("4.6046"),doc.transfers().get(2).rate());
        assertEquals(List.of("0000000123","0000000123"),doc.taric().stream().map(t -> t.code()).toList());
        var line=doc.lines().getFirst();
        assertEquals("000007",line.productCode());
        assertEquals(new BigDecimal("29.137125"),line.unitUsd());
        assertEquals(new BigDecimal("13.5"),line.kgPerCarton());
        assertEquals(new BigDecimal("0.04902"),line.m3PerCarton());
        assertEquals(new BigDecimal("0.025"),line.dutyRate());
        assertTrue(parsed.warnings().isEmpty());
        assertEquals(record.fingerprint(),parse().records().getFirst().fingerprint());
        set(blocks,69,2,501);
        assertNotEquals(record.fingerprint(),parse().records().getFirst().fingerprint());
    }

    @Test void importsAnUnfinishedProductAndSkipsUnusedTemplatesWithFormulaOutput() {
        template(1);
        template(12);
        template(23);
        set(blocks,2,12,"12 + 13");
        set(blocks,2,23,"49B");
        set(blocks,70,23,"Product without PNK");
        set(blocks,70,24,0);
        set(blocks,71,32,"#REF!");
        var parsed=parse();
        assertEquals(1,parsed.records().size());
        var record=parsed.records().getFirst();
        assertEquals("49B",record.key());
        assertEquals(29,record.column());
        assertNull(record.document().fields().get("orderDate"));
        assertNull(record.document().fields().get("title"));
        assertNull(record.document().lines().getFirst().pnk());
        assertEquals(BigDecimal.ZERO,record.document().lines().getFirst().quantity());
        assertEquals(2,parsed.warnings().size());
    }

    @Test void ambiguousOrMalformedInputsAbortWithSourceCoordinates() {
        template(1);
        template(12);
        set(blocks,69,1,"Started");
        assertTrue(assertThrows(IllegalArgumentException.class,this::parse).getMessage().contains("Importuri!G2"));
        set(blocks,2,1,"3");
        set(blocks,2,12,3);
        set(blocks,69,12,"Started too");
        assertTrue(assertThrows(IllegalArgumentException.class,this::parse).getMessage().contains("duplicate"));
        set(blocks,2,12,4);
        set(blocks,69,13,"not a number");
        assertTrue(assertThrows(IllegalArgumentException.class,this::parse).getMessage().contains("Importuri!S69"));
        set(blocks,69,13,5);
        set(blocks,68,12,"renamed");
        assertTrue(assertThrows(IllegalArgumentException.class,this::parse).getMessage().contains("source layout"));
    }

    @Test void preservesFreightAndPurchaseFallbacksWithoutFreezingCalculatedTransferTotals() {
        template(1);
        set(blocks,2,1,"FOB");
        set(blocks,69,1,"Product");
        set(blocks,44,5,new BigDecimal("4.52"));
        set(blocks,51,3,new BigDecimal("1000"));
        set(blocks,51,4,new BigDecimal("200"));
        var fields=parse().records().getFirst().document().fields();
        assertEquals("4.52",fields.get("purchaseRate"));
        assertEquals("1000",fields.get("freightRon"));
        assertEquals("200",fields.get("freightUsd"));
        set(blocks,40,3,452);
        set(blocks,40,4,100);
        set(blocks,47,3,1000);
        set(blocks,47,4,200);
        fields=parse().records().getFirst().document().fields();
        assertFalse(fields.containsKey("purchaseRate"));
        assertFalse(fields.containsKey("freightRon"));
        assertFalse(fields.containsKey("freightUsd"));
    }
}
