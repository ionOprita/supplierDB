package ro.sellfluence.db;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static ro.sellfluence.support.UsefulMethods.toLocalDateTime;
import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public record Vendor (UUID id, String name, boolean isFBE, String companyName, String account, LocalDateTime lastFetch) {
    private static final Logger logger = Logger.getLogger(Vendor.class.getName());

    /**
     * Add or update vendor.
     *
     * @param db      the database connection.
     * @param name    vendor name.
     * @param account eMAG account used when accessing the API.
     * @return the UUID of the vendor.
     * @throws SQLException if a database access error occurs.
     */
    static @NonNull UUID insertOrUpdateVendor(Connection db, String name, String account) throws SQLException {
        UUID id = selectVendorIdByName(db, name);
        if (id == null) {
            id = UUID.randomUUID();
            try (var s = db.prepareStatement("INSERT INTO vendor (id, vendor_name, isfbe, account) VALUES (?,?,?,?)")) {
                s.setObject(1, id);
                s.setString(2, name);
                // Zoopie Invest is a special case, it does not contain FBE in the name, but is FBE.
                s.setBoolean(3, name.contains("FBE") || name.contains("Zoopie Invest"));
                s.setString(4, account);
                s.executeUpdate();
            }
        }
        return id;
    }


    /**
     * Retrieves the UUID of a vendor from the database based on the provided vendor name.
     * If multiple vendors are found with the same name, a warning is logged.
     *
     * @param db the database connection.
     * @param name the name of the vendor whose UUID is to be retrieved.
     * @return the UUID of the vendor if found, or null if no vendor with the specified name exists.
     * @throws SQLException if a database access error occurs.
     */
    static @Nullable UUID selectVendorIdByName(Connection db, String name) throws SQLException {
        UUID id = null;
        try (var s = db.prepareStatement("SELECT id FROM vendor WHERE vendor_name=?")) {
            s.setString(1, name);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    id = rs.getObject(1, UUID.class);
                }
                if (rs.next()) {
                    logger.log(SEVERE, "More than one vendor with the same name ???");
                }
            }
        }
        return id;
    }

    /**
     * Retrieves the last fetch time for a specific account from the database.
     * If multiple records are found for the same account, a warning is logged.
     *
     * @param db the database connection to be used for the query.
     * @param account the account identifier whose last fetch time is to be retrieved.
     * @return the last fetch time as a LocalDateTime if found, or null if no record exists for the specified account.
     * @throws SQLException if a database access error occurs.
     */
    static @Nullable LocalDateTime selectFetchTimeByAccount(Connection db, String account) throws SQLException {
        Timestamp timestamp = null;
        try (var s = db.prepareStatement("SELECT last_fetch FROM vendor WHERE account=?")) {
            s.setString(1, account);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    timestamp = rs.getTimestamp(1);
                }
                if (rs.next()) {
                    logger.log(SEVERE, "More than one vendor with the same name ???");
                }
            }
        }
        return toLocalDateTime(timestamp);
    }

    /**
     * Generate a map from vendor UUID to vendor name.
     *
     * @param db database connection.
     * @return map.
     * @throws SQLException if a database access error occurs.
     */
    static @NonNull Map<UUID, String> selectAllVendors(Connection db) throws SQLException {
        var vendors = new HashMap<UUID, String>();
        try (var s = db.prepareStatement("SELECT id, vendor_name FROM vendor")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    vendors.put(
                            rs.getObject("id", UUID.class),
                            rs.getString("vendor_name")
                    );
                }
            }
        }
        return vendors;
    }

    /**
     * Generate a map from vendor UUID to vendor name.
     *
     * @param db database connection.
     * @return map.
     * @throws SQLException if a database access error occurs.
     */
    static @NonNull List<Vendor> getVendors(Connection db) throws SQLException {
        var vendors = new ArrayList<Vendor>();
        try (var s = db.prepareStatement("SELECT id, vendor_name, isfbe, company_name, account, last_fetch FROM vendor;")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    vendors.add(
                            new Vendor(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("vendor_name"),
                                    rs.getBoolean("isfbe"),
                                    rs.getString("company_name"),
                                    rs.getString("account"),
                                    toLocalDateTime(rs.getTimestamp("last_fetch"))
                            )
                    );
                }
            }
        }
        return vendors;
    }

    /**
     * Updates the last fetch time for a specific account in the vendor database.
     *
     * @param db the database connection to be used for executing the update.
     * @param account the account identifier whose last fetch time is to be updated.
     * @param fetchTime the new fetch time to set for the account.
     * @return the number of rows affected by the update statement.
     * @throws SQLException if a database access error occurs.
     */
    static int updateFetchTimeByAccount(Connection db, String account, LocalDateTime fetchTime) throws SQLException {
        try (var s = db.prepareStatement("UPDATE vendor SET last_fetch=? WHERE account=?")) {
            s.setTimestamp(1, toTimestamp(fetchTime));
            s.setString(2, account);
            return s.executeUpdate();
        }
    }
}