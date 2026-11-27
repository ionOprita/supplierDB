package ro.sellfluence.apphelper;

import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.db.StockImport.Invoice;
import ro.sellfluence.db.StockImport.Line;
import ro.sellfluence.db.StockImport.Source;
import ro.sellfluence.db.StockImport.Taric;
import ro.sellfluence.db.StockImport.Transfer;
import ro.sellfluence.googleapi.StockImportSheetReader.Snapshot;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Map.entry;
import static ro.sellfluence.googleapi.StockImportSheetReader.columnName;

/** Source coordinates are one-based. Date and numeric inputs come from effective, unrounded values. */
public final class StockImportSheetParser {
    public record Parsed(List<Source> records, List<String> warnings) {}
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Map<Integer,String> HEADER = Map.ofEntries(entry(6,"title"), entry(7,"type"),
            entry(8,"orderDate"), entry(9,"shipDate"), entry(13,"arrivalDate"), entry(14,"sellingCompany"),
            entry(15,"address"), entry(16,"method"), entry(17,"carrier"), entry(19,"receiptRequest"),
            entry(20,"status"), entry(21,"defects"), entry(22,"manual"), entry(23,"packaging"),
            entry(24,"lot"), entry(25,"twoStop"), entry(26,"pallet"), entry(27,"ean"), entry(28,"freightQuote"),
            entry(29,"receptionDocs"), entry(30,"eTransport"), entry(31,"deposit"), entry(32,"customsDeclarations"),
            entry(33,"invoicedBy"), entry(54,"dviNumber"), entry(55,"dviDate"),
            entry(64,"tracking"), entry(65,"nir"), entry(88,"notes"));
    private static final Map<Integer,String> ROW_ANCHORS = Map.ofEntries(entry(2,"Nr/ Import"), entry(8,"Data plasare comanda"),
            entry(14,"Firma pe care vindem"), entry(24,"Batch number"), entry(34,"Numar factura achizitie"),
            entry(40,"Transfer achizitie 1"), entry(47,"Transfer transport 1"), entry(52,"Transfer taxe vamale"),
            entry(53,"Transfer TVA"), entry(63,"Cod TARIC 6"), entry(65,"Numar NIR"), entry(88,"Observatii"));

    private StockImportSheetParser() {}

    public static Parsed parse(Snapshot source) {
        if (source.imports().isEmpty() || source.imports().getFirst().firstColumn()!=1)
            throw new IllegalArgumentException("Missing Importuri row labels.");
        var labels = new Grid("Importuri", source.imports().getFirst().rows(), 1);
        ROW_ANCHORS.forEach((row,name) -> labels.expect(row,2,name));
        var lookups = new Lookups(source);
        List<Source> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        var keys = new HashSet<String>();
        for (var chunk : source.imports().subList(1, source.imports().size())) {
            var grid = new Grid("Importuri",chunk.rows(),chunk.firstColumn());
            int width = chunk.rows().stream().mapToInt(List::size).max().orElse(0);
            // The workbook has eleven columns per import. Data under a displaced header must
            // be reported rather than assigned to the neighbouring shipment.
            for (int col=1; col<=width; col++) {
                if ((col-1)%11!=0) {
                    if (grid.text(2,col)!=null || "Nume produs".equals(grid.text(68,col)))
                        throw grid.error(2,col,"unexpected import block position");
                    continue;
                }
                String key = grid.text(2,col);
                boolean hasInputs = hasEntry(grid,col);
                if (key==null && !hasInputs) continue;
                if (key==null) throw grid.error(2,col,"shipment data without an import number");
                grid.expect(39,col,"Data transferului");
                grid.expect(57,col,"Cod TARIC");
                grid.expect(68,col,"Nume produs");
                grid.expect(68,col+1,"Cant.");
                grid.expect(68,col+8,"PNK");
                grid.expect(68,col+9,"Cod Produs");
                if (!hasInputs) {
                    warnings.add("Skipped numbered empty template " + key + " at " + grid.at(2,col));
                    continue;
                }
                if (!keys.add(key)) throw grid.error(2,col,"duplicate import number " + key);
                var document = document(grid,col,key,lookups,warnings);
                records.add(new Source(key,chunk.firstColumn()+col-1,fingerprint(document),document));
            }
        }
        if (records.isEmpty()) throw new IllegalArgumentException("The source has no populated import blocks.");
        return new Parsed(List.copyOf(records),List.copyOf(warnings));
    }

