# Cold-Read Comprehension Review (Sub-Agent Prompt)

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: reviewer-design.
Your phase: 1,4.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Inputs | reviewer-design | 1,4 | The design paths, scope, mutation kind, and optional plan/track paths passed to the cold-read reviewer. |
| §Mutation-kind specific instructions | reviewer-design | 1,4 | Extra checks per mutation kind: phase1-creation, design-sync, and the higher bar for committed phase4 artifacts. |
| §Human-reader cold-read additions | reviewer-design | 1,4 | Audience-fit, glossary-introduction, why-before-what, and navigability checks; reviewer tone relaxes to quote evidence. |
| §Prose AI-tell additions | reviewer-design | 1,4 | Over-dense / too-terse / hard-to-read scan vs Banned analysis patterns, Mechanism traces, inflated labels, Orientation, Plain language; creation-time. |
| §Track-scoped cold-read (Step 4b) | reviewer-design | 1 | The second write-time target: cold-read of plan-at-start track sections, plus absorption and full-tier fidelity. |
| §Reading rules | reviewer-design | 1,4 | Read only the provided design files; bounded vs whole-doc scope; grep-only plan reads; fetch house-style on demand. |
| §Comprehension questions | reviewer-design | 1,4 | Seven ordered questions a cold reader answers with citations; insufficient material is itself a finding. |
| §Structural findings (always check) | reviewer-design | 1,4 | Always-on checks: edge-cases sub-section, References footer, sibling consolidation, TL;DR, length budgets, Mechanics. |
| §Output format | reviewer-design | 1,4 | The exact comprehension-assessment, mental-model verdict, structural-findings, and suggested-fixes Markdown to emit. |
| §Severity rubric | reviewer-design | 1,4 | Blocker, should-fix, and suggestion definitions, with the length-tier thresholds that set should-fix vs blocker. |
| §Tone and depth | reviewer-design | 1,4 | One-sentence answers, cite don't paraphrase, flag insufficiency, no intent-speculation; human-reader rules excepted. |

<!--Document index end-->

Fresh agent reading a workflow artifact cold. Assess whether a human
reviewer could build a working mental model **using only the
document(s) provided**. Invoked by the design-mutation action
(`design-document-rules.md § Mutation discipline`) on `design.md`, and by
`create-plan` Step 4b on the plan-at-start track sections; verdict drives
iterate / warn / pass.

This prompt has **two write-time targets** (D8, `planning.md` §Tier
classification). The `target` input selects which:

- `target=design` (the default; every mutation-kind invocation) — the
  cold-read assesses `design.md`. This is everything in this file below
  except §Track-scoped cold-read (Step 4b).
- `target=tracks` — the Step-4b cold-read assesses the plan-at-start track
  sections (and the root per-track BLUF/triad). See §Track-scoped cold-read
  (Step 4b) for the target set, the absorption-completeness criterion, and
  the full-tier fidelity criterion. The comprehension mechanics
  (§Comprehension questions, §Output format, §Severity rubric, §Tone and
  depth) are shared.

Both targets carry the **absorption-completeness** cross-check against the
research log; `target=design` adds it to the Step-4a `phase1-creation`
cold-read (log → `design.md` D-records) and `target=tracks` adds it to the
Step-4b cold-read (log → carrier inline records). See §Track-scoped
cold-read (Step 4b) for the criterion (it is written once there and applies
to both targets).

## Inputs
<!-- roles=reviewer-design phases=1,4 summary="The design paths, scope, mutation kind, and optional plan/track paths passed to the cold-read reviewer." -->

- `target` (optional, default `design`) — `design` or `tracks`. Selects
  the cold-read target per the two-target note above.
- `research_log_path` (optional) — absolute path to
  `_workflow/research-log.md`. Supplied by the Step-4a `phase1-creation`
  and the Step-4b cold-read spawns so the reviewer runs the
  absorption-completeness cross-check (§Track-scoped cold-read). Absent for
  the interactive mutation kinds and for Phase 4.
- `tier` (optional) — `full` / `lite` / `minimal`. Supplied with
  `target=tracks` so the reviewer knows whether the full-tier fidelity
  criterion applies.
- `design_path` — `design.md` (for `target=design`; also passed with
  `target=tracks` in `full` so the reviewer can run the seed↔track fidelity
  check).
