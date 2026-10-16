package ro.sellfluence.db.versions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import static java.math.RoundingMode.HALF_EVEN;
import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Restore historical finalized vouchers only when the paired snapshot and checkout support the repair. */
class EmagMirrorDBVersion45 {
    private static final Logger logger = Logger.getLogger(EmagMirrorDBVersion45.class.getName());
    private static final BigDecimal CENT = new BigDecimal("0.01");

    record Pair(String orderId, int finalized, int storno) {}
    record Key(int productId, int voucherId) {}
    record Split(Key key, BigDecimal value, BigDecimal vatValue, String vat, String offeredBy, String name) {
        BigDecimal total() { return value.add(vatValue); }
    }
    record Product(int id, String pnk, int quantity, Integer initial, Integer storno,
                   BigDecimal price, BigDecimal vat) {
        int originalQuantity() { return initial == null ? quantity : initial; }
        BigDecimal originalTotal() {
            return price.multiply(BigDecimal.ONE.add(vat)).setScale(2, HALF_EVEN)
                    .multiply(BigDecimal.valueOf(originalQuantity()));
        }
        boolean fullyReturned() {
            return quantity == 0 && initial != null && initial.equals(storno);
        }
    }
    record Header(BigDecimal value, BigDecimal vatValue, BigDecimal vat, int status) {
        BigDecimal total() { return value.add(vatValue); }
    }
    record Order(BigDecimal card, BigDecimal cod, BigDecimal shipping) {}
    record Repair(Pair pair, List<Split> splits, Set<Integer> headers) {}
    record RepairPlan(List<Repair> repairs, int paymentVerified, int zeroCheckout, int fullyReturned) {
        int splitCount() { return repairs.stream().mapToInt(r -> r.splits().size()).sum(); }
        int headerCount() { return repairs.stream().mapToInt(r -> r.headers().size()).sum(); }
    }

