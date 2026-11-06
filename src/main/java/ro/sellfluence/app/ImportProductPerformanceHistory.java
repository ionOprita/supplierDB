package ro.sellfluence.app;

import ch.claudio.db.DBPass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter;
import ro.sellfluence.apphelper.ProductPerformanceHistoryCaptureReader;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.ProductIdentity;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Result;
import ro.sellfluence.db.ProductPerformanceHistoryTable;
import ro.sellfluence.db.ProductPerformanceHistoryTable.ImportBatch;
import ro.sellfluence.support.Arguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Explicit one-off importer. It never starts the application, refresh, or eMAG collectors. */
public final class ImportProductPerformanceHistory {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        var options = new Arguments(args);
        if (options.hasFlag("help")) {
            System.out.println("ImportProductPerformanceHistory [--db=emagLocal] [--through=YYYY-MM-DD] "
                    + "[--out=/tmp/product-performance-history] [--only=Judios] "
                    + "[--capture-file=/path/to/capture.json,...] "
                    + "[--apply --expected-hash=<dry-run-hash>]");
            return;
        }
        boolean apply = options.hasFlag("apply");
        if (apply && !options.hasOption("expected-hash"))
            throw new IllegalArgumentException("--apply requires --expected-hash from a reviewed dry run");
        String alias = options.getOption("db", "emagLocal");
        LocalDate through = LocalDate.parse(options.getOption("through",
                LocalDate.now(ZoneId.of("Europe/Bucharest")).toString()));
        Path output = Path.of(options.getOption("out", "/tmp/product-performance-history"));
        Files.createDirectories(output);
        var sources = ProductPerformanceHistoryImporter.APPROVED_SOURCES;
        if (options.hasOption("only")) {
            sources = sources.stream().filter(s -> s.name().equalsIgnoreCase(options.getOption("only"))).toList();
            if (sources.isEmpty()) throw new IllegalArgumentException("--only must name an approved report");
        }
        Result result;
        var accounts = new TreeMap<String,String>();
        try (Connection db = connect(alias, true)) {
            var products = identities(db);
            for (var product : products) accounts.put(product.vendorId().toString(), product.account());
            db.rollback();
            ProductPerformanceHistoryImporter.Reader reader = options.hasOption("capture-file")
                    ? new ProductPerformanceHistoryCaptureReader(Arrays.stream(options.getOption("capture-file").split(","))
                        .map(Path::of).toList()) : ProductPerformanceHistoryImporter.googleReader();
            result = new ProductPerformanceHistoryImporter(reader, products, through)
                    .read(sources);
        } catch (Exception ex) {
            var failure = Map.of("status", "failed", "phase", "source acquisition or database identity read",
                    "exception", ex.getClass().getSimpleName(), "database", alias, "throughDate", through.toString());
            Files.writeString(output.resolve("last-failure.json"), JSON.toJson(failure));
            throw ex;
        }
        Path archive = output.resolve(result.contentHash()+".json");
        if (!Files.exists(archive)) Files.writeString(archive, result.sourcePayloadJson(), StandardOpenOption.CREATE_NEW);
        else if (!Files.readString(archive).equals(result.sourcePayloadJson()))
            throw new IllegalStateException("Existing archive does not match its content hash");
        var report = report(result, alias, archive, accounts);
        Path reportFile = output.resolve(result.contentHash()+"-report.json");
        Files.writeString(reportFile, JSON.toJson(report));
        System.out.println("History dry run: " + result.observations().size()+" observations, "
                + result.diagnostics().size()+" diagnostics; complete="+result.complete());
        System.out.println("Content hash: "+result.contentHash());
        System.out.println("Report: "+reportFile);
        if (!result.complete()) throw new IllegalStateException("Source errors must be resolved before applying; see report");
        if (!apply) return;
        if (!result.contentHash().equals(options.getOption("expected-hash")))
            throw new IllegalStateException("Source/mapping content changed since the reviewed dry run; review the new report");
        try (Connection db = connect(alias, false)) {
            try {
                boolean migrated = ProductPerformanceHistoryTable.prepareSchema(db);
                var stored = ProductPerformanceHistoryTable.storeBatch(db,
                        new ImportBatch(result.contentHash(), result.sourcePayloadJson(), result.observations()));
                db.commit();
                report.put("status", stored.inserted()?"applied":"unchanged");
                report.put("batchId", stored.batchId().toString());
                report.put("schemaMigrated", migrated);
                report.put("storedObservations", stored.observationCount());
                Files.writeString(reportFile, JSON.toJson(report));
                System.out.println("History import "+report.get("status")+": batch "+stored.batchId());
            } catch (Exception ex) {
                db.rollback();
                report.put("status","failed"); report.put("phase","database apply");
                report.put("exception",ex.getClass().getSimpleName());
                Files.writeString(reportFile,JSON.toJson(report));
                throw ex;
            }
        }
    }

    private static Connection connect(String alias, boolean readOnly) throws Exception {
        var spec = DBPass.findDB(alias);
        if (!readOnly && !List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(spec.dbHost()))
            throw new IllegalArgumentException("History apply requires a local database host");
        Connection db = DriverManager.getConnection(spec.connect(), spec.user(), spec.pw());
        db.setAutoCommit(false);
        db.setReadOnly(readOnly);
        return db;
    }

    private static List<ProductIdentity> identities(Connection db) throws SQLException {
        var result = new ArrayList<ProductIdentity>();
        try (var statement = db.prepareStatement("""
                SELECT p.vendor, p.product_code, p.emag_pnk, v.account
                FROM product p JOIN vendor v ON v.id=p.vendor
                WHERE p.product_code IS NOT NULL AND v.account IS NOT NULL
                ORDER BY v.account, p.product_code, p.emag_pnk
                """); var rows = statement.executeQuery()) {
            while (rows.next()) result.add(new ProductIdentity(rows.getObject("vendor", UUID.class),
                    rows.getString("account").strip(), rows.getString("product_code").strip(),
                    rows.getString("emag_pnk") == null ? null : rows.getString("emag_pnk").strip()));
        }
        return List.copyOf(result);
    }

    private static Map<String,Object> report(Result result, String alias, Path archive, Map<String,String> accounts) {
        var report = new TreeMap<String,Object>();
        report.put("status",result.complete()?"dry-run":"failed"); report.put("complete",result.complete());
        report.put("parserVersion",ProductPerformanceHistoryImporter.PARSER_VERSION);
        report.put("database",alias); report.put("contentHash",result.contentHash()); report.put("archive",archive.toString());
        report.put("accounts",accounts);
        var accountCounts = new TreeMap<String,Map<String,Integer>>();
        for (var observation : result.observations()) accountCounts
                .computeIfAbsent(accounts.getOrDefault(observation.vendorId().toString(), observation.vendorId().toString()),
                        _ -> new TreeMap<>()).merge(observation.domain(), 1, Integer::sum);
        report.put("observationsByAccountAndDomain",accountCounts);
        report.put("observationCount",result.observations().size()); report.put("diagnostics",result.diagnostics());
        report.put("restoredZeroMetrics",result.observations().stream()
                .map(o->o.values().get("zeroRestoredMetrics")).filter(java.util.Objects::nonNull)
                .mapToLong(value->value.toString().split(",").length).sum());
        var coverage = new TreeMap<String,Map<String,Object>>();
        for (var o:result.observations()) {
            String key=o.vendorId()+" / "+o.productCode()+" / "+o.pnk()+" / "+o.domain();
            var row=coverage.computeIfAbsent(key,_ -> new TreeMap<>());
            row.put("count",((Number)row.getOrDefault("count",0)).intValue()+1);
            String date=o.observationDate().toString();
            if (!row.containsKey("firstDate") || date.compareTo((String)row.get("firstDate"))<0) row.put("firstDate",date);
            if (!row.containsKey("lastDate") || date.compareTo((String)row.get("lastDate"))>0) row.put("lastDate",date);
        }
        report.put("coverage",coverage);
        return report;
    }
}
