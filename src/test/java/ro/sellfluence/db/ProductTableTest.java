package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductTableTest {

    @Test
    void updateProductTabByPNKOnlyUpdatesTheTabForTheRequestedPNK() throws Exception {
        var sql = new AtomicReference<String>();
        var parameters = new HashMap<Integer, Object>();
        var connection = connectionCapturing(sql, parameters);

        var affectedRows = ProductTable.updateProductTabByPNK(connection, "PNK-123", "Employee tab");

        assertEquals(1, affectedRows);
        assertEquals(
                "UPDATE product SET employee_sheet_tab = ? WHERE emag_pnk = ?",
                sql.get()
        );
        assertEquals(Map.of(1, "Employee tab", 2, "PNK-123"), parameters);
    }

    @Test
    void employeeSheetTabUpdateRequiresTheAssociationUsedToResolveIt() throws Exception {
        var sql = new AtomicReference<String>();
        var parameters = new HashMap<Integer, Object>();
        var connection = connectionCapturing(sql, parameters);
        var update = new ProductTable.EmployeeSheetTabUpdate(
                "CODE-123",
                "PNK-123",
                "Employee sheet",
                "Employee tab"
        );

        var affectedRows = ProductTable.updateProductEmployeeSheetTab(connection, update);

        assertEquals(1, affectedRows);
        assertTrue(sql.get().contains("WHERE product_code = ?"));
        assertTrue(sql.get().contains("emag_pnk IS NOT DISTINCT FROM ?"));
        assertTrue(sql.get().contains("employee_sheet_name IS NOT DISTINCT FROM ?"));
        assertEquals(
                Map.of(1, "Employee tab", 2, "CODE-123", 3, "PNK-123", 4, "Employee sheet"),
                parameters
        );
    }

    @Test
    void preservingUpsertDoesNotIncludeTheEmployeeSheetTabInItsUpdate() throws Exception {
        var updateSql = new AtomicReference<String>();
        var connection = connectionCapturingProductUpdate(updateSql, new HashMap<>());
        var product = new ProductTable.ProductInfo(
                "PNK-123",
                "CODE-123",
                "Z. 1 - Test product",
                null,
                true,
                false,
                "Category",
                "Keyword",
                "Employee sheet",
                "Tab which must not be written"
        );

        ProductTable.insertOrUpdateProductPreservingEmployeeSheetTab(connection, product);

        assertNotNull(updateSql.get());
        assertTrue(updateSql.get().contains("employee_sheet_name = ?"));
        assertFalse(updateSql.get().contains("employee_sheet_tab"));
    }

    @Test
    void editorUpdateStillIncludesAndBindsTheEmployeeSheetTab() throws Exception {
        var updateSql = new AtomicReference<String>();
        var parameters = new HashMap<Integer, Object>();
        var connection = connectionCapturingProductUpdate(updateSql, parameters);
        var product = new ProductTable.ProductInfo(
                "PNK-123",
                "CODE-123",
                "Z. 1 - Test product",
                null,
                true,
                false,
                "Category",
                "Keyword",
                "Employee sheet",
                "Editor-selected tab"
        );

        ProductTable.updateExistingProduct(connection, product);

        assertNotNull(updateSql.get());
        assertTrue(updateSql.get().contains("employee_sheet_tab = ?"));
        assertEquals("Editor-selected tab", parameters.get(8));
    }

    private static Connection connectionCapturing(AtomicReference<String> sql, Map<Integer, Object> parameters) {
        return (Connection) Proxy.newProxyInstance(
                ProductTableTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (_, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        sql.set((String) arguments[0]);
                        return preparedStatementCapturing(parameters);
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static PreparedStatement preparedStatementCapturing(Map<Integer, Object> parameters) {
        return (PreparedStatement) Proxy.newProxyInstance(
                ProductTableTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (_, method, arguments) -> switch (method.getName()) {
                    case "setString" -> {
                        parameters.put((Integer) arguments[0], arguments[1]);
                        yield null;
                    }
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static Connection connectionCapturingProductUpdate(
            AtomicReference<String> updateSql,
            Map<Integer, Object> parameters
    ) {
        return (Connection) Proxy.newProxyInstance(
                ProductTableTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (_, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        var sql = (String) arguments[0];
                        if (sql.stripLeading().startsWith("UPDATE product")) {
                            updateSql.set(sql);
                            parameters.clear();
                        }
                        return productStatement(sql, parameters);
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static PreparedStatement productStatement(String sql, Map<Integer, Object> parameters) {
        return (PreparedStatement) Proxy.newProxyInstance(
                ProductTableTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (_, method, arguments) -> switch (method.getName()) {
                    case "setObject", "setBoolean" -> {
                        parameters.put((Integer) arguments[0], arguments[1]);
                        yield null;
                    }
                    case "close" -> null;
                    case "executeUpdate" -> sql.stripLeading().startsWith("INSERT INTO product") ? 0 : 1;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
