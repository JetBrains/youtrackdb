<!-- MANIFEST
findings: 8   severity: {blocker: 1, should-fix: 3, suggestion: 4}
index:
  - {id: A54, sev: should-fix, loc: "research-log.md:899 (B1, 15:05Z, Alternatives rejected)", anchor: "### A54 ", cert: C1, basis: "unlisted cheap middle: gate-verdict summary in the PR description rides into the squashed commit message, giving minimal in-tree evidence at near-zero cost"}
  - {id: A55, sev: should-fix, loc: "research-log.md:922-926 (B1 ripple)", anchor: "### A55 ", cert: C3, basis: "ripple names a Part-6 fold mention that does not exist and misses the Part-2 edge-case bullet (design.md:353-355) that B1 falsifies for minimal"}
  - {id: A56, sev: should-fix, loc: "research-log.md:911-914 (B1 honest-cost acceptance)", anchor: "### A56 ", cert: C4, basis: "'unprimed by construction' is defeasible: the user may add a lens at tier confirmation, and matched-vs-central semantics for Gate-1-no changes is undefined"}
  - {id: A57, sev: suggestion, loc: "research-log.md:899 (B1) vs :483-487 (08:40Z A4)", anchor: "### A57 ", cert: C5, basis: "B1 narrows the ratified 08:40Z A4 every-tier fold without a SUPERSEDES clause or forward marker, against the Q3/A29 precedent"}
  - {id: A58, sev: suggestion, loc: "research-log.md:900-901 (B1 lead)", anchor: "### A58 ", cert: C6, basis: "'Phase 4 collapses to the cleanup commit alone' ignores the 1.7(f) staged-mirror promotion on a workflow-modifying minimal branch"}
  - {id: A59, sev: blocker, loc: "research-log.md:927-955 (B2, Why + Ripple)", anchor: "### A59 ", cert: C7, basis: "conventions-execution.md absent from the ripple: the 2.5 TOC row (phases 2,3A,3B,3C,4) excludes both gate parties at Phase 0->1, and the review-file lifecycle home is track-anchored and uninstantiable before a plan exists"}
  - {id: A60, sev: suggestion, loc: "research-log.md:953-955 (B2 ripple, naming deferral)", anchor: "### A60 ", cert: C9, basis: "naming deferral is sound but cites no anchor (track-review.md:641); the verdict-producer manifest variant for iteration>=2 runs is the real open question"}
  - {id: A61, sev: suggestion, loc: "design.md Part 3 §Reuse (B2 ripple item 1)", anchor: "### A61 ", cert: C10, basis: "citing 2.5's S4/S6 inside Part 3 collides with design.md's own S1-S4 invariant namespace; one parenthetical resolves it"}
