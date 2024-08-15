package ch.claudio.db;

/**
 * Class for creating SQL statements.
 * <p>
 * Contains methods that where originally part of the DB class.
 *
 * @author [Claudio Nieder](mailto:private@claudio.ch)
 * <p>
 * Copyright (C) 2024 Claudio Nieder &lt;private@claudio.ch&gt;
 * CH-8045 Zürich
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 * <p>
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public
 * License along with this program. If not, see [http://www.gnu.org/licenses/](http://www.gnu.org/licenses/).
 */
public class SQLBuilder {

    /**
     * Sort ascending
     */
    public static final String ascending = " ASC";

    /**
     * Sort descending
     */
    public static final String descending = " DESC";

    /**
     * Convenience method for building SQL statements.
     *
     * @param columns for the select part.
     * @return SELECT col1,...,colN
     */
    public static String select(String... columns) {
        StringBuilder sb = new StringBuilder("SELECT ");
        addColumnList(sb, columns);
        if (columns.length == 0) {
            sb.append("*");
        }
        return sb.toString();
    }

    /**
     * Create the UPDATE … SET … part of a statement.
     *
     * @param table         to update
     * @param columnAssigns strings of the form column=newValue
     * @return statement
     */
    public static String update(String table, String... columnAssigns) {
        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(table);
        sb.append(" SET ");
        addColumnList(sb, columnAssigns);
        return sb.toString();
    }

    /**
     * Create the INSERT INTO … (…) part of a statement.
     *
     * @param table   into which to insert
     * @param columns column names.
     * @return statement part
     */
    public static String insert(String table, String... columns) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(table);
        sb.append('(');
        addColumnList(sb, columns);
        sb.append(')');
        return sb.toString();
    }

    /**
     * Convenience method for building SQL statements.
     *
     * @param table name
     * @return DELETE FROM table
     */
    public static String delete(String table) {
        return "DELETE FROM " + table;
    }

    /**
     * Convenience method for building SQL statements.
     *
     * @param table name
     * @return FROM table
     */
    public static String from(String table) {
        return " FROM " + table;
    }

    /**
     * Convenience method for building SQL statements.
     *
     * @param table table to join with
     * @param col1  column of this table
     * @param col2  column of another table
     * @return INNER JOIN table ON col1=col2
     */
    public static String join(String table, String col1, String col2) {
        return " INNER JOIN " + table + " ON " + col1 + "=" + col2;
    }

    /**
     * Convenience method for building SQL statements.
     *
     * @param table table to join with
     * @param col1  column of this table
     * @param col2  column of another table
     * @return LEFT JOIN table ON col1=col2
     */
    public static String leftJoin(String table, String col1, String col2) {
        return " LEFT JOIN " + table + " ON " + col1 + "=" + col2;
    }

    /**
     * Generate a blank prefixed where clause.
     *
     * @param condition full where condition clause.
     * @return where part
     */
    public static String where(String condition) {
        return " WHERE " + condition;
    }

    /**
     * Generate a “GROUP BY” clause for a select statement.
     * It starts with a space for easy concatenation.
     *
     * @param columns column names.
     * @return group by part
     */
    public static String group(String... columns) {
        StringBuilder sb = new StringBuilder(" GROUP BY ");
        addColumnList(sb, columns);
        return sb.toString();
    }

    /**
     * Generate an “ORDER BY” clause for a select statement.
     * It starts with a space for easy concatenation.
     *
     * @param columns column names. Each item needs to include its ascending/descending qualifier.
     * @return order clause
     */
    public static String order(String... columns) {
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        addColumnList(sb, columns);
        return sb.toString();
    }

    /**
     * Convenience method for building SQL statements.
     *
     * @param table  name
     * @param column name
     * @return table.column
     */
    public static String col(String table, String column) {
        return table + "." + column;
    }

    /**
     * Generate a blank prefixed limit part for a select statement.
     *
     * @param n number of items.
     * @return limit part
     */
    public static String limit(int n) {
        return " LIMIT " + n;
    }

    /**
     * Add a comma-separated list of the given values to the string builder.
     *
     * @param sb      to append to.
     * @param columns to append.
     */
    private static void addColumnList(StringBuilder sb, String... columns) {
        boolean comma = false;
        for (String col : columns) {
            if (comma) {
                sb.append(",");
            } else {
                comma = true;
            }
            sb.append(col);
        }
    }
}
