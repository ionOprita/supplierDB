package ch.claudio.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.Objects;
import java.util.Set;

import static ch.claudio.db.DBPass.findDB;
import static java.sql.Connection.TRANSACTION_READ_COMMITTED;
import static java.sql.Connection.TRANSACTION_REPEATABLE_READ;
import static java.sql.Connection.TRANSACTION_SERIALIZABLE;

/**
 * Class for simplifying database (PostgreSQL) usage.
 * <p>
 * __NOTE__: this creates a new db connection for each transaction. Probably needs to be reworked.
 *
 * @author [Claudio Nieder](mailto:private@claudio.ch)
 * <p>
 * Copyright (C) 2013-2024 Claudio Nieder &lt;private@claudio.ch&gt;
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
public class DB {

    private static final int initialDelay = 10;
    private static final int delayIncrement = 5;
    private static final int maxDelay = 100;
    private static final Set<String> retryHints = Set.of("might succeed if retried", "concurrent update");
    private static final String versionTable = "version_info";
    private static final String versionColumn = "version";
    private static final String dateColumn = "date";
    private final DBPass dbSpec;

    public DB(String alias) throws IOException {
        dbSpec = findDB(alias);
    }

    public interface Instructions {
        void execute(Connection connection) throws SQLException;
    }


    /**
     * Set up the database.
     * The [instructions] give the setup steps for each version of the database.
     * The instructions for going from the version i to the version i+1 are read from instructions[i].
     * An empty database has version 0, and this method creates the version table in this case.
     * This method reads the stored database version from the version table and
     * performs any steps missing to reach the latest version.
     */
    public boolean prepareDB(Instructions... instructions) throws SQLException {
        int dbVersion;
        try {
            dbVersion = singleReadTX(db -> {
                int v;
                try (var s = db.prepareStatement("SELECT MAX(" + versionColumn + ") FROM " + versionTable);
                     var rs = s.executeQuery()) {
                    if (rs.next()) {
                        v = rs.getInt(1);
                    } else {
                        v = 0;
                    }

                } catch (SQLException e) {
                    v = 0;
                }
                return v;
            });
        } catch (Exception _) {
            dbVersion = writeTX(db -> {
                createVersionTable(db);
                return 0;
            });
        }
        final var lastVersionFound = dbVersion;
        return writeTX(db -> {
            var version = lastVersionFound;
            while (version < instructions.length) {
                instructions[version].execute(db);
                version++;
                try {
                    addVersion(db, version);
                } catch (SQLException e) {
                    return false;
                }
            }
            return true;
        });
    }

    public interface TxFunction<OUT> {
        OUT apply(Connection connection) throws SQLException;
    }

    /**
     * Convenience method of [doTX] for the case with a single read operation.
     * Protects only against dirty-reads.
     *
     * @param <OUT> return value of transaction execution.
     * @param tx    pass a method that executes SQL statements and either returns a value to signal all went well,
     *              or throws an exception to signal an error.
     * @return value defined by caller.
     */
    public <OUT> OUT singleReadTX(TxFunction<OUT> tx) throws SQLException {
        return doTX(true, TRANSACTION_READ_COMMITTED, tx);
    }

    /**
     * Convenience method of [doTX] for cases without write operations.
     * Protects only against dirty-reads and non-repeatability.
     *
     * @param <OUT> return value of transaction execution.
     * @param tx    pass a method that executes SQL statements and either returns a value to signal all went well,
     *              or throws an exception to signal an error.
     * @return value defined by caller.
     */
    public <OUT> OUT readTX(TxFunction<OUT> tx) throws SQLException {
        return doTX(true, TRANSACTION_REPEATABLE_READ, tx);
    }


    /**
     * Convenience method of [doTX] for cases using write operations.
     *
     * @param <OUT> return value of transaction execution.
     *              Convenience method of [doTX] for cases without read operations.
     *              Protects only against dirty-reads and non-repeatability.
     * @return value defined by caller.
     */
    public <OUT> OUT writeTX(TxFunction<OUT> tx) throws SQLException {
        return doTX(false, TRANSACTION_SERIALIZABLE, tx);
    }

    private Connection createConnection() throws SQLException {
        if (dbSpec.user() != null) {
            return DriverManager.getConnection(dbSpec.connect(), dbSpec.user(), dbSpec.pw());
        } else {
            return DriverManager.getConnection(dbSpec.connect());
        }
    }

    /**
     * Create a version table in the database [db] and add an entry with number 0
     * representing the version of the empty database.
     */
    private void createVersionTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement(
                "CREATE TABLE " + versionTable + " (" + versionColumn + " integer, " + dateColumn + " timestamp with time zone)"
        )) {
            s.execute();
        }
        addVersion(db, 0);
    }

    /**
     * Add to the database [db] the next version entry with [version] number and time stamp.
     */
    private void addVersion(Connection db, int version) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO " + versionTable + " (" + versionColumn + "," + dateColumn + ") VALUES (?,current_timestamp)")) {
            s.setInt(1, version);
            s.execute();
        }
    }

    private <OUT> OUT doTX(boolean readOnly, int transactionIsolation, TxFunction<OUT> tx) throws SQLException {
        int delay = initialDelay;
        OUT doResult;
        do {
            try (var db = createConnection()) {
                db.setAutoCommit(false);
                try {
                    db.setTransactionIsolation(transactionIsolation);
                } catch (Exception _) {
                    // Use the strictest one if requested level not available
                    db.setTransactionIsolation(TRANSACTION_SERIALIZABLE);
                }
                try {
                    db.setReadOnly(readOnly);
                } catch (Exception _) {
                    // Ignore, this is just a hint.
                }
                OUT txResult = null;
                do {
                    try {
                        txResult = tx.apply(db);
                        Objects.requireNonNull(txResult, "database transaction must never null");
                        db.commit();
                    } catch (SQLTransactionRollbackException e) {
                        delay = waitOrThrow(delay, e);
                    } catch (SQLTransientConnectionException e) {
                        // Needs to reset the connections.
                        db.rollback();
                        throw e;
                    } catch (SQLTransientException e) {
                        db.rollback();
                        delay = waitOrThrow(delay, e);
                    } catch (SQLException e) {
                        db.rollback();
                        if (containsAny(e.getMessage(), retryHints)) {
                            delay = waitOrThrow(delay, e);
                        } else {
                            throw e;
                        }
                    } catch (Exception e) {
                        db.rollback();
                        throw e;
                    }
                } while (txResult == null);
                doResult = txResult;
            } catch (SQLTransientConnectionException e) {
                delay = waitOrThrow(delay, e);
                doResult = null;
            }
        } while (doResult == null);
        return doResult;
    }

    private static boolean containsAny(String str, Set<String> keywords) {
        for (String keyword : keywords) {
            if (str != null && str.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static int waitOrThrow(int delay, Exception e) throws SQLException {
        if (delay > maxDelay) {
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException(e);
        }
        waitFor(delay);
        return delay + delayIncrement;
    }

    private static void waitFor(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException _) {
            // Ignore
        }
    }
}