<!--
MANIFEST
dimension: workflow-context-budget
target: track-1
iteration: 1
range: 4d3962c97441218d8a78272e92f18b83955bef37..HEAD
evidence_base: certs: 0
cert_index: []
flags: evidence-trail-exempt (reason a: no refutation or certificate phase to persist)
findings:
  - id: WB1
    sev: Critical
    anchor: "#wb1-comprehension-review-description-over-the-500-char-always-loaded-cap"
    loc: docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/comprehension-review.md:3
    cert: n/a
    basis: char-count of the description: frontmatter field (517)
  - id: WB2
    sev: Recommended
    anchor: "#wb2-absorption-check-description-over-the-350-char-target"
    loc: docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/absorption-check.md:3
    cert: n/a
    basis: char-count of the description: frontmatter field (389)
  - id: WB3
    sev: Recommended
    anchor: "#wb3-design-author-description-over-the-350-char-target"
    loc: docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/design-author.md:3
    cert: n/a
    basis: char-count of the description: frontmatter field (366)
  - id: WB4
    sev: Recommended
    anchor: "#wb4-readability-auditor-description-over-the-350-char-target"
    loc: docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/readability-auditor.md:3
    cert: n/a
    basis: char-count of the description: frontmatter field (390)
-->

## Findings

### WB1 [Critical] comprehension-review description over the 500-char always-loaded cap

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/comprehension-review.md` (line 3)
- **Axis:** always-loaded
- **Cost:** 517-char `description:` field; ~130 tokens injected into every session at the Phase 4 promotion, permanently.
- **Issue:** Agent `description:` fields are the always-loaded surface — once promoted to live `.claude/agents/`, every session pays this string on every turn forever, regardless of whether the agent is ever spawned. The 517-char description is over the 500-char Critical cap. It packs the full keep-list ("the comprehension questions, the structural findings, and the whole-doc human-reader checks (navigability and the structural half of audience-fit)") plus the parenthetical tool-rationale ("Grep only to resolve `**Full design**` link targets and to read cited house-style sections"). That detail is discriminative load the body already carries verbatim (the agent's `## Who you are` and `## Reading rules` repeat it); the description needs only enough to route a spawn. The cost is deferred — the file is staged under `_workflow/staged-workflow/`, so it is NOT in the live agent tree today (confirmed: no live counterpart) — but it is the right thing to trim now, before promotion bakes it into the per-session baseline.
- **Suggestion:** Cut to ≤250 chars. Drop the keep-list enumeration and the tool-rationale parenthetical; keep the role + when-to-invoke discriminator, e.g. *"De-warmed cold comprehension-and-structure gate for `design.md` and track files: reads only the document, runs the comprehension questions, structural findings, and whole-doc human-reader checks. No research log, no prose AI-tell axis. Runs once as the outer gate after the dual-clean inner loop."* (≈260 chars; trim further as needed). The dropped detail already lives in the body.

### WB2 [Recommended] absorption-check description over the 350-char target

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/absorption-check.md` (line 3)
- **Axis:** always-loaded
- **Cost:** 389-char `description:` field; injected every session at promotion.
- **Issue:** Over the 350-char Recommended threshold (target ≤250). The description restates the two-way-matching mechanism ("confirms every load-bearing log decision appears as a seed decision record while no record invents a decision the log lacks") and the model pin ("model sonnet") that the `model:` frontmatter already declares — duplicate of a structured field. Same deferred-cost framing as WB1: staged now, always-loaded after promotion.
- **Suggestion:** Trim to ≤250 chars. Drop the "model sonnet" clause (the `model:` field is canonical and machine-read) and compress the mechanism to one clause: *"Warm per-round absorption check for `design.md` and track files: two-way coverage match between the research log's load-bearing decisions and the draft's decision records. Runs every round of the dual-clean inner loop beside the cold auditor."* (≈230 chars).

### WB3 [Recommended] design-author description over the 350-char target

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/design-author.md` (line 3)
- **Axis:** always-loaded
- **Cost:** 366-char `description:` field; injected every session at promotion.
- **Issue:** Over the 350-char Recommended threshold. The description carries the full reading-source list ("reads the research log and the live codebase through PSI, never the authoring conversation") and the by-reference contract ("Returns a thin summary only, never the drafted content") — both are load-bearing body content (`## What you read`, `## By-reference return contract`) and only the routing-relevant gist needs to live in the always-loaded description.
- **Suggestion:** Trim to ≤250 chars, keeping the discriminator (code-grounded author, never the conversation) and the spawner cue; move the source list and the never-return-the-draft clause to the body it already occupies. e.g. *"Code-grounded author for `design.md` and track files: grounds on the research log and live code via PSI, never the authoring conversation, and drafts for a reader who has only the finished document. Spawned by edit-design and create-plan Step 4b."* (≈245 chars).

### WB4 [Recommended] readability-auditor description over the 350-char target

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/readability-auditor.md` (line 3)
- **Axis:** always-loaded
- **Cost:** 390-char `description:` field; injected every session at promotion.
- **Issue:** Over the 350-char Recommended threshold. The description enumerates ownership detail ("Owns the prose AI-tell axis and the prose half of the human-reader checks") and the implementation note ("Range-sliced fan-out reusing the readability-feedback audit contract; Read and Grep only") that the body and the `tools:` field already carry. "Read and Grep only" duplicates the `tools:` frontmatter.
- **Suggestion:** Trim to ≤250 chars. Drop the "Read and Grep only" clause (duplicates `tools:`) and compress the ownership note: *"Cold readability auditor for `design.md` and track files: reads house-style and the document slice only, never the research log, and reports every passage a mid-level developer cannot reconstruct from the document alone. Range-sliced fan-out."* (≈245 chars).

## Evidence base
