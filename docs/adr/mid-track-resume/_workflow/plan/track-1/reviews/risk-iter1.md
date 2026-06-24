<!-- MANIFEST
review_kind: risk
role: reviewer-risk
phase: 3A
track: "Track 1: Ledger substate primitive, dual-path resolution, wrap-fix, tests, grammar"
iteration: 1
verdict: changes-requested
findings: 4
blockers: 0
index:
  - id: R1
    sev: should-fix
    anchor: "### R1 [should-fix]"
    loc: ".claude/scripts/workflow-startup-precheck.sh roster_scan; track-1.md Plan-of-Work item 6"
    cert: "Exposure: roster_scan wrap-join rewires the resume hot loop"
    basis: code
  - id: R2
    sev: should-fix
    anchor: "### R2 [should-fix]"
    loc: ".claude/scripts/tests/test_workflow_startup_precheck.py dual-path parity group; track-1.md Validation group 3"
    cert: "Testability: dual-path parity requires a ledger-stripped fallback arm the current harness has no helper for"
    basis: code
  - id: R3
    sev: suggestion
    anchor: "### R3 [suggestion]"
    loc: ".claude/scripts/tests/test_workflow_startup_precheck.py TESTS registry @ 4101"
    cert: "Testability: tests are hand-registered, not auto-collected"
    basis: code
  - id: R4
    sev: suggestion
    anchor: "### R4 [suggestion]"
    loc: "track-1.md D1/D2 + Plan-of-Work item 5; determine_state_from_ledger @ 1804"
    cert: "Assumption: empty substate read == pre-change ledger (D3), whose enforcing append site is Track 2"
    basis: code
evidence_base:
  exposures: 2
  assumptions: 2
  testability: 3
-->

# Risk review — Track 1 (iteration 1)

Track 1 is bash/python/markdown workflow machinery; no Java, so no mcp-steroid
probe. Symbol audits used grep + Read over the live
`workflow-startup-precheck.sh` and `test_workflow_startup_precheck.py` (the
§1.7-staged baseline — nothing staged yet at Phase A), plus the frozen
`design.md` and the plan. Reference-accuracy caveat: the call-graph claims below
rest on grep, not PSI; in a single-file bash script with no polymorphism the
miss risk is low, and each function's sole call sites were read in full.

Verdict: changes-requested — no blockers. The primitive is genuinely
low-risk-to-land (dormant read side, empty-read-falls-back). The two should-fix
items are about the one hot-loop rewire and one test the plan under-specifies,
not about the design being wrong.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — `roster_scan` wrap-join rewires the resume hot loop.
**Location**: `.claude/scripts/workflow-startup-precheck.sh` `roster_scan`
(`1306`-`1391`); track-1.md `## Plan of Work` item 6 (the wrap fix).
**Issue**: This is the single highest-blast-radius edit in the track. `roster_scan`
runs on the fallback arm of *every* `/execute-tracks` resume that lands on a
`phase=C` track with no ledger `substate` — which, until Track 2 wires the append
sites, is **every** resume (the primitive lands dormant, so the fallback is the
only live path). The current loop is a single-pass line reader: a column-0
`N. ` line with no `risk:` hits `*) continue` (`1349`-`1351`) and an indented
continuation line hits the `[0-9]*". "` guard's `*) continue` (`1337`-`1340`).
The wrap fix replaces that single-pass shape with a buffer-and-join across
multiple physical lines, terminating on the next `[0-9]*". "` line, the next
`## ` heading, or EOF. Three concrete ways the rewrite can regress a path that is
correct today:
1. **Lookahead vs. `while read` line consumption.** The current loop consumes one
   line per iteration. A join that must "stop at the next column-0 line" has to
   either buffer that next line for the following iteration or re-read it — a
   classic off-by-one where the terminating step line gets consumed by the join
   and never counted itself. The unwrapped common case (`412` in design.md, every
   real roster today) must stay byte-identical.
2. **The `## ` terminator and the existing `"## "*) return` arm (`1330`-`1333`).**
   The join's `## `-heading stop must not double-count or swallow the section-end
   return that already terminates the scan.
3. **Fenced-code / blockquote guards (`1314`-`1322`).** The design says these
   "stay as they are," but a continuation line *inside* a fence that opens
   mid-entry must still be skipped; the buffer must not absorb fenced lines.
