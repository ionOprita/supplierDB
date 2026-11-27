package ro.sellfluence.db;

import ch.claudio.db.DB;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ro.sellfluence.db.StockImport.*;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static ro.sellfluence.db.StockImportCosts.calculate;
import static ro.sellfluence.db.StockImportCosts.date;

/** Repository for group-owned import aggregates. Every child write runs in its parent's transaction. */
public final class StockImportTable {
    private static final Gson GSON = new Gson();
    private static final Type FIELDS = new TypeToken<Map<String,String>>() {}.getType();
    private final DB db;

    public StockImportTable(DB db) { this.db = db; }

    public static class NotFound extends IllegalArgumentException {
        public NotFound() { super("Importul nu a fost găsit în grupul selectat."); }
    }
    public static class Conflict extends IllegalStateException {
        public Conflict(String message) { super(message); }
    }

    public List<Group> groups() throws SQLException { return db.readTX(StockImportTable::groups); }
    public List<CatalogProduct> products(String group) throws SQLException { return db.readTX(c -> products(c, group)); }
    public List<Summary> list(String group) throws SQLException { return db.readTX(c -> list(c, group)); }
    public Stored get(String group, UUID id) throws SQLException { return db.readTX(c -> get(c, group, id)); }
    public Stored preview(String group, Document doc) throws SQLException {
        return db.readTX(c -> preview(c, group, doc));
    }
    public Stored save(String group, Document doc) throws SQLException {
        try {
            return db.writeTX(c -> save(c, group, doc));
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()) || "40001".equals(e.getSQLState()))
                throw new Conflict("Referință duplicată sau modificare simultană. Reîncărcați registrul.");
            throw e;
        }
    }

    public static List<Group> groups(Connection c) throws SQLException {
        var result = new TreeMap<String,List<String>>();
        try (var s = c.prepareStatement("""
                SELECT DISTINCT btrim(vendor_group), NULLIF(btrim(company_name),'') FROM vendor
                WHERE btrim(vendor_group) <> '' ORDER BY 1,2
                """); var r = s.executeQuery()) {
            while (r.next()) {
                var companies = result.computeIfAbsent(r.getString(1), _ -> new ArrayList<>());
                if (r.getString(2) != null) companies.add(r.getString(2));
            }
        }
        return result.entrySet().stream().map(e -> new Group(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    private static Group requireGroup(Connection c, String group) throws SQLException {
        return groups(c).stream().filter(g -> g.key().equals(group)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Grupul selectat nu există."));
    }

    private static final List<String> MODES = List.of("AIR","RAIL","SEA","TRUCK");

    public static List<CatalogProduct> products(Connection c, String group) throws SQLException {
        requireGroup(c, group);
        var columns = new ArrayList<String>();
        for (var mode : MODES) {
            var prefix = "p." + mode.toLowerCase(Locale.ROOT) + "_transport_";
            columns.add(prefix + "pcs_per_carton");
            columns.add(prefix + "kg_per_carton");
            columns.add(prefix + "volume_m3_per_carton");
        }
        var sql = "SELECT p.product_code, p.name, p.model, p.emag_pnk, p.import_tax, " + String.join(",", columns)
                + " FROM product p JOIN vendor v ON v.id=p.vendor WHERE btrim(v.vendor_group)=? ORDER BY p.product_code";
        var result = new ArrayList<CatalogProduct>();
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, group);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var packing = new LinkedHashMap<String,Packing>();
                    int index = 6;
                    for (String mode : MODES) {
                        packing.put(mode, new Packing(r.getBigDecimal(index++),r.getBigDecimal(index++),r.getBigDecimal(index++)));
                    }
                    result.add(new CatalogProduct(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                            r.getBigDecimal(5), Map.copyOf(packing)));
                }
            }
        }
        return List.copyOf(result);
    }

    public static List<Summary> list(Connection c, String group) throws SQLException {
        requireGroup(c, group);
        var result = new ArrayList<Summary>();
        try (var s = c.prepareStatement("""
                SELECT id,revision,fields->>'title',fields->>'reference',fields->>'sellingCompany',
                       fields->>'orderDate',fields->>'status',fields->>'method',units,fields->>'arrivalDate',eta,total_ron
                FROM stock_import WHERE vendor_group=?
                ORDER BY NULLIF(fields->>'orderDate','') DESC NULLS LAST, fields->>'reference' DESC, id
                """)) {
            s.setString(1, group);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(new Summary(r.getObject(1,UUID.class), r.getLong(2), r.getString(3),
                        r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getBigDecimal(9),
                        r.getString(10),r.getString(11),r.getBigDecimal(12)));
            }
        }
        return List.copyOf(result);
    }

    @FunctionalInterface private interface Row<T> { T read(ResultSet r) throws SQLException; }
    private static <T> List<T> children(Connection c, UUID id, String sql, Row<T> row) throws SQLException {
        var result = new ArrayList<T>();
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1,id);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(row.read(r));
            }
        }
        return result;
    }

    public static Stored get(Connection c, String group, UUID id) throws SQLException {
        final long revision;
        final Map<String,String> fields;
        try (var s = c.prepareStatement("SELECT revision,fields FROM stock_import WHERE vendor_group=? AND id=?")) {
            s.setString(1,group);
            s.setObject(2,id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new NotFound();
                revision = r.getLong(1);
                fields = GSON.fromJson(r.getString(2), FIELDS);
            }
        }
        var lines = children(c,id,"""
                SELECT product_code,model,pnk,quantity,unit_usd,pieces_per_carton,kg_per_carton,m3_per_carton,duty_rate
                FROM stock_import_line WHERE import_id=? ORDER BY position
                """, r -> new Line(r.getString(1),r.getString(2),r.getString(3),r.getBigDecimal(4),
                r.getBigDecimal(5),r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8),r.getBigDecimal(9)));
        var transfers = children(c,id,"""
                SELECT kind,transfer_date,reference,ron,usd,rate FROM stock_import_transfer WHERE import_id=? ORDER BY position
                """, r -> new Transfer(r.getString(1),r.getString(2),r.getString(3),r.getBigDecimal(4),
                r.getBigDecimal(5),r.getBigDecimal(6)));
        var taric = children(c,id,"""
                SELECT code,net_kg,gross_kg,quantity FROM stock_import_taric WHERE import_id=? ORDER BY position
                """, r -> new Taric(r.getString(1),r.getBigDecimal(2),r.getBigDecimal(3),r.getBigDecimal(4)));
        var invoices = children(c,id,"""
                SELECT kind,reference FROM stock_import_invoice WHERE import_id=? ORDER BY position
                """, r -> new Invoice(r.getString(1),r.getString(2)));
        var doc = new Document(id,revision,fields,lines,transfers,taric,invoices);
        return new Stored(doc,calculate(doc));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void nonnegative(BigDecimal... values) {
        for (var v : values)
            if (v != null && (v.signum() < 0 || v.precision() > 50 || Math.abs((long)v.scale()) > 50))
                throw new IllegalArgumentException("Cantitățile și sumele trebuie să fie numere pozitive sau zero.");
    }

    private static void validate(Document doc, boolean saving) {
        if (doc == null) throw new IllegalArgumentException("Lipsesc datele importului.");
        if (saving && blank(doc.fields().get("reference")))
            throw new IllegalArgumentException("Completați referința importului.");
        if (doc.lines().size() > 10000 || doc.transfers().size() > 10000 || doc.taric().size() > 10000)
            throw new IllegalArgumentException("Prea multe poziții într-un singur import.");
        for (var dateKey : List.of("orderDate","shipDate","arrivalDate","dviDate")) date(doc.fields().get(dateKey));
        for (var key : List.of("purchaseRate","freightRate","freightUsd","freightRon","vatRate","densityThreshold","transitDays")) {
            var v = StockImportCosts.decimal(doc.fields(), key);
            nonnegative(v);
            if ("transitDays".equals(key) && v != null && (v.stripTrailingZeros().scale() > 0 || v.compareTo(BigDecimal.valueOf(36500)) > 0))
                throw new IllegalArgumentException("Durata transportului trebuie să fie un număr întreg de zile.");
        }
        for (var line : doc.lines()) {
            // Discount and correction lines have signed quantities and a positive unit price.
            nonnegative(line.quantity() == null ? null : line.quantity().abs(), line.unitUsd(), line.piecesPerCarton(),
                    line.kgPerCarton(), line.m3PerCarton(), line.dutyRate());
        }
        for (var t : doc.transfers()) {
            if (!Set.of("purchase","freight","duty","vat").contains(Objects.toString(t.kind(),"")))
                throw new IllegalArgumentException("Tip de transfer nevalid.");
            date(t.date());
            nonnegative(t.usd(),t.ron(),t.rate());
            if (t.rate() != null && t.rate().signum() == 0) throw new IllegalArgumentException("Cursul trebuie să fie pozitiv.");
        }
        for (var t : doc.taric()) nonnegative(t.netKg(),t.grossKg(),t.quantity());
        for (var i : doc.invoices()) {
            if (!Set.of("purchase","freight").contains(Objects.toString(i.kind(),"")) || blank(i.reference()))
                throw new IllegalArgumentException("Completați tipul și numărul facturii.");
        }
    }

    private static void validateMembership(Connection c, Group group, Document doc, Document existing) throws SQLException {
        var company = doc.fields().get("sellingCompany");
        if (!blank(company) && !group.companies().contains(company)
                && (existing == null || !company.equals(existing.fields().get("sellingCompany"))))
            throw new IllegalArgumentException("Firma selectată nu aparține grupului.");
        var accepted = new HashSet<String>();
        if (existing != null) existing.lines().forEach(line -> accepted.add(line.productCode()));
        var codes = doc.lines().stream().map(Line::productCode).filter(code -> !blank(code) && !accepted.contains(code))
                .distinct().toArray(String[]::new);
        if (codes.length == 0) return;
        try (var s = c.prepareStatement("""
                SELECT 1 FROM product p LEFT JOIN vendor v ON v.id=p.vendor
                WHERE btrim(v.vendor_group) IS DISTINCT FROM ? AND p.product_code=ANY(?) LIMIT 1
                """)) {
            s.setString(1, group.key());
            var array = c.createArrayOf("text", codes);
            try {
                s.setArray(2,array);
                try (var r = s.executeQuery()) {
                    if (r.next())
                        throw new IllegalArgumentException("Produsul nu aparține catalogului grupului.");
                }
            } finally { array.free(); }
        }
    }

    public static Stored preview(Connection c, String group, Document doc) throws SQLException {
        validate(doc,false);
        var allowed = requireGroup(c,group);
        var existing = doc.id() == null ? null : get(c,group,doc.id()).document();
        validateMembership(c,allowed,doc,existing);
        var snapshot = StockImportCosts.snapshot(doc);
        return new Stored(snapshot,calculate(snapshot));
    }

    public static Stored save(Connection c, String group, Document doc) throws SQLException {
        if (c.getAutoCommit()) throw new SQLException("Import writes require a transaction.");
        validate(doc,true);
        var allowed = requireGroup(c,group);
        var existing = doc.id() == null ? null : get(c,group,doc.id()).document();
        validateMembership(c,allowed,doc,existing);
        if ((existing == null ? 0 : existing.revision()) != doc.revision())
            throw new Conflict("Alt utilizator a modificat acest import. Reîncărcați datele.");
        return write(c,group,doc);
    }

    private static Stored write(Connection c, String group, Document doc) throws SQLException {
        doc = StockImportCosts.snapshot(doc);
        var result = calculate(doc);
        boolean create = doc.id() == null;
        var saved = doc.version(create ? UUID.randomUUID() : doc.id(),doc.revision()+1);
        String sql = create ? """
                INSERT INTO stock_import(fields,units,eta,total_ron,id,vendor_group,revision) VALUES (?::jsonb,?,?,?,?,?,1)
                """ : """
                UPDATE stock_import SET fields=?::jsonb,units=?,eta=?,total_ron=?,revision=revision+1
                WHERE id=? AND vendor_group=? AND revision=?
                """;
        try (var s = c.prepareStatement(sql)) {
            s.setString(1,GSON.toJson(doc.fields()));
            s.setBigDecimal(2,result.totals().get("units"));
            s.setObject(3,date(result.eta()));
            s.setBigDecimal(4,result.totals().get("totalRon"));
            s.setObject(5,saved.id());
            s.setString(6,group);
            if (!create) s.setLong(7,doc.revision());
            if (s.executeUpdate() != 1) throw new Conflict("Importul a fost modificat simultan.");
        }
        writeChildren(c,saved,create);
        return new Stored(saved,result);
    }

    @FunctionalInterface private interface Bind<T> { void bind(PreparedStatement s,T value) throws SQLException; }

    private static <T> void replace(Connection c, UUID id, boolean create, String table, String columns, String placeholders,
                                    List<T> values, Bind<T> bind) throws SQLException {
        if (!create) {
            try (var s = c.prepareStatement("DELETE FROM "+table+" WHERE import_id=?")) {
                s.setObject(1,id);
                s.executeUpdate();
            }
        }
        if (values.isEmpty()) return;
        try (var s = c.prepareStatement("INSERT INTO "+table+" (import_id,position,"+columns+") VALUES (?,?,"+placeholders+")")) {
            int position = 0;
            for (T value : values) {
                s.setObject(1,id);
                s.setInt(2,position++);
                bind.bind(s,value);
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void writeChildren(Connection c, Document doc, boolean create) throws SQLException {
        replace(c,doc.id(),create,"stock_import_line","product_code,model,pnk,quantity,unit_usd,pieces_per_carton,kg_per_carton,m3_per_carton,duty_rate",
                "?,?,?,?,?,?,?,?,?",doc.lines(),(s,l) -> {
                    s.setString(3,l.productCode()); s.setString(4,l.model()); s.setString(5,l.pnk());
                    s.setBigDecimal(6,l.quantity()); s.setBigDecimal(7,l.unitUsd()); s.setBigDecimal(8,l.piecesPerCarton());
                    s.setBigDecimal(9,l.kgPerCarton()); s.setBigDecimal(10,l.m3PerCarton()); s.setBigDecimal(11,l.dutyRate());
                });
        replace(c,doc.id(),create,"stock_import_transfer","kind,transfer_date,reference,ron,usd,rate","?,?,?,?,?,?",doc.transfers(),(s,t) -> {
            s.setString(3,t.kind()); s.setObject(4,date(t.date())); s.setString(5,t.reference());
            s.setBigDecimal(6,t.ron()); s.setBigDecimal(7,t.usd()); s.setBigDecimal(8,t.rate());
        });
        replace(c,doc.id(),create,"stock_import_taric","code,net_kg,gross_kg,quantity","?,?,?,?",doc.taric(),(s,t) -> {
            s.setString(3,t.code()); s.setBigDecimal(4,t.netKg()); s.setBigDecimal(5,t.grossKg()); s.setBigDecimal(6,t.quantity());
        });
        replace(c,doc.id(),create,"stock_import_invoice","kind,reference","?,?",doc.invoices(),(s,i) -> {
            s.setString(3,i.kind()); s.setString(4,i.reference());
        });
    }

    private record Existing(UUID id,long revision, String key, String fingerprint, Long sourceRevision, String reference, int column) {}
    private record Action(Source source, Existing target, boolean changed) {}

    /** Plan the complete set before writing anything. Caller commits one transaction for the whole workbook. */
    public static Reconcile reconcile(Connection c, String group, String workbookId, int tabId,
                                      List<Source> records, boolean apply) throws SQLException {
        if (apply && c.getAutoCommit()) throw new SQLException("Import reconciliation requires a transaction.");
        requireGroup(c,group);
        if (blank(workbookId) || tabId < 0) throw new IllegalArgumentException("Identificator sursă nevalid.");
        if (apply) {
            // Serialize source cutovers and employee writes while checking identities and replacing ordered children.
            try (var lock = c.createStatement()) { lock.execute("LOCK TABLE stock_import IN SHARE ROW EXCLUSIVE MODE"); }
        }
        var byKey = new HashMap<String,Existing>();
        var byReference = new HashMap<String,Existing>();
        try (var s = c.prepareStatement("""
                SELECT id,revision,source_book,source_tab,source_key,source_fingerprint,source_revision,btrim(fields->>'reference'),source_column
                FROM stock_import WHERE vendor_group=?
                """)) {
            s.setString(1,group);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var existing = new Existing(r.getObject(1,UUID.class),r.getLong(2),r.getString(5),r.getString(6),
                            r.getObject(7,Long.class),r.getString(8),r.getInt(9));
                    if (workbookId.equals(r.getString(3)) && tabId == r.getInt(4)) byKey.put(existing.key(),existing);
                    if (!blank(existing.reference())) byReference.put(existing.reference(),existing);
                }
            }
        }
        var keys = new HashSet<String>();
        var refs = new HashSet<String>();
        var conflicts = new ArrayList<String>();
        var actions = new ArrayList<Action>();
        var changes = new ArrayList<SourceChange>();
        int inserted = 0, updated = 0, unchanged = 0;
        for (var src : records) {
            if (src == null || src.document() == null || src.column() < 1 || blank(src.key()) || blank(src.fingerprint()))
                throw new IllegalArgumentException("Sursă incompletă.");
            try {
                validate(src.document(),true);
                calculate(src.document());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Import " + src.key() + ", coloana " + src.column() + ": "
                        + invalid.getMessage(), invalid);
            }
            var ref = src.document().fields().get("reference").strip();
            if (!keys.add(src.key()) || !refs.add(ref)) {
                conflicts.add("Referință duplicată în sursă, coloana "+src.column()+": "+ref);
                continue;
            }
            var target = byKey.get(src.key());
            var owner = byReference.get(ref);
            if (owner != null && !owner.equals(target)) {
                conflicts.add("Referință deja folosită în registru, coloana "+src.column()+": "+ref);
                continue;
            }
            boolean localEdit = target != null && !Objects.equals(target.sourceRevision(), target.revision());
            boolean changed = target == null || !src.fingerprint().equals(target.fingerprint()) || localEdit;
            actions.add(new Action(src,target,changed));
            if (changed) changes.add(new SourceChange(src.key(), src.column(), target == null, localEdit));
            if (target == null) inserted++;
            else if (changed) updated++;
            else unchanged++;
        }
        if (apply && conflicts.isEmpty()) {
            try (var s = c.prepareStatement("""
                    UPDATE stock_import SET source_book=?,source_tab=?,source_key=?,source_column=?,
                    source_fingerprint=?,source_revision=revision WHERE vendor_group=? AND id=?
                    """)) {
                for (var action : actions) {
                    var src = action.source();
                    var target = action.target();
                    if (!action.changed() && src.column() == target.column()) continue;
                    UUID id = target == null ? null : target.id();
                    if (action.changed()) {
                        var doc = src.document().version(id, target == null ? 0 : target.revision());
                        id = write(c,group,doc).document().id();
                    }
                    s.setString(1,workbookId); s.setInt(2,tabId); s.setString(3,src.key()); s.setInt(4,src.column());
                    s.setString(5,src.fingerprint()); s.setString(6,group); s.setObject(7,id); s.addBatch();
                }
                s.executeBatch();
            }
        }
        var missing = byKey.keySet().stream().filter(key -> !keys.contains(key)).sorted().toList();
        return new Reconcile(inserted, updated, unchanged, List.copyOf(conflicts), missing, List.copyOf(changes));
    }
}
