<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
<!--
MANIFEST
dimension: workflow-prompt-design
target: docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md
range: b4351093be~1..b4351093be
findings: 2
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WP1, sev: Recommended, anchor: "### WP1 [Recommended]", loc: "SKILL.md:350-372,615-634", cert: n/a, basis: judgment }
  - { id: WP2, sev: Minor, anchor: "### WP2 [Minor]", loc: "SKILL.md:237-242", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Recommended]
- **File**: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 350-372 and 615-634)
- **Axis**: sub-agent delegation
- **Cost**: non-reproducible spawn behavior — a clean-context orchestrator may pass the prompt file by path-reference instead of by full content, leaving the spawned reviewer without its own system prompt, and has no stated channel for the D14 model pin
- **Issue**: Both reviewer spawns in this step name *what* to spawn and *which inputs* to pass, but never the spawn *mechanism*. Step 4 part 2 says "The gate spawns the existing `reviewer-adversarial` in its research-log scope (`prompts/adversarial-review.md` …)"; item 9 says "spawn the existing cold-read sub-agent (`prompts/design-review.md`) with `target=tracks`". Neither names the `Agent` tool, the `subagent_type`, or the rule that the spawned agent's `prompt` must be *the full content of the prompt file* with the inputs substituted into its Inputs block. The sibling `edit-design/SKILL.md` (live, lines 413-424 and 465-481) spells this out for the identical pair of reviewers: `subagent_type: general-purpose`, `description: "…"`, `prompt: the full content of .claude/workflow/prompts/<file>.md`, then the literal substitution block. A fresh LLM reading create-plan in isolation has to infer that recipe from the sibling skill. `reviewer-adversarial` / `reviewer-design` are TOC-protocol role names that resolve the prompt's phase, not registered `subagent_type`s (no such agent files exist under `.claude/agents/`), so "spawn the existing `reviewer-adversarial`" is not directly actionable. The D14 model pin is the sharpest exposure: with `subagent_type: general-purpose` there is no obvious surface to set `full → Fable 5` / `lite|minimal → Opus 4.x`. The SKILL's own degradation note ("If no per-spawn model surface is available, the split lands via the reviewer agent's frontmatter") points at an agent frontmatter that does not exist and is not staged — so the prose describes a fallback that cannot fire, leaving the LLM with no concrete way to honor or knowingly skip the pin. The input *contracts* are sound (the `### Research-log Inputs` and `target=tracks` Inputs blocks in the two staged prompts match the SKILL's `Spawn the reviewer with these inputs` / `Pass:` lists exactly), and the file-vs-inline output handling is correctly stated; only the mechanism is missing.
- **Suggestion**: At the Step 4 part 2 gate spawn and at item 9, add the same `Agent`-tool recipe the live `edit-design` uses — name the tool, `subagent_type: general-purpose`, a `description`, and `prompt: the full content of <prompt path>` with the inputs substituted into the prompt's Inputs block. For the D14 pin, either name the concrete parameter the spawn sets (e.g. the `Agent` tool's `model` field, mapping `full → fable`, `lite|minimal → opus`, plus the explicit xhigh-effort surface) or, if the harness genuinely has no per-spawn model field, replace the dangling "reviewer agent's frontmatter" fallback with the actual surface that carries the pin so the instruction is executable rather than aspirational.

### WP2 [Minor]
- **File**: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 237-242)
- **Axis**: deterministic decision rules
- **Cost**: a false affordance — the empty fenced block reads as "a command follows" when none does, and the actual create action (a `Write`) is left unspecified at the point the LLM acts
- **Issue**: Step 2 says "write the **research log's `## Initial request`** … as the first durable Phase-0 action:" and immediately opens a `bash` fence whose entire content is the comment `# directory already exists from Step 1b (mkdir -p .../plan)`. The colon-then-fence shape signals an executable step, but the block executes nothing; the file-creation instruction is in the prose *after* the fence ("Create `…/research-log.md` with the five sections …") with no indication of the tool to use. A clean-context LLM may treat the empty fence as an incomplete instruction (where is the command?) or run the no-op comment as if it were the step.
- **Suggestion**: Drop the empty `bash` fence and fold its only fact into the prose, e.g. "The `_workflow/plan/` directory already exists from Step 1b, so write the log directly: create `docs/adr/<dir-name>/_workflow/research-log.md` (a `Write`, not a shell command) with the five sections …". This removes the false affordance and names the create mechanism at the point of action.

## Evidence base

(Evidence-trail-exempt: no refutation or certificate phase. No `#### C<n>` entries.)
