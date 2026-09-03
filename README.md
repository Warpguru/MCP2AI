# MCP2AI

A Java 21 MCP (Model Context Protocol) server that exposes two tools over **Streamable HTTP**:

- **`review`** - submits an AI assistant's answer to a second, independent LLM for validation and structured feedback (`verdict`, `confidence`, `feedback`)
- **`obfuscate`** - rewrites an AI assistant's answer so that automated AI-content detectors cannot reliably classify it as machine-generated (`verdict`, `confidence`, `obfuscated`, `changes_summary`)

Both tools delegate to the same configured LLM endpoint and share a single server process. Built with the [Java MCP SDK](https://github.com/modelcontextprotocol/java-sdk) and the official [OpenAI Java SDK](https://github.com/openai/openai-java). Works against both OpenAI cloud and any OpenAI-compatible local server (Ollama, LM Studio, …).

---

## Prerequisites

## Prerequisites

> **⚡ Running the server only requires Java 21.** Maven is only needed if you want to build the project from source. If you already have a pre-built `MCP2AI-1.0.0.jar`, skip straight to [Registering with Bob](#registering-with-bob).

### To run MCP2AI (required)

- **Java 21** — [download](https://jdk.java.net/java-se-ri/21) or install via your package manager

### To build from source (optional)

- **Maven 3.9+** — [download](https://maven.apache.org/download.cgi)

> On this machine, Java and Maven can be added to the `PATH` for the current session by running:
> ```cmd
> D:\Development\SetupEnvJava21.cmd
> D:\Development\SetupEnvMaven.cmd
> ```
> These are local convenience scripts — they are not required on other machines where Java 21 and Maven are already on the `PATH`.

---

## How It Works

```mermaid
sequenceDiagram
    participant Bob as Bob (AI Assistant)
    participant MCP as MCP2AI Server
    participant LLM as Secondary LLM

    Bob->>MCP: review(user_message, assistant_message)
    MCP->>LLM: POST /v1/chat/completions (review prompt)
    LLM-->>MCP: {"verdict": "PASS|FAIL|PARTIAL", "confidence": 0.95, "feedback": "..."}
    MCP-->>Bob: JSON with verdict, confidence, feedback, model_used

    Bob->>MCP: obfuscate(assistant_message)
    MCP->>LLM: POST /v1/chat/completions (obfuscate prompt)
    LLM-->>MCP: {"verdict": "PASS", "confidence": 0.99, "obfuscated": "...", "changes_summary": "..."}
    MCP-->>Bob: JSON with verdict, confidence, obfuscated, changes_summary, model_used
```

The server exposes both tools over **Streamable HTTP** (MCP Spec 2025-03-26) on `http://<MCP_STREAMABLE_HOST>:<MCP_STREAMABLE_PORT>/mcp` (defaults to `http://127.0.0.1:8081/mcp`). It binds strictly to the loopback interface and is never exposed to the network.

> **💡 Best Practice for AI Assistants (Skills / Instructions):**
> Rather than manually instructing the AI assistant in every user prompt to invoke a tool (which pollutes the input sent to the secondary LLM), Bob or any AI assistant supporting skills/custom instructions should be configured with a dedicated skill (such as the included [Bob Skill: `/mcp-review`](#bob-skill-mcp-review) and [Bob Skill: `/mcp-obfuscate`](#bob-skill-mcp-obfuscate)). This allows the assistant to keep user queries clean, draft the answer independently, invoke the appropriate tool, and present the final result automatically.

---

## Registering with Bob

Two separate registration steps are required — the MCP server and the skills are independent:

### Step 1 — Register the MCP server

Add the following entry to your Bob MCP configuration file (`%APPDATA%\Roaming\Code\User\globalStorage\IBM.wca-code\settings\mcp.json` on Windows):

```json
"mcp-to-ai": {
  "url": "http://127.0.0.1:8081/mcp",
  "disabled": false,
  "alwaysAllow": [
    "review",
    "obfuscate"
  ],
  "timeout": 120000
}
```

> **`alwaysAllow`** lists the tool names that Bob may call without prompting for confirmation. Both `review` and `obfuscate` are safe to allow unconditionally — they only read the text you pass and never modify any file or system state.
>
> **`timeout`** is set to 120 000 ms (2 minutes) to match `OPENAI_TIMEOUT`. Increase both values together if you use a slow local model.
>
> Set `"disabled": true` to temporarily stop Bob from seeing the server without removing the configuration.

After saving the file, restart Bob (or reload the MCP servers).

### Step 2 — Install the Bob skills

Bob skills are **workspace-scoped** — they must be present inside the `.bob/skills/` folder of each workspace where you want to use them. This repository already contains both skill files:

```
.bob/skills/
├── mcp-review/
│   └── SKILL.md      ← review workflow skill
└── mcp-obfuscate/
    └── SKILL.md      ← obfuscate workflow skill
```

Because the files are already part of this repository, **no additional installation step is needed** if you are working inside this workspace. Bob automatically discovers skills from `.bob/skills/` at startup.

If you want to use the skills in a **different workspace**, the skill files are available in two ways:

**From a source checkout** — copy the folders directly:

```cmd
xcopy /E /I .bob\skills\mcp-review   <target-workspace>\.bob\skills\mcp-review
xcopy /E /I .bob\skills\mcp-obfuscate <target-workspace>\.bob\skills\mcp-obfuscate
```

**From the jar only** — the skills are bundled inside the jar under `.bob/skills/`. Extract them with:

```cmd
jar xf MCP2AI-1.0.0.jar .bob/skills/mcp-review .bob/skills/mcp-obfuscate
xcopy /E /I .bob\skills\mcp-review   <target-workspace>\.bob\skills\mcp-review
xcopy /E /I .bob\skills\mcp-obfuscate <target-workspace>\.bob\skills\mcp-obfuscate
```

Then restart Bob in that workspace.

### Step 3 — Start MCP2AI and verify

```cmd
java -jar target\MCP2AI-1.0.0.jar streamableserver
```

Bob will advertise `mcp__mcp-to-ai__review` and `mcp__mcp-to-ai__obfuscate` as available tools.

### Step 4 — Use the skills

The quickest way to invoke both tools is via slash commands in any Bob chat:

```text
/mcp-review  <your question here>
/mcp-obfuscate <paste AI-generated text here>
```

Full details — activation phrases, example output, and real-world session traces — are covered in the [Bob Skill: `/mcp-review`](#bob-skill-mcp-review) and [Bob Skill: `/mcp-obfuscate`](#bob-skill-mcp-obfuscate) sections above.

---

## Bob Skill: `/mcp-review`

A pre-configured Bob skill is available in `.bob/skills/mcp-review/` to automate requesting reviews with the `mcp-to-ai` MCP server.

When activated, Bob drafts an answer to your question, automatically sends both your original prompt and the draft answer to the secondary reviewer LLM using the `review` tool, and outputs the final response together with a structured review breakdown (`PASS`, `PARTIAL`, or `FAIL`, confidence score, reviewer model, and feedback).

### How to Use the Skill (Step-by-Step for Novice Users)

You can activate the review workflow in a new chat conversation using either of these simple methods:

#### Method 1: Using the Slash Command (Recommended)
Prefix your prompt with `/mcp-review`:

```text
/mcp-review Tell me a joke about Chuck Norris.
```
```text
/mcp-review How do I configure SSL in Spring Boot?
```

#### Method 2: Natural Language Phrases
Include phrases like **"with review"**, **"(with review)"**, **"with a second opinion"**, or **"in review mode"** anywhere in your prompt:

```text
Tell me a joke about Chuck Norris (with review)
```
```text
Explain how Java virtual threads work under the hood with a second opinion.
```

### Example Output

When you ask a question using `/mcp-review`, the output looks like:

> When Alexander Graham Bell invented the telephone, he had three missed calls from Chuck Norris.
>
> ---
>
> ### Secondary LLM Review
> - **Verdict:** `PASS`
> - **Confidence:** `0.95`
> - **Model Used:** `llama3.2:3b`
> - **Feedback:**
>   > The response provides a humorous, classic Chuck Norris fact adhering to the prompt requirements.

### Real-World Usage Examples

The following documents trace a complete review session end-to-end - user prompt, assistant draft, raw tool call and JSON response, the reviewer's verdict and feedback, and the final revised answer delivered to the user. They are a useful reference for understanding exactly what happens at each step of the review workflow.

| Document | Reviewer Model | Verdict | What it demonstrates |
|---|---|---|---|
| [📄 Primes - reviewed by `llama3.2:3b` (local)](doc/Primes.Llama.Reviewed.md) | `llama3.2:3b` via Ollama | `PASS` | A fast-path review: the draft is accepted with only minor clarity suggestions; the final answer is delivered with a light touch. |
| [📄 Primes - reviewed by `gpt-5.6-sol` (OpenAI)](doc/Primes.GPT-5.6-Sol.Reviewed.md) | `gpt-5.6-sol` via OpenAI | `PARTIAL` | A revision-cycle review: a more powerful model catches real correctness issues (overflow bugs, misleading memory claims, unimplemented optimisations), triggering a full rewrite before delivery. |

---

## Bob Skill: `/mcp-obfuscate`

A pre-configured Bob skill is available in `.bob/skills/mcp-obfuscate/` to automate text humanisation with the `mcp-to-ai` MCP server.

When activated, Bob takes your supplied text, sends it to the secondary LLM using the `obfuscate` tool, and outputs the rewritten text together with an **Obfuscation Summary** block (`verdict`, `confidence`, reviewer model, and a short description of the changes made).

### How to Use the Skill (Step-by-Step for Novice Users)

#### Method 1: Natural Language Phrases
Include phrases like **"obfuscate this"**, **"humanise this"**, **"make this sound more human"**, or **"pass AI detection"** in your prompt:

```text
Obfuscate this: <paste your AI-generated text here>
```
```text
Make this sound more human: <paste your AI-generated text here>
```

#### Method 2: Ask Bob to Obfuscate Its Own Answer
Ask a question and ask Bob to obfuscate its own reply in the same prompt:

```text
Explain quantum entanglement and obfuscate your answer.
```

### Example Output

When you ask Bob to obfuscate text, the output looks like:

> <rewritten text that is indistinguishable from human-authored writing>
>
> ---
>
> ### Obfuscation Summary
> - **Verdict:** `PASS`
> - **Confidence:** `0.99`
> - **Model Used:** `gpt-5.6-sol`
> - **Changes:**
>   > Reworked the prose for a more natural cadence, varied sentence length, favoured active constructions, and replaced stock phrasing while retaining every factual claim, figure, qualification, heading, and list item.

### Real-World Usage Examples

The following document traces a complete obfuscation session end-to-end - user prompt, assistant draft, raw tool call and JSON response, the obfuscated output, and the changes summary delivered to the user.

| Document | Obfuscator Model | Verdict | What it demonstrates |
|---|---|---|---|
| [📄 Laser executive summary - obfuscated by `gpt-5.6-sol` (OpenAI)](doc/Laser.GPT-5.6-Sol.Obfuscate.md) | `gpt-5.6-sol` via OpenAI | `PASS` | A full obfuscation pass on a defence-domain executive summary: AI-signature vocabulary stripped, sentence-length variance introduced, boilerplate structure eliminated, and voice strengthened - all factual claims, figures, and headings preserved exactly. |

---

## MCP Tool: `review`

> **Note on Tool Namespacing (`mcp__<server>__<tool>`):**
> In Bob and compatible MCP client environments, external MCP tools are registered using namespaced identifiers formatted as `mcp__<server-name>__<tool-name>` (e.g., `mcp__mcp-to-ai__review`). If you ever need to instruct the assistant directly without using the `/mcp-review` skill, you can refer to the tool by its full ID `mcp__mcp-to-ai__review` or simply ask to use the `review` tool from the `mcp-to-ai` server.

| Field | Type | Required | Description |
|---|---|---|---|
| `user_message` | string | ✅ | The original question the user asked the AI assistant |
| `assistant_message` | string | ✅ | The AI assistant's answer to be reviewed |
| `temperature` | number | ❌ | Override the reviewer LLM temperature (0.0–1.0); falls back to `OPENAI_TEMPERATURE` |

**Response** - a JSON object with four fields:

| Field | Type | Description |
|---|---|---|
| `verdict` | string | `PASS`, `FAIL`, or `PARTIAL` |
| `confidence` | number | Reviewer's self-assessed certainty (0.0–1.0) |
| `feedback` | string | Explanation of what is correct, wrong, missing, or improvable |
| `model_used` | string | The model that produced the review (traceability) |

---

## MCP Tool: `obfuscate`

> **Note on Tool Namespacing:** The full tool ID in Bob is `mcp__mcp-to-ai__obfuscate`. If you ever need to invoke it directly without the `/mcp-obfuscate` skill, you can ask the assistant to use the `obfuscate` tool from the `mcp-to-ai` server.

| Field | Type | Required | Description |
|---|---|---|---|
| `assistant_message` | string | ✅ | The AI-generated text to be rewritten |
| `temperature` | number | ❌ | Override the secondary LLM temperature (0.0–1.0); falls back to `OPENAI_TEMPERATURE` |

**Response** - a JSON object with five fields:

| Field | Type | Description |
|---|---|---|
| `verdict` | string | `PASS` (rewrite faithful) or `FAIL` (transformation failed) |
| `confidence` | number | Model's self-assessed certainty that the rewrite is faithful and undetectable (0.0–1.0) |
| `obfuscated` | string | The fully rewritten text; empty string on hard failure |
| `changes_summary` | string | One short paragraph describing the main categories of change made |
| `model_used` | string | The model that produced the rewrite (traceability) |

---

## Build

```cmd
mvn clean source:jar install
```

The uber-jar is produced at `target/MCP2AI-1.0.0.jar`. It contains all dependencies and is self-contained.

---

## Configuration

Copy one of the ready-made templates to `src/main/resources/config.properties`:

| Template | Provider | Copy command |
|---|---|---|
| `config.properties.openai` | OpenAI cloud | `copy src\main\resources\config.properties.openai src\main\resources\config.properties` |
| `config.properties.ollama` | Ollama (local) | `copy src\main\resources\config.properties.ollama src\main\resources\config.properties` |
| `config.properties.lmstudio` | LM Studio (local) | `copy src\main\resources\config.properties.lmstudio src\main\resources\config.properties` |
| `config.properties.example` | Generic template | `copy src\main\resources\config.properties.example src\main\resources\config.properties` |

> `config.properties` is gitignored and will never be committed. All templates are in `src/main/resources/`.

### Configuration keys

| Key | Default | Description |
|---|---|---|
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Endpoint URL of the reviewer LLM |
| `OPENAI_API_KEY` | *(none)* | API key - any non-empty string works for local servers |
| `OPENAI_MODEL` | `gpt-4o-mini` | Model used for the `review` tool |
| `OPENAI_TEMPERATURE` | `0.01` | Temperature for review calls (use `0.01` for local models that handle exact `0.0` poorly) |
| `OPENAI_TIMEOUT` | `120` | Request timeout in seconds (2 minutes); increase for slow local models |
| `OPENAI_SYSTEMPROMPT_REVIEW` | *(built-in)* | Fully qualified path to a text file overriding the built-in review system prompt |
| `OPENAI_SYSTEMPROMPT_OBFUSCATE` | *(built-in)* | Fully qualified path to a text file overriding the built-in obfuscate system prompt |
| `MCP_STREAMABLE_HOST` | `127.0.0.1` | Bind address for the embedded HTTP server |
| `MCP_STREAMABLE_PORT` | `8081` | Bind port for the embedded HTTP server |

### Resolution order

Settings are resolved in this order - the first non-empty value wins:

1. **Java system property** - `-Dkey=value` on the command line
2. **OS environment variable** - `set KEY=value` (cmd) / `$env:KEY=value` (PowerShell)
3. **`config.properties`** - file on the classpath
4. **Hard-coded default** - built-in fallback

---

## Running

### Check configuration and list available models

```cmd
java -jar target\MCP2AI-1.0.0.jar config
```

Prints all resolved configuration values (API key masked) and queries the configured endpoint for available models. Run this first to verify your setup before starting the server.

### Start the MCP server

```cmd
java -jar target\MCP2AI-1.0.0.jar streamableserver
```

The server starts on `http://<MCP_STREAMABLE_HOST>:<MCP_STREAMABLE_PORT>/mcp` (default: `http://127.0.0.1:8081/mcp`) and blocks the terminal. Connect your MCP client to that endpoint.

### Override settings inline (no config.properties edit needed)

Using Java system properties:

Using a local Ollama model:

```cmd
java -DOPENAI_BASE_URL=http://localhost:11434/v1 -DOPENAI_MODEL=llama3.2:3b -DOPENAI_API_KEY=local -jar target\MCP2AI-1.0.0.jar streamableserver
```

Using a large, powerful OpenAI cloud model:

```cmd
java -DOPENAI_BASE_URL=https://api.openai.com/v1 -DOPENAI_MODEL=gpt-5.6-sol -DOPENAI_API_KEY=sk-... -DOPENAI_TEMPERATURE=1 -jar target\MCP2AI-1.0.0.jar streamableserver
```

Using environment variables:

**Windows (cmd):**
```cmd
set OPENAI_BASE_URL=http://localhost:11434/v1
set OPENAI_API_KEY=local
set OPENAI_MODEL=llama3.2:3b
java -jar target\MCP2AI-1.0.0.jar streamableserver
```

**Windows (PowerShell):**
```powershell
$env:OPENAI_BASE_URL = "http://localhost:11434/v1"
$env:OPENAI_API_KEY  = "local"
$env:OPENAI_MODEL    = "llama3.2:3b"
java -jar target\MCP2AI-1.0.0.jar streamableserver
```

**Linux / macOS:**
```bash
export OPENAI_BASE_URL=http://localhost:11434/v1
export OPENAI_API_KEY=local
export OPENAI_MODEL=llama3.2:3b
java -jar target/MCP2AI-1.0.0.jar streamableserver
```

> `set` / `$env:` / `export` are session-scoped - variables are only active for the current terminal window.

---

## Local Server Setup

Any OpenAI-compatible local server works.

| Server | Default base URL | Config template |
|---|---|---|
| [Ollama](https://ollama.com) | `http://localhost:11434/v1` | `config.properties.ollama` |
| [LM Studio](https://lmstudio.ai) | `http://localhost:1234/v1` | `config.properties.lmstudio` |

**Ollama quick-start:**

```cmd
ollama pull llama3.2:3b
copy src\main\resources\config.properties.ollama src\main\resources\config.properties
mvn clean package
java -jar target\MCP2AI-1.0.0.jar streamableserver
```

**LM Studio quick-start:**

1. Download from [lmstudio.ai](https://lmstudio.ai) and load a model
2. Start the local server (default port 1234)
3. Copy the template and set the model name:
   ```cmd
   copy src\main\resources\config.properties.lmstudio src\main\resources\config.properties
   ```
4. Edit `config.properties` and set `OPENAI_MODEL` to the model name shown in LM Studio

---

## Cloud Provider Setup

### OpenAI

```cmd
copy src\main\resources\config.properties.openai src\main\resources\config.properties
```

Edit `config.properties` and replace `sk-...` with your real API key from [platform.openai.com/api-keys](https://platform.openai.com/api-keys). Recommended reviewer model: `gpt-4o-mini`.

### Other OpenAI-compatible providers

Any provider that exposes an OpenAI-compatible REST API works - point `OPENAI_BASE_URL` at their endpoint and set `OPENAI_API_KEY`.

| Provider | Base URL |
|---|---|
| [Azure OpenAI](https://azure.microsoft.com/en-us/products/ai-services/openai-service) | `https://<resource>.openai.azure.com/openai/deployments/<deployment>` |
| [Groq](https://console.groq.com) | `https://api.groq.com/openai/v1` |
| [Together AI](https://www.together.ai) | `https://api.together.xyz/v1` |
| [Mistral AI](https://console.mistral.ai) | `https://api.mistral.ai/v1` |
| [OpenRouter](https://openrouter.ai) | `https://openrouter.ai/api/v1` |

---

## Project Structure

```
src/main/java/edu/java/
├── MCP2AI.java                          # Entry point and command dispatcher
├── api/
│   ├── Config.java                      # Singleton config (system property → env var → config.properties → default)
│   ├── ClientFactory.java               # Builds the OpenAIClient with timeout
│   ├── OpenAIChat.java                  # LLM review and obfuscate calls + JSON response parsing
│   └── ReviewVerdict.java               # Enum: PASS | FAIL | PARTIAL
├── service/
│   ├── Server.java                      # Abstract base: MCP tool factory methods
│   └── StreamableSseServer.java         # Streamable HTTP transport via embedded Tomcat (MCP Spec 2025-03-26)
└── util/
    ├── ModelsDiscovery.java             # Lists available models with inferred capability tags
    └── SchemaBuilder.java               # JSON Schema builder for MCP tool input schemas

src/main/resources/
├── log4j2.xml                           # Logging configuration
├── SystemPrompt.Review.md               # Default review system prompt (loaded from classpath; overridable via OPENAI_SYSTEMPROMPT_REVIEW)
├── SystemPrompt.Obfuscate.md            # Default obfuscate system prompt (loaded from classpath; overridable via OPENAI_SYSTEMPROMPT_OBFUSCATE)
├── config.properties                    # Active config (gitignored)
├── config.properties.example            # Generic configuration template
├── config.properties.openai             # Ready-to-use OpenAI cloud template
├── config.properties.ollama             # Ready-to-use Ollama local template
└── config.properties.lmstudio           # Ready-to-use LM Studio local template
```

---

## Dependencies

| Artifact | Version | Purpose |
|---|---|---|
| [`io.modelcontextprotocol.sdk:mcp`](https://github.com/modelcontextprotocol/java-sdk) | 2.0.1 | MCP Java SDK core (includes `mcp-core` with built-in Streamable HTTP transport) |
| [`org.apache.tomcat.embed:tomcat-embed-core`](https://tomcat.apache.org) | 11.0.25 | Embedded Tomcat servlet container (Jakarta Servlet 6.1) for the MCP HTTP transport |
| [`com.openai:openai-java`](https://github.com/openai/openai-java) | 4.56.0 | Official OpenAI Java SDK |
| [`com.fasterxml.jackson.core:jackson-databind`](https://github.com/FasterXML/jackson-databind) | 2.22.2 | JSON serialization and LLM response parsing |
| [`org.apache.logging.log4j:log4j-core`](https://logging.apache.org/log4j/2.x/) | 2.26.1 | Logging implementation |
| [`org.apache.logging.log4j:log4j-slf4j2-impl`](https://logging.apache.org/log4j/2.x/) | 2.26.1 | SLF4J → Log4j2 bridge for SDK internals |
| [`org.apache.logging.log4j:log4j-jul`](https://logging.apache.org/log4j/2.x/) | 2.26.1 | JUL (java.util.logging) → Log4j2 bridge - routes embedded Tomcat logs through Log4j2 |
