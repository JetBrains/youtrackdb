<!--
MANIFEST
dimension: workflow-prompt-design
agent: review-workflow-prompt-design
iteration: 1
scope: track
target_range: 01b13bfd642b48c498c85007cfd7014c370fd2d4..HEAD
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
findings:
  - { id: WP1, sev: Recommended, anchor: "wp1-structural-review-staged-read", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md:128", cert: n/a, basis: judgment }
  - { id: WP2, sev: Minor, anchor: "wp2-conventions-1-7l-wording-lag", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions.md:1435", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Recommended] structural-review staged-read block left on the develop-era `### Constraints` read while its paired sibling moved to ledger-first

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md` (line 128)
- **Axis:** clean-context invocation / tooling routing
- **Cost:** the Phase-2 structural reviewer reads `### Constraints` for the staging signal where its paired consistency reviewer reads the ledger, and routes a `.claude/scripts/**` read to the live tree where a staged copy exists — phantom-mismatch risk on a workflow-modifying plan
- **Issue:** The **Staged-read precedence (workflow-modifying plans)** block in `prompts/structural-review.md` is byte-identical to the live develop file: it still says *"When the plan's `### Constraints` carries the canonical `§1.7(b)` workflow-modifying marker sentence"* and lists only the three-prefix path set (`.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`). A staged-tree sweep confirms this is the **only** staged-read block in the whole staged subtree that was not re-pointed — every other consumer (the standalone block in `consistency-review.md`, the `technical`/`risk`/`adversarial` prompts, the two gate-recheck prompts, `step-implementation.md`, `track-code-review.md`) now reads ledger-first with the four-prefix path set including `.claude/scripts/**`. `consistency-review.md` and `structural-review.md` are the paired Phase-2 `reviewer-plan` and run together; they now disagree on both the read source (ledger `s17` vs plan `### Constraints`) and the path set. The block is load-bearing here: the prompt takes `Workflow rules: {workflow_path}` as an input (line 125) and reviews workflow-modifying plans, so on a `lite`/`full` workflow-modifying branch that staged a `.claude/scripts/**` file, this reviewer reads the live script and reports a phantom mismatch against develop — the exact failure D14 closes for the other consumers. This is the same within-file/within-family coherence fix Step 1's episode records making for co-resident marker blocks ("leaving those on `### Constraints` would have left each file self-contradictory"); it was not extended to this prompt because the marker-read sweep (Step 1/Step 2) did not enumerate `prompts/structural-review.md`, and Step 3's scope for that file was the `minimal` pass-skip plus bloat-check adaptation.
- **Suggestion:** Re-point this block to match its sibling `consistency-review.md` (delta confirms the exact target text): *"When the branch is in §1.7(b) staging mode — read ledger-first: the phase ledger's `s17` field (`_workflow/phase-ledger.md`, last value wins) equals the workflow-modifying token; when no `phase-ledger.md` exists … fall back to the plan's `### Constraints` … — resolve every read of a `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, or `.claude/scripts/**` file through `§1.7(d)` …"*. Since the file is already staged this is a direct edit to the staged copy.

### WP2 [Minor] staged `conventions.md` §1.7(l) still describes the criteria-switch trigger as the plan `### Constraints` "either marker," lagging the ledger-first consumers it specs

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions.md` (line 1435-1442)
- **Axis:** deterministic decision rules (spec-vs-consumer divergence)
- **Cost:** the normative spec a maintainer or reviewer consults to understand the (l) trigger describes a read source the consumer prompts no longer use; no runtime sub-agent is misdirected, because the operative instruction lives in each prompt and is correct
- **Issue:** §1.7(l) reads *"The three Phase-3A criteria-switch blocks … fire when the plan's `### Constraints` carries **either** the (b) workflow-modifying marker prefix **or** the (k) opt-out marker prefix."* The three prompts those blocks live in (`technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`) were re-pointed in Step 1 to read **ledger-first** (the `s17` field equals the workflow-modifying token **or** the opt-out token), with `### Constraints` only as the pre-ledger fallback. So §1.7(l)'s trigger description now contradicts the consumers it governs on read source. This is the seam the Step-1 episode flagged and deliberately left out of scope (Track 2's `conventions.md` carve-out is the §1.7(c) read-side only). From a prompt-design lens the impact is bounded: the three review prompts are self-contained — each carries its complete ledger-first "Workflow-machinery criteria" block inline and does not cross-reference §1.7(l) at the decision point — so a reviewer in clean context following its own system prompt resolves the right read source. The divergence is between the spec and its consumers, not a decision rule that an LLM executes wrongly. It is recorded here for the Phase-C disposition the track's `## Surprises & Discoveries` already names (a Step-3-style touch or a small plan correction).
- **Suggestion:** When the §1.7(c)-only carve-out is lifted in Phase C (or by plan correction), re-frame §1.7(l)'s trigger to "fire when the branch is in §1.7(b) staging mode **or** the §1.7(k) opt-out mode — read ledger-first per §1.7(c), with the plan `### Constraints` 'either marker' as the pre-ledger fallback," mirroring the consumer wording. No edit is required within this track's declared scope; keep it as the flagged Phase-C item.

## Evidence base
