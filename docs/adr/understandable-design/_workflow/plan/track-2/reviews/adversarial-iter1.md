<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 2, suggestion: 3}
index:
  - {id: A1, sev: should-fix, loc: "track-2.md:204-206,148-153,223-230", anchor: "### A1 ", cert: S2, basis: "4a/4b collapse (concern 3) is scoped touch-only-if on workflow.md/conventions.md/planning.md, but workflow.md:71 declares a mandatory session boundary and create-plan commit/PR mechanics hard-code two session-end commits keyed off the split — these edits are required, not conditional"}
  - {id: A2, sev: should-fix, loc: "track-2.md:121-126; create-plan/SKILL.md:717", anchor: "### A2 ", cert: E1, basis: "live create-plan Step 4b still runs the absorption cross-check INSIDE the design-review.md spawn, which Track 1 moved out to the absorption-check agent; concern 1 must relocate it and fix the now-stale Step 4b instruction, not only add an author+auditor"}
  - {id: A3, sev: suggestion, loc: "track-2.md:240 (S4)", anchor: "### A3 ", cert: V1, basis: "S4 target=tracks prose-owner gap is constructible in the staged tree between Track 1 and Track 2; already defended as the R1/A1 closing seam and concern 1 closes it — survives, rationale could cite the auditor-wiring step concretely"}
  - {id: A4, sev: suggestion, loc: "track-2.md:222-230", anchor: "### A4 ", cert: S1, basis: "three-concern bundling and the under-~12-file sizing justification hold; no split warranted, but the justification counts files and omits concern 3's high per-file edit depth across four files"}
  - {id: A5, sev: suggestion, loc: "track-2.md:54,244 (by-reference / A6)", anchor: "### A5 ", cert: A1, basis: "D15's hard by-reference dependency (gate A6) is satisfied by Track 1's staged edit-design; S6 and the by-reference retain-fallback both hold — defensive confirmation, no change required"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: S1, verdict: SURVIVES, anchor: "#### S1 "}
  - {id: S2, verdict: WEAK, anchor: "#### S2 "}
  - {id: E1, verdict: BREAKS, anchor: "#### E1 "}
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: A1, verdict: HOLDS, anchor: "#### A1 "}
  - {id: V2, verdict: INFEASIBLE, anchor: "#### V2 "}
flags: [CONTRACT_OK]
-->

# Track 2 adversarial review — iteration 1

Verdict: PASS with 2 should-fix. Track 2's three design decisions are out of
scope per the D9 narrowing (vetted at the research-log gate); this review is
confined to the three narrowed challenges — scope/sizing, cross-track-episode
reality, and invariant violation. No blocker. Both should-fix findings are
instruction-completeness and scope-accuracy gaps the implementer must close,
not decision reversals. Track 1's reused outputs all exist with the claimed
shape and the hard by-reference dependency (gate A6) is satisfied.

Tooling note: mcp-steroid reachable, IDE open on `understandable-design`
matching the working tree. This is a workflow-prose track (all `.claude/**`
Markdown); per the workflow-machinery criteria, named references were verified
as workflow paths / `§`-anchors via grep + Read. No production Java symbols are
named, so PSI symbol audits are N/A — no reference-accuracy caveat applies.

## Findings

