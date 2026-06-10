<!-- MANIFEST
findings: 5   severity: {blocker: 1, should-fix: 0, suggestion: 4}
index:
  - {id: A62, sev: blocker, loc: "research-log.md:960-967 (B2 Why, two-surface diagnosis) + :980-984 (B2 ripple, access wiring)", anchor: "### A62 ", cert: C2, basis: "both wiring options are insufficient as written: the phase-only diagnosis misses the role axis (§2.5 roles lack planner, the /create-plan role that owns every third-scope spawn), and the explicit-instruction alternative fails §1.8(e) subset validation and the §1.8(f) reader-side row match standalone"}
  - {id: A63, sev: suggestion, loc: "research-log.md:984-989 (B2 ripple, variant deferral)", anchor: "### A63 ", cert: C3, basis: "if the deferred sub-question resolves to the variant, as this branch's own iteration>=2 practice already does de facto, the §Verdict-producer row (roles without planner, phases 2,3A,3C) needs the same two-axis extension the wiring item does not name"}
  - {id: A64, sev: suggestion, loc: "research-log.md:950-952 (B2 lead)", anchor: "### A64 ", cert: C4, basis: "B1's adopted PR-description summary makes B2's carrier sentence stale for minimal: no adr.md fold consumes the gate records there; one clause amends it"}
  - {id: A65, sev: suggestion, loc: "research-log.md:936-938 (B1 ripple, Part 7 item)", anchor: "### A65 ", cert: C5, basis: "Part 7 asserts the every-tier fold in two places the item does not name: the TL;DR (design.md:933-936) and the §Edge cases first bullet (:998-1001)"}
  - {id: A66, sev: suggestion, loc: "research-log.md:922-926 (B1 lens pin) vs :936-944 (ripple)", anchor: "### A66 ", cert: C6, basis: "the pinned lens semantics (centrally-matched categories plus user additions; minimal runs lens-free) have no design destination in the ripple; Part 3 §Domain priming and Part 1's matched-categories sentence keep the ambiguity the pin resolves"}
verdicts:
  - {id: A54, verdict: VERIFIED}
  - {id: A55, verdict: VERIFIED}
  - {id: A56, verdict: VERIFIED}
  - {id: A57, verdict: VERIFIED}
  - {id: A58, verdict: VERIFIED}
  - {id: A59, verdict: VERIFIED}
  - {id: A60, verdict: VERIFIED}
  - {id: A61, verdict: VERIFIED}
