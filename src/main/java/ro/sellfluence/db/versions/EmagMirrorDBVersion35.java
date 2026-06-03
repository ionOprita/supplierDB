package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion35 {
    static void version35(Connection db) throws SQLException {
        createCategorySheetDataTable(db);
    }

    private static void createCategorySheetDataTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE category_sheet_data (
                    source_row_number INTEGER PRIMARY KEY,
                    sheet_index TEXT,
                    subcategory_country TEXT NOT NULL,
                    big_category TEXT,
                    estimated_category_storno_rate TEXT,
                    division TEXT,
                    supracategory TEXT,
                    category TEXT,
                    subcategory TEXT,
                    subsubcategory TEXT,
                    supracategory_country TEXT,
                    category_country TEXT,
                    subsubcategory_country TEXT,
                    category_id TEXT,
                    scm_id TEXT,
                    doc_id TEXT,
                    source_values TEXT[] NOT NULL,
                    refreshed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp
                );
                """);
        executeStatement(db, """
                CREATE INDEX category_sheet_data_subcategory_country_idx
                    ON category_sheet_data (subcategory_country);
                """);
        executeStatement(db, """
                CREATE INDEX category_sheet_data_scm_id_idx
                    ON category_sheet_data (scm_id)
                    WHERE scm_id IS NOT NULL;
                """);
        executeStatement(db, """
                CREATE INDEX category_sheet_data_doc_id_idx
                    ON category_sheet_data (doc_id)
                    WHERE doc_id IS NOT NULL;
                """);
    }
}
