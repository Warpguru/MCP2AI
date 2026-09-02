package edu.java.util;

import com.openai.client.OpenAIClient;
import com.openai.models.models.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieves the list of models available at the configured endpoint and infers their capabilities from the model ID string.
 *
 * <p>
 * Capabilities are inferred conservatively - only assigned when the model ID contains a well-known pattern that reliably
 * indicates support for a specific feature. Models that match no specific pattern are tagged {@value #TAG_CHAT} as the safe
 * default.
 *
 * <p>
 * Fetching the model list is a best-effort operation: if the endpoint is unreachable or returns an error, an empty list is
 * returned and a warning is logged - the JVM never exits due to a models-list failure.
 */
public class ModelsDiscovery {

    private static final Logger logger = LogManager.getLogger(ModelsDiscovery.class);

    // -------------------------------------------------------------------------
    // Capability tags
    // -------------------------------------------------------------------------

    /** Tag assigned to models capable of text chat / completion. */
    private static final String TAG_CHAT = "chat";

    /** Tag assigned to text-embedding models. */
    private static final String TAG_EMBEDDINGS = "embeddings";

    /** Tag assigned to speech-to-text (transcription) models. */
    private static final String TAG_STT = "stt";

    /** Tag assigned to text-to-speech synthesis models. */
    private static final String TAG_TTS = "tts";

    /** Tag assigned to image-generation models. */
    private static final String TAG_IMAGE_GEN = "image-gen";

    /** Tag assigned to vision-capable (multimodal image input) models. */
    private static final String TAG_VISION = "vision";

    /** Tag assigned to content-moderation models. */
    private static final String TAG_MODERATION = "moderation";

    // -------------------------------------------------------------------------
    // ID patterns (all matched case-insensitively)
    // -------------------------------------------------------------------------

    /** Model ID pattern identifying speech-to-text models (e.g. {@code whisper-1}). */
    private static final String PATTERN_STT = "whisper";

    /** Model ID pattern identifying text-to-speech models (e.g. {@code tts-1}). */
    private static final String PATTERN_TTS = "tts";

    /** Model ID pattern identifying image-generation models - DALL·E family. */
    private static final String PATTERN_IMAGE_DALLE = "dall-e";

    /** Model ID pattern identifying image-generation models - GPT-image family. */
    private static final String PATTERN_IMAGE_GPT = "gpt-image";

    /** Model ID pattern identifying text-embedding models. */
    private static final String PATTERN_EMBED_1 = "embed";

    /** Model ID pattern identifying text-embedding models (alternative prefix). */
    private static final String PATTERN_EMBED_2 = "text-embedding";

    /** Model ID pattern identifying vision-capable models (Ollama naming convention). */
    private static final String PATTERN_VISION_1 = "-vision";

    /** Model ID pattern identifying vision-capable models (generic suffix). */
    private static final String PATTERN_VISION_2 = "vision";

    /** Model ID pattern identifying content-moderation models. */
    private static final String PATTERN_MODERATION = "moderation";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Holds a model's ID and the set of capability tags inferred from it.
     *
     * @param id           the model identifier as returned by the endpoint
     * @param capabilities unmodifiable set of capability tag strings (e.g. {@code "chat"}, {@code "vision"}, {@code "stt"})
     */
    public record ModelInfo(String id, Set<String> capabilities) {
    }

    /**
     * Fetches the list of models from the given client's endpoint and returns them sorted alphabetically by ID, each annotated
     * with inferred capability tags.
     *
     * <p>
     * If the endpoint does not support the models list API (e.g. returns an HTTP error or throws an exception), an empty list
     * is returned and a warning is logged.
     *
     * @param client the OpenAI-compatible client to query
     * @return sorted, unmodifiable list of {@link ModelInfo}; empty if the endpoint is unavailable or returned an error
     */
    public List<ModelInfo> listModels(final OpenAIClient client) {
        try {
            List<Model> models = client.models().list().data();
            //@formatter:off
            return models
                    .stream()
                    .map(m -> new ModelInfo(m.id(), inferCapabilities(m.id())))
                    .sorted((a, b) -> a.id().compareToIgnoreCase(b.id()))
                    .collect(Collectors.toUnmodifiableList());
            //@formatter:on
        } catch (Exception e) {
            logger.warn("Could not retrieve model list from endpoint: {}", e.getMessage());
            logger.debug("Model list retrieval failure", e);
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Capability inference
    // -------------------------------------------------------------------------

    /**
     * Infers the capability tags for a given model ID using conservative pattern matching.
     *
     * <p>
     * Only patterns that reliably indicate a capability are used. A model may receive multiple tags (e.g. a vision model also
     * gets {@value #TAG_CHAT}). Models that match no specific pattern receive only {@value #TAG_CHAT}.
     *
     * @param modelId the model identifier string (e.g. {@code "llama3.2-vision:latest"})
     * @return unmodifiable set of capability tag strings
     */
    private Set<String> inferCapabilities(final String modelId) {
        String id = modelId.toLowerCase();
        Set<String> caps = new LinkedHashSet<>();

        if (id.contains(PATTERN_STT)) {
            caps.add(TAG_STT);
        } else if (id.contains(PATTERN_TTS)) {
            caps.add(TAG_TTS);
        } else if (id.contains(PATTERN_IMAGE_DALLE) || id.contains(PATTERN_IMAGE_GPT)) {
            caps.add(TAG_IMAGE_GEN);
        } else if (id.contains(PATTERN_EMBED_1) || id.contains(PATTERN_EMBED_2)) {
            caps.add(TAG_EMBEDDINGS);
        } else if (id.contains(PATTERN_MODERATION)) {
            caps.add(TAG_MODERATION);
        } else {
            // General-purpose model - safe default
            caps.add(TAG_CHAT);
            // Vision: only tag if vision is explicit in the ID (e.g. llama3.2-vision)
            if (id.contains(PATTERN_VISION_1) || id.contains(PATTERN_VISION_2)) {
                caps.add(TAG_VISION);
            }
        }
        return Collections.unmodifiableSet(caps);
    }
    
}
