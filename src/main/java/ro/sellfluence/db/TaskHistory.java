package ro.sellfluence.db;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only attempt history and daily coverage; neither missing days nor old attempts are synthesized as successes. */
public final class TaskHistory {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Europe/Bucharest");
    private static final String OFFERS_PREFIX = "Fetch offers for ";

    private TaskHistory() {
    }

    public record Attempt(long id, String taskName,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime startedAt,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime finishedAt,
                          String status, String error) {
    }

    public record HistoryPage(List<Attempt> items, long total, int page, int pageSize) {
        public HistoryPage {
            items = List.copyOf(items);
        }
    }

    public record CollectionDay(String taskName, UUID vendorId, String vendorName,
                                @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date,
                                String status, @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime fetchedAt,
                                int attempts, String lastError) {
    }

    /** Unknown or ambiguous account mappings are left for the normal fetch to report, without blocking startup. */
    public static int registerOfferSources(Connection db, List<String> taskNames) throws SQLException {
        var inserted = 0;
        try (var statement = db.prepareStatement("""
                WITH candidates AS (SELECT id, COUNT(*) OVER () AS matches FROM vendor WHERE account = ?)
                INSERT INTO offer_collection_sources (task_name, vendor_id, enabled_on)
                SELECT ?, id, (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Bucharest')::date
                FROM candidates WHERE matches = 1
                ON CONFLICT (task_name) DO NOTHING
                """)) {
            for (var name : taskNames.stream().distinct().toList()) {
                if (!name.startsWith(OFFERS_PREFIX) || name.length() == OFFERS_PREFIX.length()) continue;
                statement.setString(1, name.substring(OFFERS_PREFIX.length()));
                statement.setString(2, name);
                inserted += statement.executeUpdate();
            }
        }
        return inserted;
    }

    private static final String HISTORY_FILTER = """
            WHERE (?::text IS NULL OR task_name = ?)
              AND (?::text IS NULL OR status = ?)
              AND (?::timestamptz IS NULL OR started_at >= ?)
              AND (?::timestamptz IS NULL OR started_at < ?)
            """;

    /** Date filters include whole Bucharest calendar days, independently of the database session zone. */
    public static HistoryPage readHistory(Connection db, String taskName, String status,
                                          LocalDate from, LocalDate to, int page, int pageSize) throws SQLException {
        if (page < 1 || pageSize < 1) throw new IllegalArgumentException("Positive page and pageSize are required");
        long total;
        try (var statement = db.prepareStatement("SELECT COUNT(*) FROM task_runs " + HISTORY_FILTER)) {
            bindHistoryFilter(statement, taskName, status, from, to);
            try (var rows = statement.executeQuery()) {
                rows.next();
                total = rows.getLong(1);
            }
        }
        var items = new ArrayList<Attempt>();
        try (var statement = db.prepareStatement("""
                SELECT id, task_name, started_at, finished_at, status, error FROM task_runs
                """ + HISTORY_FILTER + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?")) {
            bindHistoryFilter(statement, taskName, status, from, to);
            statement.setInt(9, pageSize);
            statement.setLong(10, (long) (page - 1) * pageSize);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new Attempt(rows.getLong("id"), rows.getString("task_name"),
                            rows.getObject("started_at", OffsetDateTime.class),
                            rows.getObject("finished_at", OffsetDateTime.class),
                            rows.getString("status"), rows.getString("error")));
                }
            }
        }
        return new HistoryPage(items, total, page, pageSize);
    }

    private static void bindHistoryFilter(PreparedStatement statement, String taskName, String status,
                                           LocalDate from, LocalDate to) throws SQLException {
        statement.setString(1, taskName);
        statement.setString(2, taskName);
        statement.setString(3, status);
        statement.setString(4, status);
        var start = from == null ? null : from.atStartOfDay(REPORT_ZONE).toOffsetDateTime();
        var end = to == null ? null : to.plusDays(1).atStartOfDay(REPORT_ZONE).toOffsetDateTime();
        statement.setObject(5, start);
        statement.setObject(6, start);
        statement.setObject(7, end);
        statement.setObject(8, end);
    }

    /** Every enabled calendar day is visible, including downtime with no attempt and no snapshot. */
    public static List<CollectionDay> readCollectionDays(Connection db, String taskName,
                                                         LocalDate from, LocalDate to, LocalDate today)
            throws SQLException {
        var result = new ArrayList<CollectionDay>();
        try (var statement = db.prepareStatement("""
                WITH bounds AS (
                    SELECT s.*, v.vendor_name,
                           GREATEST(s.enabled_on, COALESCE(?::date, s.enabled_on)) AS first_day,
                           LEAST(COALESCE(?::date, ?::date), ?::date) AS last_day
                    FROM offer_collection_sources s JOIN vendor v ON v.id = s.vendor_id
                    WHERE (?::text IS NULL OR s.task_name = ?)
                ), days AS (
                    SELECT b.*, b.first_day + d.day_offset AS report_date
                    FROM bounds b
                    CROSS JOIN LATERAL generate_series(0, b.last_day - b.first_day) d(day_offset)
                )
                SELECT d.task_name, d.vendor_id, d.vendor_name, d.report_date,
                       CASE WHEN s.visits_fetched_at IS NOT NULL THEN 'COLLECTED'
                            WHEN d.report_date < ?::date THEN 'MISSING'
                            WHEN a.attempts > 0 THEN 'RETRYING' ELSE 'PENDING' END AS status,
                       s.visits_fetched_at, a.attempts, e.error AS last_error
                FROM days d
                LEFT JOIN offers_snapshot s ON s.vendor_id = d.vendor_id AND s.fetch_date = d.report_date
                CROSS JOIN LATERAL (
                    SELECT COUNT(*)::integer AS attempts FROM task_runs r
                    WHERE r.task_name = d.task_name
                      AND r.started_at >= (d.report_date::timestamp AT TIME ZONE 'Europe/Bucharest')
                      AND r.started_at < ((d.report_date + 1)::timestamp AT TIME ZONE 'Europe/Bucharest')
                ) a
                LEFT JOIN LATERAL (
                    SELECT error FROM task_runs r
                    WHERE r.task_name = d.task_name AND r.status IN ('FAILED', 'INTERRUPTED')
                      AND r.started_at >= (d.report_date::timestamp AT TIME ZONE 'Europe/Bucharest')
                      AND r.started_at < ((d.report_date + 1)::timestamp AT TIME ZONE 'Europe/Bucharest')
                    ORDER BY r.started_at DESC, r.id DESC LIMIT 1
                ) e ON TRUE
                ORDER BY d.report_date DESC, d.vendor_name, d.task_name
                """)) {
            statement.setObject(1, from);
            statement.setObject(2, to);
            statement.setObject(3, today);
            statement.setObject(4, today);
            statement.setString(5, taskName);
            statement.setString(6, taskName);
            statement.setObject(7, today);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new CollectionDay(rows.getString("task_name"), rows.getObject("vendor_id", UUID.class),
                            rows.getString("vendor_name"), rows.getObject("report_date", LocalDate.class),
                            rows.getString("status"), rows.getObject("visits_fetched_at", OffsetDateTime.class),
                            rows.getInt("attempts"), rows.getString("last_error")));
                }
            }
        }
        return List.copyOf(result);
    }
}
