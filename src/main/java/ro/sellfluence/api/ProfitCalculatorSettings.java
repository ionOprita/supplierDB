package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorSettingsTable.Change;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.Data;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.DecimalSection;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.ItemSection;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.NamedCost;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.Section;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Date Calcul inputs and read-only calculations for one vendor group. */
public final class ProfitCalculatorSettings {
    private static final BigDecimal NEGATIVE_ONE = BigDecimal.ONE.negate();

    private ProfitCalculatorSettings() {
    }

    public record Response(String vendorGroup, DecimalSection general, DecimalSection generalCosts, ItemSection employees,
                           ItemSection miscellaneous, Calculated calculated) {
    }

    public record Calculated(String previousMonth, Long previousMonthUnits, String employeeTotalRon,
                             String miscellaneousTotalRon, String accountingCostPerUnitRon, String employeeCostPerUnitRon,
                             String miscellaneousCostPerUnitRon, String interestCostPerUnitRon) {
    }

    public record Request(Change change) {
    }

    static Response create(String vendorGroup, YearMonth previousMonth, Data data) {
        var costs = data.operatingCosts();
        var calculated = new Calculated(previousMonth.toString(), data.previousMonthUnits(), costs.employeeTotalRon().toPlainString(),
                costs.miscellaneousTotalRon().toPlainString(), decimalString(costs.accountingCostPerUnitRon()),
                decimalString(costs.employeeCostPerUnitRon()), decimalString(costs.miscellaneousCostPerUnitRon()), null);
        return new Response(vendorGroup, data.general(), data.generalCosts(), data.employees(), data.miscellaneous(), calculated);
    }

    static String requireGroup(String group) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("A vendor group is required.");
        return group.strip();
    }

    private static String decimalString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    public static Request parse(ObjectMapper mapper, String body) {
        if (body == null || body.length() > 1_000_000) throw invalidPayload();
        final JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (RuntimeException error) {
            throw invalidPayload();
        }
        if (root == null || !root.isObject()) throw invalidPayload();
        var vendorGroup = requireGroup(requiredText(root.get("vendorGroup"), "vendorGroup"));
        var section = Section.fromId(requiredText(root.get("section"), "section"));
        var revision = root.get("expectedRevision");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0) {
            throw new IllegalArgumentException("A nonnegative expectedRevision is required.");
        }
        var payloadField = section.hasItems() ? "items" : "values";
        requireObject(root, Set.of("vendorGroup", "section", "expectedRevision", payloadField));
        return new Request(section.hasItems()
                ? new Change(vendorGroup, section, revision.asLong(), Map.of(), parseItems(root.get(payloadField)))
                : new Change(vendorGroup, section, revision.asLong(), parseValues(section, root.get(payloadField)), List.of()));
    }

    private static Map<String, BigDecimal> parseValues(Section section, JsonNode input) {
        requireObject(input, section.fields().stream().map(field -> field.name()).collect(Collectors.toSet()));
        var result = new LinkedHashMap<String, BigDecimal>();
        for (var field : section.fields()) {
            var name = field.name();
            result.put(name, validateDecimal(name, input.get(name)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<NamedCost> parseItems(JsonNode input) {
        if (input == null || !input.isArray()) throw invalidPayload();
        var items = new ArrayList<NamedCost>();
        for (var row : input) {
            requireObject(row, Set.of("label", "amountRon"));
            var label = requiredText(row.get("label"), "label").strip();
            var amount = validateDecimal("amountRon", row.get("amountRon"));
            items.add(new NamedCost(label, amount == null ? null : amount.toPlainString()));
        }
        return List.copyOf(items);
    }

    private static BigDecimal validateDecimal(String field, JsonNode input) {
        if (input.isNull()) return null;
        if (!input.isTextual()) throw new IllegalArgumentException(field + " must be a decimal string or null.");
        var value = input.asString().strip();
        if (value.isEmpty()) return null;
        if (value.length() > 64 || !value.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw new IllegalArgumentException("Invalid decimal value for " + field + ".");
        }
        var number = new BigDecimal(value);
        if (number.toPlainString().length() > 64) {
            throw new IllegalArgumentException("Invalid decimal value for " + field + ".");
        }
        var outOfRange = switch (field) {
            case "geniusEasyboxRate", "vatRate", "incomeTaxRate", "profitTaxRate", "dividendTaxRate" ->
                    number.signum() < 0 || number.compareTo(BigDecimal.ONE) > 0;
            case "monthlyCapitalRate", "monthlyInflationRate" -> number.compareTo(NEGATIVE_ONE) <= 0;
            case "averageDaysPerMonth" -> number.signum() <= 0;
            default -> number.signum() < 0;
        };
        if (outOfRange) throw new IllegalArgumentException("Value outside the allowed range for " + field + ".");
        return number;
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid or missing " + field + ".");
        }
        return node.asString();
    }

    private static void requireObject(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size() || !fields.containsAll(node.propertyNames())) {
            throw invalidPayload();
        }
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException("Invalid Date Calcul settings payload.");
    }
}
