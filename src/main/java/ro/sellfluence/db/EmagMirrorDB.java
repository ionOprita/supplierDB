package ro.sellfluence.db;

import ch.claudio.db.DB;
import ro.sellfluence.emagapi.OrderResult;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class EmagMirrorDB {

    public static void main(String[] args) throws IOException, SQLException {
        getDatabase("emag");
    }

    public static DB getDatabase(String alias) throws SQLException, IOException {
        var db = new DB(alias);
        db.prepareDB(EmagMirrorDBVersion1::version1);
        return db;
    }

    public static void insertOrder(OrderResult orderResult) {

    }
}
