package ro.sellfusion.ads;

import ch.claudio.db.DB;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.DriveAPI.FileResult;
import ro.sellfluence.support.Logs;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.math.MathContext.DECIMAL64;
import static java.sql.Statement.SUCCESS_NO_INFO;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.support.UsefulMethods.require;
import static ro.sellfluence.support.UsefulMethods.toDate;

public class EmagAds {

    private static final Logger logger = Logs.getConsoleLogger("GetKeywordsForSEO", INFO);

    private static final DB database = initDatabase();

    private static final List<String> expectedHeader = List.of("Expresii cautate", "ID campanie Ads", "Adset ID", "Clicks", "Impressions", "CTR", "CPC Actual", "Cost", "CPS", "Order value", "Produse vandute");

    private static final DriveAPI drive = DriveAPI.getDriveAPI(defaultGoogleApp);

    static void main() throws Exception {
        // Find KW_auto
        var filesFound = drive.findFiles(Pattern.compile("KW_(auto|manual).*\\.xlsx"), "name contains 'KW_'");
        logger.log(INFO, "Found " + filesFound.size() + " files");
        //var dir = Paths.get("/var/folders/93/8hnm_v9x5dq9mwdhzlf0pmcr0000gq/T/ExeclDownloads9916024981997326495/");
        var dir = downloadAllFiles(filesFound);
        logger.log(INFO, "Downloaded " + dir.toFile().listFiles().length + " files to " + dir);
        List<Path> fileList;
        try (var list = Files.list(dir)) {
            fileList = list
                    .filter(it -> it.getFileName().toString().endsWith(".xlsx")).toList();
        }
        for (Path file : fileList) {
            var data = extractSearchPhrases(file);
            var matcher = Pattern.compile("KW_\\w+_(.*)_(\\d{4,})-(\\d{2,})-(\\d{2,})\\s*-\\s*(\\d{4,})-(\\d{2,})-(\\d{2,})\\.xlsx").matcher(file.getFileName().toString());
            if (matcher.matches()) {
                var product = matcher.group(1);
                var startDate = LocalDate.of(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
                var endDate = LocalDate.of(Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)), Integer.parseInt(matcher.group(7)));
                var result = writeToDB(product, startDate, endDate, data);
                if (result.failed > 0) { logger.log(WARNING, "Failed to insert %d rows from %s".formatted(result.failed, file.getFileName()));}
                if (result.probablyInserted > 0) { logger.log(WARNING, "Unexpected probably inserted %d rows from %s".formatted(result.probablyInserted, file.getFileName()));}
                if (result.inserted + result.probablyInserted != data.size()) { logger.log(WARNING, "Inserted %d rows instead of %d from %s".formatted(result.inserted + result.probablyInserted, data.size(), file.getFileName()));}
            } else {
                logger.log(SEVERE, "Cannot parse file name: %s.".formatted(file.getFileName()));
            }
        }
    }

    private static Path downloadAllFiles(List<FileResult> filesFound) {
        try {
            var tmpDir = Files.createTempDirectory("ExeclDownloads");
            filesFound.forEach(it -> download(it, tmpDir));
            return tmpDir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void download(FileResult file, Path targetDir) {
        var filename = file.name();
        var destination = targetDir.resolve(filename);
        try (OutputStream out = Files.newOutputStream(destination)) {
            drive.download(file, out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logger.log(INFO, "Downloaded " + filename + " to " + destination);
    }

    private static List<List<Object>> extractSearchPhrases(Path path) {
        try {
            var data = extractColumnsAtoK(path, "Search Phrases");
            var header = data.get(4);
            if (header.equals(expectedHeader)) {
                return data.stream().skip(5).toList();
            } else {
                logger.log(WARNING, () -> "Ignoring file %s because header not as expected: %s".formatted(path, header));
                return List.of();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<List<Object>> extractColumnsAtoK(Path excelPath, String tabName) throws IOException {
        List<List<Object>> data = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(excelPath.toFile());
             Workbook workbook = new XSSFWorkbook(fis)) {

            var sheet = workbook.getSheet(tabName); // first sheet

            for (Row row : sheet) {
                List<Object> rowData = new ArrayList<>();

                // Columns A–K => indices 0–10
                for (int col = 0; col <= 10; col++) {
                    Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    rowData.add(getCellValue(cell));
                }

                data.add(rowData);
            }
        }

        return data;
    }

    private static Object getCellValue(Cell cell) {
        if (cell == null) return "";

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    throw new IllegalArgumentException("Date not expected");
                    //return cell.getDateCellValue().toString();
                }
                yield new BigDecimal(cell.getNumericCellValue(), DECIMAL64);
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> throw new IllegalArgumentException("Formula not expected");
            case BLANK -> null;
            default -> throw new IllegalArgumentException("Unexpected cell type " + cell.getCellType());
        };
    }

    private record BatchResult(long inserted, long probablyInserted, long failed) {}

    private static BatchResult writeToDB(String product, LocalDate startDate, LocalDate endDate, List<List<Object>> data) throws SQLException {
        logger.log(INFO, "Product: " + product + ", start date: " + startDate + ", end date: " + endDate);
        return database.writeTX(db -> {
                    var productId = upsertProductAndGetId(db, product);
                    var periodId = upsertPeriodAndGetId(db, startDate, endDate);
                    try (var s = db.prepareStatement(
                            """
                                    INSERT INTO searches (product_id, period_id, search_phrase_id, campaign_id, ad_set_id, clicks, impressions, ctr, cpc_actual, cost, cps, order_value, products_sold)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(product_id,period_id,search_phrase_id) DO 
                                    UPDATE SET 
                                    campaign_id = EXCLUDED.campaign_id,
                                    ad_set_id = EXCLUDED.ad_set_id,
                                    clicks = EXCLUDED.clicks,
                                    impressions = EXCLUDED.impressions,
                                    ctr = EXCLUDED.ctr,
                                    cpc_actual = EXCLUDED.cpc_actual,
                                    cost = EXCLUDED.cost,
                                    cps = EXCLUDED.cps,
                                    order_value = EXCLUDED.order_value,
                                    products_sold = EXCLUDED.products_sold
                                    """
                    )) {
                        for (List<Object> row : data) {
                            require(row.size() == 11, "Expected 11 columns, got %d".formatted(row.size()));
                            try {
                                checkRow(row, 0, String.class);
                                for (int index = 1; index <= 4; index++) {
                                    checkRow(row, index, Long.class);
                                }
                                for (int index = 5; index <= 10; index++) {
                                    checkRow(row, index, BigDecimal.class);
                                }
                                var phraseId = upsertPhraseAndGetId(db, (String) row.getFirst());
                                s.setLong(1, productId);
                                s.setLong(2, periodId);
                                s.setLong(3, phraseId);
                                s.setLong(4, (long) row.get(1));
                                s.setLong(5, (long) row.get(2));
                                s.setLong(6, (long) row.get(3));
                                s.setLong(7, (long) row.get(4));
                                s.setBigDecimal(8, (BigDecimal) row.get(5));
                                s.setBigDecimal(9, (BigDecimal) row.get(6));
                                s.setBigDecimal(10, (BigDecimal) row.get(7));
                                s.setBigDecimal(11, (BigDecimal) row.get(8));
                                s.setBigDecimal(12, (BigDecimal) row.get(9));
                                s.setBigDecimal(13, (BigDecimal) row.get(10));
                                s.addBatch();
                            } catch (IllegalStateException e) {
                                logger.log(SEVERE, "Error in row %s of product %s for period %s - %s".formatted(row, product, startDate, endDate));
                                logger.log(SEVERE, "Types in row are %s".formatted(row.stream().map(it -> it.getClass().getName()).toList().toString()));
                                throw new RuntimeException(e);
                            }
                        }
                        var result = s.executeBatch();
                        var inserted = Arrays.stream(result).filter(it->it==1).count();
                        var probablyInserted = Arrays.stream(result).filter(it->it==SUCCESS_NO_INFO).count();
                        var failed = Arrays.stream(result).filter(it->it!=SUCCESS_NO_INFO&&it!=1).count();
                        return new BatchResult(inserted, probablyInserted, failed);
                    }
                }
        );
    }

    private static void checkRow(List<Object> row, int index, Class<?> klass) {
        Object value = row.get(index);
        if (!klass.isAssignableFrom(value.getClass())) {
            if (value.getClass() == String.class) {
                // Sigh, we will do the conversion.
                if (klass == Long.class) {
                    row.set(index, Long.parseLong((String) value));
                } else if (klass == BigDecimal.class) {
                    row.set(index, new BigDecimal((String) value, DECIMAL64));
                }
            } else {
                throw new IllegalArgumentException("Expected %s at column %d but was %s in row %s".formatted(klass.getName(), index, value.getClass().getName(), row));
            }
        }
    }

    private static DB initDatabase() {
        try {
            var database = new DB("emagAds");
            database.prepareDB(
                    db -> {
                        createProductTable(db);
                        createPeriodTable(db);
                        createPhraseTable(db);
                        createSearchesTable(db);
                        createIndices(db);
                    }
            );
            return database;
        } catch (Exception e) {
            if (e.getMessage().contains("password authentication failed")) {
                IO.println("Password authentication failed. Please verify you have created the user emag_ads.");
                IO.println("You can create it with the following command (using your password):");
                IO.println("CREATE ROLE emag_ads WITH LOGIN PASSWORD 'password';");
                IO.println("Make also sure you have added an entry in %s/Secrets/dbpass.txt".formatted(System.getProperty("user.home")));
            } else if (e.getMessage().contains("database \"emag_ads\" does not exist")) {
                IO.println("Database emag_ads does not exist. Please create it with the following command:");
                IO.println("CREATE DATABASE emag_ads WITH OWNER = emag_ads TEMPLATE template0;");
                IO.println("Make also sure you have added an entry in %s/Secrets/dbpass.txt".formatted(System.getProperty("user.home")));
            }
            throw new RuntimeException(e);
//        } catch (SQLException e) {
//            throw new RuntimeException(e);
        }
    }

    private static long upsertProductAndGetId(Connection db, String product) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO product (product)
                VALUES (?)
                ON CONFLICT (product) DO UPDATE SET product = EXCLUDED.product -- do something so that we can return the product_id
                RETURNING product_id
                """)) {
            s.setString(1, product);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("No product_id returned");
            }
        }
    }

    private static long upsertPhraseAndGetId(Connection db, String product) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO phrase (search_phrase)
                VALUES (?)
                ON CONFLICT (search_phrase) DO UPDATE SET search_phrase = EXCLUDED.search_phrase -- do something so that we can return the product_id
                RETURNING search_phrase_id
                """)) {
            s.setString(1, product);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("No search_phrase_id returned");
            }
        }
    }

    private static long upsertPeriodAndGetId(Connection db, LocalDate start, LocalDate end) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO period (start_date, end_date)
                VALUES (?,?)
                ON CONFLICT (start_date, end_date) DO UPDATE SET start_date = EXCLUDED.start_date -- do something so that we can return the product_id
                RETURNING period_id
                """)) {
            s.setDate(1, toDate(start));
            s.setDate(2, toDate(end));
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("No period_id returned");
            }
        }
    }

    private static void createSearchesTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE searches (
                    product_id bigint NOT NULL REFERENCES product(product_id),
                    period_id bigint NOT NULL REFERENCES period(period_id),
                    search_phrase_id bigint NOT NULL REFERENCES phrase(search_phrase_id),
                    campaign_id INTEGER,
                    ad_set_id INTEGER,
                    clicks bigint NOT NULL DEFAULT 0,
                    impressions bigint NOT NULL DEFAULT 0,
                    ctr numeric(8,2),
                    cpc_actual numeric(8,2),
                    cost numeric(8,2),
                    cps numeric(8,2),
                    order_value numeric(8,2),
                    products_sold integer,
                    CONSTRAINT uq_ids UNIQUE (product_id, period_id, search_phrase_id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createProductTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE product (
                  product_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                  product text UNIQUE NOT NULL
                );
                """)) {
            s.execute();
        }
    }

    private static void createPeriodTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE period (
                  period_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                  start_date date NOT NULL,
                  end_date date NOT NULL,
                  CONSTRAINT chk_period CHECK (end_date >= start_date),
                  CONSTRAINT uq_period UNIQUE (start_date, end_date)
                );
                """)) {
            s.execute();
        }
    }

    private static void createPhraseTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE phrase (
                  search_phrase_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                  search_phrase text UNIQUE NOT NULL
                );
                """)) {
            s.execute();
        }
    }

    private static void createIndices(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE INDEX ON searches (product_id, period_id);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE INDEX ON searches (search_phrase_id);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE INDEX ON period (start_date, end_date);
                """)) {
            s.execute();
        }
    }

}

/*
    private static final String excelMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String tabSeparatedValues = "text/tab-separated-values";
 */