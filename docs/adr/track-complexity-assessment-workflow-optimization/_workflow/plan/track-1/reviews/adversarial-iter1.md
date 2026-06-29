<!-- MANIFEST
role: reviewer-adversarial
phase: 3A
track: "Track 1: Ledger schema, resume routing, and Phase-1 artifact existence"
iteration: 1
verdict: should-fix
findings: 4
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: ".claude/workflow/prompts/consistency-review.md:78-88; .claude/workflow/prompts/structural-review.md"
    cert: "Scope: consistency/structural design-presence re-key omits the tier-presence-check sub-block"
    basis: "live consistency-review.md tier-presence check is a tier-keyed finding-emitter the track's step-3 re-key does not name"
  - id: A2
    sev: should-fix
    anchor: "### A2 "
    loc: "docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan/track-1.md:223; .claude/scripts/workflow-startup-precheck.sh:1783-1789"
    cert: "Invariant: the design_gate-before-categories ordering rationale misstates the actual safety invariant"
    basis: "the script comment names a same-named-decoy hazard, not an embedded-spaces-end-the-scan-early hazard"
  - id: A3
    sev: suggestion
    anchor: "### A3 "
    loc: "docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan/track-1.md:376-379; .claude/workflow/track-review.md:484-489; .claude/workflow/inline-replanning.md:164-196"
    cert: "Scope: ledger tier-field removal leaves live readers in Track-2-owned files; cross-track promotion contract"
    basis: "every live tier reader is in one track or the other and I6 promotes both staged trees together; residual is contract legibility"
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    loc: ".claude/scripts/workflow-startup-precheck.sh:1934-2010; docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan/track-1.md:242-244"
    cert: "Assumption: determine_state never read tier; the minimal-default-track-to-1 logic keys off an empty track, not the tier"
    basis: "determine_state_from_ledger reads only phase and track; no tier read exists to re-key"
evidence_base:
  challenges: 1
  violation_scenarios: 1
  assumption_tests: 2
-->

# Track 1 adversarial review — iteration 1

