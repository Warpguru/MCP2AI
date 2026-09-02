---
name: mcp-review
description: >-
  Review and validate AI assistant responses using the secondary reviewer LLM
  via the mcp-to-ai MCP server. Use when the user requests a second opinion,
  asks for reviewed output, mentions review mode, or uses keywords like
  /mcp-review or "with review".
metadata:
  disable-model-invocation: false
---

# MCP Review Skill

This skill guides you through answering the user's prompt and validating the response with an independent secondary LLM using the `mcp-to-ai` MCP server `review` tool.

## Workflow

### 1. Formulate the Draft Response
- Answer the user's query cleanly, accurately, and completely.
- Do not include meta-instructions or mentions of the review process within the draft response body itself.

### 2. Invoke the Review Tool
- Call the `mcp__mcp-to-ai__review` tool with:
  - `user_message`: The user's original query text (clean and unadorned).
  - `assistant_message`: The complete draft answer formulated in Step 1.
  - `temperature`: (Optional) pass temperature if specifically requested by user.

### 3. Handle the Review Verdict
The tool returns:
- `verdict`: `PASS`, `PARTIAL`, or `FAIL`
- `confidence`: Confidence score (typically 0.0 to 1.0)
- `feedback`: Detailed review comments and evaluation from the reviewer model
- `model_used`: Name of the LLM performing the review

#### Verdict Handling:
- **`PASS`**: The response meets quality, accuracy, and relevance standards. Present the answer followed by the review summary.
- **`PARTIAL`**: The response is largely acceptable but has areas for improvement or minor discrepancies. Present the answer, highlight the reviewer's caveats, and refine or clarify any points raised in the feedback.
- **`FAIL`**: The response was rejected, deemed inaccurate, or the reviewer model encountered an issue. If actionable feedback is provided, adjust and explain the correction, or clearly present the reviewer's dissenting perspective and feedback.

### 4. Format the Output
Deliver the final response structured cleanly as follows:

```markdown
<Primary Answer / Content>

---

### Secondary LLM Review
- **Verdict:** `PASS` | `PARTIAL` | `FAIL`
- **Confidence:** `<confidence score>`
- **Model Used:** `<model_used>`
- **Feedback:**
  > <feedback text>
```
