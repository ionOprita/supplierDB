package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Latest observed reviews, with retained reviews and comments that disappear from eMAG. */
class EmagMirrorDBVersion42 {
    static void version42(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE review_fetch (
                    pnk TEXT PRIMARY KEY,
                    last_successful_fetch_at TIMESTAMPTZ NOT NULL,
                    response_code INTEGER NOT NULL,
                    total_count INTEGER NOT NULL CHECK (total_count >= 0),
                    first_review_id BIGINT,
                    summary TEXT,
                    family_id TEXT,
                    notifications TEXT,
                    metadata_present BOOLEAN NOT NULL,
                    add_url_present BOOLEAN NOT NULL,
                    add_url_path TEXT, add_url_desktop_base TEXT, add_url_mobile_base TEXT,
                    view_url_present BOOLEAN NOT NULL,
                    view_url_path TEXT, view_url_desktop_base TEXT, view_url_mobile_base TEXT,
                    positive_rating_percentage INTEGER,
                    bought_count INTEGER,
                    bought_count_filter TEXT,
                    bought_count_message TEXT,
                    positive_rating_percentage_message TEXT,
                    messages_present BOOLEAN NOT NULL,
                    tag_info_text TEXT, tag_info_modal_text TEXT, tag_filter_text TEXT
                )
                """);
        executeStatement(db, """
                CREATE TABLE review (
                    pnk TEXT NOT NULL REFERENCES review_fetch (pnk) ON DELETE CASCADE,
                    review_id BIGINT NOT NULL,
                    position INTEGER NOT NULL,
                    content TEXT,
                    is_active BOOLEAN,
                    moderation_status TEXT,
                    created TEXT, modified TEXT, published TEXT, deleted TEXT,
                    report_reason TEXT,
                    edit_url_present BOOLEAN NOT NULL,
                    edit_url_path TEXT, edit_url_desktop_base TEXT, edit_url_mobile_base TEXT,
                    view_url_present BOOLEAN NOT NULL,
                    view_url_path TEXT, view_url_desktop_base TEXT, view_url_mobile_base TEXT,
                    type TEXT, title TEXT, content_no_tags TEXT,
                    rating INTEGER, is_bought BOOLEAN, votes INTEGER, current_customer_has_voted BOOLEAN,
                    brand_id BIGINT, category_id BIGINT, offer_id BIGINT,
                    client_type TEXT, client_type_info TEXT,
                    product_doc_id BIGINT, product_family_id BIGINT,
                    allow_comments_likes BOOLEAN, has_media BOOLEAN,
                    content_fingerprint TEXT NOT NULL,
                    first_fetched_at TIMESTAMPTZ NOT NULL,
                    last_fetched_at TIMESTAMPTZ NOT NULL,
                    last_changed_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (pnk, review_id)
                )
                """);
        executeStatement(db, """
                ALTER TABLE review_fetch ADD CONSTRAINT review_fetch_first_review_fkey
                    FOREIGN KEY (pnk, first_review_id) REFERENCES review (pnk, review_id)
                    DEFERRABLE INITIALLY DEFERRED
                """);
        executeStatement(db, """
                CREATE TABLE review_comment (
                    pnk TEXT NOT NULL,
                    review_id BIGINT NOT NULL,
                    comment_id BIGINT NOT NULL,
                    position INTEGER NOT NULL,
                    content TEXT, is_active BOOLEAN, moderation_status TEXT,
                    created TEXT, modified TEXT, published TEXT, deleted TEXT,
                    edit_url_present BOOLEAN NOT NULL,
                    edit_url_path TEXT, edit_url_desktop_base TEXT, edit_url_mobile_base TEXT,
                    view_url_present BOOLEAN NOT NULL,
                    view_url_path TEXT, view_url_desktop_base TEXT, view_url_mobile_base TEXT,
                    type TEXT, content_no_tags TEXT, parent_id BIGINT, is_official BOOLEAN,
                    content_fingerprint TEXT NOT NULL,
                    first_fetched_at TIMESTAMPTZ NOT NULL,
                    last_fetched_at TIMESTAMPTZ NOT NULL,
                    last_changed_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (pnk, review_id, comment_id),
                    FOREIGN KEY (pnk, review_id) REFERENCES review (pnk, review_id) ON DELETE CASCADE
                )
                """);

        // The owner key distinguishes a review from a comment even if their source IDs coincide.
        String ownerColumns = """
                pnk TEXT NOT NULL, review_id BIGINT NOT NULL,
                owner_type TEXT NOT NULL, owner_id BIGINT NOT NULL, comment_id BIGINT,
                """;
        String ownerConstraints = """
                FOREIGN KEY (pnk, review_id) REFERENCES review (pnk, review_id) ON DELETE CASCADE,
                FOREIGN KEY (pnk, review_id, comment_id)
                    REFERENCES review_comment (pnk, review_id, comment_id) ON DELETE CASCADE,
                CHECK ((owner_type = 'review' AND owner_id = review_id AND comment_id IS NULL)
                    OR (owner_type = 'comment' AND comment_id IS NOT NULL AND owner_id = comment_id))
                """;
        executeStatement(db, "CREATE TABLE review_user (" + ownerColumns + """
                user_id BIGINT, hash TEXT, name TEXT, nickname TEXT, email TEXT, is_official BOOLEAN,
                url_present BOOLEAN NOT NULL, url_path TEXT, url_desktop_base TEXT, url_mobile_base TEXT,
                avatar_present BOOLEAN NOT NULL, avatar_initials TEXT, avatar_path TEXT, avatar_background_color TEXT,
                PRIMARY KEY (pnk, review_id, owner_type, owner_id),
                """ + ownerConstraints + ")");
        executeStatement(db, "CREATE TABLE review_product (" + ownerColumns + """
                product_id BIGINT, name TEXT, part_number_key TEXT, sef_name TEXT,
                url_present BOOLEAN NOT NULL, url_path TEXT, url_desktop_base TEXT, url_mobile_base TEXT,
                offer_present BOOLEAN NOT NULL, offer_id BIGINT,
                PRIMARY KEY (pnk, review_id, owner_type, owner_id),
                """ + ownerConstraints + ")");
        executeStatement(db, """
                CREATE TABLE review_price (
                    pnk TEXT NOT NULL, review_id BIGINT NOT NULL,
                    owner_type TEXT NOT NULL, owner_id BIGINT NOT NULL,
                    current NUMERIC, is_min BOOLEAN, is_max BOOLEAN, legal NUMERIC,
                    prefix TEXT, suffix TEXT, is_visible BOOLEAN, net NUMERIC, initial NUMERIC,
                    discount_present BOOLEAN NOT NULL,
                    discount_type TEXT, discount_absolute NUMERIC, discount_percent NUMERIC,
                    discount_is_special BOOLEAN, discount_is_visible BOOLEAN,
                    discount_is_restricted_from_view BOOLEAN, discount_is_max BOOLEAN,
                    discount_label TEXT, discount_labeled_as_discount BOOLEAN,
                    currency_present BOOLEAN NOT NULL, currency_id BIGINT,
                    currency_name_present BOOLEAN NOT NULL, currency_name_default TEXT, currency_name_display TEXT,
                    recommended_retail_price_present BOOLEAN NOT NULL,
                    recommended_retail_price_amount NUMERIC, recommended_retail_price_is_visible BOOLEAN,
                    recommended_retail_price_label TEXT, recommended_retail_price_tooltip TEXT,
                    lowest_price_30_days_present BOOLEAN NOT NULL,
                    lowest_price_30_days_amount NUMERIC, lowest_price_30_days_is_visible BOOLEAN,
                    lowest_price_30_days_tooltip TEXT, lowest_price_30_days_info TEXT,
                    PRIMARY KEY (pnk, review_id, owner_type, owner_id),
                    FOREIGN KEY (pnk, review_id, owner_type, owner_id)
                        REFERENCES review_product (pnk, review_id, owner_type, owner_id) ON DELETE CASCADE
                )
                """);
        executeStatement(db, "CREATE TABLE review_image (" + ownerColumns + """
                image_kind TEXT NOT NULL CHECK (image_kind IN ('picture', 'avatar', 'product')),
                position INTEGER NOT NULL,
                original TEXT,
                PRIMARY KEY (pnk, review_id, owner_type, owner_id, image_kind, position),
                """ + ownerConstraints + ")");
        executeStatement(db, """
                CREATE TABLE review_image_size (
                    pnk TEXT NOT NULL, review_id BIGINT NOT NULL,
                    owner_type TEXT NOT NULL, owner_id BIGINT NOT NULL,
                    image_kind TEXT NOT NULL, image_position INTEGER NOT NULL,
                    position INTEGER NOT NULL, size TEXT, url TEXT,
                    PRIMARY KEY (pnk, review_id, owner_type, owner_id, image_kind, image_position, position),
                    FOREIGN KEY (pnk, review_id, owner_type, owner_id, image_kind, image_position)
                        REFERENCES review_image (pnk, review_id, owner_type, owner_id, image_kind, position)
                        ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE TABLE review_fetch_metadata (
                    pnk TEXT NOT NULL REFERENCES review_fetch (pnk) ON DELETE CASCADE,
                    position INTEGER NOT NULL, value TEXT,
                    PRIMARY KEY (pnk, position)
                )
                """);
        executeStatement(db, """
                CREATE TABLE review_suggested_question (
                    pnk TEXT NOT NULL REFERENCES review_fetch (pnk) ON DELETE CASCADE,
                    position INTEGER NOT NULL, question TEXT,
                    PRIMARY KEY (pnk, position)
                )
                """);
        executeStatement(db, """
                CREATE TABLE review_rating_distribution (
                    pnk TEXT NOT NULL REFERENCES review_fetch (pnk) ON DELETE CASCADE,
                    rating INTEGER NOT NULL, count INTEGER NOT NULL,
                    PRIMARY KEY (pnk, rating)
                )
                """);
        executeStatement(db, """
                CREATE TABLE review_characteristic_rating (
                    pnk TEXT NOT NULL REFERENCES review_fetch (pnk) ON DELETE CASCADE,
                    position INTEGER NOT NULL, review_characteristics_id BIGINT, name TEXT, average_rating DOUBLE PRECISION,
                    PRIMARY KEY (pnk, position)
                )
                """);
        executeStatement(db, """
                CREATE TABLE review_sort_option (
                    pnk TEXT NOT NULL REFERENCES review_fetch (pnk) ON DELETE CASCADE,
                    position INTEGER NOT NULL, name TEXT, direction TEXT, placeholder TEXT,
                    selected BOOLEAN, default_option BOOLEAN,
                    PRIMARY KEY (pnk, position)
                )
                """);
        executeStatement(db, "CREATE INDEX review_last_fetched_idx ON review (pnk, last_fetched_at)");
        executeStatement(db, "CREATE INDEX review_comment_last_fetched_idx ON review_comment (pnk, review_id, last_fetched_at)");
    }
}
