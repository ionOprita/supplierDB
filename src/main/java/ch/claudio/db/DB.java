package ch.claudio.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.function.Function;

public class DB {
    private static final int initialDelay = 10;
    private static final int delayIncrement = 5;
    private static final int maxDelay = 100;
    private static final String versionTable = "version_info";
    private static final String versionColumn = "version";
    private static final String dateColumn = "date";
    private final DBPass dbSpec;

    public DB(String alias) throws IOException {
        this.dbSpec = DBPass.findDB(alias);
    }

    public boolean prepareDB(Runnable... instructions) throws SQLException {
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
        } catch (Exception e) {
            var success = writeTX(db -> {
                try {
                    return createVersionTable(db);
                } catch (SQLException ex) {
                    return false;
                }
            });
            if (!success) return false;
            dbVersion = 0;
        }
        final var lastVersionFound = dbVersion;
        writeTX(db -> {
            var version = lastVersionFound;
            while (version < instructions.length) {
                instructions[version].run();
                version++;
                try {
                    addVersion(db, version);
                } catch (SQLException e) {
                    return false;
                }
            }
            return true;
        });
        return false;
    }

    public <OUT> OUT singleReadTX(Function<Connection, OUT> tx) throws SQLException {
        return doTX(true, Connection.TRANSACTION_READ_COMMITTED, tx);
    }

    public <OUT> OUT readTX(Function<Connection, OUT> tx) throws SQLException {
        return doTX(true, Connection.TRANSACTION_REPEATABLE_READ, tx);
    }

    public <OUT> OUT writeTX(Function<Connection, OUT> tx) throws SQLException {
        return doTX(false, Connection.TRANSACTION_SERIALIZABLE, tx);
    }

    private final String[] retryHints = {"might succeed if retried", "concurrent update"};

    private Connection createConnection() throws SQLException {
        if (dbSpec.user != null) {
            return DriverManager.getConnection(dbSpec.connect, dbSpec.user, dbSpec.pw);
        } else {
            return DriverManager.getConnection(dbSpec.connect);
        }
    }

    private boolean createVersionTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("CREATE TABLE " + versionTable + " (" + versionColumn + " integer, " + dateColumn + " timestamp with time zone)")) {
            s.execute();
        }
        addVersion(db, 0);
        return true;
    }

    private void addVersion(Connection db, int version) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO " + versionTable + " (" + versionColumn + "," + dateColumn + ") VALUES (?,current_timestamp)")) {
            s.setInt(1, version);
            s.execute();
        }
    }

    private <OUT> OUT doTX(boolean readOnly, int transactionIsolation, Function<Connection, OUT> tx) throws SQLException {
        int delay = initialDelay;
        OUT doResult;
        do {
            try (var db = createConnection()) {
                    db.setAutoCommit(false);
                    try {
                        db.setTransactionIsolation(transactionIsolation);
                    } catch (Exception ignored) {
                        db.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    }
                    try {
                        db.setReadOnly(readOnly);
                    } catch (Exception ignored) {
                    }
                    do {
                        try {
                            doResult = tx.apply(db);
                            if (doResult == null) {
                                throw new IllegalArgumentException("Database transaction must never be null");
                            }
                            db.commit();
                        } catch (SQLTransactionRollbackException e) {
                            delay = waitOrThrow(e, delay);
                            doResult = null;
                        } catch (SQLTransientConnectionException e) {
                            db.rollback();
                            throw e;
                        } catch (SQLTransientException e) {
                            db.rollback();
                            delay = waitOrThrow(e, delay);
                            doResult = null;
                        } catch (SQLException e) {
                            db.rollback();
                            if (containsAny(e.getMessage(), retryHints)) {
                                delay = waitOrThrow(e, delay);
                                doResult = null;
                            } else {
                                throw e;
                            }
                        } catch (Exception e) {
                            db.rollback();
                            throw e;
                        }
                    } while (doResult == null);
            } catch (SQLTransientConnectionException e) {
                delay = waitOrThrow(e, delay);
                doResult = null;
            }
        } while (doResult == null);
        return doResult;
    }

    private static boolean containsAny(String str, String[] keywords) {
        for (String keyword : keywords) {
            if (str != null && str.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static int waitOrThrow(Exception e, int delay) throws SQLException {
        if (delay > maxDelay) {
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException(e);
        }
        waitFor(delay);
        delay += delayIncrement;
        return delay;
    }

    private static void waitFor(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException ignored) {
        }
    }
}