Narrowed track-realization pass (per `track-review.md` §"Track-scoped
adversarial review"): scope/sizing and invariant-violation challenges only. The
D1/D10/D8a inline decisions were vetted by the Phase-0→1 research-log
adversarial gate and are not re-challenged. Workflow-modifying branch
(`s17=workflow-modifying`); the change is entirely prose + one Bash script + two
Python tests, so the five-prose-criteria supersession applies and PSI does not
(no Java). Every challenge is grounded in the live develop-state files, since I6
keeps the live workflow at develop state until Phase-4 promotion.

Net verdict: should-fix. Two real coverage gaps in the track's scope/rationale
prose (A1, A2); two suggestions confirming the track survives challenges that
look threatening on first read (A3, A4). No blocker — the schema delta is
sound, the collision-resolution contract is correct, and the cross-track tier
hand-off is complete once both tracks' staged edits promote together.

## Findings

### A1 [should-fix]
**Certificate**: Scope challenge — consistency/structural design-presence re-key omits the tier-presence-check sub-block
**Target**: Plan of Work step (3) — "the consistency-review and structural-review prompts re-key their design-presence gate to read `design_gate` instead of the tier"
**Challenge**: The track describes the `consistency-review.md` edit as a single re-key: "the design-presence gate reads `design_gate` instead of the tier." But the live file (`consistency-review.md:55-88`) keys *two* distinct things off the `tier` field, not one. (1) The design-presence test (lines 60-76) — which the track's re-key covers. (2) A **tier-presence check** (lines 78-88) that runs in *every* tier and **emits a finding** when "the tier resolves from neither source — no `tier` field in the phase ledger and (for a pre-ledger `lite`/`full` plan) no D18 tier line." Track 1 removes the ledger `tier` field. After the re-key, that whole sub-block's premise ("is the tier recorded?") is dangling: it would fire its malformed-plan finding on *every* post-Track-1 plan, because the `tier` field it checks for no longer exists. The branch labels themselves (`full`/`lite`/`minimal`, lines 63-76) are tier-named and must become gate-named (`design_gate=yes`/`no` × plan-presence). The track's one-line scope description does not name the tier-presence-check sub-block or the tier-named branch labels, so the implementer working the step from the track file alone could re-key only the design-presence test and leave a live finding-emitter pointed at a removed field.
**Evidence**: `consistency-review.md:78-88` is a self-contained "Tier-presence check (runs in every tier)" block that emits a finding on an absent `tier` field; `consistency-review.md:63-76` carries `full`/`lite`/`minimal` tier-named branches. The track file's step (3) and the Interfaces entry (`track-1.md:359`) both describe only "the design-presence gate reads `design_gate`." Acceptance (`track-1.md:287-315`) has no line covering the tier-presence-check re-key.
**Proposed fix**: Widen the step-3 scope text (and the `consistency-review.md` Interfaces entry) to name both sub-blocks: re-key the design-presence test to `design_gate`, AND re-point or retire the tier-presence-check finding-emitter (a post-Track-1 plan has no `tier` field, so the check must read `design_gate` presence, or be dropped, depending on whether an absent `design_gate` is still a malformed-plan finding). Add a track-acceptance line asserting the tier-presence check no longer fires on a valid post-delta ledger. Same scan applies to `structural-review.md`'s per-tier artifact checks (the track says "re-key onto the axes" — confirm there is no analogous tier-presence sub-block).

### A2 [should-fix]
**Certificate**: Violation scenario — the `design_gate`-before-`categories` ordering rationale misstates the safety invariant it relies on
**Target**: Invariant / Plan of Work step (1) — "a bare field must precede the quoted `categories` field so the quoted value's embedded spaces do not end the bare-token scan early"
**Challenge**: The track justifies placing `design_gate` in the pre-`categories` block with the wrong mechanism. The live reader (`ledger_tail_value`, `workflow-startup-precheck.sh:1779-1805`) does NOT "end the bare-token scan early on embedded spaces." It takes the **first** ` $key=` token match on the line and stops; for a quoted value it reads to the closing `"`. Embedded spaces inside `categories="a,b c"` are handled correctly by the quoted-value branch (lines 1794-1797) regardless of field order. The actual hazard the existing code documents (lines 1783-1789) is a **same-named decoy**: a key emitted *after* `categories` whose name also appears literally inside the quoted `categories="…"` value would let the decoy win the first-match scan. So the ordering constraint is real, but its stated reason in the track file is mechanically false. An implementer who tests "embedded spaces don't truncate the value" (the stated rationale) would write a test that passes regardless of field order and would *not* catch a regression where `design_gate` is moved after `categories` — the actual failure mode.
**Violation construction**: (1) Start: ledger line emitted with `design_gate` placed AFTER `categories`, and a (hypothetical) `categories` value containing the literal substring `design_gate=no`. (2) `ledger_tail_value design_gate` scans the line; the first ` design_gate=` match is the decoy inside the quoted span, not the real bare token. (3) `LEDGER_VALUE` resolves to the decoy value. (4) Step 1c routes off a wrong `design_gate` → wrong design-presence decision. Feasibility: THEORETICAL for `design_gate` specifically (the controlled category alphabet — "Workflow machinery,Architecture,…", `risk-tagging.md:10-16` — never contains the literal `design_gate=`), but the *rationale* matters for test design and for any future quoted field, so the misstatement is the finding even though the concrete exploit is infeasible today.
**Evidence**: `workflow-startup-precheck.sh:1783-1789` ("The safety invariant is that emit order, NOT a quoted-span skip: a key emitted AFTER `categories` would let a same-named decoy inside the quoted value win"); contrast `track-1.md:222-224` ("so the quoted value's embedded spaces do not end the bare-token scan early").
**Proposed fix**: Correct the step-1 rationale to match the script's documented invariant — the bare `design_gate` field must precede `categories` so a same-named token cannot lose the first-match scan to a decoy inside the quoted value; the embedded-spaces framing is wrong (the quoted-value branch handles spaces). Have the step-1 test assert the first-match-wins emit-order property (a `design_gate` placed after a `categories` value containing a `design_gate=`-shaped decoy reads the real bare token), mirroring the existing `substate`-ordering rationale the script already states.

### A3 [suggestion]
**Certificate**: Scope challenge — removing the ledger `tier` field leaves live readers in Track-2-owned files; the cross-track promotion contract
**Target**: Plan of Work step (1) / §"§1.7 staging" — the ledger schema delta drops `tier=`
**Challenge**: Track 1 removes `tier=` from the ledger schema in step (1), but the live `tier` field has readers beyond Track 1's in-scope files: `track-review.md:484-489` (Phase-A review selection), `inline-replanning.md:164-196` (the ESCALATE upgrade *writes* `--tier` and depends on "every Phase-2/3A/4 selector" reading the field), `prompts/create-final-design.md:42-90` (Phase-4 carrier), and the reviewer-prompt ledger-first reads. On first read this looks like a sizing under-scope — Track 1 drops a field whose consumers it does not edit. It survives because (a) every one of those readers is claimed by Track 2's scope — `track-2.md` explicitly re-keys `track-review.md`, `inline-replanning.md`, `track-code-review.md`, and `create-final-design.md` (`track-2.md:142,439-473`); and (b) the I6 staging property (`track-1.md:381-387`) holds both tracks' `.claude/**` edits in `_workflow/staged-workflow/` and promotes them together at Phase 4, so no intermediate live state ever has a removed `tier` field with an un-re-keyed reader. The residual is legibility, not correctness: the track file states the I6 property and names Track 2 as dependent, but does not spell out that the `tier`-field removal in step (1) is safe *only because* Track 2 re-keys all remaining readers in the same promotion.
**Survival test**: YES — the decision holds. The schema delta is correctly the foundation Track 2 builds on, the dependency is declared (`track-1.md:376-379`), and the promote-together property closes the apparent gap. This is a rationale-strengthening note, not a scope change.
**Evidence**: `track-2.md:142,439-473` (Track 2 owns the `tier`-reader re-keys); `track-1.md:381-387` (I6 staging — live stays at develop until Phase-4 promotion); `inline-replanning.md:164-196` and `track-review.md:484-489` (the out-of-scope live readers).
**Proposed fix**: Add one sentence to step (1) or the §"§1.7 staging" block: the `tier=` removal is sound only because every remaining live `tier` reader (`track-review.md`, `inline-replanning.md`, `create-final-design.md`, the reviewer prompts) is re-keyed by Track 2 and both tracks' staged edits promote together at Phase 4 — so there is never a live ledger missing `tier` while a reader still expects it.

### A4 [suggestion]
**Certificate**: Assumption test — `determine_state`'s `minimal`-default-track-to-1 logic re-keys "onto the plan-presence / track-count signal instead of the removed tier"
**Target**: Plan of Work step (2) — "`determine_state`'s `minimal`-default-track-to-1 logic re-keys onto the plan-presence / track-count signal instead of the removed tier"
**Claim**: The track asserts `determine_state` reads the tier to default the active track to 1 for the plan-less single-track case, so the re-key moves that read onto the new track-count field.
**Stress scenario**: Trace the live `determine_state_from_ledger` (`workflow-startup-precheck.sh:1934-2010`). It reads `phase` (line 1945) and, in the `C` arm, `track` (line 1965-1967): `[ -n "$track" ] || track="1"`. The default-to-1 fires when the ledger names **no track**, not when the tier is `minimal`. There is **no `tier` read anywhere in `determine_state_from_ledger` or `determine_state`** — the only `tier` references in the script are the `--tier`/`LEDGER_TIER` *append* path (lines 119, 171-172, 1696, 1718) and the usage text. The comments mention `minimal` (lines 1931-1933, 1964, 2014) as the *motivating case*, but the code keys off an empty `track`, not a tier value.
**Verdict**: HOLDS (the re-key is harmless / a near no-op) but the step-2 framing is imprecise and risks a phantom edit. Because `determine_state` never reads `tier`, there is no tier read to "re-key onto the track-count signal." The correct change is: remove the now-stale `tier`/`minimal` *comments* (lines 1931-1933, 1964, 2014, and the `--tier` usage/arg-parse/builder lines 19, 119, 171-172, 1696, 1718) and confirm the empty-`track`→1 default still behaves under the new schema (it does — it is tier-agnostic). An implementer reading step (2) literally might hunt for a `tier` read to move and either invent one or leave the comments claiming a `minimal` tier that no longer exists.
**Evidence**: `workflow-startup-precheck.sh:1945` (reads `phase`), `1965-1967` (reads `track`, defaults to `1` on empty), no `tier` read in either resume function; the only live `tier` touch-points are the append surface (lines 119, 171-172, 1696, 1718) and stale comments (1931-1933, 1964, 2014).
**Proposed fix**: Reword step (2) to "drop the stale `tier`/`minimal` comments in `determine_state`'s default-track logic and confirm the empty-`track`→1 default is tier-agnostic (it reads `track`, never `tier`)" rather than "re-key onto the plan-presence / track-count signal" — there is no tier read to re-key, only stale prose to remove and the append-side `--tier`/`LEDGER_TIER` plumbing to delete.

## Evidence base

#### Challenge: Plan of Work step (3) — consistency/structural design-presence re-key
- **Chosen approach**: Re-key the consistency-review and structural-review prompts' "design-presence gate" to read `design_gate` instead of the tier (`track-1.md` step 3; Interfaces `track-1.md:358-361`).
- **Best rejected alternative**: n/a (not a decision challenge) — the challenge is that the chosen scope text under-describes the live file.
- **Counterargument trace**:
  1. The live `consistency-review.md` keys two blocks off `tier`: the design-presence test (`:60-76`) and a tier-presence finding-emitter (`:78-88`) that runs in every tier.
  2. Track 1 removes the ledger `tier` field, so the tier-presence check's "is the tier recorded?" premise dangles — it would fire on every post-delta plan.
  3. The track's one-line scope covers only the design-presence test, not the tier-presence sub-block or the tier-named branch labels.
- **Codebase evidence**: `consistency-review.md:55-88`; `track-1.md:359`, `:287-315`.
- **Survival test**: WEAK — the re-key direction is right but the scope text is incomplete; an implementer working from the track file alone can leave a live finding-emitter pointed at a removed field.

#### Violation scenario: the `design_gate`-before-`categories` ordering protects last-value-wins resolution
- **Invariant claim**: A bare reader-consumed field must precede the quoted `categories` field "so the quoted value's embedded spaces do not end the bare-token scan early" (`track-1.md:222-224`).
- **Violation construction**:
  1. Start: `design_gate` emitted after `categories`, with a `categories` value containing the literal `design_gate=no`.
  2. Action: `ledger_tail_value design_gate` takes the FIRST ` design_gate=` match (`workflow-startup-precheck.sh:1790-1802`) — the decoy inside the quoted span.
  3. Intermediate: `LEDGER_VALUE` = the decoy, not the real bare token.
  4. Violation point: Step 1c routes off the wrong `design_gate`.
  5. Observable consequence: wrong design-presence resume decision.
- **Feasibility**: THEORETICAL for `design_gate` (category alphabet never contains `design_gate=`, `risk-tagging.md:10-16`), but the track's *stated rationale* (embedded spaces) describes a failure mode the reader does not have, while omitting the real one (same-named decoy, `workflow-startup-precheck.sh:1783-1789`). The misstatement misdirects test design.

#### Assumption test: `determine_state` reads the tier to default the active track
- **Claim**: `determine_state`'s `minimal`-default-track-to-1 logic reads the tier and the track re-keys it onto the track-count signal (`track-1.md:242-244`, step 2).
- **Stress scenario**: Trace `determine_state_from_ledger` end to end.
- **Code evidence**: `workflow-startup-precheck.sh:1945` (`phase` read), `:1965-1967` (`track` read, `[ -n "$track" ] || track="1"`), no `tier` read in `:1934-2143`; `tier` appears only in the append surface (`:119,171-172,1696,1718`) and stale comments (`:1931-1933,1964,2014`).
- **Verdict**: HOLDS — the default is tier-agnostic; there is no tier read to re-key, so step (2)'s "re-key onto the track-count signal" mis-describes the edit (the real edit is comment removal + append-plumbing deletion).

#### Assumption test: every live ledger `tier` reader is covered by one of the two tracks
- **Claim** (implicit in the §1.7-staging safety argument): removing `tier=` in Track 1 is safe because Track 2 re-keys all remaining readers and both promote together.
- **Stress scenario**: enumerate every live ledger `tier`-field reader and confirm each lands in a track scope.
- **Code evidence**: in-scope (Track 1): `consistency-review.md`, `structural-review.md`, `implementation-review.md`, `determine_state`. Out-of-scope/Track-2: `track-review.md:484-489`, `track-code-review.md`, `inline-replanning.md:164-196`, `create-final-design.md:42-90`, the reviewer prompts (`track-2.md:142,439-473`). I6 promote-together: `track-1.md:381-387`.
- **Verdict**: HOLDS — no orphaned reader; the apparent under-scope is closed by the cross-track dependency + promote-together property. Residual is contract legibility only (A3).
