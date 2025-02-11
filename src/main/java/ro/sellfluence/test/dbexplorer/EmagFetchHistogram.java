package ro.sellfluence.test.dbexplorer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public record EmagFetchHistogram(long days, int count) {
    public static List<EmagFetchHistogram> getHistogram(Connection db) throws SQLException {
        var histogram = new ArrayList<EmagFetchHistogram>();
        String query = """
                        SELECT
                            EXTRACT(DAY FROM AGE(NOW(), fetch_time)) AS days_old,
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