- `design_mechanics_path` (optional) — `design-mechanics.md` when
  the design exceeded the length trigger.
- `scope` — `bounded` or `whole-doc`.
- `bounded_scope` (when `scope=bounded`) — changed section name(s)
  + 1-2 surrounding sections.
- `mutation_kind` — one of `content-edit`, `section-add`,
  `section-remove`, `section-rename`, `section-move`,
  `structural-rewrite`, `length-trigger-crossing`, `phase1-creation`,
  `design-sync`, `phase4-creation`. (`mechanics-edit` defers
  cold-read to the next `design-sync`.)
- `plan_path` (optional) — `implementation-plan.md`; read **only**
  for `**Full design**` link resolution.
- `plan_dir` (optional) — directory of `plan/track-N.md` files
  carrying `**Full design**` refs; read **only** for link resolution.
- `output_path` (optional) — absolute path to write this review's output
  to. Supplied by the `edit-design` `§Step 4` spawn for the Phase 4
  `phase4-creation` cold-read; **absent** for every other invocation,
  including the Phase 1 `phase1-creation` cold-read. See § Output format
  for the path-conditional behavior.
- `plan_dir` (with `target=tracks`) — directory of `plan/track-N.md` files;
  the cold-read reads the plan-at-start track sections from these, not
  only for link resolution.
- `plan_path` (with `target=tracks`) — `implementation-plan.md`; the
  cold-read reads the root per-track BLUF/triad (`## Purpose / Big
  Picture`) and the checklist entries from it.

### Mutation-kind specific instructions
<!-- roles=reviewer-design phases=1,4 summary="Extra checks per mutation kind: phase1-creation, design-sync, and the higher bar for committed phase4 artifacts." -->

- **`phase1-creation`**: `design.md` and `design-mechanics.md`
  were just seeded together. **You run gated behind the cleared
  log-adversarial gate (S3).** Under D6 the decision/assumption
  challenge no longer runs as a local pass here; it ran once on the
  **research log** at the Phase 0 → 1 boundary
  (`prompts/adversarial-review.md` § Research-log-scoped review
  (Phase 0→1)), and the `edit-design` § Step 4 S3 gate blocks this
  cold-read until that log gate clears (`research.md` §The research
  log, Gate-record cadence). So the design's decisions have already
  survived challenge **on the log**: assess comprehension, not whether
  the decisions hold. The `design.md` serves both human readers (the
  user reviewing the plan, the architect during structural review,
  the PR reviewer reading the draft) and agent readers (the
  implementer executing the plan). Verify (a) the `design.md` is
  internally coherent on its own (a fresh reader can navigate it);
  (b) every `Mechanics: design-mechanics.md §"…"` link resolves to a
  matching section in mechanics; (c) the **absorption-completeness
  cross-check** this invocation owns — every load-bearing research-log
  decision appears as a seed D-record in `design.md` (see
  §Track-scoped cold-read, the `target=design` direction of the
  criterion; run only when `research_log_path` is supplied). **Plus
  the Human-reader cold-read additions AND the Prose AI-tell additions
  (both §§ below).**
- **`design-sync`**: this sync re-distilled `design.md` from the
  current state of `design-mechanics.md`. **In addition to the
  standard whole-doc cold-read**, verify that every TL;DR and
  mechanism overview in `design.md` accurately summarizes the
  current mechanics file's content for the same-named section. If
  the `design.md` TL;DR contradicts the mechanics, or names a
  mechanism that mechanics doesn't describe, flag it as a blocker
  — that's exactly what the sync was supposed to fix. **Plus the
  Human-reader cold-read additions AND the Prose AI-tell additions
  (both §§ below).**
- **`phase4-creation`**: `design-final.md` (and optionally
  `design-mechanics-final.md`) was just produced as a Phase 4
  committed artifact reflecting what was actually built. The
  paths end in `-final.md` but the same shape rules apply.
  **Phase 4 is committed to git** and read by humans (PR
  reviewers, future re-readers, decision auditors), so the bar
  is higher than Phase 1: the artifact must stand on its own as
  documentation — no reliance on working files (plan, step
  files), since those live under `_workflow/` and are removed
  by the Phase 4 cleanup commit before merge.

  **In addition to the standard whole-doc cold-read, the
  Human-reader cold-read additions, AND the Prose AI-tell additions
  (both §§ below)**, verify:

  (a) **Plan-deviation surfacing** — the Overview names what
      was built and any high-level deviations from the original
      plan (descoped goals, modified decisions, emergent
      structure).

  (b) **Implementation-grounded diagrams** — class / workflow
      diagrams reflect actual implementation (the calling
      prompt should have run a PSI verification pass; flag any
      diagram element that looks aspirational rather than
      implementation-grounded).

  (c) **No leaked working-file identifiers** — no track
      numbers, step numbers, or review-finding IDs in the
      prose.

