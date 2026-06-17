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
| §Human-reader cold-read additions | reviewer-design | 1,4 | Whole-doc human-reader checks: navigability and the structural half of audience-fit; prose half moved to the auditor. |
| §Track-scoped cold-read (Step 4b) | reviewer-design | 1 | The second write-time target: cold-read of plan-at-start track sections, plus full-tier seed-to-track fidelity. |
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
  (Step 4b) for the target set and the full-tier fidelity criterion. The
  comprehension mechanics (§Comprehension questions, §Output format,
  §Severity rubric, §Tone and depth) are shared.

This reviewer reads no research log. The **absorption-completeness**
cross-check that used to warm it, confirming every load-bearing log
decision appears as a seed/carrier record, moved to the warm
`absorption-check` agent, which runs every round of the dual-clean inner
loop beside the cold readability auditor. So this reviewer's "could a cold
reader follow this" verdict is finally cold: it reads only the document.
The prose AI-tell axis (over-dense / too-terse / hard-to-read) likewise
moved to the `readability-auditor` agent and runs nowhere here (S4). What
stays is comprehension, structure, and the whole-doc human-reader checks.

## Inputs
<!-- roles=reviewer-design phases=1,4 summary="The design paths, scope, mutation kind, and optional plan/track paths passed to the cold-read reviewer." -->

- `target` (optional, default `design`) — `design` or `tracks`. Selects
  the cold-read target per the two-target note above.
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

This reviewer takes **no `research_log_path`**. The absorption-completeness
cross-check moved to the `absorption-check` agent, so no log path is passed
here; if a spawn passes one it is a wiring error and this reviewer reads no
log regardless (S1/S2).

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
  matching section in mechanics. Absorption completeness (every
  load-bearing research-log decision appears as a seed D-record) is
  **not** checked here — it is the `absorption-check` agent's job inside
  the dual-clean inner loop, and this reviewer reads no log. **Plus the
  Human-reader cold-read additions (§ below) — the whole-doc subset only;
  the prose half is the readability auditor's.**
- **`design-sync`**: this sync re-distilled `design.md` from the
  current state of `design-mechanics.md`. **In addition to the
  standard whole-doc cold-read**, verify that every TL;DR and
  mechanism overview in `design.md` accurately summarizes the
  current mechanics file's content for the same-named section. If
  the `design.md` TL;DR contradicts the mechanics, or names a
  mechanism that mechanics doesn't describe, flag it as a blocker
  — that's exactly what the sync was supposed to fix. **Plus the
  Human-reader cold-read additions (§ below) — the whole-doc subset
  only; the readability auditor owns the prose axis on `design-sync`
  too (S4).**
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

  **In addition to the standard whole-doc cold-read and the
  Human-reader cold-read additions (§ below, the whole-doc subset; the
  readability auditor owns the prose axis on `phase4-creation` too)**,
  verify:

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
<!-- roles=reviewer-design phases=1,4 summary="Whole-doc human-reader checks: navigability and the structural half of audience-fit; prose half moved to the auditor." -->

Applies to `phase1-creation`, `phase4-creation`, `design-sync`. In addition
to the standard cold-read, verify the **whole-doc** human-reader checks — the
properties a range-sliced auditor cannot see:

- Verify navigability per `.claude/output-styles/house-style.md § Navigability`.
- Verify the **structural half of audience-fit** per
  `.claude/output-styles/house-style.md § Audience-fit`: does the Overview
  name a reader at all? (Whether the prose itself *reads* for that audience
  is the auditor's prose half — see below.)

The other three human-reader checks — glossary-introduction, why-before-what,
explanatory register — and the prose half of audience-fit moved to the
`readability-auditor` agent, because a slice plus its standing anchors can
answer them and concentrating readability on the slice-bound auditor preserves
per-slice attention. Loading all five back onto this whole-doc reviewer would
re-create the diluted multi-axis pass this branch removed. The five
human-reader checks therefore split by the context each one needs: the
whole-doc properties (navigability, the structural half of audience-fit) stay
here; the prose properties go to the auditor (D8).

**Reviewer tone** for these whole-doc rules relaxes the "one-sentence answers"
rule in § Tone and depth: quote the prose, name the audience the prose fails,
and (for navigability) the opaque section.

This reviewer runs **no** prose AI-tell axis (over-dense / too-terse /
hard-to-read) on any surface. That axis is the `readability-auditor` agent's,
on every prose-judged surface — the design creation kinds, `design-sync`, and
the Step-4b track cold-read. The one-owner-per-surface rule (S4) holds: every
prose-judged surface runs the prose axis on exactly one reviewer, never both
and never neither.

## Track-scoped cold-read (Step 4b)
<!-- roles=reviewer-design phases=1 summary="The second write-time target: cold-read of plan-at-start track sections, plus full-tier seed-to-track fidelity." -->

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

The prose AI-tell scan (over-dense / too-terse / hard-to-read) and the
absorption-completeness cross-check do **not** run on this reviewer. The
prose axis is the `readability-auditor` agent's on every surface, including
this Step-4b track cold-read; the auditor's track-surface wiring lands when
`create-plan` Step 4b spawns it. Absorption completeness — every load-bearing
log decision appearing as a carrier record — is the `absorption-check`
agent's, which reads the log; this reviewer reads no log. The Human-reader
additions do **not** run here either — they are design-kind only.

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

**Residual risk.** The full-tier fidelity criterion relies on this semantic
cold-read with no mechanical backstop (the `section_has_references` check is
`design.md`-only). An authoring-time miss has no automated catch in any
tier; the residual narrows to authoring time, because post-authoring
divergence between duplicated track copies has a defined owner and
mechanism (the cross-track propagation duty). This is accepted. (Absorption
completeness has the same authoring-time-only residual, now carried by the
`absorption-check` agent rather than this reviewer.)

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
- **No research-log read.** This reviewer reads no `research-log.md`. The absorption-completeness cross-check that once read it moved to the `absorption-check` agent, so no `research_log_path` is passed here and none is read (S1/S2).
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
always-on check for `target=tracks` is the full-tier seed-to-track fidelity
criterion in §Track-scoped cold-read; report its findings in the same
`## Structural findings` list. (Absorption completeness moved to the
`absorption-check` agent and is not a check on this reviewer.)

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
inline exactly as below. The `phase1-creation` invocation stays exempt from
the file-output branch. The de-warm dropped the prose-AI-tell finding rows
from this format (those findings now come from the `readability-auditor`
agent), so the structural-findings list carries comprehension, structure,
and the whole-doc human-reader checks only.
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
findings under the whole-doc Human-reader cold-read additions go
in the same list but prefix each with the dimension label and use
multi-line bullets to fit the evidence required by the Tone
exception — e.g. `[blocker] navigability: <quoted section + why a
reader cannot find it>` or `[should-fix] audience-fit: <the
Overview names no reader>`. The prose-axis findings — over-dense,
too-terse, hard-to-read — come from the `readability-auditor`
agent, not this reviewer, so they do not appear in this list.)

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

- One-sentence answers where one suffices. **Exception**: the whole-doc Human-reader rules (navigability and the structural half of audience-fit) require evidence (see the Reviewer tone note under § Human-reader cold-read additions) — quote the opaque section or name the missing reader, rather than a one-word verdict. The prose AI-tell checks are no longer this reviewer's; the `readability-auditor` agent carries them and their own evidence rule.
- Cite, don't paraphrase.
- If unanswerable, say "Insufficient — see finding below" and add the structural finding.
- Don't speculate about intent the doc doesn't state.
