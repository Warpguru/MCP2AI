# AGENTS.md — Agent Mode (Coding)

## Critical Before Any Work

- **Run env scripts first**: `D:\Development\SetupEnvJava21.cmd` then `D:\Development\SetupEnvMaven.cmd` — without these, `mvn` and `java` resolve to wrong versions
- **Validate build after every change**: `mvn package` must succeed before moving on

## Code Patterns

- All MCP code lives in `edu.java.mcp.*`; utilities in `edu.java.util.*`; config/client in `edu.java.api.*`
- `edu.java.MCP2AI` is the single dispatcher — every new example must be registered there with its CLI command string in the `switch` inside `process()`
- `main()` is the only `static` method allowed in `MCP2AI` — all other methods must be instance methods
- Every new example class needs a **single logger**: `private static final Logger logger = LogManager.getLogger(MyClass.class)` — use `logger.info(...)` for all human-facing output (it renders plain on the console via `ConsoleAppender`) and `logger.debug/error(...)` for diagnostics (file only). Do **not** add a second `Sysout` logger.
- All public and private methods, constants, and classes must have Javadoc

## OpenAI SDK `com.openai:openai-java:4.52.0`

Client construction: `OpenAIOkHttpClient.builder().apiKey(Config.getApiKey()).baseUrl(Config.getBaseUrl()).build()`

Key call patterns:
- Chat: `client.chat().completions().create(params)`
- Streaming: `client.chat().completions().createStreaming(params)` — returns `StreamResponse<ChatCompletionChunk>`
- Embeddings: `client.embeddings().create(params)`
- Vision: build a `List<ChatCompletionContentPart>` and call `.contentOfArrayOfContentParts(list)` — no `addContentPart()` method exists
- TTS: `client.audio().speech().create(params)` — `Voice` is a sealed union, use `.voice("nova")` not `Voice.NOVA`
- STT: `client.audio().transcriptions().create(params)` — model takes `AudioModel`, returns sealed `TranscriptionCreateResponse`; call `.asTranscription().text()`
- Image gen: `client.images().generate(params)` — use `ImageModel.DALL_E_2` from `com.openai.models.images.ImageModel`; use `.size("1024x1024")` — `512x512` is not valid for `gpt-image-1` (the default model). `gpt-image-1` returns Base64 (`b64_json`), not a URL; decode with `Base64.getDecoder().decode(b64)` and write to disk.
- Moderation: `client.moderations().create(params)` — `Moderation.categories()` is not `Optional`, and its flag getters return primitive `boolean`

When an SDK class name is uncertain, inspect the sources jar via PowerShell (see root AGENTS.md for the full technique).

## Config Keys (all 9)

`OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_EMBEDDING_MODEL`, `OPENAI_REASONING_MODEL`, `OPENAI_TTS_MODEL`, `OPENAI_STT_MODEL`, `OPENAI_IMAGE_MODEL`, `OPENAI_MODERATION_MODEL`

Use the typed getters on `Config` (e.g. `Config.getModel()`) — never call `Config.get("OPENAI_MODEL")` directly from examples.

## New Resources Must Be Registered

Any new file under `src/main/resources/` must be added to the `<includes>` block in `pom.xml`'s `<resources>` section or it will be silently excluded from the jar.
