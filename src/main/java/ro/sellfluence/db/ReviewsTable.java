package ro.sellfluence.db;

import ro.sellfluence.emagsiteapi.EmagUrl;
import ro.sellfluence.emagsiteapi.Review;
import ro.sellfluence.emagsiteapi.ReviewComment;
import ro.sellfluence.emagsiteapi.ReviewImage;
import ro.sellfluence.emagsiteapi.ReviewPrice;
import ro.sellfluence.emagsiteapi.ReviewProduct;
import ro.sellfluence.emagsiteapi.ReviewUser;
import ro.sellfluence.emagsiteapi.ReviewsResponse;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/** Stores reviews for their actual product while retaining missing reviews and comments. */
public final class ReviewsTable {
    private static final Logger logger = Logger.getLogger(ReviewsTable.class.getName());
    private static final JsonMapper FINGERPRINT_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
            .enable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
            .build();
    private static final String OWNER_COLUMNS = "pnk, review_id, owner_type, owner_id, comment_id";
    private static final String OWNER_KEY_COLUMNS = "pnk, review_id, owner_type, owner_id";
    private static final List<String> FETCH_CHILD_TABLES = List.of("review_fetch_metadata",
            "review_suggested_question", "review_rating_distribution", "review_characteristic_rating", "review_sort_option");

    private ReviewsTable() {
    }

    /** Counts refer to reviews; retained comments do not contribute to these totals. */
    public record StoreResult(int inserted, int changed, int unchanged, int notReturned) {
    }

    /** One stored review and its review-level user/product values for the sheet export. */
    public record ExportReview(long reviewId, Long productFamilyId, String pnk, Integer rating,
                               String optionValue, String created, String content, String clientName,
                               Long clientId, String clientHash, String clientType, String published,
                               String moderator) {
    }

    static List<ExportReview> readReviewExportRows(Connection db) throws SQLException {
        var export = new ArrayList<ExportReview>();
        try (var statement = db.prepareStatement("""
                SELECT r.review_id, r.product_family_id,
                       r.pnk AS export_pnk,
                       r.rating, p.family_characteristic_value, r.created, r.content,
                       u.name AS client_name, u.user_id AS client_id, u.hash AS client_hash,
                       r.client_type, r.published, r.moderated_by
                FROM review AS r
                LEFT JOIN review_product AS p
                  ON p.pnk = r.pnk AND p.review_id = r.review_id
                 AND p.owner_type = 'review' AND p.owner_id = r.review_id
                LEFT JOIN review_user AS u
                  ON u.pnk = r.pnk AND u.review_id = r.review_id
                 AND u.owner_type = 'review' AND u.owner_id = r.review_id
                ORDER BY r.published, r.pnk
                """); var result = statement.executeQuery()) {
            while (result.next()) {
                export.add(new ExportReview(
                        result.getLong("review_id"), result.getObject("product_family_id", Long.class),
                        result.getString("export_pnk"), result.getObject("rating", Integer.class),
                        result.getString("family_characteristic_value"), result.getString("created"),
                        result.getString("content"), result.getString("client_name"),
                        result.getObject("client_id", Long.class), result.getString("client_hash"),
                        result.getString("client_type"), result.getString("published"),
                        result.getString("moderated_by")));
            }
        }
        return List.copyOf(export);
    }

    private record CommentKey(long reviewId, long commentId) {
    }

    private record Owner(String pnk, long reviewId, Long commentId) {
        String type() { return commentId == null ? "review" : "comment"; }
        long id() { return commentId == null ? reviewId : commentId; }
        List<Object> key() { return row(pnk, reviewId, type(), id()); }
        List<Object> values() { return row(pnk, reviewId, type(), id(), commentId); }
    }

    private record ChangedOwner(Owner owner, ReviewUser user, ReviewProduct product, List<ReviewImage> pictures) {
    }