### A1 [should-fix]
**Certificate**: Challenge S2 — the 4a/4b collapse (D15) is scoped as a
conditional touch of `workflow.md` / `conventions.md` / `planning.md`, but the
boundary is declared load-bearing in those files.
**Target**: Scope — concern 3 (boundary collapse), `## Interfaces and
Dependencies` lines 204-206 and the `## Plan of Work` concern-3 description
(148-153).
**Challenge**: Track 2's interfaces list `workflow.md` and `conventions.md`
under "touch only where the auto-resume contract or the §1.7 staging reads
describe the 4a/4b boundary that the collapse removes", and `planning.md` under
"touch only if the Step 4b authoring description references the retired
inline-derivation flow". The collapse does not merely *maybe* touch these — it
*must*. `workflow.md:71` states "The Step 4a → Step 4b boundary is a **mandatory
session boundary**, mirroring the Phase A/B/C boundaries", and `workflow.md:62-69`
spells out the auto-resume condition. Removing the boundary contradicts that
declaration head-on; leaving it stale would make `workflow.md` assert a session
boundary the implementation no longer enforces. Separately, `create-plan/SKILL.md`
itself carries the boundary in more than Step 1c: the commit/PR mechanics at
lines 1179-1208 hard-code **two** session-end commits — `Add initial design` at
end-of-4a then `Add initial implementation plan` at end-of-4b — keyed off the
split, and Step 4a's "end the session" (584-586). A collapse that only rewrites
Step 1c leaves these in place, and a single combined invocation would either run
both commits in one session or skip one. The track scopes Step 1c
(`## Interfaces` line 196-197) but not these three sites.
**Evidence**: `workflow.md:71` ("mandatory session boundary"); `create-plan/SKILL.md`
lines 1179-1197 (the two distinct `Add initial design` / `Add initial
implementation plan` session-end commits); `create-plan/SKILL.md:584-586` (Step
4a "end the session"); `planning.md:96,723` ("derives ... in a separate session
(Step 4b)"); `conventions.md:174` ("sanctioned read points (Step 4a/4b ...)").
**Proposed fix**: Reword concern 3 / the interfaces so the `workflow.md:71`
boundary declaration and the `create-plan` commit/PR mechanics (the two
session-end commits) are named as **required** collapse edits, not conditional
ones. Decide and record at Phase A whether the collapsed happy path produces one
combined commit or keeps the two-commit shape within one session, and which of
the four `create-plan` sites (Step 1c, Step 4a end-session, the Design→plan
boundary section 527-551, the commit mechanics 1179-1208) the rewrite touches.
The `conventions.md:174` cross-ref is genuinely "touch only if it becomes
inaccurate"; that one keeps its conditional framing.

### A2 [should-fix]
**Certificate**: Assumption test E1 — Track 2 assumes wiring the loop into
`create-plan` Step 4b is "replace the planner-inline derivation with an author
spawn", but the live Step 4b also embeds an absorption cross-check that Track 1
relocated.
**Target**: Assumption — `## Plan of Work` concern 1 (lines 121-126) and the
D11 create-plan-facet rationale.
**Challenge**: Concern 1 frames the Step 4b rework as "replace the planner-inline
track derivation with an author spawn ... then run the same dual-clean inner loop
with the readability auditor and a track-decision-record absorption check as the
second check." That captures the *target* state. But the *live starting* state
is more entangled than "planner authors inline, then spawns design-review with
`target=tracks`": live `create-plan/SKILL.md:717` instructs the Step 4b cold-read
spawn to "run the **absorption-completeness cross-check** (D8)" as part of the
`design-review.md` `target=tracks` spawn. Track 1 has already **removed**
absorption from `design-review.md` — staged `design-review.md:226-233` says the
absorption cross-check does not run on this reviewer and "is the `absorption-check`
agent's." So after Track 1 lands, the live Step 4b instruction at line 717 points
at a cross-check the reviewer no longer performs. Concern 1 must therefore not
only add the author + auditor; it must relocate the absorption cross-check onto
the separate `absorption-check` agent spawn and rewrite the line-717 instruction.
The track's Plan of Work names "track-decision-record absorption check as the
second check," so the intent is present, but it does not flag that the live Step
4b text currently embeds absorption inside the reviewer — an easy instruction to
leave stale, which would yield a Step 4b that names a cross-check no spawned agent
runs.
**Evidence**: live `create-plan/SKILL.md:711-718` (Step 4b cold-read "run the
absorption-completeness cross-check (D8)" inside the `design-review.md` `target=tracks`
spawn, `subagent_type: general-purpose`); staged `design-review.md:226-233`
(absorption "is the `absorption-check` agent's ... this reviewer reads no log");
staged `design-review.md:99-102` ("no `research_log_path` is passed here").
**Proposed fix**: Add to concern 1 an explicit step: relocate the absorption-
completeness cross-check off the Step 4b `design-review.md`/`comprehension-review`
spawn onto a separate `absorption-check` agent spawn (mirroring the `edit-design`
Step 4 relocation Track 1 did at the design surface), and rewrite the live Step
4b cold-read instruction (line 717 region) so it no longer asks the reviewer to
run absorption. This is the track-path analog of the `edit-design`
`SKILL.md:478` absorption-move Track 1 Step 2 performed.

### A3 [suggestion]
**Certificate**: Violation scenario V1 — S4 (no surface runs the prose AI-tell
axis on both auditor and comprehension reviewer) admits a constructible
zero-owner window on `target=tracks`, already defended.
**Target**: Invariant S4 (`track-2.md:240`).
**Challenge**: After Track 1 lands and before Track 2 lands (a window that exists
in the staged tree as a stacked-diff seam), the `target=tracks` surface has the
prose AI-tell axis on **neither** reviewer: Track 1's staged `design-review.md:226-229`
removes it from the reviewer and explicitly says "the auditor's track-surface
wiring lands when `create-plan` Step 4b spawns it" — i.e. in Track 2. So a literal
read of S4 ("never neither") is violated in the staged tree between the two
tracks. The scenario is real but **already defended**: Track 1's own track file
records it as the R1/A1 co-promotion seam (Track 1 `## Invariants & Constraints`
constraint at lines 499, and the sizing justification at 472-475), staging keeps
the whole branch non-live until one Phase 4 promotion (S7), so the live workflow
never runs `target=tracks` without a prose owner, and Track 2 concern 1's "apply
the one-owner-per-surface rule (S4): the auditor owns the prose axis on the track
cold-read" (track-2.md:127-129) is exactly the wiring that closes it. The
invariant survives because S7 bounds the violation to the staged tree.
**Evidence**: staged `design-review.md:226-229`; Track 1 `track-2.md` constraint
line 499 and sizing justification 472-475; Track 2 `track-2.md:127-129`
(auditor owns the track prose axis).
**Proposed fix**: None required — the decision holds. Optional rationale
strengthening: have concern 1 cite Track 1's R1/A1 seam by name as the gap it
closes, so a Track-2-only reviewer sees the seam is closed here rather than
re-deriving it. The track already states S4 on the track surface; this is a
cross-reference nicety, not a correctness gap.

### A4 [suggestion]
**Certificate**: Challenge S1 — the three-concern bundling and the under-~12-file
sizing justification.
**Target**: Scope/sizing — `## Interfaces and Dependencies` track-sizing
justification (lines 222-230).
**Challenge**: I argued the strongest split alternative: peel concern 3 (the
4a/4b collapse) into its own track, since it is a machinery change to the
auto-resume contract that is conceptually distinct from "wire the readability
loop into two more authoring points." The split fails on the track's own stated
ground and on the dependency graph: concern 3 hard-depends on concern 1 (the
Step 4b loop must exist before the collapse can route a combined invocation
through it) and on Track 1's by-reference contract; concerns 1 and 3 both edit
`create-plan/SKILL.md`, so co-locating them avoids a second cold-read pass over
the same file (track-2.md:148-150, 228-230). Splitting would create a third
track that depends on this one and re-reads `create-plan/SKILL.md` — strictly
worse. So bundling survives. The weakness is in the *justification's framing*:
it argues "the three units ... cost no more to review than splitting them"
purely on autonomy and file count, but concern 3 is not a low-edit-depth unit —
per A1 it rewrites four cross-file sites (Step 1c, Step 4a end-session, the
boundary section, the commit mechanics) plus `workflow.md:71`. The under-~12-file
floor is satisfied by count, but the review load is dominated by concern 3's edit
depth, not by file count.
**Evidence**: track-2.md:148-153 (concern 3 depends on the Step 4b loop and
Track 1's by-reference contract); track-2.md:228-230 (co-location avoids a second
pass); A1's enumeration of concern 3's four `create-plan` sites + `workflow.md:71`.
**Proposed fix**: None to the track structure (no split, no merge). Optional:
the sizing justification could note that concern 3 carries the track's heaviest
per-file edit depth (the auto-resume-contract rewrite across `create-plan` and
`workflow.md`), so the ~5-file count understates its review weight — a more
honest sizing signal for the Phase A decomposer choosing step granularity.

### A5 [suggestion]
**Certificate**: Assumption test A1 — D15's hard by-reference dependency (gate
A6) is satisfied, and S6 / the by-reference retain-fallback hold.
**Target**: Assumption / Invariant — by-reference constraint and S6
(`track-2.md:54, 241, 244`).
**Challenge**: D15 states the collapse "depends hard on Track 1's by-reference
orchestration" and "if by-reference cannot hold, the boundary is retained (gate
A6)." I tested whether by-reference actually holds in Track 1's realized output.
It does: staged `edit-design/SKILL.md:746-748` has the author "return a thin
summary only, never the draft (the by-reference contract)", and the failure-mode
block at 1041-1049 handles the violation case (draft on disk at `output_path`,
do not re-spawn) without re-accumulating context. The author agent definition
(`design-author.md:54-56`) independently restates the contract. So gate A6's
precondition is met and the collapse is not blocked. I also tried to construct an
S6 violation (Phase 4 re-asserting a superseded log decision): the fidelity
check sources from episodes and routes no-episode-trace claims to PSI
(track-2.md:39, D10), which structurally prevents the doc from matching a log
decision an episode superseded — the failure is INFEASIBLE under the stated
mechanism (cert V2). Both hold.
**Evidence**: staged `edit-design/SKILL.md:746-748` and 1041-1049 (thin-summary
return + on-disk fallback); `design-author.md:54-56` (by-reference contract);
track-2.md:39-40 (D10 fidelity sources from episodes, PSI for no-trace residual).
**Proposed fix**: None — defensive confirmation that the hard dependency is
satisfied. Recording it here so the Phase A decomposer and the Phase B
implementer know gate A6 is green (by-reference holds) and the collapse may
proceed rather than retaining the boundary.

