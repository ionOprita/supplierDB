package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;

import static ro.sellfluence.support.UsefulMethods.isBlank;
import static ro.sellfluence.support.UsefulMethods.toDate;
import static ro.sellfluence.support.UsefulMethods.toLocalDateTime;
import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public record EmagFetchLog(
        String emagLogin,
        LocalDate date,
        LocalDateTime fetchTime,
        String error) {

    static int insertEmagLog(Connection db, String account, LocalDate date, LocalDateTime fetchTime, String error) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO emag_fetch_log (emag_login, date, fetch_time, error) VALUES (?, ?, ?, ?) ON CONFLICT(emag_login, date) DO NOTHING")) {
            s.setString(1, account);
            s.setDate(2, toDate(date));
            s.setTimestamp(3, toTimestamp(fetchTime));
            s.setString(4, error);
            return s.executeUpdate();
        }
    }

    static int updateEmagLog(Connection db, String account, LocalDate date, LocalDateTime fetchTime, String error) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_fetch_log SET fetch_time=?, error=? WHERE emag_login=? AND date=?")) {
            s.setTimestamp(1, toTimestamp(fetchTime));
            s.setString(2, error);
            s.setString(3, account);
            s.setDate(4, toDate(date));
            return s.executeUpdate();
        }
    }

    public record EmagFetchHistogram(long days, int count) {}

    static ArrayList<EmagFetchHistogram> getFetchHistogram(Connection db) throws SQLException {
        var histogram = new ArrayList<EmagFetchHistogram>();
        String query = """
                        SELECT
                            NOW()::DATE - fetch_time::DATE AS days_old,
                            COUNT(*) AS count
                        FROM
                            emag_fetch_log
                        GROUP BY
                            days_old
                        ORDER BY
                            days_old;
                """;
        try (PreparedStatement stmt = db.prepareStatement(query)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    histogram.add(new EmagFetchHistogram(
                            rs.getLong(1),
                            rs.getInt(2)
                    ));
                }
            }
        }
        return histogram;
    }

    /**
     * Get all entries from emag_fetch_log which overlap with the given range.
     *
     * @param db database
     * @param account emag account
     * @param date of day needed.
     */
    static EmagFetchLog getEmagLog(Connection db, String account, LocalDate date) throws SQLException {
        try (var s = db.prepareStatement("SELECT fetch_time, error FROM emag_fetch_log WHERE emag_login = ? AND date = ?")) {
            s.setString(1, account);
            s.setDate(2, toDate(date));
            try (var rs = s.executeQuery()) {
                EmagFetchLog fetchLog = null;
                if (rs.next()) {
                    fetchLog = new EmagFetchLog(account, date, toLocalDateTime(rs.getTimestamp(1)), rs.getString(2));
                }
                if (rs.next()) {
                    throw new RuntimeException("Unexpected second entry in the emag_fetch_log for %s on %s.".formatted(account, date));
                }
                return fetchLog;
            }
        }
    }

    static int deleteFetchLogsBefore(Connection db, LocalDate oldestDay) {
        try (var s = db.prepareStatement("DELETE FROM emag_fetch_log WHERE date < ?")) {
            s.setDate(1, toDate(oldestDay));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Report if the log indicates that this day is done.
     *
     * @param fetchStatus for a particular account and day or null if non was found.
     * @return true if the entry existed and had a blank error message.
     */
    public static boolean isDone(EmagFetchLog fetchStatus) {
        return fetchStatus != null && isBlank(fetchStatus.error());
    }
}