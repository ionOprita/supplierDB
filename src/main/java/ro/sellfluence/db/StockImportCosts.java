package ro.sellfluence.db;

import ro.sellfluence.db.StockImport.Calculation;
import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.db.StockImport.Transfer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.math.BigDecimal.ZERO;

/** All money, quantities and allocation shares retain decimal precision until display. */
public final class StockImportCosts {
    private StockImportCosts() {}
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    static String mode(String value) {
        if (value == null) return "";
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "AIR", "AVION", "AERIAN" -> "AIR";
            case "SEA", "MARE", "MARITIM", "VAPOR" -> "SEA";
            case "RAIL", "TREN", "FEROVIAR" -> "RAIL";
            case "TRUCK", "CAMION", "RUTIER" -> "TRUCK";
            default -> value.strip().toUpperCase(Locale.ROOT);
        };
    }

    /** Resolve the method's settings once; persist this snapshot with an employee-entered import. */
    static Document snapshot(Document document) {
        var fields = new LinkedHashMap<>(document.fields());
        String threshold;
        String transit;
        switch (mode(fields.get("method"))) {
            case "AIR" -> { threshold = "200"; transit = "12"; }
            case "RAIL" -> { threshold = "0"; transit = "42"; }
            case "SEA" -> { threshold = "1000"; transit = "90"; }
            case "TRUCK" -> { threshold = "167"; transit = "25"; }
            default -> { return document; }
        }
        if (fields.getOrDefault("densityThreshold", "").isBlank()) fields.put("densityThreshold",threshold);
        if (fields.getOrDefault("transitDays", "").isBlank()) fields.put("transitDays",transit);
        return new Document(document.id(), document.revision(), fields, document.lines(),
                document.transfers(), document.taric(), document.invoices());
    }

    static BigDecimal decimal(Map<String,String> fields, String key) {
        var value = fields.get(key);
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valoare numerică nevalidă: " + key);
        }
    }

    static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Data trebuie să aibă formatul AAAA-LL-ZZ.");
        }
    }

    private static BigDecimal times(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.multiply(b, PRECISION);
    }
    private static BigDecimal over(BigDecimal a, BigDecimal b) {
        return a == null || b == null || b.signum() == 0 ? null : a.divide(b, PRECISION);
    }
    private static BigDecimal plus(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.add(b);
    }
    private static BigDecimal minus(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }
    private static BigDecimal first(BigDecimal a, BigDecimal b) { return a == null ? b : a; }
    private static void put(Map<String,BigDecimal> target, String key, BigDecimal value) {
        if (value != null) target.put(key, value);
    }

    /** An explicit zero counts as a recorded payment. An empty payment cell is unknown. */
    private static BigDecimal payment(List<Transfer> transfers, String kind, boolean usd) {
        BigDecimal sum = null;
        for (var t : transfers) {
            if (!kind.equals(t.kind())) continue;
            var value = usd ? first(t.usd(), over(t.ron(), t.rate())) : first(t.ron(), times(t.usd(), t.rate()));
            if (value != null) sum = first(sum, ZERO).add(value);
        }
        return sum;
    }

    private static BigDecimal sum(List<Map<String,BigDecimal>> lines, String key) {
        if (lines.isEmpty()) return null;
        BigDecimal result = ZERO;
        for (var line : lines) {
            var value = line.get(key);
            if (value == null) return null;
            result = result.add(value);
        }
        return result;
    }

    /** The workbook sums measured cartons only. An unmeasured spare has no freight allocation. */
    private static BigDecimal sumAvailable(List<Map<String,BigDecimal>> lines, String key) {
        return lines.stream().map(row -> row.get(key)).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal::add).orElse(null);
    }

    private static BigDecimal quantityValue(BigDecimal quantity, BigDecimal unit) {
        return quantity != null && quantity.signum() == 0 ? ZERO : times(quantity, unit);
    }

    public static Calculation calculate(Document d) {
        var fields = snapshot(d).fields();
        boolean workbook = "sheet".equals(fields.get("costConvention"));
        var totals = new LinkedHashMap<String,BigDecimal>();
        var lines = new ArrayList<Map<String,BigDecimal>>();
        var warnings = new LinkedHashSet<String>();
        var purchasePaidRon = payment(d.transfers(), "purchase", false);
        var purchasePaidUsd = payment(d.transfers(), "purchase", true);
        var rate = first(over(purchasePaidRon, purchasePaidUsd), decimal(fields, "purchaseRate"));
        var freightPaidRon = payment(d.transfers(), "freight", false);
        var freightPaidUsd = payment(d.transfers(), "freight", true);
        var freightRate = first(over(freightPaidRon, freightPaidUsd), decimal(fields, "freightRate"));
        var invoiceUsd = decimal(fields, "freightUsd");
        var invoiceRon = decimal(fields, "freightRon");
        // Invoice totals govern balances. In a workbook with no separate invoice amount the transfer is the bill.
        boolean separateInvoice = invoiceUsd != null || invoiceRon != null;
        var billedFreightUsd = separateInvoice ? first(invoiceUsd, over(invoiceRon, freightRate)) : freightPaidUsd;
        var billedFreightRon = separateInvoice ? first(invoiceRon, times(invoiceUsd, freightRate)) : freightPaidRon;
        var freight = first(freightPaidRon, billedFreightRon);
        boolean missingFreight = freight == null;
        if (workbook && missingFreight) freight = ZERO;  // SUM of an empty source payment block.
        var dutyPaid = payment(d.transfers(), "duty", false);
        var vatPaid = payment(d.transfers(), "vat", false);
        var vatRate = decimal(fields, "vatRate");

        for (var l : d.lines()) {
            var row = new LinkedHashMap<String,BigDecimal>();
            put(row, "quantity", l.quantity());
            put(row, "purchaseUsd", quantityValue(l.quantity(), l.unitUsd()));
            var unitRon = l.unitUsd() != null && l.unitUsd().signum() == 0 ? ZERO : times(l.unitUsd(), workbook ? first(rate, ZERO) : rate);
            put(row, "purchaseUnitRon", unitRon);
            put(row, "purchaseRon", quantityValue(l.quantity(), unitRon));
            var cartons = over(l.quantity(), l.piecesPerCarton());
            put(row, "cartons", cartons);
            put(row, "kg", times(cartons, l.kgPerCarton()));
            put(row, "m3", times(cartons, l.m3PerCarton()));
            lines.add(row);
        }
        var kg = sumAvailable(lines, "kg");
        var m3 = sumAvailable(lines, "m3");
        var density = over(kg, m3);
        BigDecimal threshold = decimal(fields, "densityThreshold");
        String basis = null;
        if (kg != null && kg.signum() > 0 && (m3 == null || m3.signum() == 0)) basis = "kg";
        else if (m3 != null && m3.signum() > 0 && (kg == null || kg.signum() == 0)) basis = "m3";
        else if (density != null && threshold != null && m3.signum() > 0) {
            basis = density.compareTo(threshold) > 0 ? "kg" : "m3";
        }
        var basisTotal = "kg".equals(basis) ? kg : "m3".equals(basis) ? m3 : null;
        var purchase = sum(lines, "purchaseRon");
        var customsBase = plus(purchase, freight);
        var dutyRatio = over(dutyPaid, customsBase);
        for (int i = 0; i < lines.size(); i++) {
            var row = lines.get(i);
            var line = d.lines().get(i);
            var share = basis == null ? null : over(row.get(basis), basisTotal);
            var freightUnit = over(times(freight, share), line.quantity());
            var base = plus(row.get("purchaseUnitRon"), first(freightUnit, ZERO));
            if (!workbook && freight == null) base = null;
            var dutyRate = workbook ? first(line.dutyRate(), ZERO) : line.dutyRate();
            var dutyUnit = dutyPaid != null && dutyPaid.signum() == 0 ? ZERO
                    : times(base, dutyPaid == null ? dutyRate : dutyRatio);
            if (workbook && (row.get("purchaseUnitRon") == null || row.get("purchaseUnitRon").signum() == 0))
                dutyUnit = null;
            put(row, "freightShare", share);
            put(row, "freightUnitRon", freightUnit);
            put(row, "dutyUnitRon", dutyUnit);
            put(row, "dutyRon", quantityValue(line.quantity(), dutyUnit));
            put(row, "beforeVatUnitRon", plus(base, dutyUnit));
        }
        var estimatedDuty = workbook ? first(sumAvailable(lines, "dutyRon"), ZERO) : sum(lines, "dutyRon");
        var duty = first(dutyPaid, estimatedDuty);
        var taxable = plus(customsBase, duty);
        var vatRatio = vatPaid == null ? vatRate : over(vatPaid, taxable);
        for (int i = 0; i < lines.size(); i++) {
            var row = lines.get(i);
            var beforeVat = row.get("beforeVatUnitRon");
            var vatUnit = vatPaid != null && vatPaid.signum() == 0 ? ZERO : times(beforeVat, vatRatio);
            put(row, "vatUnitRon", vatUnit);
            put(row, "landedUnitRon", plus(beforeVat, vatUnit));
        }
        var units = sum(lines, "quantity");
        var usd = sum(lines, "purchaseUsd");
        var vat = first(vatPaid, times(taxable, vatRate));
        put(totals, "units", units);
        put(totals, "kg", kg);
        put(totals, "m3", m3);
        put(totals, "density", density);
        put(totals, "purchaseUsd", usd);
        put(totals, "purchaseRon", purchase);
        put(totals, "freightRon", freight);
        put(totals, "dutyRon", duty);
        put(totals, "vatRon", vat);
        put(totals, "purchasePaidRon", purchasePaidRon);
        put(totals, "purchasePaidUsd", purchasePaidUsd);
        put(totals, "freightPaidRon", freightPaidRon);
        put(totals, "freightPaidUsd", freightPaidUsd);
        put(totals, "dutyPaidRon", dutyPaid);
        put(totals, "vatPaidRon", vatPaid);
        put(totals, "weightedFreightRate", freightRate);
        put(totals, "weightedPurchaseRate", rate);
        put(totals, "totalRon", plus(taxable, vat));
        put(totals, "beforeVatRon", taxable);
        put(totals, "purchaseBalanceUsd", minus(usd, first(purchasePaidUsd, ZERO)));
        var freightUsd = workbook ? first(billedFreightUsd, ZERO) : billedFreightUsd;
        var freightSettledUsd = first(freightPaidUsd, over(freightPaidRon, freightRate));
        var freightInvoiceRon = workbook ? first(billedFreightRon, ZERO) : billedFreightRon;
        put(totals, "purchaseBalanceRon", minus(purchase, first(purchasePaidRon, ZERO)));
        put(totals, "freightUsd", freightUsd);
        put(totals, "freightBilledRon", freightInvoiceRon);
        put(totals, "freightBalanceUsd", minus(freightUsd, first(freightSettledUsd, ZERO)));
        put(totals, "freightBalanceRon", minus(freightInvoiceRon, first(freightPaidRon, ZERO)));
        var dutyPaidUsd = payment(d.transfers(), "duty", true);
        var vatPaidUsd = payment(d.transfers(), "vat", true);
        var billedDutyUsd = first(dutyPaidUsd, over(duty, rate));
        var billedVatUsd = first(vatPaidUsd, times(plus(plus(usd, freightUsd), billedDutyUsd), vatRate));
        put(totals, "dutyUsd", billedDutyUsd);
        put(totals, "vatUsd", billedVatUsd);
        put(totals, "dutyPaidUsd", dutyPaidUsd);
        put(totals, "vatPaidUsd", vatPaidUsd);
        put(totals, "dutyBalanceUsd", minus(billedDutyUsd, first(dutyPaidUsd, ZERO)));
        put(totals, "vatBalanceUsd", minus(billedVatUsd, first(vatPaidUsd, ZERO)));
        put(totals, "dutyBalanceRon", minus(duty, first(dutyPaid, ZERO)));
        put(totals, "vatBalanceRon", minus(vat, first(vatPaid, ZERO)));

        var transit = decimal(fields, "transitDays");
        put(totals, "transitDays", transit);
        var ship = date(fields.get("shipDate"));
        var order = date(fields.get("orderDate"));
        var arrival = date(fields.get("arrivalDate"));
        var eta = transit == null || ship == null ? null : ship.plusDays(transit.longValueExact());
        if (ship != null && order != null) put(totals, "productionDays", BigDecimal.valueOf(ChronoUnit.DAYS.between(order, ship)));
        if (ship != null && arrival != null) put(totals, "actualTransitDays", BigDecimal.valueOf(ChronoUnit.DAYS.between(ship, arrival)));
        if (!d.lines().isEmpty()) {
            if (purchase == null || rate == null) warnings.add("Costul de achiziție necesită cantitatea, prețul și cursul valutar.");
            if (basis == null || lines.stream().anyMatch(row -> !row.containsKey("freightShare"))) warnings.add("Alocarea transportului necesită date de ambalare și metoda de transport.");
            if (missingFreight) warnings.add("Costul transportului lipsește.");
            if (duty == null) warnings.add("Taxele vamale necesită costurile și cota vamală sau plata efectivă.");
            if (vat == null) warnings.add("TVA necesită cota aplicabilă sau plata efectivă.");
        }
        return new Calculation(Map.copyOf(totals), lines.stream().map(Map::copyOf).toList(),
                eta == null ? null : eta.toString(), List.copyOf(warnings), basis);
    }
}
