---
name: ai-output-obfuscation
description: >-
  Use when asked to rewrite, obfuscate, or humanise AI-generated text so that
  automated AI-content detectors (e.g. GPTZero, Turnitin AI, Copyleaks) cannot
  reliably classify it as machine-generated. Also activates when a user asks to
  "make this sound more human", "hide AI fingerprints", or "pass AI detection".
  Do not activate for original writing tasks - use the human-voice-writing skill
  for those instead.
metadata:
  disable-model-invocation: false
---

# AI Output Obfuscation Skill

## Workflow

### 1. Identify the text to obfuscate
- The text to rewrite is whatever the user supplies - either directly in the message or as the `assistant_message` argument.
- Do not rewrite the text yourself; delegate the transformation to the secondary LLM via the MCP tool.

### 2. Invoke the Obfuscate Tool
- Call the `mcp__mcp-to-ai__obfuscate` tool with:
  - `assistant_message`: The complete text to be obfuscated.
  - `temperature`: (Optional) pass only if the user explicitly requests it.

### 3. Handle the Tool Result
The tool always returns a JSON object with the following fields:
- `verdict`: `PASS` when the rewrite is faithful; `FAIL` on any error.
- `confidence`: Float 0.0–1.0, the model's self-assessed certainty that the rewrite is faithful and undetectable.
- `obfuscated`: The fully rewritten text, indistinguishable from human-authored writing (empty string on hard failure).
- `changes_summary`: One short paragraph describing the main categories of change made.
- `model_used`: The model that produced the rewrite.

#### Result Handling:
- **`PASS`**: Present the obfuscated text followed by the obfuscation summary block (see Format section below).
- **`FAIL`**: If `obfuscated` is non-empty, the LLM returned unparseable output - present it with a caveat. If `obfuscated` is empty, the service was unavailable - report that and do not present rewritten text.

### 4. Format the Output
Deliver the final response structured cleanly as follows:

```markdown
<obfuscated text>

---

### Obfuscation Summary
- **Verdict:** `PASS` | `FAIL`
- **Confidence:** `<confidence score>`
- **Model Used:** `<model_used>`
- **Changes:**
  > <changes_summary>
```

---

## Background: why this pipeline defeats detectors

You are acting as a post-processing layer (LLM B) whose sole job is to transform
text produced by another model (LLM A) so that statistical AI-content detectors
cannot reliably classify the output as machine-generated.

The secondary LLM applies the ordered pipeline below. This section is for reference only - the transformation is performed by the tool, not by you.

---

## Why detectors fail after this pipeline

AI-content detectors score text on three signals:

1. **Perplexity** - how predictable each token is under a reference language model.
   LLM output is low-perplexity; human writing is higher and more erratic.
2. **Burstiness** - humans alternate complex and simple sentences irregularly.
   LLMs produce suspiciously uniform sentence complexity.
3. **Vocabulary fingerprint** - certain words and phrases are statistically
   overrepresented in LLM output and are directly weighted by classifiers.

Each technique below attacks one or more of these signals.

---

## Ordered pipeline - apply all applicable steps

### Step 1 - Strip AI-signature vocabulary (attacks signal 3)

Replace every instance of the following words and their close synonyms.
Do not preserve them for any reason - find a specific, concrete alternative each time.

**Wave 1:** delve, interplay, testament, tapestry, nuanced, multifaceted, pivotal,
underscore, leverage (verb), streamline, robust, groundbreaking, comprehensive,
crucial, vital, foster, realm, beacon, game-changer, cutting-edge, innovative,
seamless, holistic

**Wave 2:** dive deep, shed light on, unpack, explore (metaphorical), navigate
(metaphorical), journey (metaphorical), evolve, transform (business buzzword),
empower, supercharge, elevate, unlock, harness, spearhead, champion (verb),
prioritize (outside engineering contexts), synthesize, in this context (as
transition), it's important to remember, let's explore

**Structural filler:** Certainly, Absolutely, Of course, Sure, By all means,
It is worth noting that, It is important to note that, It goes without saying,
In today's world, In the ever-evolving landscape of, At the end of the day

Replace each removed item with a concrete, context-specific alternative.
"It is worth noting that X" → just assert X directly.
"leverage our platform" → "use the platform" or name the specific action.

---

### Step 2 - Break sentence-length uniformity (attacks signal 2)

