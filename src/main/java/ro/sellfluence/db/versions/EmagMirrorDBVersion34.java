package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion34 {
    static void version34(Connection db) throws SQLException {
        createEmployeeSheetDataTable(db);
    }

    private static void createEmployeeSheetDataTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE employee_sheet_data (
                    source_row_number INTEGER PRIMARY KEY,
                    sheet_index TEXT,
                    full_name TEXT NOT NULL,
                    first_name TEXT,
                    last_name TEXT,
                    city TEXT,
                    street TEXT,
                    street_number TEXT,
                    building TEXT,
                    staircase TEXT,
                    floor TEXT,
                    apartment TEXT,
                    county_or_sector TEXT,
                    id_series TEXT,
                    id_number TEXT,
                    id_issued_by TEXT,
                    id_issued_date TEXT,
                    cnp TEXT,
                    company TEXT,
                    gender TEXT,
                    department TEXT,
                    job_title TEXT,
                    employee_code TEXT,
                    cim_number TEXT,
                    cim_date TEXT,
                    activity_start_date TEXT,
                    cim_end_date TEXT,
                    probation_period_days TEXT,
                    work_hours_per_day TEXT,
                    work_hours_per_week TEXT,
                    work_start_time TEXT,
                    work_end_time TEXT,
                    working_days TEXT,
                    paid_vacation_days_per_year TEXT,
                    gross_base_salary TEXT,
                    track_vacation_days TEXT,
                    last_documents_update TEXT,
                    updated_sections TEXT,
                    birthday TEXT,
                    cv_and_references TEXT,
                    id_copy TEXT,
                    driving_license_copy TEXT,
                    birth_certificate_copy TEXT,
                    marriage_certificate_copy TEXT,
                    studies_documents_copy TEXT,
                    gdpr TEXT,
                    employment_request TEXT,
                    main_function_declaration TEXT,
                    family_doctor_medical_certificate TEXT,
                    work_permit TEXT,
                    dependents_declaration TEXT,
                    seniority_certificate TEXT,
                    liquidation_note TEXT,
                    contribution_stage_certificate TEXT,
                    occupational_medicine_approval TEXT,
                    employment_contract TEXT,
                    remote_work_addendum TEXT,
                    bonus_annex TEXT,
                    job_description TEXT,
                    health_insurance_house_declaration TEXT,
                    work_safety_training_file TEXT,
                    pre_employment_information TEXT,
                    workplace_safety TEXT,
                    equipment_handover_minutes TEXT,
                    cas_insurance_declaration TEXT,
                    confidentiality_agreement TEXT,
                    phone_equipment TEXT,
                    company_phone_number TEXT,
                    phone_subscription_owner TEXT,
                    sim_operator TEXT,
                    personal_phone_number TEXT,
                    gift_or_documents_address TEXT,
                    computer_equipment TEXT,
                    shipped_products TEXT,
                    bank_details TEXT,
                    working_document_name TEXT,
                    working_document_link TEXT,
                    source_values TEXT[] NOT NULL,
                    refreshed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp
                );
                """);
        executeStatement(db, """
                CREATE INDEX employee_sheet_data_full_name_idx
                    ON employee_sheet_data (full_name);
                """);
        executeStatement(db, """
                CREATE INDEX employee_sheet_data_cnp_idx
                    ON employee_sheet_data (cnp)
                    WHERE cnp IS NOT NULL;
                """);
    }
}
