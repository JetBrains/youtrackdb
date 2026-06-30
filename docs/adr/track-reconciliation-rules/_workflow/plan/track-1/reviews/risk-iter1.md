<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: R1, sev: should-fix, anchor: "### R1 [should-fix]", loc: ".claude/workflow/code-review-protocol.md:53", cert: "Exposure: cap-3-keyed sites outside the restate-set grep", basis: assumption}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {kind: exposure, subject: "no-progress termination control flow on the uncapped loops", residual: LOW}
  - {kind: exposure, subject: "cap-3-keyed sites outside the restate-set grep", residual: MEDIUM}
  - {kind: assumption, subject: "restate-set grep enumerates every cap-3-keyed site that ships self-contradictory text", verdict: CONTRADICTED}
  - {kind: assumption, subject: "§Limits carve-out routes the review-agent-selection.md cross-reference reader to the override", verdict: VALIDATED}
  - {kind: assumption, subject: "medium single shared counter gates only should-fix continuation", verdict: VALIDATED}
  - {kind: testability, subject: "every constraint is grep/Read-verifiable as claimed", feasibility: ACHIEVABLE}
flags: [CONTRACT_OK]
-->

# Risk review — Track 1 (Phase-C review iteration keyed to the complexity tag), iteration 1

**Verdict: 1 should-fix.** The control-flow re-key itself is sound: every uncapped
loop passes through the no-progress diamond, `REGRESSION` short-circuits to an
immediate `FAIL`, iteration 1 cannot register no-progress, and the resume re-reads
loop state — so the removed cap-3 safety valve has a working replacement and no
unbounded-loop hazard survives. The `medium` single-shared-counter rule (D3.1) and
the §Limits carve-out wiring (D2.1) both check out against the live files. The one
risk: the restate set is keyed to a grep scoped to `track-code-review.md` alone,
but a second Phase-C-loading file — `code-review-protocol.md` — carries a
standalone "Max 3 iterations per level" assertion that the new uncapped track-level
policy contradicts and that no carve-out redirects. After the edit it ships
self-contradictory live text.

**Tooling.** Workflow-machinery (Markdown) change under `.claude/workflow/**` and
`.claude/skills/**`. No Java symbols in scope, so mcp-steroid PSI does not apply;
the five prose criteria (rule coherence / non-contradiction, instruction
completeness, prompt-design soundness, context-budget impact, breakage of
dependent prompts) supersede the Java/WAL criteria per the workflow-machinery
clause. Every reference was verified as a workflow file path or `§`-anchor via
`grep` / `Read`. No reference-accuracy caveat is warranted.

**Staged-read precedence.** Ledger carries `s17=workflow-modifying`, but Phase 3
has not run, so `_workflow/staged-workflow/` is absent — every `.claude/...` read
resolved to the LIVE develop-state file per §1.7(d), the correct current-state
anchor.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — cap-3-keyed sites outside the restate-set grep;
Assumption — "the restate-set grep enumerates every cap-3-keyed site that ships
self-contradictory text" (CONTRADICTED).
**Location**: Track `## Plan of Work` edit 3 + `## Interfaces and Dependencies`
(In-scope files) and design.md §"The full restate set"; the affected live file is
`.claude/workflow/code-review-protocol.md:53` (and the pointer at line 97).
**Issue**: The restate set is the output of a grep scoped to one file:
`grep -nE '…' .claude/workflow/track-code-review.md`. A second file that the
Phase-C orchestrator is explicitly directed to read for track-level review carries
a cap-3 assertion the new policy invalidates and that the restate set does not
touch. `code-review-protocol.md` is a Phase-C-loading file (§Single-step tracks is
`phases=3C`; §Where each level is implemented `3B,3C` directs the orchestrator to
`track-code-review.md` for track-level review; §Iteration protocol `3A,3B,3C`).
Line 53, "Max 3 iterations per level.", sits in the file's pre-TOC intro region
(read in full per §1.8(d) when no TOC row matches) immediately after the prose
that covers **both** levels ("At a **high** step… At a **track**, the full
baseline group runs"). "Per level" therefore spans the track level. After this
track lands, the track-level Phase-C loop is uncapped (blocker loop at every level,
`high` should-fix uncapped), so "Max 3 iterations per level" is false for the track
level and the file ships text that contradicts the authoritative
`track-code-review.md` §Review loop. This is the exact failure D2.1 guards against
for `review-iteration.md` §Limits — an off-site cap-3 assertion a Phase-C reader
lands on — left unguarded for a second file. Likelihood the change is *defeated*:
low, because the orchestrator runs the loop from `track-code-review.md` §Review
loop, the unambiguous dispatch point, not from this descriptive sentence. Impact if
a reader anchors on line 53: a `high`/blocker track capped at 3 against the design's
intent. Net: a meaningful self-contradiction, not a loop-breaking control-flow gap
— should-fix. (Line 97's "max 3 iterations … see review-iteration.md" is a
*pointer* into §Limits, which D2.1 carves out, so a reader following it is
redirected to the override — line 97 is mitigated; line 53 is the standalone hit.)
**Proposed fix**: Add `code-review-protocol.md` to the in-scope set and the restate
plan. Minimal restate: at line 53 qualify the assertion to the step level or route
it through the §Limits carve-out — e.g., "Max 3 iterations per level (the Phase-C
**track**-level loop overrides this per `track-code-review.md` §Review loop — keyed
to the per-track complexity tag, no fixed cap, terminated by no-progress
detection)." Alternatively, broaden edit 3's grep to run over the Phase-C-loading
file set (`track-code-review.md`, `code-review-protocol.md`) rather than
`track-code-review.md` alone, and restate every hit. Cross-check the `code-review`
skill mirror (`code-review/SKILL.md:225`, already in-scope) for the same "cap-3"
phrasing so both stay consistent.