overall: FAIL
evidence_base: {section: "## Evidence base", certs: 8, breaks: 1, holds: 3}
cert_index:
  - {id: C1, verdict: HOLDS, anchor: "#### C1 "}
  - {id: C2, verdict: BREAKS, anchor: "#### C2 "}
  - {id: C3, verdict: FRAGILE, anchor: "#### C3 "}
  - {id: C4, verdict: CONSTRUCTIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: CONSTRUCTIBLE, anchor: "#### C5 "}
  - {id: C6, verdict: FRAGILE, anchor: "#### C6 "}
  - {id: C7, verdict: HOLDS, anchor: "#### C7 "}
  - {id: C8, verdict: HOLDS, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

# Log-adversarial gate — B1 + B2 batch, iteration 2

Verification plus whole-batch re-challenge (D15) of the revised 15:05Z (B1)
and 15:07Z (B2) entries, the 15:40Z iteration-1 gate record, and the 08:40Z
A4 forward marker. All eight iteration-1 findings are VERIFIED as applied
and citation-accurate. The re-challenge of the material the fixes added
yields one blocker: B2's new access-wiring clause diagnoses the §2.5
exclusion as phase-only and offers a non-viable alternative — the `planner`
role that owns every third-scope spawn is also missing from §2.5's roles
list, so the named edit wires the reviewer and silently leaves the spawning
party barred. B1 survives with two suggestions; B2 carries one further
suggestion each on its variant deferral and its lead sentence. Count
validation: `grep -cE '^### [A-Z]+[0-9]+ '` over this file returns 5.

## Verdicts

**A54 — VERIFIED.** B1's lead adopts the PR-description verdict summary
(research-log.md:906-909) and the Alternatives list records the no-residue
initial form as dominated (:933-935); both halves of the proposed fix
landed.

**A55 — VERIFIED.** The phantom Part-6 ripple item is gone; the ripple
names the Part 2 §Edge cases first bullet with the design.md:353-355 quote
and the `full`/`lite` qualification (:938-941). The cited bullet re-resolves
at design.md:353-355.

**A56 — VERIFIED.** "Unprimed by construction" became "typically unprimed,"
the acceptance now rests on Gate-1 stakes, the lens derivation is pinned
(centrally-matched categories plus explicit user additions), and the
user-added-lens hatch is addressed (:920-926).

**A57 — VERIFIED.** B1's lead declares "NARROWS the 08:40Z A4 resolution's
every-tier fold" (:902) and the forward bracket sits in place on the 08:40Z
A4 clause (:485-487) with the correct narrowed scope, matching the Q3/A29
hygiene precedent.

**A58 — VERIFIED.** The lead now says `minimal`'s Phase 4 "carries no
`adr.md` authoring step" and explicitly preserves the §1.7(f) staged-mirror
promotion on a workflow-modifying `minimal` branch (:903-906).

**A59 — VERIFIED.** The Why is reworded as mandated — schema and read
discipline transfer; two §2.5 surfaces need explicit wiring (:960-967) —
and the ripple gains the access-wiring and location/lifecycle items
(:980-984). The phase scoping and the track-anchored-home citations
re-verify against conventions-execution.md:13/:474 and :276-282. The added
wiring text is new material and fails a fresh challenge (A62); that is a
finding against the addition, not a defect in A59's application, which
followed iteration 1's own proposed wording.

**A60 — VERIFIED.** Naming is anchored to track-review.md:641 (confirmed:
`<type>-iter<N>.md`, file-when-handed-a-path mode) and the verdict-producer
sub-question is recorded as the open implementation decision (:984-989).

**A61 — VERIFIED.** Ripple item 1 carries the qualifying parenthetical
("`conventions-execution.md`'s S4/S6 — design.md owns an unrelated S4",
:975-977); design.md:916-917 confirms the colliding design-side S4.

## Findings

### A62 [blocker]
**Certificate**: C2 (Assumption test: the access-wiring clause reaches both
gate parties)
**Target**: B2's revised `Why` (the two-surface diagnosis,
research-log.md:960-967) and the ripple's §2.5 access-wiring item
(:980-984) — material added by the A59 fix.
**Challenge**: The diagnosis attributes the §2.5 exclusion to phases alone
("the §2.5 TOC row scopes it to phases 2/3A/3B/3C/4, so under the §1.8 TOC
protocol neither the third-scope reviewer nor the spawning `create-plan`
orchestrator may read it"), and the wiring item offers two alternatives:
TOC-row phase extension, or an explicit read-§2.5 instruction. Both fail as
written. (a) Role axis: §2.5's roles list carries no `planner`, and the
role enum maps the /create-plan agent — the spawning party of every
third-scope run, both the Phase 0→1 gate and the D15 batch gates — to
`planner`, not `orchestrator`. A phase-only extension wires the reviewer
(`reviewer-adversarial` is in the roles list) and leaves the planner barred
on the role axis. The failure is silent: no CI rule catches a reader not
reading, and the planner owns the §2.5-defined duties B2 itself names
(manifest count validation, partial-fetch routing). §2.5's own prose at
conventions-execution.md:588 already names "the orchestrator or planner"
as a CONTRACT_VIOLATION fallback owner, so the roles-list omission is a
latent gap the third scope turns load-bearing. (b) The explicit-instruction
alternative cannot stand alone: a suffixed cross-ref claiming the gate
phase fails §1.8(e) subset validation against the target's `2,3A,3B,3C,4`
annotation (a CI blocker), backtick-wrapping is convention-reserved for
non-annotatable targets, and the §1.8(f) reader-side row match stops a
protocol-following reader regardless of where the read instruction came
from. So option (b) collapses into option (a) plus prose, and option (a)
as enumerated repairs one of two parties.
**Evidence**: conventions-execution.md:13/:474 (§2.5 roles+phases
annotation, no `planner`, phases `2,3A,3B,3C,4`), :588 (planner named as
fallback owner in prose); conventions.md §1.8(a) ("planner — /create-plan
agent (Phases 0, 1)"; "orchestrator — /execute-tracks session-level
driver"), §1.8(e) (citer suffix must subset the target's annotation; CI
error on violation), §1.8(f) (two-axis reader-side row match).
**Proposed fix**: Reword the diagnosis to both axes — the §2.5 row excludes
the gate on phases for both parties and on roles for the planner — and make
the wiring item one required edit: extend the §2.5 annotation/TOC row (and
its subsection rows as applicable) with `planner` in roles and the gate
phase(s) in phases. `planner` is already in the closed role enum, so this
is a plain annotation edit, not a workflow-format commit. Prose pointers in
the new scope section and the Step-4 gate prose are additive, not an
alternative.

### A63 [suggestion]
**Certificate**: C3 (Assumption test: the variant deferral leaves the
wiring complete)
**Target**: B2's verdict-producer-variant deferral (ripple,
research-log.md:984-989, added by the A60 fix).
**Challenge**: The deferral records the manifest-shape question as open,
but the branch's own gate practice has answered it: every iteration≥2 run,
including the run that produced this file, emits per-prior-finding verdicts
plus new findings — the §2.5 verdict-producer shape. If implementation
confirms the variant, the §Verdict-producer manifest variant row (roles
`orchestrator,reviewer-plan,reviewer-technical,reviewer-risk,reviewer-adversarial`,
phases `2,3A,3C`) needs the same two-axis extension as the parent row, and
the access-wiring item names only "the §2.5 TOC row."
**Evidence**: conventions-execution.md §Verdict-producer manifest variant
annotation (phases `2,3A,3C`, no `planner`); research-log.md:883-884
(14:55Z verdicts-plus-new-findings run), :990-1017 (15:40Z spawn contract);
this file's own manifest.
**Proposed fix**: One clause in the wiring item: if the variant is adopted,
its subsection row takes the same roles-plus-phases extension.

