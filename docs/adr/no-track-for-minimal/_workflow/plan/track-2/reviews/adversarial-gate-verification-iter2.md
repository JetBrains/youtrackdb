<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: A7, sev: should-fix, loc: "track-2.md:251-258 (D8/step-3); mid-phase-handoff.md:428", anchor: "### A7 ", cert: "Assumption test: a ledger paused event is cleared on resolution", basis: "ledger is append-only (precheck:46,1491); determine_state reads only phase/track (precheck:1766,1786); resolution deletes the **PAUSED marker but never the ledger paused= event"}
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 2 adversarial gate-verification — iteration 2

Verify-and-extend pass over the revised `track-2.md`. The six iteration-1
findings (A1-A6) are all VERIFIED against the edited track. One new finding
(A7, should-fix) surfaced when re-attacking the D8 pause-event fix: the
ledger `paused` event the fix introduces has no documented clear-on-resolution
path, so the extended recovery grep can re-fire on an already-resolved pause.
The new finding does not block; it strengthens an under-specified arm of an
otherwise-sound fix. Overall: PASS.

## Findings

### A7 [should-fix]
**Certificate**: Assumption test — a ledger `paused` event is cleared (or
distinguished from a live pause) on resolution.
**Target**: Decision D8 / Plan-of-Work step 3 (pause boundaries → ledger
`paused` events; extend the recovery grep to scan `paused=`).
**Challenge**: D8's surviving mitigation arm (step 3) routes the
Phase-2/State-0 and Phase-4 secondary markers to a ledger `paused` event and
extends `mid-phase-handoff.md`'s recovery grep to scan for `paused=`. But the
ledger is **append-only** (`workflow-startup-precheck.sh:46,1491`; "a mid-flight
change is recorded by APPENDING a new line") and there is **no clear/unpause/
resumed mechanism** in the primitive. The handoff resolution step
(`mid-phase-handoff.md:428`) deletes the handoff file and removes "the matching
PAUSED marker line from the step / plan file" — but a ledger `paused` event is
not a `**PAUSED ` marker line in a plan section, so resolution leaves it on
disk. `determine_state` never reads `paused` (it reads only `phase`/`track`,
`:1766,1786`), so the orphan does not corrupt resume state — but the *one*
consumer step 3 adds, the extended `paused=` recovery grep, will re-discover the
now-orphaned event on the next session, point at a handoff file resolution
already deleted, hit the durable-artifact-verification "missing artifact" branch
(`mid-phase-handoff.md:470`), and default to **Abort**. So step 3's fix, as
written, can convert a *resolved* pause into a recurring false Abort. The track
file's D8 and step 3 do not define how a ledger `paused` event is cleared or how
the recovery grep separates a live pause from a resolved one — an
instruction-completeness gap (every paused state needs a documented resume/clear
complement). This is the mirror of A3: A3 fixed *recording* the pause in the
ledger; the *clearing* half is unaddressed.
**Evidence**: ledger append-only with no clear path — `precheck:46,1491`;
`paused` is a per-field last-value-wins bare token with empties omitted, never
written as `key=` (`precheck:1604,1510`), so no append can blank it;
`determine_state_from_ledger` reads only `phase`/`track` (`precheck:1766,1786`),
so the orphan is invisible to resume but visible to the new grep; resolution
deletes the `**PAUSED ` marker line, not a ledger event
(`mid-phase-handoff.md:428`); missing-artifact branch defaults to Abort
(`mid-phase-handoff.md:470,477`).
**Proposed fix**: In D8 Risks/Caveats and step 3, name the clear-on-resolution
contract for the ledger `paused` arm. Cheapest workable shape: have the recovery
grep treat a `paused` event as **superseded** when a later ledger line carries a
`phase` past the paused boundary (the append-only, last-value-wins tail already
encodes "we moved on"), so the grep matches only a `paused` event with no
subsequent forward-phase append — equivalently, gate the `paused=` scan on
"handoff file still present", since resolution already deletes the handoff file
and that deletion is the durable resolved-signal. Either way, step 3 must say
which, so the implementer does not ship a grep that re-Aborts on resolved
pauses. (Whichever shape lands stays a Track-2 edit to `mid-phase-handoff.md`,
already in scope; no new file, no cross-track move.)

## Evidence base

#### Verdict: A1 [blocker, iter-1] — `plan-slim-rendering.md` missed consumer
- **Prior finding**: `plan-slim-rendering.md:162` "Keep the `## Final Artifacts`
  section verbatim" was a missed consumer of a removed plan section.
- **Fix claimed**: added to scope (→22 files); Plan-of-Work step 6 adapts the
  render rule plus the Goals/Non-Goals pre-Checklist list.
- **Verification**: Live `plan-slim-rendering.md:162` reads
  "4. **Keep the `## Final Artifacts` section verbatim.**" — a real consumer of
  the section D5/D7 relocates. The pre-Checklist Goals/Non-Goals list is at
  `:138-140` ("High-level plan (Goals, Constraints, ... Non-Goals)"). Track file
  `## Interfaces and Dependencies` now lists `plan-slim-rendering.md` (`:373`)
  and the `## Context and Orientation` entry (`:202-205`) and Plan-of-Work step 6
  (`:283-284`) both name `:162` and `:138-140` explicitly. The in-scope list is
  22 files (counted). Consumer is in scope and pinned by line.
- **Verdict**: VERIFIED.

#### Verdict: A2 [should-fix, iter-1] — D8 "machine-read by determine_state" unrealized
- **Prior finding**: D8 rationale claimed the ledger `paused` event is
  machine-read by `determine_state` on resume, but `determine_state` reads only
  `phase`/`track`.
- **Fix claimed**: D8 Risks/Caveats now states the `determine_state`-read is NOT
  delivered by Track 2 (out of scope — Track 1's precheck); recoverability is the
  extended grep plus the unchanged handoff file.
- **Verification**: `determine_state_from_ledger`
  (`workflow-startup-precheck.sh:1755-1807`) reads `phase` (`:1766`) and, only in
  the `C` arm, `track` (`:1786`); it never reads `paused`. Track file D8
  Risks/Caveats (`:112-117`) now states verbatim: "Track 1 writes but never reads
  `paused` (`determine_state` reads only `phase`/`track`), so the 'machine-read by
  `determine_state`' property the rationale above claims is not delivered by this
  track ... Recoverability comes from the extended grep plus the unchanged
  handoff file." The correction matches the staged primitive.
- **Verdict**: VERIFIED.

#### Verdict: A3 [should-fix, iter-1] — pause-recovery grep cannot match a bare-token `paused=`
- **Prior finding**: the recovery grep `^\*\*PAUSED ` cannot match a bare-token
  ledger `paused=` field.
- **Fix claimed**: step 3 commits to extending the recovery grep to scan the
  ledger for `paused=` events.
- **Verification**: Live `mid-phase-handoff.md:173` recovery grep is
  `grep -rn '^\*\*PAUSED ' docs/adr/<dir-name>/_workflow/`; a ledger line starts
  with `[<ISO>]` and `paused` is a bare space-rejecting token
  (`precheck:1529,1582`), so it can never hold the literal `**PAUSED `. Track file
  step 3 (`:253-256`) now commits: "extend the recovery grep in
  `mid-phase-handoff` to scan the ledger for `paused=` events," and the D8
  Risks/Caveats (`:108-112`) explains why the "keep the `**PAUSED ` prefix" arm is
  infeasible. Fix is the correct mitigation. (Note: the *clearing* half of this
  same arm is unaddressed — see new finding A7.)
- **Verdict**: VERIFIED.

#### Verdict: A4 [should-fix, iter-1] — in-flight `lite`/`full` fallback dropped
- **Prior finding**: re-pointing marker reads to the ledger drops detection for
  an in-flight pre-ledger `lite`/`full` branch carrying the plan `### Constraints`
  marker but no ledger.
- **Fix claimed**: step 2 + invariant specify ledger-first with a
  `### Constraints` fallback; a Validation scenario was added.
- **Verification**: Step 2 (`:236-238`) now reads "Each re-pointed marker read is
  **ledger-first with a plan-`### Constraints`-scan fallback**: read `s17` from
  the ledger; if no ledger ... fall back to the develop-era `### Constraints`
  stable-prefix scan, mirroring `determine_state`'s own two-level pattern." The
  D4 Risks/Caveats (`:73-75`) and the invariant (`:286-292`) both state the
  fallback. The Validation block (`:324-326`) adds: "An in-flight pre-ledger
  workflow-modifying branch (plan `### Constraints` marker present, no
  `phase-ledger.md`) is still detected ... via the `### Constraints` fallback."
  The two-level pattern matches the staged primitive
  (`determine_state:1809-1818`: ledger first, legacy plan walk on no-ledger).
- **Verdict**: VERIFIED.

#### Verdict: A5 [suggestion, iter-1] — self-count drift ("three references")
- **Prior finding**: a "three references" self-count in workflow.md re-point
  prose drifted from the 4-5 actual touch points.
- **Fix claimed**: step 6 + C&O no longer say "three"; the spots are pinned by
  line.
- **Verification**: Step 6 (`:272-280`) carries no "three" count; it pins
  `workflow.md` spots by line — `:350`, `:743` (the 2 remaining "Track 2
  re-points this" flags), `:310`, `:417`, `:768` — and notes "the staged file
  carries only 2 flags for more spots," which is confirmed: a flag grep over the
  staged `workflow.md` returns exactly 2 (`:350`, `:743`). The C&O entry
  (`:183-187`) says "the spots are pinned by line in Plan-of-Work step 6," no
  count. Drift removed; pins verified accurate (`:417` and `:768` are genuinely
  still-stale `## Plan Review` references in the staged file).
- **Verdict**: VERIFIED.

#### Verdict: A6 [suggestion, iter-1] — D11 ordering thin
- **Prior finding**: D11's materialize-then-write ordering was under-specified.
- **Fix claimed**: step 4 spells out materialize-then-write order and the tier
  write as an `--append-ledger` call.
- **Verification**: Step 4 (`:259-265`) now reads: "`inline-replanning` writes the
  upgraded tier as a ledger event (an `--append-ledger` call), in
  materialize-then-write order: first materialize `implementation-plan.md` (and
  `design.md` for `full`) ... then append the upgraded tier." The
  `--append-ledger` subcommand exists in the staged primitive (`precheck:139`,
  with `--tier`/`--s17` flags). The Validation block (`:319-321`) states the
  materialize + ledger-write outcome. Ordering is now explicit and grounded.
- **Verdict**: VERIFIED.

#### Challenge: Scope / sizing — is 22 files still one well-sized coherent track?
- **Chosen approach**: one Track 2 of 22 in-scope files re-pointing every runtime
  consumer onto the ledger / `plan-review.md`.
- **Best rejected alternative**: split into a "review-state / plan-review.md"
  track and a "marker-read / pause / escalation" track.
- **Counterargument trace**: `planning.md:602` sets the split-candidate threshold
  at "over ~20-25 in-scope files," **soft**, and `:606`/`:591` allow an
  out-of-bounds footprint with written justification. 22 is *inside* the 20-25
  band, not over it, so Track 2 is not even a split candidate by the stated rule.
  The work is also homogeneous: 18 of the 22 edits are the same two mechanical
  re-points (marker read → ledger `s17`; plan-checkbox / `## Plan Review` /
  `## Final Artifacts` reference → ledger), all consuming Track 1 contracts and
  all routing into the one staged mirror. Splitting would cut the §1.7-marker
  read-point (shared by the review prompts, the implementer gate, and the
  gate-rechecks) across two PRs, breaking the "re-point the highest-traffic
  contract once" ordering the track opens with (`:214-215`).
- **Codebase evidence**: `planning.md:598-616` (soft bounds, justification
  clause); track file `:351-376` (22-file list), `:214-215` (ordering rationale).
- **Survival test**: YES — 22 is within bounds and the change is coherent; no
  split warranted.

#### Violation scenario: "the branch stages every live workflow edit" (plan Invariant)
- **Invariant claim**: every live workflow edit on this branch stages; nothing
  edits `.claude/**` in place (plan `:315`).
- **Violation construction attempt**: Track 2 carves `conventions.md` §1.7(c)
  read-side into scope — a Track-1-owned file. Could editing a Track-1 file in
  Track 2 (a) edit live in place, or (b) create a Track-1/Track-2 ownership
  conflict?
  1. Track file `## Context and Orientation` (`:141-143`) states Track 2 stages
     under §1.7(b) like Track 1; every edit routes to
     `_workflow/staged-workflow/.claude/**`. The §1.7(c) amendment is a staged
     edit, not a live one — invariant honored.
  2. Ownership: Track 1's *own staged* `conventions.md` already hands these spots
     to Track 2. The §1.7 "Marker home" prose (`conventions.md:1006-1009`) reads
     "This track defines the home; **Track 2 re-points the consumers that read
     it**"; the §1.7(c) Detection-rule read-side (`:1056-1058`) was *deliberately
     left* at the develop-era "the implementer reads `### Constraints`" for Track
     2 to amend; §1.7(e) (`:1179-1182`) and §1.7(f) (`:1194-1195`) likewise
     pre-name Track 2's D14 gate/promotion extensions. The overlap is a
     *documented hand-off in a stacked-diff series*, not a conflict — Track 2's PR
     stacks on Track 1's and edits the same staged file, the normal stacked-diff
     shape.
- **Feasibility**: INFEASIBLE — no invariant violation and no ownership conflict;
  the carve-out is exactly what Track 1's staged conventions.md instructs.

#### Assumption test: cross-track-episode reality — new claims hold against the staged precheck + staged workflow.md
- **Claim**: the revised track's new fix-claims (ledger grammar, `s17` bare
  token, `determine_state` field reads, the 2 remaining workflow.md flags, the
  D14 three-prefix gate/promotion targets) match the as-staged Track-1 artifacts.
- **Stress scenario**: check each claim against the staged primitive and the live
  D14 targets.
- **Code evidence**: ledger grammar `{phase,track,tier,categories,s17,paused}`
  (`precheck:51,56`); `s17`/`paused` bare space-rejecting tokens
  (`precheck:1581-1582,1529`); `--append-ledger` with `--tier`/`--s17`/`--paused`
  (`precheck:120,139,157-169`); `determine_state` reads phase/track only
  (`precheck:1766,1786`); staged `workflow.md` carries exactly 2 "Track 2
  re-points" flags (`:350,:743`) with `:417`/`:768` genuinely-stale
  `## Plan Review` references; live `implementer-rules.md:387-424` §1.7(e) gate
  covers exactly workflow/skills/agents with a Phase-4 allow-clause (D14 target
  real); live `create-final-design.md:450,457` divergence check + `git add` omit
  `.claude/scripts` while `:456 cp -r` already copies them (D14 "copied yet never
  committed" claim exact); live `track-code-review.md:1434-1440` deferred-write
  resume reads the plan-file track `[x]` (step-5 target real).
- **Verdict**: HOLDS — every new claim is grounded in the as-staged / live
  artifacts. (The one gap the stress surfaced is the resolution side of the
  `paused` event — captured as A7, not a claim-accuracy failure.)

**PASS**