    // Blank templates have headings, computed zero totals and false checkboxes. The following
    // cells are genuine inputs; a product or a payment alone can make a draft meaningful.
    private static boolean hasEntry(Grid grid, int col) {
        for (int row : HEADER.keySet()) {
            Object value = grid.cell(row,col);
            if (present(value) && !Boolean.FALSE.equals(value)) return true;
        }
        for (int row=34; row<=38; row++) if (present(grid.cell(row,col))) return true;
        for (int row=40; row<=53; row++) {
            if (row>=44 && row<=46 || row==51) continue;
            for (int off=0; off<5; off++) if (present(grid.cell(row,col+off))) return true;
        }
        for (int row=58; row<=63; row++)
            for (int off=0; off<4; off++) if (present(grid.cell(row,col+off))) return true;
        for (int row=69; row<=84; row++)
            for (int off=0; off<3; off++) if (present(grid.cell(row,col+off))) return true;
        return false;
    }

    private static Document document(Grid g, int col, String key, Lookups lookups, List<String> warnings) {
        var fields = new TreeMap<String,String>();
        fields.put("reference",key);
        fields.put("costConvention","sheet");
        HEADER.forEach((row,name) -> {
            String value = switch (row) {
                case 8,9,13,55 -> g.date(row,col);
                default -> g.text(row,col);
            };
            if (value!=null) fields.put(name,value);
        });
        String mode = fields.get("method");
        Integer measureCol = mode == null ? null : lookups.modeColumns.get(mode);
        if (mode != null && measureCol == null) throw g.error(16,col,"unknown transport method " + mode);
        if (measureCol != null) {
            putDecimal(fields,"densityThreshold",lookups.cartons.number(3,measureCol));
            putDecimal(fields,"transitDays",lookups.transitDays.get(mode));
        }
        putDecimal(fields,"vatRate",lookups.vatRate);
        var invoices = new ArrayList<Invoice>();
        for (int row=34; row<=38; row++) {
            var ref = g.text(row,col);
            if (ref!=null) invoices.add(new Invoice(row==34?"purchase":"freight",ref));
        }
        var transfers = new ArrayList<Transfer>();
        for (int row=40; row<=53; row++) {
            if (row>=44 && row<=46 || row==51) continue;
            String kind = row<=43?"purchase":row<=50?"freight":row==52?"duty":"vat";
            var date = g.date(row,col);
            var ref = g.text(row,col+1);
            var ron = g.number(row,col+2);
            var usd = g.number(row,col+3);
            var rate = g.number(row,col+4);
            if (date!=null || ref!=null || ron!=null || usd!=null || rate!=null)
                transfers.add(new Transfer(kind,date,ref,ron,usd,rate));
        }
        // Normally these are SUMs of the editable transfers and their weighted FX rate.
        // A positive total/rate with no underlying amounts is a source-provided fallback.
        // Do not turn the calculated zeros in an unpaid shipment into recorded payments.
        preserveUnbackedTotals(g, col, transfers, fields);
        var taric = new ArrayList<Taric>();
        for (int row=58; row<=63; row++) {
            var code = g.text(row,col);
            var net = g.number(row,col+1);
            var gross = g.number(row,col+2);
            var qty = g.number(row,col+3);
            if (code!=null || net!=null || gross!=null || qty!=null) taric.add(new Taric(code,net,gross,qty));
        }
        var lines = new ArrayList<Line>();
        for (int row=69; row<=84; row++) {
            var model = g.text(row,col);
            var qty = g.number(row,col+1);
            var unit = g.number(row,col+2);
            // Formulas in unused derived columns occasionally return #REF!. An empty product
            // slot is still empty. For an occupied line, an error in an identity cell is reported.
            if (model==null && qty==null && unit==null) continue;
            var pnk = g.text(row,col+8);
            var code = g.text(row,col+9);
            Integer packingRow = pnk==null?null:lookups.packingRows.get(pnk);
            Integer prodRow = pnk==null?null:lookups.productRows.get(pnk);
            BigDecimal pieces=null, kg=null, m3=null;
            if (packingRow!=null && measureCol!=null) {
                pieces = lookups.cartons.number(packingRow,measureCol);
                kg = lookups.cartons.number(packingRow,measureCol+1);
                m3 = lookups.cartons.number(packingRow,measureCol+5);
            }
            var duty = prodRow==null?null:lookups.products.number(prodRow,10);
            if (pnk==null || code==null) warnings.add(g.at(row,col) + ": unresolved product identity in import " + key);
            lines.add(new Line(code,model,pnk,qty,unit,pieces,kg,m3,duty));
        }
        return new Document(null,0,fields,lines,transfers,taric,invoices);
    }

