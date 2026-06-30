<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: A1, sev: should-fix, loc: ".claude/workflow/design-decision-escalation.md:62", anchor: "### A1 ", cert: C1, basis: "Phase-C-loaded cap-3 assertion describing the very loop being uncapped; outside the track's in-scope set and uncatchable by the track-code-review.md-only grep"}
  - {id: A2, sev: should-fix, loc: ".claude/workflow/code-review-protocol.md:53", anchor: "### A2 ", cert: C2, basis: "two Phase-C-loaded 'max 3 iterations per level' assertions for the shared/track loop; one routes to the carved §Limits, one (preamble) does not; outside in-scope set"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 2}
cert_index:
  - {id: C1, verdict: SURVIVES-WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: SURVIVES-WEAK, anchor: "#### C2 "}
  - {id: C3, verdict: SURVIVES, anchor: "#### C3 "}
  - {id: C4, verdict: SURVIVES, anchor: "#### C4 "}
  - {id: C5, verdict: SURVIVES, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [should-fix]
**Certificate**: C1 (Scope/in-scope-set-completeness challenge)
**Target**: Track scope — `## Interfaces and Dependencies` in-scope file SET; Validation acceptance criterion "no file ships self-contradictory text"; design.md §"The full restate set" grep.
**Challenge**: The track's in-scope set is four files, and its cross-file restate mechanism is one grep scoped to `track-code-review.md` only (`grep -nE '...' .claude/workflow/track-code-review.md`). `design-decision-escalation.md` §Per-phase autonomy line 62 states `Phase C: track-level code review (up to 3 iterations; treats medium and high step ranges as focal points)`. That section's TOC row carries `phases=3A,3B,3C`, so it loads in a Phase-C context. It describes the exact loop this track uncaps, asserts the `up to 3 iterations` cap as live behavior, and carries no override pointer. The track neither lists this file nor restates it, so after the track lands a Phase-C reader who loads `design-decision-escalation.md` reads "up to 3 iterations" for the track loop — the self-contradictory text the track's own acceptance criterion forbids. The single-file grep cannot catch it by construction.
**Evidence**: `.claude/workflow/design-decision-escalation.md:62` (assertion) and `:10` (TOC row `phases=3A,3B,3C`). Track file `## Interfaces and Dependencies` (in-scope set = 4 files; `design-decision-escalation.md` absent) and `## Validation and Acceptance` ("No cap-3-keyed site ... still asserts a fixed `/3` cap as live behavior"). design.md:341 (the restate grep targets `track-code-review.md` alone).
**Proposed fix**: Add `design-decision-escalation.md` §Per-phase autonomy to the in-scope set (a fifth file) with a one-line restate: drop the bare `up to 3 iterations` for Phase C and point to the `track-code-review.md` §Review loop / per-track-complexity-tag termination, mirroring the §Limits carve-out. Alternatively, widen the restate mechanism from a single-file grep to a tree-scoped grep (`grep -rnE '3 iterations|of 3|three iteration' .claude/workflow .claude/skills`) and triage every Phase-C-loading hit, so the policy is not silently contradicted by files the planner did not enumerate.

### A2 [should-fix]
**Certificate**: C2 (Scope/in-scope-set-completeness challenge)
**Target**: Track scope — in-scope file SET; Validation acceptance criterion "no file ships self-contradictory text".
**Challenge**: `code-review-protocol.md` carries two cap-3 assertions that load in a Phase-C context and describe the track-level loop. Line 53 ("Max 3 iterations per level.") sits in the file preamble (before the first `## ` TOC-anchored heading), which the §1.8(d) TOC protocol reads in full for everyone who opens the file; its "per level" explicitly spans the track level this change uncaps, and it carries no override pointer. Line 97 ("The iteration protocol (max 3 iterations, ...)") sits in §Iteration protocol with `phases=3A,3B,3C`. Neither line is in the track's in-scope set, and the single-file grep over `track-code-review.md` cannot reach them. After the track lands, a Phase-C reader loading this protocol file reads an uncarved "Max 3 iterations per level."
**Evidence**: `.claude/workflow/code-review-protocol.md:53` (preamble, "Max 3 iterations per level."), `:9` and `:94-99` (§Iteration protocol, `phases=3A,3B,3C`, "max 3 iterations"). The §Iteration-protocol line defers to `review-iteration.md ... §Limits` — the home the track does carve (track edit 6) — so a reader following that pointer lands on the override; the preamble line 53 has no such pointer. Track `## Interfaces and Dependencies` (file absent from in-scope set).
**Proposed fix**: Lower-cost than A1 because line 97 already routes to the now-carved §Limits. Add `code-review-protocol.md` line 53 to the in-scope set with a one-line restate ("the track-level loop's iteration policy is keyed to the per-track complexity tag — see `track-code-review.md` §Review loop / `review-iteration.md` §Limits"), or fold it into the same tree-scoped-grep widening proposed in A1's fix. Line 97's inline "max 3 iterations" parenthetical should at minimum be softened to "per the shared iteration protocol (Phase C overrides — see §Limits)" so the descriptor does not flatly assert the cap for the track level.

## Evidence base

#### C1 [Scope challenge — in-scope-set completeness: design-decision-escalation.md]
- **Chosen approach**: In-scope set = 4 files (`track-code-review.md`, `review-agent-selection.md`, `review-iteration.md` §Limits, `code-review/SKILL.md`). Cross-file cap-3 sites in `track-code-review.md` are caught by a grep scoped to that one file (design.md:341, track edit 3); the two other dial-mapping sites are listed by hand (track edits 4-5).
- **Best rejected alternative**: A tree-scoped cap-3 grep (`grep -rnE '3 iterations|of 3|three iteration' .claude/workflow .claude/skills`) feeding a triage of every Phase-C-loading hit, plus an explicit fifth in-scope file.
- **Counterargument trace**:
  1. The change's stated acceptance bar is "no file ships self-contradictory text" (track `## Validation and Acceptance`; design.md Overview/§Scope). The mechanism that enforces it for the dominant file is a single-file grep; the two non-grep dial sites were found by manual enumeration over a closed set the planner already knew.
  2. A whole-tree grep (`grep -rnE '3 iterations|Max 3|of 3|three iteration' .claude/workflow .claude/skills .claude/agents`) returns `design-decision-escalation.md:62` = `Phase C: track-level code review (up to 3 iterations; treats medium and high step ranges as focal points)`.
  3. That row's TOC entry (`design-decision-escalation.md:10`, §Per-phase autonomy) is `phases=3A,3B,3C` — it loads in Phase C. It names the track-level code-review loop and asserts `up to 3 iterations` with no override pointer. The track does not list or restate it.
  4. Outcome: after the track lands, the live workflow holds a Phase-C-loaded statement that the track loop is capped at three, directly contradicting the uncapped policy. The single-file grep is structurally unable to surface it.
- **Codebase evidence**: `.claude/workflow/design-decision-escalation.md:62`, `:10`; design.md:341 (grep target = `track-code-review.md` only); track-1.md `## Interfaces and Dependencies` (4-file set, this file absent).
- **Survival test**: WEAK. The decision to scope at four files survives as the dominant footprint, but the *completeness* of the in-scope SET does not: a genuine Phase-C-loaded cap-3 assertion is left un-restated, and the enforcement mechanism cannot find it. Real (not theoretical) leftover self-contradiction → should-fix.

#### C2 [Scope challenge — in-scope-set completeness: code-review-protocol.md]
- **Chosen approach**: Same 4-file in-scope set; same single-file grep + manual two-site enumeration.
- **Best rejected alternative**: Same tree-scoped grep + triage as C1.
- **Counterargument trace**:
  1. The tree grep also returns `code-review-protocol.md:53` (`Max 3 iterations per level.`) and `:97` (`The iteration protocol (max 3 iterations, ...)`).
  2. Line 53 is in the file preamble (before the first `## ` heading at line 57); §1.8(d) makes preamble universally read. "per level" includes the track level. No override pointer.
  3. Line 97 is in §Iteration protocol (TOC `phases=3A,3B,3C` at `:9`); it loads in Phase C, but it explicitly defers to `review-iteration.md ... §Limits` — the home the track carves (edit 6) — so a reader following the pointer reaches the override. Its inline parenthetical "max 3 iterations" still reads as a flat cap before the pointer is followed.
  4. Outcome: a Phase-C reader loading this protocol file sees an uncarved "Max 3 iterations per level" (line 53). Partially mitigated for line 97 by its §Limits pointer; not at all for line 53.
- **Codebase evidence**: `.claude/workflow/code-review-protocol.md:53`, `:94-99`, `:9`; track-1.md in-scope set (file absent).
- **Survival test**: WEAK. Same class as C1, one notch softer because line 97 routes to the carved §Limits. Line 53's flat preamble assertion is unmitigated. should-fix.

#### C3 [Assumption test — no-progress signal is constructible off the existing verdict stream]
- **Claim** (D4 / D4.1): No-progress detection reads off the gate-check verdict stream the loop "already emits"; identity by reviewer `id`, threshold = `STILL OPEN` for every carried finding + zero net clears + no new fixable finding. Adds no new measurement machinery.
- **Stress scenario**: Demand the orchestrator construct, per iteration, (a) per-finding identity, (b) carried-vs-cleared, and (c) "new fixable finding" — without any signal the existing verdict handling does not emit.
- **Code evidence**: `review-iteration.md:106-161` §Gate verification output + §Gate-check verdict handling — the gate-check emits one verdict per carried finding (`VERIFIED`/`REJECTED`/`MOOT`/`STILL OPEN`/`REGRESSION`) keyed by reviewer `id` (identity ✓; carried-vs-cleared ✓: VERIFIED/REJECTED/MOOT clear, STILL OPEN carries). `prompts/dimensional-review-gate-check.md:65-77,96-99,114-115` — new findings are emitted only at severity `blocker`/`should-fix` (the template forbids `suggestion` new findings and only blocker/should-fix new findings force a FAIL), so "new fixable finding" is exactly "new blocker/should-fix" and is read directly off the New findings block. `REGRESSION` already forces FAIL (`review-iteration.md:160`), matching D4.1's "escalates immediately."
- **Verdict**: HOLDS. Every component of the threshold maps 1:1 onto a verdict the stream already emits; no new machinery is required. No finding.

#### C4 [Violation scenario — uncapped `low` blocker loop with no should-fix backstop loops forever]
- **Invariant claim**: With the cap removed, an unfixable blocker on a `low` track (which has no should-fix iteration as a backstop) loops indefinitely.
- **Violation construction**:
  1. Start state: `low` track, iteration 1 (full review) raises one blocker the implementer cannot fix.
  2. Action sequence: iteration 2 gate-check returns `STILL OPEN` for that blocker, clears nothing, surfaces no new blocker/should-fix.
  3. Per D4.1 threshold (`review-iteration.md` verdict stream), this is no-progress → orchestrator escalates to the user instead of looping again.
  4. Violation point: none — the loop exits at iteration 2 via no-progress escalation. The design's "First iteration" edge case (design.md:229-231) makes iteration 1 ineligible (no carried findings) and iteration 2 the earliest no-progress point.
- **Feasibility**: INFEASIBLE. `low`'s lack of a should-fix backstop is irrelevant: blockers are the only `low` driver, and no-progress fires the first time a blocker iteration clears nothing. The "loops forever" scenario the challenge needs cannot be constructed. No finding.

#### C5 [Violation scenario — Phase-C uncapping leaks into Phases 2/3A/3B]
- **Invariant claim**: The §Limits carve-out or the dial re-key bleeds the uncapped behavior into the non-Phase-C loops that share `review-iteration.md` §Limits.
- **Violation construction**:
  1. Start state: a Phase-2 plan review or Phase-3A track review loads `review-iteration.md` §Limits.
  2. Action sequence: the carve-out sentence (track edit 6 / D2.1) is scoped "Phase-C track code review overrides this per `track-code-review.md` §Review loop"; the default "Max 3 iterations per review type / escalate" is preserved for Phases 2/3A/3B (track edit 6, design.md:320-331). The dial site itself is `track-code-review.md` §Review loop, `phases=3C`.
  3. Violation point: none — the override is gated on Phase-C track code review by name; nothing in the carve-out or dial site re-points the shared default for the other phases.
- **Feasibility**: INFEASIBLE — given the carve-out is wired exactly as D2.1/edit-6 specify (default preserved, override phase-named). Caveat: this is contingent on the implementer writing edit 6 as planned; the *plan* contains no leak. No finding.
