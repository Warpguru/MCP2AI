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
 * Entry point and command dispatcher for the MCP2AI jar.
 *
 * <p>
 * Usage: {@code java -jar MCP2AI-x.y.z.jar <command>}
 *
 * <p>
 * Running without arguments prints the resolved configuration, a live list of available models, and the command reference.
 *
 * <p>
 * Available commands:
 * <ul>
 * <li>{@code config} - print all resolved configuration values (API key masked) and list available models</li>
 * <li>{@code streamableserver} - start the Streamable HTTP MCP server on the configured host and port</li>
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
     * @param args command-line arguments; first element is the subcommand, remaining elements are forwarded to it
     */
    public static void main(final String[] args) {
        // Install the Log4j2 JUL bridge so that java.util.logging calls from embedded Tomcat
        // are routed through Log4j2 instead of printing directly to the console via JUL.
        // Must be called before any JUL logger is created.
        java.util.logging.LogManager.getLogManager().reset();
        org.apache.logging.log4j.jul.Log4jBridgeHandler.install(false, "", true);
        new MCP2AI().process(args);
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    /**
     * Parses the first argument as a subcommand and dispatches to the appropriate handler, or prints usage when no arguments
     * are supplied.
     *
     * @param args command-line arguments passed from {@link #main(String[])}
     */
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
                Config.getInstance().getStreamableHost(), Config.getInstance().getStreamablePort());
        logger.info("=========================================================================");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Logs all resolved configuration values with the API key masked. Includes the four new keys added for system prompt paths
     * and server bind address/port.
     */
    private void runConfig() {
        Config config = Config.getInstance();
        String baseUrl = config.getBaseUrl();
        String model = config.getModel();
        Float temperature = config.getTemperature();
        Integer timeout = config.getTimeout();
        String reviewPromptPath = config.getSystemPromptReviewPath();
        String obfuscatePromptPath = config.getSystemPromptObfuscatePath();
        String obfuscateTemplatePath = config.getSystemPromptObfuscateTemplatePath();
        logger.info("Configuration:");
        logger.info("  Base URL             : {}", Objects.isNull(baseUrl) ? PROPERTY_NOT_SET : baseUrl);
        logger.info("  API Key              : {}", maskApiKey(config.getApiKey()));
        logger.info("  Model                : {}", Objects.isNull(model) ? PROPERTY_NOT_SET : model);
        logger.info("  Temperature          : {}", Objects.isNull(temperature) ? PROPERTY_NOT_SET : temperature);
        logger.info("  Timeout              : {} s", Objects.isNull(timeout) ? PROPERTY_NOT_SET : timeout);
        logger.info("  Review prompt file   : {}", Objects.isNull(reviewPromptPath) ? "(built-in default)" : reviewPromptPath);
        logger.info("  Obfuscate prompt file: {}",
                Objects.isNull(obfuscatePromptPath) ? "(built-in default)" : obfuscatePromptPath);
        logger.info("  Obfuscate style file : {}", Objects.isNull(obfuscateTemplatePath) ? "(not set)" : obfuscateTemplatePath);
        if (obfuscateTemplatePath != null) {
            boolean sufficient = Config.loadAndValidateStyleTemplate(obfuscateTemplatePath) != null;
            if (sufficient) {
                logger.info("  -> Style template: sufficient (>= {} words)", Config.STYLE_TEMPLATE_MIN_WORDS);
            }
            // loadAndValidateStyleTemplate already logs WARN/ERROR for too-short or unreadable files
        }
        logger.info("  Server host          : {}", config.getStreamableHost());
        logger.info("  Server port          : {}", config.getStreamablePort());
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
