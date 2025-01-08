package ro.sellfluence.app;

import com.google.api.client.auth.oauth2.TokenResponseException;
import ro.sellfluence.emagapi.EmagApi;

import static java.util.logging.Level.WARNING;
import static ro.sellfluence.googleapi.Credentials.tokenStorePath;

public class UpdateAllSheetsMain {
    public static void main(String[] args) {
        EmagApi.setAPILogLevel(WARNING);
        try {
            new TransferFromEmagToSheets(
                    "sellfluence1",
                    "2025 - Date produse & angajati",
                    "Cons. Date Prod."
            )
                    .transferFromEmagToSheet("sellfluence", "sellfusion", "zoopieconcept", "zoopieinvest", "zoopiesolutions", "judios", "koppel", "koppelfbe");
        } catch (Exception e) {
            if (e instanceof TokenResponseException) {
                System.out.println("Try to delete " + tokenStorePath);
            } else {
                System.out.println("Maybe you can try to delete " + tokenStorePath);
                throw e;
            }
        }
    }
}
