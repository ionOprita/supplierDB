package ro.sellfluence.app;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PopulateProductsTableFromSheetsTest {
    @Test
    void toProductInfoMapsAdditionalFields() {
        var row = emptyRow();
        var vendorId = UUID.randomUUID();

        set(row, PopulateProductsTableFromSheets.ProductColumn.NAME, "Z. 1 - 1+1 alb");
        set(row, PopulateProductsTableFromSheets.ProductColumn.MODEL, "1+1 alb");
        set(row, PopulateProductsTableFromSheets.ProductColumn.PRODUCT_LENGTH_MM, new BigDecimal("140"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.PRODUCT_WIDTH_MM, new BigDecimal("70"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.PRODUCT_HEIGHT_MM, new BigDecimal("80"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.PRODUCT_WEIGHT_G, new BigDecimal("150"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.EAN, "0016027482842");
        set(row, PopulateProductsTableFromSheets.ProductColumn.BRAND, "Zoopie");
        set(row, PopulateProductsTableFromSheets.ProductColumn.PRODUCT_CODE, "D9LYDSBBM");
        set(row, PopulateProductsTableFromSheets.ProductColumn.WARRANTY_MONTHS, 24);
        set(row, PopulateProductsTableFromSheets.ProductColumn.IMPORT_TAX, new BigDecimal("0.022"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.SUPPLIER_PRODUCT_CODE, "SUP-1");
        set(row, PopulateProductsTableFromSheets.ProductColumn.CONTINUE_TO_SELL, true);
        set(row, PopulateProductsTableFromSheets.ProductColumn.RETRACTED, true);
        set(row, PopulateProductsTableFromSheets.ProductColumn.AIR_TRANSPORT_PCS_PER_CARTON, 75);
        set(row, PopulateProductsTableFromSheets.ProductColumn.AIR_TRANSPORT_VOLUME_M3_PER_CARTON, new BigDecimal("0.0813"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.PNK, "D9LYDSBBM");
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMAG_LINK, "https://emag.ro/product_details/pd/D9LYDSBBM");
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMAG_TITLE, "Test title");
        set(row, PopulateProductsTableFromSheets.ProductColumn.VENDOR_NAME, "Zoopie Solutions SRL");
        set(row, PopulateProductsTableFromSheets.ProductColumn.INCOME_PROFIT_TAX, "Profit");
        set(row, PopulateProductsTableFromSheets.ProductColumn.VAT_PAYER, true);
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMAG_SALE_PRICE_RON, new BigDecimal("79.99"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMAG_COMMISSION, new BigDecimal("0.18"));
        set(row, PopulateProductsTableFromSheets.ProductColumn.OFFER_ID_SOLUTIONS_FBE, 47L);
        set(row, PopulateProductsTableFromSheets.ProductColumn.CATEGORY, "Sonerii electrice");
        set(row, PopulateProductsTableFromSheets.ProductColumn.INDEX_CATEGORY, "Z.");
        set(row, PopulateProductsTableFromSheets.ProductColumn.DIVISION, "DIY");
        set(row, PopulateProductsTableFromSheets.ProductColumn.SUPRACATEGORY, "Lighting & Electrical");
        set(row, PopulateProductsTableFromSheets.ProductColumn.CATEGORY_NAME, "Complementary Lighting Products");
        set(row, PopulateProductsTableFromSheets.ProductColumn.SUBCATEGORY, "Electric doorbells");
        set(row, PopulateProductsTableFromSheets.ProductColumn.SUPRACATEGORY_COUNTRY, "Iluminat & Electrice");
        set(row, PopulateProductsTableFromSheets.ProductColumn.CATEGORY_COUNTRY, "Produse complementare iluminat");
        set(row, PopulateProductsTableFromSheets.ProductColumn.CATEGORY_ID, 3010);
        set(row, PopulateProductsTableFromSheets.ProductColumn.SCM_ID, 3109);
        set(row, PopulateProductsTableFromSheets.ProductColumn.DOC_ID, 3531);
        set(row, PopulateProductsTableFromSheets.ProductColumn.INDEXED_SUBCATEGORY_COUNTRY, "Z. Sonerii electrice");
        set(row, PopulateProductsTableFromSheets.ProductColumn.BIG_CATEGORY, "Home & Garden");
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMAG_ADS_AUTO_ID, 1001L);
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMAG_ADS_MANUAL_ID, 1002L);
        set(row, PopulateProductsTableFromSheets.ProductColumn.MESSAGE_KEYWORD, "Soneria Zoopie");
        set(row, PopulateProductsTableFromSheets.ProductColumn.GENDER, "Feminin");
        set(row, PopulateProductsTableFromSheets.ProductColumn.MANUAL_VIDEO_LINK, "https://example.com/video");
        set(row, PopulateProductsTableFromSheets.ProductColumn.USAGE_GUIDE_LINK, "https://example.com/guide");
        set(row, PopulateProductsTableFromSheets.ProductColumn.USAGE_SITE_LINK, "https://example.com/site");
        set(row, PopulateProductsTableFromSheets.ProductColumn.USAGE_MANUAL_LINK, "https://example.com/manual");
        set(row, PopulateProductsTableFromSheets.ProductColumn.OTHER_COMMENTS, "notes");
        set(row, PopulateProductsTableFromSheets.ProductColumn.REVIEW_CALLER, "Ana");
        set(row, PopulateProductsTableFromSheets.ProductColumn.EMPLOYEE_SHEET_NAME, "Raport Zoopie");
        set(row, PopulateProductsTableFromSheets.ProductColumn.REPORT_LINK, "https://example.com/report");

        var info = PopulateProductsTableFromSheets.toProductInfo(
                row,
                Map.of("Zoopie Solutions SRL", vendorId),
                (pnk, sheetName) -> "Tab 1"
        );

        assertEquals("D9LYDSBBM", info.productCode());
        assertEquals("Z. 1 - 1+1 alb", info.name());
        assertEquals("1+1 alb", info.model());
        assertEquals(new BigDecimal("140"), info.productLengthMm());
        assertEquals("0016027482842", info.ean());
        assertEquals(24, info.warrantyMonths());
        assertEquals(new BigDecimal("0.022"), info.importTax());
        assertEquals(new BigDecimal("0.0813"), info.airTransportVolumeM3PerCarton());
        assertEquals("https://emag.ro/product_details/pd/D9LYDSBBM", info.emagLink());
        assertEquals(vendorId, info.vendor());
        assertEquals("Profit", info.incomeProfitTax());
        assertEquals(Boolean.TRUE, info.vatPayer());
        assertEquals(new BigDecimal("79.99"), info.emagSalePriceRon());
        assertEquals(47L, info.offerIdSolutionsFbe());
        assertEquals("Complementary Lighting Products", info.categoryName());
        assertEquals(3010, info.categoryId());
        assertEquals("Z. Sonerii electrice", info.indexedSubcategoryCountry());
        assertEquals(1001L, info.emagAdsAutoId());
        assertEquals("Soneria Zoopie", info.messageKeyword());
        assertEquals("https://example.com/video", info.manualVideoLink());
        assertEquals("Ana", info.reviewCaller());
        assertEquals("Raport Zoopie", info.employeeSheetName());
        assertEquals("Tab 1", info.employeeSheetTab());
        assertEquals("https://example.com/report", info.reportLink());
        assertFalse(info.retracted());
    }

    @Test
    void toProductInfoReturnsNullWhenProductCodeIsMissing() {
        var row = emptyRow();

        set(row, PopulateProductsTableFromSheets.ProductColumn.NAME, "Ignored");

        var info = PopulateProductsTableFromSheets.toProductInfo(row, Map.of(), (pnk, sheetName) -> "Tab 1");

        assertNull(info);
    }

    private static ArrayList<Object> emptyRow() {
        return new ArrayList<>(Collections.nCopies(PopulateProductsTableFromSheets.ProductColumn.values().length, null));
    }

    private static void set(ArrayList<Object> row, PopulateProductsTableFromSheets.ProductColumn column, Object value) {
        row.set(column.ordinal(), value);
    }
}
