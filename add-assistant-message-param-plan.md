# Plan: `review` Tool — Parameter Addition and Structured JSON Response

## Top-Level Overview

This plan has two phases:

**Phase 1 (complete):** Added a second parameter `assistant_message` to the `review` tool schema, fixed a key-name bug in the handler, and updated Javadoc. `SchemaBuilder.singleStringParameter()` was preserved for future single-parameter tools.

**Phase 2 (new):** Replace the placeholder echo response with the structured JSON format specified in [`doc/McpTools.md`](doc/McpTools.md). The response must be a `TextContent` carrying a JSON string with five fields: `verdict`, `confidence`, `feedback`, `model_used`, and `endpoint`. Since the actual LLM call is not yet wired, the values are hardcoded stubs: `verdict="PASS"`, `confidence=1.0`, `feedback="I completely agree"`, plus placeholder strings for `model_used` and `endpoint`.

**Scope (Phase 2):** `src/main/java/edu/java/service/Server.java` only. No new classes, no config system, no LLM call.
**Non-goal:** Implementing the actual LLM review call (that is the next phase after this one). The stub values will be replaced when the real LLM integration is implemented.

---

## Sub-Task 1 — Update the Tool Schema to Declare Both Parameters

**Intent**  
Replace the single-parameter schema with a two-parameter schema so the MCP protocol correctly advertises both `user_message` and `assistant_message` as required string inputs.

**Expected Outcomes**
- `Tool.builder()` receives a schema map that has two entries in `"properties"` and two entries in `"required"`.
- The MCP server's tool-discovery response lists both parameters.

**Todo List**
1. In `createToolReview()`, replace the call to `SchemaBuilder.singleStringParameter("user_message", "The user input")` with a call to `SchemaBuilder.objectSchema(...)` that includes both parameters:
   - `"user_message"` → `"The user input message"`
   - `"assistant_message"` → `"The AI assistant response to review"`
   - Both names must appear in the required list.

**Relevant Context**
- `SchemaBuilder.objectSchema(String description, Map<String,String> properties, List<String> required)` — [`SchemaBuilder.java:23`](src/main/java/edu/java/util/SchemaBuilder.java)
- `SchemaBuilder.singleStringParameter()` — [`SchemaBuilder.java:44`](src/main/java/edu/java/util/SchemaBuilder.java) — **must not be removed**; will be reused by future single-parameter tools.
- `createToolReview()` — [`Server.java:60`](src/main/java/edu/java/service/Server.java)

**Status:** [x] confirmed — proceed

---

## Sub-Task 2 — Fix the Argument Key Bug and Extract Both Parameters in the Handler

**Intent**  
The handler currently reads `callToolRequest.arguments().get("message")` which is the wrong key (`"message"` instead of `"user_message"`), so it always returns `null`. Fix this bug and also extract `assistant_message`.

**Expected Outcomes**
- `user_message` is correctly read from the arguments map using the key `"user_message"`.
- `assistant_message` is correctly read from the arguments map using the key `"assistant_message"`.
- Both values are available as `String` locals inside the handler lambda for use in the (future) AI call.
- The placeholder response still compiles and returns something sensible with both values visible.

**Todo List**
1. Fix the key: change `callToolRequest.arguments().get("message")` to `callToolRequest.arguments().get("user_message")`.
2. Add extraction of `assistant_message`: `String assistantMsg = (String) callToolRequest.arguments().get("assistant_message");`
3. Update the placeholder `TextContent` response to include both values (e.g., `"Echo: user=" + userMsg + " | assistant=" + assistantMsg`) so it is clear both parameters are wired.

**Relevant Context**
- Handler lambda — [`Server.java:67-76`](src/main/java/edu/java/service/Server.java)
- The placeholder response body will be replaced when the AI review logic is implemented.

**Status:** [x] confirmed — proceed

---

## Sub-Task 3 — Update Javadoc

**Intent**  
The Javadoc on `createToolReview()` still says "Accepts a single required string parameter `message`". Update it to accurately document both parameters.

**Expected Outcomes**
- Javadoc `@param` block (or `<p>` description) correctly lists both `user_message` and `assistant_message` as required string parameters.
- No outdated mention of a single parameter or the wrong key name `message`.

**Todo List**
1. Update the `<p>` description in the Javadoc to say the tool accepts two required string parameters: `user_message` and `assistant_message`.
2. Remove or correct any reference to "a single required string parameter `message`".

**Relevant Context**
- Javadoc block — [`Server.java:51-59`](src/main/java/edu/java/service/Server.java)

**Status:** [x] done

---

## Sub-Task 4 — Return Structured JSON Response from the Handler

**Intent**
Replace the placeholder echo string in `createToolReview()` with a properly structured JSON response matching the format defined in `doc/McpTools.md`. The five fields are `verdict`, `confidence`, `feedback`, `model_used`, and `endpoint`. For now, all values are hardcoded stubs since the LLM call is not yet implemented.

**Expected Outcomes**
- The tool handler builds a `Map<String, Object>` with the five response fields and serializes it to a JSON string using the existing `objectMapper` / `toJson()` helper already on `Server`.
- The `TextContent` body is that JSON string (not a free-form echo).
- The response is valid JSON that any MCP client can parse.
- Hardcoded stub values:
  - `"verdict"` → `"PASS"`
  - `"confidence"` → `1.0` (double)
  - `"feedback"` → `"I completely agree"`
  - `"model_used"` → `"stub"` (placeholder until LLM config is wired)
- `endpoint` is **excluded** — `model_used` alone provides the traceability that matters; knowing the endpoint adds no value once the model is known.
- The existing `toJson(Map)` helper on `Server` is reused — no new serialization code is needed.
- Javadoc on `createToolReview()` is updated to document the four-field JSON return structure.

**Todo List**
1. Inside the handler lambda of `createToolReview()`, replace the echo string construction with a `HashMap<String, Object>` containing the four fields; `confidence` must be stored as a `double`, not a `String`.
2. Call `toJson(responseMap)` to serialize it; assign the result to a local `String responseJson`.
3. Pass `responseJson` as the argument to `TextContent.builder(...)` instead of the echo string.
4. Update the Javadoc to document the four-field JSON return structure (`verdict`, `confidence`, `feedback`, `model_used`).

**Relevant Context**
- Current handler lambda — [`Server.java:76-88`](src/main/java/edu/java/service/Server.java)
- `toJson()` helper — [`Server.java:92-99`](src/main/java/edu/java/service/Server.java) — already accepts `Map<String, Object>`, already present
- Response format spec — [`doc/McpTools.md:61-68`](doc/McpTools.md)
- `objectMapper` is a `static final` field on `Server` — `toJson()` uses it; no new import needed
- `Map.of()` cannot hold mixed types cleanly as `Map<String, Object>` — use `new HashMap<>()` with explicit puts so `confidence` is stored as `double`, not coerced to `String`

**Status:** [ ] pending
