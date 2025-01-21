package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductInfo;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public class PopulateProductsTableFromSheets {

    public static void updateProductTable() {
        populateFrom("sellfluence1", "2025 - Date produse & angajati", "Cons. Date Prod.").forEach(productInfo -> {
            try {
                EmagMirrorDB.getEmagMirrorDB("emagLocal").addProduct(productInfo);
            } catch (SQLException e) {
                System.out.println("Could not add product " + productInfo + e.getMessage());
            } catch (IOException e) {
                System.out.println("Could not open database" + e.getMessage());
            }
        });
    }

    private static List<ProductInfo> populateFrom(String appName, String spreadSheetName, String overviewSheetName) {
        var drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        if (spreadSheetId==null||spreadSheetId.isBlank()) {
            throw new RuntimeException("Spreadsheet %s not found.".formatted(spreadSheetName));
        }
        var spreadSheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        return spreadSheet.getMultipleColumns(overviewSheetName, "C", "BH", "CN", "DW").stream().skip(3)
                .<ProductInfo>mapMulti((row, nextConsumer) -> {
                            var pnk = row.get(1).toString();
                            var name = row.get(0).toString();
                            var category = row.get(2).toString();
                            var messageKeyword = row.get(3).toString();
                            if (!(pnk.isBlank() || pnk.equals("0") || name.isBlank() || name.equals("-"))) {
                                nextConsumer.accept(new ProductInfo(pnk, name, category, messageKeyword));
                            }
                        }
                ).toList();
    }
}
