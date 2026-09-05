package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Vendor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class APIAdsVendorsTest {
    @Test
    void defaultsToSellfusionByAccountAcrossEnvironmentIds() {
        for (int environment = 0; environment < 2; environment++) {
            var sellfusion = vendor("Z company", "sellfusion");
            var other = vendor("A company", "other");
            var options = API.adsVendorOptions(List.of(sellfusion, other));

            assertEquals(sellfusion.id(), options.defaultVendorId());
            assertEquals(List.of(other.id(), sellfusion.id()),
                    options.vendors().stream().map(API.AdsVendor::vendorId).toList());
        }
    }

    @Test
    void fallsBackToFirstSortedVendorAndHandlesNoAdsVendors() {
        var first = vendor("A company", "first");
        var second = vendor("B company", "second");
        assertEquals(first.id(), API.adsVendorOptions(List.of(second, first)).defaultVendorId());
        var empty = API.adsVendorOptions(List.of());
        assertTrue(empty.vendors().isEmpty());
        assertNull(empty.defaultVendorId());
    }

    private static Vendor vendor(String name, String account) {
        return new Vendor(UUID.randomUUID(), name, false, name, account, null);
    }
}