evidence_base: {section: "## Evidence base", certs: 10, breaks: 1, holds: 2}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS, anchor: "#### C2 "}
  - {id: C3, verdict: CONSTRUCTIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: FRAGILE, anchor: "#### C4 "}
  - {id: C5, verdict: WEAK, anchor: "#### C5 "}
  - {id: C6, verdict: THEORETICAL, anchor: "#### C6 "}
  - {id: C7, verdict: BREAKS, anchor: "#### C7 "}
  - {id: C8, verdict: SURVIVES, anchor: "#### C8 "}
  - {id: C9, verdict: FRAGILE, anchor: "#### C9 "}
  - {id: C10, verdict: FRAGILE, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

# Log-adversarial gate — B1 + B2 batch, iteration 1

Whole-batch re-challenge (D15) of the two Decision Log entries timestamped
2026-06-10T15:05Z (B1: the `minimal` tier sheds `adr.md`) and
2026-06-10T15:07Z (B2: the research-log gate writes a §2.5 review file).
Both decisions survive their strongest challenges; B2 carries one blocker
on its ripple enumeration and its "needs no new machinery" rationale. Count
validation: `grep -cE '^### [A-Z]+[0-9]+ '` over this file returns 8.

## Findings

### A54 [should-fix]
**Certificate**: C1 (Challenge: B1 — best rejected alternative)
**Target**: B1's `Alternatives rejected` field (research-log.md 15:05Z)
**Challenge**: The list weighs only the two endpoints (keep the fold
everywhere; key the shed on Gate 1). It misses a cheap middle that removes
the one cost B1 concedes: fold a two-line gate-verdict summary into the PR
description at Phase 4. Per the repo's Git conventions, the squash-merge
takes its message from the PR title and description, so the summary lands
in develop's `git log` — in-tree evidence for `minimal` with no
`docs/adr/` entry and no authoring step beyond the lines themselves.
**Evidence**: CLAUDE.md §Pull Requests ("1 PR = 1 squashed commit", "the
squashed commit message is built from the PR title and description", and
the standing duty to keep the description in sync with follow-up commits,
which gives the summary a natural final-sync home). B1's honest-cost
sentence concedes verdicts otherwise survive "on GitHub, not in the merged
tree."
**Proposed fix**: Adopt the PR-description verdict summary for `minimal`,
or record it as weighed-and-rejected with a reason (the D14/A40 precedent
records middle grounds explicitly). The shed itself stands either way.

### A55 [should-fix]
**Certificate**: C3 (Violation scenario: stale audit-trail prose)
**Target**: B1's `Ripple` list
**Challenge**: Two enumeration defects. First, a phantom item: "Part 6
matrix where it names the fold" — Part 6 (design.md:825-917) contains no
`fold` or `adr.md` mention; its matrix stops at Phase 3C and its only
Phase-4 reference is the design-final cold-read edge case, which B1 does
not touch. Second, a miss: Part 2 §Edge cases (design.md:353-355) states
"any audit trail that must survive merge is folded into `adr.md`
(Part 7), not left in the log" — tier-universal as written, and false for
`minimal` once B1 lands. Executing the ripple as written leaves that
bullet asserting the opposite of B1 to every Part-2 reader.
**Evidence**: grep over design.md: every fold/`adr.md` mention sits in
Part 2 (line 354-355) and Part 7 (934-1015); none in Part 6.
**Proposed fix**: Replace the Part-6 ripple item with the Part-2
edge-case bullet (qualify it to `full`/`lite`, with `minimal`'s trail
surviving only in PR history).

### A56 [should-fix]
**Certificate**: C4 (Assumption test: unprimed by construction)
**Target**: B1's acceptance rationale ("the `minimal` gate is unprimed by
construction")
**Challenge**: The premise has two holes. (a) The design gives the user an
explicit add-a-lens hatch at tier confirmation (design.md:283-285,
435-436); a `minimal` change with a user-added lens runs a primed gate,
so "by construction" overstates. (b) Lens generation keys on "matched"
categories, but Part 1 defines the matched set only on the Gate-1-yes
path (design.md:248-251); whether a touched-but-not-central category
"matches" on a Gate-1-no change is an open reading, and B1 silently picks
the no-lenses interpretation.
**Evidence**: design.md Part 1 §Gate 1 criteria and §Edge cases; Part 3
§Domain priming ("The user may add or drop a lens at tier confirmation").
**Proposed fix**: Rest the acceptance on the second leg, which holds
regardless — `minimal` verdicts are low-stakes by Gate-1 construction —
and acknowledge the lens hatch; optionally pin the matched-set semantics
(lenses derive from central categories plus explicit user additions).

### A57 [suggestion]
**Certificate**: C5 (Challenge: supersession hygiene)
**Target**: B1's provenance discipline vs the ledger's own precedent
**Challenge**: B1 narrows ratified ground — the 08:40Z A4 resolution
"extended the `adr.md` adversarial-verdict fold to **every tier**" — with
no REOPENS/SUPERSEDES clause and no forward marker on the 08:40Z text. A
ledger reader walking the 08:40Z entry still reads the every-tier
extension as live. Q3 (11:12Z) declared "REOPENS and SUPERSEDES" when it
reopened A13/D7, and A29 added forward markers in place on superseded
brackets; B1 should match that hygiene.
**Evidence**: research-log.md:483-487 (08:40Z A4), :490-494 (Q3's
declaration pattern), :673-674 (A29 markers applied in place).
**Proposed fix**: Add "narrows the 08:40Z A4 every-tier fold to
`full`/`lite`" to B1 and a forward bracket on the 08:40Z A4 clause.

### A58 [suggestion]
**Certificate**: C6 (Violation scenario: Phase 4 on a workflow-modifying
`minimal` branch)
**Target**: B1's lead sentence ("`minimal`'s Phase 4 collapses to the
`_workflow/` cleanup commit alone")
**Challenge**: A single-track change can stage `.claude/**` edits without
workflow machinery being central to its purpose (Gate 1 reads centrality;
§1.7 staging keys on the files touched) — for example a code change that
also updates a hook's script path. That branch is `minimal` and
workflow-modifying, and its Phase 4 carries the §1.7(f) staged-mirror
promotion before the cleanup commit: two acts, not one. A reader
implementing "cleanup-only" could drop the promotion.
**Evidence**: design.md:986-994 (D13 staging applies to workflow-modifying
branches as a class); design.md:246-251 (Gate 1 centrality rule leaves
incidental workflow edits below the gate).
**Proposed fix**: Reword to "sheds the fold, so Phase 4 carries no
`adr.md` authoring step" rather than "collapses to the cleanup commit
alone."

### A59 [blocker]
**Certificate**: C7 (Assumption test: needs no new machinery)
**Target**: B2's `Why` ("the manifest + partial-fetch read discipline
needs no new machinery") and its `Ripple` list
**Challenge**: Two `conventions-execution.md` surfaces contradict the
claim at the Phase 0→1 boundary, and the ripple names neither. (a)
Access: the §2.5 TOC row and section markers scope it to phases
`2,3A,3B,3C,4`. The third-scope reviewer and the spawning `create-plan`
orchestrator both sit outside that set, and the §1.8 TOC protocol is
explicit: no matching row means "the file holds nothing for you — do not
read further." The schema that defines the file the reviewer must write
and the manifest the orchestrator must validate is unreachable to both
parties exactly where B2 deploys it. (b) Home and lifecycle: §Review-file
lifecycle pins every review file to
`docs/adr/<dir>/_workflow/plan/track-N/reviews/`, "a directory beside the
`plan/track-N.md` file" — no plan directory or track exists before Step
4b, so the canonical home is uninstantiable at the gate (this very run
had to use an improvised `_workflow/reviews/` path). The file-mode
decision itself survives (C8); the entry's enumeration and rationale do
not, and a planner trusting "no new machinery" would create no track item
for either edit.
**Evidence**: conventions-execution.md:13 and :474 (phase scoping),
:276-282 (track-anchored home, per-fan-out filename, commit-at-return);
adversarial-review.md:5-7 (the do-not-read-further rule); precedent: A20
and A43 graded enumeration misses that break the mechanism as blockers.
**Proposed fix**: Reword the sentence — the schema and read discipline
transfer; the access row and lifecycle home do not — and extend the
ripple with (i) the §2.5 TOC-row phase extension (or an explicit
read-§2.5 instruction inside the new scope section and the Step-4 gate
prose) and (ii) a third-scope location/lifecycle clause (directory,
commit-at-return applicability, Phase-4 sweep).

### A60 [suggestion]
**Certificate**: C9 (Assumption test: naming deferral)
**Target**: B2's `Ripple` ("per-iteration file naming is decided at
implementation against existing Phase-3A practice")
**Challenge**: The deferral is safe but unanchored, and it names the
wrong open question. The practice exists concretely —
`track-review.md:641` specifies `<type>-iter<N>.md` in
file-when-handed-a-path mode — so citing it turns the deferral into a
selection. The genuinely open question is manifest shape: every
iteration-2+ run on this branch emitted per-prior-finding verdicts plus
new findings, which in §2.5 terms is the verdict-producer variant
(`verdicts:` block, `overall: PASS/FAIL`); B2 does not say whether
third-scope iteration files use the variant or fold verdicts into the
finding set.
**Evidence**: track-review.md:641; conventions-execution.md:597-628
(verdict-producer variant); research-log.md 07:10Z, 12:05Z, 14:55Z
iteration records (verdicts + new findings every time).
**Proposed fix**: Cite `track-review.md:641` as the naming anchor and add
the verdict-producer variant question to the deferred-decision clause.

### A61 [suggestion]
**Certificate**: C10 (Assumption test: invariant-namespace collision)
**Target**: B2's first ripple item (Part 3 §Reuse gains the output-mode
rule)
**Challenge**: The natural rendering of the output-mode rule cites §2.5's
count-validation and no-bodies invariants by their labels S4 and S6.
design.md owns a different S4 (per-step risk tag vs tier non-stacking,
design.md:916-917), so an unqualified citation hands the reader two S4s
with unrelated meanings in the same document.
**Evidence**: design.md Part 6 §References (S4); conventions-execution.md
§2.5 (S4/S6 labels).
**Proposed fix**: When the Part-3 edit lands, qualify the labels
("`conventions-execution.md`'s S4/S6, not this design's invariants").

