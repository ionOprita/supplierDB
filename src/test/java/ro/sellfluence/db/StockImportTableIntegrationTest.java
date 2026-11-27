package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.db.StockImport.Invoice;
import ro.sellfluence.db.StockImport.Source;
import ro.sellfluence.db.versions.EmagMirrorDBVersion55;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** All schema and data writes are rolled back in an isolated, disposable test transaction. */
class StockImportTableIntegrationTest {
    private static Document doc(String reference, String title) {
        return new Document(null,0,Map.of("reference",reference,"title",title),List.of(),List.of(),List.of(),
                List.of(new Invoice("purchase","Invoice "+reference)));
    }
    private static Source src(String reference, int column, String fingerprint) {
        return new Source(reference,column,fingerprint,doc(reference,"From sheet "+fingerprint));
    }

    @Test
    void sourceRerunsEmployeeEditsIsolationAndBatchCollision() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank());
        var props = new Properties();
        if (System.getenv("ADS_TEST_DB_USER") != null) props.setProperty("user",System.getenv("ADS_TEST_DB_USER"));
        if (System.getenv("ADS_TEST_DB_PASSWORD") != null) props.setProperty("password",System.getenv("ADS_TEST_DB_PASSWORD"));
        try (var db = DriverManager.getConnection(url,props)) {
            db.setAutoCommit(false);
            try (var s = db.createStatement()) {
                String schema = "importuri_test_"+UUID.randomUUID().toString().replace("-","");
                s.execute("CREATE SCHEMA "+schema);
                s.execute("SET LOCAL search_path TO "+schema);
                s.execute("CREATE TABLE vendor (vendor_group TEXT, company_name TEXT)");
                s.execute("INSERT INTO vendor VALUES ('S','Firm S'),('Z','Firm Z')");
            }
            EmagMirrorDBVersion55.version55(db);
            var input = List.of(src("49A",13,"v1"));
            var first = StockImportTable.reconcile(db,"S","wb",1,input,false);
            assertEquals(1,first.inserted());
            assertTrue(StockImportTable.list(db,"S").isEmpty());
            assertEquals(first,StockImportTable.reconcile(db,"S","wb",1,input,true));
            var id = StockImportTable.list(db,"S").getFirst().id();
            assertEquals(1, StockImportTable.reconcile(db,"S","wb",1,input,true).unchanged());
            var saved = StockImportTable.get(db,"S",id).document();
            assertEquals(1,saved.revision());
            assertEquals(1,saved.invoices().size());
            assertThrows(StockImportTable.NotFound.class,() -> StockImportTable.get(db,"Z",id));
            assertThrows(StockImportTable.NotFound.class,() -> StockImportTable.save(db,"Z",saved));
            var edit = doc("49A","manual edit").version(id,1);
            assertEquals(2,StockImportTable.save(db,"S",edit).document().revision());
            assertThrows(StockImportTable.Conflict.class,() -> StockImportTable.save(db,"S",edit));
            var replacement = StockImportTable.reconcile(db,"S","wb",1,input,false);
            assertEquals(1, replacement.updated());
            assertTrue(replacement.changes().getFirst().replacesLocalEdit());
            assertEquals(replacement, StockImportTable.reconcile(db,"S","wb",1,input,true));
            assertEquals("From sheet v1",StockImportTable.get(db,"S",id).document().fields().get("title"));
            assertEquals(1,StockImportTable.reconcile(db,"S","wb",1,List.of(src("49A",27,"v2")),true).updated());
            StockImportTable.save(db,"S",doc("manual","entered by employee"));
            var collides = List.of(src("49A",27,"v3"),src("manual",42,"v1"));
            var conflict = StockImportTable.reconcile(db,"S","wb",1,collides,true);
            assertFalse(conflict.conflicts().isEmpty());
            assertEquals("From sheet v2",StockImportTable.get(db,"S",id).document().fields().get("title"));
            assertEquals(List.of("49A"), StockImportTable.reconcile(db,"S","wb",1,List.of(),false).missing());
            var other = StockImportTable.save(db,"Z",doc("49A","Z group"));
            assertNotEquals(other.document().id(),id);
            db.rollback();
        }
    }
}