The blast radius if this regresses: a finished track mis-routes back into Phase B
(under-count) or a partial track is mis-read as done (over-count) — the exact
failure class this track exists to kill, now reachable through the fallback the
track keeps alive.
**Proposed fix**: Decompose the wrap fix into its own atomic step with the
worked-trace fixture from design.md `406`-`430` as a *table* of regression
fixtures, not one case: (a) unwrapped step reads byte-identical to today; (b)
single wrapped step counted; (c) two adjacent wrapped steps do not merge; (d) a
wrapped step immediately followed by a `## ` heading; (e) a wrapped step at EOF;
(f) a wrapped step whose continuation carries an inline `` `[ ]` `` in backticks
(design.md `455`-`460`); (g) a blank line inside an entry vs. between entries
(design.md `462`-`465`). The track's `## Validation` lists only one wrapped-roster
case (the YTDB-1134 literal) — name (c)-(g) explicitly in the decomposition so the
hot-loop rewrite is pinned on all its own edge cases, not just the headline one.

### R2 [should-fix]
**Certificate**: Testability — dual-path parity requires a ledger-stripped
fallback arm the current harness has no helper for.
**Location**: `.claude/scripts/tests/test_workflow_startup_precheck.py` (the
dual-path parity test, group 3); track-1.md `## Validation` group "Dual-path
parity (D2 mandate)" and S3.
**Coverage target**: 85% line / 70% branch.
**Difficulty assessment**: The parity test must run *one* fixture twice — once so
the ledger `substate` is read (primary path) and once with the ledger `substate`
*stripped* so resolution falls through to `determine_c_substate` (fallback path)
— and assert both resolve the identical slug. The track file itself flags the
trap: "running the same fixture twice without stripping would make both arms read
the ledger and the assertion vacuous." The existing helpers do not support this.
`_ledger_state(phase, track, track_body)` (`3574`) composes the ledger from
`phase`/`track` only — it has **no `substate` parameter** — and `_substate(body)`
(`2600`) builds a *plan-backed* State-C fixture with **no ledger at all**. So the
parity test needs a new fixture that (a) writes a ledger line carrying
`substate=<slug>` via the verbatim `write_ledger` (`462`), then (b) re-runs the
identical track file against a ledger line with the `substate=` token removed,
forcing the fallback. The "force the fallback" arm is the fragile part: it works
only because the empty-`substate` read falls through (the `determine_state_from_ledger`
phase=C arm at `1804`-`1821` calls `determine_c_substate` when the ledger read is
empty) — so the stripped arm must keep `phase=C track=N` while dropping only
`substate=`, not drop the whole ledger (dropping the ledger entirely routes
through `determine_state` legacy walk, a *third* path, not the fallback the parity
test means to exercise).
**Feasibility**: ACHIEVABLE — `write_ledger` is verbatim and `_track_doc` composes
the roster, so the two arms are constructible — but DIFFICULT to get right, and an
under-built version is silently vacuous (both arms read the ledger, the assert
still passes, parity is never actually tested).
**Proposed fix**: Add an explicit decomposition note that the parity test (i)
introduces a fixture composing a verbatim ledger with `substate=<slug>` for the
primary arm and the same ledger with the `substate=` token deleted (but
`phase=C track=N` kept) for the fallback arm, and (ii) carries a guard assertion
that the stripped arm actually reached `determine_c_substate` (e.g. assert the
fallback ledger line contains no `substate=`), so a future edit that accidentally
makes both arms read the ledger fails loudly instead of passing vacuously.
Without that guard the S3 invariant is testable in form but not in substance.

### R3 [suggestion]
**Certificate**: Testability — tests are hand-registered in a `TESTS` list, not
auto-collected.
**Location**: `.claude/scripts/tests/test_workflow_startup_precheck.py` `TESTS`
registry (`4101`) and the `main()` runner (`16`-`40`); track-1.md item 10 / the
five test groups.
**Coverage target**: 85% line / 70% branch.
**Difficulty assessment**: Pytest is not installed (the file's own header at `34`
says so; the suite runs as a plain script via `main()` iterating a hand-authored
`TESTS: List[Tuple[str, Callable]]`). A new `def test_*` that is **not** appended
to `TESTS` is silently never run — no collection error, no failure, the suite
still reports "All N passed." The track adds roughly five-plus new test functions
across five groups; each needs a matching `TESTS` entry or its coverage is
illusory.
**Existing test infrastructure**: `main()` + `TESTS` (`4101`, `4217`);
`write_ledger`, `write_track_only`, `_track_doc`, `_substate`, `_ledger_state`,
`run_precheck`, `_state` are all present and reusable.
**Feasibility**: ACHIEVABLE — the pattern is well established (103 registered
tests today) — the only risk is a forgotten registry entry.
**Proposed fix**: In decomposition, name the registry append as an explicit
sub-item of the test step, and verify post-implementation that
`grep -cE '^def test_'` equals the `TESTS` length so no new test is orphaned. Low
probability, trivial to catch, worth one line in the step.

