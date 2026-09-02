# AGENTS.md - Plan Mode

## Non-Obvious Architectural Constraints

- **Single dispatcher pattern**: `edu.java.MCP2AI.process()` is the only command router - new examples register a CLI command string there via a `switch` case; do not create separate entry points
- **`main()` constraint**: `main()` is the only `static` method allowed in `MCP2AI`; all other methods must be instance methods - this is an explicit project rule
- **Uber-jar via shade plugin**: classpath-relative resource loading (`config.properties`) works because shade merges everything into one jar - do not plan solutions that assume a `libs/` directory at runtime
- **Config priority**: env vars override `config.properties`; `OPENAI_REASONING_MODEL` silently falls back to `OPENAI_MODEL` if unset - plans for reasoning features must account for both cases
- **Log4j2 + shade = plugin cache issue**: `Log4j2PluginCacheFileTransformer` from `log4j-transform-maven-shade-plugin-extensions:0.2.0` must remain as a shade plugin `<dependency>` - removing it breaks logging silently in the uber-jar
- **pom.xml `<includes>` is exhaustive**: only `log4j2.xml` and `config.properties` are included from `src/main/resources/` - any plan adding new classpath resources must also add them to this list
- **OpenAI SDK**: `com.openai:openai-java:4.52.0` - official first-party SDK; all request/response objects use immutable builders; API names must be verified from the sources jar before planning (see root AGENTS.md)
- **Version sync**: `MCP2AI.MCP2AI_VERSION` and `pom.xml` version must be kept in sync manually - there is no build-time substitution
