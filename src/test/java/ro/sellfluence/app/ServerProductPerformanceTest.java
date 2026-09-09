package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import ro.sellfluence.api.ProductPerformanceData.Period;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerProductPerformanceTest {
    @Test
    void acceptsCanonicalVendorIdsAndPreservesTheExactProductCode() {
        var vendorId = UUID.randomUUID();
        var code = " product/code with spaces? ";

        assertEquals(new Server.ProductPerformanceRequest(vendorId, code, Period.WEEK),
                Server.parseProductPerformanceRequest(vendorId.toString().toUpperCase(), code, null));
        assertEquals(Period.WEEK, Server.parseProductPerformanceRequest(vendorId.toString(), code, "week").period());
        assertEquals(Period.MONTH, Server.parseProductPerformanceRequest(vendorId.toString(), code, "month").period());
    }

    @Test
    void rejectsMissingOrMalformedVendorAndProductParameters() {
        for (var vendorId : new String[]{null, "", "sellfusion", "1-1-1-1-1", "not-a-uuid"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProductPerformanceRequest(vendorId, "product", null));
        }
        var validVendorId = UUID.randomUUID().toString();
        for (var productCode : new String[]{null, "", " \t\r\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProductPerformanceRequest(validVendorId, productCode, null));
        }
    }

    @Test
    void rejectsUnsupportedPeriods() {
        var vendorId = UUID.randomUUID().toString();
        for (var period : new String[]{"", "day", "Week", " month ", "year"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProductPerformanceRequest(vendorId, "product", period));
        }
    }
}
