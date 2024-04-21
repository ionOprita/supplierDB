package ro.sellfluence.app;

import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.util.List;

public class GetOverview {

    private final DriveAPI drive;
    private final SheetsAPI spreadSheet;

    public GetOverview(String appName, String spreadSheetName) {
        drive = new DriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        spreadSheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
    }

    public record SheetData(
            String productName,
            String pnk,
            String spreadSheetName,
            String spreadSheetId
    ) {
    }

    public List<SheetData> getWorkSheets() {
        String sheetName = "Cons. Date Prod."; // TODO: Move also to Main
        return spreadSheet.getMultipleColumns(sheetName, "C", "V", "BH", "EI").stream()
                .skip(3)
                .filter(
                        row -> {
                            var removed = Boolean.TRUE.equals(row.get(1));
                            var spreadSheetName = row.get(3).toString();
                            return !(removed || spreadSheetName.isBlank() || spreadSheetName.equals("0"));
                        }
                ).map(row -> {
                    var productName = row.get(0).toString();
                    var pnk = row.get(2).toString();
                    var name = row.get(3).toString();
                    var id = drive.getFileId(name);
                    return new SheetData(productName, pnk, name, id);
                })
                .filter(it -> it.spreadSheetId != null)
                .toList();
    }
}