## Evidence base

#### Exposure: no-progress termination control flow on the uncapped loops
- **Track claim**: D4 / D4.1 replace the cap-3 escalation safety valve with
  no-progress detection on the uncapped loops (blocker loop at all levels, `high`
  should-fix), reading the existing gate-check verdict stream; D3 makes the blocker
  loop uncapped at every level, with `low` relying solely on no-progress detection.
- **Critical path trace** (the new loop, design.md §"The new Phase-C review loop"
  flowchart + D4.1):
  1. Enter loop → read reconciled tag from ledger; missing tag → `medium` safe
     default (flowchart `B -->|missing tag| MED`).
  2. Run reviewers (iteration 1 = full review, not a gate-check; design.md
     §No-progress detection edge case "First iteration" → cannot register
     no-progress; earliest no-progress is iteration 2).
  3. Blocker/`REGRESSION` open? → if yes, `Gate-check made progress?` diamond →
     `no` routes to ESC (escalate), `yes` spawns the fix implementer and re-runs
     the gate-check (flowchart `LP/MP/HP/HSP -->|no| ESC`).
  4. `REGRESSION` is progress-negative and forces an immediate `FAIL` per
     `review-iteration.md:155-161` ("A `REGRESSION` forces the iteration `FAIL`"),
     so it never waits for the no-progress check (D4.1 threshold bullet 3).
  5. Resume across a context pause re-reads loop state, so no-progress spans the
     resume (D4.2 + design.md §No-progress detection edge case "Resume across the
     pause").
- **Blast radius**: an unbounded loop on an unfixable blocker (loops across
  sessions forever) if the no-progress gate failed to bound a path. Traced: every
  uncapped path (`LP`, `MP`, `HP`, `HSP` in the flowchart) routes through a
  `Gate-check made progress?` diamond whose `no` edge escalates. `medium`
  should-fix stays cap-3-bounded on the shared counter (D3.1), so it needs no
  no-progress gate; once a `medium` blocker carries the loop past 3, the blocker
  loop's no-progress gate governs (D4.1 "Which loops it gates").
- **Existing safeguards**: the gate-check verdict stream
  (`review-iteration.md:126-127, 134-161`, verdicts `VERIFIED`/`REJECTED`/`MOOT`/
  `STILL OPEN`/`REGRESSION`) already exists; no new measurement machinery. The
  per-iteration context-consumption check (`track-code-review.md:813-831`) is
  unchanged and composes on an orthogonal axis (D4.2).
- **Residual risk**: LOW — every uncapped path has a defined escalation edge, the
  termination signal is an existing stream, and the first-iteration / resume edges
  are covered. No unbounded-loop hazard survives in the prose as written.

#### Exposure: cap-3-keyed sites outside the restate-set grep
- **Track claim**: edit 3 restates "every cap-3-keyed site the uncapping touches,"
  authority = `grep -nE '3 iterations|N/3|/3|of 3|three iteration'
  .claude/workflow/track-code-review.md`; the In-scope set is four files
  (`track-code-review.md`, `review-agent-selection.md`, `review-iteration.md`
  §Limits, `code-review/SKILL.md`).
- **Critical path trace**: `code-review-protocol.md` §Where each level is
  implemented (`phases=3B,3C`) routes the Phase-C orchestrator to
  `track-code-review.md` for track-level review; the file's intro region (lines
  16-53, no TOC row → read in full per §1.8(d)) covers both step and track levels
  and closes with "Max 3 iterations per level." (line 53). After the track lands
  the track-level loop is uncapped → line 53 contradicts the live policy.
- **Blast radius**: a Phase-C reader anchoring on line 53 caps a `high`/blocker
  track at 3, defeating the change's convergence intent. Bounded by the fact that
  the loop is actually run from `track-code-review.md` §Review loop (the dispatch
  point), so this is a stale cross-statement rather than the operative control flow.
- **Existing safeguards**: the authoritative dispatch lives in
  `track-code-review.md` §Review loop, which the track does restate; line 97's
  pointer redirects to `review-iteration.md` §Limits where D2.1 adds the carve-out.
  Only line 53's *standalone* assertion is unguarded.