## Evidence base

**Decision challenges**

#### C1 Challenge: B1 — the minimal tier sheds adr.md
- **Chosen approach**: D10's fold runs in `full`/`lite` only; `minimal`'s
  verdicts survive in the PR's commit history on GitHub, not the merged
  tree.
- **Best rejected alternative**: The listed status quo (fold in every
  tier) is weaker than an unlisted middle: a two-line gate-verdict
  summary in the PR description, synced at Phase 4.
- **Counterargument trace**:
  1. CLAUDE.md §Pull Requests builds the squashed commit message from the
     PR title and description, so description text lands in develop's
     `git log` at merge.
  2. A verdict summary there costs no `docs/adr/` entry and no Phase-4
     authoring step beyond the lines, and the existing keep-description-
     in-sync duty gives it a maintenance home.
  3. Outcome: in-tree evidence for `minimal`, removing the only cost B1
     concedes while keeping the shed.
- **Codebase evidence**: CLAUDE.md Git conventions; research-log.md
  15:05Z honest-cost sentence.
- **Survival test**: WEAK — the shed survives; the alternatives list needs
  the middle recorded (adopted or rejected with a reason). → A54

#### C5 Challenge: B1 — supersession hygiene vs ledger precedent
- **Chosen approach**: B1 narrows the 08:40Z A4 every-tier fold with no
  supersession declaration or forward marker.
