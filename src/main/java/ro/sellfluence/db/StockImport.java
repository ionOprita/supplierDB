package ro.sellfluence.db;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stock entry, editable snapshots and server-calculated amounts. Rates are decimal fractions. */
public final class StockImport {
    private StockImport() {}

    public record Document(UUID id, long revision, Map<String,String> fields, List<Line> lines,
                           List<Transfer> transfers, List<Taric> taric, List<Invoice> invoices) {
        public Document {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            lines = lines == null ? List.of() : List.copyOf(lines);
            transfers = transfers == null ? List.of() : List.copyOf(transfers);
            taric = taric == null ? List.of() : List.copyOf(taric);
            invoices = invoices == null ? List.of() : List.copyOf(invoices);
        }

        public Document version(UUID id, long revision) {
            return new Document(id, revision, fields, lines, transfers, taric, invoices);
        }
    }

    public record Line(String productCode, String model, String pnk, BigDecimal quantity, BigDecimal unitUsd,
                       BigDecimal piecesPerCarton, BigDecimal kgPerCarton, BigDecimal m3PerCarton, BigDecimal dutyRate) {}
    public record Transfer(String kind, String date, String reference, BigDecimal ron, BigDecimal usd, BigDecimal rate) {}
    public record Taric(String code, BigDecimal netKg, BigDecimal grossKg, BigDecimal quantity) {}
    public record Invoice(String kind, String reference) {}
    public record Calculation(Map<String,BigDecimal> totals, List<Map<String,BigDecimal>> lines,
                              String eta, List<String> warnings, String freightBasis) {}
    public record Stored(Document document, Calculation calculation) {}
    public record Group(String key, List<String> companies) {}
    public record Packing(BigDecimal piecesPerCarton, BigDecimal kgPerCarton, BigDecimal m3PerCarton) {}
    public record CatalogProduct(String productCode, String name, String model, String pnk, BigDecimal dutyRate,
                                 Map<String,Packing> packing) {}
    public record Summary(UUID id, long revision, String title, String reference, String sellingCompany,
                          String orderDate, String status, String method, BigDecimal units,
                          String arrivalDate, String eta, BigDecimal totalRon) {}
    public record Source(String key, int column, String fingerprint, Document document) {}
    public record SourceChange(String reference, int column, boolean inserted, boolean replacesLocalEdit) {}
    public record Reconcile(int inserted, int updated, int unchanged, List<String> conflicts,
                            List<String> missing, List<SourceChange> changes) {}
}