- **Residual risk**: MEDIUM — self-contradictory live text in a Phase-C-loading
  file, the same failure mode D2.1 fixed for §Limits, left open for a second file.
  → R1.

#### Assumption: the restate-set grep enumerates every cap-3-keyed site that ships self-contradictory text
- **Track claim**: design.md §"The full restate set" — "The complete set is the
  output of this grep" (scoped to `track-code-review.md`), plus two named
  dial-mapping files outside the grep.
- **Evidence search** (Grep — Markdown, PSI N/A): `grep -rlnE 'single shallow
  pass|iterate to convergence|cap.3|Max 3 iteration|3 iterations|of 3 iteration'
  .claude/workflow .claude/skills`, then per-file `grep -nE` + `Read` of each
  Phase-C-loading hit.
- **Code evidence**: `.claude/workflow/code-review-protocol.md:53` ("Max 3
  iterations per level.") and `:97` ("max 3 iterations … see review-iteration.md");
  TOC at `:5-11` shows §Single-step tracks `3C`, §Where each level is implemented
  `3B,3C`, §Iteration protocol `3A,3B,3C` — all Phase-C-loading. The intro region
  `:30-53` covers both step and track levels. This file is neither in the grep
  scope nor in the In-scope set.
- **Verdict**: CONTRADICTED
- **Detail**: a cap-3 assertion that the new track-level policy invalidates exists
  in a Phase-C-loading file the restate set does not reach. Out-of-scope files were
  re-checked and confirmed genuinely out of scope: `track-review.md` (Phase A,
  `phases=3A`), `structural-review.md` / `implementation-review.md` (Phase 2),
  `step-implementation.md:424` ("3 iterations within the orchestrator's context" —
  step-level), and `risk-tagging.md` (per-*step* risk-tag `low/medium/high`, a
  different axis). Only `code-review-protocol.md` is the gap. → R1.

#### Assumption: the §Limits carve-out routes the review-agent-selection.md cross-reference reader to the override
- **Track claim**: D2.1 — wire the carve-out into `review-iteration.md` §Limits so
  a Phase-C reader who lands there via the `review-agent-selection.md` rigor-dial
  cross-reference is routed to the override.
- **Evidence search** (Grep/Read): `review-agent-selection.md` §"Complexity sets
  the Phase-C rigor dial" cross-reference target; `review-iteration.md` §Limits TOC
  phase coverage.
- **Code evidence**: `review-agent-selection.md:239-242` points to
  `review-iteration.md … §Limits`; `review-iteration.md:7,35` shows §Limits with
  `phases=2,3A,3B,3C` (loads in Phase C). Edit 6 adds the carve-out to §Limits.
- **Verdict**: VALIDATED
- **Detail**: §Limits does load in Phase C and is the cross-reference target, so
  the planned carve-out reaches the reader the design names. The wiring is sound;
  the gap is the *second* off-site assertion (R1), not this one.

#### Assumption: the `medium` single shared counter gates only should-fix continuation
- **Track claim**: D3.1 — keep the single shared counter; gate "should-fix drives a
  new iteration" on `iteration ≤ 3`; do not gate "a surviving blocker drives a new
  iteration"; introduce no second counter.
- **Evidence search** (Grep/Read): `track-code-review.md:832-847` (shared-counter
  accounting) and the design.md §"The `medium` shared counter".
- **Code evidence**: `track-code-review.md:834` — "The iteration count is shared
  across all review dimensions (not independent counters)"; the flowchart
  `MS{Should-fix open AND iteration <= 3?}` gates only the should-fix edge while
  `MB{Blockers or REGRESSION open?}` is ungated.
- **Verdict**: VALIDATED
- **Detail**: the existing single-counter fact holds in the live file; D3.1 reuses
  it and gates one edge. No new counter, no contradiction with the live mechanic.

#### Testability: every constraint in `## Invariants & Constraints` is grep/Read-verifiable as claimed
- **Coverage target**: prose change — verified by Phase-C workflow-review agents +
  grep/Read, not Java tests (85/70 line/branch is N/A; this maps to "is each
  constraint mechanically checkable as the track claims").
- **Difficulty assessment**: each of the five constraints names its verification
  (consistency review; re-running the restate grep; reading §Limits; reading the
  `review-agent-selection.md` "never drops a domain-selected reviewer" sentence).
  All are grep- or Read-decidable.
- **Existing test infrastructure**: the restate-set grep (the constraint's own
  acceptance check) and the workflow-review agent panel (consistency,
  instruction-completeness, prompt-design, context-budget, writing-style).
- **Feasibility**: ACHIEVABLE — with one caveat folded into R1: the "no
  cap-3-keyed site still asserts a fixed `/3` cap as live behavior" constraint, as
  written, scopes its grep to `track-code-review.md` and so would pass even with
  line 53 of `code-review-protocol.md` left contradictory. Broadening that
  constraint's grep to the Phase-C file set (R1's proposed fix) makes the
  constraint catch the gap it currently misses.
