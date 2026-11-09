package ro.sellfluence.apphelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Capture;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Grid;
import ro.sellfluence.apphelper.ProductPerformanceHistoryImporter.Reader;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Replays explicit typed connector captures when the local service-account credential is unavailable.
 * Every requested cell must be covered by captured evidence, including intentionally empty cells.
 */
public final class ProductPerformanceHistoryCaptureReader implements Reader {
    private static final Gson JSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final Pattern RECTANGLE = Pattern.compile("([A-Z]+)([1-9][0-9]*)(?::([A-Z]+)([1-9][0-9]*))?");
    public record Bundle(Map<String,List<Grid>> metadata,List<Capture> captures) { }
    private record Rectangle(int r1,int r2,int c1,int c2) { }
    private final Map<String,List<Grid>> metadata=new HashMap<>();
    private final List<Capture> captures=new ArrayList<>();

    public ProductPerformanceHistoryCaptureReader(List<Path> files) throws IOException {
        for(Path file:files) {
            Bundle bundle=JSON.fromJson(Files.readString(file),Bundle.class);
            if(bundle==null || bundle.metadata()==null || bundle.captures()==null)
                throw new IllegalArgumentException("Capture file requires metadata and captures");
            bundle.metadata().forEach((id,grids)->{
                var previous=metadata.putIfAbsent(id,List.copyOf(grids));
                if(previous!=null && !previous.equals(grids))throw new IllegalArgumentException("Conflicting captured metadata for "+id);
            });
            for(var capture:bundle.captures()) {
                rectangle(capture.range());
                if(!List.of("FORMULA","UNFORMATTED_VALUE").contains(capture.render()) || capture.values()==null)
                    throw new IllegalArgumentException("Capture must contain typed values and an explicit render mode");
                captures.add(capture);
            }
        }
    }

    @Override public List<Grid> metadata(String spreadsheetId) {
        var grids=metadata.get(spreadsheetId);
        if(grids==null)throw new IllegalArgumentException("Spreadsheet metadata absent from capture: "+spreadsheetId);
        return grids;
    }

    @Override public List<List<Object>> read(String spreadsheetId,String sheet,int firstRow,int lastRow,
                                             int firstColumn,int lastColumn,boolean formulas) {
        int rows=lastRow-firstRow+1,cols=lastColumn-firstColumn+1;
        if(rows<=0 || cols<=0 || (long)rows*cols>50_000)throw new IllegalArgumentException("Invalid replay rectangle");
        var values=new ArrayList<List<Object>>();
        for(int r=0;r<rows;r++)values.add(new ArrayList<>(Collections.nCopies(cols,null)));
        var covered=new boolean[rows][cols];
        String render=formulas?"FORMULA":"UNFORMATTED_VALUE";
        for(var capture:captures) {
            if(!capture.spreadsheetId().equals(spreadsheetId) || !capture.sheet().equals(sheet) || !capture.render().equals(render))continue;
            var b=rectangle(capture.range());
            for(int r=Math.max(firstRow,b.r1());r<=Math.min(lastRow,b.r2());r++) {
                for(int c=Math.max(firstColumn,b.c1());c<=Math.min(lastColumn,b.c2());c++) {
                    int rr=r-firstRow,cc=c-firstColumn,cr=r-b.r1(),ccol=c-b.c1();
                    Object value=cr<capture.values().size() && ccol<capture.values().get(cr).size()
                            ? capture.values().get(cr).get(ccol):null;
                    if(covered[rr][cc] && !same(values.get(rr).get(cc),value))
                        throw new IllegalArgumentException("Conflicting source captures at "+sheet+"!"+ProductPerformanceHistoryImporter.column(c)+r);
                    covered[rr][cc]=true;values.get(rr).set(cc,value);
                }
            }
        }
        for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)if(!covered[r][c])
            throw new IllegalArgumentException("Missing captured cell "+sheet+"!"+ProductPerformanceHistoryImporter.column(firstColumn+c)+(firstRow+r));
        return values;
    }
    private static boolean same(Object a,Object b) {
        if(a instanceof Number && b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;
        if(a==null && "".equals(b) || b==null && "".equals(a))return true;
        return Objects.equals(a,b);
    }
    private static Rectangle rectangle(String a1) {
        var matcher=RECTANGLE.matcher(Objects.toString(a1,""));
        if(!matcher.matches())throw new IllegalArgumentException("Capture range must be a bounded cell-only A1 rectangle: "+a1);
        int r1=Integer.parseInt(matcher.group(2)),r2=matcher.group(4)==null?r1:Integer.parseInt(matcher.group(4));
        int c1=column(matcher.group(1)),c2=matcher.group(3)==null?c1:column(matcher.group(3));
        if(r2<r1 || c2<c1 || (long)(r2-r1+1)*(c2-c1+1)>50_000)throw new IllegalArgumentException("Capture rectangle exceeds bounded-read limit");
        return new Rectangle(r1,r2,c1,c2);
    }
    private static int column(String name) {int n=0;for(char c:name.toCharArray())n=n*26+c-'A'+1;return n;}
}
