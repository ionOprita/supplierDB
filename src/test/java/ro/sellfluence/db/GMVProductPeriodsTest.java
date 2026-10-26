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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GMVProductPeriodsTest {
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
        assertTrue(EmagMirrorDB.computeGMVForProductPeriods(null, "CODE", Map.of()).isEmpty());
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
