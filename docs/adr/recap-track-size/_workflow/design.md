<!-- workflow-sha: 59c7dd338fc472a21ea2bd40876edb7ae96ee13b -->
# Two-sided track sizing, phase-aware enforcement, and design-first freeze — Design

## Overview

Today the workflow sizes a track by one rule: "more than ~5-7 steps, split."
Mining the 42 committed tracks shows that rule never bound anything — the
largest track was 5 steps, and a 35-file track passed every check. The
dimension that actually goes unbounded is file footprint. Separately,
`design.md` is authored last in the planning session and is nominally
mutable during execution, which contradicts the workflow's own "frozen
after Phase 1" rule and lets the plan derive from a design that is still
moving.

This design makes four coordinated changes. It **redefines a track as one
PR in a stacked-diff series** — autonomous (stands alone, builds on prior
tracks, independently mergeable) and reviewable (inside a footprint
ceiling) — and replaces the step ceiling with a two-sided footprint bound
plus a *maximize* directive that packs work into each track up to that
bound. It binds each size metric to the phase where it is knowable: files
predict at plan time, lines measure during and after execution. It
**freezes `design.md` after Phase 1** by removing the live mutation paths
and routing replan design intent into the plan's Decision Records and the
track narrative. And it **authors `design.md` first**, in its own session
that ends when the design's adversarial-then-cold-read review passes, so
the plan derives from a frozen, reviewed seed.

The two enabling primitives are the **files-predict / lines-measure phase
split** (a planning-time cap cannot be stated in lines because the code
does not exist yet) and the **argumentation gate** (a track outside the
bounds passes only if it carries a written justification, which keeps the
soft bound honest without a hard stop). The subsystems that change to fit:
the five review prompts that enforce the old metric, the Phase B step loop
and Phase C code review, the inline-replan routing, the `create-plan`
session ordering, and the `edit-design` mutation loop.

The rest of this document covers: Core Concepts → Track sizing → Phase-aware
enforcement → Design freeze → Design-first authoring → Sync surface and
staging.

## Core Concepts

This design introduces eight load-bearing ideas. Each is named and used
without re-definition in the sections that follow; each pairs the new
concept with what it replaces, so the delta from the baseline is visible
at a glance.

**Track as a stacked-diff PR.** A track is one PR in a stacked-diff series:
it builds on the tracks before it, stands on its own as an independently
reviewable and mergeable unit, and carries as much of the feature as one
reviewable diff holds. Replaces "a coherent stream of related work, max
~5-7 steps". → §"Track sizing".

**Maximize.** The planning directive: size each track up to the largest
autonomous, reviewable bundle, opening a new track only when the next unit
breaches the ceiling or breaks autonomy. Replaces the implicit
"cut at the first logical seam" bias. → §"Track sizing".

**Floor.** A track ≤~12 in-scope files that can fold into an adjacent track
under the ceiling is a merge candidate. Replaces "no lower bound". → §"Track
sizing".

**Ceiling.** A soft footprint bound: split candidate at >~20-25 in-scope
files, recorded "overblown" at >~40. Replaces the one-sided step ceiling.
→ §"Track sizing".

**Footprint.** The count of distinct in-scope files a track changes — the
plan-time-knowable size dimension, distinct from step count. Replaces "step
count is the size metric". → §"Phase-aware enforcement".

**Argumentation gate.** A track outside the bounds (under-floor, or below
the maximize target, or over the ceiling) must carry a written justification
in its track file; a documented out-of-bounds track passes Phase 2
autonomously, an undocumented one escalates. Replaces "the threshold either
blocks or is silently ignored". → §"Track sizing".

**Phase-aware enforcement.** Each metric is bound to the phase where it is
knowable: steps and files predict at Phase 1/A, the running diff measures at
Phase B, the review-burden diff measures at Phase C. Replaces "one
plan-time rule". → §"Phase-aware enforcement".

**Design freeze and design-first.** `design.md` is authored and reviewed in
its own session, frozen at Phase 1, and never mutated during execution;
replan design intent lives in the plan's Decision Records and the track
narrative. Replaces "design.md authored last and mutable mid-execution".
→ §"Design freeze", §"Design-first authoring".

## Track sizing

