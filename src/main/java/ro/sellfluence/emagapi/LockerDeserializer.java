package ro.sellfluence.emagapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class LockerDeserializer extends JsonDeserializer<Locker> {
    @Override
    public Locker deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        JsonNode node = jsonParser.getCodec().readTree(jsonParser);
        // Check if the node is an array
        if (node.isArray()) {
            // Handle the case where it is an empty array
            if (node.isEmpty()) {
                return null;
            } else {
                throw new RuntimeException("locker found with a non zero-length array");
            }
        } else if (node.isObject()) {
            // Handle the case where it is a single object
            return jsonParser.getCodec().treeToValue(node, Locker.class);
        }
        // Handle unexpected cases (like null or other types)
        throw new RuntimeException("locker found, which is neither an array, not an object.");
    }
}
