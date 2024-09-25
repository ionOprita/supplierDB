package ro.sellfluence.app;

import ro.sellfluence.emagapi.EmagApi;

import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

public class UpdateAllSheetsMain {
    public static void main(String[] args) {
        Logger.getLogger(EmagApi.class.getName()).setLevel(WARNING);
        new TransferFromEmagToSheets(
                "sellfluence1",
                "Testing Coding 2024 - Date Produse & Angajati",
                "Cons. Date Prod."
        )
                .transferFromEmagToSheet("emag", "emag2");
    }
}
