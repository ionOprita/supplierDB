package ro.sellfluence.app;

import com.google.api.client.auth.oauth2.TokenResponseException;
import ro.sellfluence.emagapi.EmagApi;

import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static ro.sellfluence.googleapi.Credentials.tokenStorePath;

public class UpdateAllSheetsMain {
    public static void main(String[] args) {
        Logger.getLogger(EmagApi.class.getName()).setLevel(WARNING);
        try {
            new TransferFromEmagToSheets(
                    "sellfluence1",
                    "Testing Coding 2024 - Date Produse & Angajati",
                    "Cons. Date Prod."
            )
                    .transferFromEmagToSheet("emag", "emag2");
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
