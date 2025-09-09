package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.app.BackupDB.backupDB;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

/**
 * Class, which executes all the single applications.
 */
public class EmagBot {
    private static final Logger logger = Logs.getConsoleLogger("EmagBot", INFO);

    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var dbAlias = arguments.getOption(databaseOptionName, defaultDatabase);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(dbAlias);
        logger.log(INFO, "Back up the %s database.%n".formatted(dbAlias));
        backupDB(dbAlias);
        logger.log(INFO, "Syncing the %s database.%n".formatted(dbAlias));
        EmagDBApp.fetchFromEmag(mirrorDB, arguments);
        logger.log(INFO, "Update the product table of database %s.".formatted(dbAlias));
        try {
            PopulateProductsTableFromSheets.updateProductTable(mirrorDB);
        } catch (Exception e) {
            logger.log(WARNING, "Updating the product table ended with an exception. The Bot will continue, but additional problems might occur later.", e);
        }
        logger.log(INFO, "Transfer orders from the %s database to the date comenzi sheet.".formatted(dbAlias));
        try {
            PopulateDateComenziFromDB.updateSpreadsheets(mirrorDB);
        } catch (SQLException e) {
            logger.log(WARNING, "Updating the date comenzi sheet ended with an exception.", e);
            throw new RuntimeException("PopulateDateComenzi ended with an exception ", e);
        }
        logger.log(INFO, "Update Stornos and Returns for the current month from the %s database to the Cent. Ret. Sto. sheet.".formatted(dbAlias));
        try {
            PopulateStornoAndReturns.updateSpreadsheets(mirrorDB);
        } catch (SQLException e) {
            logger.log(WARNING, "Updating the Storno and Return sheet ended with an exception.", e);
            throw new RuntimeException("PopulateStornoAndReturns ended with an exception ", e);
        }
        logger.log(INFO, "Update the sheet used for customer feedback using %s.".formatted(dbAlias));
        UpdateEmployeeSheetsFromDB.updateSheets(mirrorDB);
    }
}