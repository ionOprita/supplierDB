package ro.sellfluence.apphelper;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductInfo;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import static java.lang.Boolean.TRUE;
import static java.util.logging.Level.WARNING;

/**
 * Provides the method for updating our product information.
 */
public class PopulateProductsTableFromSheets {
    private static final Logger logger = Logger.getLogger(PopulateProductsTableFromSheets.class.getName());

    /**
     * Find all products that are on the Date produse & angajati sheet and add any missing product to our database.
     */
    public static void updateProductTable() {
        EmagMirrorDB mirrorDB;
        try {
            mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        } catch (Exception e) {
            throw new RuntimeException("Could not open the database", e);
        }
        populateFrom("sellfluence1", "2025 - Date produse & angajati", "Cons. Date Prod.")
                .forEach(productInfo -> {
            try {
                mirrorDB.addProduct(productInfo);
            } catch (SQLException e) {
                System.out.println("Could not add the product " + productInfo + e.getMessage());
            }
        });
    }

    /**
     * Read from the Google spreadsheet our product information.
     *
     * @param appName google app name
     * @param spreadSheetName name of the spreadsheet
     * @param overviewSheetName name of the tab holding the product data.
     * @return list of record needed for populating the database.
     */
    private static List<ProductInfo> populateFrom(String appName, String spreadSheetName, String overviewSheetName) {
        var drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        if (spreadSheetId==null||spreadSheetId.isBlank()) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(spreadSheetName));
        }
        var spreadSheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        return spreadSheet.getMultipleColumns(overviewSheetName, "C", "K", "U", "V", "BH", "CN", "DW").stream().skip(3)
                .<ProductInfo>mapMulti((row, nextConsumer) -> {
                    var pnk = row.get(4).toString();
                            var productCode = row.get(1).toString();
                            var name = row.get(0).toString();
                    var category = row.get(5).toString();
                    var messageKeyword = row.get(6).toString();
                    var continueToSell = TRUE.equals(row.get(2));
                    var retracted = TRUE.equals(row.get(3));
                    if (continueToSell && retracted) {
                        logger.log(
                                WARNING,
                                "Product %s (%s) has both 'continue to sell' and 'retracted' set, which doesn't make sense. Dropping retracted."
                                        .formatted(name, pnk)
                        );
                        retracted = false;
                    }
                            if (!(pnk.isBlank() || pnk.equals("0") || name.isBlank() || name.equals("-"))) {
                                nextConsumer.accept(new ProductInfo(pnk, productCode, name, continueToSell, retracted, category, messageKeyword));
                            }
                        }
                ).toList();
    }
}