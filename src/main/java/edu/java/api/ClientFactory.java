package edu.java.api;

import java.time.Duration;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

/**
 * Factory for constructing an {@link OpenAIClient}.
 *
 * <p>
 * API key and base URL are sourced from {@link Config}. The API key is never logged.
 */
public class ClientFactory {

    private static final Logger logger = LogManager.getLogger(ClientFactory.class);

    /**
     * Hidden constructor.
     */
    private ClientFactory() {
    }

    /**
     * Creates and returns a configured {@link OpenAIClient}.
     *
     * @return {@link OpenAIClient} ready to use or null
     */
    public static OpenAIClient create() {
        String baseUrl = Config.getBaseUrl();
        String apiKey = Config.getApiKey();
        if (Objects.isNull(baseUrl)) {
            return null;
        }
        // Instantiate client
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(Config.getTimeout()));
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        } else {
            // SDK requires a non-null key; use a placeholder for local servers
            logger.warn("OPENAI_API_KEY is not set - using placeholder key (fine for local servers)");
            builder.apiKey("undefined");
        }
        return builder.build();
    }

}
