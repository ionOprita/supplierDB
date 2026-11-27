package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.db.StockImport.Line;
import ro.sellfluence.db.StockImport.Transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StockImportCostsTest {
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static void close(String expected, BigDecimal actual) {
        assertNotNull(actual);
        var decimal = n(expected);
        assertEquals(decimal, actual.setScale(decimal.scale(), RoundingMode.HALF_UP));
    }
    private static Transfer payment(String kind, String ron, String usd) {
        return new Transfer(kind, null, null, ron == null ? null : n(ron), usd == null ? null : n(usd), null);
    }
    private static Document document(Map<String,String> fields, List<Line> lines, Transfer... transfers) {
        return new Document(null, 0, fields, lines, List.of(transfers), List.of(), List.of());
    }
    private static Line line(String quantity, String usd, String kg, String m3, String dutyRate) {
        return new Line(null,"Model",null,n(quantity),n(usd),BigDecimal.ONE,n(kg),n(m3),n(dutyRate));
    }

    @Test
    void wholeShipmentControlsFromImport18() {
        var d = document(Map.of("method","AIR","shipDate","2025-12-28","vatRate","0.21"),
                List.of(line("70","20","3","0.01","0.05")),
                payment("purchase","6300","1400"),payment("freight","12016.89",null),payment("duty","1762",null));
        var calc = StockImportCosts.calculate(d);
        assertEquals("kg",calc.freightBasis());
        assertEquals("2026-01-09",calc.eta());
        close("171.66985714",calc.lines().getFirst().get("freightUnitRon"));
        close("25.17142857",calc.lines().getFirst().get("dutyUnitRon"));
        close("1762",calc.totals().get("dutyRon"));
    }

    @Test
    void estimatedDutyControlFromImport49AAndWeightedPurchaseRate() {
        var d = document(Map.of("method","AIR"), List.of(line("4","13","2",".002",".022")),
                payment("purchase","45.6","10"), payment("purchase","191.488","42"), payment("freight","73.35",null));
        var calc = StockImportCosts.calculate(d);
        close("59.272",calc.lines().getFirst().get("purchaseUnitRon"));
        close("18.3375",calc.lines().getFirst().get("freightUnitRon"));
        close("1.707409",calc.lines().getFirst().get("dutyUnitRon"));
    }

    @Test
    void volumeAllocationAndActualZeroDutyForFreeSamples() {
        var d = document(Map.of("method","SEA","vatRate",".21"),List.of(
                line("2","20","2",".01",".2"),line("1","0","2",".02",".2")),
                payment("purchase","250","50"),payment("freight","40","8"),payment("duty","0","0"));
        var calc = StockImportCosts.calculate(d);
        assertEquals("m3",calc.freightBasis());
        close("10",calc.lines().getFirst().get("freightUnitRon"));
        close("20",calc.lines().getLast().get("freightUnitRon"));
        close("0",calc.lines().getLast().get("dutyUnitRon"));
        close("-10",calc.totals().get("purchaseBalanceUsd"));
        close("-50",calc.totals().get("purchaseBalanceRon"));
        close("0",calc.totals().get("dutyPaidRon"));
        close("50.40",calc.totals().get("vatRon"));
    }

    @Test
    void freightInvoicesAndPaymentsCanDifferInBothCurrencies() {
        var inputs = Map.of("method","SEA", "freightRon","510","freightUsd","100");
        var calc = StockImportCosts.calculate(document(inputs, List.of(), payment("freight","300","60")));
        close("300", calc.totals().get("freightPaidRon"));
        close("60", calc.totals().get("freightPaidUsd"));
        close("210", calc.totals().get("freightBalanceRon"));
        close("40", calc.totals().get("freightBalanceUsd"));
        var settled = StockImportCosts.calculate(document(Map.of(), List.of(), payment("freight","300","60")));
        close("0", settled.totals().get("freightBalanceRon"));
        close("0", settled.totals().get("freightBalanceUsd"));
        assertFalse(settled.totals().containsKey("vatPaidRon"));
        assertFalse(settled.totals().containsKey("dutyPaidRon"));
    }

    @Test
    void workbookAllocatesFreightAcrossMeasuredLinesAndKeepsSignedDiscounts() {
        var d = document(Map.of("method","SEA","costConvention","sheet"), List.of(
                line("10","20","2",".01",".05"),
                new Line("DISC","Discount",null,n("-1"),n("2"),null,null,null,null)),
                payment("purchase","990","198"),payment("freight","110","22"),payment("duty","11",null));
        var calc = StockImportCosts.calculate(d);
        close("198",calc.totals().get("purchaseUsd"));
        close("11",calc.lines().getFirst().get("freightUnitRon"));
        assertNull(calc.lines().getLast().get("freightUnitRon"));
        close("0.10",calc.lines().getLast().get("dutyUnitRon"));
        var unpaid = StockImportCosts.calculate(document(Map.of("method","SEA","costConvention","sheet"),
                List.of(line("8","10","2",".01",".05"))));
        close("0",unpaid.lines().getFirst().get("purchaseUnitRon"));
        close("0",unpaid.lines().getFirst().get("freightUnitRon"));
        assertFalse(unpaid.totals().containsKey("weightedPurchaseRate"));
        assertFalse(unpaid.warnings().isEmpty());
    }

    @Test
    void snapshotStoresModeDefaultsAndPreservesExplicitSettings() {
        var d = document(Map.of("method", "AIR", "transitDays", "14"), List.of());
        var snapshot = StockImportCosts.snapshot(d);
        assertEquals("200", snapshot.fields().get("densityThreshold"));
        assertEquals("14", snapshot.fields().get("transitDays"));
        assertNull(snapshot.fields().get("vatRate"));
        assertEquals(StockImportCosts.calculate(d), StockImportCosts.calculate(snapshot));
    }

    @Test
    void missingDependenciesDoNotBecomeZeroesOrInvalidDates() {
        var d = document(Map.of("method","AIR"),List.of(new Line(null,"Sample",null,n("2"),null,null,null,null,null)));
        var calc = StockImportCosts.calculate(d);
        assertNull(calc.eta());
        assertNull(calc.freightBasis());
        assertFalse(calc.totals().containsKey("totalRon"));
        assertFalse(calc.lines().getFirst().containsKey("landedUnitRon"));
        assertFalse(calc.warnings().isEmpty());
    }
}