- **Best rejected alternative**: The ledger's own convention — Q3's
  "REOPENS and SUPERSEDES" line and A29's in-place forward markers.
- **Counterargument trace**:
  1. research-log.md:483-487 still reads "extended the `adr.md`
     adversarial-verdict fold to **every tier**" with no marker.
  2. A reader auditing the fold's provenance stops there and reads
     superseded ground as live; the Q3 precedent exists precisely to
     prevent this.
  3. Outcome: a navigation defect, not a decision defect.
- **Codebase evidence**: research-log.md:490-494, :673-674.
- **Survival test**: WEAK — content stands; hygiene needs one clause and
  one bracket. → A57

#### C8 Challenge: B2 — uniform file mode vs inline return
- **Chosen approach**: Every third-scope spawn supplies an output path;
  the reviewer persists the §2.5 file and returns the thin manifest.
- **Best rejected alternative**: Inline return (the Phase-1 design-scope
  precedent). Its strongest case is a D14-shaped run — one iteration,
  five findings (13:45Z) — where file overhead exceeds the certificate
  bulk saved.
- **Counterargument trace**:
  1. Small runs make inline locally cheaper, but the asymmetry is
     one-sided: the 4-iteration runs (06:30Z-08:10Z, 11:35Z-13:00Z) would
     have put four full certificate sets into the orchestrator's
     permanent context tail.
  2. The measured re-read tail is ~53% of orchestrator session cost, and
     D14's xhigh spawns inflate certificate volume; file mode caps the
     worst case at trivial overhead on the best case.
  3. B2's §2.5 citation is verbatim-accurate: "Persisting to a file
     rather than returning the same structure inline is the load-bearing
     choice … The file is what makes resume cheap"
     (conventions-execution.md:510-516).
- **Codebase evidence**: conventions-execution.md:510-516; research-log
  iteration records.
- **Survival test**: YES — the decision holds; the enumeration repair is
  A59's, not this challenge's.

**Assumption tests**

#### C2 Assumption test: PR commit history stays recoverable evidence
- **Claim**: After squash-merge, `minimal`'s gate records remain
  recoverable on GitHub.
- **Stress scenario**: Branch deleted after merge; intermediate history
  force-pushed during the branch's life.
- **Code evidence**: The log is append-only and deleted only by the
  Phase-4 cleanup commit, so the complete ledger sits at the cleanup
  commit's parent in whatever final history merges; GitHub retains the PR
  head ref after branch deletion, and squash-merge does not rewrite it.
  Earlier force-pushes cannot remove the parent-of-cleanup snapshot from
  the final history. The off-platform caveat (clones do not fetch PR
  refs) is conceded in B1's own wording ("on GitHub, not in the merged
  tree").
- **Verdict**: HOLDS — no finding; the trace strengthens B1.

#### C4 Assumption test: the minimal gate is unprimed by construction
- **Claim**: Gate 1 = no HIGH-risk category central, so no emphasis
  lenses match.
- **Stress scenario**: (a) the user adds a lens at tier confirmation, an
  explicitly designed hatch; (b) a category touched but not central on a
  Gate-1-no change — the design defines the matched set only on the yes
  path, so whether it generates a lens is an open reading.
