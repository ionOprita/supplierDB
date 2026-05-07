package ro.sellfluence.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProductTable {
    public record ProductInfo(
            String pnk,
            String productCode,
            String name,
            UUID vendor,
            boolean continueToSell,
            boolean retracted,
            String category,
            String messageKeyword,
            String employeeSheetName,
            String employeeSheetTab,
            String model,
            BigDecimal productLengthMm,
            BigDecimal productWidthMm,
            BigDecimal productHeightMm,
            BigDecimal productWeightG,
            String ean,
            String brand,
            Integer warrantyMonths,
            BigDecimal importTax,
            String supplierProductCode,
            BigDecimal airTransportPcsPerCarton,
            BigDecimal airTransportKgPerCarton,
            BigDecimal airTransportLengthCmPerCarton,
            BigDecimal airTransportWidthCmPerCarton,
            BigDecimal airTransportHeightCmPerCarton,
            BigDecimal airTransportVolumeM3PerCarton,
            BigDecimal railTransportPcsPerCarton,
            BigDecimal railTransportKgPerCarton,
            BigDecimal railTransportLengthCmPerCarton,
            BigDecimal railTransportWidthCmPerCarton,
            BigDecimal railTransportHeightCmPerCarton,
            BigDecimal railTransportVolumeM3PerCarton,
            BigDecimal seaTransportPcsPerCarton,
            BigDecimal seaTransportKgPerCarton,
            BigDecimal seaTransportLengthCmPerCarton,
            BigDecimal seaTransportWidthCmPerCarton,
            BigDecimal seaTransportHeightCmPerCarton,
            BigDecimal seaTransportVolumeM3PerCarton,
            BigDecimal truckTransportPcsPerCarton,
            BigDecimal truckTransportKgPerCarton,
            BigDecimal truckTransportLengthCmPerCarton,
            BigDecimal truckTransportWidthCmPerCarton,
            BigDecimal truckTransportHeightCmPerCarton,
            BigDecimal truckTransportVolumeM3PerCarton,
            String emagLink,
            String emagTitle,
            String incomeProfitTax,
            Boolean vatPayer,
            BigDecimal emagSalePriceRon,
            BigDecimal emagCommission,
            Long offerIdConcept,
            Long offerIdSolutions,
            Long offerIdSolutionsFbe,
            Long offerIdJudiosConcept,
            Long offerIdJudiosConceptFbe,
            Long offerIdJudyCreativeStudiosFbe,
            Long offerIdSellfusion,
            Long offerIdSellfusionFbe,
            Long offerIdKoppel,
            Long offerIdKoppelFbe,
            String indexCategory,
            String division,
            String supracategory,
            String categoryName,
            String subcategory,
            String subsubcategory,
            String supracategoryCountry,
            String categoryCountry,
            Integer categoryId,
            Integer scmId,
            Integer docId,
            String indexedSubcategoryCountry,
            String bigCategory,
            Long emagAdsAutoId,
            Long emagAdsManualId,
            String gender,
            String manualVideoLink,
            String usageGuideLink,
            String usageSiteLink,
            String usageManualLink,
            String otherComments,
            String reviewCaller,
            String reportLink
    ) {
        public ProductInfo(
                String pnk,
                String productCode,
                String name,
                UUID vendor,
                boolean continueToSell,
                boolean retracted,
                String category,
                String messageKeyword,
                String employeeSheetName,
                String employeeSheetTab
        ) {
            this(
                    pnk,
                    productCode,
                    name,
                    vendor,
                    continueToSell,
                    retracted,
                    category,
                    messageKeyword,
                    employeeSheetName,
                    employeeSheetTab,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        public static final Comparator<String> nameComparatorString = (String name1, String name2) -> {
            ParsedName n1 = parse(name1);
            ParsedName n2 = parse(name2);

            // Compare letter rank first
            int cmp = Integer.compare(vendorGroup(n1.letter), vendorGroup(n2.letter));
            if (cmp != 0) return cmp;

            // Compare letter first
            cmp = Character.compare(n1.letter, n2.letter);
            if (cmp != 0) return cmp;

            // Compare number numerically
            cmp = Integer.compare(n1.number, n2.number);
            if (cmp != 0) return cmp;

            // Fallback: compare remaining text
            return n1.text.compareTo(n2.text);
        };

        public static final Comparator<ProductInfo> nameComparator =  Comparator.comparing(ProductInfo::name, nameComparatorString);

        public int vendorGroup() {
            return vendorGroup(name().charAt(0));
        }

        public static int vendorGroupNumber = 4;

        private static int vendorGroup(char letter) {
            return switch (Character.toUpperCase(letter)) {
                case 'Z' -> 0;
                case 'J' -> 1;
                case 'S' -> 2;
                case 'K' -> 3;
                default -> throw new IllegalArgumentException("Invalid vendor letter: " + letter);
            };
        }

        private static final Pattern namePattern = Pattern.compile("^([A-Z])\\.\\s*(\\d+)\\s*-\\s*(.*)$");

        private record ParsedName(char letter, int number, String text) {
        }

        private static ParsedName parse(String name) {
            Matcher m = namePattern.matcher(name);
            if (m.matches()) {
                char letter = m.group(1).charAt(0);
                int number = Integer.parseInt(m.group(2));
                String text = m.group(3);
                return new ParsedName(letter, number, text);
            }
            // Fallback: treat as "unknown" but still sortable
            return new ParsedName('\0', Integer.MAX_VALUE, name);
        }
    }

    public record ProductWithVendor(
            String pnk,
            String productCode,
            String name,
            String vendorName
    ) {
        public static final Comparator<ProductWithVendor> nameComparator = (p1, p2) -> ProductInfo.nameComparator.compare(
                toProductInfo(p1),
                toProductInfo(p2)
        );

        private static ProductInfo toProductInfo(ProductWithVendor product) {
            return new ProductInfo(
                    product.pnk(),
                    product.productCode(),
                    product.name(),
                    null,
                    false,
                    false,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    /**
     * Inserts or updates a product in the product table. If an insertion attempt fails because the product already exists,
     * the method attempts to update the product's information. If neither insertion nor update is successful,
     * a runtime exception is thrown.
     *
     * @param db          the database connection to use for the operation
     * @param productInfo the product information to be inserted or updated
     * @return always returns 0 upon successful completion
     * @throws SQLException     if a database access error occurs
     * @throws RuntimeException if the product cannot be inserted or updated
     */
    static int insertOrUpdateProduct(Connection db, ProductInfo productInfo) throws SQLException {
        var inserted = insertProduct(db, productInfo);
        if (inserted == 0) {
            var updated = updateProduct(db, productInfo);
            if (updated == 0) {
                throw new RuntimeException("Product could neither be inserted nor updated: %s.".formatted(productInfo));
            }
        }
        return 0;
    }

    /**
     * Retrieve all products from the product table.
     *
     * @param db database
     * @return list of all the products.
     * @throws SQLException if anything bad happens.
     */
    static List<ProductInfo> getProducts(Connection db) throws SQLException {
        var products = new ArrayList<ProductInfo>();
        try (var s = db.prepareStatement("""
                SELECT
                    emag_pnk,
                    product_code,
                    name,
                    vendor,
                    continue_to_sell,
                    retracted,
                    category,
                    message_keyword,
                    employee_sheet_name,
                    employee_sheet_tab,
                    model,
                    product_length_mm,
                    product_width_mm,
                    product_height_mm,
                    product_weight_g,
                    ean,
                    brand,
                    warranty_months,
                    import_tax,
                    supplier_product_code,
                    air_transport_pcs_per_carton,
                    air_transport_kg_per_carton,
                    air_transport_length_cm_per_carton,
                    air_transport_width_cm_per_carton,
                    air_transport_height_cm_per_carton,
                    air_transport_volume_m3_per_carton,
                    rail_transport_pcs_per_carton,
                    rail_transport_kg_per_carton,
                    rail_transport_length_cm_per_carton,
                    rail_transport_width_cm_per_carton,
                    rail_transport_height_cm_per_carton,
                    rail_transport_volume_m3_per_carton,
                    sea_transport_pcs_per_carton,
                    sea_transport_kg_per_carton,
                    sea_transport_length_cm_per_carton,
                    sea_transport_width_cm_per_carton,
                    sea_transport_height_cm_per_carton,
                    sea_transport_volume_m3_per_carton,
                    truck_transport_pcs_per_carton,
                    truck_transport_kg_per_carton,
                    truck_transport_length_cm_per_carton,
                    truck_transport_width_cm_per_carton,
                    truck_transport_height_cm_per_carton,
                    truck_transport_volume_m3_per_carton,
                    emag_link,
                    emag_title,
                    income_profit_tax,
                    vat_payer,
                    emag_sale_price_ron,
                    emag_commission,
                    offer_id_concept,
                    offer_id_solutions,
                    offer_id_solutions_fbe,
                    offer_id_judios_concept,
                    offer_id_judios_concept_fbe,
                    offer_id_judy_creative_studios_fbe,
                    offer_id_sellfusion,
                    offer_id_sellfusion_fbe,
                    offer_id_koppel,
                    offer_id_koppel_fbe,
                    index_category,
                    division,
                    supracategory,
                    category_name,
                    subcategory,
                    subsubcategory,
                    supracategory_country,
                    category_country,
                    category_id,
                    scm_id,
                    doc_id,
                    indexed_subcategory_country,
                    big_category,
                    emag_ads_auto_id,
                    emag_ads_manual_id,
                    gender,
                    manual_video_link,
                    usage_guide_link,
                    usage_site_link,
                    usage_manual_link,
                    other_comments,
                    review_caller,
                    report_link
                FROM product
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    products.add(mapProductInfo(rs));
                }
            }
        }
        return products;
    }

    /**
     * Retrieve all products from the product table, including vendor name.
     *
     * @param db database
     * @return list of all products with vendor name.
     * @throws SQLException if anything bad happens.
     */
    static List<ProductWithVendor> getProductsWithVendor(Connection db) throws SQLException {
        var products = new ArrayList<ProductWithVendor>();
        try (var s = db.prepareStatement("""
                SELECT p.emag_pnk, p.product_code, p.name, v.vendor_name
                FROM product AS p
                LEFT JOIN vendor AS v ON p.vendor = v.id
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    products.add(
                            new ProductWithVendor(
                                    rs.getString("emag_pnk"),
                                    rs.getString("product_code"),
                                    rs.getString("name"),
                                    rs.getString("vendor_name")
                            )
                    );
                }
            }
        }
        return products;
    }

    /**
     * Retrieves all product codes from the product table.
     *
     * @param db the database connection to use for querying product codes.
     * @return a list of product codes retrieved from the database.
     * @throws SQLException if an error occurs while accessing the database.
     */
    static List<String> getProductCodes(Connection db) throws SQLException {
        var products = new ArrayList<String>();
        try (var s = db.prepareStatement("SELECT product_code FROM product")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    products.add(rs.getString(1));
                }
            }
        }
        return products;
    }

    /**
     * Updates the employee_sheet_tab column of the product table for the product identified by the given PNK.
     *
     * @param db      the database connection to use for the update operation
     * @param pnk     the PNK of the product to be updated
     * @param tabName the new value to set for the employee_sheet_tab column
     * @return the number of rows affected by the update operation
     * @throws SQLException if a database access error occurs
     */
    static int updateProductTabByPNK(Connection db, String pnk, String tabName) throws SQLException {
        try (var s = db.prepareStatement("UPDATE product SET employee_sheet_tab = ? WHERE emag_pnk = ?")) {
            s.setString(1, tabName);
            s.setString(2, pnk);
            return s.executeUpdate();
        }
    }

    /**
     * Insert a product in the table that records our information about a product and associates our name and
     * category with the PNK used by eMAG.
     *
     * @param db          database
     * @param productInfo record mapping to column
     * @return 1 or 0 depending on whether the insertion was successful or not.
     * @throws SQLException if anything bad happens.
     */
    private static int insertProduct(Connection db, ProductInfo productInfo) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO product (
                    product_code,
                    emag_pnk,
                    name,
                    vendor,
                    continue_to_sell,
                    retracted,
                    category,
                    message_keyword,
                    employee_sheet_name,
                    employee_sheet_tab,
                    model,
                    product_length_mm,
                    product_width_mm,
                    product_height_mm,
                    product_weight_g,
                    ean,
                    brand,
                    warranty_months,
                    import_tax,
                    supplier_product_code,
                    air_transport_pcs_per_carton,
                    air_transport_kg_per_carton,
                    air_transport_length_cm_per_carton,
                    air_transport_width_cm_per_carton,
                    air_transport_height_cm_per_carton,
                    air_transport_volume_m3_per_carton,
                    rail_transport_pcs_per_carton,
                    rail_transport_kg_per_carton,
                    rail_transport_length_cm_per_carton,
                    rail_transport_width_cm_per_carton,
                    rail_transport_height_cm_per_carton,
                    rail_transport_volume_m3_per_carton,
                    sea_transport_pcs_per_carton,
                    sea_transport_kg_per_carton,
                    sea_transport_length_cm_per_carton,
                    sea_transport_width_cm_per_carton,
                    sea_transport_height_cm_per_carton,
                    sea_transport_volume_m3_per_carton,
                    truck_transport_pcs_per_carton,
                    truck_transport_kg_per_carton,
                    truck_transport_length_cm_per_carton,
                    truck_transport_width_cm_per_carton,
                    truck_transport_height_cm_per_carton,
                    truck_transport_volume_m3_per_carton,
                    emag_link,
                    emag_title,
                    income_profit_tax,
                    vat_payer,
                    emag_sale_price_ron,
                    emag_commission,
                    offer_id_concept,
                    offer_id_solutions,
                    offer_id_solutions_fbe,
                    offer_id_judios_concept,
                    offer_id_judios_concept_fbe,
                    offer_id_judy_creative_studios_fbe,
                    offer_id_sellfusion,
                    offer_id_sellfusion_fbe,
                    offer_id_koppel,
                    offer_id_koppel_fbe,
                    index_category,
                    division,
                    supracategory,
                    category_name,
                    subcategory,
                    subsubcategory,
                    supracategory_country,
                    category_country,
                    category_id,
                    scm_id,
                    doc_id,
                    indexed_subcategory_country,
                    big_category,
                    emag_ads_auto_id,
                    emag_ads_manual_id,
                    gender,
                    manual_video_link,
                    usage_guide_link,
                    usage_site_link,
                    usage_manual_link,
                    other_comments,
                    review_caller,
                    report_link
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?
                )
                ON CONFLICT DO NOTHING
                """)) {
            s.setObject(1, productInfo.productCode());
            s.setObject(2, productInfo.pnk());
            s.setObject(3, productInfo.name());
            s.setObject(4, productInfo.vendor());
            s.setBoolean(5, productInfo.continueToSell());
            s.setBoolean(6, productInfo.retracted());
            s.setObject(7, productInfo.category());
            s.setObject(8, productInfo.messageKeyword());
            s.setObject(9, productInfo.employeeSheetName());
            s.setObject(10, productInfo.employeeSheetTab());
            bindAdditionalFields(s, productInfo, 11);
            return s.executeUpdate();
        }
    }

    /**
     * Insert a product in the table that records our information about a product and associates our name and
     * category with the PNK used by eMAG.
     *
     * @param db          database
     * @param productInfo record mapping to column
     * @return 1 or 0 depending on whether the insertion was successful or not.
     * @throws SQLException if anything bad happens.
     */
    private static int updateProduct(Connection db, ProductInfo productInfo) throws SQLException {
        try (var s = db.prepareStatement("""
                UPDATE product
                SET emag_pnk = ?,
                    category = ?,
                    message_keyword = ?,
                    continue_to_sell = ?,
                    retracted = ?,
                    name = ?,
                    employee_sheet_name = ?,
                    employee_sheet_tab = ?,
                    vendor = ?,
                    model = ?,
                    product_length_mm = ?,
                    product_width_mm = ?,
                    product_height_mm = ?,
                    product_weight_g = ?,
                    ean = ?,
                    brand = ?,
                    warranty_months = ?,
                    import_tax = ?,
                    supplier_product_code = ?,
                    air_transport_pcs_per_carton = ?,
                    air_transport_kg_per_carton = ?,
                    air_transport_length_cm_per_carton = ?,
                    air_transport_width_cm_per_carton = ?,
                    air_transport_height_cm_per_carton = ?,
                    air_transport_volume_m3_per_carton = ?,
                    rail_transport_pcs_per_carton = ?,
                    rail_transport_kg_per_carton = ?,
                    rail_transport_length_cm_per_carton = ?,
                    rail_transport_width_cm_per_carton = ?,
                    rail_transport_height_cm_per_carton = ?,
                    rail_transport_volume_m3_per_carton = ?,
                    sea_transport_pcs_per_carton = ?,
                    sea_transport_kg_per_carton = ?,
                    sea_transport_length_cm_per_carton = ?,
                    sea_transport_width_cm_per_carton = ?,
                    sea_transport_height_cm_per_carton = ?,
                    sea_transport_volume_m3_per_carton = ?,
                    truck_transport_pcs_per_carton = ?,
                    truck_transport_kg_per_carton = ?,
                    truck_transport_length_cm_per_carton = ?,
                    truck_transport_width_cm_per_carton = ?,
                    truck_transport_height_cm_per_carton = ?,
                    truck_transport_volume_m3_per_carton = ?,
                    emag_link = ?,
                    emag_title = ?,
                    income_profit_tax = ?,
                    vat_payer = ?,
                    emag_sale_price_ron = ?,
                    emag_commission = ?,
                    offer_id_concept = ?,
                    offer_id_solutions = ?,
                    offer_id_solutions_fbe = ?,
                    offer_id_judios_concept = ?,
                    offer_id_judios_concept_fbe = ?,
                    offer_id_judy_creative_studios_fbe = ?,
                    offer_id_sellfusion = ?,
                    offer_id_sellfusion_fbe = ?,
                    offer_id_koppel = ?,
                    offer_id_koppel_fbe = ?,
                    index_category = ?,
                    division = ?,
                    supracategory = ?,
                    category_name = ?,
                    subcategory = ?,
                    subsubcategory = ?,
                    supracategory_country = ?,
                    category_country = ?,
                    category_id = ?,
                    scm_id = ?,
                    doc_id = ?,
                    indexed_subcategory_country = ?,
                    big_category = ?,
                    emag_ads_auto_id = ?,
                    emag_ads_manual_id = ?,
                    gender = ?,
                    manual_video_link = ?,
                    usage_guide_link = ?,
                    usage_site_link = ?,
                    usage_manual_link = ?,
                    other_comments = ?,
                    review_caller = ?,
                    report_link = ?
                WHERE product_code = ?
                """)) {
            s.setObject(1, productInfo.pnk());
            s.setObject(2, productInfo.category());
            s.setObject(3, productInfo.messageKeyword());
            s.setBoolean(4, productInfo.continueToSell());
            s.setBoolean(5, productInfo.retracted());
            s.setObject(6, productInfo.name());
            s.setObject(7, productInfo.employeeSheetName());
            s.setObject(8, productInfo.employeeSheetTab());
            s.setObject(9, productInfo.vendor());
            bindAdditionalFields(s, productInfo, 10);
            s.setObject(83, productInfo.productCode());
            return s.executeUpdate();
        }
    }

    private static ProductInfo mapProductInfo(java.sql.ResultSet rs) throws SQLException {
        return new ProductInfo(
                rs.getString("emag_pnk"),
                rs.getString("product_code"),
                rs.getString("name"),
                rs.getObject("vendor", UUID.class),
                rs.getBoolean("continue_to_sell"),
                rs.getBoolean("retracted"),
                rs.getString("category"),
                rs.getString("message_keyword"),
                rs.getString("employee_sheet_name"),
                rs.getString("employee_sheet_tab"),
                rs.getString("model"),
                rs.getBigDecimal("product_length_mm"),
                rs.getBigDecimal("product_width_mm"),
                rs.getBigDecimal("product_height_mm"),
                rs.getBigDecimal("product_weight_g"),
                rs.getString("ean"),
                rs.getString("brand"),
                rs.getObject("warranty_months", Integer.class),
                rs.getBigDecimal("import_tax"),
                rs.getString("supplier_product_code"),
                rs.getBigDecimal("air_transport_pcs_per_carton"),
                rs.getBigDecimal("air_transport_kg_per_carton"),
                rs.getBigDecimal("air_transport_length_cm_per_carton"),
                rs.getBigDecimal("air_transport_width_cm_per_carton"),
                rs.getBigDecimal("air_transport_height_cm_per_carton"),
                rs.getBigDecimal("air_transport_volume_m3_per_carton"),
                rs.getBigDecimal("rail_transport_pcs_per_carton"),
                rs.getBigDecimal("rail_transport_kg_per_carton"),
                rs.getBigDecimal("rail_transport_length_cm_per_carton"),
                rs.getBigDecimal("rail_transport_width_cm_per_carton"),
                rs.getBigDecimal("rail_transport_height_cm_per_carton"),
                rs.getBigDecimal("rail_transport_volume_m3_per_carton"),
                rs.getBigDecimal("sea_transport_pcs_per_carton"),
                rs.getBigDecimal("sea_transport_kg_per_carton"),
                rs.getBigDecimal("sea_transport_length_cm_per_carton"),
                rs.getBigDecimal("sea_transport_width_cm_per_carton"),
                rs.getBigDecimal("sea_transport_height_cm_per_carton"),
                rs.getBigDecimal("sea_transport_volume_m3_per_carton"),
                rs.getBigDecimal("truck_transport_pcs_per_carton"),
                rs.getBigDecimal("truck_transport_kg_per_carton"),
                rs.getBigDecimal("truck_transport_length_cm_per_carton"),
                rs.getBigDecimal("truck_transport_width_cm_per_carton"),
                rs.getBigDecimal("truck_transport_height_cm_per_carton"),
                rs.getBigDecimal("truck_transport_volume_m3_per_carton"),
                rs.getString("emag_link"),
                rs.getString("emag_title"),
                rs.getString("income_profit_tax"),
                rs.getObject("vat_payer", Boolean.class),
                rs.getBigDecimal("emag_sale_price_ron"),
                rs.getBigDecimal("emag_commission"),
                rs.getObject("offer_id_concept", Long.class),
                rs.getObject("offer_id_solutions", Long.class),
                rs.getObject("offer_id_solutions_fbe", Long.class),
                rs.getObject("offer_id_judios_concept", Long.class),
                rs.getObject("offer_id_judios_concept_fbe", Long.class),
                rs.getObject("offer_id_judy_creative_studios_fbe", Long.class),
                rs.getObject("offer_id_sellfusion", Long.class),
                rs.getObject("offer_id_sellfusion_fbe", Long.class),
                rs.getObject("offer_id_koppel", Long.class),
                rs.getObject("offer_id_koppel_fbe", Long.class),
                rs.getString("index_category"),
                rs.getString("division"),
                rs.getString("supracategory"),
                rs.getString("category_name"),
                rs.getString("subcategory"),
                rs.getString("subsubcategory"),
                rs.getString("supracategory_country"),
                rs.getString("category_country"),
                rs.getObject("category_id", Integer.class),
                rs.getObject("scm_id", Integer.class),
                rs.getObject("doc_id", Integer.class),
                rs.getString("indexed_subcategory_country"),
                rs.getString("big_category"),
                rs.getObject("emag_ads_auto_id", Long.class),
                rs.getObject("emag_ads_manual_id", Long.class),
                rs.getString("gender"),
                rs.getString("manual_video_link"),
                rs.getString("usage_guide_link"),
                rs.getString("usage_site_link"),
                rs.getString("usage_manual_link"),
                rs.getString("other_comments"),
                rs.getString("review_caller"),
                rs.getString("report_link")
        );
    }

    private static int bindAdditionalFields(java.sql.PreparedStatement s, ProductInfo productInfo, int index) throws SQLException {
        s.setObject(index++, productInfo.model());
        s.setObject(index++, productInfo.productLengthMm());
        s.setObject(index++, productInfo.productWidthMm());
        s.setObject(index++, productInfo.productHeightMm());
        s.setObject(index++, productInfo.productWeightG());
        s.setObject(index++, productInfo.ean());
        s.setObject(index++, productInfo.brand());
        s.setObject(index++, productInfo.warrantyMonths());
        s.setObject(index++, productInfo.importTax());
        s.setObject(index++, productInfo.supplierProductCode());
        s.setObject(index++, productInfo.airTransportPcsPerCarton());
        s.setObject(index++, productInfo.airTransportKgPerCarton());
        s.setObject(index++, productInfo.airTransportLengthCmPerCarton());
        s.setObject(index++, productInfo.airTransportWidthCmPerCarton());
        s.setObject(index++, productInfo.airTransportHeightCmPerCarton());
        s.setObject(index++, productInfo.airTransportVolumeM3PerCarton());
        s.setObject(index++, productInfo.railTransportPcsPerCarton());
        s.setObject(index++, productInfo.railTransportKgPerCarton());
        s.setObject(index++, productInfo.railTransportLengthCmPerCarton());
        s.setObject(index++, productInfo.railTransportWidthCmPerCarton());
        s.setObject(index++, productInfo.railTransportHeightCmPerCarton());
        s.setObject(index++, productInfo.railTransportVolumeM3PerCarton());
        s.setObject(index++, productInfo.seaTransportPcsPerCarton());
        s.setObject(index++, productInfo.seaTransportKgPerCarton());
        s.setObject(index++, productInfo.seaTransportLengthCmPerCarton());
        s.setObject(index++, productInfo.seaTransportWidthCmPerCarton());
        s.setObject(index++, productInfo.seaTransportHeightCmPerCarton());
        s.setObject(index++, productInfo.seaTransportVolumeM3PerCarton());
        s.setObject(index++, productInfo.truckTransportPcsPerCarton());
        s.setObject(index++, productInfo.truckTransportKgPerCarton());
        s.setObject(index++, productInfo.truckTransportLengthCmPerCarton());
        s.setObject(index++, productInfo.truckTransportWidthCmPerCarton());
        s.setObject(index++, productInfo.truckTransportHeightCmPerCarton());
        s.setObject(index++, productInfo.truckTransportVolumeM3PerCarton());
        s.setObject(index++, productInfo.emagLink());
        s.setObject(index++, productInfo.emagTitle());
        s.setObject(index++, productInfo.incomeProfitTax());
        s.setObject(index++, productInfo.vatPayer());
        s.setObject(index++, productInfo.emagSalePriceRon());
        s.setObject(index++, productInfo.emagCommission());
        s.setObject(index++, productInfo.offerIdConcept());
        s.setObject(index++, productInfo.offerIdSolutions());
        s.setObject(index++, productInfo.offerIdSolutionsFbe());
        s.setObject(index++, productInfo.offerIdJudiosConcept());
        s.setObject(index++, productInfo.offerIdJudiosConceptFbe());
        s.setObject(index++, productInfo.offerIdJudyCreativeStudiosFbe());
        s.setObject(index++, productInfo.offerIdSellfusion());
        s.setObject(index++, productInfo.offerIdSellfusionFbe());
        s.setObject(index++, productInfo.offerIdKoppel());
        s.setObject(index++, productInfo.offerIdKoppelFbe());
        s.setObject(index++, productInfo.indexCategory());
        s.setObject(index++, productInfo.division());
        s.setObject(index++, productInfo.supracategory());
        s.setObject(index++, productInfo.categoryName());
        s.setObject(index++, productInfo.subcategory());
        s.setObject(index++, productInfo.subsubcategory());
        s.setObject(index++, productInfo.supracategoryCountry());
        s.setObject(index++, productInfo.categoryCountry());
        s.setObject(index++, productInfo.categoryId());
        s.setObject(index++, productInfo.scmId());
        s.setObject(index++, productInfo.docId());
        s.setObject(index++, productInfo.indexedSubcategoryCountry());
        s.setObject(index++, productInfo.bigCategory());
        s.setObject(index++, productInfo.emagAdsAutoId());
        s.setObject(index++, productInfo.emagAdsManualId());
        s.setObject(index++, productInfo.gender());
        s.setObject(index++, productInfo.manualVideoLink());
        s.setObject(index++, productInfo.usageGuideLink());
        s.setObject(index++, productInfo.usageSiteLink());
        s.setObject(index++, productInfo.usageManualLink());
        s.setObject(index++, productInfo.otherComments());
        s.setObject(index++, productInfo.reviewCaller());
        s.setObject(index++, productInfo.reportLink());
        return index;
    }
}
