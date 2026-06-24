<!-- MANIFEST
role: reviewer-adversarial
phase: 3A
track: "Track 1: Ledger substate primitive, dual-path resolution, wrap-fix, tests, grammar"
iteration: 1
verdict: should-fix
findings: 3
blockers: 0
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: ".claude/scripts/workflow-startup-precheck.sh roster_scan wrap fix; track-1.md Plan of Work step 6.3 + lines 205-206"
    cert: "Violation scenario: S5 wrap-fixed fallback correctness (join terminator)"
    basis: "code"
  - id: A2
    sev: suggestion
    anchor: "### A2 "
    loc: "track-1.md Validation 'Dual-path parity'; Invariant S3"
    cert: "Assumption test: dual-path parity test is non-vacuous"
    basis: "code"
  - id: A3
    sev: suggestion
    anchor: "### A3 "
    loc: ".claude/scripts/workflow-startup-precheck.sh ledger_tail_value_for_track; Invariant S1"
    cert: "Violation scenario: S1 track-scoped read leaks a prior track's terminal substate"
    basis: "code"
evidence_base:
  challenges: 0
  violation_scenarios: 4
  assumption_tests: 2
notes: >
  Workflow-modifying §1.7-staged branch (ledger s17=workflow-modifying, nothing
  staged yet) — challenged against the LIVE workflow-startup-precheck.sh, its
  tests, and conventions*.md. Narrowed per D9 + track-1 exception: D1/D2 design
  decisions not re-challenged (research-log adversarial gate passed iter3); the
  cross-track-episode challenge is dropped (Track 1 is the first track). No Java
  in scope, so mcp-steroid was not probed; symbol claims are grep+Read with the
  reference-accuracy caveat noted where it matters. The bash glob and ledger-read
  behaviors in the certificates were verified by running the actual idioms, not
  reasoned about.
-->

# Adversarial review — Track 1 (iteration 1)

Verdict: **should-fix**. The track is independently mergeable while dormant, the
~4-file footprint holds, and the S1/S2/S6 invariants and the emit-order safety
invariant survive construction. One realization defect needs fixing before
implementation (a join-terminator glob the track states in a form that never
fires, plus a comment that wrongly certifies it as correct bash), and two
suggestions harden tests the track already mandates in prose.

## Findings

### A1 [should-fix]
**Certificate**: Violation scenario — S5 wrap-fixed fallback correctness (join terminator)
**Target**: Invariant S5 / Plan of Work step 6.3 (the `roster_scan` wrap fix)
**Challenge**: The track tells the implementer to terminate the continuation-line
join at `[0-9]*". "` (Plan of Work step 6.3) and asserts at track lines 205-206
that this is "correct bash: `[0-9]*` matches a digit followed by anything, then
the literal `. `." As a standalone `case` pattern that is false: a `case` glob
matches the *whole* string, so `[0-9]*". "` matches only a line that ENDS at the
`. ` — it never matches a real next-step line like `2. Second step — risk:
medium  [ ]`. Verified by running it: `case '12. Foo — risk: medium  [ ]' in
[0-9]*". ")` → no match; `[0-9]*". "*)` (trailing `*`) → match. The existing live
matcher at `workflow-startup-precheck.sh:1338` uses the correct `[0-9]*". "*`,
and the design's own worked trace (design.md:421-422) only holds under the
trailing-`*` form. An implementer who copies the track's literal glob writes a
terminator that never fires; whether that produces a wrong count is then masked
or not depending on the incidental order of the buffer-vs-terminator arms, so the
bug is latent and order-dependent rather than always-visible — exactly the class
of defect a regression test can pass by luck while the terminator is dead.
**Evidence**: `case` glob match is whole-string (confirmed empirically);
`workflow-startup-precheck.sh:1338` already uses `[0-9]*". "*`; design.md:399,424,443
carry the same `[0-9]*". "` shorthand, and design.md:421-422's trace requires the
trailing `*` to be true — so the design uses the bare glob as loose shorthand for
the real matcher, and the track copied the shorthand into an instruction plus a
false correctness claim.
**Proposed fix**: In Plan of Work step 6.3, write the terminator as `[0-9]*". "*`
(trailing `*`), matching the existing entry-detector at line 1338, and correct
the track lines 205-206 comment to say the matcher is the same `[0-9]*". "*` glob
`roster_scan` already uses to detect a column-0 step line. Add a decomposition
note that the join's terminator arm and the entry-detector arm must use the
identical glob so the two cannot drift.

