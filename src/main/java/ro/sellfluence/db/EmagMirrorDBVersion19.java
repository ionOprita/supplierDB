package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion19 {
    /**
     * Change the product table to depend on the product code as identification.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version19(Connection db) throws SQLException {
        dropExistingDataAndForeignKey(db);
        dropProductTableConstraintsAndId(db);
        makeNewPrimaryKey(db);
        changeReferenceInGMVTable(db);
    }

    private static void dropExistingDataAndForeignKey(Connection db) throws SQLException {
        executeStatement(db, """
                DELETE FROM gmv;
                """);
        executeStatement(db, """
                DELETE FROM product;
                """);
        executeStatement(db, """
                ALTER TABLE gmv DROP CONSTRAINT gmv_product_id_fkey;
                """);
    }

    private static void dropProductTableConstraintsAndId(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product DROP CONSTRAINT product_pkey;
                """);
        executeStatement(db, """
                ALTER TABLE product DROP CONSTRAINT product_emag_pnk_key;
                """);
        executeStatement(db, """
                ALTER TABLE product DROP CONSTRAINT unique_name;
                """);
        executeStatement(db, """
                ALTER TABLE product ALTER COLUMN name DROP NOT NULL;
                """);
        executeStatement(db, """
                ALTER TABLE product ALTER COLUMN emag_pnk DROP NOT NULL;
                """);
        executeStatement(db, """
                ALTER TABLE product DROP COLUMN id;
                """);
    }

    private static void makeNewPrimaryKey(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product ADD PRIMARY KEY (product_code);
                """);
    }

    private static void changeReferenceInGMVTable(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE gmv DROP COLUMN product_id;
                """);
        executeStatement(db, """
                ALTER TABLE gmv ADD COLUMN product_code character varying(255) NOT NULL;
                """);
        executeStatement(db, """
                ALTER TABLE gmv ADD CONSTRAINT gmv_product_code_fkey FOREIGN KEY (product_code) REFERENCES product(product_code);
                """);
    }
}