    /** The caller owns the transaction, including rollback if validation or any write fails. */
    static StoreResult storeReviews(Connection db, String pnk, Instant fetchedAt, ReviewsResponse response)
            throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        if (pnk == null || pnk.isBlank()) throw new IllegalArgumentException("A nonblank PNK is required");
        pnk = pnk.strip();
        validateResponse(response);
        if (db.getAutoCommit()) throw new SQLException("Storing reviews requires an active transaction");
        // PostgreSQL stores microseconds. Normalize before comparisons and all writes.
        var timestamp = fetchedAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        var matchingReviews = new ArrayList<Review>();
        var reviewFingerprints = new HashMap<Long, String>();
        var commentFingerprints = new HashMap<CommentKey, String>();
        for (var review : response.data().items()) {
            var productPnk = review.product() == null ? null : review.product().partNumberKey();
            if (productPnk == null || productPnk.isBlank()) {
                logger.warning("Skipping review %d for PNK %s because its product PNK is missing"
                        .formatted(review.id(), pnk));
                continue;
            }
            if (!pnk.equals(productPnk)) continue;
            matchingReviews.add(review);
            reviewFingerprints.put(review.id(), fingerprint(review));
            for (var comment : review.comments()) {
                commentFingerprints.put(new CommentKey(review.id(), comment.id()), fingerprint(comment));
            }
        }

