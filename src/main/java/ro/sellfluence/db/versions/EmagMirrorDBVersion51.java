package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Shared current FBE processing tariffs, seeded once from the read-only reference grid. */
public final class EmagMirrorDBVersion51 {
    private EmagMirrorDBVersion51() {
    }

    public static void version51(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE profit_calculator_fbe_tariff (
                    id INTEGER PRIMARY KEY CHECK (id BETWEEN 1 AND 12),
                    size_category TEXT NOT NULL CHECK (btrim(size_category) <> ''),
                    max_length_mm NUMERIC NOT NULL CHECK (max_length_mm > 0),
                    max_width_mm NUMERIC NOT NULL CHECK (max_width_mm > 0),
                    max_height_mm NUMERIC NOT NULL CHECK (max_height_mm > 0),
                    max_girth_mm NUMERIC NOT NULL CHECK (max_girth_mm > 0),
                    weight_band_kg NUMERIC NOT NULL CHECK (weight_band_kg > 0),
                    order_processing_fee_ron NUMERIC NOT NULL CHECK (order_processing_fee_ron >= 0),
                    return_processing_fee_ron NUMERIC NOT NULL CHECK (return_processing_fee_ron >= 0),
                    removal_fee_ron NUMERIC NOT NULL CHECK (removal_fee_ron >= 0),
                    disposal_fee_ron NUMERIC NOT NULL CHECK (disposal_fee_ron >= 0),
                    weight_range_kg NUMERIC CHECK (weight_range_kg > 0),
                    extra_order_fee_ron NUMERIC CHECK (extra_order_fee_ron >= 0),
                    extra_return_fee_ron NUMERIC CHECK (extra_return_fee_ron >= 0),
                    extra_removal_fee_ron NUMERIC CHECK (extra_removal_fee_ron >= 0),
                    extra_disposal_fee_ron NUMERIC CHECK (extra_disposal_fee_ron >= 0),
                    revision BIGINT NOT NULL CHECK (revision > 0)
                )
                """);
        // Calculator cost FBE, 'Grila costuri procesare FBE'!B4:S15, read 2026-11-16.
        // IDs and repeated extra-large tiers are retained; source blanks remain SQL NULL.
        executeStatement(db, """
                INSERT INTO profit_calculator_fbe_tariff (
                    id, size_category, max_length_mm, max_width_mm, max_height_mm, max_girth_mm,
                    weight_band_kg, order_processing_fee_ron, return_processing_fee_ron,
                    removal_fee_ron, disposal_fee_ron, weight_range_kg,
                    extra_order_fee_ron, extra_return_fee_ron, extra_removal_fee_ron, extra_disposal_fee_ron,
                    revision
                ) VALUES
                    (1, 'Packable Small / Ambalabil mic', 200, 150, 10, 520, 2, 3.5, 1.8, 2, 2, 5, 0.5, 0.3, 2, 2, 1),
                    (2, 'Packable Medium / Ambalabil mediu', 330, 230, 25, 840, 2, 4.2, 2.1, 2, 3, 5, 0.5, 0.3, 2, 2, 1),
                    (3, 'Packable Large / Ambalabil mare', 330, 230, 50, 890, 2, 4.6, 2.3, 2, 5, 10, 0.5, 0.3, 2, 2, 1),
                    (4, 'Shippable Small / Expediabil mic', 340, 170, 120, 920, 2, 5.5, 2.8, 3.2, 2.1, 10, 0.5, 0.3, 1, 1, 1),
                    (5, 'Shippable Medium / Expediabil mediu', 450, 340, 260, 1650, 2, 6.4, 3.2, 3.2, 2.4, 15, 0.5, 0.3, 1, 1, 1),
                    (6, 'Shippable Large / Expediabil mare', 610, 460, 460, 2450, 2, 7.5, 3.8, 3.2, 2.5, 40, 0.5, 0.3, 1, 1, 1),
                    (7, 'Shippable Atypical / Expediabil atipic', 1200, 600, 600, 3600, 2, 15, 7.5, 3.2, 2.3, 60, 1, 0.5, 1, 1, 1),
                    (8, 'Oversize / Supradimensionat', 1200, 800, 800, 4400, 5, 18, 9, 8.5, 2.3, 60, 1.5, 0.8, 1.5, 1.5, 1),
                    (9, 'Oversize Extra-Large / Supradimensionat extra-mare', 1860, 750, 630, 4620, 10, 30, 15, 12, 12, 60, 1.5, 0.8, 0.8, 0.8, 1),
                    (10, 'Oversize Extra-Large / Supradimensionat extra-mare', 1860, 750, 630, 4620, 60, 105, 52.5, 52, 52, 90, 1, 0.5, 0.8, 0.8, 1),
                    (11, 'Oversize Extra-Large / Supradimensionat extra-mare', 1860, 750, 630, 4620, 90, 135, 67.5, 76, 76, 300, 1, 0.5, 0.8, 0.8, 1),
                    (12, 'Oversize Extra-Large / Supradimensionat extra-mare', 1860, 750, 630, 4620, 300, 177, 88.5, 110, 110, NULL, NULL, NULL, NULL, NULL, 1)
                """);
    }
}