### R4 [suggestion]
**Certificate**: Assumption — an empty track-scoped `substate` read means exactly
"pre-this-change ledger," and the append site that makes this true is Track 2.
**Location**: track-1.md D1 "Append cadence," D2 "Why both paths exist," `## Plan
of Work` item 5; `determine_state_from_ledger` phase=C arm (`1804`-`1821`);
design.md `344`-`353` (D3) and `162`-`166`.
**Evidence search**: grep for `--substate` / `LEDGER_SUBSTATE` append call sites
across `.claude/` (live tree) — the flag does not exist yet (this track adds the
*parser case and validation*, not a caller); D3's enforcing append sites live in
Track 2 per the plan Checklist (`67`-`68`) and design.md `346`-`349`.
**Code evidence**: The read side is self-consistent and safe to land alone: with
no append site wired, every `substate` read is empty, so `determine_state_from_ledger`
always falls through to `determine_c_substate` — the pre-change behavior plus the
wrap fix (the dormancy argument, track-1.md `289`-`297`). The assumption that
"empty == pre-change ledger" only becomes *load-bearing* once Track 2 wires the
appends; until then "empty" trivially means "no append site." So the risk is not
in Track 1 in isolation — it is the cross-track contract: if Track 2 ever lands an
append path that can leave a `phase=C` track with **no** `substate` (a missed
boundary, a single-step track that skips a milestone), the empty-read silently
routes to the roster fallback instead of the intended slug, reviving the
silent-default mode design.md `350`-`353` warns against.
**Verdict**: VALIDATED for Track 1 in isolation (dormant, falls back correctly).
The cross-track invariant is UNVALIDATED here by construction — it is Track 2's to
prove.
**Detail / Proposed fix**: No change required in Track 1. Carry one line into the
`## Surprises & Discoveries` log (or the track episode) flagging that the
"every `phase=C` track carries an explicit `substate`" invariant is an
*aspirational* invariant this track consumes but does not enforce, so Track 2's
risk review treats "no append boundary can leave `substate` empty on a live
`phase=C` track" as a blocker-level completeness check rather than re-deriving it.

## Evidence base

#### Exposure: `roster_scan` wrap-join rewires the resume hot loop
- **Track claim**: item 6 — join continuation lines so a wrapped step's `risk:`
  tail and `[x]` status are read; the unwrapped case is unchanged.
- **Critical path trace**:
  1. Entry: `determine_state()` `@ workflow-startup-precheck.sh:1832` — runs at
     turn 1 of every `/execute-tracks` session (the `full`-mode precheck).
  2. `determine_state_from_ledger()` `@ 1839` (preferred) → phase=C arm `@ 1804`.
  3. With no ledger `substate` (the dormant state until Track 2) →
     `determine_c_substate(track_file, "todo")` `@ 1813`.
  4. `determine_c_substate` `@ 1713` → `roster_scan "$track_file"` `@ 1724`.
  5. `roster_scan` `@ 1306` — the line loop (`1313`-`1390`) the fix rewrites.
- **Blast radius**: A regression here mis-routes resume: under-count → finished
  track back into Phase B; over-count → partial track read as done. This is the
  precise failure the track fixes, reachable through the fallback the track keeps
  live. Callers of `roster_scan`: `determine_c_substate` only (grep, single file).
- **Existing safeguards**: `classify_marker` + `parse_error` (`1375`-`1378`) fail
  loud on a bad glyph; the fenced/blockquote guards (`1314`-`1322`); 103 passing
  tests today including realistic-track-file fixtures (`2503`, `2822`, `3027`).
  But NONE of those fixtures wrap a `risk:` tail — grep for a multi-line roster
  entry in the test file finds none — so the wrap behavior is currently untested.
