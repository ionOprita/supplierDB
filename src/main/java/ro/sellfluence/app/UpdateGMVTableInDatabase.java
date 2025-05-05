package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;

import java.io.IOException;
import java.sql.SQLException;

public class UpdateGMVTableInDatabase {

    public static void main(String[] args) throws SQLException, IOException {
        EmagMirrorDB.getEmagMirrorDB("emagLocal").updateGMVTable();
    }
}