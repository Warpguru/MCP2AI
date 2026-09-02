# Plan: Wire Real LLM Review Call into the `review` MCP Tool

## Top-Level Overview

The `review` MCP tool currently returns hardcoded stub values. This plan wires up a real LLM call by:

1. Adding `OPENAI_TIMEOUT` to `Config` (key constant + `getTimeout()` getter, default 2 minutes).
2. Using that timeout in `ClientFactory` when constructing the `OpenAIClient`.
3. Transforming `OpenAIChat` from a self-contained demo into a reusable `review()` method that accepts `userMessage`, `assistantMessage`, `model`, and `temperature`, calls the LLM with a baked-in reviewer system prompt, parses the JSON response, and returns a `Map<String, Object>` with the four response fields.
4. Replacing the TODO stub block in `Server.createToolReview()` with a call to `OpenAIChat.review()`.
5. Updating all five affected config files to document `OPENAI_TIMEOUT`.

**Files changed:**
- `src/main/java/edu/java/api/Config.java`
- `src/main/java/edu/java/api/ClientFactory.java`
- `src/main/java/edu/java/api/OpenAIChat.java`
- `src/main/java/edu/java/service/Server.java`
- `src/main/resources/config.properties.example`
- `src/main/resources/config.properties.openai`
- `src/main/resources/config.properties.ollama`
- `src/main/resources/config.properties.lmstudio`
- `src/main/resources/config.properties`

**Non-goal:** Adding a `REVIEWER_SYSTEM_PROMPT` env-var override (the system prompt is baked in; that can be a future enhancement).

---

## Sub-Task 1 — Add `OPENAI_TIMEOUT` to `Config` and all properties files

**Intent**
Centralise the request timeout as a first-class configuration value with a 2-minute default, following the same `KEY_*` / `getAs*` pattern already used for the other four keys.

**Expected Outcomes**
- `KEY_TIMEOUT = "OPENAI_TIMEOUT"` constant added alongside the existing four key constants.
- `getAsInt(String key)` primitive getter added (timeout is expressed as whole seconds — no float needed).
- `getTimeout()` getter added: returns an `int` representing the timeout in seconds, defaulting to `120`.
- All five properties files updated with a commented `OPENAI_TIMEOUT=120` entry.

**Todo List**
1. In `Config.java`, add `private static final String KEY_TIMEOUT = "OPENAI_TIMEOUT";` after `KEY_TEMPERATURE`.
2. Add `public static int getAsInt(String key)` that reads env var / properties file and returns `-1` if absent (signals "use default").
3. Add `public static int getTimeout()` that calls `getAsInt(KEY_TIMEOUT)` and returns `120` as the default.
4. Add `OPENAI_TIMEOUT=120` with a comment to `config.properties.example`, `config.properties.openai`, `config.properties.ollama`, `config.properties.lmstudio`, and `config.properties`.

**Relevant Context**
- `Config.java` — [`Config.java:32-42`](src/main/java/edu/java/api/Config.java) for the key constants pattern
- `Config.getAsFloat()` — [`Config.java:78-87`](src/main/java/edu/java/api/Config.java) — model for `getAsInt()`
- [`McpTools.md:151-154`](doc/McpTools.md) — specifies the 2-minute timeout rationale

**Status:** [ ] pending

---

## Sub-Task 2 — Apply the timeout in `ClientFactory`

**Intent**
`ClientFactory.create()` currently builds the client without a request timeout, exposing the server to the SDK's 10-minute default with two retries (up to 30 minutes blocked). Wire in `Config.getTimeout()`.

**Expected Outcomes**
- `ClientFactory.create()` sets a `Timeout` with the configured request duration on the `OpenAIOkHttpClient.Builder`.
- `java.time.Duration` and the SDK's `com.openai.core.http.OkHttpClient.Timeout` (or equivalent) are imported.

**Todo List**
1. In `ClientFactory.create()`, after setting `baseUrl` and `apiKey`, call:
   ```java
   builder.timeout(Timeout.builder()
       .request(Duration.ofSeconds(Config.getTimeout()))
       .build());
   ```
2. Add the required imports: `java.time.Duration` and `com.openai.core.http.OkHttpClient` or whichever class `Timeout` belongs to — verify from the SDK sources referenced in `McpTools.md`.

**Relevant Context**
- `ClientFactory.create()` — [`ClientFactory.java:29-46`](src/main/java/edu/java/api/ClientFactory.java)
- SDK usage pattern — [`McpTools.md:147-156`](doc/McpTools.md):
  ```java
  OpenAIOkHttpClient.builder()
      .timeout(Timeout.builder().request(Duration.ofMinutes(2)).build())
  ```
- Exact `Timeout` class path must be verified from `openai-java-core` sources before coding

**Status:** [ ] pending

---

## Sub-Task 3 — Transform `OpenAIChat` into a reusable `review()` method

**Intent**
Add a `public Map<String, Object> review(String userMessage, String assistantMessage, String model, Double temperature)` method to `OpenAIChat`. This is the core of the integration: it composes the reviewer prompt, calls the LLM, strips markdown fences, parses JSON, and returns the four response fields. The existing `run()` demo method is preserved unchanged.

**Expected Outcomes**
- `REVIEWER_SYSTEM_PROMPT` constant defined as a `private static final String` on `OpenAIChat`.
- `review()` method composes the user message as:
  ```
  Question: <userMessage>

  Answer to review:
  <assistantMessage>
  ```
