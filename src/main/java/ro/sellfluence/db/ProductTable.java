package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ProductTable {
    public record ProductInfo(
            String pnk,
            String productCode,
            String name,
            boolean continueToSell,
            boolean retracted,
            String category,
            String messageKeyword,
            String employeeSheetName
    ) {
    }

    /**
     * Insert a product in the table that records our information about a product and associates our name and
     * category with the PNK used by emag.
     *
     * @param db database
     * @param productInfo record mapping to column
     * @return 1 or 0 depending on whether the insertion was successful or not.
     * @throws SQLException if anything bad happens.
     */
    static int insertProduct(Connection db, ProductInfo productInfo) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO product (product_code, emag_pnk, name, continue_to_sell, retracted, category, message_keyword, employee_sheet_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """)) {
            s.setString(1, productInfo.productCode());
            s.setString(2, productInfo.pnk());
            s.setString(3, productInfo.name());
            s.setBoolean(4, productInfo.continueToSell());
            s.setBoolean(5, productInfo.retracted());
            s.setString(6, productInfo.category());
            s.setString(7, productInfo.messageKeyword());
            s.setString(8, productInfo.employeeSheetName());
            return s.executeUpdate();
        }
    }

    /**
     * Insert a product in the table that records our information about a product and associates our name and
     * category with the PNK used by emag.
     *
     * @param db database
     * @param productInfo record mapping to column
     * @return 1 or 0 depending on whether the insertion was successful or not.
     * @throws SQLException if anything bad happens.
     */
    static int updateProduct(Connection db, ProductInfo productInfo) throws SQLException {
        try (var s = db.prepareStatement("""
                UPDATE product
                SET emag_pnk = ?, category = ?, message_keyword = ?, continue_to_sell = ?, retracted = ?, name = ?, employee_sheet_name = ?
                WHERE product_code = ?
                """)) {
            s.setString(1, productInfo.pnk());
            s.setString(2, productInfo.category());
            s.setString(3, productInfo.messageKeyword());
            s.setBoolean(4, productInfo.continueToSell());
            s.setBoolean(5, productInfo.retracted());
            s.setString(6, productInfo.name());
            s.setString(7, productInfo.employeeSheetName());
            s.setString(8, productInfo.productCode());
            return s.executeUpdate();
        }
    }

    /**
     * Retrieve all products from the product table.
     *
     * @param db database
     * @return list of all the products.
     * @throws SQLException if anything bad happens.
     */
    static List<ProductInfo> getProducts(Connection db) throws SQLException {
        var products = new ArrayList<ProductInfo>();
        try (var s = db.prepareStatement("SELECT emag_pnk, product_code, name, continue_to_sell, retracted, category, message_keyword, employee_sheet_name FROM product")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    products.add(
                            new ProductInfo(
                                    rs.getString("emag_pnk"),
                                    rs.getString("product_code"),
                                    rs.getString("name"),
                                    rs.getBoolean("continue_to_sell"),
                                    rs.getBoolean("retracted"),
                                    rs.getString("category"),
                                    rs.getString("message_keyword"),
                                    rs.getString("employee_sheet_name")
                            )
                    );
                }
            }
        }
        return products;
    }

    /**
     * Retrieves all product codes from the product table.
     *
     * @param db the database connection to use for querying product codes.
     * @return a list of product codes retrieved from the database.
     * @throws SQLException if an error occurs while accessing the database.
     */
    static List<String> getProductCodes(Connection db) throws SQLException {
        var products = new ArrayList<String>();
        try (var s = db.prepareStatement("SELECT product_code FROM product")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    products.add(rs.getString(1));
                }
            }
        }
        return products;
    }
}