        Long firstReviewId = response.data().firstItem() == null ? null : response.data().firstItem().id();
        if (!reviewFingerprints.containsKey(firstReviewId)) firstReviewId = null;
        lockFetch(db, pnk, timestamp, response, firstReviewId);
        var previousReviews = readReviewFingerprints(db, pnk);
        var previousComments = readCommentFingerprints(db, pnk);
        var changedOwners = new ArrayList<ChangedOwner>();
        int inserted = 0;
        int changed = 0;
        int unchanged = 0;
        try (var rows = new BatchRows(db)) {
            int reviewPosition = 0;
            for (var review : matchingReviews) {
                String hash = reviewFingerprints.get(review.id());
                String previous = previousReviews.remove(review.id());
                if (previous == null) inserted++;
                else if (!previous.equals(hash)) changed++;
                else unchanged++;
                storeReview(rows, pnk, timestamp, review, reviewPosition++, hash);
                if (!hash.equals(previous)) {
                    changedOwners.add(new ChangedOwner(new Owner(pnk, review.id(), null),
                            review.user(), review.product(), review.pictures()));
                }
                int commentPosition = 0;
                for (var comment : review.comments()) {
                    var key = new CommentKey(review.id(), comment.id());
                    String commentHash = commentFingerprints.get(key);
                    storeComment(rows, pnk, review.id(), timestamp, comment, commentPosition++, commentHash);
                    if (!commentHash.equals(previousComments.get(key))) {
                        changedOwners.add(new ChangedOwner(new Owner(pnk, review.id(), comment.id()),
                                comment.user(), comment.product(), List.of()));
                    }
                }
            }
            rows.flush();
        }
        replaceChangedChildren(db, changedOwners);
        replaceFetchDetails(db, pnk, timestamp, response, firstReviewId);
        return new StoreResult(inserted, changed, unchanged, previousReviews.size());
    }

    private static void validateResponse(ReviewsResponse response) {
        if (response == null || response.code() != 200 || response.data() == null) {
            throw new IllegalArgumentException("A successful review response is required");
        }
        var data = response.data();
        if (data.count() < 0 || data.count() != data.items().size()) {
            throw new IllegalArgumentException("The review response must contain all reported reviews");
        }
        var ids = new HashSet<Long>();
        for (var review : data.items()) {
            if (review == null || review.id() == null || !ids.add(review.id())) {
                throw new IllegalArgumentException("Review IDs must be nonnull and unique within a product fetch");
            }
            var commentIds = new HashSet<Long>();
            for (var comment : review.comments()) {
                if (comment == null || comment.id() == null || !commentIds.add(comment.id())) {
                    throw new IllegalArgumentException("Comment IDs must be nonnull and unique within review " + review.id());
                }
            }
        }
        if (data.firstItem() != null && (data.firstItem().id() == null || !ids.contains(data.firstItem().id()))) {
            throw new IllegalArgumentException("The first review must refer to one of the returned reviews");
        }
    }

    private static String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(FINGERPRINT_MAPPER.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void lockFetch(Connection db, String pnk, OffsetDateTime fetchedAt, ReviewsResponse response,
                                  Long firstReviewId)
            throws SQLException {
        // The parent insert or row lock serializes concurrent fetches of the same PNK.
        try (var rows = new BatchRows(db)) {
            storeFetch(rows, pnk, fetchedAt, response, firstReviewId, "ON CONFLICT (pnk) DO NOTHING");
            rows.flush();
        }
        try (var statement = db.prepareStatement(
                "SELECT last_successful_fetch_at FROM review_fetch WHERE pnk = ? FOR UPDATE")) {
            statement.setString(1, pnk);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing review fetch row for " + pnk);
                if (result.getObject(1, OffsetDateTime.class).isAfter(fetchedAt)) {
                    throw new IllegalArgumentException("An older review fetch cannot replace a more recent fetch for " + pnk);
                }
            }
        }
    }

    private static Map<Long, String> readReviewFingerprints(Connection db, String pnk) throws SQLException {
        var hashes = new HashMap<Long, String>();
        try (var statement = db.prepareStatement("SELECT review_id, content_fingerprint FROM review WHERE pnk = ?")) {
            statement.setString(1, pnk);
            try (var result = statement.executeQuery()) {
                while (result.next()) hashes.put(result.getLong(1), result.getString(2));
            }
        }
        return hashes;
    }

    private static Map<CommentKey, String> readCommentFingerprints(Connection db, String pnk) throws SQLException {
        var hashes = new HashMap<CommentKey, String>();
        try (var statement = db.prepareStatement(
                "SELECT review_id, comment_id, content_fingerprint FROM review_comment WHERE pnk = ?")) {
            statement.setString(1, pnk);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    hashes.put(new CommentKey(result.getLong(1), result.getLong(2)), result.getString(3));
                }
            }
        }
        return hashes;
    }

    private static void storeReview(BatchRows rows, String pnk, OffsetDateTime timestamp, Review review,
                                    int position, String hash) throws SQLException {
        var values = row(pnk, review.id(), position, review.content(), review.isActive(), review.moderationStatus(),
                review.moderatedBy(), review.created(), review.modified(), review.published(), review.deleted(),
                review.reportReason());
        addUrl(values, review.editUrl());
        addUrl(values, review.viewUrl());
        values.addAll(row(review.type(), review.title(), review.contentNoTags(), review.rating(), review.isBought(),
                review.votes(), review.currentCustomerHasVoted(), review.brandId(), review.categoryId(), review.offerId(),
                review.clientType(), review.clientTypeInfo(), review.productDocId(), review.productFamilyId(),
                review.allowCommentsLikes(), review.hasMedia(), hash, timestamp, timestamp, timestamp));
        rows.upsertObserved("review", "pnk, review_id", """
                pnk, review_id, position, content, is_active, moderation_status, moderated_by,
                created, modified, published, deleted,
                report_reason, edit_url_present, edit_url_path, edit_url_desktop_base, edit_url_mobile_base,
                view_url_present, view_url_path, view_url_desktop_base, view_url_mobile_base,
                type, title, content_no_tags, rating, is_bought, votes, current_customer_has_voted,
                brand_id, category_id, offer_id, client_type, client_type_info, product_doc_id, product_family_id,
                allow_comments_likes, has_media, content_fingerprint, first_fetched_at, last_fetched_at, last_changed_at
                """, values);
    }

    private static void storeComment(BatchRows rows, String pnk, long reviewId, OffsetDateTime timestamp,
                                     ReviewComment comment, int position, String hash) throws SQLException {
        var values = row(pnk, reviewId, comment.id(), position, comment.content(), comment.isActive(),
                comment.moderationStatus(), comment.created(), comment.modified(), comment.published(),
                comment.deleted() == null ? null : comment.deleted().toString());
        addUrl(values, comment.editUrl());
        addUrl(values, comment.viewUrl());
        values.addAll(row(comment.type(), comment.contentNoTags(), comment.parentId(), comment.isOfficial(),
                hash, timestamp, timestamp, timestamp));
        rows.upsertObserved("review_comment", "pnk, review_id, comment_id", """
                pnk, review_id, comment_id, position, content, is_active, moderation_status,
                created, modified, published, deleted,
                edit_url_present, edit_url_path, edit_url_desktop_base, edit_url_mobile_base,
                view_url_present, view_url_path, view_url_desktop_base, view_url_mobile_base,
                type, content_no_tags, parent_id, is_official,
                content_fingerprint, first_fetched_at, last_fetched_at, last_changed_at
                """, values);
    }

    private static void replaceChangedChildren(Connection db, List<ChangedOwner> owners) throws SQLException {
        try (var deletes = new BatchRows(db)) {
            for (var changed : owners) {
                for (String table : List.of("review_image", "review_user", "review_product")) {
                    deletes.statement("DELETE FROM " + table
                            + " WHERE pnk = ? AND review_id = ? AND owner_type = ? AND owner_id = ?", changed.owner().key());
                }
            }
            deletes.flush();
        }
        try (var rows = new BatchRows(db)) {
            for (var changed : owners) {
                storeUser(rows, changed.owner(), changed.user());
                storeProduct(rows, changed.owner(), changed.product());
                for (int position = 0; position < changed.pictures().size(); position++) {
                    storeImage(rows, changed.owner(), "picture", position, changed.pictures().get(position));
                }
            }
            rows.flush();
        }
    }

    private static void storeUser(BatchRows rows, Owner owner, ReviewUser user) throws SQLException {
        if (user == null) return;
        var values = owner.values();
        values.addAll(row(user.id(), user.hash(), user.name(), user.nickname(), user.email(), user.isOfficial()));
        addUrl(values, user.url());
        var avatar = user.userAvatar();
        values.addAll(row(avatar != null, avatar == null ? null : avatar.initials(),
                avatar == null ? null : avatar.path(), avatar == null ? null : avatar.backgroundColor()));
        rows.insert("review_user", OWNER_COLUMNS + """
                , user_id, hash, name, nickname, email, is_official,
                url_present, url_path, url_desktop_base, url_mobile_base,
                avatar_present, avatar_initials, avatar_path, avatar_background_color
                """, values);
        if (avatar != null) storeImage(rows, owner, "avatar", 0, avatar.image());
    }

    private static void storeProduct(BatchRows rows, Owner owner, ReviewProduct product) throws SQLException {
        if (product == null) return;
        var values = owner.values();
        values.addAll(row(product.id(), product.name(), product.sefName(),
                product.firstFamilyCharacteristicValue()));
        addUrl(values, product.url());
        var offer = product.offer();
        values.addAll(row(offer != null, offer == null ? null : offer.id()));
        rows.insert("review_product", OWNER_COLUMNS + """
                , product_id, name, sef_name, family_characteristic_value,
                url_present, url_path, url_desktop_base, url_mobile_base,
                offer_present, offer_id
                """, values);
        if (offer != null) storePrice(rows, owner, offer.price());
        storeImage(rows, owner, "product", 0, product.image());
    }

    private static void storePrice(BatchRows rows, Owner owner, ReviewPrice price) throws SQLException {
        if (price == null) return;
        var values = owner.key();
        values.addAll(row(price.current(), price.isMin(), price.isMax(), price.legal(), price.prefix(), price.suffix(),
                price.isVisible(), price.net(), price.initial()));
        var discount = price.discount();
        values.addAll(row(discount != null, discount == null ? null : discount.type(),
                discount == null ? null : discount.absolute(), discount == null ? null : discount.percent(),
                discount == null ? null : discount.isSpecial(), discount == null ? null : discount.isVisible(),
                discount == null ? null : discount.isRestrictedFromView(), discount == null ? null : discount.isMax(),
                discount == null ? null : discount.label(), discount == null ? null : discount.labeledAsDiscount()));
        var currency = price.currency();
        var name = currency == null ? null : currency.name();
        values.addAll(row(currency != null, currency == null ? null : currency.id(), name != null,
                name == null ? null : name.defaultName(), name == null ? null : name.display()));
        var retail = price.recommendedRetailPrice();
        values.addAll(row(retail != null, retail == null ? null : retail.amount(),
                retail == null ? null : retail.isVisible(), retail == null ? null : retail.label(),
                retail == null ? null : retail.tooltip()));
        var lowest = price.lowestPrice30Days();
        values.addAll(row(lowest != null, lowest == null ? null : lowest.amount(),
                lowest == null ? null : lowest.isVisible(), lowest == null ? null : lowest.tooltip(),
                lowest == null ? null : lowest.info()));
        rows.insert("review_price", OWNER_KEY_COLUMNS + """
                , current, is_min, is_max, legal, prefix, suffix, is_visible, net, initial,
                discount_present, discount_type, discount_absolute, discount_percent, discount_is_special,
                discount_is_visible, discount_is_restricted_from_view, discount_is_max, discount_label,
                discount_labeled_as_discount, currency_present, currency_id, currency_name_present,
                currency_name_default, currency_name_display,
                recommended_retail_price_present, recommended_retail_price_amount, recommended_retail_price_is_visible,
                recommended_retail_price_label, recommended_retail_price_tooltip,
                lowest_price_30_days_present, lowest_price_30_days_amount, lowest_price_30_days_is_visible,
                lowest_price_30_days_tooltip, lowest_price_30_days_info
                """, values);
    }

    private static void storeImage(BatchRows rows, Owner owner, String kind, int position, ReviewImage image)
            throws SQLException {
        if (image == null) return;
        var values = owner.values();
        values.addAll(row(kind, position, image.original()));
        rows.insert("review_image", OWNER_COLUMNS + ", image_kind, position, original", values);
        for (int index = 0; index < image.resizedImages().size(); index++) {
            var size = image.resizedImages().get(index);
            var sizeValues = owner.key();
            sizeValues.addAll(row(kind, position, index, size.size(), size.url()));
            rows.insert("review_image_size", OWNER_KEY_COLUMNS + ", image_kind, image_position, position, size, url",
                    sizeValues);
        }
    }

    private static void replaceFetchDetails(Connection db, String pnk, OffsetDateTime timestamp, ReviewsResponse response,
                                            Long firstReviewId)
            throws SQLException {
        try (var deletes = new BatchRows(db)) {
            for (String table : FETCH_CHILD_TABLES) deletes.statement("DELETE FROM " + table + " WHERE pnk = ?", row(pnk));
            deletes.flush();
        }
        try (var rows = new BatchRows(db)) {
            storeFetch(rows, pnk, timestamp, response, firstReviewId, null);
            if (response.metadata() != null) {
                for (int i = 0; i < response.metadata().size(); i++) {
                    rows.insert("review_fetch_metadata", "pnk, position, value", row(pnk, i, response.metadata().get(i)));
                }
            }
            var data = response.data();
            for (int i = 0; i < data.suggestedQuestions().size(); i++) {
                rows.insert("review_suggested_question", "pnk, position, question", row(pnk, i, data.suggestedQuestions().get(i)));
            }
            for (var rating : data.ratingDistribution().entrySet()) {
                rows.insert("review_rating_distribution", "pnk, rating, count", row(pnk, rating.getKey(), rating.getValue()));
            }
            for (int i = 0; i < data.reviewCharacteristicsAverageRating().size(); i++) {
                var rating = data.reviewCharacteristicsAverageRating().get(i);
                rows.insert("review_characteristic_rating", "pnk, position, review_characteristics_id, name, average_rating",
                        row(pnk, i, rating.reviewCharacteristicsId(), rating.name(), rating.averageRating()));
            }
            for (int i = 0; i < data.sortOptions().size(); i++) {
                var option = data.sortOptions().get(i);
                rows.insert("review_sort_option", "pnk, position, name, direction, placeholder, selected, default_option",
                        row(pnk, i, option.name(), option.direction(), option.placeholder(), option.selected(), option.defaultOption()));
            }
            rows.flush();
        }
    }

    private static void storeFetch(BatchRows rows, String pnk, OffsetDateTime timestamp,
                                   ReviewsResponse response, Long firstReviewId, String conflictClause) throws SQLException {
        var data = response.data();
        var values = row(pnk, timestamp, response.code(), data.count(), firstReviewId,
                data.summary(), data.family_id(), response.notifications(), response.metadata() != null);
        addUrl(values, data.addUrl());
        addUrl(values, data.viewUrl());
        var messages = data.messages();
        values.addAll(row(data.positiveRatingPercentage(), data.boughtCount(), data.boughtCountFilter(), data.boughtCountMessage(),
                data.positiveRatingPercentageMessage(), messages != null, messages == null ? null : messages.tagInfoText(),
                messages == null ? null : messages.tagInfoModalText(), messages == null ? null : messages.tagFilterText()));
        String columns = """
                pnk, last_successful_fetch_at, response_code, total_count, first_review_id, summary, family_id,
                notifications, metadata_present, add_url_present, add_url_path, add_url_desktop_base, add_url_mobile_base,
                view_url_present, view_url_path, view_url_desktop_base, view_url_mobile_base,
                positive_rating_percentage, bought_count, bought_count_filter, bought_count_message,
                positive_rating_percentage_message, messages_present, tag_info_text, tag_info_modal_text, tag_filter_text
                """;
        rows.insert("review_fetch", columns,
                conflictClause == null ? updateClause("review_fetch", "pnk", columns, false) : conflictClause, values);
    }

    private static void addUrl(List<Object> values, EmagUrl url) {
        values.addAll(row(url != null, url == null ? null : url.path(),
                url == null ? null : url.desktopBase(), url == null ? null : url.mobileBase()));
    }

    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String updateClause(String table, String keys, String columns, boolean observed) {
        var keyColumns = List.of(keys.split(",\\s*"));
        var updates = new ArrayList<String>();
        for (String raw : columns.split(",")) {
            String column = raw.strip();
            if (keyColumns.contains(column) || observed && column.equals("first_fetched_at")) continue;
            if (observed && column.equals("last_changed_at")) {
                updates.add("last_changed_at = CASE WHEN " + table + ".content_fingerprint IS DISTINCT FROM EXCLUDED.content_fingerprint"
                        + " THEN EXCLUDED.last_changed_at ELSE " + table + ".last_changed_at END");
            } else {
                updates.add(column + " = EXCLUDED." + column);
            }
        }
        return "ON CONFLICT (" + keys + ") DO UPDATE SET " + String.join(", ", updates);
    }

    /** One JDBC batch per SQL shape. Callers flush parent rows before dependent rows. */
    private static final class BatchRows implements AutoCloseable {
        private final Connection db;
        private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();

        BatchRows(Connection db) { this.db = db; }

        void insert(String table, String columns, List<Object> values) throws SQLException {
            insert(table, columns, "", values);
        }

        void upsertObserved(String table, String keys, String columns, List<Object> values) throws SQLException {
            insert(table, columns, updateClause(table, keys, columns, true), values);
        }

        void insert(String table, String columns, String conflict, List<Object> values) throws SQLException {
            int count = columns.split(",").length;
            if (count != values.size()) throw new IllegalArgumentException("Column/value mismatch for " + table);
            statement("INSERT INTO " + table + " (" + columns + ") VALUES ("
                    + String.join(", ", Collections.nCopies(count, "?")) + ") " + conflict, values);
        }

        void statement(String sql, List<Object> values) throws SQLException {
            PreparedStatement statement = statements.get(sql);
            if (statement == null) {
                statement = db.prepareStatement(sql);
                statements.put(sql, statement);
            }
            for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
            statement.addBatch();
        }

        void flush() throws SQLException {
            for (var statement : statements.values()) statement.executeBatch();
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            for (var statement : statements.values()) {
                try {
                    statement.close();
                } catch (SQLException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
