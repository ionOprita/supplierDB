package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.EmagMirrorDB.VendorGroupCategory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorOptionsTest {
    @Test
    void combinesVendorsWithinEachGroupWithoutMixingTheirCategories() {
        var options = ProfitCalculatorOptions.create(List.of(
                new VendorGroupCategory("S", "Trimmers"),
                new VendorGroupCategory("J", "Baby gyms"),
                new VendorGroupCategory("S", "Epilators"),
                new VendorGroupCategory("S", "Trimmers"),
                new VendorGroupCategory("K", null),
                new VendorGroupCategory("Z", "Batteries"),
                new VendorGroupCategory("J", "Trimmers")
        ));

        assertEquals(List.of("J", "K", "S", "Z"), options.vendorGroups().stream()
                .map(ProfitCalculatorOptions.VendorGroupOption::vendorGroup).toList());
        assertEquals(List.of("Baby gyms", "Trimmers"), options.vendorGroups().getFirst().categories());
        assertTrue(options.vendorGroups().get(1).categories().isEmpty());
        assertEquals(List.of("Epilators", "Trimmers"), options.vendorGroups().get(2).categories());
        assertEquals(List.of("Batteries"), options.vendorGroups().getLast().categories());
        assertEquals("S", options.defaultVendorGroup());
    }

    @Test
    void excludesBlankValuesAndUsesTheFirstAvailableGroupWithoutS() {
        var options = ProfitCalculatorOptions.create(List.of(
                new VendorGroupCategory(null, "Orphan"),
                new VendorGroupCategory(" \t", "Orphan"),
                new VendorGroupCategory("K", " \t"),
                new VendorGroupCategory(" J ", " Trimmers "),
                new VendorGroupCategory("J", "Trimmers")
        ));

        assertEquals("J", options.defaultVendorGroup());
        assertEquals(List.of("J", "K"), options.vendorGroups().stream()
                .map(ProfitCalculatorOptions.VendorGroupOption::vendorGroup).toList());
        assertEquals(List.of("Trimmers"), options.vendorGroups().getFirst().categories());
        assertTrue(options.vendorGroups().getLast().categories().isEmpty());
    }

    @Test
    void handlesAnEmptyDatabaseWithoutInventingOptions() {
        var options = ProfitCalculatorOptions.create(List.of());
        assertTrue(options.vendorGroups().isEmpty());
        assertNull(options.defaultVendorGroup());
    }
}
