package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.sql.SQLException;

import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

public class UpdateGMVTableInDatabase {

    public static void main(String[] args) throws SQLException, IOException {
        EmagMirrorDB.getEmagMirrorDB(new Arguments(args).getOption(databaseOptionName, defaultDatabase)).updateGMVTable();
    }
}