### Human-reader cold-read additions
<!-- roles=reviewer-design phases=1,4 summary="Audience-fit, glossary-introduction, why-before-what, and navigability checks; reviewer tone relaxes to quote evidence." -->

Applies to `phase1-creation`, `phase4-creation`, `design-sync`. In
addition to the standard cold-read, verify:

- Verify audience-fit per `.claude/output-styles/house-style.md § Audience-fit`.
- Verify glossary-introduction per `.claude/output-styles/house-style.md § Glossary-introduction`.
- Verify why-before-what per `.claude/output-styles/house-style.md § Why-before-what`.
- Verify navigability per `.claude/output-styles/house-style.md § Navigability`.
- Verify explanatory register per `.claude/output-styles/house-style.md § Explanatory register`.

**Reviewer tone** for these five rules relaxes the "one-sentence answers"
rule in § Tone and depth: quote the prose, list undefined terms, name the
audience the prose fails, and (for navigability) the opaque section or
(for explanatory register) the disconnected assertions.

### Prose AI-tell additions
<!-- roles=reviewer-design phases=1,4 summary="Over-dense / too-terse / hard-to-read scan vs Banned analysis patterns, Mechanism traces, inflated labels, Orientation, Plain language; creation-time." -->

Applies to `phase1-creation`, `phase4-creation`, `design-sync` (the three
`target=design` kinds) **and** `target=tracks`. This block has its own
applies-to set: unlike the Human-reader additions above (design kinds
only), it also runs on the Step-4b track cold-read, because plan-at-start
track prose carries the same over-dense / too-terse failures as design
prose. Scan the changed sections (for `target=tracks`, the plan-at-start
track sections) on **three axes**:

- **Over-dense** — the judgment cases the `dsc-ai-tell` regex set cannot
  catch. Check against `.claude/output-styles/house-style.md § Banned analysis patterns`
  and `.claude/output-styles/house-style.md § Mechanism traces and inline citations`;
  also flag lists spliced into one sentence (a `(1)…(2)…(3)…` or
  comma-chained enumeration presented as prose rather than a list) and
  inflated-abstraction labels the closed-set regex misses (a subject-slot
  "the enabling primitive", "the key abstraction", "the underlying
  mechanism" that names nothing concrete — built from an inflation word
  outside the regex's curated set, or sitting in a non-subject slot the
  regex does not scan).
- **Too-terse** — check against `.claude/output-styles/house-style.md § Orientation`,
  the floor the cut-rules cut to: prose a reader cannot follow without
  opening the code, or a one-line assertion dropped with no motivation, is
  a finding the same as padding.
- **Hard-to-read** — check against `.claude/output-styles/house-style.md § Plain language`.
  Flag a sentence that uses an uncommon word where a common one fits, a
  long tangled sentence the reader must read twice, or an idiom or
  ambiguous phrasal verb. This axis is about word choice and sentence
  shape, so it applies even to prose that is the right length and
  well-motivated. Report it as a finding; plain-language quality stays a
  judgment call, with no score.

**Bound to creation-time prose.** This block runs at design-mutation time
(`target=design`) and once at Step 4b before the plan commit
(`target=tracks`). It does **not** cover live Phase-3 prose — `## Decision
Log` entries, episodes, and review findings that accrue after the Step-4b
cold-read have no re-run here; that surface is held by the always-on
AI-tell subset wiring on the writers, not by this block.

## Track-scoped cold-read (Step 4b)
<!-- roles=reviewer-design phases=1 summary="The second write-time target: cold-read of plan-at-start track sections, plus absorption and full-tier fidelity." -->

This section applies **only when `target=tracks`** — the Step-4b cold-read
`create-plan` spawns after the track files are written and before the Step
5 commit (D8, `planning.md` §Tier classification). It reuses this same
cold-read sub-agent on a second artifact; it is not a second reviewer.

