package ro.sellfluence.app;

import ch.claudio.db.DBPass;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import ro.sellfluence.apphelper.StockImportSheetParser;
import ro.sellfluence.db.StockImportTable;
import ro.sellfluence.googleapi.StockImportSheetReader;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.googleapi.StockImportSheetReader.columnName;

/** Manual cutover tool. Run against a database with the Stocuri migration already installed. */
public final class ImportStocuriFromSheets {
    static final String DEFAULT_BOOK = "1fNOYIx057225mG-jo71a7U1CD95b7cqDVPScvsHT0ng";
    static final int DEFAULT_GID = 26177680;
    public static final String USAGE = """
            ImportStocuriFromSheets [--db=emagLocal] [--group=S] [--spreadsheet-id=ID --gid=GID] [--dry-run | --apply]
            Default is a read-only preview of the S register. For another register, supply its group, spreadsheet ID and Importuri gid.
            --apply copies all validated changes into the selected DB in one transaction. The source sheet wins for previously copied imports.
            """;
    private ImportStocuriFromSheets() {}

    record Options(String database, String group, String spreadsheet, int gid, boolean apply) {}

    static Options options(String[] args) {
        var values = new HashMap<String,String>();
        boolean apply=false, dryRun=false;
        for (String arg : args) {
            if ("--apply".equals(arg)) { apply=true; continue; }
            if ("--dry-run".equals(arg)) { dryRun=true; continue; }
            String[] parts = arg.split("=",2);
            if (parts.length!=2 || !Set.of("--db","--group","--spreadsheet-id","--gid").contains(parts[0])
                    || parts[1].isBlank() || values.put(parts[0],parts[1].strip())!=null)
                throw new IllegalArgumentException("Invalid or duplicate option: " + arg + "\n" + USAGE);
        }
        if (apply && dryRun) throw new IllegalArgumentException("Choose either --dry-run or --apply.");
        String group=values.getOrDefault("--group","S").toUpperCase(Locale.ROOT);
        String book=values.getOrDefault("--spreadsheet-id",DEFAULT_BOOK);
        int gid=Integer.parseInt(values.getOrDefault("--gid",Integer.toString(DEFAULT_GID)));
        if (!book.matches("[A-Za-z0-9_-]+") || gid<0) throw new IllegalArgumentException("Invalid spreadsheet ID or gid.");
        if ((!DEFAULT_BOOK.equals(book) || DEFAULT_GID!=gid || !"S".equals(group))
                && !(values.containsKey("--group") && values.containsKey("--spreadsheet-id") && values.containsKey("--gid")))
            throw new IllegalArgumentException("An alternate source requires explicit --group, --spreadsheet-id and --gid.");
        if (DEFAULT_BOOK.equals(book) && !"S".equals(group))
            throw new IllegalArgumentException("The supplied S workbook belongs to vendor group S.");
        return new Options(values.getOrDefault("--db",defaultDatabase),group,book,gid,apply);
    }

    public static void main(String[] args) throws Exception {
        if (args.length==1 && "--help".equals(args[0])) { System.out.print(USAGE); return; }
        var options=options(args);
        StockImportSheetReader.Snapshot snapshot;
        try {
            snapshot = new StockImportSheetReader().read(options.spreadsheet(),options.gid());
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode()==403)
                throw new IllegalStateException("Google Sheets denied read access. Verify that the workbook is shared as Viewer "
                        + "with the client_email in ~/Secrets/googleServiceAccount.json and that the Sheets API is enabled.", e);
            throw e;
        }
        var parsed=StockImportSheetParser.parse(snapshot);
        long lines=parsed.records().stream().mapToLong(s -> s.document().lines().size()).sum();
        System.out.printf("Source: %d imports, %d product lines. Target: %s / %s. Mode: %s.%n",
                parsed.records().size(),lines,options.database(),options.group(),options.apply()?"apply":"dry-run");
        parsed.warnings().forEach(w -> System.out.println("Warning: " + w));
        var db=DBPass.findDB(options.database());
        try (var connection=DriverManager.getConnection(db.connect(),db.user(),db.pw())) {
            connection.setReadOnly(!options.apply());
            connection.setAutoCommit(false);
            try {
                var result=StockImportTable.reconcile(connection,options.group(),options.spreadsheet(),options.gid(),
                        parsed.records(),options.apply());
                System.out.printf("Insert: %d. Update: %d. Unchanged: %d.%n",
                        result.inserted(),result.updated(),result.unchanged());
                for (var change : result.changes()) {
                    System.out.printf("%s import %s (Importuri!%s2)%s%n",
                            change.inserted() ? "Insert" : "Update", change.reference(), columnName(change.column()),
                            change.replacesLocalEdit() ? " — replaces changes made in the app" : "");
                }
                result.missing().forEach(key -> System.out.println("Retained in DB, absent from sheet: " + key));
                result.conflicts().forEach(message -> System.out.println("Conflict: " + message));
                if (!result.conflicts().isEmpty()) throw new IllegalStateException("Resolve reference conflicts before applying.");
                if (options.apply()) {
                    connection.commit();
                    System.out.println("Import committed.");
                } else {
                    connection.rollback();
                    System.out.println("Dry run complete.");
                }
            } catch (Exception e) {
                try { connection.rollback(); } catch (Exception failure) { e.addSuppressed(failure); }
                throw e;
            }
        }
    }
}
