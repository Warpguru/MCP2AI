package edu.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.openai.client.OpenAIClient;

import edu.java.api.ClientFactory;
import edu.java.api.Config;
import edu.java.service.StreamableSseServer;
import edu.java.util.ModelsDiscovery;
import edu.java.util.ModelsDiscovery.ModelInfo;

/**
 * Entry point and command dispatcher for the JavaOpenAI tutorial jar.
 *
 * <p>
 * Usage: {@code java -jar JavaOpenAI-x.y.z.jar <command>}
 *
 * <p>
 * Running without arguments prints the configured endpoint, a live list of available models with inferred capability tags, and
 * the command reference.
 *
 * <p>
 * Available commands:
 * <ul>
 * <li>{@code config} - print resolved configuration (API key masked)</li>
 * <li>{@code streamableserver} - start streamable SSE MCP server</li>
 * </ul>
 */
public class MCP2AI {

    private static final Logger logger = LogManager.getLogger(MCP2AI.class);

    /** MCP2AI version (keep in sync with pom.xml). */
    public static final String MCP2AI_VERSION = "1.0.0";

    /** MCP streamable-http server. */
    public static final String MCP_JAVA_SDK_STREAMABLE_SERVER = "MCP Java SDK StreamableHttpServer";

    private static final String PROPERTY_NOT_SET = "(not set)";

    /**
     * Main entry point.
     * 
     * @param args
     */
    public static void main(final String[] args) {
        new MCP2AI().process(args);
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    private void process(final String[] args) {
        logger.info("=========================================================================");
        logger.info("                             MCP2AI Launcher                             ");
        logger.info("=========================================================================");
        logger.info("");
        // Check for requested MCP2AI function
        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
            if ("config".equalsIgnoreCase(subCommand)) {
                runConfig();
                runModelList();
                return;
            } else if ("streamableserver".equalsIgnoreCase(subCommand)) {
                StreamableSseServer.main(subArgs);
                return;
            }
        }

        // Default behavior: show configuration, available models, and usage
        runConfig();
        runModelList();
        logger.info("");
        logger.info("Usage:");
        logger.info("  java -jar target/{}-{}.jar <subcommand> [args...]", this.getClass().getSimpleName(),
                MCP2AI.MCP2AI_VERSION);
        logger.info("");
        logger.info("Available Subcommands:");
        logger.info("  config");
        logger.info("    -> Print resolved configuration (API key masked)");
        logger.info("  streamableserver");
        logger.info("    -> Launches the stand-alone MCP2AI Server over Streamable HTTP (MCP Spec 2025-03-26).");
        logger.info("       Binds strictly to local loopback interface {}:{} (no arguments).",
                StreamableSseServer.STREAMABLE_HOST, StreamableSseServer.STREAMABLE_PORT);
        logger.info("=========================================================================");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void runConfig() {
        String baseUrl = Config.getBaseUrl();
        String model = Config.getModel();
        Float temperature = Config.getTemperature();
        Integer timeout = Config.getTimeout();
        logger.info("Configuration:");
        logger.info("  Base URL    : {}", Objects.isNull(baseUrl) ? PROPERTY_NOT_SET : baseUrl);
        logger.info("  API Key     : {}", maskApiKey(Config.getApiKey()));
        logger.info("  Model       : {}", Objects.isNull(model) ? PROPERTY_NOT_SET : model);
        logger.info("  Temperature : {}", Objects.isNull(temperature) ? PROPERTY_NOT_SET : temperature);
        logger.info("  Timeout     : {} s", Objects.isNull(timeout) ? PROPERTY_NOT_SET : timeout);
    }

    /**
     * Queries the configured endpoint for available models and logs each one with its inferred capability tags. Failures are
     * non-fatal - a warning is logged and execution continues.
     */
    private void runModelList() {
        logger.info("Available models:");
        List<ModelInfo> models = new ArrayList<>();
        OpenAIClient client = ClientFactory.create();
        if (client != null) {
            models = new ModelsDiscovery().listModels(client);
            client.close();
        }
        if (models.isEmpty()) {
            logger.warn("    -> Endpoint may be undefined, unreachable or does not support model listing");
        } else {
            for (ModelInfo modelInfo : models) {
                logger.info("  {}", modelInfo.id());
            }
        }
    }

    /**
     * Masks an API key for safe display: shows the first 5 characters followed by {@code ****}, or just {@code ****} if the key
     * is shorter than 5 characters or not set.
     *
     * @param key the raw API key, may be {@code null} or empty
     * @return the masked key string, never {@code null}
     */
    private String maskApiKey(String key) {
        if (key == null || key.isEmpty()) {
            return PROPERTY_NOT_SET;
        }
        if (key.length() <= 5) {
            return "****";
        }
        return key.substring(0, 5) + "****";
    }

}
