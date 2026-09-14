package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetReport;
import ro.sellfluence.emagapi.AdSet;
import ro.sellfluence.emagapi.AdsAdset;
import ro.sellfluence.emagapi.AdsCampaign;
import ro.sellfluence.emagapi.AdsCampaignSnapshot;
import ro.sellfluence.emagapi.AdsKeyword;
import ro.sellfluence.emagapi.AdsPerformanceSummary;
import ro.sellfluence.emagapi.AdsSearchPhrase;
import ro.sellfluence.emagapi.AdsTargetedProduct;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only the explicitly supplied disposable PostgreSQL test database. */
class AdsCampaignTableIntegrationTest {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 8, 1);
    private static final LocalDate SECOND_DAY = FIRST_DAY.plusDays(1);
    private static final int CAMPAIGN_ID = 101;
    private static final int ADSET_ID = 202;
    private static final List<String> ADS_TABLES = List.of(
            "ads_campaign", "ads_adset", "ads_keyword", "ads_search_phrase", "ads_targeted_product"
    );

    private Connection db;
    private String schema;
    private final UUID firstVendor = UUID.randomUUID();
    private final UUID secondVendor = UUID.randomUUID();
    private final UUID noAdsVendor = UUID.randomUUID();

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "ads_storage_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute("""
                CREATE TABLE vendor (
                    id UUID PRIMARY KEY,
                    vendor_name TEXT NOT NULL UNIQUE,
                    isfbe BOOLEAN,
                    company_name TEXT,
                    account TEXT,
                    last_fetch TIMESTAMP
                )
                """);
        for (int version = 36; version <= 39; version++) {
            applyMigration(version);
        }
        addVendor(firstVendor, "Z Sellfusion", "sellfusion");
        addVendor(secondVendor, "A Second vendor", "second-account");
        addVendor(noAdsVendor, "B No ads", "no-ads");
        db.commit();
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                if (schema != null) {
                    execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    @Test
    void identicalIdsRemainIndependentAcrossAllUpsertsAndSingleDayReports() throws SQLException {
        var first = snapshot(FIRST_DAY, "First", 2);
        var second = snapshot(FIRST_DAY, "Second", 200);
        assertEquals(5, AdsCampaignTable.upsertCampaigns(db, firstVendor, List.of(first)));
        assertEquals(5, AdsCampaignTable.upsertCampaigns(db, secondVendor, List.of(second)));
        var phraseId = phraseId(firstVendor);

        assertEquals(5, AdsCampaignTable.upsertCampaigns(db, firstVendor,
                List.of(snapshot(FIRST_DAY, "First revised", 5))));
        for (var table : ADS_TABLES) {
            assertEquals(2, rowCount(table), table);
        }
        assertEquals(phraseId, phraseId(firstVendor), "Updating a phrase retains its generated identity");
        assertNotEquals(phraseId(firstVendor), phraseId(secondVendor));
        assertReportValues(firstVendor, AdsReportPeriod.singleDay(FIRST_DAY), "First revised", 5);
        assertReportValues(secondVendor, AdsReportPeriod.singleDay(FIRST_DAY), "Second", 200);
    }

    @Test
    void separateDetailBatchesUseTheVendorFromEachAdsetKey() throws SQLException {
        var first = snapshot(FIRST_DAY, "First", 7);
        var second = snapshot(FIRST_DAY, "Second", 700);
        AdsCampaignTable.upsertCampaignsAndAdsets(db, firstVendor, List.of(first));
        AdsCampaignTable.upsertCampaignsAndAdsets(db, secondVendor, List.of(second));
        var firstKey = new AdsAdsetKey(firstVendor, FIRST_DAY, CAMPAIGN_ID, ADSET_ID);
        var secondKey = new AdsAdsetKey(secondVendor, FIRST_DAY, CAMPAIGN_ID, ADSET_ID);
        assertNotEquals(firstKey, secondKey);
        var firstAdset = first.adSets().getFirst();
        var secondAdset = second.adSets().getFirst();

        AdsCampaignTable.upsertKeywordReports(db, List.of(
                new AdsAdsetReport<>(firstKey, firstAdset.keywords()),
                new AdsAdsetReport<>(secondKey, secondAdset.keywords())
        ));
        AdsCampaignTable.upsertSearchPhraseReports(db, List.of(
                new AdsAdsetReport<>(firstKey, firstAdset.searchPrases()),
                new AdsAdsetReport<>(secondKey, secondAdset.searchPrases())
        ));
        AdsCampaignTable.upsertTargetedProductReports(db, List.of(
                new AdsAdsetReport<>(firstKey, firstAdset.targetedProducts()),
                new AdsAdsetReport<>(secondKey, secondAdset.targetedProducts())
        ));

        assertReportValues(firstVendor, AdsReportPeriod.singleDay(FIRST_DAY), "First", 7);
        assertReportValues(secondVendor, AdsReportPeriod.singleDay(FIRST_DAY), "Second", 700);
    }

    @Test
    void periodTotalsAndLatestNamesAreComputedWithinEachVendor() throws SQLException {
        AdsCampaignTable.upsertCampaigns(db, firstVendor, List.of(
                snapshot(FIRST_DAY, "First old", 2), snapshot(SECOND_DAY, "First latest", 3)
        ));
        AdsCampaignTable.upsertCampaigns(db, secondVendor, List.of(
                snapshot(FIRST_DAY, "Second old", 200), snapshot(SECOND_DAY, "Second latest", 300)
        ));
        var period = new AdsReportPeriod(FIRST_DAY, SECOND_DAY);
        assertReportValues(firstVendor, period, "First latest", 5);
        assertReportValues(secondVendor, period, "Second latest", 500);
        assertTrue(AdsCampaignTable.getCampaigns(db, noAdsVendor, period).rows().isEmpty());
        assertTrue(AdsCampaignTable.getAdsets(db, noAdsVendor, CAMPAIGN_ID, period).rows().isEmpty());
        assertTrue(AdsCampaignTable.getKeywords(db, noAdsVendor, CAMPAIGN_ID, ADSET_ID, period).rows().isEmpty());
        assertTrue(AdsCampaignTable.getSearchPhrases(db, noAdsVendor, CAMPAIGN_ID, ADSET_ID, period).rows().isEmpty());
        assertTrue(AdsCampaignTable.getTargetedProducts(db, noAdsVendor, CAMPAIGN_ID, ADSET_ID, period).rows().isEmpty());
    }

    @Test
    void vendorOptionsDatesAndDetailDiscoveryUseOnlyTheRequestedVendor() throws SQLException {
        AdsCampaignTable.upsertCampaignsAndAdsets(db, firstVendor, List.of(snapshot(FIRST_DAY, "First", 1)));
        AdsCampaignTable.upsertCampaignsAndAdsets(db, secondVendor, List.of(
                snapshot(FIRST_DAY, "Second", 2), snapshot(SECOND_DAY, "Second", 3)
        ));

        assertEquals(List.of(secondVendor, firstVendor),
                AdsCampaignTable.getVendors(db).stream().map(Vendor::id).toList());
        assertEquals(List.of(FIRST_DAY), AdsCampaignTable.getCampaignReportDates(db, firstVendor));
        assertEquals(List.of(SECOND_DAY, FIRST_DAY), AdsCampaignTable.getCampaignReportDates(db, secondVendor));
        assertEquals(List.of(FIRST_DAY), AdsCampaignTable.getAdsetReportDates(db, firstVendor, CAMPAIGN_ID));
        assertEquals(List.of(SECOND_DAY, FIRST_DAY),
                AdsCampaignTable.getAdsetReportDates(db, secondVendor, CAMPAIGN_ID));
        assertEquals(List.of(new AdsAdsetKey(firstVendor, FIRST_DAY, CAMPAIGN_ID, ADSET_ID)),
                AdsCampaignTable.getAdsetKeys(db, firstVendor, FIRST_DAY, SECOND_DAY.plusDays(1)));
        assertEquals(List.of(new AdsAdsetKey(secondVendor, FIRST_DAY, CAMPAIGN_ID, ADSET_ID)),
                AdsCampaignTable.getAdsetKeys(db, secondVendor, FIRST_DAY, SECOND_DAY),
                "Detail discovery retains its exclusive end date");
        assertTrue(AdsCampaignTable.getCampaignReportDates(db, noAdsVendor).isEmpty());
        assertTrue(AdsCampaignTable.getAdsetKeys(db, noAdsVendor, FIRST_DAY, SECOND_DAY).isEmpty());
    }

    @Test
    void accountResolutionRejectsMissingAndAmbiguousVendors() throws SQLException {
        assertEquals(firstVendor, Vendor.requireVendorIdByAccount(db, "sellfusion"));
        assertEquals(secondVendor, Vendor.requireVendorIdByAccount(db, "second-account"));
        assertThrows(SQLException.class, () -> Vendor.requireVendorIdByAccount(db, "missing-account"));
        assertThrows(SQLException.class, () -> Vendor.requireVendorIdByAccount(db, " "));
        addVendor(UUID.randomUUID(), "Duplicate sellfusion", "sellfusion");
        var failure = assertThrows(SQLException.class, () -> Vendor.requireVendorIdByAccount(db, "sellfusion"));
        assertTrue(failure.getMessage().contains("sellfusion"));
    }

    private void assertReportValues(UUID vendorId, AdsReportPeriod period, String name, int clicks) throws SQLException {
        var campaigns = AdsCampaignTable.getCampaigns(db, vendorId, period);
        assertEquals(1, campaigns.rows().size());
        assertEquals(name + " campaign", campaigns.rows().getFirst().values().get("name"));
        assertEquals(Integer.toString(clicks), campaigns.rows().getFirst().values().get("summary_clicks"));
        var adsets = AdsCampaignTable.getAdsets(db, vendorId, CAMPAIGN_ID, period);
        assertEquals(name + " campaign", adsets.campaignName());
        assertEquals(1, adsets.rows().size());
        assertEquals(name + " adset", adsets.rows().getFirst().values().get("name"));
        assertEquals(Integer.toString(clicks), adsets.rows().getFirst().values().get("summary_clicks"));
        var keywords = AdsCampaignTable.getKeywords(db, vendorId, CAMPAIGN_ID, ADSET_ID, period);
        assertEquals(name + " campaign", keywords.campaignName());
        assertEquals(name + " adset", keywords.adsetName());
        assertEquals(1, keywords.rows().size());
        assertEquals(Integer.toString(clicks), keywords.rows().getFirst().values().get("summary_clicks"));
        var phrases = AdsCampaignTable.getSearchPhrases(db, vendorId, CAMPAIGN_ID, ADSET_ID, period);
        assertEquals(name + " campaign", phrases.campaignName());
        assertEquals(name + " adset", phrases.adsetName());
        assertEquals(1, phrases.rows().size());
        assertEquals(Integer.toString(clicks), phrases.rows().getFirst().values().get("summary_clicks"));
        var products = AdsCampaignTable.getTargetedProducts(db, vendorId, CAMPAIGN_ID, ADSET_ID, period);
        assertEquals(name + " campaign", products.campaignName());
        assertEquals(name + " adset", products.adsetName());
        assertEquals(1, products.rows().size());
        assertEquals(name + " product", products.rows().getFirst().values().get("product_name"));
        assertEquals(Integer.toString(clicks), products.rows().getFirst().values().get("clicks"));
    }

    private static AdsCampaignSnapshot snapshot(LocalDate date, String name, int clicks) {
        var summary = new AdsPerformanceSummary(
                BigDecimal.valueOf(20), clicks, BigDecimal.ONE, BigDecimal.TWO, clicks * 100,
                BigDecimal.valueOf(clicks * 10L), clicks, clicks, BigDecimal.valueOf(clicks * 2L),
                1, 1, 0, 1, 1, 1, BigDecimal.valueOf(100), BigDecimal.valueOf(5)
        );
        var adset = new AdsAdset(ADSET_ID, name + " adset", "manual", BigDecimal.ONE,
                "active", "active", null, summary);
        var campaign = new AdsCampaign(CAMPAIGN_ID, name + " campaign", 303,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "active", "active", "manual",
                null, null, summary, name, List.of(adset), null, null);
        var keyword = new AdsKeyword(404, BigDecimal.ONE, "active", "shared keyword", "exact",
                "active", BigDecimal.ONE, adset, summary);
        var phrase = new AdsSearchPhrase(ADSET_ID, "shared phrase", false, summary);
        var product = new AdsTargetedProduct(505, name + " product", BigDecimal.TEN, BigDecimal.ONE,
                "shared-pnk", "category", 606, "brand", 707, ADSET_ID, clicks, clicks * 100,
                BigDecimal.ONE, BigDecimal.TWO, BigDecimal.valueOf(clicks * 2L), BigDecimal.valueOf(20),
                BigDecimal.valueOf(clicks * 10L), clicks, clicks, null, BigDecimal.valueOf(5), BigDecimal.valueOf(100));
        return new AdsCampaignSnapshot(date, campaign,
                List.of(new AdSet(adset, List.of(phrase), List.of(product), List.of(keyword))));
    }

    private void addVendor(UUID id, String name, String account) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO vendor (id, vendor_name, account) VALUES (?, ?, ?)")) {
            s.setObject(1, id);
            s.setString(2, name);
            s.setString(3, account);
            s.executeUpdate();
        }
    }

    private int rowCount(String table) throws SQLException {
        try (var s = db.prepareStatement("SELECT COUNT(*) FROM " + table); var rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long phraseId(UUID vendor) throws SQLException {
        try (var s = db.prepareStatement("SELECT id FROM ads_search_phrase WHERE vendor_id = ?")) {
            s.setObject(1, vendor);
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var s = db.createStatement()) {
            s.execute(sql);
        }
    }

    private void applyMigration(int version) throws Exception {
        var type = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion" + version);
        var method = type.getDeclaredMethod("version" + version, Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(null, db);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception failure) throw failure;
            throw e;
        }
    }
}
