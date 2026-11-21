package ro.sellfluence.db;

import ch.claudio.db.DB;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.api.ProfitCalculatorCategory;
import ro.sellfluence.api.ProfitCalculatorFbeTariffs;
import ro.sellfluence.api.ProfitCalculatorOverrides;
import ro.sellfluence.api.ProfitCalculatorSettings;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Change;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.ConflictException;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.versions.EmagMirrorDBVersion50;
import ro.sellfluence.db.versions.EmagMirrorDBVersion51;
import ro.sellfluence.db.versions.SetupDB;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reads actual calculator inputs through the API, mirror, and pooled read transaction. */
class EmagMirrorDBProfitCalculatorCategoryIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
    private String url;
    private String schema;
    private final Properties properties = new Properties();
    private HikariDataSource pool;
    private DB database;
    private API api;

    @BeforeEach
    void createCommittedCategoryAndTariffFixture() throws SQLException {
        url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        schema = "profit_category_inputs_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(url, properties)) {
            db.setAutoCommit(false);
            try (var statement = db.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        CREATE TABLE version_info (version INTEGER PRIMARY KEY, date TIMESTAMP WITH TIME ZONE);
                        INSERT INTO version_info VALUES (51, current_timestamp);
                        CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_group TEXT, company_name TEXT);
                        CREATE TABLE product (
                            product_code TEXT PRIMARY KEY, name TEXT, emag_pnk TEXT,
                            vendor UUID REFERENCES vendor(id), category TEXT, retracted BOOLEAN NOT NULL,
                            emag_sale_price_ron NUMERIC, income_profit_tax TEXT, vat_payer BOOLEAN,
                            emag_commission NUMERIC, product_length_mm NUMERIC, product_width_mm NUMERIC,
                            product_height_mm NUMERIC, product_weight_g NUMERIC
                        );
                        CREATE TABLE emag_order (
                            surrogate_id INTEGER PRIMARY KEY, id TEXT NOT NULL,
                            vendor_id UUID REFERENCES vendor(id), status INTEGER NOT NULL, date TIMESTAMP,
                            UNIQUE (id, vendor_id, status)
                        );
                        CREATE TABLE product_in_order (
                            id INTEGER NOT NULL, emag_order_surrogate_id INTEGER REFERENCES emag_order(surrogate_id),
                            part_number_key TEXT, quantity INTEGER, initial_qty INTEGER, product_id INTEGER, mkt_id INTEGER,
                            PRIMARY KEY (id, emag_order_surrogate_id)
                        );
                        CREATE TABLE storno (storno_date TIMESTAMP, order_id TEXT, product_id INTEGER, quantity INTEGER);
                        CREATE TABLE rma_result (emag_id INTEGER PRIMARY KEY, order_id TEXT, request_status INTEGER, date TIMESTAMP);
                        CREATE TABLE emag_returned_products (
                            emag_id INTEGER REFERENCES rma_result(emag_id), product_id INTEGER,
                            product_emag_id INTEGER, quantity INTEGER
                        );
                        INSERT INTO vendor VALUES
                            ('00000000-0000-0000-0000-000000000001', 'S', 'Company S'),
                            ('00000000-0000-0000-0000-000000000002', 'S', 'Company S FBE'),
                            ('00000000-0000-0000-0000-000000000003', 'J', 'Company J'),
                            ('00000000-0000-0000-0000-000000000004', 'K', 'Company K'),
                            ('00000000-0000-0000-0000-000000000005', 'Z', 'Company Z');
                        INSERT INTO product VALUES
                            ('active', 'S. 2 - Active', 'ACTIVE', '00000000-0000-0000-0000-000000000001',
                             'Trimmers', false, 100, 'Profit', true, 0.18, 100, 100, 10, 2000.1),
                            ('retracted', 'S. 1 - Retracted', 'RETRACTED', '00000000-0000-0000-0000-000000000001',
                             'Trimmers', true, 100, 'Profit', true, 0.25, 100, 100, 10, 1000);
                        INSERT INTO emag_order VALUES
                            (1, 'sale', '00000000-0000-0000-0000-000000000001', 4, '2026-10-15 12:00:00'),
                            (2, 'sale', '00000000-0000-0000-0000-000000000001', 5, '2026-10-20 12:00:00'),
                            (3, 'sale', '00000000-0000-0000-0000-000000000002', 4, '2026-10-15 12:00:00'),
                            (4, 'sale', '00000000-0000-0000-0000-000000000003', 4, '2026-10-15 12:00:00'),
                            (5, 'next-month', '00000000-0000-0000-0000-000000000001', 4, '2026-11-01 00:00:00');
                        INSERT INTO product_in_order (id, emag_order_surrogate_id, part_number_key, quantity) VALUES
                            (1, 1, 'ACTIVE', 3), (1, 2, 'ACTIVE', 0), (1, 3, 'ACTIVE', 4),
                            (1, 4, 'ACTIVE', 100), (1, 5, 'ACTIVE', 100);
                        """);
            }
            EmagMirrorDBVersion50.version50(db);
            EmagMirrorDBVersion51.version51(db);
            db.commit();
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDataSourceProperties(properties);
        config.addDataSourceProperty("options", "-c search_path=" + schema);
        config.setAutoCommit(false);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        pool = new HikariDataSource(config);
        database = new DB(pool);
        SetupDB.setupAndUpdateDB(database);
        api = new API(new EmagMirrorDB(database));
    }

    @AfterEach
    void closePoolAndDropFixture() throws SQLException {
        if (pool != null) pool.close();
        if (schema != null) {
            try (var db = DriverManager.getConnection(url, properties); var statement = db.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void readsFeesAlongsideGrossUnitsAndCommissionAndPreservesActiveFirstOrdering() throws Exception {
        var response = read();
        assertEquals(List.of("active", "retracted"), response.products().stream()
                .map(ProfitCalculatorCategory.Product::productCode).toList());
        var active = response.products().getFirst();
        assertEquals(7L, active.calculated().previousMonthGrossUnits());
        assertEquals(0.0, active.calculated().returnRate());
        assertEquals(0.0, active.calculated().refusalRate());
        amount("2", active.calculated().warehousePreparationCostRon());
        amount("0", active.calculated().refusalCostRon());
        amount("0", active.calculated().defectiveProductCostRon());
        assertNull(active.calculated().parcelPreparationCostRon());
        amount("0.18", active.calculated().effectiveCommissionRate());
        amount("18", active.calculated().commissionCostRon());
        amount("4", active.calculated().fbeProcessingCostRon());
        amount("2.1", active.calculated().fbeReturnCostRon());
        amount("4", active.calculated().fbeWithdrawalCostRon());
        amount("4", active.calculated().fbeDisposalCostRon());
        var retracted = response.products().getLast().calculated();
        assertNull(retracted.returnRate());
        assertNull(retracted.refusalRate());
        assertNull(retracted.fbeProcessingCostRon());
        assertNull(retracted.fbeReturnCostRon());
        assertNull(retracted.fbeWithdrawalCostRon());
        assertNull(retracted.fbeDisposalCostRon());
        assertNull(retracted.warehousePreparationCostRon());
    }

    @Test
    void subsequentReadsAndPreviewsUseSavedTariffEditsAndPreserveMeasurements() throws Exception {
        api.saveProfitCalculatorFbeTariffs(new ProfitCalculatorFbeTariffs.Request(List.of(
                new Change(1, 1, Map.of("orderProcessingFeeRon", "10", "extraOrderFeeRon", "0.125")))));
        var saved = read().products().getFirst();
        amount("10.125", saved.calculated().fbeProcessingCostRon());

        var request = new ProfitCalculatorOverrides.Request("S", "Trimmers", List.of(
                new OverrideChange("active", 0, Map.of("salePriceRon", "120", "fulfillmentSource", "Minulescu"))));
        var response = preview(request);
        var preview = response.products().getFirst();
        amount("10.125", preview.calculated().fbeProcessingCostRon());
        amount("2.1", preview.calculated().fbeReturnCostRon());
        amount("4", preview.calculated().fbeWithdrawalCostRon());
        amount("4", preview.calculated().fbeDisposalCostRon());
        assertEquals(7L, preview.calculated().previousMonthGrossUnits());
        amount("21.6", preview.calculated().commissionCostRon());
        assertEquals("Minulescu", preview.overrides().fulfillmentSource());
        amount("2", preview.calculated().parcelPreparationCostRon());
        amount("100", read().products().getFirst().calculated().effectiveSalePriceRon());
        assertEquals(0, read().products().getFirst().overrideRevision());
    }

    @Test
    void missingMeasurementKeepsFeesUnknownWithoutHidingOtherResults() throws Exception {
        try (var db = DriverManager.getConnection(url, properties); var statement = db.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("UPDATE product SET product_weight_g = NULL WHERE product_code = 'active'");
        }
        var calculated = read().products().getFirst().calculated();
        assertNull(calculated.fbeProcessingCostRon());
        assertNull(calculated.fbeReturnCostRon());
        assertNull(calculated.fbeWithdrawalCostRon());
        assertNull(calculated.fbeDisposalCostRon());
        assertEquals(7L, calculated.previousMonthGrossUnits());
        amount("18", calculated.commissionCostRon());
    }

    @Test
    void ratesUseAllAccountsAndKeepSixCompletedMonthsAtTheExactThreshold() throws Exception {
        executeFixture("""
                INSERT INTO emag_order VALUES
                    (6, 'before-six', '00000000-0000-0000-0000-000000000001', 4, '2026-04-30 23:59:59'),
                    (7, 'paired', '00000000-0000-0000-0000-000000000003', 4, '2026-05-01 00:00:00'),
                    (8, 'paired', '00000000-0000-0000-0000-000000000003', 5, '2026-05-12 12:00:00');
                INSERT INTO product_in_order VALUES
                    (1, 6, 'ACTIVE', 200, 200, 11, 111),
                    (1, 7, 'ACTIVE', 100, 110, 11, 111),
                    (1, 8, 'ACTIVE', 50, 110, 11, 111);
                INSERT INTO storno VALUES
                    ('2026-06-01 00:00:00', 'paired', 1, 50),
                    ('2026-11-01 00:00:00', 'paired', 1, 50);
                INSERT INTO rma_result VALUES
                    (1, 'paired', 7, '2026-06-01 00:00:00'),
                    (2, 'paired', 7, '2026-11-01 00:00:00'),
                    (3, 'paired', 6, '2026-06-01 00:00:00'),
                    (4, 'paired', 7, '2026-06-01 00:00:00');
                INSERT INTO emag_returned_products VALUES
                    (1, 11, 111, 25), (2, 11, 111, 25), (3, 11, 111, 12), (4, 11, 999, 8);
                """);

        // The legacy PNK count picks 100 units from the colliding 'sale' order and 100 from 'paired'.
        var active = read().products().getFirst().calculated();
        assertEquals(0.125, active.returnRate());
        assertEquals(0.125, active.refusalRate());
        assertEquals(7L, active.previousMonthGrossUnits());

        var request = new ProfitCalculatorOverrides.Request("S", "Trimmers", List.of(
                new OverrideChange("active", 0, Map.of("salePriceRon", "120"))));
        var preview = preview(request).products().getFirst().calculated();
        assertEquals(active.returnRate(), preview.returnRate());
        assertEquals(active.refusalRate(), preview.refusalRate());
        assertEquals(active.previousMonthGrossUnits(), preview.previousMonthGrossUnits());
    }

    @Test
    void stornoRateAboveThresholdUsesTwelveMonthsForBothRates() throws Exception {
        executeFixture("""
                INSERT INTO emag_order VALUES
                    (6, 'before-twelve', '00000000-0000-0000-0000-000000000001', 4, '2025-10-31 23:59:59'),
                    (7, 'storno-only', '00000000-0000-0000-0000-000000000003', 5, '2025-11-01 00:00:00');
                INSERT INTO product_in_order VALUES
                    (1, 6, 'ACTIVE', 400, 400, 11, 111),
                    (1, 7, 'ACTIVE', 0, 100, 11, 111);
                INSERT INTO storno VALUES
                    ('2025-10-31 23:59:59', 'storno-only', 1, 30),
                    ('2025-11-01 00:00:00', 'storno-only', 1, 10),
                    ('2026-05-01 00:00:00', 'storno-only', 1, 30);
                INSERT INTO rma_result VALUES
                    (1, 'storno-only', 7, '2025-11-01 00:00:00'),
                    (2, 'storno-only', 7, '2026-05-01 00:00:00');
                INSERT INTO emag_returned_products VALUES (1, 11, 111, 10), (2, 11, 111, 10);
                """);

        // The short window has 100 units ordered and 30 storned. The long window has 200 and 40.
        var active = read().products().getFirst().calculated();
        assertEquals(0.1, active.returnRate());
        assertEquals(0.1, active.refusalRate());
        assertEquals(7L, active.previousMonthGrossUnits());
    }

    @Test
    void unknownRatesStayBlankAndInvalidCountsDoNotTriggerAnExtendedWindow() throws Exception {
        executeFixture("""
                INSERT INTO product (product_code, name, emag_pnk, vendor, category, retracted) VALUES
                    ('no-sales', 'S. 3 - No sales', 'NO-SALES', '00000000-0000-0000-0000-000000000001', 'Trimmers', false),
                    ('no-pnk', 'S. 4 - No PNK', NULL, '00000000-0000-0000-0000-000000000001', 'Trimmers', false);
                INSERT INTO emag_order VALUES
                    (6, 'before-six', '00000000-0000-0000-0000-000000000001', 4, '2026-04-30 23:59:59'),
                    (7, 'invalid-count', '00000000-0000-0000-0000-000000000001', 5, '2026-05-01 00:00:00');
                INSERT INTO product_in_order VALUES
                    (1, 6, 'ACTIVE', 400, 400, 11, 111),
                    (1, 7, 'ACTIVE', 0, 0, 11, 111);
                INSERT INTO rma_result VALUES (1, 'invalid-count', 7, '2026-05-01 00:00:00');
                INSERT INTO emag_returned_products VALUES (1, 11, 111, 101);
                """);

        // 101 returns and -101 refusals for 100 units are invalid. Zero stornos do not extend the window.
        for (var product : read().products()) {
            assertNull(product.calculated().returnRate(), product.productCode());
            assertNull(product.calculated().refusalRate(), product.productCode());
        }
    }

    @Test
    void dateCalculCountsAllAccountsForTheOwnedCatalogAndDoesNotIncludeOtherGroupsProducts() throws Exception {
        executeFixture("""
                INSERT INTO product (product_code, name, emag_pnk, vendor, category, retracted) VALUES
                    ('j-product', 'J. 1 - Product', 'J-PRODUCT', '00000000-0000-0000-0000-000000000003', NULL, false),
                    ('k-product', 'K. 1 - Product', 'K-PRODUCT', '00000000-0000-0000-0000-000000000004', 'Trimmers', false);
                INSERT INTO emag_order VALUES
                    (6, 'boundary', '00000000-0000-0000-0000-000000000003', 4, '2026-10-01 00:00:00'),
                    (7, 'outside', '00000000-0000-0000-0000-000000000001', 4, '2026-09-30 23:59:59');
                INSERT INTO product_in_order (id, emag_order_surrogate_id, part_number_key, quantity) VALUES
                    (2, 4, 'ACTIVE', 2), (1, 6, 'RETRACTED', 6), (1, 7, 'ACTIVE', 99),
                    (3, 3, 'J-PRODUCT', 15), (4, 3, NULL, 300);
                """);
        var before = Clock.fixed(Instant.parse("2026-10-31T21:59:59Z"), ZoneOffset.UTC);
        var after = Clock.fixed(Instant.parse("2026-10-31T22:00:00Z"), ZoneOffset.UTC);
        assertEquals(99L, api.getProfitCalculatorSettings("S", before).calculated().previousMonthUnits());
        var settings = api.getProfitCalculatorSettings("S", after);
        var totals = settings.calculated();
        assertEquals("2026-10", totals.previousMonth());
        assertEquals(115L, totals.previousMonthUnits());
        assertEquals(15L, api.getProfitCalculatorSettings("J", after).calculated().previousMonthUnits());
        assertEquals(0L, api.getProfitCalculatorSettings("K", after).calculated().previousMonthUnits());
        assertNull(api.getProfitCalculatorSettings("K", after).calculated().accountingCostPerUnitRon());
        assertEquals("0.21", settings.general().values().get("vatRate"));
        amount("33300", new BigDecimal(totals.employeeTotalRon()));
        amount("11015", new BigDecimal(totals.miscellaneousTotalRon()));
        assertEquals(new BigDecimal("1600").divide(BigDecimal.valueOf(115), MathContext.DECIMAL128),
                new BigDecimal(totals.accountingCostPerUnitRon()));
        assertNull(totals.interestCostPerUnitRon());
        var active = read().products().getFirst().calculated();
        assertEquals(7L, active.previousMonthGrossUnits());
        assertEquals(new BigDecimal(totals.accountingCostPerUnitRon()), active.accountingCostPerUnitRon());
        assertEquals(new BigDecimal(totals.employeeCostPerUnitRon()), active.employeeCostPerUnitRon());
        assertEquals(new BigDecimal(totals.miscellaneousCostPerUnitRon()).multiply(new BigDecimal("1.21")),
                active.miscellaneousCostPerUnitRon());
        amount("3.388", active.geniusCostRon());

        var withoutSales = read("K").products().getFirst().calculated();
        assertNull(withoutSales.accountingCostPerUnitRon());
        assertNull(withoutSales.employeeCostPerUnitRon());
        assertNull(withoutSales.miscellaneousCostPerUnitRon());
        amount("3.388", withoutSales.geniusCostRon());

        executeFixture("UPDATE product_in_order SET quantity = NULL WHERE id = 2 AND emag_order_surrogate_id = 4");
        totals = api.getProfitCalculatorSettings("S", after).calculated();
        assertNull(totals.previousMonthUnits());
        assertNull(totals.employeeCostPerUnitRon());
        assertNull(totals.miscellaneousCostPerUnitRon());
        var unknownSales = read().products().getFirst().calculated();
        assertEquals(7L, unknownSales.previousMonthGrossUnits());
        assertNull(unknownSales.accountingCostPerUnitRon());
        assertNull(unknownSales.employeeCostPerUnitRon());
        assertNull(unknownSales.miscellaneousCostPerUnitRon());
        amount("3.388", unknownSales.geniusCostRon());
    }

    @Test
    void settingsSavesAreIndependentAndCategoryPreviewsUseTheCommittedValues() throws Exception {
        var mapper = new ObjectMapper();
        var edit = ProfitCalculatorSettings.parse(mapper, """
                {"vendorGroup":"S","section":"generalCosts","expectedRevision":1,"values":{
                 "packingCostRon":"1","courierCostRon":null,"geniusCostRon":"12",
                 "monthlyAccountingCostRon":"0","warehousePreparationCostRon":"1.250000000000000001"}}
                """);
        api.saveProfitCalculatorSettings(edit);
        assertThrows(ConflictException.class, () -> api.saveProfitCalculatorSettings(edit));
        var employeeEdit = ProfitCalculatorSettings.parse(mapper, """
                {"vendorGroup":"S","section":"employees","expectedRevision":1,"items":[
                 {"label":"Example allocation","amountRon":null},{"label":"Other allocation","amountRon":"0"}]}
                """);
        api.saveProfitCalculatorSettings(employeeEdit);
        SetupDB.setupAndUpdateDB(database);
        var settings = api.getProfitCalculatorSettings("S", CLOCK);
        assertEquals(2, settings.generalCosts().revision());
        assertEquals(2, settings.employees().revision());
        assertEquals(1, settings.general().revision());
        assertEquals(1, settings.miscellaneous().revision());
        assertNull(settings.generalCosts().values().get("courierCostRon"));
        assertEquals("0", settings.calculated().accountingCostPerUnitRon());
        assertNull(settings.employees().items().getFirst().amountRon());
        assertEquals("0", settings.employees().items().getLast().amountRon());
        assertEquals("2", api.getProfitCalculatorSettings("K", CLOCK).generalCosts().values()
                .get("warehousePreparationCostRon"));
        var loaded = read().products().getFirst().calculated();
        amount("1.250000000000000001", loaded.warehousePreparationCostRon());
        amount("5.082", loaded.geniusCostRon());
        amount("0", loaded.accountingCostPerUnitRon());
        amount("0", loaded.employeeCostPerUnitRon());
        var ownWarehousePreview = preview(new ProfitCalculatorOverrides.Request("S", "Trimmers", List.of(
                new OverrideChange("active", 0, Map.of("fulfillmentSource", "Minulescu")))));
        amount("1.250000000000000001", ownWarehousePreview.products().getFirst().calculated().parcelPreparationCostRon());
        var fbePreview = preview(new ProfitCalculatorOverrides.Request("S", "Trimmers", List.of(
                new OverrideChange("active", 0, Map.of("fulfillmentSource", "eMAG")))));
        var preview = fbePreview.products().getFirst().calculated();
        amount("4.84", preview.parcelPreparationCostRon());
        assertEquals(loaded.geniusCostRon(), preview.geniusCostRon());
        assertEquals(loaded.accountingCostPerUnitRon(), preview.accountingCostPerUnitRon());
        assertEquals(loaded.employeeCostPerUnitRon(), preview.employeeCostPerUnitRon());
        assertEquals(loaded.miscellaneousCostPerUnitRon(), preview.miscellaneousCostPerUnitRon());
    }

    private void executeFixture(String sql) throws SQLException {
        try (var db = DriverManager.getConnection(url, properties); var statement = db.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute(sql);
        }
    }

    private ProfitCalculatorCategory.Response read() throws Exception {
        return read("S");
    }

    private ProfitCalculatorCategory.Response read(String vendorGroup) throws Exception {
        // Fix the business calendar without widening the production overload's visibility.
        var method = API.class.getDeclaredMethod("getProfitCalculatorCategory", String.class, String.class, Clock.class);
        method.setAccessible(true);
        return (ProfitCalculatorCategory.Response) method.invoke(api, vendorGroup, "Trimmers", CLOCK);
    }

    private ProfitCalculatorCategory.Response preview(ProfitCalculatorOverrides.Request request) throws Exception {
        var method = API.class.getDeclaredMethod("previewProfitCalculatorCategory",
                ProfitCalculatorOverrides.Request.class, Clock.class);
        method.setAccessible(true);
        return (ProfitCalculatorCategory.Response) method.invoke(api, request, CLOCK);
    }

    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