### A2 [suggestion]
**Certificate**: Assumption test — dual-path parity test is non-vacuous
**Target**: Invariant S3 (dual-path parity) / Validation "Dual-path parity (D2 mandate)"
**Challenge**: The parity test compares the ledger path against the
"ledger-stripped fallback path." If the fallback arm reads the same fixture
*without* stripping the ledger `substate`, both arms read the ledger and the
assertion is vacuously true — it would pass even if `roster_scan` were broken.
The track already names this trap (Validation lines 247-249 and S3 line 311-313
both state the fallback arm MUST strip the ledger `substate`), so the assumption
HOLDS in the plan as written. The risk is purely that decomposition or
implementation loses the stripping step, because nothing in the in-scope code
forces it: `determine_c_substate` does not read the ledger at all today (verified:
no `ledger` reference inside it), so the "strip" is a test-fixture action, not a
production-code guarantee a unit can fail on.
**Evidence**: `determine_c_substate` reads only `## Progress` + `## Concrete Steps`
(no ledger ref in `workflow-startup-precheck.sh`); the parity assertion's
non-vacuity rests entirely on the fixture omitting `substate=` on the fallback
arm; the track text already mandates this but it is prose, not an enforced
invariant.
**Proposed fix**: When decomposing the parity test step, make the
non-vacuity explicit in the test: assert that the fallback-arm fixture's ledger
carries NO `substate=` token (or that the ledger path and fallback path resolve
the same slug for a slug that the roster shape would NOT produce if the ledger
leaked — i.e. pick a roster shape whose fallback slug differs from a decoy ledger
`substate`, so a leak fails the test loudly).

### A3 [suggestion]
**Certificate**: Violation scenario — S1 track-scoped read leaks a prior track's terminal substate
**Target**: Invariant S1 (track-scoped read)
**Challenge**: S1 says the read keeps the last `substate` on a line whose `track=`
equals the active track. The construction that would break it: a real `track=1`
line whose `categories="…"` free-text carries a space-led `track=2` decoy, read by
a matcher that scans the WHOLE line for ` track=<active>`. Verified: a whole-line
` track=2 ` match on `… track=1 substate=review-done-track-open categories="foo
track=2 bar"` FALSELY matches active track 2 and would leak track 1's terminal
`review-done-track-open`. The track closes this in Plan of Work step 4 — the
reader "reads `track=` and `substate=` from the pre-`categories` block" — and the
pre-`categories` read correctly does NOT match the decoy (verified). So the
invariant HOLDS as designed; the exposure is that the safety rests on the
implementer honoring "pre-`categories` block," and the planned S1 test
(track-1 terminal followed by track-2 substate, no `categories` decoy) does not
exercise the decoy that would catch a whole-line implementation.
**Evidence**: whole-line ` track=2 ` glob matches a space-led decoy inside
`categories` (empirically); pre-`categories` truncation (`${line%% categories=*}`)
correctly does not; `categories` is free text (matched HIGH-risk category names),
so a `track=` decoy is implausible but not impossible — THEORETICAL, not
CONSTRUCTIBLE in normal operation.
**Proposed fix**: Add a track-scoping test variant whose non-active-track line
carries a `categories="… track=<active> …"` decoy and assert the active track's
`substate` still wins — this pins that `track=` is read from the pre-`categories`
block and not the whole line, turning the step-4 instruction into an enforced
invariant.

## Evidence base

#### Violation scenario: S5 wrap-fixed fallback correctness (join terminator)
- **Invariant claim**: The wrap-fixed `roster_scan` counts a step whose `risk:`
  tail wrapped onto a continuation line; the join stops at the next roster entry
  so two adjacent wrapped steps never merge.
- **Violation construction**:
  1. Start state: a two-step roster, both `[x]`, step 2's description wraps onto
     a continuation line carrying the `risk:` tail.
  2. Action sequence: the current scan
     (`workflow-startup-precheck.sh:1337-1352`) matches the column-0 step line,
     finds no `risk:` on it, and hits the `*) continue` arm — step 2 is skipped
     and uncounted (reproduced: `ROSTER_STEP_COUNT=1`, not 2).
  3. Intermediate state: the wrap fix must join step 2 with its continuation
     line. The track's terminator for "stop the join" is `[0-9]*". "`
     (step 6.3); a `case` pattern matches whole-string, so `[0-9]*". "` matches
     only a line ending at `. ` and never a next-step line (reproduced:
     `case '12. Foo — risk: medium  [ ]' in [0-9]*". ")` → no match).
  4. Violation point: track-1.md Plan of Work step 6.3 + the "correct bash"
     comment at track lines 205-206 — the stated glob is not the matcher the
     existing code uses (`workflow-startup-precheck.sh:1338` = `[0-9]*". "*`).
  5. Observable consequence: an implementer copying the literal glob writes a
     dead terminator; the join's correctness then depends on the incidental
     order of the buffer-reseed arm vs the terminator arm, so a wrapped-step
     regression test can pass while the terminator never fires.
- **Feasibility**: CONSTRUCTIBLE — the current-scan miscount is reproduced and
  the wrong glob is demonstrated to never match a real next-step line.

#### Assumption test: dual-path parity test is non-vacuous
- **Claim**: The parity test proves the ledger path and the fallback path resolve
  the identical sub-state for a track whose ledger `substate` and roster/Progress
  imply the same slug.
- **Stress scenario**: run the same fixture through both arms without stripping
  the ledger `substate` on the fallback arm.
