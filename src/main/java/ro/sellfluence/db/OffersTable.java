package ro.sellfluence.db;

import ro.sellfluence.emagdashboard.Offer;
import ro.sellfluence.emagdashboard.OffersData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Writes the complete record graph of one vendor's daily offers snapshot. */
public class OffersTable {
    private OffersTable() {
    }

    /** The caller owns the transaction so a failed replacement preserves the previous snapshot. */
    static int storeSnapshot(Connection db, UUID vendorId, LocalDate fetchDate, OffersData data)
            throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(vendorId, "vendorId");
        Objects.requireNonNull(fetchDate, "fetchDate");
        var offers = validateSnapshot(data);
        if (db.getAutoCommit()) {
            throw new SQLException("Storing an offers snapshot requires an active transaction");
        }

        // Upserting the parent locks this vendor/date until commit, serializing concurrent replacements.
        try (var statement = db.prepareStatement("""
                INSERT INTO offers_snapshot (vendor_id, fetch_date, total_number_of_items)
                VALUES (?, ?, ?)
                ON CONFLICT (vendor_id, fetch_date) DO UPDATE
                    SET total_number_of_items = EXCLUDED.total_number_of_items
                """)) {
            statement.setObject(1, vendorId);
            statement.setObject(2, fetchDate);
            statement.setInt(3, offers.size());
            statement.executeUpdate();
        }
        try (var statement = db.prepareStatement("""
                DELETE FROM offers_offer WHERE vendor_id = ? AND fetch_date = ?
                """)) {
            statement.setObject(1, vendorId);
            statement.setObject(2, fetchDate);
            statement.executeUpdate();
        }

