package edu.java.service;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.java.api.Config;
import edu.java.api.OpenAIChat;
import edu.java.util.SchemaBuilder;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

/**
 * Abstract base class representing a generic MCP2AI Server. Holds all shared MCP2AI primitive factory methods, shared
 * constants, Jackson ObjectMapper instance, and base properties such as the serverType and serverName to resolve small
 * parameter differences.
 */
public abstract class Server {

    protected final Logger logger = LogManager.getLogger(getClass());

    /** Shared Jackson object mapper instance. */
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    /** The dynamic server name used for logging and Info resource descriptors. */
    protected final String serverType;

    /** The user-friendly resource display name. */
    protected final String serverName;

    /**
     * Protected constructor initializing base properties.
     *
     * @param serverType descriptor used inside the info resource content (e.g., "StdioServer" or "SseServer")
     * @param serverName user-friendly name used for info resource naming (e.g., "StdioServer Info" or "SseServer Info")
     */
    protected Server(final String serverType, final String serverName) {
        this.serverType = serverType;
        this.serverName = serverName;
    }

    // -------------------------------------------------------------------------
    // Tool factory methods
    // -------------------------------------------------------------------------

    /**
     * MCP2AI Tool - {@code review}.
     *
     * <p>
     * Validation/Review: validates and reviews the user and assistant prompts processed by a LLM with another LLM to provide
     * feedback. Accepts two required and one optional parameter:
     * <ul>
     * <li>{@code user_message} (string, required) - the original user input sent to the AI assistant</li>
     * <li>{@code assistant_message} (string, required) - the AI assistant response to be reviewed</li>
     * <li>{@code temperature} (number, optional) - temperature for the reviewing LLM call; falls back to the server-configured
     * default when absent</li>
     * </ul>
     *
     * <p>
     * Returns a {@link TextContent} whose body is a JSON object with four fields:
     * <ul>
     * <li>{@code verdict} - {@code "PASS"}, {@code "FAIL"}, or {@code "PARTIAL"}</li>
     * <li>{@code confidence} - float 0.0–1.0 representing the reviewer's self-assessed certainty</li>
     * <li>{@code feedback} - explanation of issues or confirmation of correctness</li>
     * <li>{@code model_used} - the model that produced the review (traceability)</li>
     * </ul>
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    protected AsyncToolSpecification createToolReview() {
        //@formatter:off
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("user_message", SchemaBuilder.stringProperty("The original user input sent to the AI assistant"));
        properties.put("assistant_message",SchemaBuilder.stringProperty("The AI assistant response to be reviewed"));
        properties.put("temperature", SchemaBuilder.numberProperty("Temperature for the reviewing LLM call (0.0–1.0); optional, falls back to server default"));
        Tool tool = Tool
                .builder("review", SchemaBuilder.objectSchema(
                        "Parameters",
                        properties,
                        List.of("user_message", "assistant_message")))
                .description("[MCP2AI Primitives:Tool] Validates and reviews the user and assistant prompt by another LLM to provide feedback.")
                .build();
        //@formatter:on
        return new AsyncToolSpecification(tool, (exchange, callToolRequest) -> {
            logger.info("Executing [MCP2AI Primitives:Tool] 'review' with arguments: {}", callToolRequest.arguments());
            String userMsg = (String) callToolRequest.arguments().get("user_message");
            String assistantMsg = (String) callToolRequest.arguments().get("assistant_message");
            // Temperature is optional: MCP client sends a number → deserialized as Double; absent → null
            Double temperature = (Double) callToolRequest.arguments().get("temperature");

            // Invoke review by a LLM
            try {
                Map<String, Object> result = new OpenAIChat().review(userMsg, assistantMsg, Config.getModel(), temperature);
                //@formatter:off
                return Mono.just(CallToolResult.builder()
                        .content(List.of(TextContent.builder(toJson(result)).build()))
                        .isError(false)
                        .build());
                //@formatter:on
            } catch (Exception e) {
                logger.error("Review tool call failed: {}", e.getMessage(), e);
                //@formatter:off
                return Mono.just(CallToolResult.builder()
                        .content(List.of(TextContent.builder(e.getMessage()).build()))
                        .isError(true)
                        .build());
                //@formatter:on
            }
        });
    }

    /**
     * Helper to convert {@link Map} to {@code Json} format.
     */
    protected String toJson(final Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Error converting schema to JSON string", e);
        }
    }

}
