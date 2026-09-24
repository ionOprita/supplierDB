package ro.sellfluence.emagdashboard;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.ArrayList;
import java.util.List;

public class MeasurementsListDeserializer extends ValueDeserializer<List<Measurements>> {
    @Override
    public List<Measurements> deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        JsonNode node = context.readTree(parser);
        if (node.isArray()) {
            if (node.isEmpty()) {
                return List.of();
            }
            var measurements = new ArrayList<Measurements>(node.size());
            for (JsonNode element : node) {
                if (!element.isObject()) {
                    throw new IllegalArgumentException("measurements array contains a non-object value");
                }
                measurements.add(context.readTreeAsValue(element, Measurements.class));
            }
            return List.copyOf(measurements);
        }
        if (node.isObject()) {
            return List.of(context.readTreeAsValue(node, Measurements.class));
        }
        if (node.isNull()) {
            return List.of();
        }
        throw new IllegalArgumentException("measurements is neither an object nor an array");
    }
}
