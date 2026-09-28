package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.emagsiteapi.EmagUrl;
import ro.sellfluence.emagsiteapi.Review;
import ro.sellfluence.emagsiteapi.ReviewComment;
import ro.sellfluence.emagsiteapi.ReviewImage;
import ro.sellfluence.emagsiteapi.ReviewPrice;
import ro.sellfluence.emagsiteapi.ReviewProduct;
import ro.sellfluence.emagsiteapi.ReviewUser;
import ro.sellfluence.emagsiteapi.ReviewsData;
import ro.sellfluence.emagsiteapi.ReviewsResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and an isolated schema. */
class ReviewsTableIntegrationTest {
    private static final String PNK = "PNK-1";
    private static final Instant FIRST_FETCH = Instant.parse("2026-09-26T03:14:15.123456Z");
    private static final Instant SECOND_FETCH = FIRST_FETCH.plusSeconds(86_400);
    private static final Instant THIRD_FETCH = SECOND_FETCH.plusSeconds(86_400);
    private static final ZonedDateTime SOURCE_DELETED = ZonedDateTime.of(
            2026, 9, 21, 13, 27, 41, 123456000, ZoneId.of("Europe/Bucharest"));

    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "reviews_storage_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        applyMigration();
        db.commit();
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                db.setSchema("public");
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void repeatedFetchUpdatesObservationTimeWithoutDuplicatingOrChangingContent() throws Exception {
        var review = fullReview(11, 1);
        var response = response(review);

        assertResult(ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response), 1, 0, 0, 0);
        db.commit(); // Also checks the deferred first-review foreign key against the completed graph.
        var fingerprint = text("SELECT content_fingerprint FROM review");
        var counts = tableCounts();
        assertResult(ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response), 0, 0, 1, 0);

        assertEquals(counts, tableCounts(), "No duplicate rows on a repeated complete fetch");
        assertEquals(1, count("SELECT count(*) FROM review"), "first_item is a reference, not another review");
        assertEquals(11, count("SELECT first_review_id FROM review_fetch"));
        assertEquals(fingerprint, text("SELECT content_fingerprint FROM review"));
        assertTimes("review", FIRST_FETCH, SECOND_FETCH, FIRST_FETCH);
        assertTimes("review_comment", FIRST_FETCH, SECOND_FETCH, FIRST_FETCH);
        assertEquals(SECOND_FETCH, instant("SELECT last_successful_fetch_at FROM review_fetch"));
    }

    @Test
    void storesAllParsedFieldsIncludingNestedPricesImagesSourceTimesAndSummaryOrder() throws Exception {
        var review = fullReview(11, 1);
        var response = response(review);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response);

        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM review")) {
            assertTrue(rows.next());
            assertRecordColumns(rows, review, "", Map.of("id", "review_id"),
                    Set.of("user", "product", "comments", "pictures"));
            assertEquals(0, rows.getInt("position"));
            assertFalse(rows.next());
        }
        var comment = review.comments().getFirst();
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM review_comment")) {
            assertTrue(rows.next());
            assertRecordColumns(rows, comment, "", Map.of("id", "comment_id"), Set.of("user", "product"));
            assertEquals(SOURCE_DELETED.toString(), rows.getString("deleted"));
            assertFalse(rows.next());
        }
        assertOwnerRecords("review", review.id(), review.user(), review.product());
        assertOwnerRecords("comment", comment.id(), comment.user(), comment.product());
        assertImages("review", review.id(), "picture", review.pictures());

        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM review_fetch")) {
            assertTrue(rows.next());
            assertEquals(PNK, rows.getString("pnk"));
            assertEquals(response.code(), rows.getInt("response_code"));
            assertEquals(response.notifications(), rows.getString("notifications"));
            assertTrue(rows.getBoolean("metadata_present"));
            assertRecordColumns(rows, response.data(), "", Map.of("count", "total_count"),
                    Set.of("firstItem", "items", "suggestedQuestions", "ratingDistribution",
                            "reviewCharacteristicsAverageRating", "sortOptions", "messages"));
            assertTrue(rows.getBoolean("messages_present"));
            assertRecordColumns(rows, response.data().messages(), "", Map.of(), Set.of());
        }
        assertOrderedStrings("review_fetch_metadata", "value", response.metadata());
        assertOrderedStrings("review_suggested_question", "question", response.data().suggestedQuestions());
        assertOrderedRecords("review_characteristic_rating", response.data().reviewCharacteristicsAverageRating());
        assertOrderedRecords("review_sort_option", response.data().sortOptions());
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT rating, count FROM review_rating_distribution")) {
            var distribution = new LinkedHashMap<Integer, Integer>();
            while (rows.next()) distribution.put(rows.getInt("rating"), rows.getInt("count"));
            assertEquals(response.data().ratingDistribution(), distribution);
        }
        assertEquals(0, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND data_type IN ('json', 'jsonb')
                """), "Parsed data is stored in relational columns");
    }

    @Test
    void latestNestedValuesReplaceOldRowsAndPreserveMissingVersusPresentEmptyObjects() throws Exception {
        var original = fullReview(11, 1);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(original));
        var emptyUrl = new EmagUrl(null, null, null);
        var emptyAvatar = new ReviewUser.ReviewAvatar(null, null, null, new ReviewImage(null, List.of()));
        var emptyUser = new ReviewUser(null, null, null, emptyAvatar, null, emptyUrl, null, null);
        var emptyPrice = new ReviewPrice(null, null, null, null, null, null, null,
                new ReviewPrice.Discount(null, null, null, null, null, null, null, null, null),
                new ReviewPrice.Currency(null, new ReviewPrice.Currency.Name(null, null)), null,
                new ReviewPrice.RecommendedRetailPrice(null, null, null, null),
                new ReviewPrice.LowestPrice30Days(null, null, null, null), null);
        var emptyProduct = new ReviewProduct(null, null, null, new ReviewImage(null, List.of()),
                new ReviewProduct.ReviewOffer(null, emptyPrice), emptyUrl, null);
        var comment = copyRecord(original.comments().getFirst(), Map.of("content", "Comment remains"));
        var replacements = new LinkedHashMap<String, Object>();
        replacements.put("user", emptyUser);
        replacements.put("product", emptyProduct);
        replacements.put("editUrl", emptyUrl);
        replacements.put("viewUrl", null);
        replacements.put("pictures", List.of());
        replacements.put("comments", List.of(comment));
        var emptyObjects = copyRecord(original, replacements);

        ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(emptyObjects));

        assertEquals(1, count("SELECT count(*) FROM review_user WHERE owner_type = 'review' AND avatar_present AND url_present"));
        assertEquals(1, count("SELECT count(*) FROM review_product WHERE owner_type = 'review' AND offer_present AND url_present"));
        assertEquals(1, count("""
                SELECT count(*) FROM review_price WHERE owner_type = 'review'
                    AND discount_present AND currency_present AND currency_name_present
                    AND recommended_retail_price_present AND lowest_price_30_days_present
                """));
        assertEquals(1, count("SELECT count(*) FROM review WHERE edit_url_present AND NOT view_url_present"));
        assertNull(text("SELECT edit_url_path FROM review"));
        assertEquals(0, count("SELECT count(*) FROM review_image WHERE owner_type = 'review' AND image_kind = 'picture'"));
        assertEquals(2, count("SELECT count(*) FROM review_image WHERE owner_type = 'review'"));
        assertEquals(0, count("SELECT count(*) FROM review_image_size WHERE owner_type = 'review'"));

        replacements.put("user", null);
        replacements.put("product", null);
        replacements.put("editUrl", null);
        var absentObjects = copyRecord(original, replacements);
        ReviewsTable.storeReviews(db, PNK, THIRD_FETCH, response(absentObjects));

        for (var table : List.of("review_user", "review_product", "review_price", "review_image", "review_image_size")) {
            assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE owner_type = 'review'"), table);
            assertTrue(count("SELECT count(*) FROM " + table + " WHERE owner_type = 'comment'") > 0, table);
        }
        assertEquals(1, count("SELECT count(*) FROM review WHERE NOT edit_url_present AND NOT view_url_present"));
    }

    @Test
    void latestSummaryReplacesChildrenAndPreservesNullVersusEmptyMetadata() throws Exception {
        var original = fullReview(11, 1);
        var first = response(original);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, first);
        var replacements = new LinkedHashMap<String, Object>();
        replacements.put("summary", null);
        replacements.put("suggestedQuestions", List.of());
        replacements.put("ratingDistribution", Map.of());
        replacements.put("reviewCharacteristicsAverageRating", List.of());
        replacements.put("sortOptions", List.of());
        replacements.put("messages", null);
        replacements.put("addUrl", null);
        replacements.put("viewUrl", new EmagUrl(null, null, null));
        var emptyData = copyRecord(first.data(), replacements);

        ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, new ReviewsResponse(200, emptyData, List.of(), null));

        for (var table : List.of("review_fetch_metadata", "review_suggested_question", "review_rating_distribution",
                "review_characteristic_rating", "review_sort_option")) {
            assertEquals(0, count("SELECT count(*) FROM " + table), table);
        }
        assertEquals(1, count("""
                SELECT count(*) FROM review_fetch WHERE metadata_present AND NOT messages_present
                    AND NOT add_url_present AND view_url_present
                """));
        assertNull(text("SELECT summary FROM review_fetch"));
        assertNull(text("SELECT notifications FROM review_fetch"));
        assertNull(text("SELECT tag_info_text FROM review_fetch"));
        ReviewsTable.storeReviews(db, PNK, THIRD_FETCH, new ReviewsResponse(200, emptyData, null, null));
        assertEquals(1, count("SELECT count(*) FROM review_fetch WHERE NOT metadata_present"));
    }

    @Test
    void detectsVotesAndCommentChangesEvenWhenSourceModifiedIsUnchanged() throws Exception {
        var original = fullReview(11, 1);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(original));
        var oldFingerprint = text("SELECT content_fingerprint FROM review");
        var updatedComment = copyRecord(original.comments().getFirst(), Map.of("content", "Updated comment ț"));
        var revised = copyRecord(original, Map.of("votes", original.votes() + 1,
                "comments", List.of(updatedComment)));
        assertEquals(original.modified(), revised.modified());

        assertResult(ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(revised)), 0, 1, 0, 0);

        assertEquals(revised.votes().longValue(), count("SELECT votes FROM review"));
        assertEquals("Updated comment ț", text("SELECT content FROM review_comment"));
        assertNotEquals(oldFingerprint, text("SELECT content_fingerprint FROM review"));
        assertTimes("review", FIRST_FETCH, SECOND_FETCH, SECOND_FETCH);
        assertTimes("review_comment", FIRST_FETCH, SECOND_FETCH, SECOND_FETCH);
    }

    @Test
    void missingReviewsAndCommentsRemainStoredAndCanReappearWithoutDuplicates() throws Exception {
        var kept = fullReview(11, 1);
        var missing = fullReview(12, 2);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(kept, missing));
        var commentChildren = commentPhysicalRows(11);
        var withoutComment = copyRecord(kept, Map.of("comments", List.of()));

        assertResult(ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(withoutComment)), 0, 1, 0, 1);

        assertEquals(2, count("SELECT count(*) FROM review"));
        assertEquals(2, count("SELECT count(*) FROM review_comment"));
        assertEquals(FIRST_FETCH, instant("SELECT last_fetched_at FROM review WHERE review_id = 12"));
        assertEquals(FIRST_FETCH, instant("SELECT last_fetched_at FROM review_comment WHERE review_id = 11"));
        assertEquals(commentChildren, commentPhysicalRows(11), "An omitted comment retains its complete nested graph");
        assertEquals(0, count("""
                SELECT count(*) FROM review_comment AS c JOIN review AS r USING (pnk, review_id)
                WHERE r.review_id = 11 AND c.last_fetched_at = r.last_fetched_at
                """));

        assertResult(ReviewsTable.storeReviews(db, PNK, THIRD_FETCH, response(kept, missing)), 0, 1, 1, 0);

        assertEquals(2, count("SELECT count(*) FROM review"));
        assertEquals(2, count("SELECT count(*) FROM review_comment"));
        assertEquals(2, count("""
                SELECT count(*) FROM review_comment AS c JOIN review AS r USING (pnk, review_id)
                WHERE c.last_fetched_at = r.last_fetched_at
                """));
        assertEquals(THIRD_FETCH, instant("SELECT last_fetched_at FROM review WHERE review_id = 12"));
        assertEquals(FIRST_FETCH, instant("SELECT last_changed_at FROM review WHERE review_id = 12"));
        assertEquals(FIRST_FETCH, instant("SELECT last_changed_at FROM review_comment WHERE review_id = 11"));
        assertEquals(commentChildren, commentPhysicalRows(11), "Unchanged returning comments do not rewrite nested rows");
    }

    @Test
    void emptySuccessfulFetchRecordsZeroWithoutDeletingPreviouslyStoredReviews() throws Exception {
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(fullReview(11, 1)));
        var before = tableCounts();

        assertResult(ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response()), 0, 0, 0, 1);
        assertResult(ReviewsTable.storeReviews(db, "NEVER-HAD-REVIEWS", SECOND_FETCH, response()), 0, 0, 0, 0);

        assertEquals(1, count("SELECT count(*) FROM review"));
        assertEquals(2, count("SELECT count(*) FROM review_fetch"));
        assertEquals(0, count("SELECT total_count FROM review_fetch WHERE pnk = 'PNK-1'"));
        assertNull(text("SELECT first_review_id FROM review_fetch WHERE pnk = 'PNK-1'"));
        assertEquals(SECOND_FETCH, instant("SELECT last_successful_fetch_at FROM review_fetch WHERE pnk = 'PNK-1'"));
        assertTimes("review", FIRST_FETCH, FIRST_FETCH, FIRST_FETCH);
        assertTimes("review_comment", FIRST_FETCH, FIRST_FETCH, FIRST_FETCH);
        for (var table : List.of("review_user", "review_product", "review_price", "review_image", "review_image_size")) {
            assertEquals(before.get(table), count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void matchingReviewIdsRemainIndependentForDifferentRequestedPnks() throws Exception {
        var first = fullReview(11, 1);
        var second = copyRecord(fullReview(11, 2), Map.of("content", "Other requested product"));
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(first));
        ReviewsTable.storeReviews(db, "PNK-2", SECOND_FETCH, response(second));

        assertEquals(2, count("SELECT count(*) FROM review WHERE review_id = 11"));
        assertEquals(first.content(), text("SELECT content FROM review WHERE pnk = 'PNK-1'"));
        assertEquals(second.content(), text("SELECT content FROM review WHERE pnk = 'PNK-2'"));
        assertResult(ReviewsTable.storeReviews(db, PNK, THIRD_FETCH, response()), 0, 0, 0, 1);
        assertEquals(1, count("SELECT total_count FROM review_fetch WHERE pnk = 'PNK-2'"));
        assertEquals(SECOND_FETCH, instant("SELECT last_fetched_at FROM review WHERE pnk = 'PNK-2'"));
    }

    @Test
    void rejectsIncompleteMissingIdAndDuplicateResponsesBeforeChangingStoredData() throws Exception {
        var original = fullReview(11, 1);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(original));
        var before = tableCounts();
        var valid = response(original);
        for (var invalidCount : List.of(-1, 0, 2)) {
            var invalid = new ReviewsResponse(200, copyRecord(valid.data(), Map.of("count", invalidCount)),
                    valid.metadata(), valid.notifications());
            assertThrows(IllegalArgumentException.class,
                    () -> ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, invalid));
        }
        var noId = copyRecord(original, Collections.singletonMap("id", null));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(noId)));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(original, original)));
        var noCommentId = copyRecord(original.comments().getFirst(), Collections.singletonMap("id", null));
        var invalidComment = copyRecord(original, Map.of("comments", List.of(noCommentId)));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(invalidComment)));
        var repeatedComment = copyRecord(original, Map.of("comments",
                List.of(original.comments().getFirst(), original.comments().getFirst())));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(repeatedComment)));

        assertEquals(before, tableCounts());
        assertEquals(original.content(), text("SELECT content FROM review"));
        assertEquals(FIRST_FETCH, instant("SELECT last_successful_fetch_at FROM review_fetch"));
    }

    @Test
    void databaseFailureRollsBackReviewChildrenAndSuccessfulFetchMarker() throws Exception {
        var original = fullReview(11, 1);
        ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response(original));
        execute("ALTER TABLE review_price ADD CONSTRAINT reject_test_price CHECK (current >= 0)");
        db.commit();
        var before = tableCounts();
        var oldFingerprint = text("SELECT content_fingerprint FROM review");
        var badPrice = copyRecord(original.product().offer().price(), Map.of("current", BigDecimal.valueOf(-1)));
        var badOffer = copyRecord(original.product().offer(), Map.of("price", badPrice));
        var badProduct = copyRecord(original.product(), Map.of("offer", badOffer));
        var revised = copyRecord(original, Map.of("title", "Would change", "product", badProduct));

        assertThrows(SQLException.class,
                () -> ReviewsTable.storeReviews(db, PNK, SECOND_FETCH, response(revised, fullReview(12, 2))));
        db.rollback();

        assertEquals(before, tableCounts());
        assertEquals(original.title(), text("SELECT title FROM review"));
        assertEquals(oldFingerprint, text("SELECT content_fingerprint FROM review"));
        assertEquals(FIRST_FETCH, instant("SELECT last_successful_fetch_at FROM review_fetch"));
        assertTimes("review", FIRST_FETCH, FIRST_FETCH, FIRST_FETCH);
        assertTimes("review_comment", FIRST_FETCH, FIRST_FETCH, FIRST_FETCH);
    }

    @Test
    void refusesAutocommitSoPartialGraphsCannotBeCommitted() throws SQLException {
        db.setAutoCommit(true);
        try {
            assertThrows(SQLException.class, () -> ReviewsTable.storeReviews(db, PNK, FIRST_FETCH, response()));
            assertEquals(0, count("SELECT count(*) FROM review_fetch"));
        } finally {
            db.setAutoCommit(false);
        }
    }

    private static ReviewsResponse response(Review... reviews) {
        return new ReviewsResponse(200, new ReviewsData(reviews.length,
                reviews.length == 0 ? null : reviews[0], List.of(reviews), "Summary ț, quotes \" and apostrophe '",
                List.of("Question two", "Question one", "Question two"), Map.of(1, 3, 5, 17),
                List.of(new ReviewsData.ReviewCharacteristicRating(901L, "Comfort", 4.123456789),
                        new ReviewsData.ReviewCharacteristicRating(900L, "Quality", 3.5)),
                new ro.sellfluence.emagsiteapi.EmagUrl("/add", "https://desktop.add", "https://mobile.add"),
                new ro.sellfluence.emagsiteapi.EmagUrl("/view", "https://desktop.view", "https://mobile.view"),
                List.of(new ReviewsData.ReviewSortOption("created", "desc", "Newest", true, false),
                        new ReviewsData.ReviewSortOption("votes", "asc", "Helpful", false, true)),
                87, 13, "bought filter", "bought message", "positive message",
                new ReviewsData.ReviewMessages("tag info", "modal info", "tag filter"), "family-73"),
                Arrays.asList("metadata second", null, "metadata first"), "notifications ț");
    }

    private static Review fullReview(long id, int seed) throws ReflectiveOperationException {
        var comment = sampleRecord(ReviewComment.class, seed + 1, Map.of("id", id + 1_000,
                "parentId", id, "deleted", SOURCE_DELETED));
        return sampleRecord(Review.class, seed, Map.of("id", id, "comments", List.of(comment)));
    }

    /** Distinct values catch omitted fields and shifted prepared-statement parameters. */
    private static <T extends Record> T sampleRecord(Class<T> type, int seed, Map<String, Object> overrides)
            throws ReflectiveOperationException {
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            var component = components[index];
            var fieldType = component.getType();
            types[index] = fieldType;
            int value = seed * 100 + index;
            if (overrides.containsKey(component.getName())) values[index] = overrides.get(component.getName());
            else if (fieldType == String.class) {
                values[index] = List.of("created", "modified", "published", "deleted").contains(component.getName())
                        ? "2026-09-21T13:27:41.123456+03:00"
                        : component.getName() + "-" + value + " ț ' \\\"";
            } else if (fieldType == Long.class) values[index] = 3_000_000_000L + value;
            else if (fieldType == Integer.class) values[index] = value;
            else if (fieldType == Boolean.class) values[index] = index % 2 == 0;
            else if (fieldType == BigDecimal.class) values[index] = new BigDecimal(value + ".123456789123456789");
            else if (fieldType == ZonedDateTime.class) values[index] = SOURCE_DELETED;
            else if (fieldType == List.class) {
                var elementType = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                if (elementType.isRecord()) {
                    values[index] = List.of(sampleRecord(elementType.asSubclass(Record.class), seed + 1, Map.of()),
                            sampleRecord(elementType.asSubclass(Record.class), seed + 2, Map.of()));
                } else throw new AssertionError("Unhandled list component: " + component);
            } else if (fieldType.isRecord()) {
                values[index] = sampleRecord(fieldType.asSubclass(Record.class), seed + 1, Map.of());
            } else throw new AssertionError("Unhandled component: " + component);
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T copyRecord(T original, Map<String, Object> replacements)
            throws ReflectiveOperationException {
        var components = original.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            var component = components[index];
            types[index] = component.getType();
            values[index] = replacements.containsKey(component.getName()) ? replacements.get(component.getName())
                    : component.getAccessor().invoke(original);
        }
        return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
    }

    private static void assertResult(ReviewsTable.StoreResult result, int inserted, int changed,
                                     int unchanged, int notReturned) {
        assertEquals(inserted, result.inserted());
        assertEquals(changed, result.changed());
        assertEquals(unchanged, result.unchanged());
        assertEquals(notReturned, result.notReturned());
    }

    private void assertOwnerRecords(String ownerType, long ownerId, ReviewUser user, ReviewProduct product)
            throws Exception {
        var owner = " WHERE owner_type = '" + ownerType + "' AND owner_id = " + ownerId;
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM review_user" + owner)) {
            assertTrue(rows.next());
            assertRecordColumns(rows, user, "", Map.of("id", "user_id", "user_avatar", "avatar"), Set.of("image"));
            assertFalse(rows.next());
        }
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM review_product" + owner)) {
            assertTrue(rows.next());
            assertRecordColumns(rows, product, "", Map.of("id", "product_id"), Set.of("image", "price"));
            assertFalse(rows.next());
        }
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM review_price" + owner)) {
            assertTrue(rows.next());
            assertRecordColumns(rows, product.offer().price(), "", Map.of("lowest_price30_days", "lowest_price_30_days",
                    "currency_name_default_name", "currency_name_default"), Set.of());
            assertFalse(rows.next());
        }
        assertImages(ownerType, ownerId, "avatar", List.of(user.userAvatar().image()));
        assertImages(ownerType, ownerId, "product", List.of(product.image()));
    }

    private void assertImages(String ownerType, long ownerId, String kind, List<ReviewImage> images) throws Exception {
        var owner = " WHERE owner_type = '" + ownerType + "' AND owner_id = " + ownerId + " AND image_kind = '" + kind + "'";
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT * FROM review_image" + owner + " ORDER BY position")) {
            for (int position = 0; position < images.size(); position++) {
                assertTrue(rows.next());
                assertEquals(position, rows.getInt("position"));
                assertEquals(images.get(position).original(), rows.getString("original"));
                try (var sizesStatement = db.createStatement(); var sizes = sizesStatement.executeQuery(
                        "SELECT * FROM review_image_size" + owner + " AND image_position = " + position + " ORDER BY position")) {
                    for (var expected : images.get(position).resizedImages()) {
                        assertTrue(sizes.next());
                        assertRecordColumns(sizes, expected, "", Map.of(), Set.of());
                    }
                    assertFalse(sizes.next());
                }
            }
            assertFalse(rows.next());
        }
    }

    private void assertOrderedStrings(String table, String column, List<String> expected) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY position")) {
            for (int position = 0; position < expected.size(); position++) {
                assertTrue(rows.next(), table);
                assertEquals(position, rows.getInt("position"), table);
                assertEquals(expected.get(position), rows.getString(column), table);
            }
            assertFalse(rows.next(), table);
        }
    }

    private void assertOrderedRecords(String table, List<? extends Record> expected) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY position")) {
            for (int position = 0; position < expected.size(); position++) {
                assertTrue(rows.next(), table);
                assertEquals(position, rows.getInt("position"), table);
                assertRecordColumns(rows, expected.get(position), "", Map.of(), Set.of());
            }
            assertFalse(rows.next(), table);
        }
    }

    private static void assertRecordColumns(ResultSet rows, Record expected, String prefix,
                                             Map<String, String> renamedColumns, Set<String> skippedFields) throws Exception {
        for (var component : expected.getClass().getRecordComponents()) {
            if (skippedFields.contains(component.getName())) continue;
            var value = component.getAccessor().invoke(expected);
            var column = prefix + component.getName().replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
            column = renamedColumns.getOrDefault(column, column);
            if (value instanceof Record record) {
                assertTrue(rows.getBoolean(column + "_present"), column + "_present");
                assertRecordColumns(rows, record, column + "_", renamedColumns, skippedFields);
            } else if (value instanceof BigDecimal decimal) {
                var actual = rows.getBigDecimal(column);
                assertNotNull(actual, column);
                assertEquals(0, decimal.compareTo(actual), column);
            } else if (value instanceof ZonedDateTime dateTime) {
                assertEquals(dateTime.toString(), rows.getString(column), column);
            } else {
                assertEquals(value, rows.getObject(column), column);
            }
        }
    }

    private void assertTimes(String table, Instant first, Instant last, Instant changed) throws SQLException {
        assertEquals(first, instant("SELECT first_fetched_at FROM " + table), table);
        assertEquals(last, instant("SELECT last_fetched_at FROM " + table), table);
        assertEquals(changed, instant("SELECT last_changed_at FROM " + table), table);
    }

    private Map<String, Long> tableCounts() throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        try (var statement = db.createStatement(); var tables = statement.executeQuery("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name LIKE 'review%'
                ORDER BY table_name
                """)) {
            while (tables.next()) {
                var table = tables.getString(1);
                counts.put(table, count("SELECT count(*) FROM " + table));
            }
        }
        return counts;
    }

    private Map<String, String> commentPhysicalRows(long reviewId) throws SQLException {
        var result = new LinkedHashMap<String, String>();
        for (var table : List.of("review_user", "review_product", "review_price", "review_image", "review_image_size")) {
            result.put(table, text("SELECT string_agg(ctid::text, ',' ORDER BY ctid::text) FROM " + table
                    + " WHERE owner_type = 'comment' AND review_id = " + reviewId));
        }
        return result;
    }

    private long count(String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getString(1);
        }
    }

    private Instant instant(String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private void applyMigration() throws Exception {
        var type = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion42");
        var method = type.getDeclaredMethod("version42", Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(null, db);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception failure) throw failure;
            throw exception;
        }
    }
}
