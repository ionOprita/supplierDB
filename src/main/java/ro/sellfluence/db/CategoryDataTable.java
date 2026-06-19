package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class CategoryDataTable {

    public static final int SOURCE_COLUMN_COUNT = 15;

    public enum CategoryColumn {
        SHEET_INDEX(1, "sheet_index"),
        SUBCATEGORY_COUNTRY(2, "subcategory_country"),
        BIG_CATEGORY(3, "big_category"),
        ESTIMATED_CATEGORY_STORNO_RATE(4, "estimated_category_storno_rate"),
        DIVISION(5, "division"),
        SUPRACATEGORY(6, "supracategory"),
        CATEGORY(7, "category"),
        SUBCATEGORY(8, "subcategory"),
        SUBSUBCATEGORY(9, "subsubcategory"),
        SUPRACATEGORY_COUNTRY(10, "supracategory_country"),
        CATEGORY_COUNTRY(11, "category_country"),
        SUBSUBCATEGORY_COUNTRY(12, "subsubcategory_country"),
        CATEGORY_ID(13, "category_id"),
        SCM_ID(14, "scm_id"),
        DOC_ID(15, "doc_id");

        private final int sheetIndex;
        private final String dbColumn;

        CategoryColumn(int sheetIndex, String dbColumn) {
            this.sheetIndex = sheetIndex;
            this.dbColumn = dbColumn;
        }

        public int sheetIndex() {
            return sheetIndex;
        }

        public String dbColumn() {
            return dbColumn;
        }

        public boolean integerColumn() {
            return this == CATEGORY_ID || this == SCM_ID || this == DOC_ID;
        }

        public static CategoryColumn fromSheetIndex(int sheetIndex) {
            for (var column : values()) {
                if (column.sheetIndex == sheetIndex) {
                    return column;
                }
            }
            throw new IllegalArgumentException("Unknown category sheet index: " + sheetIndex + ".");
        }
    }

    public record CategoryInfo(int sourceRowNumber, List<String> sourceValues) {
        public CategoryInfo {
            if (sourceRowNumber <= 0) {
                throw new IllegalArgumentException("sourceRowNumber must be positive.");
            }
            sourceValues = paddedSourceValues(sourceValues);
        }

        public String subcategoryCountry() {
            return value(CategoryColumn.SUBCATEGORY_COUNTRY);
        }

        public String value(CategoryColumn column) {
            Objects.requireNonNull(column);
            return sourceValues.get(column.sheetIndex() - 1);
        }
    }

    static List<CategoryInfo> getCategoryData(Connection db) throws SQLException {
        var categories = new ArrayList<CategoryInfo>();
        try (var s = db.prepareStatement("""
                SELECT *
                FROM category_sheet_data
                ORDER BY source_row_number
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    categories.add(mapCategoryInfo(rs));
                }
            }
        }
        return categories;
    }

    static int insertCategoryData(Connection db, CategoryInfo category) throws SQLException {
        try (var s = db.prepareStatement(insertSql())) {
            bindInsert(s, category);
            return s.executeUpdate();
        }
    }

    static int updateCategoryData(Connection db, CategoryInfo category) throws SQLException {
        try (var s = db.prepareStatement(updateSql())) {
            var mergedCategory = new CategoryInfo(category.sourceRowNumber(), mergeSourceValuesForUpdate(db, category));
            int index = 1;
            for (var column : CategoryColumn.values()) {
                bindCategoryColumn(s, index++, column, mergedCategory.value(column));
            }
            s.setArray(index++, db.createArrayOf("text", mergedCategory.sourceValues().toArray(String[]::new)));
            s.setInt(index, mergedCategory.sourceRowNumber());
            return s.executeUpdate();
        }
    }

    static int nextSourceRowNumber(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT COALESCE(MAX(source_row_number), 2) + 1
                FROM category_sheet_data
                """);
             var rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int replaceCategoryData(Connection db, List<CategoryInfo> categories) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(categories);

        try (var s = db.prepareStatement("DELETE FROM category_sheet_data")) {
            s.executeUpdate();
        }

        if (categories.isEmpty()) {
            return 0;
        }

        try (var s = db.prepareStatement(insertSql())) {
            for (var category : categories) {
                bindInsert(s, category);
                s.addBatch();
            }
            return insertedRows(s.executeBatch());
        }
    }

    private static CategoryInfo mapCategoryInfo(ResultSet rs) throws SQLException {
        var sourceValues = sourceValues(rs);
        for (var column : CategoryColumn.values()) {
            sourceValues.set(column.sheetIndex() - 1, resultValue(rs, column));
        }
        return new CategoryInfo(rs.getInt("source_row_number"), sourceValues);
    }

    private static String resultValue(ResultSet rs, CategoryColumn column) throws SQLException {
        if (!column.integerColumn()) {
            return rs.getString(column.dbColumn());
        }
        var value = rs.getObject(column.dbColumn(), Integer.class);
        return value == null ? null : value.toString();
    }

    private static ArrayList<String> sourceValues(ResultSet rs) throws SQLException {
        var sqlArray = rs.getArray("source_values");
        if (sqlArray == null) {
            return paddedMutableSourceValues(List.of());
        }
        var values = (String[]) sqlArray.getArray();
        return paddedMutableSourceValues(Arrays.asList(values));
    }

    private static List<String> mergeSourceValuesForUpdate(Connection db, CategoryInfo category) throws SQLException {
        var sourceValues = existingSourceValues(db, category.sourceRowNumber());
        for (var column : CategoryColumn.values()) {
            sourceValues.set(column.sheetIndex() - 1, category.value(column));
        }
        return sourceValues;
    }

    private static ArrayList<String> existingSourceValues(Connection db, int sourceRowNumber) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT source_values
                FROM category_sheet_data
                WHERE source_row_number = ?
                """)) {
            s.setInt(1, sourceRowNumber);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return sourceValues(rs);
                }
            }
        }
        return paddedMutableSourceValues(List.of());
    }

    private static void bindInsert(java.sql.PreparedStatement s, CategoryInfo category) throws SQLException {
        int index = 1;
        s.setInt(index++, category.sourceRowNumber());
        for (var column : CategoryColumn.values()) {
            bindCategoryColumn(s, index++, column, category.value(column));
        }
        s.setArray(index, s.getConnection().createArrayOf("text", category.sourceValues().toArray(String[]::new)));
    }

    private static void bindCategoryColumn(java.sql.PreparedStatement s, int index, CategoryColumn column, String value) throws SQLException {
        if (!column.integerColumn()) {
            s.setObject(index, value);
            return;
        }
        s.setObject(index, parseIntegerValue(value, column.dbColumn()), Types.INTEGER);
    }

    private static Integer parseIntegerValue(String value, String label) {
        if (value == null) {
            return null;
        }
        var normalized = normalizeIntegerValue(value, label);
        if (normalized == null) {
            return null;
        }
        return Integer.parseInt(normalized);
    }

    public static String normalizeIntegerValue(String value, String label) {
        if (value == null) {
            return null;
        }
        var normalized = value.replace(",", "").replace(" ", "").trim();
        if (normalized.isBlank()) {
            return null;
        }
        try {
            Integer.parseInt(normalized);
            return normalized;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer.", e);
        }
    }

    private static int insertedRows(int[] batchResult) {
        int inserted = 0;
        for (int result : batchResult) {
            if (result == Statement.SUCCESS_NO_INFO) {
                inserted++;
            } else if (result > 0) {
                inserted += result;
            }
        }
        return inserted;
    }

    private static String insertSql() {
        var columns = new ArrayList<String>();
        columns.add("source_row_number");
        for (var column : CategoryColumn.values()) {
            columns.add(column.dbColumn());
        }
        columns.add("source_values");

        return """
                INSERT INTO category_sheet_data (
                    %s
                )
                VALUES (
                    %s
                )
                """.formatted(
                String.join(",\n                    ", columns),
                String.join(", ", Collections.nCopies(columns.size(), "?"))
        );
    }

    private static String updateSql() {
        var assignments = new ArrayList<String>();
        for (var column : CategoryColumn.values()) {
            assignments.add(column.dbColumn() + " = ?");
        }
        assignments.add("source_values = ?");
        assignments.add("refreshed_at = current_timestamp");

        return """
                UPDATE category_sheet_data
                SET %s
                WHERE source_row_number = ?
                """.formatted(String.join(",\n                    ", assignments));
    }

    private static List<String> paddedSourceValues(List<String> sourceValues) {
        return Collections.unmodifiableList(paddedMutableSourceValues(sourceValues));
    }

    private static ArrayList<String> paddedMutableSourceValues(List<String> sourceValues) {
        Objects.requireNonNull(sourceValues);
        var padded = new ArrayList<String>(SOURCE_COLUMN_COUNT);
        for (int i = 0; i < SOURCE_COLUMN_COUNT; i++) {
            var value = i < sourceValues.size() ? sourceValues.get(i) : null;
            var column = CategoryColumn.fromSheetIndex(i + 1);
            padded.add(column.integerColumn() ? normalizeIntegerValue(value, column.dbColumn()) : value);
        }
        return padded;
    }
}