- **Residual risk**: MEDIUM — the edit is in a hot path, the loop shape changes
  from single-pass to buffered, and the regression surface (R1 (a)-(g)) is wider
  than the one case the track names.

#### Exposure: `determine_state_from_ledger` ledger-first read insertion
- **Track claim**: item 5 — read track-scoped `substate` before
  `determine_c_substate`; emit directly when non-empty, fall through when empty.
- **Critical path trace**:
  1. `determine_state_from_ledger` phase=C arm `@ 1804`-`1821`: resolves the
     active track (`ledger_tail_value "track"` `@ 1808`, default `1` `@ 1810`),
     probes `track_file` `@ 1811`-`1812`.
  2. The track adds `ledger_tail_value_for_track substate <track>` before the
     existing `determine_c_substate` call `@ 1813`.
- **Blast radius**: bounded — a new branch in front of an existing call; the empty
  arm preserves today's behavior exactly. The only new reader is
  `ledger_tail_value_for_track`, which mirrors `ledger_tail_value` (`1675`-`1707`)
  but adds the `track=`-scoping. Mis-scoping (global last-value instead of
  per-track) would leak a completed prior track's terminal `substate` — caught by
  the track-scoping ledger-path test (S1).
- **Existing safeguards**: the emit-order invariant (`ledger_tail_value` header
  `1681`-`1690`; design.md `142`-`149`) — every bare read-key written before the
  one quoted `categories` field — already holds and is preserved (`substate` is
  bare, written in the pre-`categories` block, item 3). The torn-append atomicity
  (`1629`-`1661`) is unchanged.
- **Residual risk**: LOW — additive branch, dormant until Track 2, empty-read
  parity with today proven by the fallback-path test (S2).

#### Assumption: empty `substate` read == pre-this-change ledger (D3)
- **Track claim**: item 5 / D1 — "The empty case is the unambiguous signal of a
  ledger written before this change."
- **Evidence search**: grep `--substate`/`LEDGER_SUBSTATE` over live `.claude/` —
  no append caller exists; D3's append sites are Track 2 (plan `67`-`68`).
- **Code evidence**: `determine_state_from_ledger:1804`-`1821` + dormancy argument
  track-1.md `289`-`297`.
- **Verdict**: VALIDATED for Track 1 alone; the cross-track invariant is Track 2's
  to enforce. See R4.

#### Assumption: the four committed slugs are byte-identical to workflow.md step 5
- **Track claim**: `## Interfaces` — `workflow.md` step 5 routing is unchanged
  because the four committed slugs match the slugs it already routes on.
- **Evidence search**: grep the four slugs + the two fallback-only slugs in
  `.claude/workflow/workflow.md`.
- **Code evidence**: `workflow.md:342`-`349` — the sub-state routing table carries
  `decomposition-pending`, `steps-partial`, `steps-done-review-pending`,
  `review-done-track-open` (the four committed) plus `failed-step` and
  `section-discrepancy` (the two fallback-only), byte-identical to the track's
  slug set.
- **Verdict**: VALIDATED — `workflow.md` step 5 needs no edit; the out-of-scope
  claim holds.

#### Testability: dual-path parity (group 3 / S3)
- See R2. Feasibility ACHIEVABLE but DIFFICULT; vacuous-pass risk without an
  explicit ledger-stripped fallback arm and a guard that the fallback was reached.

#### Testability: wrapped-roster regression + the wider wrap edge set
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the headline YTDB-1134 case is straightforward
  (`_track_doc` composes a roster whose step 2 wraps; assert
  `steps-done-review-pending` where today's scan yields `steps-partial`). The
  *wider* set (R1 (c)-(g)) needs deliberate fixtures: adjacent wrapped steps, a
  wrapped step before `## `, at EOF, with an inline `` `[ ]` ``, and the blank-line
  cases. The branch-coverage target makes these worth pinning — the buffer/join
  adds branches (`1364`-`1374`-style checks) that one happy-path fixture leaves
  uncovered.
- **Existing test infrastructure**: `_track_doc` (`2585`), `_substate` (`2600`),
  `write_track_only` (`478`), `run_precheck`, `_state`.
- **Feasibility**: ACHIEVABLE; the gap is breadth, not possibility (R1 fix).

#### Testability: hand-registered `TESTS` list
- See R3. ACHIEVABLE; the only risk is an orphaned new test (forgotten registry
  append) — caught by a `grep -cE '^def test_' == len(TESTS)` check.
