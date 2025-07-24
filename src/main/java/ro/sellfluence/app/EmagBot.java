package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.sql.SQLException;

import static ro.sellfluence.app.BackupDB.backupDB;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

/**
 * Class, which executes all the single applications.
 */
public class EmagBot {
    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var dbAlias = arguments.getOption(databaseOptionName, defaultDatabase);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(dbAlias);
        System.out.printf("Back up the %s database.%n", dbAlias);
        backupDB(dbAlias);
        System.out.printf("Syncing the %s database.%n", dbAlias);
        EmagDBApp.fetchFromEmag(mirrorDB, arguments);
        try {
            System.out.printf("Transfer orders from the%s database to the date comenzi sheet.%n", dbAlias);
            PopulateDateComenziFromDB.updateSpreadsheets(mirrorDB);
        } catch (SQLException e) {
            throw new RuntimeException("PopulateDateComenzi endet with an exception ", e);
        }
        System.out.printf("Update the sheet used for customer feedback using %s.%n", dbAlias);
        UpdateEmployeeSheetsFromDB.updateSheets(mirrorDB);
    }
}