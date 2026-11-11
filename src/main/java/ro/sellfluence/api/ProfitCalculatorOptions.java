package ro.sellfluence.api;

import ro.sellfluence.db.EmagMirrorDB.VendorGroupCategory;

import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Database-backed vendor groups and their application product categories. */
public final class ProfitCalculatorOptions {
    private ProfitCalculatorOptions() {
    }

    public record VendorGroupOption(String vendorGroup, List<String> categories) {
    }

    public record Response(List<VendorGroupOption> vendorGroups, String defaultVendorGroup) {
    }

    static Response create(List<VendorGroupCategory> rows) {
        var categoriesByGroup = new TreeMap<String, TreeSet<String>>();
        for (var row : rows) {
            if (row.vendorGroup() == null || row.vendorGroup().isBlank()) continue;
            var categories = categoriesByGroup.computeIfAbsent(row.vendorGroup().strip(), ignored -> new TreeSet<>());
            if (row.category() != null && !row.category().isBlank()) categories.add(row.category().strip());
        }
        var groups = categoriesByGroup.entrySet().stream()
                .map(entry -> new VendorGroupOption(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        var defaultGroup = categoriesByGroup.containsKey("S") ? "S"
                : groups.isEmpty() ? null : groups.getFirst().vendorGroup();
        return new Response(groups, defaultGroup);
    }
}
