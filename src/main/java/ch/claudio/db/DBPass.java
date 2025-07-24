package ch.claudio.db;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a line from the dbpass.txt file.
 * <p>
 * The file contains tab-separated entries with the following fields:
 * <ul>
 * <li>alias: A unique key used to identify the database.</li>
 * <li>connect: connect string for JDBC.</li>
 * <li>user: optional user.</li>
 * <li>pw: optional password.</li>
 *</ul>
 * <p>
 * @author [Claudio Nieder](mailto:private@claudio.ch)
 * <p>
 * Copyright (C) 2010-2025 Claudio Nieder &lt;private@claudio.ch&gt;,
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
 * License along with this program. If not, see http://www.gnu.org/licenses/.
 */
public record DBPass(String alias, String connect, String user, String pw) {

    /**
     * Create an instance from a line in the password file.
     */
    private static DBPass fromString(String line) {
        String[] fields = line.trim().split("\t");
        int count = fields.length;
        if (count < 2) {
            throw new IllegalArgumentException("The line must have at least two fields separated by a tabulator character");
        }
        return new DBPass(fields[0], fields[1], count > 2 ? fields[2] : null, count > 3 ? fields[3] : null);
    }

    /**
     * Uses getAll and then filters the line for the database with the given alias.
     * <p>
     * It throws an exception if the alias is not present in the file or if the file
     * has more than one line with the same alias.
     */
    public static DBPass findDB(String alias) throws IOException {
        return getAll().stream()
                .filter(dbPass -> dbPass.alias().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No database found with alias: " + alias));
    }

    /**
     * Return all entries from the file ~/Secrets/dbpass.txt
     */
    public static List<DBPass> getAll() throws IOException {
        Path filePath = Paths.get(System.getProperty("user.home")).resolve("Secrets").resolve("dbpass.txt");
        return Files.readAllLines(filePath).stream()
                .filter(line -> !line.isBlank())
                .map(DBPass::fromString)
                .collect(Collectors.toList());
    }

    /**
     * Returns the database name from the connect-string.
     *
     * <p><b>Note:</b> This is known to work only for MySQL and PostgreSQL JDBC connect strings.
     * It might work for others that have a similar syntax.</p>
     */
    public String dbName() {
        return URI.create(connect.substring(5)).getPath().substring(1);
    }
}