package ro.sellfluence.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Product metadata and reference values for the category profit calculator. */
public final class ProfitCalculatorTable {
    private static final String STRIP_CHARACTERS = stripCharacters();

    private ProfitCalculatorTable() {
    }

    public record CategoryProduct(String productCode, String name, String pnk, boolean retracted,
                                  BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer) {
        public CategoryProduct(String productCode, String name, String pnk, boolean retracted) {
            this(productCode, name, pnk, retracted, null, null, null);
        }
    }

    static List<CategoryProduct> getCategoryProducts(Connection db, String vendorGroup, String category)
            throws SQLException {
        var products = new ArrayList<CategoryProduct>();
        try (var statement = db.prepareStatement("""
                SELECT p.product_code, p.name, p.emag_pnk, p.retracted,
                       p.emag_sale_price_ron, p.income_profit_tax, p.vat_payer
                FROM product p
                JOIN vendor v ON p.vendor = v.id
                WHERE btrim(v.vendor_group, ?) = ? AND btrim(p.category, ?) = ?
                """)) {
            statement.setString(1, STRIP_CHARACTERS);
            statement.setString(2, vendorGroup);
            statement.setString(3, STRIP_CHARACTERS);
            statement.setString(4, category);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    products.add(new CategoryProduct(result.getString("product_code"), result.getString("name"),
                            result.getString("emag_pnk"), result.getBoolean("retracted"),
                            result.getBigDecimal("emag_sale_price_ron"), result.getString("income_profit_tax"),
                            result.getObject("vat_payer", Boolean.class)));
                }
            }
        }
        return products;
    }

    private static String stripCharacters() {
        // Selector options use String.strip(); PostgreSQL's default btrim only removes spaces.
        var characters = new StringBuilder();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.isWhitespace(codePoint)) characters.appendCodePoint(codePoint);
        }
        return characters.toString();
    }
}