**TL;DR.** A track is one stacked-diff PR. Size it by *maximizing* — pack
units in up to a soft footprint ceiling, related or not — and clamp with a
floor below and a ceiling above. The bounds are soft: a track outside them
passes when it carries a written justification, and only escalates when it
does not. Step count is retired as the sizing metric; footprint (in-scope
files) replaces it.

The sizing routine runs on the planned in-scope file footprint, not step
count. The planner builds each track as large as the bounds allow, then
clamps:

```mermaid
flowchart TD
    START["Next unit of work\n(cluster of logically united steps)"] --> ADD{"Adding it to the\ncurrent track:\nbreaches ceiling OR\nbreaks autonomy?"}
    ADD -->|"no"| PACK["Add to current track\n(maximize — relatedness\nnot required)"]
    PACK --> START
    ADD -->|"yes"| NEW["Open a new track\n(prefer a dependency\nboundary as the cut)"]
    NEW --> START

    subgraph clamp["Per-track size check (footprint = in-scope files)"]
        F{"≤ ~12 files AND\nfolds into a neighbor\nunder the ceiling?"}
        F -->|"yes"| FLOOR["Merge candidate\n(flag-only)"]
        F -->|"no"| C{"> ~20-25 files?"}
        C -->|"no, and ≥ target"| OK["In bounds — no argumentation"]
        C -->|"< ~20 (under target)"| ARGU["Argumentation required:\nwhy not maximized further"]
        C -->|"yes"| ARGO["Split candidate +\nargumentation: why not split"]
        ARGO --> OVER{"> ~40 files?"}
        OVER -->|"yes"| BLOWN["Record 'overblown'\n(calibration feed)"]
    end
```

The governing principle of both the floor and the maximize directive is one
sentence: **minimize the number of track cycles, subject to the reviewability
ceiling and inter-track mergeability.** Each track cycle is expensive — a
Phase A review and decomposition, a Phase B implementation pass, a Phase C
code review, and the session boundaries between them — so the plan pays a
fixed tax per track. Maximize is the proactive form (build big tracks up
front); the floor is the reactive backstop (fold in a track that slipped
through).

Two coherence properties must stay separate. **Inter-track autonomy** —
does the track stand alone and merge independently of *later* tracks — is
required by the definition. **Internal thematic coherence** — are the
changes within a track about the same subsystem — is *not* a sizing
criterion: bundling two unrelated autonomous changes into one track keeps
both autonomous, and unrelated changes carry no interaction, so reviewing
them together costs no more than reviewing them apart. The only real
reviewability constraint is total volume, which the ceiling measures.

### Edge cases / Gotchas

- A track that lands under ~20 files but is genuinely complete (no further
  unit to add) satisfies the argumentation requirement trivially: "this is
  the whole change". The gate checks for a written reason, not for size
  above a line.
- The floor never auto-merges. A track merge re-partitions PRs, picks a
  neighbor, must preserve the dependency DAG, and renumbers cross-references
  — none of which is "a single unambiguous edit that doesn't change plan
  intent", so it fails the `mechanical` test and is performed by the
  planner, not a tool. See §"Phase-aware enforcement" for where the floor
  and ceiling are checked.
- "Overblown" (>~40 files) is a recorded status for calibration, not a
  build-blocking stop. Even the upper tier is soft.

### References

- D-records: D1, D2, D3
- Mechanics: §"Phase-aware enforcement" — which phase checks the bound and
  what each does.

## Phase-aware enforcement

**TL;DR.** A planning-time cap cannot be stated in lines, because the code
does not exist yet. Bind each metric to the phase where it is knowable:
files and steps *predict* at Phase 1/A; the running diff is a *measured
early-warning* at Phase B; the review-burden diff is a *measured check* at
Phase C. Only Phase 2 has any gate, and it gates on the presence of
argumentation, not on the count.

```mermaid
flowchart LR
    P1["Phase 1 / A\nPREDICT"] --> P1M["steps + in-scope files\n→ floor / ceiling / maximize;\nargumentation when out of bounds"]
    P2["Phase 2 review\nGATE"] --> P2M["out-of-bounds track WITHOUT\nargumentation → design-decision\n(escalate); WITH → pass"]
    PB["Phase B step loop\nMEASURE (running)"] --> PBM["git diff base..HEAD --stat\nafter each step; balloon past\nceiling with steps pending →\nconsider inline replan"]
    PC["Phase C code review\nMEASURE (burden)"] --> PCM[">~2,000 lines: page the diff,\nflag burden. >~4,000: record\noverblown. Total +/-, exclude\ngenerated, keep test."]
```