    private static void preserveUnbackedTotals(Grid g, int col, List<Transfer> transfers, Map<String,String> fields) {
        boolean purchaseRon = false;
        boolean purchaseUsd = false;
        boolean freightRon = false;
        boolean freightUsd = false;
        for (var transfer : transfers) {
            boolean ron = positive(transfer.ron());
            boolean usd = positive(transfer.usd());
            if ("purchase".equals(transfer.kind())) {
                purchaseRon |= ron;
                purchaseUsd |= usd;
            } else if ("freight".equals(transfer.kind())) {
                freightRon |= ron;
                freightUsd |= usd;
            }
        }
        if (!purchaseRon || !purchaseUsd) putPositive(fields, "purchaseRate", g.number(44,col+4));
        if (!freightRon) putPositive(fields, "freightRon", g.number(51,col+2));
        if (!freightUsd) putPositive(fields, "freightUsd", g.number(51,col+3));
        if (!freightRon || !freightUsd) putPositive(fields, "freightRate", g.number(51,col+4));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void putPositive(Map<String,String> fields, String key, BigDecimal value) {
        if (positive(value)) putDecimal(fields, key, value);
    }

    private static void putDecimal(Map<String,String> fields, String key, BigDecimal value) {
        if (value!=null) fields.put(key,value.toPlainString());
    }

    private static String fingerprint(Document document) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(document)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean present(Object value) {
        return value!=null && !(value instanceof String s && s.isBlank());
    }

    private static final class Lookups {
        final Grid products;
        final Grid cartons;
        final Map<String,Integer> productRows;
        final Map<String,Integer> packingRows;
        final Map<String,Integer> modeColumns = new HashMap<>();
        final Map<String,BigDecimal> transitDays = new HashMap<>();
        final BigDecimal vatRate;
        Lookups(Snapshot s) {
            products = new Grid("Date Prod.",s.products(),1);
            products.expect(3,5,"PNK eMAG");
            products.expect(3,10,"Taxa import");
            productRows = products.indexRows(4,5);
            cartons = new Grid("Tabel Meas. CTN.",s.cartons(),1);
            cartons.expect(4,4,"PNK");
            packingRows = cartons.indexRows(5,4);
            var transit = new Grid("Setari",s.transit(),2,23);
            transit.expect(1,1,"Metoda transport");
            for (int i=0;i<4;i++) {
                int c=7+6*i;
                String mode=cartons.text(2,c);
                if (mode==null || modeColumns.put(mode,c)!=null)
                    throw cartons.error(2,c,"missing or duplicate transport method");
                cartons.expect(4,c,"Transport (pcs./ctn.)");
                cartons.expect(4,c+5,"Transport (m³/ctn.)");
                String transitMode = transit.text(2+i,1);
                if (transitMode==null || transitDays.containsKey(transitMode))
                    throw transit.error(2+i,1,"missing or duplicate transport method");
                transitDays.put(transitMode,transit.number(2+i,2));
            }
            if (!modeColumns.keySet().equals(transitDays.keySet()))
                throw new IllegalArgumentException("Transport methods in Setari and Tabel Meas. CTN. disagree.");
            var vat = new Grid("Cons. Date Secundare",s.vat(),5,2);
            vat.expect(1,1,"TVA");
            vatRate = vat.number(2,1);
        }
    }

    private record Grid(String tab, List<List<Object>> rows, int firstColumn, int firstRow) {
        Grid(String tab, List<List<Object>> rows, int firstColumn) { this(tab,rows,firstColumn,1); }
        Object cell(int row, int col) {
            if (row>rows.size() || col>rows.get(row-1).size()) return null;
            return rows.get(row-1).get(col-1);
        }
        String text(int row,int col) {
            Object value=cell(row,col);
            if (!present(value)) return null;
            if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            String s=value.toString().strip();
            if (s.matches("^#(?:REF!|N/A|VALUE!|DIV/0!|NUM!|ERROR!|NAME\\?).*"))
                throw error(row,col,"source error " + s);
            return s;
        }
        BigDecimal number(int row,int col) {
            var v=cell(row,col);
            if (!present(v)) return null;
            if (!(v instanceof Number)) throw error(row,col,"expected a numeric cell");
            return new BigDecimal(v.toString()).stripTrailingZeros();
        }
        String date(int row,int col) {
            BigDecimal serial = number(row,col);
            if (serial==null) return null;
            try {
                return LocalDate.of(1899,12,30).plusDays(serial.longValueExact()).toString();
            } catch (ArithmeticException | java.time.DateTimeException e) {
                throw error(row,col,"invalid Sheets date serial");
            }
        }
        String at(int row,int col) { return tab + "!" + columnName(firstColumn+col-1) + (firstRow+row-1); }
        IllegalArgumentException error(int row,int col,String detail) {
            return new IllegalArgumentException(at(row,col) + ": " + detail);
        }
        void expect(int row,int col,String value) {
            if (!value.equals(text(row,col))) throw error(row,col,"expected " + value + "; check source layout");
        }
        Map<String,Integer> indexRows(int first, int col) {
            var result = new HashMap<String,Integer>();
            for (int row=first; row<=rows.size(); row++) {
                var key=text(row,col);
                if (key!=null && result.put(key,row)!=null) throw error(row,col,"duplicate product key " + key);
            }
            return result;
        }
    }
}
