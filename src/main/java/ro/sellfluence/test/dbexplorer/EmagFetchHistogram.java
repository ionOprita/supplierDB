package ro.sellfluence.test.dbexplorer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

//TODO: EXTRACT(DAY FROM is wrong, it gives the day part but ignores months and years.
public record EmagFetchHistogram(long days, int count) {
    public static List<EmagFetchHistogram> getHistogram(Connection db) throws SQLException {
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
}