**Target set.** Read the plan-at-start sections of every track file under
`plan_dir` — `## Purpose / Big Picture`, `## Context and Orientation`,
`## Plan of Work`, and `## Interfaces and Dependencies` — plus the root
per-track BLUF/triad (the `## Purpose / Big Picture` BLUF and the
ADDED/MODIFIED/REMOVED triad) and the checklist entries in `plan_path`.
Assess whether a cold reader could build a working mental model of what
each track does and why, using only the track files and the plan. The
seven §Comprehension questions apply, re-pointed from "the design" to "the
track sections": question 1 becomes "what does this track add or change?",
question 7 becomes "how would a reader find the full mechanism — the
inline `## Decision Log` records, and in `full` the `**Full design**`
reference into the frozen `design.md` seed?".

**Plus the Prose AI-tell additions (§ above).** Run that block's over-dense
and too-terse scan on the plan-at-start track sections, the same as on a
`target=design` cold-read; its applies-to set names `target=tracks`
explicitly. The Human-reader additions do **not** run here — they are
design-kind only.

**Absorption-completeness criterion (both targets).** "Carrier
authoritative" (S2) is enforced at write-time by this criterion rather
than a mechanical pass. Cross-check the research log against the carrier
**both ways**: every load-bearing research-log decision in a track's scope
must appear as an inline Decision Record in the appropriate carrier, and a
log decision with no carrier home is a finding. The criterion is a
checkable surface, not free judgment:

- **Load-bearing** = a `## Decision Log` entry in the research log whose
  `**Alternatives rejected:**` field names a **real fork**. The field's
  presence is the mechanical surface; you still judge whether the named
  alternative is a real fork rather than a vacuous "none", and a genuine
  fork recorded under a different sub-field (a `**Scope note:**` or
  `**Reconciliation:**`) is still in scope, so field placement cannot game
  the trigger. An empty `Alternatives rejected` is not load-bearing and
  does not force a carrier record.
- **In-scope** = the decision constrains a file or interface the track
  touches, bound to the track's `## Interfaces and Dependencies`. On a
  workflow-modifying plan, "a file or interface the track touches"
  includes the workflow-prose files and `§`-anchors listed there, so the
  trigger binds to prose dependencies, not only Java symbols.
- **Per-carrier** is uniform for the live record: a full inline record in
  each relevant track's `## Decision Log`, in every tier. In `full`, the
  criterion additionally requires the decision's seed D-record in
  `design.md` and fidelity between the two (below).

For `target=design` (the Step-4a `phase1-creation` cold-read), apply the
same criterion in its log → `design.md` D-records direction: every
load-bearing log decision must appear as a seed D-record in `design.md`.
The reviewer reads `research_log_path` only for this cross-check; it is a
verdict/absorption read at the sanctioned authoring point, not a new
decision-content read site (S2).

**Full-tier fidelity criterion (`tier=full`, `target=tracks`).** Fidelity
is an **authoring-time** bar, not a standing equality constraint. Each
`full`-tier track Decision Record must be **substantively equivalent** to
the `design.md` seed record it was copied from; a paraphrase that shifts
the meaning is a finding even though the presence check passes. The
check's domain keeps it safe: iterate the `design.md` seed records and
verify their track copies — a track DR with **no** seed counterpart is a
post-seed live decision, out of scope by construction (it is not a
fidelity miss). A track DR carrying the inline-replan revision format, or
named by a supersession note, is compared **provenance-only**: its
`**Original decision**` field (which stays seed-pinned across revisions)
must semantically correspond to the seed (summary-correspondence, not
verbatim inclusion). Seed→track restoration is an authoring-time-only fix;
no reviewer or mechanical pass rewrites a track DR from the seed after
authoring, because post-authoring divergence is legitimate (the track
evolved; the frozen seed cannot).

**Residual risk.** Absorption and fidelity rely on this semantic cold-read
with no mechanical backstop (the `section_has_references` check is
`design.md`-only). An authoring-time miss has no automated catch in any
tier; the residual narrows to authoring time, because post-authoring
divergence between duplicated track copies has a defined owner and
mechanism (the cross-track propagation duty). This is accepted.

**Iterate loop.** The Step-4b cold-read blocker re-opens Step-4b
derivation in the **same session** — it does not defer to a later phase,
because the author's context is the thing being spent. For a held review
batch the flush session counts as that session.

