package ro.sellfluence.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Temporary, database-independent data for reviewing the product performance dashboard.
 * Values are illustrative samples, not calculations or product classification rules.
 */
public final class ProductPerformanceMockData {
    private static final LocalDate FIRST_WEEK = LocalDate.of(2026, 5, 1);
    private static final long SEED = 20260501L;
    private static final int WEEK_COUNT = 19;

    private static final List<ColumnGroup> GROUPS = List.of(
            new ColumnGroup("overview", "Product metrics", List.of(
                    column("week", "Week", "date"),
                    column("stock", "Stock", "integer"),
                    column("gmv30", "GMV 30", "integer"),
                    column("clicks", "Clicks", "integer"),
                    column("salesPrice", "Sales Price", "decimal"),
                    column("conversion", "Conv.", "percent"),
                    column("performance", "Perform.", "text"),
                    column("averagePrice", "Average price", "decimal"),
                    column("reviews", "# rev.", "integer"),
                    column("rating", "rating", "decimal")
            )),
            new ColumnGroup("total", "Total", adsColumns("total", null)),
            new ColumnGroup("auto", "Auto", adsColumns("auto", "Auto")),
            new ColumnGroup("broad", "Broad", adsColumns("broad", "Broad")),
            new ColumnGroup("exact", "Exact", adsColumns("exact", "Exact")),
            new ColumnGroup("product", "Product", adsColumns("product", "Prod."))
    );

    private ProductPerformanceMockData() {
    }

    public record Product(String label, String name, String pnk, String url) {
    }

    /** Percent columns contain fractional numbers, for example 0.0267 means 2.67%. */
    public record Column(String key, String label, String type) {
    }

    public record ColumnGroup(String key, String label, List<Column> columns) {
    }

    public record Row(Map<String, Object> values) {
    }

    public record Response(boolean mock, Product product, List<ColumnGroup> groups, List<Row> rows) {
    }

    static Response create() {
        return create(new Product("SELL", "S. 53 - Trimmer 2.0", "DDHSVQMBM",
                "https://emag.ro/product_details/pd/DDHSVQMBM"));
    }

    static Response create(Product product) {
        var random = new Random(SEED);
        var rows = new ArrayList<Row>();
        for (int week = 0; week < WEEK_COUNT; week++) {
            var values = new LinkedHashMap<String, Object>();
            for (var group : GROUPS) {
                for (var column : group.columns()) {
                    values.put(column.key(), sampleValue(column, week, random));
                }
            }
            // Unlike Map.copyOf, this representation also supports future unavailable values.
            rows.add(new Row(Collections.unmodifiableMap(values)));
        }
        return new Response(true, product, GROUPS, List.copyOf(rows));
    }

    private static Column column(String key, String label, String type) {
        return new Column(key, label, type);
    }

    private static List<Column> adsColumns(String group, String shareLabel) {
        var columns = new ArrayList<>(List.of(
                column(group + "_impressions", "Imp.", "integer"),
                column(group + "_clicks", "Clicks", "integer"),
                column(group + "_spend", "Spend", "integer"),
                column(group + "_sales", "Sales", "integer"),
                column(group + "_units", "Total Units Sold", "integer"),
                column(group + "_ctr", "CTR", "percent"),
                column(group + "_conversion", "Conv.", "percent"),
                column(group + "_cpc", "CPC", "decimal"),
                column(group + "_roas", "ROAS", "decimal"),
                column(group + "_acos", "ACOS", "percent"),
                column(group + "_tacos", "TACOS", "percent")
        ));
        if (shareLabel == null) {
            columns.add(column(group + "_clicks_share", "% Clicks Ads of Total", "percent"));
            columns.add(column(group + "_sales_share", "% Sales Ads of Total", "percent"));
        } else {
            columns.add(column(group + "_impressions_share", "% Imp. " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_clicks_share", "% Clicks " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_spend_share", "% Spend " + shareLabel + " of Tot. Ads", "percent"));
            columns.add(column(group + "_sales_share", "% Sales " + shareLabel + " of Tot. Ads", "percent"));
        }
        return List.copyOf(columns);
    }

    private static Object sampleValue(Column column, int week, Random random) {
        return switch (column.type()) {
            case "date" -> FIRST_WEEK.plusWeeks(week).toString();
            case "text" -> "SUPER HOT";
            case "integer" -> sampleInteger(column, random);
            case "decimal" -> sampleDecimal(column, random);
            case "percent" -> switch (column.label()) {
                case "CTR", "Conv." -> random.nextInt(100, 801) / 10_000.0;
                default -> random.nextInt(500, 8001) / 10_000.0;
            };
            default -> throw new IllegalArgumentException("Unsupported sample column type: " + column.type());
        };
    }

    private static int sampleInteger(Column column, Random random) {
        return switch (column.label()) {
            case "Stock" -> random.nextInt(200, 5001);
            case "GMV 30" -> random.nextInt(10_000, 80_001);
            case "Imp." -> random.nextInt(10_000, 250_001);
            case "Clicks" -> "clicks".equals(column.key())
                    ? random.nextInt(5000, 20_001) : random.nextInt(100, 4001);
            case "Spend" -> random.nextInt(250, 15_001);
            case "Sales" -> random.nextInt(1000, 50_001);
            case "Total Units Sold" -> random.nextInt(10, 401);
            case "# rev." -> random.nextInt(100, 3001);
            default -> throw new IllegalArgumentException("Unsupported sample count: " + column.label());
        };
    }

    private static double sampleDecimal(Column column, Random random) {
        return switch (column.label()) {
            case "Sales Price", "Average price" -> random.nextInt(8000, 30_001) / 100.0;
            case "rating" -> random.nextInt(350, 501) / 100.0;
            case "CPC" -> random.nextInt(25, 801) / 100.0;
            case "ROAS" -> random.nextInt(50, 601) / 100.0;
            default -> throw new IllegalArgumentException("Unsupported sample decimal: " + column.label());
        };
    }
}
