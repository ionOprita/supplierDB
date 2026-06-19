package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class EmployeeDataTable {

    public static final int SOURCE_COLUMN_COUNT = 140;

    public enum EmployeeColumn {
        SHEET_INDEX(1, "sheet_index"),
        FULL_NAME(2, "full_name"),
        FIRST_NAME(3, "first_name"),
        LAST_NAME(4, "last_name"),
        CITY(5, "city"),
        STREET(6, "street"),
        STREET_NUMBER(7, "street_number"),
        BUILDING(8, "building"),
        STAIRCASE(9, "staircase"),
        FLOOR(10, "floor"),
        APARTMENT(11, "apartment"),
        COUNTY_OR_SECTOR(12, "county_or_sector"),
        ID_SERIES(13, "id_series"),
        ID_NUMBER(14, "id_number"),
        ID_ISSUED_BY(15, "id_issued_by"),
        ID_ISSUED_DATE(16, "id_issued_date"),
        CNP(17, "cnp"),
        COMPANY(18, "company"),
        GENDER(19, "gender"),
        DEPARTMENT(20, "department"),
        JOB_TITLE(21, "job_title"),
        EMPLOYEE_CODE(22, "employee_code"),
        CIM_NUMBER(23, "cim_number"),
        CIM_DATE(24, "cim_date"),
        ACTIVITY_START_DATE(25, "activity_start_date"),
        CIM_END_DATE(26, "cim_end_date"),
        PROBATION_PERIOD_DAYS(27, "probation_period_days"),
        WORK_HOURS_PER_DAY(28, "work_hours_per_day"),
        WORK_HOURS_PER_WEEK(29, "work_hours_per_week"),
        WORK_START_TIME(30, "work_start_time"),
        WORK_END_TIME(31, "work_end_time"),
        WORKING_DAYS(32, "working_days"),
        PAID_VACATION_DAYS_PER_YEAR(33, "paid_vacation_days_per_year"),
        GROSS_BASE_SALARY(34, "gross_base_salary"),
        TRACK_VACATION_DAYS(35, "track_vacation_days"),
        LAST_DOCUMENTS_UPDATE(36, "last_documents_update"),
        UPDATED_SECTIONS(37, "updated_sections"),
        BIRTHDAY(38, "birthday"),
        CV_AND_REFERENCES(40, "cv_and_references"),
        ID_COPY(41, "id_copy"),
        DRIVING_LICENSE_COPY(42, "driving_license_copy"),
        BIRTH_CERTIFICATE_COPY(43, "birth_certificate_copy"),
        MARRIAGE_CERTIFICATE_COPY(44, "marriage_certificate_copy"),
        STUDIES_DOCUMENTS_COPY(45, "studies_documents_copy"),
        GDPR(46, "gdpr"),
        EMPLOYMENT_REQUEST(47, "employment_request"),
        MAIN_FUNCTION_DECLARATION(48, "main_function_declaration"),
        FAMILY_DOCTOR_MEDICAL_CERTIFICATE(49, "family_doctor_medical_certificate"),
        WORK_PERMIT(50, "work_permit"),
        DEPENDENTS_DECLARATION(51, "dependents_declaration"),
        SENIORITY_CERTIFICATE(52, "seniority_certificate"),
        LIQUIDATION_NOTE(53, "liquidation_note"),
        CONTRIBUTION_STAGE_CERTIFICATE(54, "contribution_stage_certificate"),
        OCCUPATIONAL_MEDICINE_APPROVAL(55, "occupational_medicine_approval"),
        EMPLOYMENT_CONTRACT(56, "employment_contract"),
        REMOTE_WORK_ADDENDUM(57, "remote_work_addendum"),
        BONUS_ANNEX(58, "bonus_annex"),
        JOB_DESCRIPTION(59, "job_description"),
        HEALTH_INSURANCE_HOUSE_DECLARATION(60, "health_insurance_house_declaration"),
        WORK_SAFETY_TRAINING_FILE(61, "work_safety_training_file"),
        PRE_EMPLOYMENT_INFORMATION(62, "pre_employment_information"),
        WORKPLACE_SAFETY(63, "workplace_safety"),
        EQUIPMENT_HANDOVER_MINUTES(64, "equipment_handover_minutes"),
        CAS_INSURANCE_DECLARATION(65, "cas_insurance_declaration"),
        CONFIDENTIALITY_AGREEMENT(66, "confidentiality_agreement"),
        PHONE_EQUIPMENT(68, "phone_equipment"),
        COMPANY_PHONE_NUMBER(69, "company_phone_number"),
        PHONE_SUBSCRIPTION_OWNER(70, "phone_subscription_owner"),
        SIM_OPERATOR(71, "sim_operator"),
        PERSONAL_PHONE_NUMBER(72, "personal_phone_number"),
        GIFT_OR_DOCUMENTS_ADDRESS(73, "gift_or_documents_address"),
        COMPUTER_EQUIPMENT(74, "computer_equipment"),
        SHIPPED_PRODUCTS(75, "shipped_products"),
        BANK_DETAILS(76, "bank_details"),
        WORKING_DOCUMENT_NAME(81, "working_document_name"),
        WORKING_DOCUMENT_LINK(82, "working_document_link");

        private final int sheetIndex;
        private final String dbColumn;

        EmployeeColumn(int sheetIndex, String dbColumn) {
            this.sheetIndex = sheetIndex;
            this.dbColumn = dbColumn;
        }

        public int sheetIndex() {
            return sheetIndex;
        }

        public String dbColumn() {
            return dbColumn;
        }
    }

    public record EmployeeInfo(int sourceRowNumber, List<String> sourceValues) {
        public EmployeeInfo {
            if (sourceRowNumber <= 0) {
                throw new IllegalArgumentException("sourceRowNumber must be positive.");
            }
            sourceValues = paddedSourceValues(sourceValues);
        }

        public String fullName() {
            return value(EmployeeColumn.FULL_NAME);
        }

        public String value(EmployeeColumn column) {
            Objects.requireNonNull(column);
            return sourceValues.get(column.sheetIndex() - 1);
        }
    }

    static List<EmployeeInfo> getEmployeeData(Connection db) throws SQLException {
        var employees = new ArrayList<EmployeeInfo>();
        try (var s = db.prepareStatement("""
                SELECT *
                FROM employee_sheet_data
                ORDER BY source_row_number
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    employees.add(mapEmployeeInfo(rs));
                }
            }
        }
        return employees;
    }

    static int insertEmployeeData(Connection db, EmployeeInfo employee) throws SQLException {
        try (var s = db.prepareStatement(insertSql())) {
            bindInsert(s, employee);
            return s.executeUpdate();
        }
    }

    static int updateEmployeeData(Connection db, EmployeeInfo employee) throws SQLException {
        try (var s = db.prepareStatement(updateSql())) {
            var mergedEmployee = new EmployeeInfo(employee.sourceRowNumber(), mergeSourceValuesForUpdate(db, employee));
            int index = 1;
            for (var column : EmployeeColumn.values()) {
                s.setObject(index++, mergedEmployee.value(column));
            }
            s.setArray(index++, db.createArrayOf("text", mergedEmployee.sourceValues().toArray(String[]::new)));
            s.setInt(index, mergedEmployee.sourceRowNumber());
            return s.executeUpdate();
        }
    }

    static int nextSourceRowNumber(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT COALESCE(MAX(source_row_number), 2) + 1
                FROM employee_sheet_data
                """);
             var rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int replaceEmployeeData(Connection db, List<EmployeeInfo> employees) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(employees);

        try (var s = db.prepareStatement("DELETE FROM employee_sheet_data")) {
            s.executeUpdate();
        }

        if (employees.isEmpty()) {
            return 0;
        }

        try (var s = db.prepareStatement(insertSql())) {
            for (var employee : employees) {
                bindInsert(s, employee);
                s.addBatch();
            }
            return insertedRows(s.executeBatch());
        }
    }

    private static EmployeeInfo mapEmployeeInfo(ResultSet rs) throws SQLException {
        var sourceValues = sourceValues(rs);
        for (var column : EmployeeColumn.values()) {
            sourceValues.set(column.sheetIndex() - 1, rs.getString(column.dbColumn()));
        }
        return new EmployeeInfo(rs.getInt("source_row_number"), sourceValues);
    }

    private static ArrayList<String> sourceValues(ResultSet rs) throws SQLException {
        var sqlArray = rs.getArray("source_values");
        if (sqlArray == null) {
            return paddedMutableSourceValues(List.of());
        }
        var values = (String[]) sqlArray.getArray();
        return paddedMutableSourceValues(Arrays.asList(values));
    }

    private static List<String> mergeSourceValuesForUpdate(Connection db, EmployeeInfo employee) throws SQLException {
        var sourceValues = existingSourceValues(db, employee.sourceRowNumber());
        for (var column : EmployeeColumn.values()) {
            sourceValues.set(column.sheetIndex() - 1, employee.value(column));
        }
        return sourceValues;
    }

    private static ArrayList<String> existingSourceValues(Connection db, int sourceRowNumber) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT source_values
                FROM employee_sheet_data
                WHERE source_row_number = ?
                """)) {
            s.setInt(1, sourceRowNumber);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return sourceValues(rs);
                }
            }
        }
        return paddedMutableSourceValues(List.of());
    }

    private static void bindInsert(java.sql.PreparedStatement s, EmployeeInfo employee) throws SQLException {
        int index = 1;
        s.setInt(index++, employee.sourceRowNumber());
        for (var column : EmployeeColumn.values()) {
            s.setObject(index++, employee.value(column));
        }
        s.setArray(index, s.getConnection().createArrayOf("text", employee.sourceValues().toArray(String[]::new)));
    }

    private static int insertedRows(int[] batchResult) {
        int inserted = 0;
        for (int result : batchResult) {
            if (result == Statement.SUCCESS_NO_INFO) {
                inserted++;
            } else if (result > 0) {
                inserted += result;
            }
        }
        return inserted;
    }

    private static String insertSql() {
        var columns = new ArrayList<String>();
        columns.add("source_row_number");
        for (var column : EmployeeColumn.values()) {
            columns.add(column.dbColumn());
        }
        columns.add("source_values");

        return """
                INSERT INTO employee_sheet_data (
                    %s
                )
                VALUES (
                    %s
                )
                """.formatted(
                String.join(",\n                    ", columns),
                String.join(", ", Collections.nCopies(columns.size(), "?"))
        );
    }

    private static String updateSql() {
        var assignments = new ArrayList<String>();
        for (var column : EmployeeColumn.values()) {
            assignments.add(column.dbColumn() + " = ?");
        }
        assignments.add("source_values = ?");
        assignments.add("refreshed_at = current_timestamp");

        return """
                UPDATE employee_sheet_data
                SET %s
                WHERE source_row_number = ?
                """.formatted(String.join(",\n                    ", assignments));
    }

    private static List<String> paddedSourceValues(List<String> sourceValues) {
        return Collections.unmodifiableList(paddedMutableSourceValues(sourceValues));
    }

    private static ArrayList<String> paddedMutableSourceValues(List<String> sourceValues) {
        Objects.requireNonNull(sourceValues);
        var padded = new ArrayList<String>(SOURCE_COLUMN_COUNT);
        for (int i = 0; i < SOURCE_COLUMN_COUNT; i++) {
            padded.add(i < sourceValues.size() ? sourceValues.get(i) : null);
        }
        return padded;
    }
}
