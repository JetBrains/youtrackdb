<!-- MANIFEST
dimension: workflow-prompt-design
scope: step
target: "Track 2, Step 1 (commit d8c1ad4ec5) — create-plan Step 4b dual-clean authoring loop"
findings:
  - {id: WP1, sev: Recommended, loc: ".claude/skills/create-plan/SKILL.md:751-765", anchor: "### WP1 ", cert: n/a, basis: "track-path auditor slicing undefined — single (target_path,range) pair gives the orchestrator no deterministic partition of N track files into fan-out slices"}
  - {id: WP2, sev: Minor, loc: ".claude/skills/create-plan/SKILL.md:781-817", anchor: "### WP2 ", cert: n/a, basis: "comprehension-gate re-open vs inner-loop iteration_budget interaction is stated only by cross-reference; whether a gate re-open consumes a budget round is not local"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### WP1 [Recommended] Track-path auditor slicing is undefined — non-reproducible fan-out

- **File:** `.claude/skills/create-plan/SKILL.md` (lines 751-765, the per-round `readability-auditor` paragraph)
- **Axis:** deterministic decision rules / sub-agent delegation
- **Cost:** non-reproducible LLM behavior on the track surface — different orchestrator runs partition the track files differently, changing each slice's anchor visibility and therefore the readability findings.
- **Issue:** Item 9 spawns the `readability-auditor` as a "range-sliced fan-out, one spawn per slice" with "a slice `range` per spawn," and leans on `edit-design/SKILL.md` § Step 4 for the spawn mechanics. But the `edit-design` auditor contract and the `readability-auditor` agent definition both model a slice as a single `target_path` plus a single `range` (one ~200-line window in one file, output `Range audited: <target_path>:<start>-<end>`). On the design path that is unambiguous: there is exactly one file, `design.md`, sliced by line range. On the **track path** the audited surface is N track files (`readability-auditor.md § Inputs` even notes `target_path` becomes "the track files under `plan_dir`" — plural files behind a singular key). Neither item 9, nor `edit-design` § Step 4, nor `design-review.md § Track-scoped cold-read` (which explicitly defers — "the auditor's track-surface wiring lands when `create-plan` Step 4b spawns it") states how the orchestrator turns N files into per-spawn `(target_path, range)` pairs: one spawn per track file, a line-window within each file, or a ~200-line window that may straddle files. The orchestrator LLM is left to invent the partition, and a fan-out whose unit of work is undefined is non-reproducible.
- **Suggestion:** Add one sentence to item 9's auditor bullet fixing the track-path slice unit, e.g.: *"On the track path, slice per track file: one `readability-auditor` spawn per `plan/track-N.md`, its params file carrying `target_path=<that track file>` and a whole-file `range`; the standing anchors (Component Map + that track's `## Purpose / Big Picture`) supply the cross-file vocabulary a single-file slice lacks."* — or whatever partition is intended, but state it so the fan-out is deterministic.

### WP2 [Minor] Comprehension-gate re-open vs inner-loop budget is stated only by cross-reference

- **File:** `.claude/skills/create-plan/SKILL.md` (lines 781-817, the `iteration_budget` and comprehension-gate paragraphs)
- **Axis:** deterministic decision rules
- **Cost:** a borderline reader could read the gate↔inner-loop re-entry as resetting rather than consuming the budget, risking a perceived unbounded loop on a load-bearing termination rule.
- **Issue:** The inner loop is "bounded by `iteration_budget` (default 3)," and item 9 deliberately restates that cap so "the loop carries its own termination rather than borrowing it across skills" — a good self-containment move. But a decision-shaped comprehension-gate finding "re-opens the S3 gate and re-enters the inner loop," and the closing sentence ("exits when the inner loop is dual-clean **and** the comprehension gate passes, or the budget is spent") does not say locally whether a gate re-open consumes a budget round or starts a fresh count. The single shared-budget reading is the correct one and is settled in `edit-design/SKILL.md` § Step 6, but the delta took care to localize the cap precisely so the loop is self-terminating — the one remaining termination edge (gate re-open) still relies on the cross-skill read.
- **Suggestion:** Add a half-sentence where the re-open is described, e.g. *"a comprehension-gate re-open consumes a round of the same `iteration_budget`,"* so the loop's termination is fully local, consistent with the cap-restatement rationale already in the paragraph.

## Evidence base