### A64 [suggestion]
**Certificate**: C4 (Violation scenario: a minimal-tier verdict-path audit
from B2's lead)
**Target**: B2's lead sentence ("The log's gate records remain the verdict
carrier Part 7's `adr.md` fold consumes," research-log.md:950-952).
**Challenge**: B1's iteration-1 revision (fold narrowed to `full`/`lite`,
PR-description summary adopted for `minimal`) makes the sentence stale for
`minimal`: no fold consumes the gate records there; the PR-description
verdict summary does. A ledger reader auditing `minimal`'s verdict path
from B2 alone reads a consumer its sibling abolished two entries earlier —
the sibling-interaction case D15's whole-batch re-challenge exists for. The
load-bearing half of the sentence (review files are never the verdict
carrier; the log is) survives in every tier.
**Evidence**: research-log.md:901-910 (B1 lead, post-fix) vs :950-952 (B2
lead).
**Proposed fix**: "the verdict carrier Phase 4's audit trail consumes (the
`adr.md` fold in `full`/`lite`; the PR-description summary in `minimal`)."

### A65 [suggestion]
**Certificate**: C5 (Violation scenario: Mutation 5 executes B1's Part-7
ripple item as enumerated)
**Target**: B1's ripple, Part-7 item granularity (research-log.md:936-938).
**Challenge**: "Part 7 §The Phase 4 audit trail + D10" names the section
body (design.md:971-984) and the D-record reference (:1012-1014), but
Part 7 asserts the every-tier fold in two more places the item does not
name: the TL;DR (:933-936, "folds the research log's resolved adversarial
verdicts into `adr.md` in every tier") and the §Edge cases first bullet
(:998-1001, "The `adr.md` fold runs in every tier"). Executing the ripple
as enumerated leaves both asserting what B1 narrows. This is within-Part
granularity under an unambiguous semantic instruction, so a suggestion
rather than A55's cross-Part should-fix. Checked in the same pass: the
Part-1 item is not an A55-class phantom — Part 1 carries no fold text, but
the tier map is the natural artifacts-per-tier surface for the
no-`docs/adr/`-entry note, so the item has a real edit to perform.
**Evidence**: grep for the fold's every-tier mentions over design.md
Part 7: lines 935, 979, 998.
**Proposed fix**: Widen the item to "Part 7's fold mentions (TL;DR, §The
Phase 4 audit trail, §Edge cases first bullet, D10 reference)."