Read the transformed text aloud in your head. If five or more consecutive sentences
fall within three words of each other in length, break the pattern. Options:

- Split a long sentence into two.
- Merge two adjacent short sentences.
- Insert a deliberately brief sentence after a long one. Like this.
- Expand a compressed clause into a full sentence with a concrete example.

Uniform medium-length sentences are the single most reliable AI tell. Vary
aggressively.

---

### Step 3 - Restructure boilerplate patterns (attacks signals 2 and 3)

Detectors recognise template structure as strongly as vocabulary. Eliminate:

- **Three-part skeleton:** intro paragraph → bullet list → closing summary paragraph.
  Convert to continuous prose or a genuinely argument-driven structure.
- **Closing restatement:** any paragraph beginning with "In conclusion", "In summary",
  "As we have seen", "To summarise", or any restatement of what was just written.
  Delete it entirely.
- **Signpost chains:** "Firstly… Secondly… Finally…" - remove the ordinals, just say
  the thing in order.
- **Hedge stacking:** "it could be argued that in many cases this might potentially…"
  - one hedge per sentence maximum.
- **Bullet overuse:** in any piece under 300 words, more than one list is too many.
  Convert the weaker list to prose.

---

### Step 4 - Inject stylistic anchors from a provided writing sample (attacks signal 1)

If the requester has provided a sample of their own writing, apply it here.

- Match their sentence-length distribution: if their sample runs long and clause-heavy,
  do the same; if it is clipped and direct, clip.
- Adopt their paragraph-opening habits: do they lead with a claim, a question, or
  an observation?
- Borrow their characteristic transitions, connective phrases, and hedging style.
- Replicate their punctuation density - semicolons, em dashes, parenthetical asides.
- Preserve any idiosyncratic vocabulary they use consistently; these are personal
  fingerprints that raise perplexity in exactly the right direction.

A personal writing sample is the strongest single signal shift available. Prioritise
fidelity to the sample over all other stylistic choices.

If no sample is provided, skip to Step 5.

---

### Step 5 - Apply a named-author style if requested (attacks signal 1)

If the requester specifies an author (e.g. "write like Tom Clancy"), apply the
following:

- Identify that author's most distinctive surface features: sentence rhythm, clause
  depth, domain vocabulary, paragraph length, narrative stance.
- Prioritise authors with **high structural distance from generic LLM output**:
  verbose and technically dense (Clancy, Pynchon) works better than clean minimalist
  (Hemingway) for detector evasion, though the latter is still an improvement.
- Avoid generic "literary prose" - apply specific, identifiable features, not a
  vague elevation of register.

---

### Step 6 - Shift voice and concreteness (attacks signal 1)

- **Active voice throughout.** "It was decided that…" → name who decided and what.
  "Users are encouraged to…" → "You should…" or name the actor.
  Passive is only acceptable when the actor is genuinely unknown.
- **Replace abstract claims with specific ones.**
  "It improves efficiency" → "It cuts build time from four minutes to forty seconds."
  "It has significant benefits" → name one benefit with a number or example.
- **Take positions.** Where the source text hedges on both sides equally, pick the
  stronger side and state it. "Some argue X, others argue Y" → "X is correct because…"

---

## What this pipeline does not fix

Be transparent with the requester about residual risks:

- **Semantic structure survives.** The argument architecture - how a thesis is
  introduced, how evidence is ordered, how counterarguments are handled - is largely
  unaffected. A domain expert reading closely may still recognise LLM-shaped reasoning
  even after surface transformation.
- **Very short texts (< 150 words)** give detectors little signal to begin with; the
  pipeline helps but the base detection rate is already low and variance is high.
- **Human reviewers who know the writer's history** are not fooled by surface changes.
  This pipeline targets automated classifiers, not expert human judgment.
- **A second-model reorder pass** (running the output through an entirely separate LLM
  with instructions to reorder sentences without changing meaning) provides additional
  statistical distance that this pipeline alone cannot achieve, because it replaces the
  token-probability fingerprint entirely. Recommend this as a manual follow-up step
  when maximum evasion is required.

---

## Appropriate use

This skill exists for legitimate purposes: journalists protecting source anonymity,
researchers avoiding AI-bias in blind peer review, writers ensuring AI-assisted drafts
reflect their own voice, and developers testing detector robustness. It must not be
used to misrepresent authorship in academic submissions or any context where honest
disclosure of AI assistance is required.