## Evidence base

#### S1 Challenge: three-concern bundling and the under-~12-file sizing justification
- **Chosen approach**: Bundle three downstream reuse units (Step 4b track loop,
  Phase 4 fidelity check, 4a/4b collapse) into one ~5-file track, cut from
  Track 1 at the core-to-downstream dependency boundary.
- **Best rejected alternative**: Split concern 3 (the 4a/4b collapse) into its
  own track, isolating the auto-resume-contract machinery change from the
  readability-loop wiring.
- **Counterargument trace**:
  1. Concern 3 is a machinery change to the `create-plan` auto-resume contract
     (track-2.md:148-153), conceptually distinct from "wire the loop into two
     more authoring points" — a candidate for its own PR on cohesion grounds.
  2. The split would instead create a third track depending on this one
     (concern 3 needs the Step 4b loop to exist) that re-reads
     `create-plan/SKILL.md`, which concerns 1 and 3 both edit (track-2.md:228-230).
  3. This produces strictly more review cost (a third stacked PR + a second
     cold-read pass over `create-plan/SKILL.md`) for no isolation gain, since
     the dependency chain forces serial review anyway.
- **Codebase evidence**: track-2.md:148-150 (concern 3 depends on the Step 4b
  loop + Track 1 by-reference); 228-230 (co-location avoids a second pass over
  the shared file).
