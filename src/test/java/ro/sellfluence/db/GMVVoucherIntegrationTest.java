package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.emagapi.VoucherSplit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GMVVoucherIntegrationTest {
    private Connection db;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Disposable PostgreSQL required");
        var properties = new Properties();
        if (System.getenv("ADS_TEST_DB_USER") != null) properties.setProperty("user", System.getenv("ADS_TEST_DB_USER"));
        if (System.getenv("ADS_TEST_DB_PASSWORD") != null) properties.setProperty("password", System.getenv("ADS_TEST_DB_PASSWORD"));
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "gmv_voucher_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); db.setSchema(schema);
        sql("CREATE TABLE product (name TEXT, emag_pnk TEXT, product_code TEXT)");
        sql("CREATE TABLE emag_order (id TEXT, surrogate_id INT, status INT, date TIMESTAMP, modified TIMESTAMP)");
        sql("""
                CREATE TABLE product_in_order (id INT, emag_order_surrogate_id INT, part_number_key TEXT,
                    quantity INT, initial_qty INT, storno_qty INT, sale_price NUMERIC, vat TEXT,
                    created TIMESTAMP, modified TIMESTAMP)
                """);
        sql("""
                CREATE TABLE voucher_split (voucher_id INT, product_id INT, emag_order_surrogate_id INT,
                    value NUMERIC, vat_value NUMERIC, vat TEXT, offered_by TEXT, voucher_name TEXT)
                """);
        sql("INSERT INTO product VALUES ('Product', 'PNK', 'CODE')");
        sql("""
                INSERT INTO emag_order VALUES
                  ('ORDER', 1, 4, '2026-01-10', NULL), ('ORDER', 2, 5, '2026-02-10', NULL),
                  ('UNRELATED', 3, 4, '2026-01-10', NULL)
                """);
        sql("""
                INSERT INTO product_in_order VALUES
                  (100, 1, 'PNK', 4, 4, 0, 100, '0', NULL, NULL),
                  (100, 2, 'PNK', 3, 4, 1, 100, '0', NULL, NULL),
                  (101, 1, 'OTHER', 1, 1, 0, 100, '0', NULL, NULL),
                  (100, 3, 'OTHER', 1, 1, 0, 100, '0', NULL, NULL)
                """);
        sql("""
                INSERT INTO voucher_split VALUES
                  (1,100,1,-15,-5,'0','seller','First'), (2,100,1,-20,0,'0','seller','Second'),
                  (3,100,1,-100,0,'0','emag','Platform'), (1,100,2,-25,-5,'0','seller','Remaining'),
                  (4,101,1,-999,0,'0','seller','Other product'), (5,100,3,-999,0,'0','seller','Other snapshot')
                """);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (db != null) try (var ignored = db) {
            db.rollback(); db.setAutoCommit(true); db.setSchema("public");
            sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void loaderSumsSellerSplitsWithoutDuplicatingLinesOrMixingSnapshots() throws Exception {
        var lines = load().get("ORDER");
        assertEquals(2, lines.size());
        assertEquals(new BigDecimal("-40.00"), lines.getFirst().sellerVoucherTotal());
        assertEquals(new BigDecimal("-30.00"), lines.getLast().sellerVoucherTotal());
        assertEquals(new BigDecimal("100.00"), lines.getFirst().price());
        assertEquals(new BigDecimal("360.00"), GMV.computeGMVForPeriod(load(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertEquals(new BigDecimal("-90.00"), GMV.computeGMVForPeriod(load(),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        assertEquals(new BigDecimal("-40.00"), POInfo.getByProductAndMonth(db, "CODE", YearMonth.of(2026, 1))
                .getFirst().sellerVoucherTotal());
    }

    @Test
    void sumsNullableComponentsAndRoundsOnlyAfterSummingTheVoucher() throws Exception {
        sql("INSERT INTO voucher_split VALUES (6,100,1,NULL,-0.015,'0','seller','VAT only'), (7,100,1,-0.015,NULL,'0','seller','Net only')");
        assertEquals(new BigDecimal("-40.03"), load().get("ORDER").getFirst().sellerVoucherTotal());
    }

    @Test
    void absentSellerVouchersDoNotUseVouchersFromAnotherSnapshot() throws Exception {
        sql("DELETE FROM voucher_split WHERE emag_order_surrogate_id = 1 AND offered_by = 'seller'");
        assertEquals(new BigDecimal("0.00"), load().get("ORDER").getFirst().sellerVoucherTotal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void orderReconstructionReadsOnlyTheRequestedSnapshot() throws Exception {
        var method = EmagOrder.class.getDeclaredMethod("selectVoucherSplitsByProductId", Connection.class, int.class, int.class);
        method.setAccessible(true);
        var finalized = (List<VoucherSplit>) method.invoke(null, db, 100, 1);
        var storno = (List<VoucherSplit>) method.invoke(null, db, 100, 2);
        assertEquals(3, finalized.size());
        assertEquals(1, storno.size());
        assertTrue(storno.stream().allMatch(v -> v.voucher_name().equals("Remaining")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<POInfo>> load() throws Exception {
        var method = EmagMirrorDB.class.getDeclaredMethod("getOrderDataByProduct", Connection.class, String.class);
        method.setAccessible(true);
        return (Map<String, List<POInfo>>) method.invoke(null, db, "CODE");
    }
    private void sql(String query) throws Exception { try (var s = db.createStatement()) { s.execute(query); } }
}