- **Code evidence**: design.md:283-285 and :435-436 (lens hatch);
  design.md:248-251 (matched-set recording defined under Gate-1-yes).
- **Verdict**: FRAGILE — holds on the auto path under one reading; the
  hatch defeats "by construction." → A56

#### C7 Assumption test: the discipline needs no new machinery at Phase 0→1
- **Claim**: §2.5 transfers as-is; only the spawn contract and Part 3
  need edits.
- **Stress scenario**: Both gate parties follow the §1.8 TOC protocol at
  a phase the §2.5 row does not cover, then look for the lifecycle home.
- **Code evidence**: conventions-execution.md:13/:474 (§2.5 phases
  `2,3A,3B,3C,4`; the third-scope reviewer and the Step-4
  planner/orchestrator match no row); adversarial-review.md:5-7 ("do not
  read further" on no match); conventions-execution.md:276-282 (every
  review file lives beside `plan/track-N.md`, which does not exist before
  Step 4b — this run improvised `_workflow/reviews/`).
- **Verdict**: BREAKS — the schema content transfers, the access row and
  lifecycle home do not, and the ripple names neither edit. → A59

#### C9 Assumption test: per-iteration file naming can be deferred
- **Claim**: Implementation inherits an existing Phase-3A practice.
- **Stress scenario**: The deferral points at a practice that might not
  exist, or might not cover the gate's loop shape.
- **Code evidence**: track-review.md:641 (`<type>-iter<N>.md`,
  file-when-handed-a-path) — the practice exists, so naming is safe. The
  loop shape is the gap: iteration-2+ runs emit per-prior-finding
  verdicts plus new findings, the §2.5 verdict-producer variant
  (conventions-execution.md:597-628), which the deferral does not name.
- **Verdict**: FRAGILE — holds for naming, silent on manifest shape.
  → A60

#### C10 Assumption test: the output-mode rule lands in Part 3 collision-free
- **Claim**: Citing §2.5 in Part 3 is a pure addition.
- **Stress scenario**: The citation carries §2.5's S4/S6 labels into a
  document whose own S4 means risk-tag/tier non-stacking.
- **Code evidence**: design.md:916-917 (design S4);
  conventions-execution.md §2.5 (S4 count grep, S6 no-bodies).
- **Verdict**: FRAGILE — one qualifying parenthetical resolves it. → A61

**Invariant / violation scenarios**

#### C3 Violation scenario: the Part-7 audit-trail guarantee under B1's ripple
- **Invariant claim**: Every audit trail that must survive merge is
  folded into `adr.md` (design.md:353-355); "the fold runs in every tier"
  (design.md:979, :998).
- **Violation construction**:
  1. Start state: B1 ratifies with its current ripple list.
  2. Action sequence: the batch mutation edits Part 1, Part 7, Part 6,
     and D12 per the list.
  3. Intermediate state: the Part-6 item is a no-op — grep shows Part 6
     (design.md:825-917) never names the fold or `adr.md` — and
     design.md:353-355 is untouched because the list omits it.
  4. Violation point: Part 2's edge case still asserts the universal
     fold, false for `minimal`.
  5. Observable consequence: the Step-4b planner and the Phase-2
     consistency reviewer, both Part-2 readers, conclude `minimal`'s
     trail survives merge in-tree.
- **Feasibility**: CONSTRUCTIBLE — it is the default outcome of executing
  the ripple as written. → A55

#### C6 Violation scenario: cleanup-only Phase 4 on a staged minimal branch
- **Invariant claim**: "`minimal`'s Phase 4 collapses to the `_workflow/`
  cleanup commit alone" (B1 lead).
- **Violation construction**:
  1. Start state: a single-track code change renames a script referenced
     from a `.claude/` hook; the purpose is the code rename.
  2. Action sequence: Gate 1 = no (workflow machinery not central),
     Gate 2 = single → `minimal`; the hook edit makes the plan
     workflow-modifying, so edits accumulate in the staged mirror (D13).
  3. Intermediate state: Phase 4 owes both the §1.7(f) promotion and the
     cleanup commit.
  4. Violation point: an implementer of "cleanup-only" Phase 4 skips the
     promotion; the staged edit never reaches the live tree.
  5. Observable consequence: the merged branch silently drops its
     `.claude/` change.
- **Feasibility**: THEORETICAL — requires the incidental-workflow-edit
  combination, which the design permits but this branch has never
  produced. → A58