        insertOffers(db, vendorId, fetchDate, offers);
        insertStocks(db, vendorId, fetchDate, offers);
        insertPrices(db, vendorId, fetchDate, offers);
        insertLinks(db, vendorId, fetchDate, offers);
        insertSupplyRecommendations(db, vendorId, fetchDate, offers);
        insertMeasurements(db, vendorId, fetchDate, offers);
        return offers.size();
    }

    private static List<Offer> validateSnapshot(OffersData data) {
        Objects.requireNonNull(data, "data");
        var envelope = Objects.requireNonNull(data.offers(), "data.offers");
        var items = Objects.requireNonNull(envelope.items(), "data.offers.items");
        if (envelope.totalNumberOfItems() == null || envelope.totalNumberOfItems() != items.size()) {
            throw new IllegalArgumentException("The offers snapshot must contain all reported items");
        }
        var ids = new HashSet<String>();
        for (var offer : items) {
            if (offer == null || offer.id() == null || offer.id().isBlank()) {
                throw new IllegalArgumentException("Every offer must have a nonblank ID");
            }
            if (!ids.add(offer.id())) {
                throw new IllegalArgumentException("Duplicate offer ID in snapshot: " + offer.id());
            }
            if (offer.measurements() != null && offer.measurements().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Offer measurements must not contain null entries: " + offer.id());
            }
        }
        return List.copyOf(items);
    }

    private static void insertOffers(Connection db, UUID vendorId, LocalDate fetchDate,
                                      List<Offer> offers) throws SQLException {
        try (var statement = prepareInsert(db, "offers_offer", """
                vendor_id, fetch_date, offer_id, is_unsafe_for_site, seller_id, seller_name, eans, category_doc_id,
                category_id, ext_id, valid, inactivation_criticalities, ext_stock, stock2p, stock3p, ext_sale_price,
                ext_original_sale_price, invalidation_reason, ext_status, ext_handling_time, fullfilled_by_emag,
                ext_url, ext_part_number, brand_name, mkt_id, type, offer_properties, min_price, max_price,
                ext_warranty, offer_details_unfair_price, offer_details_locker_eligibility,
                offer_details_supply_lead_time, offer_details_emag_club, offer_details_emag_club_type,
                offer_details_emag_club_merged, green_tax, product_performance_suggested_price,
                product_performance_buy_button_rank, product_performance_needed_stock_next7,
                product_performance_needed_stock_next30, product_performance_depletion_days,
                product_performance_multioffer_offers_count, product_performance_order_value2, rrp, rrp_emag,
                rrp_emag_type, rrp_seller, rrp_type, rrp_badge, is_stock_supply_needed, platform_id, currency,
                doc_product_part_number_key, doc_product_part_number, doc_product_name, doc_product_id,
                doc_product_brand_name, doc_product_brand_mkt_id, product_performance_multioffer_best_price,
                product_performance_multioffer_no_of_reviews, product_performance_multioffer_review_score,
                recycle_warranties_quantity, product_performance_lost_order_value,
                product_performance_days_without_stock, offers_out_of_stock_day, product_performance_order_value1,
                invalidation_reason_date, invalidation_reason_name, invalidation_sub_reason,
                invalidation_sub_reason_name, has_supply_recommendation, measurements_present
                """)) {
            for (var offer : offers) {
                int index = bindOfferKey(statement, vendorId, fetchDate, offer.id());
                statement.setObject(index++, offer.isUnsafeForSite());
                statement.setObject(index++, offer.sellerId());
                statement.setString(index++, offer.sellerName());
                setStrings(statement, index++, offer.eans());
                statement.setObject(index++, offer.categoryDocId());
                statement.setObject(index++, offer.categoryId());
                statement.setString(index++, offer.extId());
                statement.setObject(index++, offer.valid());
                setStrings(statement, index++, offer.inactivationCriticalities());
                statement.setObject(index++, offer.extStock());
                statement.setObject(index++, offer.stock2p());
                statement.setObject(index++, offer.stock3p());
                statement.setBigDecimal(index++, offer.extSalePrice());
                statement.setBigDecimal(index++, offer.extOriginalSalePrice());
                statement.setString(index++, offer.invalidationReason());
                statement.setObject(index++, offer.extStatus());
                statement.setObject(index++, offer.extHandlingTime());
                statement.setObject(index++, offer.fullfilledByEmag());
                statement.setString(index++, offer.extUrl());
                statement.setString(index++, offer.extPartNumber());
                statement.setString(index++, offer.brandName());
                statement.setString(index++, offer.mktId());
                statement.setObject(index++, offer.type());
                setStrings(statement, index++, offer.offerProperties());
                statement.setBigDecimal(index++, offer.minPrice());
                statement.setBigDecimal(index++, offer.maxPrice());
                statement.setObject(index++, offer.extWarranty());
                statement.setBigDecimal(index++, offer.offerDetailsUnfairPrice());
                statement.setObject(index++, offer.offerDetailsLockerEligibility());
                statement.setObject(index++, offer.offerDetailsSupplyLeadTime());
                statement.setObject(index++, offer.offerDetailsEmagClub());
                statement.setObject(index++, offer.offerDetailsEmagClubType());
                statement.setObject(index++, offer.offerDetailsEmagClubMerged());
                statement.setBigDecimal(index++, offer.greenTax());
                statement.setBigDecimal(index++, offer.productPerformanceSuggestedPrice());
                statement.setObject(index++, offer.productPerformanceBuyButtonRank());
                statement.setObject(index++, offer.productPerformanceNeededStockNext7());
                statement.setObject(index++, offer.productPerformanceNeededStockNext30());
                statement.setObject(index++, offer.productPerformanceDepletionDays());
                statement.setObject(index++, offer.productPerformanceMultiofferOffersCount());
                statement.setBigDecimal(index++, offer.productPerformanceOrderValue2());
                statement.setBigDecimal(index++, offer.rrp());
                statement.setBigDecimal(index++, offer.rrpEmag());
                statement.setObject(index++, offer.rrpEmagType());
                statement.setBigDecimal(index++, offer.rrpSeller());
                statement.setObject(index++, offer.rrpType());
                statement.setObject(index++, offer.rrpBadge());
                statement.setObject(index++, offer.isStockSupplyNeeded());
                statement.setObject(index++, offer.platformId());
                statement.setString(index++, offer.currency());
                statement.setString(index++, offer.docProductPartNumberKey());
                statement.setString(index++, offer.docProductPartNumber());
                statement.setString(index++, offer.docProductName());
                statement.setObject(index++, offer.docProductId());
                statement.setString(index++, offer.docProductBrandName());
                statement.setObject(index++, offer.docProductBrandMktId());
                statement.setBigDecimal(index++, offer.productPerformanceMultiofferBestPrice());
                statement.setObject(index++, offer.productPerformanceMultiofferNoOfReviews());
                statement.setBigDecimal(index++, offer.productPerformanceMultiofferReviewScore());
                statement.setObject(index++, offer.recycleWarrantiesQuantity());
                statement.setBigDecimal(index++, offer.productPerformanceLostOrderValue());
                statement.setObject(index++, offer.productPerformanceDaysWithoutStock());
                statement.setObject(index++, offer.offersOutOfStockDay());
                statement.setBigDecimal(index++, offer.productPerformanceOrderValue1());
                statement.setObject(index++, offer.invalidationReasonDate());
                statement.setString(index++, offer.invalidationReasonName());
                statement.setString(index++, offer.invalidationSubReason());
                statement.setString(index++, offer.invalidationSubReasonName());
                statement.setObject(index++, offer.hasSupplyRecommendation());
                statement.setBoolean(index, offer.measurements() != null);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertStocks(Connection db, UUID vendorId, LocalDate fetchDate,
                                      List<Offer> offers) throws SQLException {
        try (var statement = prepareInsert(db, "offers_stock", """
                vendor_id, fetch_date, offer_id, category, status_type, status_name, inactivation_reason, hotness_type,
                hotness_name, rrp_badge_color, rrp_guideline, rrp_value, vat_id, start_date, property_types,
                place_order_to_supplier_until, invalidation_reason_label
                """)) {
            for (var offer : offers) {
                var details = offer.offerStock();
                if (details == null) continue;
                int index = bindOfferKey(statement, vendorId, fetchDate, offer.id());
                statement.setString(index++, details.category());
                statement.setString(index++, details.statusType());
                statement.setString(index++, details.statusName());
                statement.setString(index++, details.inactivationReason());
                statement.setString(index++, details.hotnessType());
                statement.setString(index++, details.hotnessName());
                statement.setString(index++, details.rrpBadgeColor());
                statement.setString(index++, details.rrpGuideline());
                statement.setBigDecimal(index++, details.rrpValue());
                statement.setObject(index++, details.vatId());
                statement.setObject(index++, details.startDate());
                setStrings(statement, index++, details.propertyTypes());
                statement.setObject(index++, details.placeOrderToSupplierUntil());
                statement.setString(index++, details.invalidationReasonLabel());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertPrices(Connection db, UUID vendorId, LocalDate fetchDate,
                                      List<Offer> offers) throws SQLException {
        try (var statement = prepareInsert(db, "offers_price", """
                vendor_id, fetch_date, offer_id, is_ean_mandatory, is_emag_club_eligible, status_details
                """)) {
            for (var offer : offers) {
                var details = offer.offerPrice();
                if (details == null) continue;
                int index = bindOfferKey(statement, vendorId, fetchDate, offer.id());
                statement.setObject(index++, details.isEanMandatory());
                statement.setObject(index++, details.isEmagClubEligible());
                statement.setString(index++, details.statusDetails());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertLinks(Connection db, UUID vendorId, LocalDate fetchDate,
                                      List<Offer> offers) throws SQLException {
        try (var statement = prepareInsert(db, "offers_links", """
                vendor_id, fetch_date, offer_id, edit, seller, details
                """)) {
            for (var offer : offers) {
                var details = offer.links();
                if (details == null) continue;
                int index = bindOfferKey(statement, vendorId, fetchDate, offer.id());
                statement.setString(index++, details.edit());
                statement.setString(index++, details.seller());
                statement.setString(index++, details.details());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertSupplyRecommendations(Connection db, UUID vendorId, LocalDate fetchDate,
                                      List<Offer> offers) throws SQLException {
        try (var statement = prepareInsert(db, "offers_supply_recommendation", """
                vendor_id, fetch_date, offer_id, recommended_qty_replenish, replenishment_period, days_until_oos,
                estimated_days_until_oos, days_in_stock_l35, units_sold_l35, units_forecast_n35, units_in_transit,
                stock_in_current_day
                """)) {
            for (var offer : offers) {
                var details = offer.supplyRecommendationData();
                if (details == null) continue;
                int index = bindOfferKey(statement, vendorId, fetchDate, offer.id());
                statement.setObject(index++, details.recommendedQtyReplenish());
                statement.setObject(index++, details.replenishmentPeriod());
                statement.setObject(index++, details.daysUntilOos());
                statement.setObject(index++, details.estimatedDaysUntilOos());
                statement.setObject(index++, details.daysInStockL35());
                statement.setObject(index++, details.unitsSoldL35());
                statement.setObject(index++, details.unitsForecastN35());
                statement.setObject(index++, details.unitsInTransit());
                statement.setObject(index++, details.stockInCurrentDay());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertMeasurements(Connection db, UUID vendorId, LocalDate fetchDate,
                                      List<Offer> offers) throws SQLException {
        try (var statement = prepareInsert(db, "offers_measurement", """
                vendor_id, fetch_date, offer_id, position, weight, height, length, width
                """)) {
            for (var offer : offers) {
                if (offer.measurements() == null) continue;
                for (int position = 0; position < offer.measurements().size(); position++) {
                    var measurement = offer.measurements().get(position);
                    int index = bindOfferKey(statement, vendorId, fetchDate, offer.id());
                    statement.setInt(index++, position);
                    statement.setBigDecimal(index++, measurement.weight());
                    statement.setBigDecimal(index++, measurement.height());
                    statement.setBigDecimal(index++, measurement.length());
                    statement.setBigDecimal(index++, measurement.width());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static PreparedStatement prepareInsert(Connection db, String table, String columns) throws SQLException {
        var placeholders = String.join(", ", Collections.nCopies(columns.split(",").length, "?"));
        return db.prepareStatement("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")");
    }

    private static int bindOfferKey(PreparedStatement statement, UUID vendorId, LocalDate fetchDate, String offerId)
            throws SQLException {
        statement.setObject(1, vendorId);
        statement.setObject(2, fetchDate);
        statement.setString(3, offerId);
        return 4;
    }

    private static void setStrings(PreparedStatement statement, int index, List<String> values) throws SQLException {
        if (values == null) {
            statement.setNull(index, Types.ARRAY);
        } else {
            statement.setArray(index, statement.getConnection().createArrayOf("text", values.toArray(String[]::new)));
        }
    }
}
