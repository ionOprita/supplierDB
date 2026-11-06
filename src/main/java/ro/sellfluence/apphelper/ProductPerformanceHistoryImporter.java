package ro.sellfluence.apphelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ro.sellfluence.db.ProductPerformanceHistoryTable.Observation;
import ro.sellfluence.googleapi.SheetsAPI;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-only source acquisition and deterministic parsing for the approved historical reports. */
public final class ProductPerformanceHistoryImporter {
    public static final String PARSER_VERSION = "product-performance-history-v2";
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final LocalDate SHEETS_EPOCH = LocalDate.of(1899, 12, 30);
    private static final Pattern IMPORT_ID = Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]+)");
    private static final String ORGANIC = "Date Organic/W.";
    private static final String ADS = "eMAG ADS/W.";
    private static final String REVIEWS = "Nr. Rew. Fam./W.";
    private static final String RATING = "Rating Fam./W.";
    private static final Map<String, String> ORGANIC_METRICS = Map.of(
            "Stock", "stock", "GMV 30", "gmv", "Clicks", "clicks", "Sales Price", "salesPrice",
            "Conv.", "sourceConversion", "Perform.", "performance");
    private static final Map<String, String> ADS_METRICS = Map.of(
            "Imp.", "impressions", "Clicks", "clicks", "Spend", "spend", "Sales", "sales",
            "Total Units Sold", "units", "Total Orders", "salesCount");
    private static final List<String> GROUPS = List.of("auto", "broad", "exact", "product");
    private static final Map<String, String> ACCOUNTS = Map.of(
            "judios concept srl", "judios", "koppel", "koppelfbe", "koppel srl", "koppelfbe",
            "sellfusion srl", "sellfusion", "sellfusion", "sellfusion",
            "zoopie concept srl", "zoopieconcept", "zoopie solutions srl", "zoopiesolutions");

    public record Source(String name, String spreadsheetId) { }
    public static final List<Source> APPROVED_SOURCES = List.of(
            new Source("Sellfusion", "142uaMMd2wf8XPLdLX7_wcWl_Jp0XkKDNREhc-RnmRlQ"),
            new Source("Zoopie", "1-mDyK4JaYIzlgMSShnDMFXLs3N0tBew8WOYB59nn_iQ"),
            new Source("Koppel", "1Jd2Oh8KXX_k2mAv7GnR8tghJzZ8-E0RnPcLXoGQ5_VE"),
            new Source("Judios", "1RwL9e-fmZgixk797z6yFGUHgnlVguuHtdq8bGSdSH0E"));

    public record ProductIdentity(UUID vendorId, String account, String productCode, String pnk) { }
    public record Grid(int sheetId, String title, int rows, int columns) { }
    public record Diagnostic(String severity, String code, String spreadsheetId, String sheet,
                             String range, String message) { }
    public record Capture(String spreadsheetId, String sheet, String range, String render,
                          List<List<Object>> values) { }
    public record Result(List<Observation> observations, List<Diagnostic> diagnostics,
                         String sourcePayloadJson, String contentHash, boolean complete) { }

    /** Implementations must return typed UNFORMATTED_VALUE values, including genuine zeroes. */
    public interface Reader {
        List<Grid> metadata(String spreadsheetId);
        List<List<Object>> read(String spreadsheetId, String sheet, int firstRow, int lastRow,
                                int firstColumn, int lastColumn, boolean formulas);
    }

    public static Reader googleReader() {
        return new Reader() {
            private final Map<String, SheetsAPI> opened = new HashMap<>();
            private SheetsAPI sheet(String id) {
                return opened.computeIfAbsent(id, key -> Objects.requireNonNull(
                        SheetsAPI.getSpreadSheetById(Defaults.defaultGoogleApp, key), "Spreadsheet unavailable: " + key));
            }
            @Override public List<Grid> metadata(String id) {
                return sheet(id).getSheetGrids().stream()
                        .map(g -> new Grid(g.sheetId(), g.title(), g.rows(), g.columns())).toList();
            }
            @Override public List<List<Object>> read(String id, String tab, int r1, int r2, int c1, int c2,
                                                       boolean formulas) {
                return sheet(id).getBoundedValues(tab, r1, r2, c1, c2, formulas);
            }
        };
    }

    private final Reader reader;
    private final List<ProductIdentity> products;
    private final LocalDate through;
    private final List<Capture> captures = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<Observation> observations = new ArrayList<>();
    private final Map<String, List<Grid>> metadata = new TreeMap<>();

    public ProductPerformanceHistoryImporter(Reader reader, List<ProductIdentity> products, LocalDate through) {
        this.reader = Objects.requireNonNull(reader);
        this.products = List.copyOf(products);
        this.through = Objects.requireNonNull(through);
    }

    public Result read(List<Source> sources) {
        for (Source source : sources) {
            try {
                grids(source.spreadsheetId());
                var catalogue = catalogue(source);
                for (String tab : List.of(ORGANIC, ADS, REVIEWS, RATING)) {
                    try {
                        readMatrix(source, tab, catalogue);
                    } catch (RuntimeException ex) {
                        diagnostic("error", "source_read_failed", source.spreadsheetId(), tab, "",
                                safeMessage(ex));
                    }
                }
            } catch (RuntimeException ex) {
                diagnostic("error", "source_read_failed", source.spreadsheetId(), "", "", safeMessage(ex));
            }
        }
        rejectDuplicateObservations();
        observations.sort(Comparator.comparing((Observation o) -> o.vendorId().toString())
                .thenComparing(Observation::productCode).thenComparing(Observation::observationDate)
                .thenComparing(Observation::domain).thenComparing(Observation::sourceSpreadsheetId));
        var payload = new TreeMap<String, Object>();
        payload.put("parserVersion", PARSER_VERSION);
        payload.put("throughDate", through.toString());
        payload.put("sources", sources);
        payload.put("metadata", metadata);
        payload.put("captures", captures);
        payload.put("diagnostics", diagnostics);
        payload.put("observations", observations.stream().map(ProductPerformanceHistoryImporter::observationJson).toList());
        String raw = JSON.toJson(payload);
        return new Result(List.copyOf(observations), List.copyOf(diagnostics), raw, sha256(raw),
                diagnostics.stream().noneMatch(d -> d.severity().equals("error")));
    }

    private List<Grid> grids(String id) { return metadata.computeIfAbsent(id, reader::metadata); }
    private Grid grid(String id, String tab) {
        return grids(id).stream().filter(g -> g.title().equals(tab)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Required source tab missing: " + tab));
    }
    private List<List<Object>> read(String id, String tab, int r1, int r2, int c1, int c2, boolean formulas) {
        Grid grid = grid(id, tab);
        if (r2 > grid.rows() || c2 > grid.columns() || (long) (r2-r1+1) * (c2-c1+1) > 50_000)
            throw new IllegalArgumentException("Read outside bounded source grid: " + tab);
        var values = reader.read(id, tab, r1, r2, c1, c2, formulas);
        captures.add(new Capture(id, tab, range(r1, r2, c1, c2), formulas ? "FORMULA" : "UNFORMATTED_VALUE", values));
        return values;
    }

    private record CatalogRow(String name, String company, String pnk, String code, ProductIdentity resolved) { }
    private List<CatalogRow> catalogue(Source source) {
        String id = source.spreadsheetId();
        Grid g = grid(id, "Date Prod.");
        var rows = read(id, g.title(), 3, Math.min(g.rows(), 1000), 3, 9, false);
        requireHeader(rows, 0, 1, "Firma de pe care vindem pe eMAG");
        requireHeader(rows, 0, 2, "PNK");
        requireHeader(rows, 0, 5, "Cod produs");
        var result = new ArrayList<CatalogRow>();
        int emptyIdentityRows = 0;
        for (int i = 1; i < rows.size(); i++) {
            String pnk = string(cell(rows, i, 2));
            String code = string(cell(rows, i, 5));
            String name = string(cell(rows, i, 0));
            String company = string(cell(rows, i, 1));
            if (name == null && pnk == null && code == null) continue;
            if (pnk == null && code == null) { emptyIdentityRows++; continue; }
            if (pnk == null || code == null) {
                diagnostic("warning", "unresolved_identity", id, g.title(), "C"+(i+3)+":I"+(i+3),
                        "Missing exact PNK or product code; retained in raw archive only");
                continue;
            }
            String account = account(company);
            var matches = products.stream().filter(p -> (p.pnk() == null || p.pnk().isBlank() || p.pnk().equals(pnk)) && p.productCode().equals(code)
                    && p.account().equals(account)).distinct().toList();
            ProductIdentity resolved = matches.size() == 1 ? new ProductIdentity(matches.getFirst().vendorId(),
                    matches.getFirst().account(), code, pnk) : null;
            if (resolved != null && (matches.getFirst().pnk() == null || matches.getFirst().pnk().isBlank()))
                diagnostic("warning", "exact_code_fallback", id, g.title(), "C"+(i+3)+":I"+(i+3),
                        "Database PNK absent; exact product code/account identifies source PNK " + pnk + " / " + code);
            if (resolved == null) diagnostic("warning", "unresolved_identity", id, g.title(),
                    "C"+(i+3)+":I"+(i+3), "Exact PNK/code/account mapping is missing or ambiguous: "
                            + pnk + " / " + code + " / " + Objects.toString(account, "unmapped company " + company));
            result.add(new CatalogRow(name, company, pnk, code, resolved));
        }
        if (emptyIdentityRows > 0) diagnostic("info", "catalogue_slots_without_identifiers", id, g.title(),
                "C4:I"+Math.min(g.rows(),1000), emptyIdentityRows
                        + " named catalogue slots have neither PNK nor product code; retained in raw archive only");
        return result;
    }

    private void readMatrix(Source source, String tab, List<CatalogRow> catalogue) {
        String id = source.spreadsheetId();
        Grid g = grid(id, tab);
        boolean ads = tab.equals(ADS), family = tab.equals(REVIEWS) || tab.equals(RATING);
        int headerRows = ads ? 4 : 3;
        int metricsStart = family ? 6 : 7;
        var headers = read(id, tab, 1, headerRows, 1, g.columns(), false);
        requireHeader(headers, headerRows-1, family ? 2 : 3, "PNK");
        var idRows = read(id, tab, headerRows+1, g.rows(), 1, metricsStart-1, false);
        int lastRow = headerRows;
        for (int r = 0; r < idRows.size(); r++) {
            if (string(cell(idRows, r, 1)) != null || string(cell(idRows, r, family ? 2 : 3)) != null)
                lastRow = headerRows+1+r;
        }
        if (lastRow == headerRows) return;
        int lastCol = metricsStart-1;
        for (int c = metricsStart-1; c < g.columns(); c++) {
            LocalDate date = date(cell(headers, 1, c));
            if (date != null && !date.isAfter(through)) lastCol = c+1;
        }
        if (lastCol < metricsStart) return;
        int count = lastRow-headerRows;
        var data = new ArrayList<List<Object>>(count);
        for (int r = 0; r < count; r++) data.add(new ArrayList<>(Collections.nCopies(lastCol, null)));
        for (int c1 = metricsStart; c1 <= lastCol;) {
            int c2 = Math.min(lastCol, c1 + Math.max(1, 40_000/count)-1);
            var chunk = read(id, tab, headerRows+1, lastRow, c1, c2, false);
            for (int r = 0; r < chunk.size(); r++) for (int c = 0; c < chunk.get(r).size(); c++)
                data.get(r).set(c1-1+c, chunk.get(r).get(c));
            c1 = c2+1;
        }
        Map<RawKey, RawTotals> zeroEvidence = ads ? readRawAds(source, catalogue) : Map.of();
        for (int r = 0; r < count; r++) {
            String pnk = string(cell(idRows, r, family ? 2 : 3));
            String name = string(cell(idRows, r, 1));
            if (pnk == null) continue;
            String company = family ? null : string(cell(idRows, r, 2));
            var candidates = catalogue.stream().filter(p -> p.pnk().equals(pnk)
                    && Objects.equals(p.name(), name) && (family || Objects.equals(p.company(), company))).toList();
            if (candidates.size() != 1 || candidates.getFirst().resolved() == null) {
                diagnostic("warning", "unresolved_source_row", id, tab, "B"+(headerRows+r+1)+":F"+(headerRows+r+1),
                        "Source PNK/name/company does not identify one verified catalogue product: " + pnk);
                continue;
            }
            var product = candidates.getFirst().resolved();
            var byDate = new TreeMap<LocalDate, Map<String, Object>>();
            var columnsByDate = new HashMap<LocalDate, List<Integer>>();
            var restoredByDate = new HashMap<LocalDate, List<String>>();
            for (int c = metricsStart-1; c < lastCol; c++) {
                LocalDate when = date(cell(headers, 1, c));
                if (when == null || when.isAfter(through)) continue;
                String metric;
                if (family) metric = tab.equals(REVIEWS) ? "reviews" : "rating";
                else if (ads) {
                    String group = lower(string(cell(headers, 2, c)));
                    String primitive = ADS_METRICS.get(string(cell(headers, 3, c)));
                    if (!GROUPS.contains(group) || primitive == null)
                        throw new IllegalArgumentException("Unknown Ads header at " + column(c+1));
                    metric = group + "_" + primitive;
                } else {
                    metric = ORGANIC_METRICS.get(string(cell(headers, 2, c)));
                    if (metric == null) throw new IllegalArgumentException("Unknown organic header at " + column(c+1));
                }
                Object value = normalized(cell(data, r, c));
                if (value instanceof String text && text.startsWith("#")) {
                    diagnostic("error", "source_formula_error", id, tab, column(c+1)+(headerRows+r+1), text);
                    value = null;
                } else if (!metric.equals("performance") && value != null && !(value instanceof BigDecimal)) {
                    diagnostic("error", "invalid_numeric_cell", id, tab, column(c+1)+(headerRows+r+1),
                            "Expected numeric value; original value retained in archive");
                    value = null;
                }
                var values = byDate.computeIfAbsent(when, _ -> new TreeMap<>());
                if (values.containsKey(metric)) throw new IllegalArgumentException("Duplicate metric/date header: " + metric);
                if (ads && value == null) {
                    String group = metric.substring(0, metric.indexOf('_'));
                    var proof = zeroEvidence.get(new RawKey(pnk, when, group));
                    String primitive = metric.substring(metric.indexOf('_')+1);
                    if (proof != null && proof.provesZero(primitive, when)) {
                        value = BigDecimal.ZERO;
                        restoredByDate.computeIfAbsent(when, _ -> new ArrayList<>()).add(metric);
                    }
                }
                values.put(metric, value);
                columnsByDate.computeIfAbsent(when, _ -> new ArrayList<>()).add(c+1);
            }
            for (var entry : byDate.entrySet()) {
                var values = entry.getValue();
                // An explicitly blank dated snapshot must supersede any previously imported
                // values. Missing source rows/identities still produce no observation.
                if (family) values.put("familyId", normalized(cell(idRows, r, 4)));
                else {
                    values.put("sourceStartDate", entry.getKey().minusDays(29).toString());
                    values.put("sourceEndDate", entry.getKey().toString());
                    values.put("sourceWindowDays", BigDecimal.valueOf(30));
                    if (restoredByDate.containsKey(entry.getKey()))
                        values.put("zeroRestoredMetrics", String.join(",", restoredByDate.get(entry.getKey())));
                }
                String domain = ads ? "ads" : family ? tab.equals(REVIEWS) ? "reviews" : "rating" : "organic";
                var columns = columnsByDate.get(entry.getKey());
                observations.add(new Observation(product.vendorId(), product.productCode(), product.pnk(), entry.getKey(),
                        domain, values, id, g.sheetId(), tab,
                        range(headerRows+r+1, headerRows+r+1, columns.getFirst(), columns.getLast())));
            }
        }
    }

    private record RawKey(String pnk, LocalDate date, String group) { }
    private static final class RawTotals {
        final Map<String, BigDecimal> sums = new HashMap<>();
        final Set<String> missing = new HashSet<>();
        final Set<LocalDate> days = new HashSet<>();
        boolean invalidDates;
        int rows;
        boolean provesZero(String metric, LocalDate end) {
            if (rows == 0 || invalidDates || missing.contains(metric) || !sums.containsKey(metric)
                    || sums.get(metric).signum() != 0 || days.size() != 30) return false;
            for (int i=0;i<30;i++) if (!days.contains(end.minusDays(i))) return false;
            return true;
        }
    }

    /** Raw rows validate suppressed zeroes; they never replace a nonzero matrix cell or add snapshots. */
    private Map<RawKey, RawTotals> readRawAds(Source source, List<CatalogRow> catalogue) {
        String sourceId = source.spreadsheetId();
        try {
            String formula = string(cell(read(sourceId, ADS, 1, 1, 1, 1, true), 0, 0));
            var ids = new HashSet<String>();
            var matcher = IMPORT_ID.matcher(Objects.toString(formula, ""));
            while (matcher.find()) ids.add(matcher.group(1));
            if (ids.size() != 1) throw new IllegalArgumentException("Cannot identify one original Ads master");
            String id = ids.iterator().next();
            Grid g = grid(id, "Introd. Date");
            var headers = read(id, g.title(), 3, 3, 2, Math.min(25, g.columns()), false);
            requireHeader(headers, 0, 0, "Data");
            requireHeader(headers, 0, 1, "Start date");
            requireHeader(headers, 0, 2, "End date");
            requireHeader(headers, 0, 4, "Campaign name");
            requireHeader(headers, 0, 8, "Match type");
            requireHeader(headers, 0, 9, "Impressions");
            requireHeader(headers, 0, 10, "Clicks");
            requireHeader(headers, 0, 13, "Spent");
            requireHeader(headers, 0, 16, "7 Day Total Sales");
            requireHeader(headers, 0, 17, "7 Day Total Orders");
            requireHeader(headers, 0, 18, "7 Day Total Units");
            int lastRow = 3;
            for (int first = 4; first <= g.rows(); first += 40_000) {
                var dates = read(id, g.title(), first, Math.min(g.rows(), first+39_999), 2, 2, false);
                for (int r=0;r<dates.size();r++) if (normalized(cell(dates,r,0)) != null) lastRow=first+r;
            }
            Set<String> pnks = new HashSet<>();
            for (var row : catalogue) pnks.add(row.pnk());
            var totals = new HashMap<RawKey, RawTotals>();
            Map<String,Integer> positions = Map.of("impressions",9,"clicks",10,"spend",13,
                    "sales",16,"salesCount",17,"units",18);
            for (int first=4;first<=lastRow;first+=2000) {
                var rows = read(id,g.title(),first,Math.min(lastRow,first+1999),2,25,false);
                for (var row : rows) {
                    LocalDate when = date(cell(row,0));
                    if (when == null || when.isAfter(through)) continue;
                    String campaign = Objects.toString(cell(row,4), "");
                    Set<String> tokens = new HashSet<>(List.of(campaign.split("[^A-Za-z0-9]+")));
                    var matched = pnks.stream().filter(tokens::contains).toList();
                    if (matched.size() != 1) continue;
                    String group = lower(string(cell(row,8)));
                    if ("products".equals(group)) group="product";
                    if (!GROUPS.contains(group)) continue;
                    var total = totals.computeIfAbsent(new RawKey(matched.getFirst(),when,group), _ -> new RawTotals());
                    total.rows++;
                    LocalDate start=date(cell(row,1)), end=date(cell(row,2));
                    if (start==null || end==null || start.isAfter(end) || start.isBefore(when.minusDays(29)) || end.isAfter(when))
                        total.invalidDates=true;
                    else for (LocalDate day=start;!day.isAfter(end);day=day.plusDays(1)) total.days.add(day);
                    for (var metric:positions.entrySet()) {
                        Object value=normalized(cell(row,metric.getValue()));
                        if (value instanceof BigDecimal number) total.sums.merge(metric.getKey(),number,BigDecimal::add);
                        else total.missing.add(metric.getKey());
                    }
                }
            }
            return totals;
        } catch (RuntimeException ex) {
            diagnostic("error", "raw_ads_validation_failed", sourceId, ADS, "A1", safeMessage(ex));
            return Map.of();
        }
    }

    private void rejectDuplicateObservations() {
        var counts = new HashMap<String,Integer>();
        for (var o:observations) counts.merge(key(o),1,Integer::sum);
        observations.removeIf(o -> {
            if (counts.get(key(o))==1) return false;
            diagnostic("error","duplicate_observation",o.sourceSpreadsheetId(),o.sourceSheet(),o.sourceRange(),
                    "Conflicting duplicate product/date/domain; neither row selected");
            return true;
        });
    }
    private static String key(Observation o) {
        return o.vendorId()+"|"+o.productCode()+"|"+o.observationDate()+"|"+o.domain();
    }
    private void diagnostic(String severity,String code,String id,String tab,String range,String message) {
        diagnostics.add(new Diagnostic(severity,code,id,tab,range,message));
    }
    private static void requireHeader(List<List<Object>> rows,int row,int col,String expected) {
        if (!expected.equals(string(cell(rows,row,col))))
            throw new IllegalArgumentException("Source header mismatch: expected '"+expected+"'");
    }
    public static LocalDate date(Object raw) {
        if (raw instanceof Number n) {
            double serial=n.doubleValue();
            if (!Double.isFinite(serial) || serial!=Math.floor(serial) || serial<36526 || serial>73050) return null;
            return SHEETS_EPOCH.plusDays(n.longValue());
        }
        if (raw instanceof String text) {
            try { return LocalDate.parse(text.trim()); } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }
    private static Object normalized(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text) return text.isBlank()?null:text.strip();
        return value;
    }
    private static String string(Object value) {
        if (!(value instanceof String text)) return null;
        return text.isBlank()?null:text.strip();
    }
    private static String lower(String value) { return value==null?"":value.toLowerCase(Locale.ROOT); }
    private static String account(String company) { return ACCOUNTS.get(lower(company)); }
    private static Object cell(List<List<Object>> rows,int row,int col) {
        return row<rows.size()?cell(rows.get(row),col):null;
    }
    private static Object cell(List<Object> row,int col) { return col<row.size()?row.get(col):null; }
    public static String column(int index) {
        var result=new StringBuilder();
        for(int i=index;i>0;i=(i-1)/26) result.append((char)('A'+(i-1)%26));
        return result.reverse().toString();
    }
    private static String range(int r1,int r2,int c1,int c2) {
        return column(c1)+r1+":"+column(c2)+r2;
    }
    private static String safeMessage(RuntimeException ex) {
        // Source IDs and exception classes are enough for audit; HTTP bodies/credential paths are not archived.
        if (ex instanceof IllegalArgumentException || ex instanceof NullPointerException)
            return Objects.toString(ex.getMessage(),ex.getClass().getSimpleName());
        return ex.getClass().getSimpleName()+": source request failed; retry the dry run";
    }
    private static Map<String,Object> observationJson(Observation o) {
        var row=new TreeMap<String,Object>();
        row.put("vendorId",o.vendorId().toString());row.put("productCode",o.productCode());row.put("pnk",o.pnk());
        row.put("observationDate",o.observationDate().toString());row.put("domain",o.domain());row.put("values",o.values());
        row.put("sourceSpreadsheetId",o.sourceSpreadsheetId());row.put("sourceSheetId",o.sourceSheetId());
        row.put("sourceSheet",o.sourceSheet());row.put("sourceRange",o.sourceRange());return row;
    }
    public static String sha256(String input) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
