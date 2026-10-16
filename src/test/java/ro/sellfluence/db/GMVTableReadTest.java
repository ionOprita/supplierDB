package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GMVTableReadTest {
    @Test
    void bulkReadPreservesDuplicateNamesAndProductsWithoutGmv() throws Exception {
        var january = YearMonth.of(2026, 1);
        var february = YearMonth.of(2026, 2);
        var fixture = new ReadFixture(List.of(
                product("later", "Z. 10 - Later"),
                product("second", "Z. 2 - Shared"),
                product("empty", "J. 1 - Empty"),
                product("first", "Z. 2 - Shared"),
                product("unnamed", null)
        ), List.of(
                gmv("first", february, "-2.50"),
                gmv("later", january, "0.00"),
                gmv("second", january, "9.00"),
                gmv("first", january, "1.25")
        ));

        var table = EmagMirrorDB.getGMV(fixture.connection());
        var products = table.keySet().stream().toList();

        assertEquals(List.of("empty", "later", "first", "second", "unnamed"),
                products.stream().map(ProductTable.ProductInfo::productCode).toList());
        assertEquals(Map.of(january, new BigDecimal("1.25"), february, new BigDecimal("-2.50")),
                table.get(products.get(2)));
        assertEquals(List.of(january, february), table.get(products.get(2)).keySet().stream().toList());
        assertEquals(Map.of(january, new BigDecimal("9.00")), table.get(products.get(3)));
        assertEquals(new BigDecimal("0.00"), table.get(products.get(1)).get(january));
        assertFalse(table.get(products.get(1)).containsKey(february));
        assertTrue(table.get(products.get(0)).isEmpty());
        assertTrue(table.get(products.get(4)).isEmpty());
        assertEquals(2, fixture.queryCount);
    }

    @Test
    void acceptsLegacyAndOtherVendorProductNames() throws Exception {
        var fixture = new ReadFixture(List.of(
                product("legacy", "Legacy product"),
                product("other-vendor", "A. 1 - Product")
        ), List.of());

        assertEquals(List.of("other-vendor", "legacy"), EmagMirrorDB.getGMV(fixture.connection()).keySet()
                .stream().map(ProductTable.ProductInfo::productCode).toList());
    }

    @Test
    void emptyDatabaseReturnsEmptyTableWithTwoBulkReads() throws Exception {
        var fixture = new ReadFixture(List.of(), List.of());

        assertTrue(EmagMirrorDB.getGMV(fixture.connection()).isEmpty());
        assertEquals(2, fixture.queryCount);
    }

    private static Map<String, Object> product(String code, String name) {
        var row = new HashMap<String, Object>();
        row.put("product_code", code);
        row.put("name", name);
        row.put("emag_pnk", "PNK-" + code);
        return row;
    }

    private static Map<String, Object> gmv(String code, YearMonth month, String value) {
        return Map.of("product_code", code, "month", Date.valueOf(month.atDay(1)),
                "gmv", new BigDecimal(value));
    }

    /** Supplies product rows and stored GMV rows; additional per-product queries fail. */
    private static final class ReadFixture {
        private final List<List<Map<String, Object>>> queryRows;
        private int queryCount;

        private ReadFixture(List<Map<String, Object>> products, List<Map<String, Object>> gmv) {
            queryRows = List.of(products, gmv);
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (_, method, _) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            if (queryCount >= queryRows.size()) {
                                throw new AssertionError("Unexpected additional query");
                            }
                            yield statement(queryRows.get(queryCount++));
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement(List<Map<String, Object>> rows) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (_, method, _) -> switch (method.getName()) {
                        case "executeQuery" -> resultSet(rows);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ResultSet resultSet(List<Map<String, Object>> rows) {
            var cursor = new int[]{-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (_, method, arguments) -> switch (method.getName()) {
                        case "next" -> ++cursor[0] < rows.size();
                        case "getString", "getObject", "getBigDecimal", "getDate" -> rows.get(cursor[0]).get(arguments[0]);
                        case "getBoolean" -> Boolean.TRUE.equals(rows.get(cursor[0]).get(arguments[0]));
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
