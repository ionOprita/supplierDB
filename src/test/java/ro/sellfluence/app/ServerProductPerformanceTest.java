package ro.sellfluence.app;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerProductPerformanceTest {
    @Test
    void acceptsCanonicalVendorIdsAndPreservesTheExactProductCode() {
        var vendorId = UUID.randomUUID();
        var code = " product/code with spaces? ";

        assertEquals(new Server.ProductPerformanceRequest(vendorId, code),
                Server.parseProductPerformanceRequest(vendorId.toString().toUpperCase(), code));
    }

    @Test
    void rejectsMissingOrMalformedVendorAndProductParameters() {
        for (var vendorId : new String[]{null, "", "sellfusion", "1-1-1-1-1", "not-a-uuid"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProductPerformanceRequest(vendorId, "product"));
        }
        var validVendorId = UUID.randomUUID().toString();
        for (var productCode : new String[]{null, "", " \t\r\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProductPerformanceRequest(validVendorId, productCode));
        }
    }
}
