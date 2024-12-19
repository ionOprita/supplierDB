package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

/**
 * Read data from the comenzi sheet and compare it to the database data.
 *
 * Find orders that are only in one place.
 *
 * Find differences in orders that are in both places.
 */
public class CompareDBWithDataComenzi {

    private static final String appName = "sellfluence1";
    private static final String spreadSheetName = "Testing Coding 2024 - Date comenzi";
    private static final String sheetName = "Date";

    // Fetch all orders from google sheet.
    // Store them in a list of objects.
    // Read from database and produse same data structure.
    // Compare to find:
    // a) items in emag db missing from google sheet.
    // b) items in google sheet and missing in emag db
    // c) orders which are in both lists but differ in data.

    public static void main(String[] args) {
        final DriveAPI drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var spreadSheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        System.out.println("Reading spreadsheet ...");
        var dataFromSheet = sheetDataToOrderList(spreadSheet.getRowsInColumnRange(sheetName, "A", "AF").stream().skip(3).toList());
        try {
            System.out.println("Reading database ...");
            var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagOprita");
            var dataFromDB = dbDataToOrderList(mirrorDB.readForComparisonApp());
            System.out.println("Compare data ...");
            compare(dataFromDB, dataFromSheet);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    enum Vendor {
        judios,
        koppel,
        koppelfbe,
        sellfluence,
        sellfusion,
        zoopieconcept,
        zoopieinvest,
        zoopiesolutions;

        static Vendor fromSheet(String name, boolean isFBE) {
            return switch (name) {
                case "Judios Concept SRL", "Judios Concept" -> judios;
                case "Koppel SRL", "Koppel" -> isFBE ? koppelfbe : koppel;
                case "Sellfluence SRL", "Sellfluence" -> sellfluence;
                case "Sellfusion SRL", "Sellflusion SRL", "SELLFUSION FBE" -> sellfusion;
                case "Zoopie Concept SRL", "Zoopie Concept" -> zoopieconcept;
                case "Zoopie Invest SRL", "Zoopie Invest" -> zoopieinvest;
                case "Zoopie Solutions SRL", "Zoopie Solutions" -> zoopiesolutions;
                default ->
                        throw new IllegalArgumentException("Unrecognized vendor '" + name + "' " + (isFBE ? "FBE" : ""));
            };
        }
    }

    /**
     * Store all data belonging to a single order line as found in the google sheet.
     */
    record OrderLine(
            String orderId,
            Vendor vendor,
            String PNK
    ) {
    }

    private static List<OrderLine> dbDataToOrderList(List<List<Object>> dataFromDB) {
        return dataFromDB.stream().map(row ->
                {
                    return new OrderLine(
                            (String) row.get(0),
                            Vendor.fromSheet((String) row.get(1), (Boolean) row.get(2)),
                            (String) row.get(3)

                    );
                }
        ).toList();
    }

    private static List<OrderLine> sheetDataToOrderList(List<List<String>> dataFromSheet) {
        return dataFromSheet.stream().map(row -> {
            String vendorName = row.get(23);
            Vendor vendor = Vendor.fromSheet(vendorName, isEMAGFbe(row.get(24)));
            return new OrderLine(row.get(1), vendor, row.get(4));
        }).toList();
    }

    private static boolean isEMAGFbe(String fbe) {
        return switch (fbe) {
            case "eMAG FBE" -> true;
            case "eMAG NON-FBE" -> false;
            default -> throw new IllegalArgumentException("Unrecognized FBE value " + fbe);
        };
    }

    private static void compare(List<OrderLine> unsortedDataFromDB, List<OrderLine> unsortedDataFromSheet) {
        System.out.println("Sort datbase values ...");
        var dataFromDB = unsortedDataFromDB.stream()
                .sorted(Comparator.comparing(OrderLine::vendor).thenComparing(OrderLine::orderId))
                .toList();
        System.out.println("Sort sheet values ...");
        var dataFromSheet = unsortedDataFromSheet.stream().sorted(Comparator.comparing(OrderLine::vendor).thenComparing(OrderLine::orderId)).toList();
        System.out.println("Find ones only in sheet ...");
        var onlyInSheet = dataFromSheet.stream().filter(it -> !dataFromDB.contains(it)).toList();
        System.out.println("Find ones only in DB ...");
        var onlyInDB = dataFromDB.stream().filter(it -> !dataFromSheet.contains(it)).toList();
        System.out.println("# elements from sheet "+dataFromSheet.size());
        System.out.println("# elements only in sheet "+onlyInSheet.size());
        System.out.println("# elements from db "+dataFromDB.size());
        System.out.println("# elements only in db "+onlyInDB.size());
    }
}
/*

 */