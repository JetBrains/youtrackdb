<!--
MANIFEST
dimension: workflow-prompt-design
review_file_schema: §2.5
findings: 4
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - { id: WP1, sev: Recommended, anchor: "### WP1 [Recommended]", loc: "prompts/create-final-design.md (Step 2, lines 29-78)", cert: n/a, basis: judgment }
  - { id: WP2, sev: Minor, anchor: "### WP2 [Minor]", loc: "prompts/create-final-design.md (Step 3, lines 79-95)", cert: n/a, basis: judgment }
  - { id: WP3, sev: Minor, anchor: "### WP3 [Minor]", loc: "track-review.md (§Track-scoped adversarial review, lines 754-760)", cert: n/a, basis: judgment }
  - { id: WP4, sev: Minor, anchor: "### WP4 [Minor]", loc: "prompts/consistency-review.md (Tier guard, lines 60-70)", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Recommended]
File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md` (Step 2, lines 29-78), Axis: clean-context invocation / deterministic decision rules, Cost: a clean-context `final-designer` agent in `lite`/`minimal` tries to Read a `design.md` that does not exist before it ever reads the tier.

**Issue.** Track 2 makes Phase 4 per-tier: Step 3 (line 81) keys artifact production off the tier, and `lite`/`minimal` have no `design.md` (stated explicitly at lines 87-88, 142-147, 377-378). But **Step 2's reading list (line 37) still says unconditionally** `read docs/adr/<dir-name>/_workflow/design.md — original design document (do NOT modify)`, and Step 2 runs *before* Step 3 reads the tier. The only conditional the reading list carries is the `track-skip` carve-out for deleted track files (lines 46-47); there is no design-absent branch. A sub-agent spawned with no conversation history executes Step 1 → Step 2 in order, and in a `lite`/`minimal` run the Step-2 `Read` of `design.md` hits a missing file before the agent has any signal that the file is expected to be absent. Every other axis in this track gained a per-tier guard (the create-final-design Step 3 table, the consistency/structural design-presence guards); the Step 2 read list is the one place the design-presence conditional was not propagated.

**Suggestion.** Add a tier/design-presence guard to the Step 2 reading list, mirroring the `track-skip` carve-out already there. For example, gate the `design.md` bullet: "`docs/adr/<dir-name>/_workflow/design.md` — original design document (do NOT modify). **`full`-tier only — absent in `lite`/`minimal`; skip this read when the file does not exist (read the tier line in `implementation-plan.md` first if unsure).**" This keeps Step 2 self-determining without forcing the tier read up into Step 1.

### WP2 [Minor]
File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md` (Step 3, lines 79-95), Axis: deterministic decision rules, Cost: a borderline "which subsection runs" judgment is left to the agent where an explicit pointer would remove it.

**Issue.** Step 3 line 94-95 says "The sub-sections below are written for `full`; apply the tier table above to decide which run." The per-artifact guards (Artifact 1 `**full-tier only**` at line 142, Artifact 2 `**full/lite only**` at line 416, the `### Minimal tier` section at line 374) make this resolvable, but the bridge sentence leaves the agent to map "the tier table" onto the unlabeled intervening sub-sections itself. The `## Adversarial gate verdicts` fold sub-section (lines 296-303, 322-334) is the one that is *not* per-artifact-guarded inline and runs in `full` *and* `lite` (line 339-341 says so) but lands in the PR description under `minimal` — a reader applying "written for `full`, decide which run" to it has to cross-reference three places.

**Suggestion.** Tag the verdict-fold sub-section header the same way the artifacts are tagged (e.g. a one-line `**Runs in `full`/`lite` (folds into `adr.md`); under `minimal` see §"Minimal tier: PR-description verdict fold"**` note under the `## Adversarial gate verdicts` heading), so each Step-3 sub-section carries its own tier disposition rather than relying on the agent to re-derive it from the table.

### WP3 [Minor]
File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/track-review.md` (§Track-scoped adversarial review, lines 754-760), Axis: sub-agent delegation / output contract, Cost: the model-pin instruction is unambiguous for `full`/`lite` but leaves the spawn's effort field to an unstated session default with no fallback assertion.

**Issue.** The D14 model/effort pin (lines 754-760) tells the orchestrator to set the Agent `model` field by tier (`full` → Fable 5, `lite` → Opus 4.x) and acknowledges the xhigh-effort half "rides the session default" because no per-spawn effort field exists. As a prompt-to-an-orchestrator this is honest about the degradation, but it gives the spawning agent no positive instruction for what to do about effort — it neither says "do not attempt to set effort" nor "verify the session is running at xhigh." An orchestrator reading this may either ignore effort (the intended behavior) or invent an effort-setting step that the Agent surface does not support. The decision is documented as accepted (line 758-760), so this is polish, not a correctness gap.

**Suggestion.** Add one clause making the no-op explicit: "Do not attempt to set a per-spawn effort field — none exists; the spawn carries only the `model` field." This converts the caveat from a rationale-only note into an actionable instruction.

### WP4 [Minor]
File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/consistency-review.md` (Tier guard, lines 55-76), Axis: deterministic decision rules, Cost: the `minimal` branch's "lightens to a track-vs-code check" wording is resolvable but states the axis-narrowing in two registers the agent must reconcile.

**Issue.** The tier guard (lines 60-70) describes `minimal` two ways: the bullet at line 65-70 says "additionally skip the **plan-content cross-check** ... Compare track + code only (the **PLAN ↔ CODE** axis lightens to a track-vs-code check)," and the PLAN ↔ CODE section body (delta lines 238-244) names the exact bullets to skip (first, second, fourth) and run (third). These agree, but the guard's summary phrase "lightens to a track-vs-code check" and the body's "skip bullets 1/2/4, run bullet 3" are two encodings of the same rule placed ~180 lines apart; a clean-context agent reads the guard first and forms an interpretation before reaching the precise bullet list. This is the most precise of the three tier guards in the prompt and is unlikely to misfire, hence Minor.

**Suggestion.** In the line 65-70 `minimal` bullet, point forward to the precise rule rather than paraphrasing it: "... skip the plan-content cross-check (the PLAN ↔ CODE axis runs only its track-reference bullet — see that axis below for exactly which bullets drop)." This makes the guard's summary defer to the single authoritative bullet list instead of re-stating the narrowing.

## Evidence base