### A66 [suggestion]
**Certificate**: C6 (Assumption test: the lens pin survives into the
carrier)
**Target**: B1's pinned lens semantics (research-log.md:922-926) vs its
ripple (:936-944).
**Challenge**: The pin — lenses derive from the centrally-matched
categories plus explicit user additions, so a `minimal` gate runs lens-free
unless the user adds one — resolves the matched-set ambiguity A56 found,
but the ripple gives it no design destination. The log dies at the Phase-4
cleanup; Part 3 §Domain priming ("the tier's matched risk categories") and
Part 1's matched-categories sentence keep the Gate-1-no reading open for
every future design reader and for the implemented Step-4 gate prose. A
resolution minted to close an adversarial finding is lost if no carrier
absorbs it.
**Evidence**: design.md:245-251 ("the gate records which categories
matched," stated under the Gate-1-yes framing), :419-436 (§Domain priming);
research-log.md:936-944 (no Part-1/Part-3 ripple item).
**Proposed fix**: Add a ripple item: Part 3 §Domain priming gains the
central-plus-user-additions derivation clause, and Part 1's "records which
categories matched" takes a "centrally" qualifier in the same mutation.

## Evidence base

**Decision challenges**

#### C1 Challenge: B1's adopted PR-description verdict-summary mechanism
- **Chosen approach**: `minimal`'s Phase 4 folds a two-line gate-verdict
  summary into the PR description; the squash-merge carries it into
  develop's `git log`.
- **Best rejected alternative**: None stronger found; the probe instead
  stresses the mechanism's dependencies — writer, timing, and conflicts
  with the repo's PR conventions.
- **Counterargument trace**:
  1. Writer and timing: Phase 4 in `minimal` ends with the cleanup commit
     and final push; the description fold is a `gh pr edit`-class action by
     the same Phase-4 agent before the user's ready-flip and merge, so the
     ordering rides the normal flow (the user merges after the agent
     reports Phase 4 done).
  2. CLAUDE.md conflicts: §Pull Requests mandates the PR template's
     Motivation section — the summary is two additional description lines
     beside it, template intact (`.github/pull_request_template.md`
     carries only Title and Motivation). The keep-description-in-sync duty
     makes a late description edit the documented norm. "1 PR = 1 squashed
     commit" with the message "built from the PR title and description" is
     the repo's stated squash behavior, so the git-log claim rests on
     documented convention, not on GitHub defaults.
  3. Residual exposure: a user who merges before the fold loses the two
     lines — the same exposure every description-carried duty (Motivation,
     sync) already has; no new failure class.
- **Codebase evidence**: CLAUDE.md §Pull Requests;
  `.github/pull_request_template.md`.
- **Survival test**: YES — the mechanism survives; no finding. One
  terminology nit not worth a finding: "in-tree evidence" is strictly
  in-history evidence (a commit message is not in the checkout tree); the
  contrast the entry needs — recoverable from any clone without GitHub —
  is true as stated.

**Assumption tests**

#### C2 Assumption test: the access-wiring clause reaches both gate parties
- **Claim**: "TOC-row phase extension, or an explicit read-§2.5 instruction
  in the new scope section and the Step-4 gate prose" wires §2.5 to the
  Phase 0→1 boundary.
- **Stress scenario**: Implement each option literally and walk both
  parties through §1.8. Leg (a), phase-only extension: the planner (the
  role of every third-scope spawning party per §1.8(a)) checks the §2.5
  row — phases now match, roles
  (`orchestrator,decomposer,implementer,reviewer-*`) still exclude
  `planner`, the §1.8(f) row match fails, "do not read further." Leg (b),
  instruction-only: a suffixed ref claiming the gate phase fails §1.8(e)
  subset validation against the target's `2,3A,3B,3C,4` annotation (CI
  blocker); a backticked ref dodges validation but is reserved for
  non-annotatable targets, and the (f) reader-side match still fails at
  the row.
- **Code evidence**: conventions-execution.md:13/:474 (annotation), :588
  (planner named in §2.5's own fallback prose); conventions.md
  §1.8(a)/(e)/(f).
- **Verdict**: BREAKS — the wiring as enumerated repairs the reviewer's
  path and not the planner's, and its alternative is non-viable
  standalone. → A62

#### C3 Assumption test: the variant deferral leaves the wiring complete
- **Claim**: Whether iteration≥2 third-scope runs use the verdict-producer
  variant can wait for implementation with no further wiring consequence.
- **Stress scenario**: Implementation says yes — the de-facto answer,
  since every iteration≥2 run on this branch emits verdicts plus new
  findings — and the variant's subsection row (roles without `planner`,
  phases `2,3A,3C`) then needs the same extension the wiring item never
  names.
- **Code evidence**: conventions-execution.md §Verdict-producer manifest
  variant annotation; research-log.md:883-884, :990-1017; this file's
  manifest.
- **Verdict**: FRAGILE — the deferral is safe only if the wiring item
  grows the contingent clause. → A63

#### C6 Assumption test: the lens pin survives into the carrier
- **Claim**: Pinning the lens-derivation semantics inside B1's acceptance
  rationale settles the matched-set question.
- **Stress scenario**: The log is deleted at the Phase-4 cleanup; the
  ripple lists no Part-1/Part-3 destination; the design text carrying the
  ambiguity (design.md:245-251 under the Gate-1-yes framing, :419-436) is
  what Step 4b and the implemented gate prose read.