The Phase 2 structural reviewer classifies every finding as `mechanical`
(orchestrator auto-fixes) or `design-decision` (orchestrator escalates to
the user) — there is no advisory tier. So "soft finding for the file
ceiling" is not an available slot. Instead the gate keys on the
argumentation: an out-of-bounds track (under-floor, below the maximize
target, or over the ceiling) that lacks a justification block is a
`design-decision`; a documented one passes without escalation. This keeps
the autonomous phase autonomous for the common case — maximize deliberately
produces large tracks, and they pass when documented — while still catching
the undocumented oversize that genuinely needs human judgment.

The line thresholds live only in the Phase B step loop and the Phase C code
review, never in the planning prose. The basis is total `+/-` (a reviewer
reads deletions too), generated code is excluded (consistent with the
project's Spotless and coverage exclusions) and test code is kept (it is
real review burden). The threshold values are review-capacity estimates;
the Phase C overblown-recording is the calibration feed that lets them be
re-pinned later.

### Edge cases / Gotchas

- The Phase B early-warning is orchestrator judgment, never a hard stop. A
  running total past the ceiling with steps still pending is a prompt to
  consider inline-replanning the tail, not an automatic split.
- Staging does not inflate footprint. On a workflow-modifying branch the
  staged copies live under `_workflow/` (removed at Phase 4); the ceiling
  counts the distinct live `.claude/` files changed, 1:1 with the staged
  copies. See §"Sync surface and staging".

### References

- D-records: D3, D4
- Mechanics: §"Track sizing" — the bound the predictive metrics check.

## Design freeze

**TL;DR.** `design.md` is frozen after Phase 1. The live mutation paths are
removed, and replan design intent routes into the plan's Decision Records
(authoritative for current intent) and the track narrative (long-form),
never back into `design.md`. Phase 4 is the single point that reconciles
`design.md` into `design-final.md` against the real code.

The freeze closes a live self-contradiction: the design rules list inline
replanning as a `design.md` mutation trigger four lines from the rule that
says `design.md` is never modified after planning. The model already holds
about 70% — no Phase 3A or 3C reviewer receives `design.md`, and the de-facto
source of truth during execution is already the plan's immutable Decision
Records. This design removes the remaining live paths and adds one handling
rule.

```mermaid
flowchart TD
    REPLAN["Inline replan during\nPhase 3 ESCALATE"] --> DR["Revise the plan's\nDecision Records\n(current intent)"]
    REPLAN --> NARR["Update track narrative\n(## Plan of Work /\n## Interfaces and Dependencies)"]
    DR -.->|"NOT a path anymore"| DESIGN["design.md\n(frozen Phase-1 snapshot)"]
    DR --> LINK{"Revised DR's\nFull design: link now\npoints at a superseded\nsection?"}
    LINK -->|"yes"| FIX["Drop / re-target the link,\nor caveat 'describes\npre-revision approach'"]
    DESIGN --> P4["Phase 4 reconciles\ninto design-final.md\nagainst real code"]
```

A consequence at Phase 2: on a re-run after an inline replan (when the plan's
`## Plan Review` was reset to `[ ]`), the consistency reviewer compares the
plan's Decision Records against the frozen `design.md`. A revised DR will
legitimately diverge from the design section it once mirrored. The
consistency-review prompt gains a note that this divergence is **expected,
not a finding** — the Decision Records are authoritative for current intent,
`design.md` is the Phase-1 snapshot.

### Edge cases / Gotchas

- No script checks the *semantics* of a `Full design:` link, only its
  existence. A revised DR whose link points at a superseded section is
  handled procedurally (drop / re-target / caveat); a lint is deferred to
  YTDB-1079.
- The Phase 3B implementer takes current build intent from the plan, never
  resolving a decision out of frozen `design.md`; when the plan is silent it
  escalates `DESIGN_DECISION_NEEDED` rather than reading intent from the
  snapshot.

