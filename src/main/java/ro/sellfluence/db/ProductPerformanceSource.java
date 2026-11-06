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
    List<ProductPerformanceHistoryTable.Observation> getProductPerformanceHistory(UUID vendorId, String productCode)
            throws SQLException;

    Set<LocalDate> getProductPerformanceAdsReportDates(UUID vendorId, LocalDate start, LocalDate end)
            throws SQLException;

    List<ProductPerformanceTable.DailyAdset> getProductPerformanceAdsets(UUID vendorId, String pnk)
            throws SQLException;

    Optional<LocalDate> getFirstProductPerformanceActivity(UUID vendorId, String productCode, String pnk)
            throws SQLException;

    Optional<LocalDate> getLatestCompletedProductVisitsSnapshotDate(UUID vendorId, LocalDate today)
            throws SQLException;

    List<OffersTable.DailyProductOffer> getProductPerformanceOffers(UUID vendorId, String pnk,
                                                                  LocalDate start, LocalDate end)
            throws SQLException;

    Map<LocalDate, BigDecimal> computeGMVForProductPeriods(UUID vendorId, String productCode,
                                                        Map<LocalDate, LocalDate> periods)
            throws SQLException;

    /** Net order-line sales, including net seller voucher and storno adjustments, for each inclusive period. */
    Map<LocalDate, BigDecimal> computeNetSalesForProductPeriods(UUID vendorId, String productCode,
                                                             Map<LocalDate, LocalDate> periods)
            throws SQLException;

    Map<LocalDate, Long> countOrdersForProductPeriods(UUID vendorId, String pnk, Map<LocalDate, LocalDate> periods)
            throws SQLException;

    Map<LocalDate, OffersTable.ProductVisits> getProductVisitsForDates(UUID vendorId, String pnk,
                                                                    Collection<LocalDate> dates)
            throws SQLException;

    Map<LocalDate, OffersTable.ProductHotness> getProductHotnessForDates(UUID vendorId, String pnk,
                                                                      Collection<LocalDate> dates)
            throws SQLException;
}
