package edu.java.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/**
 * Wrapper around the OpenAI chat completions API providing LLM-based answer review and obfuscation.
 */
public class OpenAIChat {

    private static final Logger logger = LogManager.getLogger(OpenAIChat.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Appended to the obfuscate system prompt when a valid style template is loaded. Uses the {@code {{style_template}}}
     * placeholder which is substituted with the file content at call time.
     */
    private static final String STYLE_TEMPLATE_SECTION = "\n\n---\n\n" + "## Author writing style\n\n"
            + "The text below is a writing sample by the author. "
            + "Use it as a reference for sentence rhythm, vocabulary, paragraph length, "
            + "and any characteristic phrasing. Bring the rewritten text closer to this voice "
            + "without copying specific sentences.\n\n" + "<STYLE_TEMPLATE>\n{{style_template}}\n</STYLE_TEMPLATE>";

    /**
     * Feedback returned when the review client cannot be created or the API call fails entirely. The actual error detail is
     * logged separately at ERROR level.
     */
    private static final String FEEDBACK_REVIEW_UNAVAILABLE = "Review could not be performed: the reviewer LLM is unavailable or the request failed.";

    /** Classpath resource containing the default review system prompt. */
    private static final String REVIEWER_SYSTEM_PROMPT_RESOURCE = "SystemPrompt.Review.md";

    /**
     * Default system prompt for the reviewer LLM, loaded once from {@value #REVIEWER_SYSTEM_PROMPT_RESOURCE} on the classpath.
     *
     * <p>
     * The prompt uses {@code {{user_message}}} and {@code {{assistant_message}}} placeholders that are substituted at call time
     * in {@link #review(String, String, String, Double)}.
     *
     * <p>
     * Falls back to an empty string if the resource cannot be read; a WARN is logged in that case.
     */
    private static final String REVIEWER_SYSTEM_PROMPT = loadClasspathPrompt(REVIEWER_SYSTEM_PROMPT_RESOURCE);

    /** Classpath resource containing the default obfuscate system prompt. */
    private static final String OBFUSCATOR_SYSTEM_PROMPT_RESOURCE = "SystemPrompt.Obfuscate.md";

    /**
     * Default system prompt for the obfuscator LLM, loaded once from {@value #OBFUSCATOR_SYSTEM_PROMPT_RESOURCE} on the
     * classpath.
     *
     * <p>
     * The prompt uses the {@code {{assistant_message}}} placeholder that is substituted at call time in
     * {@link #obfuscate(String, String, Double)}.
     *
     * <p>
     * Falls back to an empty string if the resource cannot be read; a WARN is logged in that case.
     */
    private static final String OBFUSCATOR_SYSTEM_PROMPT = loadClasspathPrompt(OBFUSCATOR_SYSTEM_PROMPT_RESOURCE);

    /**
     * Submits a user message and an AI assistant answer to a second LLM for independent review.
     *
     * <p>
     * The reviewer is called with a single chat completion (not multi-turn). The user content sent to the reviewer follows this
     * syntax:
     * 
     * <pre>
     * User message: &lt;userMessage&gt;
     *
     * Assistant answer to review:
     * &lt;assistantMessage&gt;
     * </pre>
     *
     * <p>
     * The response JSON from the LLM is parsed and returned as a {@link Map} with four fields:
     * <ul>
     * <li>{@code verdict} - {@link ReviewVerdict#PASS}, {@link ReviewVerdict#FAIL}, or {@link ReviewVerdict#PARTIAL}
     * (serialised as its {@link ReviewVerdict#name()})</li>
     * <li>{@code confidence} - double 0.0–1.0, the model's self-assessed certainty</li>
     * <li>{@code feedback} - explanation of issues or confirmation of correctness</li>
     * <li>{@code model_used} - the {@code model} argument, for traceability</li>
     * </ul>
     *
     * <p>
     * If the model ignores the JSON format instruction, the raw text is returned as {@code feedback} with
     * {@code verdict=}{@link ReviewVerdict#PARTIAL} and {@code confidence=0.3}. If the client cannot be created or the API call
     * fails entirely, {@code verdict=}{@link ReviewVerdict#FAIL} is returned with {@link #FEEDBACK_REVIEW_UNAVAILABLE} as
     * feedback and {@code confidence=1.0} (the failure is certain); the full error is logged at ERROR level.
     *
     * <p>
     * {@link OpenAIClient#close()} is always called after the LLM call to release underlying resources.
     *
     * @param userMessage      the original user question
     * @param assistantMessage the AI assistant answer to be reviewed
     * @param model            model identifier passed to the API
     * @param temperature      temperature for this call; {@code null} falls back to {@link Config#getTemperature()}
     * @return review result map with {@code verdict}, {@code confidence}, {@code feedback}, {@code model_used}
     */
    public Map<String, Object> review(final String userMessage, final String assistantMessage, final String model,
            final Double temperature) {
        OpenAIClient client = null;
        try {
            client = ClientFactory.create();
            if (client == null) {
                throw new IllegalStateException("Endpoint may be undefined, unreachable");
            }
            String systemPrompt = loadSystemPrompt(Config.getInstance().getSystemPromptReviewPath(), REVIEWER_SYSTEM_PROMPT)
                    .replace("{{user_message}}", userMessage).replace("{{assistant_message}}", assistantMessage);
            double effectiveTemp = (temperature != null) ? temperature : Config.getInstance().getTemperature();
            //@formatter:off
            ChatCompletionCreateParams params = ChatCompletionCreateParams
                    .builder()
                    .addSystemMessage(systemPrompt)
                    .model(model)
                    .temperature(effectiveTemp)
                    .responseFormat(ResponseFormatJsonObject.builder().build())
                    .build();
            //@formatter:on
            logger.debug("Sending review request to model: {}", model);
            //@formatter:off
            ChatCompletion response = client
                    .chat()
                    .completions()
                    .create(params);
            //@formatter:on
            String raw = response.choices().get(0).message().content().orElse("{}");
            // Local models sometimes wrap JSON in markdown fences - strip them
            raw = raw.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").trim();
            raw = raw.replaceAll("(?s)```\\s*(.*?)\\s*```", "$1").trim();
            logger.debug("Review raw response: {}", raw);

            try {
                JsonNode node = objectMapper.readTree(raw);
                String verdict = node.get("verdict").asText();
                double confidence = node.get("confidence").asDouble();
                String feedback = node.get("feedback").asText();
                Map<String, Object> result = new HashMap<>();
                result.put("verdict", verdict);
                result.put("confidence", confidence);
                result.put("feedback", feedback);
                result.put("model_used", model);
                return result;
            } catch (JsonProcessingException e) {
                // Model ignored the format instruction - return a safe fallback
                logger.warn("Review response could not be parsed as JSON, returning fallback: {}", e.getMessage());
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("verdict", ReviewVerdict.PARTIAL.name());
                fallback.put("confidence", 0.3);
                fallback.put("feedback", raw);
                fallback.put("model_used", model);
                return fallback;
            }
        } catch (Exception e) {
            // confidence=1.0: we are completely certain the review failed (an exception occurred)
            logger.error("Review request failed - reviewer LLM unavailable or request error: model={}, error={}", model,
                    e.getMessage(), e);
            Map<String, Object> fail = new HashMap<>();
            fail.put("verdict", ReviewVerdict.FAIL.name());
            fail.put("confidence", 1.0);
            fail.put("feedback", FEEDBACK_REVIEW_UNAVAILABLE);
            fail.put("model_used", model);
            return fail;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Rewrites an AI assistant answer so that automated AI-content detectors cannot reliably classify it as machine-generated.
     *
     * <p>
     * The obfuscator is called with a single chat completion (not multi-turn). The {@code {{assistant_message}}} placeholder in
     * the system prompt is substituted with {@code assistantMessage} before the call.
     *
     * <p>
     * The response JSON from the LLM is parsed and returned as a {@link Map} with five fields:
     * <ul>
     * <li>{@code verdict} - {@code "PASS"} when the rewrite is faithful; {@code "FAIL"} if the transformation could not be
     * completed</li>
     * <li>{@code confidence} - double 0.0–1.0, the model's self-assessed certainty that the rewrite is faithful and
     * undetectable</li>
     * <li>{@code obfuscated} - the rewritten text, indistinguishable from human-authored writing</li>
     * <li>{@code changes_summary} - one short paragraph describing the main categories of change made</li>
     * <li>{@code model_used} - the {@code model} argument, for traceability</li>
     * </ul>
     *
     * <p>
     * If the model ignores the JSON format instruction ({@link JsonProcessingException}), or if the client cannot be created or
     * the API call fails entirely, {@code verdict=FAIL} is returned with {@code obfuscated} set to the raw LLM text when
     * available, or empty when no response was received. The full error is logged at WARN or ERROR level respectively.
     *
     * <p>
     * {@link OpenAIClient#close()} is always called after the LLM call to release underlying resources.
     *
     * @param assistantMessage the AI assistant answer to be obfuscated
     * @param model            model identifier passed to the API
     * @param temperature      temperature for this call; {@code null} falls back to {@link Config#getTemperature()}
     * @return obfuscation result map with {@code verdict}, {@code confidence}, {@code obfuscated}, {@code changes_summary},
     *         {@code model_used}; {@code verdict=FAIL} on any error; when a valid style template is configured via
     *         {@code OPENAI_SYSTEMPROMPT_OBFUSCATE_TEMPLATE} the rewrite is additionally guided toward the author's personal
     *         voice
     */
    public Map<String, Object> obfuscate(final String assistantMessage, final String model, final Double temperature) {
        OpenAIClient client = null;
        try {
            client = ClientFactory.create();
            if (client == null) {
                throw new IllegalStateException("Endpoint may be undefined, unreachable");
            }
            String systemPrompt = loadSystemPrompt(Config.getInstance().getSystemPromptObfuscatePath(),
                    OBFUSCATOR_SYSTEM_PROMPT).replace("{{assistant_message}}", assistantMessage);
            // Append style-template section when a valid author writing sample is configured
            String styleTemplate = loadStyleTemplate(Config.getInstance().getSystemPromptObfuscateTemplatePath());
            if (styleTemplate != null) {
                systemPrompt = systemPrompt + STYLE_TEMPLATE_SECTION.replace("{{style_template}}", styleTemplate);
                logger.debug("Author style template appended to obfuscate system prompt");
            }
            double effectiveTemp = (temperature != null) ? temperature : Config.getInstance().getTemperature();
            //@formatter:off
            ChatCompletionCreateParams params = ChatCompletionCreateParams
                    .builder()
                    .addSystemMessage(systemPrompt)
                    .model(model)
                    .temperature(effectiveTemp)
                    .responseFormat(ResponseFormatJsonObject.builder().build())
                    .build();
            //@formatter:on
            logger.debug("Sending obfuscate request to model: {}", model);
            //@formatter:off
            ChatCompletion response = client
                    .chat()
                    .completions()
                    .create(params);
            //@formatter:on
            String raw = response.choices().get(0).message().content().orElse("{}");
            // Local models sometimes wrap JSON in markdown fences - strip them
            raw = raw.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").trim();
            raw = raw.replaceAll("(?s)```\\s*(.*?)\\s*```", "$1").trim();
            logger.debug("Obfuscate raw response: {}", raw);

            try {
                JsonNode node = objectMapper.readTree(raw);
                String verdict = node.get("verdict").asText();
                double confidence = node.get("confidence").asDouble();
                String obfuscated = node.get("obfuscated").asText();
                String changesSummary = node.get("changes_summary").asText();
                Map<String, Object> result = new HashMap<>();
                result.put("verdict", verdict);
                result.put("confidence", confidence);
                result.put("obfuscated", obfuscated);
                result.put("changes_summary", changesSummary);
                result.put("model_used", model);
                return result;
            } catch (JsonProcessingException e) {
                // Model ignored the format instruction - raw text is still useful as obfuscated value
                logger.warn("Obfuscate response could not be parsed as JSON, returning FAIL: {}", e.getMessage());
                Map<String, Object> fail = new HashMap<>();
                fail.put("verdict", ReviewVerdict.FAIL.name());
                fail.put("confidence", 0.0);
                fail.put("obfuscated", raw);
                fail.put("changes_summary", "");
                fail.put("model_used", model);
                return fail;
            }
        } catch (Exception e) {
            // Return empty obfuscated to save bandwidth; verdict=FAIL signals the caller that the call failed
            logger.error("Obfuscate request failed - obfuscator LLM unavailable or request error: model={}, error={}", model,
                    e.getMessage(), e);
            Map<String, Object> fail = new HashMap<>();
            fail.put("verdict", ReviewVerdict.FAIL.name());
            fail.put("confidence", 1.0);
            fail.put("obfuscated", "");
            fail.put("changes_summary", "");
            fail.put("model_used", model);
            return fail;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Loads a system prompt resource from the classpath. Called once at class initialisation to populate
     * {@link #REVIEWER_SYSTEM_PROMPT} and {@link #OBFUSCATOR_SYSTEM_PROMPT}.
     *
     * @param resource classpath resource name (no leading slash)
     * @return the resource content as a UTF-8 string, or an empty string if the resource is missing or unreadable
     */
    private static String loadClasspathPrompt(final String resource) {
        try (InputStream is = OpenAIChat.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                logger.warn("Classpath resource '{}' not found - review system prompt will be empty", resource);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not read classpath resource '{}': {}", resource, e.getMessage());
            return "";
        }
    }

    /**
     * Loads a system prompt from an external file, falling back to a built-in default when the path is not configured or the
     * file cannot be read.
     *
     * @param filePath path to the external prompt file, or {@code null} if not configured
     * @param fallback the built-in default prompt to use when the file is absent or unreadable
     * @return the prompt text to use, never {@code null}
     */
    private static String loadSystemPrompt(final String filePath, final String fallback) {
        if (filePath == null) {
            return fallback;
        }
        try {
            return Files.readString(Path.of(filePath));
        } catch (IOException e) {
            logger.warn("Could not read system prompt file '{}', using built-in default: {}", filePath, e.getMessage());
            return fallback;
        }
    }

    /**
     * Delegates to {@link Config#loadAndValidateStyleTemplate(String)} - single authoritative implementation.
     *
     * @param filePath path to the style template file, or {@code null} if not configured
     * @return trimmed file content, or {@code null} if absent, unreadable, or too short
     */
    private static String loadStyleTemplate(final String filePath) {
        return Config.loadAndValidateStyleTemplate(filePath);
    }

}
