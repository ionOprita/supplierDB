package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GMVDashboardTableTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final UUID THIRD = new UUID(0, 3);
    private static final List<GMVDashboardTable.VendorCompany> VENDORS = List.of(
            new GMVDashboardTable.VendorCompany(FIRST, "company:Alpha", "Alpha"),
            new GMVDashboardTable.VendorCompany(SECOND, "company:Beta", "Beta"),
            new GMVDashboardTable.VendorCompany(THIRD, "vendor:" + THIRD, "Same display name"));

    @Test
    void computesTheMonthOnceAndAllocatesNetPairedGmvOnTheContributingStornoDate() throws SQLException {
        var sources = List.of(source("P", FIRST, 1, "order", 4, "2026-11-01", 4, 4, 0, "100.00", "-40.00"),
                source("P", FIRST, 2, "order", 5, "2026-11-03", 3, 4, 1, "110.00", "-30.00"));

        var data = GMVDashboardTable.calculate(day("2026-11-03"), VENDORS, sources);

        assertEquals("300.00", data.total());
        assertEquals(List.of("0.00", "0.00", "300.00"), company(data, "company:Alpha").gmv());
        assertEquals("300.00", company(data, "company:Alpha").total());
        assertEquals("0.00", company(data, "company:Beta").total());
        assertReconciles(data);
    }

    @Test
    void includesOutsidePartnerStatesAndRestatesTheCurrentMonthWhenItsStornoDateEntersTheRange() throws SQLException {
        var sources = List.of(source("P", FIRST, 1, "order", 4, "2026-11-01", 4, 4, 0, "100.00", "-40.00"),
                source("P", FIRST, 2, "order", 5, "2026-11-03", 3, 4, 1, "110.00", "-30.00"));

        var early = GMVDashboardTable.calculate(day("2026-11-01"), VENDORS, sources);
        var later = GMVDashboardTable.calculate(day("2026-11-03"), VENDORS, sources);
        var futureMonth = GMVDashboardTable.calculate(day("2026-12-31"), VENDORS, sources);

        assertEquals(List.of("360.00"), company(early, "company:Alpha").gmv());
        assertEquals(List.of("0.00", "0.00", "300.00"), company(later, "company:Alpha").gmv());
        assertEquals("0.00", futureMonth.total());
        var crossMonth = List.of(
                source("P", FIRST, 3, "earlier", 4, "2026-10-31", 4, 4, 0, "100.00", "-40.00"),
                source("P", FIRST, 4, "earlier", 5, "2026-11-02", 3, 4, 1, "110.00", "-30.00"));
        var returned = GMVDashboardTable.calculate(day("2026-11-03"), VENDORS, crossMonth);
        assertEquals("-100.00", returned.total());
        assertEquals(List.of("0.00", "-100.00", "0.00"), company(returned, "company:Alpha").gmv());
        assertReconciles(early);
        assertReconciles(later);
        assertReconciles(returned);
    }

    @Test
    void keepsProductCodesAndVendorIdentitiesSeparateAndRetainsAmountsBeyondJavascriptSafeIntegers() throws SQLException {
        var first = source("P", FIRST, 1, "shared", 4, "2026-11-01", 2, 2, 0, "9007199254740993.12", "-0.29");
        var duplicateCatalogCode = new GMVDashboardTable.SourceLine("OTHER-CODE", FIRST, "RON", first.line());
        var otherVendor = source("P", SECOND, 2, "shared", 4, "2026-11-01", 1, 1, 0, "10.00", "0.00");

        var data = GMVDashboardTable.calculate(day("2026-11-01"), VENDORS,
                List.of(first, duplicateCatalogCode, otherVendor));

        assertEquals("36028797018963981.90", data.total());
        assertEquals("36028797018963971.90", company(data, "company:Alpha").total());
        assertEquals("10.00", company(data, "company:Beta").total());
        assertReconciles(data);
    }

    @Test
    void retainsZeroCompaniesStableKeysDuplicateDisplayNamesAndLeapDayCalendars() throws SQLException {
        var extra = new UUID(0, 4);
        var vendors = new ArrayList<>(VENDORS);
        vendors.add(new GMVDashboardTable.VendorCompany(extra, "vendor:" + extra, "Same display name"));
        var data = GMVDashboardTable.calculate(day("2028-02-29"), vendors, List.of());

        assertEquals("2028-02", data.month());
        assertEquals("2028-02-29", data.throughDate());
        assertEquals("RON", data.currency());
        assertEquals("0.00", data.total());
        assertEquals(29, data.days().size());
        assertEquals("2028-02-29", data.days().getLast());
        assertEquals(List.of("company:Alpha", "company:Beta", "vendor:" + THIRD, "vendor:" + extra),
                data.companies().stream().map(GMVDashboardTable.CompanySeries::key).toList());
        assertTrue(data.companies().stream().allMatch(company -> company.gmv().stream().allMatch("0.00"::equals)));
        assertThrows(UnsupportedOperationException.class, () -> data.days().add("bad"));
        assertThrows(UnsupportedOperationException.class, () -> data.companies().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.companies().getFirst().gmv().clear());
        assertTrue(GMVDashboardTable.calculate(day("2026-11-01"), List.of(), List.of()).companies().isEmpty());
    }

    @Test
    void rejectsMissingOrUnsupportedCurrenciesWithSafeMessagesAndNeverMixesTotals() {
        var normal = source("P", FIRST, 1, "order", 4, "2026-11-01", 1, 1, 0, "10.00", "0.00");
        for (var currency : new String[]{null, "", " ", "EUR", "RON<script>"}) {
            var invalid = new GMVDashboardTable.SourceLine(normal.productCode(), normal.vendorId(), currency, normal.line());
            var failure = assertThrows(GMVDashboardTable.UnsupportedCurrencyException.class,
                    () -> GMVDashboardTable.calculate(day("2026-11-01"), VENDORS, List.of(invalid)));
            assertTrue(failure.getMessage().contains("only supports RON"));
            assertFalse(failure.getMessage().contains("<script>"));
        }
        assertTrue(new GMVDashboardTable.UnsupportedCurrencyException("EUR").getMessage().contains("EUR"));
    }

    @Test
    void failsOnInconsistentSnapshotPairsOrMissingCompanyMetadata() {
        var first = source("P", FIRST, 1, "order", 4, "2026-11-01", 1, 1, 0, "10.00", "0.00");
        var duplicateState = source("P", FIRST, 2, "order", 4, "2026-11-02", 1, 1, 0, "10.00", "0.00");
        var failure = assertThrows(SQLException.class, () -> GMVDashboardTable.calculate(day("2026-11-03"),
                VENDORS, List.of(first, duplicateState)));
        assertTrue(failure.getMessage().contains("inconsistent order snapshots"));
        assertThrows(SQLException.class,
                () -> GMVDashboardTable.calculate(day("2026-11-03"), List.of(), List.of(first)));
    }

    private static GMVDashboardTable.CompanySeries company(GMVDashboardTable.GMVDashboardData data, String key) {
        return data.companies().stream().filter(company -> company.key().equals(key)).findFirst().orElseThrow();
    }

    private static void assertReconciles(GMVDashboardTable.GMVDashboardData data) {
        var total = new BigDecimal("0.00");
        for (var company : data.companies()) {
            var sum = company.gmv().stream().map(BigDecimal::new).reduce(new BigDecimal("0.00"), BigDecimal::add);
            assertEquals(company.total(), sum.toPlainString());
            total = total.add(sum);
        }
        assertEquals(data.total(), total.toPlainString());
    }

    private static GMVDashboardTable.SourceLine source(String productCode, UUID vendor, int surrogate,
                                                       String order, int status, String date, int quantity,
                                                       int initial, int storno, String price, String voucher) {
        return new GMVDashboardTable.SourceLine(productCode, vendor, "RON",
                new POInfo(order, surrogate, day(date), status, 101, "Product", quantity, initial, storno,
                        new BigDecimal(price), new BigDecimal(voucher)));
    }

    private static LocalDate day(String text) {
        return LocalDate.parse(text);
    }
}