- **Survival test**: YES (rationale holds — no split). The justification's
  framing is WEAK on one axis only (it counts files, not concern 3's edit
  depth), surfaced as suggestion A4.

#### S2 Challenge: the 4a/4b collapse is scoped as a conditional touch of workflow.md / conventions.md / planning.md
- **Chosen approach**: List `workflow.md`, `conventions.md` ("touch only where
  the auto-resume contract or §1.7 staging reads describe the 4a/4b boundary")
  and `planning.md` ("touch only if the Step 4b authoring description references
  the retired inline-derivation flow") as conditional edits.
- **Best rejected alternative**: Name the `workflow.md:71` boundary declaration
  and the `create-plan` commit/PR mechanics as **required** collapse edits.
- **Counterargument trace**:
  1. `workflow.md:71` declares "The Step 4a → Step 4b boundary is a mandatory
     session boundary, mirroring the Phase A/B/C boundaries", with the
     auto-resume condition at 62-69.
  2. Collapsing the boundary contradicts that statement directly; a conditional
     "touch only if" leaves `workflow.md` asserting a boundary the code no
     longer enforces — an instruction-completeness defect.
  3. `create-plan/SKILL.md:1179-1208` hard-codes two session-end commits keyed
     off the split (`Add initial design` at 4a, `Add initial implementation
     plan` at 4b); a one-invocation happy path must reconcile these, so they are
     required edits, not conditional ones.
- **Codebase evidence**: `workflow.md:71` and 62-69; `create-plan/SKILL.md:1179-1208`,
  584-586 (Step 4a end-session); `conventions.md:174`; `planning.md:96,723`.
- **Survival test**: WEAK — the decision to collapse survives, but the scope
  framing understates the required cross-file edits and risks a stale
  `workflow.md` declaration. Strengthen to should-fix A1.

#### E1 Assumption test: the Step 4b rework is "replace planner-inline derivation with an author spawn"
- **Claim**: Wiring the loop into Step 4b is replacing the inline planner
  authoring with an author spawn plus the dual-clean loop (track-2.md:121-126).
- **Stress scenario**: After Track 1 lands, the live Step 4b cold-read still
  instructs the `design-review.md` `target=tracks` spawn to run the
  absorption-completeness cross-check (line 717), but Track 1 moved absorption
  out of that reviewer onto the `absorption-check` agent. An implementer who
  reads concern 1 literally ("add an author spawn + auditor + absorption second
  check") may leave the line-717 instruction pointing at a cross-check the
  reviewer no longer performs.
- **Code evidence**: live `create-plan/SKILL.md:711-718` (absorption cross-check
  inside the `target=tracks` `design-review.md` spawn); staged
  `design-review.md:226-233` and 99-102 (absorption is the `absorption-check`
  agent's; no `research_log_path` passed; reviewer reads no log).
- **Verdict**: BREAKS — the assumption that Step 4b's only change is "add an
  author spawn" is incomplete; the absorption relocation and the stale line-717
  instruction must be handled. Concern 1's "track-DR absorption as second check"
  captures the target but not the live-text reconciliation. Surfaced as A2.

#### V1 Violation scenario: S4 — no prose-judged surface runs the prose axis on neither reviewer
- **Invariant claim**: Every prose-judged surface runs the prose AI-tell axis on
  exactly one reviewer, never both and never neither (track-2.md:240).
- **Violation construction**:
  1. Start state: Track 1 landed in the staged tree; Track 2 not yet applied.
  2. Action sequence: Track 1's staged `design-review.md:226-229` removed the
     prose AI-tell scan from the `target=tracks` reviewer and deferred the
     auditor's track-surface wiring to "when `create-plan` Step 4b spawns it"
     (Track 2).
  3. Intermediate state: the `target=tracks` surface has the prose axis on the
     reviewer = no, and on a track-path auditor = not-yet-wired.
  4. Violation point: staged tree, `target=tracks` prose axis owner = none
     (literal "never neither" breach).
  5. Observable consequence: none in the live workflow — S7 keeps the whole
     branch staged until one Phase 4 promotion, so the live `target=tracks`
     never runs without a prose owner; the gap is staged-tree-only and closes
     when Track 2 concern 1 wires the auditor.
- **Feasibility**: CONSTRUCTIBLE in the staged tree, but bounded by S7 to a
  stacked-diff seam Track 1 already documented (R1/A1). Defended; surfaced as
  suggestion A3.

#### A1 Assumption test: D15's by-reference dependency (gate A6) holds in Track 1's realized output
- **Claim**: The 4a/4b collapse may proceed because Track 1's by-reference
  orchestration holds; if it could not, the boundary would be retained
  (track-2.md:54, 244; gate A6).
- **Stress scenario**: Inspect Track 1's staged `edit-design` and the author
  agent definition for whether the author actually returns a thin summary and
  whether a violation re-accumulates orchestrator context.
- **Code evidence**: staged `edit-design/SKILL.md:746-748` ("returns a thin
  summary only, never the draft — the by-reference contract") and 1041-1049
  (violation handled: draft on disk at `output_path`, do not re-spawn);
  `design-author.md:54-56` (by-reference return contract restated).
- **Verdict**: HOLDS — gate A6's precondition is met; the collapse is not
  blocked and need not retain the boundary. Confirmed defensively in A5.

#### V2 Violation scenario: S6 — Phase 4 re-asserts a superseded log decision
- **Invariant claim**: `design-final.md` never re-asserts a log decision an
  episode superseded (track-2.md:241, S6).
- **Violation construction**:
  1. Start state: a planned decision was scoped down by an inline replan during
     execution, recorded in a step episode.
  2. Action sequence: the Phase 4 fidelity check (D10) sources doc-against-
     episodes text matching, with PSI covering any `design-final.md` claim
     lacking an episode trace (track-2.md:39-40).
  3. Violation point attempted: for the doc to re-assert the superseded decision,
     it would have to match a log decision the episode contradicts — but the
     check matches the doc against the *episodes* (which carry the supersession),
     not the log, and a no-episode-trace claim routes to PSI against the code.
  4. Result: the mechanism structurally prevents the doc from passing while
     re-asserting a decision an episode superseded.
- **Feasibility**: INFEASIBLE under the stated D10 mechanism — the only residual
  is a silent scope-down no episode records, which the coverage residual (PSI on
  no-episode-trace claims) is explicitly designed to catch (track-2.md:40).
  Confirmed defensively in A5.
