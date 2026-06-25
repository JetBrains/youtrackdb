<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
  - id: WP1
    sev: Critical
    anchor: "### WP1 "
    loc: ".claude/agents/readability-auditor.md:90-93 (vs create-plan/SKILL.md:815-819, edit-design/SKILL.md:648-651)"
    cert: "n/a"
    basis: read
  - id: WP2
    sev: Recommended
    anchor: "### WP2 "
    loc: ".claude/agents/readability-auditor.md:31,33,93; edit-design/SKILL.md:700-705"
    cert: "n/a"
    basis: read
  - id: WP3
    sev: Minor
    anchor: "### WP3 "
    loc: ".claude/agents/readability-auditor.md:97; edit-design/SKILL.md:899-913"
    cert: "n/a"
    basis: read
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### WP1 [Critical] — agent guard reads two params the track-path and design-sync spawns never pass, with no rule for the absent case

- **File:** `.claude/agents/readability-auditor.md` (lines 90-93), against the two non-design-creation auditor spawn sites `.claude/skills/create-plan/SKILL.md` (lines 815-819) and `.claude/skills/edit-design/SKILL.md` (lines 648-651).
- **Axis:** clean-context invocation / deterministic decision rules.
- **Cost:** non-reproducible LLM behavior — on a track-path or design-sync spawn the cold agent reads a params file missing `slice_count`/`total_lines`, and the prompt gives no deterministic rule for that case, so the whole-doc guard fires, no-ops, or errors at the model's discretion.

**Issue.** The agent now treats `slice_count` and `total_lines` as unconditional inputs. The `## Inputs` block lists both with no "present only on …" qualifier (lines 90-91), and the closing instruction is imperative and unconditional: *"Apply the whole-doc guard … the moment you have read them: when `slice_count == 1 AND total_lines > ~300`, flag the wiring error and stop"* (line 93). `## Who you are` is the same shape: *"You receive two slicing-metadata params, `slice_count` and `total_lines`"* (line 31).

But the `readability-auditor` is spawned in three modes, and only one passes these params:

- **Design-path creation-kind fan-out** (`edit-design` Step 4, lines 681-682) — passes `target=design`, `target_path`, `range`, `slice_count`, `total_lines`. Guard is computable. Correct.
- **Track-path fan-out** (`create-plan` Step 4b item 9, lines 815-819) — passes `target=tracks`, `target_path` (one track file), whole-file `range`. **No `slice_count`, no `total_lines`.** Confirmed by `grep -n "slice_count\|total_lines"` over `create-plan/SKILL.md` returning zero matches.
- **`design-sync` single prose pass** (`edit-design` lines 648-651) — passes `target=design`, `target_path`, a whole-document `range`. **No `slice_count`, no `total_lines`.**

A cold sub-agent has only its system prompt plus the params file; it cannot know "this is the track path, so the missing fields are expected." The prompt as written instructs it to evaluate `slice_count == 1 AND total_lines > ~300` against fields that are not there. The `design-sync` case is the sharp one: that spawn is one whole-document slice over a `design.md` that is routinely > 300 lines, which is exactly the `slice_count == 1 AND total_lines > ~300` shape the guard treats as a `blocker` wiring error — yet it is a legitimate single-pass spawn, not a collapse. If a model substitutes a plausible default for the absent fields (e.g. infers `slice_count = 1` from being the only spawn and reads `total_lines` off the document), the guard raises a false `blocker` on every `design-sync` run.

This is a localized per-prompt-surface defect: the agent's input contract is unconditional while two of its three callers supply a subset. The track file's S1 invariant claims the new params are "constant across the round's fan-out" but never states the guard is inert when the params are absent, and the agent prompt — the thing the LLM actually executes — carries no branch for it.

