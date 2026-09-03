package edu.java.service;

import java.util.List;
import java.util.function.BiFunction;

import org.apache.catalina.Context;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;

import edu.java.MCP2AI;
import edu.java.api.Config;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;

/**
 * MCP2AI Server implementation over HTTP Streamable transport (MCP2AI Spec 2025-03-26) using an embedded Tomcat servlet
 * container. Inherits all core MCP2AI primitive factory methods from {@link Server}.
 *
 * <p>
 * Streamable HTTP is the modern replacement for the legacy SSE transport. It condenses both the SSE notification channel and
 * the JSON-RPC command channel into a single {@code /mcp} endpoint that supports GET (open an SSE notification stream) and POST
 * (send a JSON-RPC message, receive an optional SSE stream or direct JSON response in the same HTTP response).
 */
public class StreamableSseServer extends Server {

    /** Unified MCP2AI Streamable HTTP endpoint path (handles both GET and POST). */
    public static final String STREAMABLE_ENDPOINT = "/mcp";

    /**
     * Constructor initializing the StreamableSseServer base details.
     */
    public StreamableSseServer() {
        super("StreamableSseServer", "MCP2AI Streamable HTTP Server Info");
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Application entry point.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        new StreamableSseServer().processTransportStreamable();
    }

    // -------------------------------------------------------------------------
    // Transport entry points
    // -------------------------------------------------------------------------

    /**
     * MCP2AI Transport - Streamable HTTP (MCP2AI Spec 2025-03-26).
     *
     * <p>
     * Launches the MCP2AI Server over the Streamable HTTP transport. Unlike the legacy SSE transport which required two
     * separate endpoints ({@code /sse} and {@code /message}), this transport exposes a single unified endpoint
     * {@link #STREAMABLE_ENDPOINT} that handles both GET requests (open a persistent SSE notification stream) and POST requests
     * (send a JSON-RPC message and receive a response inline or via SSE).
     *
     * <p>
     * The server binds strictly to the loopback interface resolved from {@code MCP_STREAMABLE_HOST} and
     * {@code MCP_STREAMABLE_PORT} (defaults: {@code 127.0.0.1:8081}). Delegates all primitive registration, endpoint routing,
     * and server startup to {@link #buildServer()}. Any fatal startup exception is logged cleanly and the process shuts down
     * with exit code {@code 1}.
     */
    public void processTransportStreamable() {
        logger.info("Starting {} over streamable-http transport...", MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER);
        try {
            buildServer();
        } catch (Exception e) {
            logger.error("Fatal error starting {}: {}", MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER, e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Transport-agnostic server builder
    // -------------------------------------------------------------------------

    /**
     * Registers all MCP2AI primitives and starts the Streamable HTTP server.
     *
     * <p>
     * Initializes the {@link HttpServletStreamableServerTransportProvider} (MCP SDK 2.x built-in transport), registers
     * server-side capabilities and tool specifications, and spins up an embedded Tomcat instance listening strictly on the host
     * and port resolved from {@link Config} ({@code MCP_STREAMABLE_HOST} / {@code MCP_STREAMABLE_PORT}).
     *
     * <p>
     * Blocks the main thread indefinitely via {@code tomcat.getServer().await()} to let the Tomcat connector threads handle
     * incoming MCP requests.
     *
     * @throws Exception if the embedded Tomcat fails to start
     */
    @SuppressWarnings("deprecation") // addServletMappingDecoded deprecated in Tomcat 11.0.25 API; no replacement in embedded
                                     // mode
    private void buildServer() throws Exception {
        // Resolve host and port from configuration (falls back to 127.0.0.1:8081 if not set).
        String host = Config.getInstance().getStreamableHost();
        int port = Config.getInstance().getStreamablePort();
        String serverAddress = "http://" + host + ":" + port;

        // 1. Initialize the MCP SDK 2.x Streamable HTTP Transport Provider.
        // HttpServletStreamableServerTransportProvider IS a jakarta.servlet.http.HttpServlet.
        //@formatter:off
        HttpServletStreamableServerTransportProvider transportProvider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(McpJsonDefaults.getMapper())
                        .mcpEndpoint(STREAMABLE_ENDPOINT)
                        .build();
        //@formatter:on

        // 2. Build Server Capabilities (Tools, Resources, Prompts)
        //@formatter:off
        ServerCapabilities capabilities = ServerCapabilities
                .builder()
                .tools(true)
                .resources(false, true)
                .prompts(true)
                .build();
        //@formatter:on

        // 3. Define and configure all specifications
        AsyncToolSpecification toolReview = createToolReview();
        AsyncToolSpecification toolObfuscate = createToolObfuscate();

        // 4. Build and configure the McpAsyncServer
        //@formatter:off
        @SuppressWarnings("unused")
        McpAsyncServer server = McpServer
                .async(transportProvider)
                .serverInfo(McpSchema.Implementation.builder(MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER, MCP2AI.MCP2AI_VERSION)
                        .title("MCP2AI Java SDK - Streamable HTTP Reference Server")
                        .description("MCP2AI Java SDK reference implementation over Streamable HTTP transport (MCP2AI Spec 2025-03-26). "
                                + "Exposes review and obfuscate tools to validate, review, and humanise AI assistant responses.")
                        .build())
                .capabilities(capabilities)
                .tools(toolReview, toolObfuscate)
                // Suppress SDK WARN "no consumers provided" - roots notifications are not used by this server
                .rootsChangeHandlers(List.<BiFunction<McpAsyncServerExchange, List<Root>, Mono<Void>>>of(
                        (exchange, roots) -> { 
                            logger.debug("Roots changed (ignored): {}", roots); 
                            return Mono.empty();
                            }
                        ))
                .build();
        //@formatter:on

        // 5. Start an embedded Tomcat bound strictly to loopback, register the MCP servlet at /mcp.
        Tomcat tomcat = new Tomcat();
        // Avoid a tomcat.<port> directory in the current directory but use the temp directory
        tomcat.setBaseDir(System.getProperty("java.io.tmpdir")); 
        // Security advice: never bind to 0.0.0.0
        tomcat.setHostname(host);
        tomcat.setPort(port);
        // Connector must be added before start(); Tomcat.getConnector() creates the default one.
        tomcat.getConnector();

        // Empty docBase - no static files served; the servlet handles everything.
        Context ctx = tomcat.addContext("", null);
        // Register the transport servlet (async-capable) at the MCP endpoint.
        Tomcat.addServlet(ctx, "mcp", transportProvider).setAsyncSupported(true);
        ((StandardContext) ctx).addServletMappingDecoded(STREAMABLE_ENDPOINT, "mcp", false);

        // Start server
        tomcat.start();

        logger.info("{} started successfully and listening on {}", MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER, serverAddress);
        logger.info("  Streamable HTTP Endpoint: {}", serverAddress + STREAMABLE_ENDPOINT);

        // Block the main thread indefinitely - Tomcat connector threads handle all requests.
        tomcat.getServer().await();
    }

}
