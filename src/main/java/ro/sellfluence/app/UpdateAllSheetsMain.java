package ro.sellfluence.app;

public class UpdateAllSheetsMain {
    public static void main(String[] args) {
        new TransferFromEmagToSheets(
                "sellfluence1",
                "Testing Coding 2024 - Date Produse & Angajati",
                "Cons. Date Prod."
        )
                .transferFromEmagToSheet("emag", "emag2");
    }
}