    static void version45(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE INDEX IF NOT EXISTS voucher_split_order_product_idx
                ON voucher_split (emag_order_surrogate_id, product_id)
                """);
        // Inspect every candidate before inserting anything. SetupDB owns the migration transaction.
        var plan = inspect(db);
        for (var repair : plan.repairs()) {
            for (var voucherId : repair.headers()) copyHeader(db, repair.pair(), voucherId);
            for (var split : repair.splits()) copySplit(db, repair.pair(), split.key());
        }
        logger.info("Restored " + plan.splitCount() + " voucher splits and " + plan.headerCount()
                + " voucher headers on " + plan.repairs().size() + " finalized orders; payment verified: "
                + plan.paymentVerified() + ", zero checkout: " + plan.zeroCheckout()
                + ", fully returned without recorded payment: " + plan.fullyReturned());
    }

    /** Read-only preflight; also usable to check historical data without applying the migration. */
    static RepairPlan inspect(Connection db) throws SQLException {
        var repairs = new ArrayList<Repair>();
        int paid = 0, zero = 0, returned = 0;
        for (var pair : candidates(db)) {
            var finalizedProducts = products(db, pair.finalized());
            var stornoProducts = products(db, pair.storno());
            var finalizedSplits = splits(db, pair.finalized());
            var stornoSplits = splits(db, pair.storno());
            var missing = stornoSplits.values().stream()
                    .filter(s -> finalizedProducts.containsKey(s.key().productId())
                            && !finalizedSplits.containsKey(s.key())).toList();
            var voucherIds = new HashSet<Integer>();
            missing.forEach(s -> voucherIds.add(s.key().voucherId()));
            var missingHeaders = new HashSet<Integer>();
            var sourceShipping = shippingSplits(db, pair.storno());
            var targetShipping = shippingSplits(db, pair.finalized());

            for (var split : stornoSplits.values()) {
                if (!voucherIds.contains(split.key().voucherId())) continue;
                var source = stornoProducts.get(split.key().productId());
                var target = finalizedProducts.get(split.key().productId());
                require(source != null && target != null, pair, "voucher product is absent from a snapshot");
                require(Objects.equals(source.pnk(), target.pnk())
                                && Objects.equals(source.initial(), target.initial())
                                && same(source.price(), target.price()) && same(source.vat(), target.vat()),
                        pair, "product identity, initial quantity, price or VAT differs between snapshots");
                var existing = finalizedSplits.get(split.key());
                require(existing == null || sameSplit(existing, split), pair, "existing voucher split conflicts with source");
            }
            for (var voucherId : voucherIds) {
                var source = header(db, pair.storno(), voucherId);
                var target = header(db, pair.finalized(), voucherId);
                require(source != null, pair, "source voucher header is absent: " + voucherId);
                require(same(source.total(), sum(stornoSplits.values().stream()
                                .filter(s -> s.key().voucherId() == voucherId).map(Split::total).toList())
                                .add(sourceShipping.getOrDefault(voucherId, BigDecimal.ZERO))),
                        pair, "source voucher header does not reconcile: " + voucherId);
                require(target == null || same(source.value(), target.value())
                                && same(source.vatValue(), target.vatValue())
                                && same(source.vat(), target.vat()) && source.status() == target.status(),
                        pair, "existing voucher header conflicts with source: " + voucherId);
                var proposed = sum(finalizedSplits.values().stream()
                        .filter(s -> s.key().voucherId() == voucherId).map(Split::total).toList())
                        .add(sum(missing.stream().filter(s -> s.key().voucherId() == voucherId)
                                .map(Split::total).toList()))
                        .add(targetShipping.getOrDefault(voucherId, BigDecimal.ZERO));
                require(same(proposed, source.total()), pair, "repaired voucher allocation does not reconcile: " + voucherId);
                if (target == null) missingHeaders.add(voucherId);
            }

            var finalizedOrder = order(db, pair.finalized());
            var stornoOrder = order(db, pair.storno());
            var total = sum(finalizedProducts.values().stream().map(Product::originalTotal).toList())
                    .add(sum(finalizedSplits.values().stream().map(Split::total).toList()))
                    .add(sum(missing.stream().map(Split::total).toList()))
                    .add(finalizedOrder.shipping()).add(sum(targetShipping.values()));
            long units = finalizedProducts.values().stream().mapToLong(Product::originalQuantity).sum();
            var tolerance = CENT.multiply(BigDecimal.valueOf(Math.max(1, units)));
            require(total.compareTo(tolerance.negate()) >= 0, pair, "vouchers exceed the original checkout total");
            var payments = new HashSet<BigDecimal>();
            for (var payment : List.of(finalizedOrder.card(), finalizedOrder.cod(), stornoOrder.card(), stornoOrder.cod())) {
                if (payment.signum() > 0) payments.add(payment.stripTrailingZeros());
            }
            require(payments.size() <= 1, pair, "recorded payment amounts disagree between snapshots");
            if (!payments.isEmpty()) {
                require(total.subtract(payments.iterator().next()).abs().compareTo(tolerance) <= 0,
                        pair, "repaired checkout does not match recorded payment (total " + total + ")");
                paid++;
            } else if (total.abs().compareTo(tolerance) <= 0) {
                zero++;
            } else {
                require(!stornoProducts.isEmpty() && stornoProducts.values().stream().allMatch(Product::fullyReturned),
                        pair, "no recorded payment and the order is not fully returned");
                returned++;
            }
            repairs.add(new Repair(pair, missing, Set.copyOf(missingHeaders)));
        }
        return new RepairPlan(List.copyOf(repairs), paid, zero, returned);
    }

    private static List<Pair> candidates(Connection db) throws SQLException {
        var pairs = new ArrayList<Pair>();
        try (var s = db.prepareStatement("""
                SELECT DISTINCT f.id, f.surrogate_id AS finalized, s.surrogate_id AS storno
                FROM emag_order s
                JOIN emag_order f ON f.id = s.id AND f.vendor_id = s.vendor_id AND f.status = 4
                JOIN product_in_order p5 ON p5.emag_order_surrogate_id = s.surrogate_id
                JOIN product_in_order p4 ON p4.emag_order_surrogate_id = f.surrogate_id AND p4.id = p5.id
                JOIN voucher_split v5 ON v5.emag_order_surrogate_id = s.surrogate_id AND v5.product_id = p5.id
                WHERE s.status = 5 AND NOT EXISTS (
                    SELECT 1 FROM voucher_split v4
                    WHERE v4.emag_order_surrogate_id = f.surrogate_id AND v4.product_id = p4.id
                      AND v4.voucher_id = v5.voucher_id
                )
                ORDER BY finalized
                """); var rs = s.executeQuery()) {
            while (rs.next()) pairs.add(new Pair(rs.getString("id"), rs.getInt("finalized"), rs.getInt("storno")));
        }
        return pairs;
    }

    private static Map<Integer, Product> products(Connection db, int snapshot) throws SQLException {
        var products = new LinkedHashMap<Integer, Product>();
        try (var s = db.prepareStatement("""
                SELECT id, part_number_key, quantity, initial_qty, storno_qty, sale_price, vat
                FROM product_in_order WHERE emag_order_surrogate_id = ? ORDER BY id
                """)) {
            s.setInt(1, snapshot);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var quantity = rs.getObject("quantity", Integer.class);
                    var initial = rs.getObject("initial_qty", Integer.class);
                    var price = rs.getBigDecimal("sale_price");
                    var vat = rs.getString("vat");
                    if (quantity == null || quantity < 0 || initial != null && initial < quantity
                            || price == null || vat == null || vat.isBlank()) {
                        throw new SQLException("Cannot validate product " + rs.getInt("id") + " in snapshot " + snapshot);
                    }
                    BigDecimal rate;
                    try { rate = new BigDecimal(vat); }
                    catch (NumberFormatException e) { throw new SQLException("Invalid VAT in snapshot " + snapshot, e); }
                    products.put(rs.getInt("id"), new Product(rs.getInt("id"), rs.getString("part_number_key"),
                            quantity, initial, rs.getObject("storno_qty", Integer.class), price, rate));
                }
            }
        }
        return products;
    }

    private static Map<Key, Split> splits(Connection db, int snapshot) throws SQLException {
        var splits = new LinkedHashMap<Key, Split>();
        try (var s = db.prepareStatement("""
                SELECT product_id, voucher_id, value, vat_value, vat, offered_by, voucher_name
                FROM voucher_split WHERE emag_order_surrogate_id = ? ORDER BY product_id, voucher_id
                """)) {
            s.setInt(1, snapshot);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var key = new Key(rs.getInt("product_id"), rs.getInt("voucher_id"));
                    splits.put(key, new Split(key, zero(rs.getBigDecimal("value")), zero(rs.getBigDecimal("vat_value")),
                            rs.getString("vat"), rs.getString("offered_by"), rs.getString("voucher_name")));
                }
            }
        }
        return splits;
    }

    private static Map<Integer, BigDecimal> shippingSplits(Connection db, int snapshot) throws SQLException {
        var splits = new LinkedHashMap<Integer, BigDecimal>();
        try (var s = db.prepareStatement("""
                SELECT voucher_id, value, vat_value FROM order_voucher_split WHERE emag_order_surrogate_id = ?
                """)) {
            s.setInt(1, snapshot);
            try (var rs = s.executeQuery()) {
                while (rs.next()) splits.merge(rs.getInt("voucher_id"),
                        zero(rs.getBigDecimal("value")).add(zero(rs.getBigDecimal("vat_value"))), BigDecimal::add);
            }
        }
        return splits;
    }

    private static Header header(Connection db, int snapshot, int voucherId) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT sale_price, sale_price_vat, vat, status FROM voucher
                WHERE emag_order_surrogate_id = ? AND voucher_id = ?
                """)) {
            s.setInt(1, snapshot);
            s.setInt(2, voucherId);
            try (var rs = s.executeQuery()) {
                return rs.next() ? new Header(zero(rs.getBigDecimal("sale_price")),
                        zero(rs.getBigDecimal("sale_price_vat")), rs.getBigDecimal("vat"), rs.getInt("status")) : null;
            }
        }
    }

    private static Order order(Connection db, int snapshot) throws SQLException {
        try (var s = db.prepareStatement("SELECT cashed_co, cashed_cod, shipping_tax FROM emag_order WHERE surrogate_id = ?")) {
            s.setInt(1, snapshot);
            try (var rs = s.executeQuery()) {
                if (!rs.next()) throw new SQLException("Missing order snapshot " + snapshot);
                return new Order(zero(rs.getBigDecimal("cashed_co")), zero(rs.getBigDecimal("cashed_cod")),
                        zero(rs.getBigDecimal("shipping_tax")));
            }
        }
    }

    private static void copyHeader(Connection db, Pair pair, int voucherId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO voucher (voucher_id, emag_order_surrogate_id, modified, created, status,
                    sale_price_vat, sale_price, voucher_name, vat, issue_date, id)
                SELECT voucher_id, ?, modified, created, status,
                    sale_price_vat, sale_price, voucher_name, vat, issue_date, id
                FROM voucher WHERE emag_order_surrogate_id = ? AND voucher_id = ?
                """)) {
            s.setInt(1, pair.finalized()); s.setInt(2, pair.storno()); s.setInt(3, voucherId);
            require(s.executeUpdate() == 1, pair, "voucher header copy count changed");
        }
    }

    private static void copySplit(Connection db, Pair pair, Key key) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO voucher_split (voucher_id, emag_order_surrogate_id, product_id,
                    value, vat_value, vat, offered_by, voucher_name)
                SELECT voucher_id, ?, product_id, value, vat_value, vat, offered_by, voucher_name
                FROM voucher_split WHERE emag_order_surrogate_id = ? AND product_id = ? AND voucher_id = ?
                """)) {
            s.setInt(1, pair.finalized()); s.setInt(2, pair.storno());
            s.setInt(3, key.productId()); s.setInt(4, key.voucherId());
            require(s.executeUpdate() == 1, pair, "voucher split copy count changed");
        }
    }

    private static boolean sameSplit(Split a, Split b) {
        return same(a.value(), b.value()) && same(a.vatValue(), b.vatValue()) && sameVat(a.vat(), b.vat())
                && Objects.equals(a.offeredBy(), b.offeredBy()) && Objects.equals(a.name(), b.name());
    }

    private static boolean sameVat(String a, String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        try { return same(new BigDecimal(a), new BigDecimal(b)); }
        catch (NumberFormatException _) { return false; }
    }

    private static BigDecimal sum(Iterable<BigDecimal> amounts) {
        var total = BigDecimal.ZERO;
        for (var amount : amounts) total = total.add(amount);
        return total;
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
    private static void require(boolean condition, Pair pair, String message) throws SQLException {
        if (!condition) throw new SQLException("Voucher repair rejected for order " + pair.orderId()
                + " (snapshots " + pair.finalized() + "/" + pair.storno() + "): " + message);
    }
}
