package ro.sellfluence.app;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Class, which executes all the single applications.
 */
public class EmagBot {
    public static void main(String[] args) {
        System.out.println("Syncing the eMag mirror database.");
        EmagDBApp.main(new String[0]);
        try {
            System.out.println("Transfer orders from the database to the date comenzi sheet.");
            PopulateDateComenziFromDB.main(new String[0]);
        } catch (IOException | SQLException e) {
            throw new RuntimeException("PopulateDateComenzi endet with an exception ", e);
        }
        System.out.println("Update the sheet used for customer feedback.");
        // UpdateEmployeeSheetsFromDB.main(new String[0]);
        UpdateAllSheetsMain.main(new String[0]);
    }
}