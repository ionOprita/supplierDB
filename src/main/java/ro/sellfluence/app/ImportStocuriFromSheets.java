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
    private static final String DEFAULT_SPREADSHEET_NAME = "S. Stocuri";
    public static final String USAGE = """
            ImportStocuriFromSheets [--db=emagLocal] [--group=S] [--spreadsheet-name="S. Stocuri"] [--dry-run | --apply]
            Defaults to the Importuri tab in "S. Stocuri". For another register, supply its group and exact workbook name.
            --apply copies all validated changes into the selected DB in one transaction. The source sheet wins for previously copied imports.
            """;
    private ImportStocuriFromSheets() {}

    record Options(String database, String group, String spreadsheetName, boolean apply) {}

    static Options options(String[] args) {
        var values = new HashMap<String,String>();
        boolean apply=false, dryRun=false;
        for (String arg : args) {
            if ("--apply".equals(arg)) { apply=true; continue; }
            if ("--dry-run".equals(arg)) { dryRun=true; continue; }
            String[] parts = arg.split("=",2);
            if (parts.length != 2 || !Set.of("--db", "--group", "--spreadsheet-name").contains(parts[0])
                    || parts[1].isBlank() || values.put(parts[0], parts[1]) != null)
                throw new IllegalArgumentException("Invalid or duplicate option: " + arg + "\n" + USAGE);
        }
        if (apply && dryRun) throw new IllegalArgumentException("Choose either --dry-run or --apply.");
        String group = values.getOrDefault("--group", "S").strip().toUpperCase(Locale.ROOT);
        String name = values.getOrDefault("--spreadsheet-name", DEFAULT_SPREADSHEET_NAME);
        if (DEFAULT_SPREADSHEET_NAME.equals(name)) {
            if (!"S".equals(group))
                throw new IllegalArgumentException("Supply --spreadsheet-name for group " + group
                        + ". The default workbook belongs to group S.");
        } else if (!values.containsKey("--group")) {
            throw new IllegalArgumentException("Supply --group with an alternate --spreadsheet-name.");
        }
        return new Options(values.getOrDefault("--db", defaultDatabase).strip(), group, name, apply);
    }

    public static void main(String[] args) throws Exception {
        if (args.length==1 && "--help".equals(args[0])) { System.out.print(USAGE); return; }
        var options=options(args);
        StockImportSheetReader.ResolvedSnapshot source;
        try {
            source = new StockImportSheetReader().read(options.spreadsheetName());
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode()==403)
                throw new IllegalStateException("Google denied read access to \"" + options.spreadsheetName()
                        + "\". Verify that the workbook grants at least Viewer access to the client_email in "
                        + "~/Secrets/googleServiceAccount.json and that the Drive and Sheets APIs are enabled.", e);
            throw e;
        }
        System.out.printf("Workbook: %s / Importuri — https://docs.google.com/spreadsheets/d/%s/edit#gid=%d%n",
                options.spreadsheetName(), source.spreadsheetId(), source.tabId());
        var parsed = StockImportSheetParser.parse(source.snapshot());
        long lines=parsed.records().stream().mapToLong(s -> s.document().lines().size()).sum();
        System.out.printf("Source: %d imports, %d product lines. Target: %s / %s. Mode: %s.%n",
                parsed.records().size(), lines, options.database(), options.group(),
                options.apply() ? "apply" : "dry-run");
        parsed.warnings().forEach(w -> System.out.println("Warning: " + w));
        var db=DBPass.findDB(options.database());
        try (var connection=DriverManager.getConnection(db.connect(),db.user(),db.pw())) {
            connection.setReadOnly(!options.apply());
            connection.setAutoCommit(false);
            try {
                var result = StockImportTable.reconcile(connection, options.group(), source.spreadsheetId(),
                        source.tabId(), parsed.records(), options.apply());
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
