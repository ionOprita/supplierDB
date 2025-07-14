package ro.sellfluence.apphelper;

import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Logs;

import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

public class GetOverview {

    private final DriveAPI drive;
    private final SheetsAPI spreadSheet;
    private final String overviewSheetName;

    private static final Logger warningLogger = Logs.getConsoleAndFileLogger("GetOverviewWarnings", WARNING, 10, 1_000_000);

    public GetOverview(String appName, String spreadSheetName, String sheetName) {
        drive = DriveAPI.getDriveAPI(appName);
        spreadSheet = SheetsAPI.getSpreadSheetByName(appName, spreadSheetName);
        overviewSheetName = sheetName;
    }

    public record SheetData(
            String productName,
            String pnk,
            String spreadSheetName,
            String spreadSheetId
    ) {
    }

    public List<SheetData> getWorkSheets() {
        return spreadSheet.getMultipleColumns(overviewSheetName, "C", "V", "BH", "EI").stream()
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
                    if (id==null) {
                        warningLogger.log(WARNING, "Spreadsheet %s for the product %s with PNK %s was not found, it will be ignored.".formatted(name, productName, pnk));
                    }
                    return new SheetData(productName, pnk, name, id);
                })
                .filter(it -> it.spreadSheetId != null)
                .toList();
    }
}