**Suggestion.** Make the guard's applicability an explicit, testable condition in the agent prompt. Add to the `## Inputs` block (and mirror in `## Who you are` § The whole-doc guard): *"`slice_count` and `total_lines` are present only on the design-path creation-kind fan-out spawn. When the params file does not carry both fields (the track-path per-file fan-out and the `design-sync` single pass omit them), the whole-doc guard does not apply — proceed with the normal audit. Apply the guard only when both `slice_count` and `total_lines` are present."* Equivalently, have the two non-design-creation spawn sites pass the fields explicitly (e.g. track path passes `slice_count` = number of track files, `total_lines` = that track file's length; `design-sync` passes `slice_count=1` and the doc length but with the guard suppressed) — but the prompt-side guard rule is cheaper and removes the ambiguity at the point the agent decides.

### WP2 [Recommended] — agent-side `blocker` guard keys on a fuzzy `~300` threshold

- **File:** `.claude/agents/readability-auditor.md` (lines 31, 33, 93); the floor is also stated approximate in `.claude/skills/edit-design/SKILL.md` (lines 700-705).
- **Axis:** deterministic decision rules.
- **Cost:** non-reproducible behavior on borderline inputs — two runs on a ~300-line doc can disagree on whether the agent emits a `blocker`.

**Issue.** The whole-doc guard is a binary, `blocker`-emitting decision: *"When `slice_count == 1 AND total_lines > ~300`, that is a wiring error: flag it"* (line 31), restated at line 93. The `~` makes the comparison approximate. For the orchestrator's window-sizing (`~200`-line window, `~6` cap in `edit-design` Step 4) an approximate target is fine — it is a soft sizing knob. But the agent-side guard is a hard pass/fail gate that raises a `blocker` finding, and an LLM asked to evaluate `total_lines > ~300` on a 305-line or 290-line doc has no deterministic answer. Borderline collapse cases (a single slice over a doc just above the floor) are precisely where the secondary detector is supposed to catch what the orchestrator self-check missed, so fuzziness at the boundary blunts the backstop.

**Suggestion.** Give the agent-side guard an exact integer comparison: *"When `slice_count == 1 AND total_lines > 300, …"* (drop the `~` in the guard's two occurrences, lines 31 and 93, and in the short-doc carve-out at line 33). Keep the `~` on the orchestrator's window/cap targets where it correctly signals a soft sizing rule. A one-line note that 300 is the exact guard floor while 200/6 stay approximate window targets keeps the two threshold styles from reading as an inconsistency.

### WP3 [Minor] — cold-read note points the agent at an orchestrator-only `§ Step 6` it can neither read nor act on

- **File:** `.claude/agents/readability-auditor.md` (line 97); target is `.claude/skills/edit-design/SKILL.md` § Step 6 "The canonical convergence mechanism" (lines 899-913).
- **Axis:** clean-context invocation.
- **Cost:** mild — a cross-reference in the agent prompt to a mechanism the agent has no role in and (by its TOC role/phase filter) will not open; harmless but adds a dangling pointer to the prompt the LLM reads.

**Issue.** The new cold-read note tells the agent its settled-state *"lives entirely with the orchestrator (`edit-design/SKILL.md` § Step 6, the canonical convergence mechanism)"* (line 97). The intent — reassure the agent it receives no settled-state and must audit fully cold — is correct and well-stated. The reference itself is the minor wrinkle: the auditor's role is `reviewer-readability` and the convergence mechanism is an orchestrator-only procedure; the agent will never (and should never) open Step 6 to act on it. The pointer reads as actionable when it is purely explanatory provenance.

**Suggestion.** Demote the reference to plainly non-actionable provenance so a cold agent does not treat it as a doc to fetch: *"… lives entirely with the orchestrator (it is defined in `edit-design` Step 6; you neither read nor act on it)."* The substantive instruction at line 97 — audit cold every spawn, the orchestrator filters your returned findings — is already complete without the agent needing the Step 6 content, so this only sharpens that the pointer is FYI, not a read target.

## Evidence base
