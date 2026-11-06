package ro.sellfluence.apphelper;

import org.junit.jupiter.api.Test;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Grid;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.ProductIdentity;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Reader;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductPerformanceHistoryImporterTest {
    private static final LocalDate DATE = LocalDate.of(2026,5,1);
    private static final UUID VENDOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final List<ProductIdentity> PRODUCTS = List.of(new ProductIdentity(VENDOR,"judios","JCS-1","DP1"));

    @Test void preservesSnapshotPrimitivesZeroesAndFamilyDatesWithoutInventingFutureActivity() {
        var fixture=new Fixture();
        var result=fixture.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        assertEquals(4,result.observations().size());
        var organic=result.observations().stream().filter(o->o.domain().equals("organic")).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO,organic.values().get("stock"));
        assertEquals(new BigDecimal("199.99"),organic.values().get("salesPrice"));
        assertEquals(new BigDecimal("5"),organic.values().get("clicks"));
        assertEquals("2026-04-02",organic.values().get("sourceStartDate"));
        assertEquals("2026-05-01",organic.values().get("sourceEndDate"));
        var ads=result.observations().stream().filter(o->o.domain().equals("ads")).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO,ads.values().get("exact_sales"));
        assertEquals(BigDecimal.ZERO,ads.values().get("exact_units"));
        assertNull(ads.values().get("auto_sales"));
        var reviews=result.observations().stream().filter(o->o.domain().equals("reviews")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2023,12,29),reviews.observationDate());
        assertEquals(0,((BigDecimal)reviews.values().get("reviews")).compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("123"),reviews.values().get("familyId"));
        assertTrue(result.sourcePayloadJson().contains("\"FORMULA\""));
        assertTrue(result.sourcePayloadJson().contains("7 Day Total Sales"));
        assertEquals(result.contentHash(),fixture.run(PRODUCTS).contentHash());
    }

    @Test void blankIsNotZeroWhenRawInputIsMissingOrItsThirtyDayWindowIsIncomplete() {
        var fixture=new Fixture();
        fixture.set("raw","Introd. Date",4,18,null); // Sales missing, other values numeric.
        var result=fixture.run(PRODUCTS);
        var ads=result.observations().stream().filter(o->o.domain().equals("ads")).findFirst().orElseThrow();
        assertNull(ads.values().get("exact_sales"));
        assertEquals(BigDecimal.ZERO,ads.values().get("exact_units"));
        fixture.set("raw","Introd. Date",4,3,serial(DATE.minusDays(28)));
        ads=fixture.run(PRODUCTS).observations().stream().filter(o->o.domain().equals("ads")).findFirst().orElseThrow();
        assertNull(ads.values().get("exact_units"));
    }

    @Test void restoresZeroAcrossMonthBoundaryPartitionsWithoutSummingOtherObservationDates() {
        var fixture=new Fixture();
        fixture.set("raw","Introd. Date",4,4,serial(DATE.minusDays(1)));
        fixture.set("raw","Introd. Date",5,2,serial(DATE));
        fixture.set("raw","Introd. Date",5,3,serial(DATE));
        fixture.set("raw","Introd. Date",5,4,serial(DATE));
        fixture.set("raw","Introd. Date",5,6,"Campaign DP1");
        fixture.set("raw","Introd. Date",5,10,"exact");
        for(int column:new int[]{11,12,15,18,19,20})fixture.set("raw","Introd. Date",5,column,0);
        var ads=fixture.run(PRODUCTS).observations().stream().filter(o->o.domain().equals("ads")).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO,ads.values().get("exact_sales"));
    }

    @Test void exactPnkCodeAndAccountAreRequiredEvenForRetiredOrOutOfStockProducts() {
        var fixture=new Fixture();
        assertEquals(4,fixture.run(PRODUCTS).observations().size()); // Retired=true and stock=0.
        var wrongAccount=List.of(new ProductIdentity(VENDOR,"judios-main","JCS-1","DP1"));
        assertTrue(fixture.run(wrongAccount).observations().isEmpty());
        var wrongCode=List.of(new ProductIdentity(VENDOR,"judios","OTHER","DP1"));
        var result=fixture.run(wrongCode);
        assertTrue(result.observations().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("unresolved_identity")));
        fixture.set("report","Date Prod.",5,3,"Other name");
        fixture.set("report","Date Prod.",5,4,"Judios Concept SRL");
        fixture.set("report","Date Prod.",5,5,"DP1");
        fixture.set("report","Date Prod.",5,8,"OTHER");
        assertEquals(4,fixture.run(PRODUCTS).observations().size()); // Exact source name selects original catalogue row.
    }

    @Test void sourceFormulaErrorsRemainAuditableAndCannotProduceACompleteRun() {
        var fixture=new Fixture();
        fixture.set("report","Date Organic/W.",4,9,"#REF!");
        var result=fixture.run(PRODUCTS);
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("source_formula_error")));
        assertTrue(result.sourcePayloadJson().contains("#REF!"));
        var organic=result.observations().stream().filter(o->o.domain().equals("organic")).findFirst().orElseThrow();
        assertNull(organic.values().get("clicks"));
    }

    @Test void exactCodeFallbackIsAllowedOnlyForAbsentDatabasePnkAndKeepsSourcePnk() {
        var fixture=new Fixture();
        var result=fixture.run(List.of(new ProductIdentity(VENDOR,"judios","JCS-1",null)));
        assertEquals(4,result.observations().size());
        assertTrue(result.observations().stream().allMatch(o->o.pnk().equals("DP1")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("exact_code_fallback")));
        assertTrue(fixture.run(List.of(new ProductIdentity(VENDOR,"judios","JCS-1","OTHER"))).observations().isEmpty());
    }

    @Test void missingRawSourceRetainsMatrixButMarksRunIncomplete() {
        var fixture=new Fixture();
        fixture.data.remove("raw");
        var result=fixture.run(PRODUCTS);
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("raw_ads_validation_failed")));
        assertTrue(result.observations().stream().anyMatch(o->o.domain().equals("ads")));
    }

    @Test void campaignSubstringCannotProveZeroForAnotherProduct() {
        var fixture=new Fixture();
        fixture.set("raw","Introd. Date",4,6,"Campaign XDP1X");
        var ads=fixture.run(PRODUCTS).observations().stream().filter(o->o.domain().equals("ads")).findFirst().orElseThrow();
        assertNull(ads.values().get("exact_sales"));
    }

    @Test void duplicateSourceRowsAreRejectedInsteadOfMultiplyingMetrics() {
        var fixture=new Fixture();
        var rows=fixture.data.get("report").get("Date Organic/W.");
        rows.set(4,new ArrayList<>(rows.get(3)));
        var result=fixture.run(PRODUCTS);
        assertFalse(result.complete());
        assertTrue(result.observations().stream().noneMatch(o->o.domain().equals("organic")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("duplicate_observation")));
    }

    @Test void retainsExplicitlyClearedRatingWithItsDateAndSourceIdentity() {
        var fixture=new Fixture();
        var original=fixture.run(PRODUCTS);
        fixture.set("report","Rating Fam./W.",4,6,null);
        var result=fixture.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        var rating=result.observations().stream().filter(o->o.domain().equals("rating")).findFirst().orElseThrow();
        assertTrue(rating.values().containsKey("rating"));
        assertNull(rating.values().get("rating"));
        assertEquals(new BigDecimal("123"),rating.values().get("familyId"));
        assertEquals(LocalDate.of(2023,12,29),rating.observationDate());
        assertEquals("F4:F4",rating.sourceRange());
        assertEquals("report",rating.sourceSpreadsheetId());
        assertNotEquals(original.contentHash(),result.contentHash());
        assertEquals(result.contentHash(),fixture.run(PRODUCTS).contentHash());
        assertTrue(result.sourcePayloadJson().contains("product-performance-history-v2"));
    }

    @Test void retainsEntirelyClearedOrganicAndAdsSnapshotsWithoutInventingZeroes() {
        var fixture=new Fixture();
        for(int column=7;column<=12;column++)fixture.set("report","Date Organic/W.",4,column,null);
        for(int column=7;column<=30;column++)fixture.set("report","eMAG ADS/W.",5,column,null);
        // Matching raw values are nonzero, so blank matrix cells have no proof of zero.
        for(int column:new int[]{11,12,15,18,19,20})fixture.set("raw","Introd. Date",4,column,1);
        var result=fixture.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        var organic=result.observations().stream().filter(o->o.domain().equals("organic")).findFirst().orElseThrow();
        assertEquals(DATE,organic.observationDate());
        for(String metric:List.of("stock","gmv","clicks","salesPrice","sourceConversion","performance")) {
            assertTrue(organic.values().containsKey(metric),metric);
            assertNull(organic.values().get(metric),metric);
        }
        assertEquals("2026-04-02",organic.values().get("sourceStartDate"));
        var ads=result.observations().stream().filter(o->o.domain().equals("ads")).findFirst().orElseThrow();
        for(String category:List.of("auto","broad","exact","product")) {
            for(String metric:List.of("impressions","clicks","spend","sales","units","salesCount")) {
                String key=category+"_"+metric;
                assertTrue(ads.values().containsKey(key),key);
                assertNull(ads.values().get(key),key);
            }
        }
        assertFalse(ads.values().containsKey("zeroRestoredMetrics"));
    }

    @Test void retainsAnIndividualClearedMetricAlongsideTheOtherObservedValues() {
        var fixture=new Fixture();
        fixture.set("report","Date Organic/W.",4,9,null);
        var result=fixture.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        var organic=result.observations().stream().filter(o->o.domain().equals("organic")).findFirst().orElseThrow();
        assertTrue(organic.values().containsKey("clicks"));
        assertNull(organic.values().get("clicks"));
        assertEquals(BigDecimal.ZERO,organic.values().get("stock"));
        assertEquals(new BigDecimal("199.99"),organic.values().get("salesPrice"));
    }

    @Test void missingRowsAndUnresolvedIdentitiesDoNotEmitWithdrawals() {
        var removed=new Fixture();
        for(int column=1;column<=18;column++)removed.set("report","Date Organic/W.",4,column,null);
        var result=removed.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        assertTrue(result.observations().stream().noneMatch(o->o.domain().equals("organic")));
        assertEquals(3,result.observations().size());

        var unresolved=new Fixture();
        unresolved.set("report","Date Organic/W.",4,2,"Unverified source product");
        result=unresolved.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        assertTrue(result.observations().stream().noneMatch(o->o.domain().equals("organic")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("unresolved_source_row")));
    }

    @Test void explicitBlanksStayWithinTheSelectedSourceAndCutoff() {
        var fixture=new Fixture();
        for(int column=7;column<=18;column++)fixture.set("report","Date Organic/W.",4,column,null);
        var result=fixture.run(PRODUCTS);
        assertTrue(result.complete(),result.diagnostics().toString());
        assertTrue(result.observations().stream().allMatch(o->o.sourceSpreadsheetId().equals("report")));
        assertTrue(result.observations().stream().noneMatch(o->o.observationDate().isAfter(DATE)));
        assertEquals(1,result.observations().stream().filter(o->o.domain().equals("organic")).count());
        var later=new ProductPerformanceHistoryImporter(fixture,PRODUCTS,DATE.plusDays(7))
                .read(List.of(new Source("test","report")));
        assertEquals(2,later.observations().stream().filter(o->o.domain().equals("organic")).count());
        assertTrue(later.observations().stream().filter(o->o.domain().equals("organic"))
                .allMatch(o->o.values().get("clicks")==null));
    }

    private static long serial(LocalDate date){return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899,12,30),date);}
    private static final class Fixture implements Reader {
        final Map<String,Map<String,List<List<Object>>>> data=new HashMap<>();
        Fixture(){
            table("report","Date Prod.",8,9);
            setRow("report","Date Prod.",3,3,null,"Firma de pe care vindem pe eMAG","PNK",null,null,"Cod produs",null);
            setRow("report","Date Prod.",4,3,"J. 1 - Product","Judios Concept SRL","DP1",null,true,"JCS-1",null);
            table("report","Date Organic/W.",8,18);
            setRow("report","Date Organic/W.",3,2,"Nume produs","Firma","PNK",null,null,
                    "Stock","GMV 30","Clicks","Sales Price","Conv.","Perform.",
                    "Stock","GMV 30","Clicks","Sales Price","Conv.","Perform.");
            for(int c=7;c<=12;c++)set("report","Date Organic/W.",2,c,serial(DATE));
            for(int c=13;c<=18;c++)set("report","Date Organic/W.",2,c,serial(DATE.plusDays(7)));
            setRow("report","Date Organic/W.",4,2,"J. 1 - Product","Judios Concept SRL","DP1",null,true,0,100,5,199.99,0.1,"SUPER HOT",7,5,4,3,2,"future");
            table("report","eMAG ADS/W.",8,30);
            setRow("report","eMAG ADS/W.",4,2,"Nume produs","Firma","PNK",null,null);
            int c=7;
            for(String group:List.of("Auto","Broad","Exact","Product"))for(String metric:List.of("Imp.","Clicks","Spend","Sales","Total Units Sold","Total Orders")){
                set("report","eMAG ADS/W.",2,c,serial(DATE));set("report","eMAG ADS/W.",3,c,group);set("report","eMAG ADS/W.",4,c++,metric);
            }
            setRow("report","eMAG ADS/W.",5,2,"J. 1 - Product","Judios Concept SRL","DP1",null,true);
            setRow("report","eMAG ADS/W.",5,19,241,3,1.75,null,null,null);
            for(String tab:List.of("Nr. Rew. Fam./W.","Rating Fam./W.")){
                table("report",tab,8,7);setRow("report",tab,3,2,"Nume produs","PNK","Am retras produsul?","Familie de produse");
                set("report",tab,2,6,serial(LocalDate.of(2023,12,29)));
                setRow("report",tab,4,2,"J. 1 - Product","DP1",true,123,tab.startsWith("Nr.")?0:4.5);
            }
            table("raw","Introd. Date",8,25);
            setRow("raw","Introd. Date",3,2,"Data","Start date","End date","Campaign ID","Campaign name","Adset ID","Adset name","Targeting","Match type","Impressions","Clicks","CTR","CPC","Spent","ACOS","ROAS","7 Day Total Sales","7 Day Total Orders","7 Day Total Units");
            setRow("raw","Introd. Date",4,2,serial(DATE),serial(DATE.minusDays(29)),serial(DATE),1,"Campaign DP1",2,"DP1","keywords","exact",241,3,0,0,1.75,0,0,0,0,0);
        }
        void table(String id,String tab,int rows,int cols){
            var table=new ArrayList<List<Object>>();for(int r=0;r<rows;r++)table.add(new ArrayList<>(java.util.Collections.nCopies(cols,null)));
            data.computeIfAbsent(id,_ -> new HashMap<>()).put(tab,table);
        }
        void set(String id,String tab,int row,int col,Object value){data.get(id).get(tab).get(row-1).set(col-1,value);}
        void setRow(String id,String tab,int row,int col,Object...values){for(int i=0;i<values.length;i++)set(id,tab,row,col+i,values[i]);}
        ProductPerformanceHistoryImporter.Result run(List<ProductIdentity> products){
            return new ProductPerformanceHistoryImporter(this,products,DATE).read(List.of(new Source("test","report")));
        }
        @Override public List<Grid> metadata(String id){
            return data.get(id).entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e->new Grid(Math.abs(e.getKey().hashCode()),e.getKey(),e.getValue().size(),e.getValue().getFirst().size())).toList();
        }
        @Override public List<List<Object>> read(String id,String tab,int r1,int r2,int c1,int c2,boolean formulas){
            if(formulas)return List.of(List.of("=IMPORTRANGE(\"https://docs.google.com/spreadsheets/d/raw/edit\",\"eMAG ADS/W.!A1:ZZ1000\")"));
            var rows=data.get(id).get(tab);var result=new ArrayList<List<Object>>();
            for(int r=r1-1;r<r2;r++)result.add(new ArrayList<>(rows.get(r).subList(c1-1,c2)));
            return result;
        }
    }
}
