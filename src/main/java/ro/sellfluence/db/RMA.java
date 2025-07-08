package ro.sellfluence.db;

import ro.sellfluence.emagapi.AWB;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.emagapi.RequestHistory;
import ro.sellfluence.emagapi.ReturnedProduct;
import ro.sellfluence.emagapi.StatusHistory;
import ro.sellfluence.emagapi.StatusRequest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public class RMA {
    static int addRMAResult(Connection db, RMAResult rmaResult) throws SQLException {
        var emagId = rmaResult.emag_id();
        insertRMAResult(db, rmaResult);
        if (rmaResult.products() != null) {
            for (var product : rmaResult.products()) {
                insertReturnedProduct(db, product, emagId);
            }
        }
        if (rmaResult.awbs() != null) {
            for (var awb : rmaResult.awbs()) {
                insertAWB(db, awb, emagId);
            }
        }
        if (rmaResult.request_history() != null) {
            for (var requestHistory : rmaResult.request_history()) {
                insertRequestHistory(db, requestHistory, emagId);
            }
        }
        if (rmaResult.status_history() != null) {
            for (var statusHistory : rmaResult.status_history()) {
                var historyUUID = UUID.randomUUID();
                insertStatusHistory(db, statusHistory, emagId, historyUUID);
                if (statusHistory.requests() != null) {
                    for (var statusRequest : statusHistory.requests()) {
                        insertStatusRequest(db, statusRequest, historyUUID);
                    }
                }
            }
        }
        return 0;
    }


    private static int insertAWB(Connection db, AWB awb, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO awb (
                reservation_id,
                emag_id
                ) VALUES (?, ?) ON CONFLICT(reservation_id) DO NOTHING""")) {
            s.setInt(1, awb.reservation_id());
            s.setInt(2, emagId);
            return s.executeUpdate();
        }
    }

    private static int insertRequestHistory(Connection db, RequestHistory requestHistory, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO request_history (
                emag_id,
                id,
                req_user,
                action,
                action_type,
                source,
                date
                ) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING""")) {
            s.setInt(1, emagId);
            s.setLong(2,requestHistory.id());
            s.setString(3,requestHistory.user());
            s.setString(4,requestHistory.action());
            s.setString(5,requestHistory.action_type());
            s.setString(6,requestHistory.source());
            s.setTimestamp(7, toTimestamp(requestHistory.date()));
            return s.executeUpdate();
        }
    }

    private static int insertStatusHistory(Connection db, StatusHistory statusHistory, int emagId, UUID uuid) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO status_history (uuid, code, event_date, emag_id) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO NOTHING")) {
            s.setObject(1, uuid);
            s.setString(2, statusHistory.code());
            s.setTimestamp(3, statusHistory.event_date() == null ? null : toTimestamp(statusHistory.event_date()));
            s.setInt(4, emagId);
            return s.executeUpdate();
        }
    }

    private static int insertStatusRequest(Connection db, StatusRequest statusRequest, UUID statusHistoryUuid) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO status_request (amount, created, refund_type, refund_status, rma_id, status_date, status_history_uuid) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setBigDecimal(1, statusRequest.amount());
            s.setTimestamp(2, toTimestamp(statusRequest.created()));
            s.setString(3, statusRequest.refund_type());
            s.setString(4, statusRequest.refund_status());
            s.setString(5, statusRequest.rma_id());
            s.setTimestamp(6, toTimestamp(statusRequest.status_date()));
            s.setObject(7, statusHistoryUuid);
            return s.executeUpdate();
        }
    }
    private static int insertReturnedProduct(Connection db, ReturnedProduct returnedProduct, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO emag_returned_products (
                id,
                product_emag_id,
                product_id, quantity,
                product_name,
                return_reason,
                observations,
                diagnostic,
                reject_reason,
                retained_amount,
                emag_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING""")) {
            s.setInt(1, returnedProduct.id());
            s.setInt(2, returnedProduct.product_emag_id());
            s.setInt(3, returnedProduct.product_id());
            s.setInt(4, returnedProduct.quantity());
            s.setString(5, returnedProduct.product_name());
            s.setInt(6, returnedProduct.return_reason());
            s.setString(7, returnedProduct.observations());
            s.setString(8, returnedProduct.diagnostic());
            s.setObject(9, returnedProduct.reject_reason());
            s.setInt(10, returnedProduct.retained_amount());
            s.setInt(11, emagId);
            return s.executeUpdate();
        }
    }

    private static int insertRMAResult(Connection db, RMAResult rmaResult) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO rma_result (
                is_full_fbe,
                emag_id,
                return_parent_id,
                order_id,
                type,
                is_club,
                is_fast,
                customer_name,
                customer_company,
                customer_phone,
                pickup_country,
                pickup_suburb,
                pickup_city,
                pickup_address,
                pickup_zipcode,
                pickup_locality_id,
                pickup_method,
                customer_account_iban,
                customer_account_bank,
                customer_account_beneficiary,
                replacement_product_emag_id,
                replacement_product_id,
                replacement_product_name,
                replacement_product_quantity,
                observations,
                request_status,
                return_type,
                return_reason,
                date,
                maximum_finalization_date,
                first_pickup_date,
                estimated_product_pickup,
                estimated_product_reception,
                return_tax_value,
                swap,
                return_address_snapshot,
                locker_hash,
                locker_pin,
                locker_pin_interval_end,
                return_address_id,
                country,
                address_type,
                request_status_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(emag_id) DO NOTHING""")) {
            s.setInt(1, rmaResult.is_full_fbe());
            s.setInt(2, rmaResult.emag_id());
            s.setObject(3, rmaResult.return_parent_id());
            s.setString(4, rmaResult.order_id());
            s.setInt(5, rmaResult.type());
            s.setInt(6, rmaResult.is_club());
            s.setInt(7, rmaResult.is_fast());
            s.setString(8, rmaResult.customer_name());
            s.setString(9, rmaResult.customer_company());
            s.setString(10, rmaResult.customer_phone());
            s.setString(11, rmaResult.pickup_country());
            s.setString(12, rmaResult.pickup_suburb());
            s.setString(13, rmaResult.pickup_city());
            s.setString(14, rmaResult.pickup_address());
            s.setString(15, rmaResult.pickup_zipcode());
            s.setInt(16, rmaResult.pickup_locality_id());
            s.setInt(17, rmaResult.pickup_method());
            s.setString(18, rmaResult.customer_account_iban());
            s.setString(19, rmaResult.customer_account_bank());
            s.setString(20, rmaResult.customer_account_beneficiary());
            s.setObject(21, rmaResult.replacement_product_emag_id());
            s.setObject(22, rmaResult.replacement_product_id());
            s.setString(23, rmaResult.replacement_product_name());
            s.setObject(24, rmaResult.replacement_product_quantity());
            s.setString(25, rmaResult.observations());
            s.setInt(26, rmaResult.request_status());
            s.setInt(27, rmaResult.return_type());
            s.setInt(28, rmaResult.return_reason());
            s.setTimestamp(29, toTimestamp(rmaResult.date()));
            s.setTimestamp(30, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().maximum_finalization_date()));
            s.setTimestamp(31, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().first_pickup_date()));
            s.setTimestamp(32, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().estimated_product_pickup()));
            s.setTimestamp(33, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().estimated_product_reception()));
            s.setString(34, rmaResult.return_tax_value());
            s.setString(35, rmaResult.swap());
            s.setString(36, rmaResult.return_address_snapshot());
            s.setString(37, rmaResult.locker() == null ? null : rmaResult.locker().locker_hash());
            s.setString(38, rmaResult.locker() == null ? null : rmaResult.locker().locker_pin());
            s.setTimestamp(39, rmaResult.locker() == null ? null : toTimestamp(rmaResult.locker().locker_pin_interval_end()));
            s.setObject(40, rmaResult.return_address_id());
            s.setString(41, rmaResult.country());
            s.setString(42, rmaResult.address_type());
            s.setObject(43, rmaResult.request_status_reason());
            return s.executeUpdate();
        }
    }
}