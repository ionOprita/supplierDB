package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorFbeTariffTable;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Change;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Exact editable values for the shared FBE tariff grid. */
public final class ProfitCalculatorFbeTariffs {
    private ProfitCalculatorFbeTariffs() {
    }

    public record Response(List<Tariff> tariffs) {
        public Response {
            tariffs = List.copyOf(tariffs);
        }
    }

    public record Request(List<Change> changes) {
        public Request {
            changes = List.copyOf(changes);
        }
    }

    public static Request parse(ObjectMapper mapper, String body) {
        if (body == null || body.length() > 1_000_000) {
            throw new IllegalArgumentException("Invalid FBE tariff changes payload.");
        }
        final JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid FBE tariff changes payload.");
        }
        requireObject(root, Set.of("changes"));
        var sourceChanges = root.get("changes");
        if (sourceChanges == null || !sourceChanges.isArray() || sourceChanges.size() > 12) {
            throw new IllegalArgumentException("Provide at most 12 tariff changes.");
        }
        var changes = new ArrayList<Change>();
        for (var source : sourceChanges) {
            requireObject(source, Set.of("id", "expectedRevision", "values"));
            var id = source.get("id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() < 1 || id.asInt() > 12) {
                throw new IllegalArgumentException("A tariff ID between 1 and 12 is required.");
            }
            var revision = source.get("expectedRevision");
            if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                    || revision.asLong() < 1) {
                throw new IllegalArgumentException("A positive expectedRevision is required.");
            }
            var sourceValues = source.get("values");
            if (sourceValues == null || !sourceValues.isObject() || sourceValues.size() == 0) {
                throw new IllegalArgumentException("Provide changed tariff fields.");
            }
            var values = new LinkedHashMap<String, String>();
            for (var entry : sourceValues.properties()) {
                var value = entry.getValue();
                if (!value.isNull() && !value.isTextual()) {
                    throw new IllegalArgumentException("Tariff values must be decimal strings, text, or null.");
                }
                values.put(entry.getKey(), value.isNull() ? null : value.asString());
            }
            changes.add(new Change(id.asInt(), revision.asLong(), Collections.unmodifiableMap(values)));
        }
        ProfitCalculatorFbeTariffTable.validateChanges(changes);
        return new Request(changes);
    }

    private static void requireObject(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || !fields.containsAll(value.propertyNames())) {
            throw new IllegalArgumentException("Invalid FBE tariff changes payload.");
        }
    }
}
