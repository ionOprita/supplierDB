package ro.sellfluence.emagapi;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Logs;
import ro.sellfluence.support.UsefulMethods;
import ro.sellfluence.support.UserPassword;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class EmagAccounts {
    private static final Logger logger = Logs.getConsoleAndFileLogger("EmagAccounts", INFO, 10, 1_000_000);

    private static final Set<String> invalidAlias = new HashSet<>();

    /**
     * Marks an account as invalid.
     *
     * @param alias of the account.
     */
    public static void invalidAccount(String alias) {
        logger.log(SEVERE, "Invalid account " + alias + " is not returned for future requests.");
        invalidAlias.add(alias);
    }

    /**
     * Removes the mark on the account, so it is retried again.
     *
     * @param alias of the account.
     */
    public static void retryAccount(String alias) {
        invalidAlias.remove(alias);
    }

    /**
     * Returns all valid accounts.
     *
     * @param mirrorDB database for accessing the vendor table.
     * @return list of valid accounts.
     */
    public static List<UserPassword> getAccounts(EmagMirrorDB mirrorDB) {
        try {
            List<UserPassword> accounts = mirrorDB.getAllVendors().stream().<UserPassword>mapMulti((vendor, b) -> {
                if (!invalidAlias.contains(vendor.account())) {
                    var userPW = UserPassword.findAlias(vendor.account());
                    if (userPW != null) {
                        b.accept(userPW);
                    }
                }
            }).toList();
            if (accounts.isEmpty()) {
                logger.log(WARNING, "No accounts found.");
            } else {
                var loginNames = accounts.stream().map(UserPassword::getUsername).collect(Collectors.joining(", "));
                logger.log(INFO, "Returning accounts %s.".formatted(loginNames));
            }
            return accounts;
        } catch (SQLException e) {
            logger.log(WARNING, "Error reading vendors. Returning an empty list.");
            return List.of();
        }
    }

    /**
     * Returns all valid accounts having an OTP authenticator.
     *
     * @param mirrorDB database for accessing the vendor table.
     * @return list of valid accounts.
     */
    public static List<UserPassword> getOTPAccounts(EmagMirrorDB mirrorDB) {
        try {
            List<UserPassword> accounts = mirrorDB.getAllVendors().stream().<UserPassword>mapMulti((vendor, b) -> {
                if (!invalidAlias.contains(vendor.account())) {
                    var userPW = UserPassword.findAlias(vendor.account());
                    if (userPW != null && !UsefulMethods.isBlank(userPW.getOtpAuth())) {
                        b.accept(userPW);
                    }
                }
            }).toList();
            if (accounts.isEmpty()) {
                logger.log(WARNING, "No OTP accounts found.");
            } else {
                var loginNames = accounts.stream().map(UserPassword::getUsername).collect(Collectors.joining(", "));
                logger.log(INFO, "Returning OTP accounts %s.".formatted(loginNames));
            }
            return accounts;
        } catch (SQLException e) {
            logger.log(WARNING, "Error reading vendors. Returning an empty list.");
            return List.of();
        }
    }
}
