package edu.java.service;

import java.util.List;
import java.util.function.BiFunction;

import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

import edu.java.MCP2AI;
import edu.java.api.Config;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/**
 * MCP2AI Server implementation over HTTP Streamable transport (MCP2AI Spec 2025-03-26) using WebFlux. Inherits all core MCP2AI
 * primitive factory methods from {@link Server}.
 *
 * <p>
 * Streamable HTTP is the modern replacement for the legacy SSE transport. It condenses both the SSE notification channel and
 * the JSON-RPC command channel into a single {@code /mcp} endpoint that supports GET (open an SSE notification stream) and POST
 * (send a JSON-RPC message, receive an optional SSE stream or direct JSON response in the same HTTP response).
 */
public class StreamableSseServer extends Server {

    /** IP address (typically 127.0.0.1) of MCP2AI Streamable HTTP Server. */
    public static final String STREAMABLE_HOST = "127.0.0.1";
    /** Port of MCP2AI Streamable HTTP Server. */
    public static final String STREAMABLE_PORT = "8081";
    /** Base address of MCP2AI Streamable HTTP Server. */
    public static final String STREAMABLE_SERVER = "http" + "://" + STREAMABLE_HOST + ":" + STREAMABLE_PORT;
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
     * The server binds strictly to the loopback interface {@link #STREAMABLE_HOST} and {@link #STREAMABLE_PORT}. Delegates all
     * primitive registration, endpoint routing, and server startup to {@link #buildServer()}. Any fatal startup exception is
     * logged cleanly and the process shuts down with exit code {@code 1}.
     */
    public void processTransportStreamable() {
        logger.info("Starting {} over streamable-http transport...", MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER);
        // Eagerly load config once at startup so the "not found" message appears here,
        // not on the first incoming request in a Netty worker thread.
        Config.load();
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
     * Initializes the WebFlux Streamable HTTP transport provider, registers server-side capabilities, definitions, and
     * specifications (Tools, Resources, and Prompts), and spins up a standalone Netty HTTP server listening on the configured
     * loopback endpoint {@link #STREAMABLE_SERVER}.
     *
     * <p>
     * Blocks the main thread forever using {@code Mono.never()} to let active background reactive Netty handler threads manage
     * simultaneous network streams indefinitely.
     */
    private void buildServer() {
        try {
            // 1. Initialize the WebFlux Streamable HTTP Transport Provider
            //@formatter:off
            WebFluxStreamableServerTransportProvider transportProvider = WebFluxStreamableServerTransportProvider
                    .builder()
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .messageEndpoint(STREAMABLE_ENDPOINT)
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

            // 4. Build and configure the McpAsyncServer
            //@formatter:off
            @SuppressWarnings("unused")
            McpAsyncServer server = McpServer
                    .async(transportProvider)
                    .serverInfo(McpSchema.Implementation.builder(MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER, MCP2AI.MCP2AI_VERSION)
                            .title("MCP2AI Java SDK \u2014 Streamable HTTP Reference Server")
                            .description("MCP2AI Java SDK reference implementation over Streamable HTTP transport (MCP2AI Spec 2025-03-26). "
                                    + "Exposes review tool to validate and review the response of an AI assistant.")
                            .build())
                    .capabilities(capabilities)
                    .tools(toolReview)
                    // Suppress SDK WARN "no consumers provided" — roots notifications are not used by this server
                    .rootsChangeHandlers(List.<BiFunction<McpAsyncServerExchange, List<Root>, Mono<Void>>>of(
                            (exchange, roots) -> { logger.debug("Roots changed (ignored): {}", roots); return Mono.empty(); }))
                    .build();
            //@formatter:on

            // 5. Wire the transport into a Reactor Netty HTTP server bound to 127.0.0.1 (Localhost only)
            var routerFunction = transportProvider.getRouterFunction();
            var httpHandler = RouterFunctions.toHttpHandler(routerFunction);
            var adapter = new ReactorHttpHandlerAdapter(httpHandler);

            // Run the MCP2AI Server
            //@formatter:off
            HttpServer.create()
                    .host(STREAMABLE_HOST) // Security constraint: never bind to 0.0.0.0
                    .port(Integer.valueOf(STREAMABLE_PORT))
                    .handle(adapter)
                    .bindNow();
            //@formatter:on

            logger.info("{} started successfully and listening on {}", MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER,
                    STREAMABLE_SERVER);
            logger.info("  Streamable HTTP Endpoint: {}", STREAMABLE_SERVER + STREAMABLE_ENDPOINT);

            // Keep the main thread alive indefinitely to let background netty threads run
            Mono.never().block();
        } catch (Exception e) {
            logger.error("Fatal error starting {}: {}", MCP2AI.MCP_JAVA_SDK_STREAMABLE_SERVER, e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
