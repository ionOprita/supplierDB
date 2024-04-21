package ro.sellfluence.app;

public class UpdateAllSheets {
    public static void main(String[] args) {
        new TransferFromEmagToSheets("sellfluence1", "Testing Coding 2024 - Date Produse & Angajati")
                .transferFromEmagToSheet();
    }
}