### References

- D-records: D6
- Mechanics: §"Design-first authoring" — the freeze point moves earlier, to
  the moment the design's own review passes.

## Design-first authoring

**TL;DR.** Author `design.md` first, in a dedicated session that ends when
its adversarial-then-cold-read review passes (or the user accepts open
risks). The implementation plan, Architecture Notes, Decision Records, and
track files derive from that frozen design in a separate session.
`/create-plan` auto-resumes plan derivation when `design.md` exists and
`implementation-plan.md` does not.

This mirrors the mandatory session boundary already enforced between Phases
A, B, and C. Today `create-plan` Step 4 authors Architecture Notes and the
track checklist first and writes `design.md` last, so the design back-fills
decisions the plan already crystallized. Reordering makes the design the
seed the plan derives from.

```mermaid
sequenceDiagram
    participant U as User
    participant S1 as Session 1 (design)
    participant S2 as Session 2 (plan)
    U->>S1: /create-plan
    S1->>S1: author design.md via edit-design (phase1-creation)
    S1->>S1: adversarial review (challenge decisions, hidden assumptions)
    S1->>S1: cold-read review (can a fresh reader build a model?)
    S1-->>U: design frozen + committed (session ends)
    U->>S2: /create-plan (re-invoke)
    S2->>S2: design.md exists, implementation-plan.md absent → auto-resume plan derivation
    S2->>S2: Architecture Notes, Decision Records, track files from frozen design
    S2-->>U: plan committed
```

The review order is load-bearing: adversarial first answers "does the design
hold up against the real code"; cold-read then answers "can a fresh reader
build a working mental model." Running cold-read first would assess the
readability of a design the adversarial pass may still force to change. The
`edit-design` mutation loop gains an adversarial step before its cold-read
step for the `phase1-creation` kind.

### Edge cases / Gotchas

- This change is implemented here; YTDB-975 (which specifies overlapping
  design-first authoring) is corrected on a separate branch afterward, so the
  reordering is not built twice.
- The new rules are not live during this issue's own planning. This plan is
  authored under the current design-last flow and applies the sizing rules by
  hand; the merged result is the first plan the live rules govern.

### References

- D-records: D7
- Mechanics: §"Design freeze" — design-first moves the freeze point to the
  moment the design's review passes.

## Sync surface and staging

**TL;DR.** The retired sizing metric appears in 12 places, not the 4 the
issue first listed; five of them are review prompts that enforce the old
rule on reviewers. All must move to the two-sided cap, with a sync-list so
they cannot drift apart again. Every target file is workflow machinery, so
edits stage under `_workflow/staged-workflow/` and the staged-vs-live delta
gets the Phase C staged-delta review.

The "~5-7 steps" rule currently lives in `planning.md`, `conventions.md`
(×2), `track-review.md`, `create-plan/SKILL.md`, and the `structural` (×3),
`technical`, `adversarial`, `risk`, and `consistency` review prompts. The
five review prompts are the consequential part: if they keep the old metric,
Phase 2 and 3A reviewers will flag tracks by "≤5-7 steps" after the metric
is retired, and the structural reviewer's finding template would contradict
the new cap. A sync-list comment anchors the set so a future edit to one
updates the rest.

This plan carries the canonical `§1.7(b)` workflow-modifying marker. Edits
route to the staged mirror under
`docs/adr/recap-track-size/_workflow/staged-workflow/.claude/...`; the live
tree stays at develop's state through Phase B/C; one Phase 4 promotion
commit copies staged over live just before final artifacts land. Staged
reads compose within the plan, so a later edit to a shared file (for example
`planning.md`, touched by the sizing and the design-first work) reads the
earlier edit's staged copy.

### Edge cases / Gotchas

- Promotion is additive-only — `cp -r` does not propagate whole-file
  deletions. This plan has none; every removal is a within-file content edit
  (a trigger bullet, a phase annotation), which the staged copy carries and
  `cp -r` overwrites cleanly.
- Develop moves on these exact files while the branch runs. The Phase 4
  pre-promotion divergence check halts if the branch is behind; the
  resolution is a rebase before promotion.

### References

- D-records: D5
- Mechanics: §"Track sizing", §"Design freeze" — the rules the synced files
  carry.
