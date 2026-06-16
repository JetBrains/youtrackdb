<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: R8, sev: should-fix, loc: "track-2.md step 2 / D4 Risks-Caveats; implementation-review.md:186", anchor: "### R8 ", cert: "Assumption: tier-line reader inventory complete", basis: "grep+Read: implementation-review.md tier read not in re-point set; plan D4 says every tier-line reader re-points"}
  - {id: R9, sev: suggestion, loc: "track-2.md §Context/step 2 conventions §1.7(c) carve-out; staged conventions.md §1.7(c)", anchor: "### R9 ", cert: "Assumption: §1.7(c) read-side carve-out resolves the split-brain", basis: "Read staged conventions §1.7(b) vs §1.7(c): signal still named 'Constraints declaration'"}
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
  - {id: R7, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 2 risk gate — verification, iteration 2

Both iteration-1 blockers (R1 inventory completeness, R2 marker-read fallback) are
resolved in the edited track file, and the five should-fix / suggestion findings are
addressed. Independent re-grep of the four consumer categories
(removed-plan-section reads, §1.7 marker reads, tier-line reads, Phase-4 / completion
signals) confirms the now-22-file inventory is exhaustive for the removed-section,
§1.7-marker, and completion-signal categories. The tier-line category surfaced one
omitted reader (R8, should-fix) and the R5 §1.7(c) carve-out has a residual coherence
refinement (R9, suggestion). Neither is a blocker. Gate verdict: PASS.

## Verdicts on prior findings

**R1 [blocker] — VERIFIED**
The five consumers of `## Plan Review` / `## Final Artifacts` outside the original
16-file scope are all added: `workflow-drift-check.md:216`,
`skills/execute-tracks/SKILL.md:89`, `skills/review-plan/SKILL.md:97`,
`workflow/structural-review.md:167`, `plan-slim-rendering.md:162` (plus its
`:138-140` Goals/Non-Goals list). They appear in `## Interfaces` in-scope
(22 files), the D4 Risks/Caveats inventory, the `## Surprises & Discoveries` note,
and Plan-of-Work steps 1 and 6. `workflow-drift-check` is re-based on the ledger
`phase == "D"/"Done"` tail (steps 1/6, Validation line). Independent re-grep
confirms each location and finds no further removed-section reader: the only other
`## Plan Review` hit is `migrate-workflow/SKILL.md:544`, which is a generic
illustrative rename example in tier-agnostic migration machinery (it edits whatever
the replayed diff dictates), not a section reader — correctly out of scope.

For the §1.7 marker-read category I confirmed the four files that carry a
staged-path *mechanism* reference but **no** marker *read* —
`agents/review-workflow-context-budget.md`, `skills/code-review/SKILL.md`,
`skills/migrate-workflow/SKILL.md`, `workflow/review-agent-selection.md` — operate on
changed-file path lists, not on the `### Constraints` marker sentence, so none is a
marker reader Track 2 must re-point. Every standalone "Staged-read precedence" block
(the marker-gated read caveat, per staged conventions §1.7(b)/(d):1119-1129) sits in
an in-scope file: adversarial / consistency / dimensional-review-gate-check /
review-gate-verification / risk / prompts-structural / technical review prompts,
`step-implementation.md`, and `track-code-review.md`. No inventory escapee.

**R2 [blocker] — VERIFIED**
Step 2 and the restated invariant (lines 228-240, 286-292) specify the re-pointed
marker read as **ledger-first with a plan-`### Constraints`-scan fallback**, mirroring
`determine_state`'s two-level pattern: read `s17` from the ledger; if no ledger
(in-flight pre-ledger workflow-modifying branch), fall back to the develop-era
stable-prefix scan. The D4 Risks/Caveats note (lines 73-75) states the same. A
dedicated Validation scenario was added (lines 324-326): "An in-flight pre-ledger
workflow-modifying branch (marker present, no `phase-ledger.md`) is still detected
… via the `### Constraints` fallback." The fallback now covers `s17`, closing the
iteration-1 gap (the `determine_state` fallback covered only phase/track).

**R3 [should-fix] — VERIFIED**
D8 Risks/Caveats (lines 106-117) and step 3 (lines 251-258) drop the infeasible
"keep the greppable `**PAUSED` prefix" arm and commit to the other arm: extend the
recovery grep in `mid-phase-handoff` to scan the ledger for `paused=` events. I
verified feasibility against Track 1's confirmed contract: the ledger is at
`docs/adr/<branch>/_workflow/phase-ledger.md` with grammar
`… s17=<v> paused=<v>`, and `paused` is a bare space-rejecting token
(staged precheck lines 1582, 1604), so it cannot hold `**PAUSED ` — the track's
reasoning is correct. `determine_state` reads only phase/track (precheck line 71),
matching D8's admission that the "machine-read by `determine_state`" property is not
delivered by this track. A `grep 'paused=' phase-ledger.md` recovers the pointer; the
handoff file is unchanged and still found by the `handoffs` glob.

**R4 [should-fix] — VERIFIED**
The invariant is restated as the **outcome** (lines 286-292): "a workflow-modifying
branch is detected identically — the true/false verdict is unchanged. Only the read
location and mechanism move, from a stable-prefix substring match … to a
presence/equality test of the ledger `s17` token." The ledger comparison is defined
(presence/equality of `s17`), and the note that the bare-token `s17` drops the
develop-era forward-compat path-prefix-list growth is accurate against the staged
conventions §1.7(b) (the `s17` token carries no path list; the stable-prefix
property lives on the in-plan fallback form only).

**R5 [should-fix] — VERIFIED**
`conventions.md` §1.7(c) read-side is carved into Track 2 scope as a narrow
Track-1-file carve-out (Interfaces lines 374-376; Context lines 206-210; step 2
lines 239-241), to describe the ledger-first read so the normative spec matches its
consumers. I independently confirmed the split-brain it resolves: the *staged*
conventions §1.7(b) (lines 994-1007, Track 1's deliverable) declares the marker home
is the ledger `s17` and "Track 2 re-points the consumers," while the staged §1.7(c)
detection rule (lines 1056-1063) still says "the implementer reads … `### Constraints`
… checks for the marker sentence in (b)" with no ledger acknowledgment. The carve-out
is the in-scope resolution path. VERIFIED; a residual refinement is filed as R9.

**R6 [should-fix] — VERIFIED**
Step 6 (lines 272-280) pins the `workflow.md` re-point spots by line —
`:310, :417, :768` (the `## Plan Review` references) and `:350, :743` (the
`## Final Artifacts` references) — and explicitly notes "pinned by line so the
implementer works the enumeration, not the 'Track 2 re-points this' flag grep (the
staged file carries only 2 flags for more spots)." Verified against the **staged**
`workflow.md` (the file Track 2 reads/edits, staged by Track 1): all five pins resolve
to the intended content; the two `Track 2 re-points` flags are at `:350` and `:743`,
so flag-count (2) < spot-count (5), confirming the rationale. A sixth grep hit at
`:637` is `workflow.md`'s own `## Final Artifacts (Phase 4)` section heading — not a
reference to the removed *plan* section — correctly excluded from the re-point set.

**R7 [suggestion] — VERIFIED**
Step 4 (lines 263-265) names the `inline-replanning` step-6 `## Plan Review` reset
re-point (live `inline-replanning.md:212-214`) onto the ledger review state /
`plan-review.md`. Step 5 (lines 269-271) names the `track-code-review` deferred-write
reconciliation resume signal (the "approved vs not" check, live
`track-code-review.md:1434-1440`, which today reads "track still `[ ]` in the plan
file") and re-points it onto the ledger `phase` tail for the `minimal` (no-plan) case.
Both resume signals are now named, matching the iteration-1 fix.

## Findings

### R8 [should-fix]
**Certificate**: Assumption — tier-line reader inventory complete (Part 1).
**Location**: `track-2.md` step 2 (lines 228-242) and D4 Risks/Caveats (lines 61-67);
`.claude/workflow/implementation-review.md:182-189` (the D9/D10 "Tier-driven pass
selection" block).
**Issue**: D4 states the tier line's authoritative home moves to the ledger and
"every existing reader must be re-pointed at the ledger; a missed reader silently
reads a stale or absent fact" (plan D4, track lines 60-61). Step 2's tier-line
re-point set names `track-review`, `create-final-design`, `inline-replanning`
(descriptive read), and step 1 names `consistency-review` (ledger tier line). But
`implementation-review.md` reads the **D18 tier line from `implementation-plan.md`**
to select Phase-2 passes (`:186` "D18 tier line from `implementation-plan.md`, the
single change-level …"; the §Tier-driven pass selection block, role=orchestrator
phase=2). The track touches `implementation-review.md` only for the D7 audit-summary
move (step 1) and the loader note (step 6); its tier read is neither re-pointed nor
mentioned. Likelihood the implementer notices on its own: moderate — the file is
in scope and heavily edited in step 1, but nothing in step 1/2 directs the tier read.
Impact: low-to-moderate. In `minimal` the plan stub keeps a tier line (create-plan
SKILL:842; conventions §1.1:87), and an `inline-replanning` upgrade rewrites both the
plan tier line (`inline-replanning.md:150-153`) and the ledger event (step 4), so the
plan-mirror read does not actually go stale in the common case. The defect is one of
**coherence by omission**, not a live break: D4 mandates re-pointing all tier-line
readers, the track silently leaves one on the plan, and the implementer has no written
treatment for it.
**Proposed fix**: Either (a) add `implementation-review.md`'s Phase-2 tier-driven
pass-selection read to step 2's tier-line re-point set (read the ledger `tier`, plan
mirror as fallback, parallel to `consistency-review`), or (b) add one sentence to
step 1 stating the Phase-2 tier read stays on the plan-mirror tier line and why that
is safe (the plan stub carries it in every tier and `inline-replanning` keeps the
mirror in sync). Option (a) is the more consistent with D4's "single fixed ledger
location" rationale; option (b) is acceptable if the mirror-faithfulness argument is
made explicit so the inventory is not silently incomplete.

### R9 [suggestion]
**Certificate**: Assumption — the §1.7(c) read-side carve-out resolves the
spec/consumer split-brain (Part 1).
**Location**: `track-2.md` §Context lines 206-210 and step 2 lines 239-241 (the
conventions §1.7(c) read-side carve-out); staged `conventions.md` §1.7(c) detection
rule (lines 1050-1063).
**Issue**: The R5 carve-out re-points only the §1.7(c) **read mechanism**
("ledger-first with the plan-`### Constraints` fallback"). But §1.7(c) is titled
"Detection rule — two signals, two consumers" and structurally names its first
signal "**Constraints declaration** drives the implementer enforcement gate." If
the read becomes ledger-`s17`-first, the signal is no longer a "Constraints
declaration"; a read-side-only edit that leaves the signal *labelled*
"Constraints declaration" while its body reads the ledger first is internally
inconsistent within §1.7(c). Likelihood of confusion: low (one paragraph, one
implementer). Impact: low — a coherence wrinkle in the spec, not a runtime break.
**Proposed fix**: When the implementer applies the §1.7(c) carve-out, rename or
re-frame the first signal so its label matches the ledger-first body (e.g.
"§1.7 staging-mode signal — ledger `s17`, with the `### Constraints` declaration as
the pre-ledger fallback"), not only the read sentence. Keeps §1.7(c)'s "two signals"
framing coherent with §1.7(b)'s D4 ledger-home statement.

## Evidence base

Certificates (Assumption-class; this is a workflow-prose verification, so the
five §1.7(l) prose lenses supersede the Java-oriented exposure/testability
templates — references verified as workflow paths / §-anchors via grep + Read, with
the staged copy taken for `conventions.md`, `conventions-execution.md`, and
`workflow.md` per §1.7(d); mcp-steroid PSI not used because no Java symbol is in play).

#### Assumption: removed-plan-section reader inventory complete (R1)
- **Evidence search**: `grep -rnE '## (Plan Review|Final Artifacts)'` over
  `.claude/workflow .claude/skills .claude/agents`; cross-checked each hit against the
  22-file scope.
- **Code evidence**: every removed-section reader resolves to an in-scope file or to
  Track-1-owned files (`create-plan/SKILL.md`, `conventions.md`); the lone non-scope
  hit `migrate-workflow/SKILL.md:544` is an illustrative rename example, not a reader;
  `workflow.md:637` is workflow.md's own section heading.
- **Verdict**: VALIDATED.

#### Assumption: §1.7 marker-read inventory complete (R1)
- **Evidence search**: `grep -rn 'Staged-read precedence'` and
  `grep -rn 'carries the canonical'` over the same tree; `grep -rl` for the
  staged-mechanism block.
- **Code evidence**: all nine standalone staged-read precedence blocks + the three
  §1.7(l) criteria blocks + the §1.7(c) verdict reads (implementer-rules:261-263,
  :387-407; step-implementation:468; track-code-review:252) sit in in-scope files; the
  four staged-mechanism-only files carry no marker read.
- **Verdict**: VALIDATED.

#### Assumption: ledger `paused` recovery grep is feasible (R3)
- **Evidence search**: Read staged `workflow-startup-precheck.sh` (path resolver
  :1499-1504, grammar :51-56, bare-token reject :1582, determine_state read set :71).
- **Code evidence**: ledger at `docs/adr/<branch>/_workflow/phase-ledger.md`;
  `paused=<bare-token>`; `determine_state` reads only phase/track. A `paused=` grep
  recovers the event; `**PAUSED ` cannot live in the bare token.
- **Verdict**: VALIDATED.

#### Assumption: D14 promotion half is real and correctly scoped (step 2 part ii)
- **Evidence search**: Read live `create-final-design.md` promotion block
  (:446-458) and `implementer-rules.md` §1.7(e) gate (:387-407).
- **Code evidence**: `cp -r "$STAGED_DIR/.claude/." .claude/` (:456) copies staged
  scripts, but `git add .claude/workflow .claude/skills .claude/agents` (:457) and the
  divergence `git log … -- .claude/workflow .claude/skills .claude/agents` (:450) omit
  `.claude/scripts`; the §1.7(e) gate name-check (:403) likewise omits scripts. The
  track's step 2(ii) extends exactly these three sites.
- **Verdict**: VALIDATED (the task is necessary and accurately described).

#### Assumption: tier-line reader inventory complete (R8)
- **Evidence search**: `grep -rnE 'tier line|D18 tier|reads? the tier'` over the
  workflow/skills tree; cross-checked tier-line readers vs the track's re-point set.
- **Code evidence**: `implementation-review.md:182-189` reads the D18 tier line from
  the plan for Phase-2 pass selection; the track's tier-line re-point set (steps 1/2)
  omits it.
- **Verdict**: CONTRADICTED (one tier-line reader is not covered) → R8.

#### Assumption: §1.7(c) read-side carve-out resolves the split-brain (R5 / R9)
- **Evidence search**: Read staged `conventions.md` §1.7(b) (994-1048) vs §1.7(c)
  (1050-1083).
- **Code evidence**: §1.7(b) declares the ledger home and Track-2 re-point; §1.7(c)
  still names the signal "Constraints declaration" and describes a `### Constraints`
  read. The carve-out re-points the read mechanism but the signal label is unaddressed.
- **Verdict**: VALIDATED for R5 (carve-out is the in-scope resolution); refinement
  → R9.

**PASS**