- Temperature: uses the supplied value when non-null; falls back to `Config.getTemperature()`.
- The call uses `ResponseFormatJsonObject` where supported (per `McpTools.md:95`) but the fallback fence-stripping + Jackson parse is always implemented regardless.
- Successful parse returns a `Map<String, Object>` with:
  - `"verdict"` → String: `"PASS"`, `"FAIL"`, or `"PARTIAL"`
  - `"confidence"` → Double: 0.0–1.0
  - `"feedback"` → String
  - `"model_used"` → String (the model name passed in, for traceability)
- Parse failure (model ignored format) returns a safe fallback map:
  - `"verdict"` → `"PARTIAL"`
  - `"confidence"` → `0.3`
  - `"feedback"` → the raw text response
  - `"model_used"` → the model name
- Any network/API exception is caught, logged, and re-thrown as `RuntimeException`.

**Reviewer system prompt (baked in):**
```
You are a strict technical reviewer. You will be given an original user question
and an AI-generated answer. Evaluate the answer for correctness, completeness,
and clarity. Consider whether the answer fully addresses the question, whether
any statements are factually wrong or misleading, and whether anything important
is missing or could be improved.

Return a JSON object with exactly these fields:
{
  "verdict": "PASS" | "FAIL" | "PARTIAL",
  "confidence": <float 0.0-1.0>,
  "feedback": "<specific explanation: what is correct, what is wrong or missing, what improvements are suggested>"
}
Rules:
- "verdict" must be exactly one of: PASS, FAIL, PARTIAL
- "confidence" must be a float between 0.0 and 1.0
- "feedback" must always explain the reasoning, even for PASS
- Return only the JSON object. No preamble, no explanation outside the JSON.
```

**Todo List**
1. Add `private static final String REVIEWER_SYSTEM_PROMPT` constant with the prompt above.
2. Add `private static final ObjectMapper objectMapper = new ObjectMapper();` (Jackson already transitive dep).
3. Implement `public Map<String, Object> review(String userMessage, String assistantMessage, String model, Double temperature)`:
   a. Compose `userContent = "User message: " + userMessage + "\n\nAssistant answer to review:\n" + assistantMessage`.
   b. Resolve effective temperature: `double effectiveTemp = (temperature != null) ? temperature : Config.getTemperature();`
   c. Build `ChatCompletionCreateParams` with system message, user content, model, temperature, and `ResponseFormatJsonObject`.
   d. Call `client.chat().completions().create(params)` — client obtained from `ClientFactory.create()`.
   e. Extract raw string: `response.choices().get(0).message().content().orElse("{}")`.
   f. Strip markdown fences (two regex passes per `McpTools.md:78-80`).
   g. Parse JSON with Jackson; extract `verdict`, `confidence`, `feedback`.
   h. Build and return result map including `model_used = model`.
   i. On `JsonProcessingException`: log warning, return fallback map.
   j. On any other exception: log error, re-throw as `RuntimeException`.
4. Add required imports: `com.fasterxml.jackson.databind.JsonNode`, `com.fasterxml.jackson.core.JsonProcessingException`, `com.openai.models.chat.completions.ChatCompletionCreateParams`, `com.openai.models.shared.ResponseFormatJsonObject`, `java.util.Map`, `java.util.HashMap`.

**Relevant Context**
- Current `OpenAIChat.run()` — [`OpenAIChat.java:32-73`](src/main/java/edu/java/api/OpenAIChat.java) — preserved as-is
- JSON parsing pattern — [`McpTools.md:74-92`](doc/McpTools.md)
- `ResponseFormatJsonObject` usage — [`McpTools.md:95`](doc/McpTools.md)
- SDK gotcha: `ResponseFormatJsonObject` is from `com.openai.models.shared` — verify exact path from sources before coding

**Status:** [ ] pending

---

## Sub-Task 4 — Replace the TODO stub in `Server.createToolReview()` with the real call

**Intent**
Wire the `OpenAIChat.review()` method into the MCP tool handler, replacing the four hardcoded stub lines. Remove `@SuppressWarnings("unused")` from the parameter extractions since they are now consumed.

**Expected Outcomes**
- The TODO stub block is replaced by a call to `new OpenAIChat().review(userMsg, assistantMsg, Config.getModel(), temperature)`.
- The returned `Map<String, Object>` is passed directly to `toJson()` as before.
- On exception, `isError(true)` is set and the error message is returned as the `TextContent` body.
- `@SuppressWarnings("unused")` annotations removed from `userMsg`, `assistantMsg`, `temperature`.

**Todo List**
1. Remove the `@SuppressWarnings("unused")` annotations from the three argument extractions.
2. Replace the TODO stub block with:
   ```java
   Map<String, Object> result = new OpenAIChat().review(userMsg, assistantMsg, Config.getModel(), temperature);
   ```
3. Wrap in try/catch: on exception set `isError(true)` and return the error message as `TextContent`.
4. Add import for `edu.java.api.OpenAIChat` and `edu.java.api.Config`.

**Relevant Context**
- Handler lambda — [`Server.java:89-112`](src/main/java/edu/java/service/Server.java)
- `toJson()` — [`Server.java:118-124`](src/main/java/edu/java/service/Server.java) — unchanged

**Status:** [ ] pending