## Reading rules
<!-- roles=reviewer-design phases=1,4 summary="Read only the provided design files; bounded vs whole-doc scope; grep-only plan reads; fetch house-style on demand." -->

- **Only the files passed in §Inputs** — no source code, prior conversation, or other workflow files. For `target=design` that is the design file(s); for `target=tracks` that is the track files under `plan_dir`, `plan_path`, and (when supplied) `design_path` for the fidelity check.
- **Bounded scope** (`target=design`): changed section + 1-2 surrounding + `## Overview` + (when present) `## Core Concepts`; open mechanics only for a specific `Mechanics:` link.
- **Whole-doc scope** (`target=design`): entire `design.md`; mechanics for link targets.
- **Track-scoped reads** (`target=tracks`): read the plan-at-start sections of each track file in full and the root per-track BLUF/triad + checklist from `plan_path` — this is the assessed artifact, not grep-only-for-links. For the full-tier fidelity check, read the `design.md` seed D-records the track copies derive from.
- **Plan / track-file reads** (`target=design`): grep-only for `**Full design**` link resolution.
- **`research_log_path` reads** (absorption cross-check): read the log's `## Decision Log` for the load-bearing-decision enumeration only — a verdict/absorption read, not a decision-content seeding read (S2). Do not import a log decision as if it were a carrier record; a log decision absent from the carrier is the finding.
- **`house-style.md` reads**: read only the cited `§ <heading>` section using grep + targeted Read (offset/limit). Never load the file whole and never pre-load all cited sections; fetch a section only when a finding is forming.

## Comprehension questions
<!-- roles=reviewer-design phases=1,4 summary="Seven ordered questions a cold reader answers with citations; insufficient material is itself a finding." -->

Answer in order; **cite the paragraph(s) you relied on** (quoted
phrase or section + anchor). Insufficient material is itself a finding.

For `target=tracks`, re-point each question from "the design" to "the
track sections" (§Track-scoped cold-read names the re-pointings for
questions 1 and 7); answer per track when the plan has more than one.

1. **What is this design replacing or adding?** State in one
   sentence. Use the Overview.
2. **What are the load-bearing concepts a reader needs before
   the Parts?** Use the Core Concepts section if present;
   otherwise note its absence and whether the Overview alone
   gives enough vocabulary. (For docs without Parts and
   <3 new domain terms, this question is N/A — say so.)
3. **What is the load-bearing claim of the section that just
   changed?** Read the changed section's TL;DR; restate in your
   own words.
4. **What constraint or invariant must remain true after this
   change?** Either name an explicit S-code or describe the
   invariant in prose.
5. **What would break if this change were reverted?**
6. **What's the most subtle gotcha called out in the changed
   section?** Quote it.
7. **How would you find the full mechanism detail if you needed
   it?** Use the References footer / `Mechanics:` cross-ref.

## Structural findings (always check)
<!-- roles=reviewer-design phases=1,4 summary="Always-on checks: edge-cases sub-section, References footer, sibling consolidation, TL;DR, length budgets, Mechanics." -->

The checks below are the `target=design` structural set. For
`target=tracks`, the design-shape checks (References footer, `Mechanics:`
link, Core Concepts, Overview-concept-first, the ≤2,000-line budget) are
**N/A** — track files carry no References footer or mechanics split. The
always-on checks for `target=tracks` are the absorption-completeness and
full-tier fidelity criteria in §Track-scoped cold-read; report their
findings in the same `## Structural findings` list.

- Verify **Edge cases / Gotchas** per `.claude/output-styles/house-style.md § Edge cases sub-section required`.
- Verify **References footer** per `.claude/output-styles/house-style.md § References footer shape`.
- Verify **Same-shape sibling consolidation** per `.claude/output-styles/house-style.md § Same-shape sibling consolidation`.
- (**Whole-doc only**) Verify **Overview is concept-first** per `.claude/output-styles/house-style.md § Overview concept-first`.
- **TL;DR present**; **Mechanism overview ≤300 lines** (warn 200); **Length budget** ≤2,000.
- **`Mechanics:` link target** exists in `design-mechanics.md` when split applies.
- (**Whole-doc only**) **Core Concepts current and complete** for docs with Parts or ≥3 new domain terms; **`**Full design**` refs** in plan and track files resolve to real `design.md` sections.
- Mechanical checks live in `.claude/scripts/design-mechanical-checks.py`; see `design-document-rules.md § Mechanical checks` for the rule contracts.

