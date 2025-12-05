package ro.sellfluence.emagapi;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class LockerDeserializer extends ValueDeserializer<Locker> {

    @Override
    public Locker deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) {
        JsonNode node = deserializationContext.readTree(jsonParser);
        // Check if the node is an array
        if (node.isArray()) {
            // Handle the case where it is an empty array
            if (node.isEmpty()) {
                return null;
            } else {
                throw new RuntimeException("locker found with a non-zero-length array");
            }
        } else if (node.isObject()) {
            // Handle the case where it is a single object
            return jsonParser.objectReadContext().readValue(jsonParser, Locker.class);
        }
        // Handle unexpected cases (like null or other types)
        throw new RuntimeException("locker found, which is neither an array nor an object.");
    }
}
