package ro.sellfluence.db;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only inputs for a product report; each aggregate receives the same explicit intervals. */
public interface ProductPerformanceSource {
    /** Whether at least one selected order snapshot supplied a quantity, including zero. */
    record ProductOrders(long units, boolean observed) {}

    List<ProductPerformanceHistoryTable.Observation> getProductPerformanceHistory(UUID vendorId, String productCode)
            throws SQLException;

    Set<LocalDate> getProductPerformanceAdsReportDates(UUID vendorId, LocalDate start, LocalDate end)
            throws SQLException;

    List<ProductPerformanceTable.DailyAdset> getProductPerformanceAdsets(UUID vendorId, String pnk)
            throws SQLException;

    Optional<LocalDate> getFirstProductPerformanceActivity(UUID vendorId, String productCode, String pnk)
            throws SQLException;

    List<OffersTable.DailyProductOffer> getProductPerformanceOffers(UUID vendorId, String pnk,
                                                                  LocalDate start, LocalDate end)
            throws SQLException;

    /** VAT-inclusive amounts keyed by period start only when order lines contribute, including measured zero. */
    Map<LocalDate, BigDecimal> getObservedGMVForProductPeriods(
            UUID vendorId, String productCode, Map<LocalDate, LocalDate> periods) throws SQLException;

    /** VAT-exclusive amounts on the same observed periods, including net seller voucher and storno adjustments. */
    Map<LocalDate, BigDecimal> getObservedNetSalesForProductPeriods(
            UUID vendorId, String productCode, Map<LocalDate, LocalDate> periods) throws SQLException;

    Map<LocalDate, ProductOrders> getProductPerformanceOrders(
            UUID vendorId, String pnk, Map<LocalDate, LocalDate> periods) throws SQLException;

    Map<LocalDate, OffersTable.ProductVisits> getProductVisitsForDates(UUID vendorId, String pnk,
                                                                    Collection<LocalDate> dates)
            throws SQLException;

    Map<LocalDate, OffersTable.ProductHotness> getProductHotnessForDates(UUID vendorId, String pnk,
                                                                      Collection<LocalDate> dates)
            throws SQLException;
}
