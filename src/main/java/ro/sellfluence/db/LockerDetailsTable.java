package ro.sellfluence.db;

import ro.sellfluence.emagapi.LockerDetails;
import ro.sellfluence.support.Logs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

public class LockerDetailsTable {

    private static final Logger debugLogger = Logs.getFileLogger("emag_locker", FINE, 5, 10_000_000);

    /**
     * Inserts or updates locker details in the database. If a locker with the same locker_id does not exist,
     * it inserts the provided locker details. If a locker with the same locker_id exists and the details
     * differ, it updates the locker details in the database.
     *
     * @param db the database connection used to execute the query
     * @param details the locker details to be inserted or updated, containing locker_id, locker_name,
     *                locker_delivery_eligible, and courier_external_office_id
     * @throws SQLException if a database access error occurs
     */
    static void insertOrUpdateLockerDetails(Connection db, final LockerDetails details) throws SQLException {
        var added = insertLockerDetails(db, details);
        if (added == 0) {
            var current = selectLockerDetails(db, details.locker_id());
            if (!details.equals(current)) {
                debugLogger.log(FINE, () -> "LockerDetails differs:%n old: %s%n new: %s%n".formatted(current, details));
                updateLockerDetails(db, details);
            }
        }
    }

    /**
     * Inserts locker details into the database. If a locker with the same locker_id already exists,
     * the insertion is skipped.
     *
     * @param db the database connection used to execute the query
     * @param ld the locker details to be inserted, containing locker_id, locker_name,
     *           locker_delivery_eligible, and courier_external_office_id
     * @return the number of rows affected by the insertion; returns 0 if a locker with the same
     *         locker_id already exists.
     * @throws SQLException if a database access error occurs
     */
    private static int insertLockerDetails(Connection db, LockerDetails ld) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO locker_details (locker_id, locker_name, locker_delivery_eligible, courier_external_office_id) VALUES (?,?,?,?) ON CONFLICT(locker_id) DO NOTHING")) {
            s.setString(1, ld.locker_id());
            s.setString(2, ld.locker_name());
            s.setInt(3, ld.locker_delivery_eligible());
            s.setString(4, ld.courier_external_office_id());
            return s.executeUpdate();
        }
    }

    /**
     * Fetches the details of a locker from the database using the provided locker ID.
     *
     * @param db the database connection used to execute the query
     * @param lockerId the unique identifier of the locker to fetch details for
     * @return a LockerDetails object containing information about the locker if found,
     *         otherwise null if no matching locker exists.
     * @throws SQLException if a database access error occurs
     */
    private static LockerDetails selectLockerDetails(Connection db, String lockerId) throws SQLException {
        LockerDetails lockerDetails = null;
        try (var s = db.prepareStatement("SELECT * FROM locker_details WHERE locker_id = ?")) {
            s.setString(1, lockerId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    lockerDetails = new LockerDetails(rs.getString("locker_id"), rs.getString("locker_name"), rs.getInt("locker_delivery_eligible"), rs.getString("courier_external_office_id"));
                }
            }
        }
        return lockerDetails;
    }

    /**
     * Updates the details of an existing locker in the database based on the provided locker ID.
     *
     * @param db the database connection used to execute the update query
     * @param ld the locker details containing the updated information, which includes locker_id,
     *           locker_name, locker_delivery_eligible, and courier_external_office_id
     * @return the number of rows affected by the update operation
     * @throws SQLException if a database access error occurs
     */
    private static int updateLockerDetails(Connection db, LockerDetails ld) throws SQLException {
        try (var s = db.prepareStatement("UPDATE locker_details SET locker_name = ?, locker_delivery_eligible = ?, courier_external_office_id = ? WHERE locker_id = ?")) {
            s.setString(1, ld.locker_name());
            s.setInt(2, ld.locker_delivery_eligible());
            s.setString(3, ld.courier_external_office_id());
            s.setString(4, ld.locker_id());
            return s.executeUpdate();
        }
    }
}