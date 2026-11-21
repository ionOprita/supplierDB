package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Current Date Calcul inputs, seeded once for each vendor group. */
public final class EmagMirrorDBVersion52 {
    private EmagMirrorDBVersion52() {
    }

    public static void version52(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE profit_calculator_general_settings (
                    vendor_group TEXT PRIMARY KEY CHECK (btrim(vendor_group) <> ''),
                    revision BIGINT NOT NULL CHECK (revision > 0),
                    genius_easybox_rate NUMERIC,
                    monthly_capital_rate NUMERIC,
                    monthly_inflation_rate NUMERIC,
                    average_days_per_month NUMERIC,
                    vat_rate NUMERIC,
                    income_tax_rate NUMERIC,
                    profit_tax_rate NUMERIC,
                    dividend_tax_rate NUMERIC,
                    unaccounted_storno_rate NUMERIC
                )
                """);
        executeStatement(db, """
                CREATE TABLE profit_calculator_general_costs (
                    vendor_group TEXT PRIMARY KEY CHECK (btrim(vendor_group) <> ''),
                    revision BIGINT NOT NULL CHECK (revision > 0),
                    packing_cost_ron NUMERIC,
                    courier_cost_ron NUMERIC,
                    genius_cost_ron NUMERIC,
                    monthly_accounting_cost_ron NUMERIC,
                    warehouse_preparation_cost_ron NUMERIC
                )
                """);
        executeStatement(db, """
                CREATE TABLE profit_calculator_employee_costs (
                    vendor_group TEXT PRIMARY KEY CHECK (btrim(vendor_group) <> ''),
                    revision BIGINT NOT NULL CHECK (revision > 0),
                    items JSONB NOT NULL CHECK (jsonb_typeof(items) = 'array')
                )
                """);
        executeStatement(db, """
                CREATE TABLE profit_calculator_misc_costs (
                    vendor_group TEXT PRIMARY KEY CHECK (btrim(vendor_group) <> ''),
                    revision BIGINT NOT NULL CHECK (revision > 0),
                    items JSONB NOT NULL CHECK (jsonb_typeof(items) = 'array')
                )
                """);
        // Date Calcul from the J, K, S and Z Calculator Profit workbooks, read 2026-11-21.
        // Seed evaluated arithmetic literals and preserve named entries with unknown amounts.
        executeStatement(db, """
                INSERT INTO profit_calculator_general_settings (
                    vendor_group, revision, genius_easybox_rate, monthly_capital_rate, monthly_inflation_rate,
                    average_days_per_month, vat_rate, income_tax_rate, profit_tax_rate,
                    dividend_tax_rate, unaccounted_storno_rate
                ) VALUES
                    ('J', 1, 0.35, 0, 0.015, 30.416666666666668, 0.21, 0.03, 0.16, 0.16, 0),
                    ('K', 1, 0.35, 0, 0.015, 30.416666666666668, 0.21, 0.03, 0.16, 0.16, 0),
                    ('S', 1, 0.35, 0, 0.015, 30.416666666666668, 0.21, 0.03, 0.16, 0.16, 0),
                    ('Z', 1, 0.35, 0, 0.015, 30.416666666666668, 0.21, 0.03, 0.16, 0.16, 0)
                """);
        executeStatement(db, """
                INSERT INTO profit_calculator_general_costs (
                    vendor_group, revision, packing_cost_ron, courier_cost_ron, genius_cost_ron,
                    monthly_accounting_cost_ron, warehouse_preparation_cost_ron
                ) VALUES
                    ('J', 1, 1, 11, 8, 600, 2),
                    ('K', 1, 1, 11, 8, 3334.0000000000005, 2),
                    ('S', 1, 1, 13, 8, 1600, 2),
                    ('Z', 1, 1, 11, 8, 6666, 2)
                """);
        executeStatement(db, """
                INSERT INTO profit_calculator_employee_costs (vendor_group, revision, items) VALUES
                    ('J', 1, '[]'::jsonb),
                    ('K', 1, '[
                        {"label":"Blaga Daniel","amountRon":"4166.5"},
                        {"label":"Costache Vlad","amountRon":"4166.5"},
                        {"label":"Turcu Alexandra","amountRon":"4166.5"},
                        {"label":"Angajat Depozit","amountRon":"2083.75"},
                        {"label":"Manastireanu Bogdan","amountRon":"2467.42672"},
                        {"label":"Andreea Kovacs","amountRon":"2165.7664"},
                        {"label":"Teodora Turc","amountRon":"2000"},
                        {"label":"Diana Santa","amountRon":"3904"},
                        {"label":"Tudor Stoian","amountRon":null},
                        {"label":"Alex Amarandei","amountRon":"5191.5"},
                        {"label":"Madalina Braescu","amountRon":"1616.9900000000002"}
                    ]'::jsonb),
                    ('S', 1, '[
                        {"label":"Dorina","amountRon":"1800"},
                        {"label":"Ionut","amountRon":"4000"},
                        {"label":"Alex","amountRon":"7500"},
                        {"label":"Ana","amountRon":"3500"},
                        {"label":"Iulian","amountRon":"7500"},
                        {"label":"Alina","amountRon":"7500"},
                        {"label":"Bogdan","amountRon":"1500"}
                    ]'::jsonb),
                    ('Z', 1, '[
                        {"label":"Blaga Daniel","amountRon":"4166.5"},
                        {"label":"Costache Vlad","amountRon":"4166.5"},
                        {"label":"Angajat Depozit","amountRon":"4166.25"},
                        {"label":"Manastireanu Bogdan","amountRon":"4933.373279999999"},
                        {"label":"Andreea Kovacs","amountRon":"4330.2336"},
                        {"label":"Teodora Turc","amountRon":"2000"},
                        {"label":"Diana Santa","amountRon":"3904"},
                        {"label":"Tudor Stoian","amountRon":null},
                        {"label":"Alex Amarandei","amountRon":"5191.5"},
                        {"label":"Madalina Braescu","amountRon":"3233.0099999999998"}
                    ]'::jsonb)
                """);
        executeStatement(db, """
                INSERT INTO profit_calculator_misc_costs (vendor_group, revision, items) VALUES
                    ('J', 1, '[]'::jsonb),
                    ('K', 1, '[
                        {"label":"Chirie depozit","amountRon":"1466.96"},
                        {"label":"Intretinere depozit","amountRon":"0"},
                        {"label":"Internet","amountRon":"50"},
                        {"label":"Altele (loom, telefonie, google)","amountRon":"540"}
                    ]'::jsonb),
                    ('S', 1, '[
                        {"label":"Chirie depozit","amountRon":"4470"},
                        {"label":"Utilitati depozit","amountRon":"1240"},
                        {"label":"Chirie apartament","amountRon":"2805"},
                        {"label":"Utilitati apartament","amountRon":"1000"},
                        {"label":"Cheltuieli depozit lunare","amountRon":"1000"},
                        {"label":"Alte servicii","amountRon":"500"}
                    ]'::jsonb),
                    ('Z', 1, '[
                        {"label":"Chirie depozit","amountRon":"2933.04"},
                        {"label":"Intretinere depozit","amountRon":"0"},
                        {"label":"Internet","amountRon":"50"},
                        {"label":"Altele","amountRon":"0"}
                    ]'::jsonb)
                """);
    }
}
