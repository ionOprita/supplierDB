package ro.sellfluence.googleapi;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.sheets.v4.Sheets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SheetsAPIHistoryReadTest {
    @Test void boundedTypedReadRetainsZeroDatesBlanksAndDoesNotWrite() {
        var urls=new ArrayList<String>();
        var transport=new MockHttpTransport(){
            @Override public LowLevelHttpRequest buildRequest(String method,String url){
                assertEquals("GET",method);urls.add(URLDecoder.decode(url,StandardCharsets.UTF_8));
                return new MockLowLevelHttpRequest(url){
                    @Override public LowLevelHttpResponse execute() throws IOException {
                        return new MockLowLevelHttpResponse().setContentType("application/json")
                                .setContent("{\"values\":[[0,46143,\"\",199.99],[\"#REF!\"]]}");
                    }
                };
            }
        };
        var service=new Sheets.Builder(transport,GsonFactory.getDefaultInstance(),null).setApplicationName("test").build();
        var api=new SheetsAPI("test","id","source",service,_ -> {});
        var values=api.getBoundedValues("Seller's data",4,5,2,5,false);
        assertEquals(0,((Number)values.getFirst().getFirst()).intValue());
        assertEquals(46143,((Number)values.getFirst().get(1)).intValue());
        assertEquals("",values.getFirst().get(2));
        assertEquals(1,values.get(1).size(),"Trailing blanks must not become invented zeroes");
        assertTrue(urls.getFirst().contains("'Seller''s data'!B4:E5"));
        assertTrue(urls.getFirst().contains("valueRenderOption=UNFORMATTED_VALUE"));
        assertTrue(urls.getFirst().contains("dateTimeRenderOption=SERIAL_NUMBER"));
        api.getBoundedValues("Seller's data",1,1,1,1,true);
        assertTrue(urls.getLast().contains("valueRenderOption=FORMULA"));
        assertThrows(IllegalArgumentException.class,()->api.getBoundedValues("x",1,1000,1,100,false));
        assertEquals(2,urls.size());
    }
}
