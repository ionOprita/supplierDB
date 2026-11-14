package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.Overrides;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Strict request parsing shared by preview and persisted override changes. */
public final class ProfitCalculatorOverrides {
    private ProfitCalculatorOverrides() {
    }

    public record Request(String vendorGroup, String category, List<OverrideChange> changes) {
    }

    public static Request parse(ObjectMapper mapper, String body) {
        if (body == null || body.length() > 1_000_000) {
            throw new IllegalArgumentException("Invalid override changes payload.");
        }
        final JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid override changes payload.");
        }
        requireObject(root, Set.of("vendorGroup", "category", "changes"));
        var vendorGroup = requiredText(root.get("vendorGroup"), "vendorGroup").strip();
        var category = requiredText(root.get("category"), "category").strip();
        var sourceChanges = root.get("changes");
        if (sourceChanges == null || !sourceChanges.isArray() || sourceChanges.size() > 500) {
            throw new IllegalArgumentException("Provide at most 500 product changes.");
        }
        var changes = new ArrayList<OverrideChange>();
        var products = new HashSet<String>();
        for (var source : sourceChanges) {
            requireObject(source, Set.of("productCode", "expectedRevision", "values"));
            var productCode = requiredText(source.get("productCode"), "productCode");
            if (!products.add(productCode)) throw new IllegalArgumentException("Duplicate product changes.");
            var revision = source.get("expectedRevision");
            if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0) {
                throw new IllegalArgumentException("A nonnegative expectedRevision is required.");
            }
            var sourceValues = source.get("values");
            if (sourceValues == null || !sourceValues.isObject() || sourceValues.size() == 0) {
                throw new IllegalArgumentException("Provide changed override fields.");
            }
            var values = new LinkedHashMap<String, String>();
            for (var entry : sourceValues.properties()) {
                if (!entry.getValue().isNull() && !entry.getValue().isTextual()) {
                    throw new IllegalArgumentException("Override values must be decimal strings or null.");
                }
                values.put(entry.getKey(), entry.getValue().isNull() ? null : entry.getValue().asString());
            }
            Overrides.empty().applyChanges(values);
            changes.add(new OverrideChange(productCode, revision.asLong(),
                    Collections.unmodifiableMap(values)));
        }
        return new Request(vendorGroup, category, List.copyOf(changes));
    }

    private static String requiredText(JsonNode node, String name) {
        if (node == null || !node.isTextual() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid or missing " + name + ".");
        }
        return node.asString();
    }

    private static void requireObject(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !fields.containsAll(node.propertyNames())) {
            throw new IllegalArgumentException("Invalid override changes payload.");
        }
    }
}
