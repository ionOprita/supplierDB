package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GMVProductPeriodsTest {
    @Test
    void selectedVendorIsBoundForBothSalesBasesAndRejectsMissingIdentity() throws Exception {
        var vendorId = new UUID(0, 1);
        var period = Map.of(day("2026-11-01"), day("2026-11-30"));
        var rows = List.of(snapshotWithNetVoucher("order", 1, "2026-11-02", 4, 2, 2, 0,
                "100.00", "0.21", "-12.10", "-10.00"));
        var netFixture = new ReadFixture(rows);
        var grossFixture = new ReadFixture(rows);

        assertEquals(Map.of(day("2026-11-01"), new BigDecimal("190.00")),
                EmagMirrorDB.computeNetSalesForProductPeriods(netFixture.connection(), vendorId, "CODE", period));
        assertEquals(Map.of(day("2026-11-01"), new BigDecimal("229.90")),
                EmagMirrorDB.computeGMVForProductPeriods(grossFixture.connection(), vendorId, "CODE", period));
        assertEquals(Map.of(1, "CODE", 2, vendorId), netFixture.parameters);
        assertEquals(Map.of(1, "CODE", 2, vendorId), grossFixture.parameters);
        assertEquals(1, netFixture.executionCount);
        assertEquals(1, grossFixture.executionCount);
        assertTrue(EmagMirrorDB.computeNetSalesForProductPeriods(null, vendorId, "CODE", Map.of()).isEmpty());
        assertTrue(EmagMirrorDB.computeGMVForProductPeriods(null, vendorId, "CODE", Map.of()).isEmpty());
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeNetSalesForProductPeriods(null, null, "CODE", Map.of()));
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(null, null, "CODE", Map.of()));
    }

    @Test
    void netSalesUseOriginalPricesAndNetVouchersAcrossVatRatesWhileGmvStaysGross() throws Exception {
        var rows = List.of(
                snapshotWithNetVoucher("standard", 1, "2026-11-01", 4, 2, 2, 0,
                        "100.00", "0.21", "-12.10", "-10.00"),
                snapshotWithNetVoucher("reduced", 2, "2026-11-02", 4, 1, 1, 0,
                        "50.00", "0.11", "-5.55", "-5.00"),
                snapshotWithNetVoucher("zero-rate", 3, "2026-11-03", 4, 1, 1, 0,
                        "20.00", "0", "0", "0")
        );
        var periods = Map.of(day("2026-11-01"), day("2026-11-30"),
                day("2026-11-02"), day("2026-11-02"));
        var netFixture = new ReadFixture(rows);

        var net = EmagMirrorDB.computeNetSalesForProductPeriods(netFixture.connection(), "CODE", periods);
        var gross = EmagMirrorDB.computeGMVForProductPeriods(new ReadFixture(rows).connection(), "CODE", periods);

        assertEquals(new BigDecimal("255.00"), net.get(day("2026-11-01")));
        assertEquals(new BigDecimal("45.00"), net.get(day("2026-11-02")));
        assertEquals(new BigDecimal("299.85"), gross.get(day("2026-11-01")));
        assertEquals(new BigDecimal("49.95"), gross.get(day("2026-11-02")));
        assertEquals(1, netFixture.queryCount);
        assertEquals(1, netFixture.executionCount);
        assertEquals(Map.of(1, "CODE"), netFixture.parameters);
    }

    @Test
    void netSalesPreserveUnitPricePrecisionUntilThePeriodTotalIsRounded() throws Exception {
        var fixture = new ReadFixture(List.of(
                snapshotWithNetVoucher("precise", 1, "2026-11-01", 4, 3, 3, 0,
                        "10.005", "0.21", "0", "0"),
                snapshotWithNetVoucher("small-first", 2, "2026-11-02", 4, 1, 1, 0,
                        "0.005", "0.11", "0", "0"),
                snapshotWithNetVoucher("small-second", 3, "2026-11-03", 4, 1, 1, 0,
                        "0.005", "0", "0", "0")
        ));

        var result = EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), "CODE", Map.of(
                day("2026-11-01"), day("2026-11-01"),
                day("2026-11-02"), day("2026-11-03")
        ));

        assertEquals(new BigDecimal("30.02"), result.get(day("2026-11-01")));
        assertEquals(new BigDecimal("0.01"), result.get(day("2026-11-02")));
    }

    @Test
    void netSalesRetainStornoAndVoucherAllocationForOverlappingFourteenAndThirtyDayPeriods() throws Exception {
        var fixture = new ReadFixture(List.of(
                snapshotWithNetVoucher("paired", 1, "2027-01-29", 4, 2, 2, 0,
                        "100.00", "0.19", "-23.80", "-20.00"),
                snapshotWithNetVoucher("paired", 2, "2027-01-30", 5, 1, 2, 1,
                        "110.00", "0.21", "-12.10", "-10.00"),
                snapshotWithNetVoucher("shared-day", 3, "2027-01-31", 4, 1, 1, 0,
                        "10.00", "0.11", "-1.11", "-1.00"),
                snapshotWithNetVoucher("fourteen-end", 4, "2027-02-07", 4, 1, 1, 0,
                        "1.00", "0", "0", "0"),
                snapshotWithNetVoucher("thirty-end", 5, "2027-02-28", 4, 1, 1, 0,
                        "2.00", "0.21", "0", "0")
        ));

        var result = EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), "CODE", Map.of(
                day("2027-01-02"), day("2027-01-31"),
                day("2027-01-30"), day("2027-02-28"),
                day("2027-01-25"), day("2027-02-07"),
                day("2027-02-08"), day("2027-02-21")
        ));

        // The paired order contributes 100 when both states are inside a period, and -100
        // when only the storno date is inside. Net voucher differences follow the same rule.
        assertEquals(Map.of(
                day("2027-01-02"), new BigDecimal("109.00"),
                day("2027-01-30"), new BigDecimal("-88.00"),
                day("2027-01-25"), new BigDecimal("110.00"),
                day("2027-02-08"), new BigDecimal("0.00")
        ), result);
        assertEquals(1, fixture.queryCount);
    }

    @Test
    void netSalesReturnZeroWithoutOrdersAndDoNotReadForEmptyPeriods() throws Exception {
        var fixture = new ReadFixture(List.of());
        assertEquals(Map.of(day("2026-11-01"), new BigDecimal("0.00")),
                EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), "CODE", Map.of(
                        day("2026-11-01"), day("2026-11-30"))));
        assertTrue(EmagMirrorDB.computeNetSalesForProductPeriods((Connection) null, "CODE", Map.of()).isEmpty());
    }

    @Test
    void netSalesValidateBeforeReadingAndPropagateDatabaseFailures() {
        var fixture = new ReadFixture(List.of());
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), "CODE", null));
        assertThrows(IllegalArgumentException.class,
                () -> EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), "CODE", Map.of(
                        day("2026-11-02"), day("2026-11-01"))));
        assertEquals(0, fixture.queryCount);

        var failure = new SQLException("order history unavailable");
        fixture.failure = failure;
        assertSame(failure, assertThrows(SQLException.class,
                () -> EmagMirrorDB.computeNetSalesForProductPeriods(fixture.connection(), "CODE", Map.of(
                        day("2026-11-01"), day("2026-11-30")))));
    }

    @Test
    void loadsTheProductOnceAndIncludesBothPeriodBoundaries() throws Exception {
        var fixture = new ReadFixture(List.of(
                snapshot("before", 1, "2026-01-14", 4, 1, 1, 0, "100.00", "0", "0"),
                snapshot("start", 2, "2026-01-15", 4, 2, 2, 0, "10.00", "0.19", "-2.00"),
                snapshot("end", 3, "2026-02-15", 4, 1, 1, 0, "3.99", "0", "0"),
                snapshot("after", 4, "2026-02-16", 4, 1, 1, 0, "100.00", "0", "0")
        ));

        var result = EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", Map.of(
                day("2026-01-15"), day("2026-02-15"),
                day("2026-02-15"), day("2026-02-15"),
                day("2026-03-01"), day("2026-03-31")
        ));

        assertEquals(Map.of(
                day("2026-01-15"), new BigDecimal("25.79"),
                day("2026-02-15"), new BigDecimal("3.99"),
                day("2026-03-01"), new BigDecimal("0.00")
        ), result);
        assertEquals(1, fixture.queryCount);
        assertEquals(1, fixture.executionCount);
        assertEquals(Map.of(1, "CODE"), fixture.parameters);
    }

    @Test
    void retainsMatchingSnapshotsOutsideEachRangeAndTreatsEachRangeAsOneBucket() throws Exception {
        var fixture = new ReadFixture(List.of(
                snapshot("order", 1, "2026-01-10", 4, 4, 4, 0, "100.00", "0", "-40.00"),
                snapshot("order", 2, "2026-02-10", 5, 3, 4, 1, "110.00", "0", "-30.00")
        ));

        var result = EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", Map.of(
                day("2026-01-01"), day("2026-01-31"),
                day("2026-02-01"), day("2026-02-28"),
                day("2026-01-10"), day("2026-02-10"),
                day("2026-03-01"), day("2026-03-31")
        ));

        assertEquals(new BigDecimal("360.00"), result.get(day("2026-01-01")));
        assertEquals(new BigDecimal("-100.00"), result.get(day("2026-02-01")));
        assertEquals(new BigDecimal("300.00"), result.get(day("2026-01-10")));
        assertEquals(new BigDecimal("0.00"), result.get(day("2026-03-01")));
        assertEquals(1, fixture.queryCount);
    }

    @Test
    void computesFourteenAndOverlappingMonthEndThirtyDayWindowsIndependently() throws Exception {
        var fixture = new ReadFixture(List.of(
                snapshot("before", 1, "2027-01-01", 4, 1, 1, 0, "1000.00", "0", "0"),
                snapshot("thirty-start", 2, "2027-01-02", 4, 1, 1, 0, "10.00", "0", "0"),
                snapshot("fourteen-start", 3, "2027-01-25", 4, 1, 1, 0, "2.00", "0", "0"),
                snapshot("paired", 4, "2027-01-29", 4, 4, 4, 0, "100.00", "0", "-40.00"),
                snapshot("paired", 5, "2027-01-30", 5, 3, 4, 1, "110.00", "0", "-30.00"),
                snapshot("shared-day", 6, "2027-01-31", 4, 1, 1, 0, "5.00", "0", "0"),
                snapshot("fourteen-end", 7, "2027-02-07", 4, 1, 1, 0, "3.00", "0", "0"),
                snapshot("thirty-end", 8, "2027-02-28", 4, 1, 1, 0, "7.00", "0", "0"),
                snapshot("after", 9, "2027-03-01", 4, 1, 1, 0, "1000.00", "0", "0")
        ));

        var result = EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", Map.of(
                day("2027-01-02"), day("2027-01-31"),
                day("2027-01-30"), day("2027-02-28"),
                day("2027-01-25"), day("2027-02-07"),
                day("2027-02-08"), day("2027-02-21")
        ));

        // January 30/31 belong to both 30-day observations. The paired order contributes 300
        // when both snapshots are in a window, but -100 when only its storno date is inside.
        assertEquals(Map.of(
                day("2027-01-02"), new BigDecimal("317.00"),
                day("2027-01-30"), new BigDecimal("-85.00"),
                day("2027-01-25"), new BigDecimal("310.00"),
                day("2027-02-08"), new BigDecimal("0.00")
        ), result);
        assertEquals(1, fixture.queryCount);
        assertEquals(1, fixture.executionCount);
    }

    @Test
    void returnsZeroForEveryRequestedPeriodWhenTheProductHasNoOrders() throws Exception {
        var fixture = new ReadFixture(List.of());

        var result = EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", Map.of(
                day("2026-01-01"), day("2026-01-31"),
                day("2026-02-01"), day("2026-02-28")
        ));

        assertEquals(Map.of(day("2026-01-01"), new BigDecimal("0.00"),
                day("2026-02-01"), new BigDecimal("0.00")), result);
        assertEquals(1, fixture.queryCount);
    }

    @Test
    void emptyPeriodsDoNotReadTheDatabase() throws Exception {
        assertTrue(EmagMirrorDB.computeGMVForProductPeriods((Connection) null, "CODE", Map.of()).isEmpty());
    }

    @Test
    void validatesEveryArgumentAndRangeBeforeReadingTheDatabase() {
        var fixture = new ReadFixture(List.of());
        var validPeriods = Map.of(day("2026-01-01"), day("2026-01-31"));
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), null, validPeriods));
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", null));

        var nullStart = new HashMap<LocalDate, LocalDate>();
        nullStart.put(null, day("2026-01-31"));
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", nullStart));
        var nullEnd = new HashMap<LocalDate, LocalDate>();
        nullEnd.put(day("2026-01-01"), null);
        assertThrows(NullPointerException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", nullEnd));

        var reversedRange = new LinkedHashMap<>(validPeriods);
        reversedRange.put(day("2026-02-02"), day("2026-02-01"));
        assertThrows(IllegalArgumentException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", reversedRange));
        assertEquals(0, fixture.queryCount);
    }

    @Test
    void propagatesDatabaseFailures() {
        var fixture = new ReadFixture(List.of());
        var failure = new SQLException("order history unavailable");
        fixture.failure = failure;

        var thrown = assertThrows(SQLException.class,
                () -> EmagMirrorDB.computeGMVForProductPeriods(fixture.connection(), "CODE", Map.of(
                        day("2026-01-01"), day("2026-01-31"))));

        assertSame(failure, thrown);
        assertEquals(1, fixture.executionCount);
    }

    private static LocalDate day(String value) {
        return LocalDate.parse(value);
    }

    private static Map<String, Object> snapshotWithNetVoucher(String orderId, int surrogateId, String date,
            int status, int quantity, int initialQuantity, int stornoQuantity, String salePrice, String vat,
            String grossVoucher, String netVoucher) {
        var row = snapshot(orderId, surrogateId, date, status, quantity, initialQuantity, stornoQuantity,
                salePrice, vat, grossVoucher);
        row.put("sellerVoucherNetTotal", new BigDecimal(netVoucher));
        return row;
    }

    private static Map<String, Object> snapshot(String orderId, int surrogateId, String date, int status,
                                                 int quantity, int initialQuantity, int stornoQuantity,
                                                 String salePrice, String vat, String voucher) {
        var result = new HashMap<String, Object>();
        result.put("orderId", orderId);
        result.put("orderSurrogateId", surrogateId);
        result.put("orderDate", Timestamp.valueOf(day(date).atStartOfDay()));
        result.put("orderStatus", status);
        result.put("pioId", 10);
        result.put("productName", "Product");
        result.put("quantity", quantity);
        result.put("initialQuantity", initialQuantity);
        result.put("stornoQuantity", stornoQuantity);
        result.put("salePrice", new BigDecimal(salePrice));
        result.put("vat", vat);
        result.put("sellerVoucherTotal", new BigDecimal(voucher));
        return result;
    }

    /** Supplies full product history and rejects any unexpected per-period SQL parameters. */
    private static final class ReadFixture {
        private final List<Map<String, Object>> rows;
        private final Map<Integer, Object> parameters = new HashMap<>();
        private int queryCount;
        private int executionCount;
        private SQLException failure;

        private ReadFixture(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (_, method, _) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            queryCount++;
                            yield statement();
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (_, method, arguments) -> switch (method.getName()) {
                        case "setObject" -> {
                            parameters.put((Integer) arguments[0], arguments[1]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            executionCount++;
                            if (failure != null) throw failure;
                            yield resultSet();
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ResultSet resultSet() {
            var cursor = new int[]{-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (_, method, arguments) -> switch (method.getName()) {
                        case "next" -> ++cursor[0] < rows.size();
                        case "getString", "getInt", "getBigDecimal", "getTimestamp" ->
                                rows.get(cursor[0]).get(arguments[0]);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