- **Code evidence**: `determine_c_substate` reads only `## Progress` + `## Concrete
  Steps`, never the ledger (grep confirms no ledger reference inside it). So the
  fallback arm reads the ledger only if the ledger path runs first or the fixture
  retains `substate=`; the non-vacuity is a fixture property, not a code
  guarantee. The track already mandates stripping (Validation 247-249, S3 311-313).
- **Verdict**: HOLDS — the plan text closes the trap; the residual risk is that
  decomposition drops the prose mandate, which a stronger assertion would prevent.

#### Violation scenario: S1 track-scoped read leaks a prior track's terminal substate
- **Invariant claim**: the `substate` read keeps the last `substate` on a line
  whose `track=` equals the active track, never the global last value.
- **Violation construction**:
  1. Start state: ledger with a `track=1 substate=review-done-track-open` line and
     a later `track=2 substate=steps-partial` line; active track resolved to 2.
  2. Action sequence: a track-scoped reader that scans the WHOLE line for
     ` track=2`. A real `track=1` line whose `categories="foo track=2 bar"` carries
     a space-led `track=2` decoy then FALSELY matches active 2 (reproduced).
  3. Intermediate state: the false match reads that line's `substate=` →
     `review-done-track-open`, track 1's terminal slug.
  4. Violation point: would be `ledger_tail_value_for_track`'s `track=` match if it
     scanned the whole line. The track's Plan of Work step 4 reads `track=` from
     the pre-`categories` block, which correctly does NOT match the decoy
     (reproduced via `${line%% categories=*}`).
  5. Observable consequence: a finished track resumes into a wrong sub-state — but
     only under the whole-line implementation the track text does not prescribe.
- **Feasibility**: THEORETICAL — `categories` is free text (HIGH-risk category
  names); a `track=` decoy is implausible, and the design's pre-`categories` read
  closes it. Worth a test that pins the pre-`categories` read.

#### Violation scenario: S2 empty-substate dormant fallback (airtight under dormant landing)
- **Invariant claim**: an empty `substate` read on a `phase=C` track triggers the
  roster fallback and nothing else (the pre-change behavior plus the wrap fix).
- **Violation construction attempt**:
  1. Start state: Track 1 landed, no append site wired (Track 2 not merged), so no
     ledger line carries `substate=`.
  2. Action sequence: `determine_state_from_ledger` `phase=C` arm resolves the
     active track, calls `ledger_tail_value_for_track substate <track>` → empty,
     falls through to `determine_c_substate` exactly as today.
  3. Violation point sought: a path where empty `substate` does something other
     than the fallback. None found — the empty branch is the unambiguous
     pre-change signal; the `phase=C` arm already calls `determine_c_substate`
     when the track file exists (`workflow-startup-precheck.sh:1812-1814`), and the
     wrap fix only changes how the roster is counted inside that same fallback.
- **Feasibility**: INFEASIBLE — with no `substate` ever written, every read is
  empty and the routing is byte-identical to the current fallback, so the dormant
  landing is genuinely behavior-preserving (Track 1 is independently mergeable).

#### Violation scenario: emit-order safety (bare substate before quoted categories)
- **Invariant claim**: the reader takes the first ` substate=` token and stops, so
  a decoy `substate=` inside `categories="…"` can never win, because
  `append_ledger` emits the bare `substate` before the quoted `categories`.
- **Violation construction attempt**:
  1. Start state: a line `… phase=C track=2 substate=steps-partial
     categories="substate=review-done-track-open,Arch"`.
  2. Action sequence: the reader's first-` substate=`-wins scan
     (mirroring `ledger_tail_value`:1691-1704) reads `steps-partial` and stops
     (reproduced).
  3. Violation point sought: a path where the decoy inside `categories` wins. It
     requires `substate` to be emitted AFTER `categories`. The track's Plan of
     Work step 3 mandates the bare `substate` token in the pre-`categories` block;
     `append_ledger`:1622-1627 already emits `track` before `categories`, so the
     same ordering applies.
- **Feasibility**: INFEASIBLE under the prescribed emit order — recorded as the
  load-bearing invariant the implementer must not reorder (the track text already
  flags it as load-bearing at lines 166-168).

#### Assumption test: ~4-file footprint, independently-mergeable dormant track
- **Claim**: the under-filled ~4-file footprint is the right cut, and Track 1 is
  independently mergeable while dormant.
- **Stress scenario**: land Track 1 alone; does anything route on `substate`
  before Track 2 wires the append sites?
- **Code evidence**: no append site writes `substate` until Track 2
  (Component Map / Checklist confirm Track 2 owns the five append sites); with no
  `substate` on the ledger, every read is empty → fallback → pre-change behavior
  plus the wrap fix (S2 INFEASIBLE-to-violate above). The four files
  (precheck script, its test file, conventions.md glossary,
  conventions-execution.md §2.1) are a single core-primitive unit cut at the
  core→consumer seam; the sizing justification (track lines 289-297) holds and
  all four references resolve (conventions-execution.md §2.1 exists; design.md
  carries all four cited section headers).
- **Verdict**: HOLDS — no scope/sizing finding. The cut is correct and the
  dormant landing is safe.
