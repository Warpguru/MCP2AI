package edu.java.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Centralised configuration loader.
 *
 * <p>
 * Resolution order for each key:
 * <ol>
 * <li>Java system property ({@code -Dkey=value} on the command line)</li>
 * <li>OS environment variable</li>
 * <li>{@code config.properties} on the classpath</li>
 * <li>Hard-coded default</li>
 * </ol>
 *
 * <p>
 * Every getter always returns a non-null, non-empty string. {@code config.properties} is gitignored - copy
 * {@code config.properties.example} to create it. If the file is absent, only environment variables and hard-coded defaults are
 * used.
 */
public class Config {

    private static final Logger logger = LogManager.getLogger(Config.class);

    private static final String FILE_CONFIG_PROPERTIES = "config.properties";

    /** Configuration key - base URL of the OpenAI-compatible API endpoint. */
    private static final String KEY_BASE_URL = "OPENAI_BASE_URL";

    /** Configuration key - API key. */
    private static final String KEY_API_KEY = "OPENAI_API_KEY";

    /** Configuration key - model identifier. */
    private static final String KEY_MODEL = "OPENAI_MODEL";

    /** Configuration key - LLM temperature. */
    private static final String KEY_TEMPERATURE = "OPENAI_TEMPERATURE";

    /** Configuration key - request timeout in seconds. */
    private static final String KEY_TIMEOUT = "OPENAI_TIMEOUT";

    /** Lazily loaded; {@code null} means not yet initialised. */
    private static volatile Properties properties;

    /**
     * Hidden constructor.
     */
    private Config() {
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
    public static String getAsString(String key) {
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
        String propValue = loadProperties().getProperty(key);
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
    public static Float getAsFloat(String key) {
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
        String propValue = loadProperties().getProperty(key);
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
    public static Integer getAsInt(String key) {
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
        String propValue = loadProperties().getProperty(key);
        return propValue != null ? Integer.parseInt(propValue.trim()) : -1;
    }

    /**
     * Base URL for the OpenAI API endpoint. Defaults to the public OpenAI endpoint.
     * 
     * @return baseUrl or null if absent
     */
    public static String getBaseUrl() {
        String v = getAsString(KEY_BASE_URL);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * API key. Defaults to an empty string when {@code OPENAI_API_KEY} is not set; most providers will then reject requests
     * with an authentication error, which is more helpful than a {@link NullPointerException} inside the SDK.
     *
     * @return apiKey, never {@code null}
     */
    public static String getApiKey() {
        String v = getAsString(KEY_API_KEY);
        return (v != null) ? v : "";
    }

    /**
     * Chat model. Defaults to {@code gpt-4o-mini}.
     * 
     * @return model or null if absent
     */
    public static String getModel() {
        String v = getAsString(KEY_MODEL);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    /**
     * Model temperature. Defaults to {@code 0.01}.
     *
     * @return temperature
     */
    public static Float getTemperature() {
        Float f = getAsFloat(KEY_TEMPERATURE);
        return (f != null) ? f : Float.valueOf(0.01f);
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
    public static Integer getTimeout() {
        int v = getAsInt(KEY_TIMEOUT);
        return (v > 0) ? v : 120;
    }

    // -------------------------------------------------------------------------
    // Startup initialisation
    // -------------------------------------------------------------------------

    /**
     * Eagerly initialises the configuration by loading {@code config.properties} from the classpath.
     *
     * <p>
     * Calling this once at server startup ensures the "not found" debug message is emitted at a
     * predictable moment rather than on the first worker-thread request.
     */
    public static void load() {
        loadProperties();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Loads {@code config.properties} from the classpath on first call and caches the result.
     *
     * <p>
     * Uses double-checked locking to ensure the file is read at most once even under concurrent access. If the file is absent
     * the returned {@link Properties} object is empty; callers fall back to hard-coded defaults.
     *
     * @return the loaded (possibly empty) {@link Properties} instance
     */
    private static Properties loadProperties() {
        if (properties == null) {
            synchronized (Config.class) {
                if (properties == null) {
                    Properties propertiesConfig = new Properties();
                    try (InputStream is = Config.class.getClassLoader().getResourceAsStream(FILE_CONFIG_PROPERTIES)) {
                        if (is != null) {
                            propertiesConfig.load(is);
                            logger.debug("Loaded {}", FILE_CONFIG_PROPERTIES);
                        } else {
                            logger.debug("{} not found on classpath - using env vars and defaults only",
                                    FILE_CONFIG_PROPERTIES);
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to read {}: {}", FILE_CONFIG_PROPERTIES, e.getMessage());
                    }
                    properties = propertiesConfig;
                }
            }
        }
        return properties;
    }

}
