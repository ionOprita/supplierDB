package ro.sellfluence.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/** Current Date Calcul input shared across all vendor groups. */
public final class ProfitCalculatorSharedSettings {
    private ProfitCalculatorSharedSettings() {
    }

    public record Response(long revision, String turnoverTaxVatRate) {
    }

    public record Request(long expectedRevision, BigDecimal turnoverTaxVatRate) {
    }

    public static Request parse(ObjectMapper mapper, String body) {
        if (body == null || body.length() > 1_000_000) throw invalidPayload();
        final JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (RuntimeException error) {
            throw invalidPayload();
        }
        if (root == null || !root.isObject() || root.size() != 2) throw invalidPayload();
        var revision = root.get("expectedRevision");
        var rate = root.get("turnoverTaxVatRate");
        if (revision == null || rate == null) throw invalidPayload();
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() <= 0) {
            throw new IllegalArgumentException("A positive expectedRevision is required.");
        }
        return new Request(revision.asLong(), parseVatRate(rate));
    }

    private static BigDecimal parseVatRate(JsonNode rate) {
        if (rate.isNull()) return null;
        if (!rate.isTextual()) throw invalidVatRate();
        var value = rate.asString().strip();
        if (value.length() > 64 || !value.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw invalidVatRate();
        }
        var decimal = new BigDecimal(value);
        if (decimal.compareTo(BigDecimal.ONE) > 0 || decimal.toPlainString().length() > 64) {
            throw invalidVatRate();
        }
        return decimal;
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException("Invalid shared Date Calcul settings payload.");
    }

    private static IllegalArgumentException invalidVatRate() {
        return new IllegalArgumentException("turnoverTaxVatRate must be a decimal string from 0 to 1 or null.");
    }
}