## Output format
<!-- roles=reviewer-design phases=1,4 summary="The exact comprehension-assessment, mental-model verdict, structural-findings, and suggested-fixes Markdown to emit." -->

**Path-conditional output — file when `output_path` is supplied, inline
otherwise.** This cold-read is a research/audit producer under the
review-file coverage rule (`conventions-execution.md` `§2.5` → Coverage
(S5)). When the spawn supplies `output_path`, write the full Markdown
below to that path and return only a short summary (the **Verdict** line
plus the blocker/should-fix count) so the caller pulls the detail on
demand. When `output_path` is **absent** (the Phase 1 `phase1-creation`
cold-read and every interactive mutation kind), return the full Markdown
inline exactly as below, **byte-for-byte today's format**: the no-path
branch is unchanged, so the `phase1-creation` invocation stays exempt.
The cold-read emits a comprehension assessment and a verdict, not a
severity-anchored finding set, so its `## Structural findings` bullets are
not `### <PREFIX><N> ` anchors and the `§2.5` count grep does not apply;
the file is the audit-producer summary-plus-detail shape, not the
manifest-plus-anchors finding shape.

Produce exactly the following Markdown, no preamble:

```markdown
## Comprehension assessment

1. <answer + citation>
2. <answer + citation>
3. <answer + citation>
4. <answer + citation>
5. <answer + citation>
6. <answer + citation>
7. <answer + citation>

## Could a cold reader build a working mental model from this?

[YES / PARTIAL / NO]

<2-3 sentence rationale>

## Structural findings

[None / list of findings]

- [<severity>] <description>
- [<severity>] <description>
...

(For `phase1-creation`, `phase4-creation`, and `design-sync`,
findings under the Human-reader cold-read additions go in the
same list but prefix each with the dimension label and use
multi-line bullets to fit the evidence required by the Tone
exception — e.g. `[blocker] audience-fit: <quoted prose +
named audience + why it breaks down>`.)

(Findings under the § Prose AI-tell additions go in the same
list with the same multi-line shape, prefixed `over-dense:` or
`too-terse:` — e.g. `[should-fix] over-dense: <quoted sentence +
the house-style § it breaks>`. This holds for all three
`target=design` kinds **and** for `target=tracks` — the Prose
AI-tell block runs on the Step-4b track cold-read even though the
Human-reader additions do not, so its findings are rendered the
same way there.)

## Verdict

[PASS / NEEDS REVISION]

<If NEEDS REVISION: list which findings are blockers and which
are should-fix.>

## Suggested fixes (when applicable)

<For each finding that has an obvious mechanical fix, propose
the exact edit. The mutation action's iteration step will
attempt the fix automatically.>

- <finding ref>: <suggested edit>
```

## Severity rubric
<!-- roles=reviewer-design phases=1,4 summary="Blocker, should-fix, and suggestion definitions, with the length-tier thresholds that set should-fix vs blocker." -->

- **blocker** — comprehension fails, mechanical violation corrupts
  cross-refs, or section lacks TL;DR / References footer / shape.
- **should-fix** — comprehensible but a rule violated. Per
  `design-document-rules.md § Mechanical checks`, length tiers: 201-300 = suggestion, 301-400 = should-fix, >400 = blocker.
- **suggestion** — non-rule-mandated improvement.

## Tone and depth
<!-- roles=reviewer-design phases=1,4 summary="One-sentence answers, cite don't paraphrase, flag insufficiency, no intent-speculation; human-reader rules excepted." -->

- One-sentence answers where one suffices. **Exception**: the five Human-reader rules require evidence (see the Reviewer tone note under § Human-reader cold-read additions). **A second exception**: the § Prose AI-tell additions checks require evidence too — quote the over-dense sentence (or the too-terse assertion, or the hard-to-read one) and name the house-style rule it breaks (§ Banned analysis patterns, § Mechanism traces and inline citations, § Orientation, or § Plain language), rather than a one-word verdict.
- Cite, don't paraphrase.
- If unanswerable, say "Insufficient — see finding below" and add the structural finding.
- Don't speculate about intent the doc doesn't state.
