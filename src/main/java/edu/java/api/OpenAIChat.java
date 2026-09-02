package edu.java.api;

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
 * Wrapper around the OpenAI chat completions API providing LLM-based answer review.
 */
public class OpenAIChat {

    private static final Logger logger = LogManager.getLogger(OpenAIChat.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Feedback returned when the client cannot be created or the API call fails entirely. The actual error detail is logged
     * separately at ERROR level.
     */
    private static final String FEEDBACK_REVIEW_UNAVAILABLE = "Review could not be performed: the reviewer LLM is unavailable or the request failed.";

    /**
     * System prompt for the reviewer LLM used in {@link #review(String, String, String, Double)}.
     *
     * <p>
     * Instructs the model to evaluate the assistant answer for correctness, completeness, and clarity and return a strict JSON
     * response with {@code verdict}, {@code confidence}, and {@code feedback} fields.
     */
    private static final String REVIEWER_SYSTEM_PROMPT = "You are a strict technical reviewer. You will be given an original user question\n"
            + "and an AI-generated answer. Evaluate the answer for correctness, completeness,\n"
            + "and clarity. Consider whether the answer fully addresses the question, whether\n"
            + "any statements are factually wrong or misleading, and whether anything important\n"
            + "is missing or could be improved.\n\n" + "Return a JSON object with exactly these fields:\n" + "{\n"
            + "  \"verdict\": \"PASS\" | \"FAIL\" | \"PARTIAL\",\n" + "  \"confidence\": <float 0.0-1.0>,\n"
            + "  \"feedback\": \"<specific explanation: what is correct, what is wrong or missing, "
            + "what improvements are suggested>\"\n" + "}\n" + "Rules:\n"
            + "- \"verdict\" must be exactly one of: PASS, FAIL, PARTIAL\n"
            + "- \"confidence\" must be a float between 0.0 and 1.0\n"
            + "- \"feedback\" must always explain the reasoning, even for PASS\n"
            + "- Return only the JSON object. No preamble, no explanation outside the JSON.";

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
            String userContent = "User message: " + userMessage + "\n\nAssistant answer to review:\n" + assistantMessage;
            double effectiveTemp = (temperature != null) ? temperature : Config.getTemperature();
            //@formatter:off
            ChatCompletionCreateParams params = ChatCompletionCreateParams
                    .builder()
                    .addSystemMessage(REVIEWER_SYSTEM_PROMPT)
                    .addUserMessage(userContent)
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

}
