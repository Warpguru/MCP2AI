package edu.java.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal helper to build JSON Schema maps for tool input schemas. The SDK serializes these directly into the protocol message.
 */
public final class SchemaBuilder {

    private SchemaBuilder() {
    }

    // -------------------------------------------------------------------------
    // Property entry factories
    // -------------------------------------------------------------------------

    /**
     * Build a JSON Schema property entry with {@code "type": "string"}.
     *
     * @param description human-readable description of the property
     * @return {@link Map} representing the property schema entry
     */
    public static Map<String, Object> stringProperty(final String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * Build a JSON Schema property entry with {@code "type": "number"}. Suitable for float/double parameters such as
     * {@code temperature}.
     *
     * @param description human-readable description of the property
     * @return {@link Map} representing the property schema entry
     */
    public static Map<String, Object> numberProperty(final String description) {
        return Map.of("type", "number", "description", description);
    }

    // -------------------------------------------------------------------------
    // Schema builders
    // -------------------------------------------------------------------------

    /**
     * Create an object schema from pre-built property entries. Each value in {@code properties} must be a valid JSON Schema
     * property map (e.g. produced by {@link #stringProp} or {@link #numberProp}). Only the names listed in
     * {@code requiredNames} are marked required; any name present in {@code properties} but absent from {@code requiredNames}
     * is treated as optional.
     *
     * @param description   human-readable description of the parameter object
     * @param properties    {@link Map}&lt;parameterName, propertySchemaEntry&gt;
     * @param requiredNames names of the required parameters (subset of {@code properties} keys)
     * @return JSON Schema {@link Map} ready for {@link io.modelcontextprotocol.spec.McpSchema.Tool.Builder}
     */
    public static Map<String, Object> objectSchema(final String description, final Map<String, Object> properties,
            final List<String> requiredNames) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("properties", properties);
        schema.put("required", requiredNames);
        return schema;
    }

    /**
     * Convenience: schema with a single required string property.
     *
     * @param parameterName        name of the parameter
     * @param parameterDescription human-readable description of the parameter
     * @return JSON Schema {@link Map} ready for {@link io.modelcontextprotocol.spec.McpSchema.Tool.Builder}
     */
    public static Map<String, Object> singleStringParameter(final String parameterName, final String parameterDescription) {
        return objectSchema("Parameters", Map.of(parameterName, stringProperty(parameterDescription)), List.of(parameterName));
    }

}
