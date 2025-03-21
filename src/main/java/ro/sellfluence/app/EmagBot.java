package ro.sellfluence.app;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Class, which executes all the single applications.
 */
public class EmagBot {
    public static void main(String[] args) {
        EmagDBApp.main(new String[0]);
        try {
            PopulateDateComenziFromDB.main(new String[0]);
        } catch (IOException | SQLException e) {
            throw new RuntimeException("PopulateDateComenzi endet with an exception ", e);
        }
        UpdateAllSheetsMain.main(new String[0]);
    }
}