- **Code evidence**: research-log.md:936-944; design.md:245-251, :419-436.
- **Verdict**: FRAGILE — the pin's content is sound and consistent with
  the design's closest reading; only its destination is missing. → A66

#### C7 Assumption test: the third-scope location/lifecycle clause and the interim home are sane
- **Claim**: A directory under `_workflow/`, commit-at-return, and the
  Phase-4 sweep cover the third scope's file lifecycle.
- **Stress scenario**: Collisions, sweep coverage, resume semantics. Grep
  over `.claude/workflow/`, `.claude/skills/`, and this branch's
  `_workflow/` finds no competing claimant for `_workflow/reviews/`; the
  Phase-4 cleanup removes all of `_workflow/`, so the sweep needs no new
  rule; commit-at-return's rationale (the committed file is the resume
  precondition, D10) transfers verbatim to a mid-gate `/clear`; a
  top-level home sidesteps the `plan/*` glob caution that track-anchored
  review files carry; and the track-anchored home is correctly diagnosed
  as uninstantiable before Step 4b (conventions-execution.md:276-282).
- **Code evidence**: conventions-execution.md:276-299; grep results (no
  `_workflow/reviews` mention outside this gate's own files).
- **Verdict**: HOLDS — strengthens B2; no finding.

#### C8 Assumption test: the revised entries' remaining citations are accurate
- **Claim**: The fixes' new citations resolve to real anchors and say what
  the entries claim.
- **Stress scenario**: Re-resolve each. track-review.md:641 carries
  `<type>-iter<N>.md` and file-when-handed-a-path mode; §2.5's
  file-persistence-is-load-bearing sentence matches
  conventions-execution.md ("The file is what makes resume cheap"); the
  track-anchored lifecycle home and commit-at-return sit at :276-299; "4
  and 4+1 iterations" matches the ratified 13:45Z D14 entry
  (research-log.md:718-719) and the 06:30Z-08:10Z / 11:12Z-13:00Z
  records; the 08:40Z forward marker (:485-487) states B1's exact narrowed
  scope; design.md:916-917 owns the colliding S4; the
  `phase1-creation`-passes-no-path claim matches adversarial-review.md
  §Output Format.
- **Code evidence**: as listed per item.
- **Verdict**: HOLDS — supports the eight VERIFIED verdicts.

**Invariant / violation scenarios**

#### C4 Violation scenario: a minimal-tier verdict-path audit from B2's lead
- **Invariant claim**: The ledger reads consistently entry-to-entry — the
  gate's own internal-consistency standard since the 06:30Z dogfood run.
- **Violation construction**:
  1. Start state: both revised entries ratify as-is.
  2. Action sequence: a reader audits "where do `minimal`'s gate verdicts
     go after cleanup?" and lands on B2's lead (research-log.md:950-952).
  3. Intermediate state: the lead names "Part 7's `adr.md` fold" as the
     consumer of the gate records, tier-unqualified.
  4. Violation point: B1 (:901-910), revised in the same iteration, sheds
     the fold for `minimal` and substitutes the PR-description summary;
     the freshly-revised siblings disagree on `minimal`'s consumer.
  5. Observable consequence: a Mutation-5 author seeding Part-3/Part-7
     wording from B2's lead reintroduces the every-tier-fold framing B1
     removed.
- **Feasibility**: CONSTRUCTIBLE — it requires only reading the two
  entries in order. → A64

#### C5 Violation scenario: Mutation 5 executes B1's Part-7 ripple item as enumerated
- **Invariant claim**: Post-mutation, design.md nowhere asserts the
  every-tier fold.
- **Violation construction**:
  1. Start state: B1's ripple as written.
  2. Action sequence: the mutation edits §The Phase 4 audit trail
     (design.md:971-984) and the D10 References line (:1012-1014), plus
     the Part-1, Part-2, and D12 items as listed.
  3. Intermediate state: the Part-7 TL;DR (:933-936) and the §Edge cases
     first bullet (:998-1001) are untouched; neither is named.
  4. Violation point: Part 7 contradicts itself between its TL;DR/edge
     bullet and its body.
  5. Observable consequence: Part-7 readers (the Step-4b planner, the
     Phase-2 consistency reviewer) get both stories.
- **Feasibility**: CONSTRUCTIBLE — the default outcome of literal
  execution, though within one Part under an unambiguous parenthetical
  instruction, so the severity stays a suggestion. → A65
