package edu.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.service.StreamableSseServer;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 
 */
public class MCP2AI {

    private static final Logger logger = LogManager.getLogger(MCP2AI.class);

    /** MCP2AI version (keep in sync with pom.xml). */
    public static final String MCP2AI_VERSION = "1.0.0";

    /** MCP streamable-http server. */
    public static final String MCP_JAVA_SDK_STREAMABLE_SERVER = "MCP Java SDK StreamableHttpServer";
    
    public static void main(String[] args) {
        // Check for requested MCP2AI function
        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);

            if ("streamableserver".equalsIgnoreCase(subCommand)) {
                StreamableSseServer.main(subArgs);
                return;
            }
        }

        // Default behavior: Show usage information
        logger.info("=========================================================================");
        logger.info("                             MCP2AI Launcher                             ");
        logger.info("=========================================================================");
        logger.info("");
        McpSchema.Implementation implementation = McpSchema.Implementation
                .builder("MCP2AI", MCP2AI_VERSION).build();
        logger.info("MCP2AI Java SDK Api: Name = {}, Version = {}", implementation.name(), implementation.version());
        logger.info("");
        logger.info("Usage:");
        logger.info("  java -jar target/MCP2AI-{}.jar <subcommand> [args...]", MCP2AI.MCP2AI_VERSION);
        logger.info("");
        logger.info("Available Subcommands:");
        logger.info("  streamableserver");
        logger.info("    -> Launches the stand-alone MCP2AI Server over Streamable HTTP (MCP2AI Spec 2025-03-26).");
        logger.info("       Binds strictly to local loopback interface {}:{} (no arguments).",
                StreamableSseServer.STREAMABLE_HOST, StreamableSseServer.STREAMABLE_PORT);
        logger.info("=========================================================================");
    }

}
