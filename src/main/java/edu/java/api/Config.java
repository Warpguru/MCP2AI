package edu.java.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Centralised configuration loader - singleton.
 *
 * <p>
 * Obtain the single instance via {@link #getInstance()}. Resolution order for each key:
 * <ol>
 * <li>Java system property ({@code -Dkey=value} on the command line)</li>
 * <li>OS environment variable</li>
 * <li>{@code config.properties} on the classpath</li>
 * <li>Hard-coded default</li>
 * </ol>
 *
 * <p>
 * Every getter always returns a non-null, non-empty string. {@code config.properties} is gitignored - copy
 * {@code config.properties.template} to create it. If the file is absent, only environment variables and hard-coded defaults
 * are used.
 */
public class Config {

    private static final Logger logger = LogManager.getLogger(Config.class);

    /** Singleton instance; initialised on first call to {@link #getInstance()}. */
    private static volatile Config instance;

    /** Loaded once on first access; never {@code null} after initialisation. */
    private final Properties properties;

    /** Default properties file. */
    private static final String FILE_CONFIG_PROPERTIES = "config.properties";

    /** Configuration key - base URL of the OpenAI-compatible API endpoint. */
    private static final String KEY_BASE_URL = "OPENAI_BASE_URL";

    /** Configuration key - API key. */
    private static final String KEY_API_KEY = "OPENAI_API_KEY";

    /** Configuration key - model identifier. */
    private static final String KEY_MODEL = "OPENAI_MODEL";

    /** Configuration key - LLM temperature. */
    private static final String KEY_TEMPERATURE = "OPENAI_TEMPERATURE";

    /** Default LLM temperature. */
    private static final Float KEY_TEMPERATURE_DEFAULT = 0.01f;

    /** Configuration key - request timeout in seconds. */
    private static final String KEY_TIMEOUT = "OPENAI_TIMEOUT";

    /** Default request timeout in seconds. */
    private static final int KEY_TIMEOUT_DEFAULT = 120;

    /** Configuration key - fully qualified path to the review system prompt file. */
    private static final String KEY_SYSTEMPROMPT_REVIEW = "OPENAI_SYSTEMPROMPT_REVIEW";

    /** Configuration key - fully qualified path to the obfuscate system prompt file. */
    private static final String KEY_SYSTEMPROMPT_OBFUSCATE = "OPENAI_SYSTEMPROMPT_OBFUSCATE";

    /** Configuration key - fully qualified path to an optional file containing a sample of the author's writing style. */
    private static final String KEY_SYSTEMPROMPT_OBFUSCATE_TEMPLATE = "OPENAI_SYSTEMPROMPT_OBFUSCATE_TEMPLATE";

    /** Configuration key - bind address for the embedded HTTP server. */
    private static final String KEY_STREAMABLE_HOST = "MCP_STREAMABLE_HOST";

    /** Default bind address for the embedded HTTP server. */
    private static final String KEY_STREAMABLE_HOST_DEFAULT = "127.0.0.1";

    /** Configuration key - bind port for the embedded HTTP server. */
    private static final String KEY_STREAMABLE_PORT = "MCP_STREAMABLE_PORT";

    /** Default bind port for the embedded HTTP server. */
    private static final int KEY_STREAMABLE_PORT_DEFAULT = 8081;

    /**
     * Minimum number of words an author style-template file must contain to be usable. Files below this threshold are ignored
     * with a warning.
     */
    public static final int STYLE_TEMPLATE_MIN_WORDS = 800;

    /**
     * Private constructor - loads {@code config.properties} from the classpath.
     */
    private Config() {
        this.properties = loadProperties();
    }

    /**
     * Returns the singleton {@link Config} instance, creating it on first call.
     *
     * <p>
     * Uses double-checked locking for thread-safe lazy initialisation.
     *
     * @return the singleton instance, never {@code null}
     */
    public static Config getInstance() {
        if (instance == null) {
            synchronized (Config.class) {
                if (instance == null) {
                    instance = new Config();
                }
            }
        }
        return instance;
    }

    /**
     * Loads the optional author style-template file and validates that it meets the minimum word count.
     *
     * <p>
     * This is the single authoritative implementation used by both the config reporter and the obfuscate call path. Returns the
     * trimmed file content when the path is non-null, the file is readable, and the content contains at least
     * {@value #STYLE_TEMPLATE_MIN_WORDS} words. Returns {@code null} in all other cases.
     *
     * @param filePath path to the style template file, or {@code null} if not configured
     * @return trimmed file content, or {@code null} if absent, unreadable, or too short
     */
    public static String loadAndValidateStyleTemplate(final String filePath) {
        if (filePath == null) {
            return null;
        }
        String content;
        try {
            content = Files.readString(Path.of(filePath)).trim();
        } catch (IOException e) {
            logger.error("Could not read style template file '{}': {}", filePath, e.getMessage());
            return null;
        }
        int wordCount = content.isBlank() ? 0 : content.split("\\s+").length;
        if (wordCount < STYLE_TEMPLATE_MIN_WORDS) {
            logger.warn("Style template '{}' is too short ({} words); minimum is {} words - template will be ignored", filePath,
                    wordCount, STYLE_TEMPLATE_MIN_WORDS);
            return null;
        }
        logger.debug("Style template loaded: {} words from '{}'", wordCount, filePath);
        return content;
    }
    
    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    /**
     * Base URL for the OpenAI API endpoint. Defaults to the public OpenAI endpoint.
     *
     * @return baseUrl or null if absent
     */
    public String getBaseUrl() {
        String v = getAsString(KEY_BASE_URL);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * API key. Defaults to an empty string when {@code OPENAI_API_KEY} is not set; most providers will then reject requests
     * with an authentication error, which is more helpful than a {@link NullPointerException} inside the SDK.
     *
     * @return apiKey, never {@code null}
     */
    public String getApiKey() {
        String v = getAsString(KEY_API_KEY);
        return (v != null) ? v : "";
    }

    /**
     * Chat model. Defaults to {@code gpt-4o-mini}.
     *
     * @return model or null if absent
     */
    public String getModel() {
        String v = getAsString(KEY_MODEL);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * Model temperature. Defaults to {@code 0.01}.
     *
     * @return temperature
     */
    public Float getTemperature() {
        Float f = getAsFloat(KEY_TEMPERATURE);
        return (f != null) ? f : KEY_TEMPERATURE_DEFAULT;
    }

    /**
     * Request timeout in seconds. Defaults to {@code 120} (2 minutes).
     *
     * <p>
     * The SDK default is 10 minutes and retries twice, so without an explicit cap a stuck local model can block for up to 30
     * minutes. Set {@code OPENAI_TIMEOUT} to a lower value for interactive use.
     *
     * @return timeout in seconds, never negative
     */
    public Integer getTimeout() {
        int v = getAsInteger(KEY_TIMEOUT);
        return (v > 0) ? v : KEY_TIMEOUT_DEFAULT;
    }

    /**
     * Fully qualified path to an external file containing the review system prompt.
     *
     * <p>
     * When {@code null}, callers must fall back to their built-in default prompt.
     *
     * @return configured path string, or {@code null} if absent or empty
     */
    public String getSystemPromptReviewPath() {
        String v = getAsString(KEY_SYSTEMPROMPT_REVIEW);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * Fully qualified path to an external file containing the obfuscate system prompt.
     *
     * <p>
     * When {@code null}, callers must fall back to their built-in default prompt.
     *
     * @return configured path string, or {@code null} if absent or empty
     */
    public String getSystemPromptObfuscatePath() {
        String v = getAsString(KEY_SYSTEMPROMPT_OBFUSCATE);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * Fully qualified path to an optional text file containing a sample of the author's writing style.
     *
     * <p>
     * When present and the file contains at least the minimum word count, its content is appended to the obfuscate system
     * prompt so the secondary LLM can adapt its rewrite toward the author's personal voice.
     *
     * @return configured path string, or {@code null} if absent or empty
     */
    public String getSystemPromptObfuscateTemplatePath() {
        String v = getAsString(KEY_SYSTEMPROMPT_OBFUSCATE_TEMPLATE);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * Bind address for the embedded Streamable HTTP server. Defaults to {@code 127.0.0.1}.
     *
     * @return host string, never {@code null}
     */
    public String getStreamableHost() {
        String v = getAsString(KEY_STREAMABLE_HOST);
        return (v != null && !v.isEmpty()) ? v : KEY_STREAMABLE_HOST_DEFAULT;
    }

    /**
     * Bind port for the embedded Streamable HTTP server. Defaults to {@code 8081}.
     *
     * @return port number, always positive
     */
    public int getStreamablePort() {
        int v = getAsInteger(KEY_STREAMABLE_PORT);
        return (v > 0) ? v : KEY_STREAMABLE_PORT_DEFAULT;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Loads {@code config.properties} from the classpath.
     *
     * <p>
     * Called once from the constructor. If the file is absent the returned {@link Properties} object is empty; callers fall
     * back to hard-coded defaults.
     *
     * @return the loaded (possibly empty) {@link Properties} instance
     */
    private Properties loadProperties() {
        Properties propertiesConfig = new Properties();
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream(FILE_CONFIG_PROPERTIES)) {
            if (is != null) {
                propertiesConfig.load(is);
                logger.debug("Loaded {}", FILE_CONFIG_PROPERTIES);
            } else {
                logger.debug("{} not found on classpath - using env vars and defaults only", FILE_CONFIG_PROPERTIES);
            }
        } catch (IOException e) {
            logger.warn("Failed to read {}: {}", FILE_CONFIG_PROPERTIES, e.getMessage());
        }
        return propertiesConfig;
    }

    /**
     * Returns the string value for {@code key}, or {@code null} if not found.
     *
     * <p>
     * Resolution order:
     * <ol>
     * <li>Java system property ({@code -Dkey=value})</li>
     * <li>OS environment variable</li>
     * <li>{@code config.properties} on the classpath</li>
     * </ol>
     *
     * @param key the configuration key
     * @return trimmed value, or {@code null} if absent in all sources
     */
    private String getAsString(String key) {
        // 1. Java system property (-Dkey=value)
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        // 2. OS environment variable
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        // 3. config.properties
        String propValue = properties.getProperty(key);
        return propValue != null ? propValue.trim() : null;
    }

    /**
     * Returns the float value for {@code key}, or {@code null} if not found.
     *
     * <p>
     * Resolution order: system property → env var → config file.
     *
     * @param key the configuration key
     * @return parsed {@link Float}, or {@code null} if absent in all sources
     */
    private Float getAsFloat(String key) {
        // 1. Java system property (-Dkey=value)
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isBlank()) {
            return Float.valueOf(sysProp.trim());
        }
        // 2. OS environment variable
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return Float.valueOf(envValue.trim());
        }
        // 3. config.properties
        String propValue = properties.getProperty(key);
        return propValue != null ? Float.valueOf(propValue.trim()) : null;
    }

    /**
     * Returns the integer value for {@code key}, or {@code -1} if not found.
     *
     * <p>
     * Resolution order: system property → env var → config file.
     *
     * @param key the configuration key
     * @return parsed int, or {@code -1} if absent in all sources
     */
    private Integer getAsInteger(String key) {
        // 1. Java system property (-Dkey=value)
        String systemProperties = System.getProperty(key);
        if (systemProperties != null && !systemProperties.isBlank()) {
            return Integer.parseInt(systemProperties.trim());
        }
        // 2. OS environment variable
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return Integer.parseInt(envValue.trim());
        }
        // 3. config.properties
        String propValue = properties.getProperty(key);
        return propValue != null ? Integer.parseInt(propValue.trim()) : -1;
    }

}
