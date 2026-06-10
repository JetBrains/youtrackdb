# Design Mutations Log

Append-only record of every `design.md` mutation and its review, per
`design-document-rules.md § Mutation discipline § Review log`. This file is not
stamped (`conventions.md` §1.6(f) exclusion).

## Mutation 1 — 2026-06-09 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` for Complexity-Adaptive Workflow Tiering
from the frozen research-log ledger. The doc carries Overview, Core Concepts
(nine load-bearing terms), Class Design + Workflow Mermaid diagrams, and seven
Parts (tier classification, the research log, the relocated adversarial review,
carriers and self-containment, write-time cold-read, tier-driven review
selection, design-presence conditionals plus the Phase 4 audit trail), with
decision records D1–D13 and invariants S1–S4 collected in per-section References
footers. 821 lines, 11 top-level `##` sections, single file (no mechanics
companion). Authored under the current live workflow per the Phase-0 handoff.

**Mechanical checks** (target=design, scope=whole-doc): PASS. Initial run flagged
4 AI-tell should-fix (two em-dash-density paragraphs, one "fundamentally"
persuasive trope, one fragmented header); all cleared before the reviewers ran.
One further em-dash-density should-fix introduced by the A3 fix was cleared. Final
run: 0 findings.

**Adversarial** (phase1-creation, design-scoped, code-grounded): 0 blockers, 4
should-fix (A1–A4), 3 suggestions (A5–A7). All applied. A1: the Part-6 review
matrix omitted the live Phase-3A Risk review — added a Risk row
(track-characteristic-gated in `lite`/`full`, dropped in `minimal`), verified
against `track-review.md:607-621`. A2: the workflow-machinery domain lens named
the `/code-review`-dispatched `review-workflow-*` agents (no subject at the
Phase 0→1 boundary) — re-expressed as prose-scrutiny emphases, verified against
the six `.claude/agents/review-workflow-*.md` frontmatters. A3: specified the
mid-flight upgrade re-entry (adds the new tier's Phase-3A passes forward, does
not retroactively re-run a skipped Phase-2 pass). A4: extended the `adr.md`
adversarial-verdict fold to every tier. A5/A6/A7: S1 scoped to the script (Step
1c gains one routing branch), D8 "checkable" softened, Phase-3A track-1 skip
narrowed to the episode challenge only.

**Cold-read** (scope: whole-doc): PASS. 2 should-fix + 2 suggestions, all
glossary-introduction (undefined working-file shorthand `A1`/`A12`/`DL-3`,
`Move 4`/`Move-1`, `Route (a)/(b)`, bare issue numbers, "episode"). All applied
to make the `full`-tier canonical carrier self-contained.

**Findings**:
- should-fix (adversarial A1): missing Phase-3A Risk review row — RESOLVED.
- should-fix (adversarial A2): workflow-machinery lens named code-review agents — RESOLVED.
- should-fix (adversarial A3): mid-flight upgrade re-entry unspecified — RESOLVED.
- should-fix (adversarial A4): `adr.md` fold under-scoped to no-design tiers — RESOLVED.
- suggestion (adversarial A5/A6/A7): S1 / D8 precision, skip-track-1 breadth — APPLIED.
- should-fix (cold-read): undefined `A1`/`A12`/`DL-3` working-file IDs — RESOLVED.
- should-fix (cold-read): undefined `Move 4`/`Move-1`/`Route (a)(b)` labels — RESOLVED.
- suggestion (cold-read): bare issue-number glosses, "episode" gloss — APPLIED.

**Adversarial re-run**: not spent. The 4 should-fix were applied as the reviewer's
own proposed fixes (zero blockers), the two load-bearing ones (A1, A2) verified
directly against the live files, and the cold-read fixes are glossary-only with no
semantic change to any decision. Re-running the full adversarial pass to
re-confirm should-fix resolutions is the ceremony this branch's token-economy aim
argues against; the iterate loop's "completes with should-fix addressed" outcome
applies.

**Iterations**: 1 of 3 (PASS — 0 blockers from either reviewer; all should-fix and
suggestions applied).

## Mutation 2 — 2026-06-10 — content-edit (design.md)

**Diff summary**: Applied the user-review batch (Q1 + Q3, Q2 framing) from the
ratified research-log entry 2026-06-10T11:12Z — the carrier flip. Tracks now
carry the full live Decision Record inline in every tier; `design.md` (`full`
only) becomes a frozen seed keeping its D-records and mechanism (Step-4b
derivation source, review-phase navigation, on-demand `**Full design**`
mechanism reference), never the live decision carrier. Touched: Overview, Core
Concepts ("Tier-relative self-containment" → "Track-canonical live decisions";
"Inline decision records" reworded), Class Design edge labels, Part 2 (S2
provenance-not-authority + read-point clarification), Part 4 (rewritten carrier
section + new "Cross-track propagation" section + Q1 track-episode aggregation
paragraph + D11 by-artifact split), Part 5 (new "Fidelity and the post-replan
rule" section, uniform per-carrier bullet, narrowed residual risk), Part 7
(bloat-fix re-route in every tier + full-tier duplication-check repurpose +
frozen-seed-text deferral wording). The Q3 decision itself passed a dedicated
4-iteration research-log adversarial gate (A19–A37, 3 blockers, all resolved)
before this edit; per the batch routing convention the content-edit ran
mechanical + cold-read only, with the cold-read escalated to whole-doc over all
changes.

**Mechanical checks** (target=design, scope=whole-doc): PASS. Initial run
flagged 3 should-fix (Overview 42/40 lines; em-dash density in the Part-2 TL;DR
and the D11 paragraph); all cleared. One em-dash regression introduced by a
cold-read fix was cleared in the same iteration. Final run: 0 findings.

**Cold-read** (scope: whole-doc): PASS. "The revised carrier model is told as
one story end-to-end … the superseded models are referenced only in explicit
'Replaces …' framing." 3 should-fix + 3 suggestions, all local wording: stale
D13 entry in Part 4's footer (D13 lives in Part 7); "frozen carrier" wording in
the acceptance-#4 rewrite (tier-unqualified, used "carrier" for the seed);
"seeded by the research log" reading as a Step-4b seeding endorsement; RLOG→
DESIGN diagram edge said "Step 4a/4b" (design is 4a-only); "exactly two places"
read-scope pedantry vs the Part-5 absorption cross-check; Part-7 deferral
phrase still in the superseded decision-residency register. All six applied.

**Findings**:
- should-fix (cold-read): stale D13 footer entry in Part 4 — RESOLVED (deleted).
- should-fix (cold-read): "frozen carrier's inline records" wording — RESOLVED
  (tier-qualified to the `design.md` seed's D-records / log read point).
- should-fix (cold-read): tier-unqualified "seeded by the research log" —
  RESOLVED (seed in `full`, log in `lite`/`minimal`).
- suggestion (cold-read): RLOG→DESIGN edge label, read-point parenthetical,
  Part-7 frozen-seed-text deferral wording — APPLIED.
- should-fix (mechanical): Overview length + 2 em-dash densities — RESOLVED.

**Iterations**: 1 of 3 (PASS — 0 blockers from either pass; all should-fix and
suggestions applied; mechanical re-run clean after fixes).

## Mutation 3 — 2026-06-10 — content-edit (design.md)

**Diff summary**: Added D14 — tier-keyed model triage for every
adversarial-reviewer spawn — from the ratified research-log entry
2026-06-10T13:20Z (log-adversarial gate PASS in one iteration, findings
A38–A42 applied to the entry first). `full` → Fable 5; `lite`/`minimal` →
Opus 4.x; both branches pin an explicit xhigh effort override; scope =
adversarial spawns only (the Phase 0→1 log gate + the narrowed Phase-3A
track adversarial; technical/risk/cold-read stay session-default).
Touched: Part 3 (TL;DR sentence, new "### Reviewer model triage" subsection,
Step-4a re-trigger edge-case bullet, D12+D14 footer entries), Part 6 (one
model sentence in the narrowed-adversarial section, D14 footer entry).

**Mechanical checks** (target=design, scope=bounded "Relocated adversarial
review"): PASS. Initial run flagged 1 em-dash-density should-fix in the new
subsection; cleared. Final run: 0 findings.

**Cold-read** (scope: bounded — Part 3 + Part 6 + Overview + Core Concepts):
PASS. "D14 is introduced before use and in the right order … the subsection's
placement between 'Domain priming' and 'Freeze-order preservation' coheres
with the Part 3 story." 1 should-fix: inline "(D12)" parenthetical aside
banned by the References-footer shape rule — RESOLVED (aside removed, D12
cross-part footer entry added, mirroring Part 6's D14 pattern).

**Findings**:
- should-fix (mechanical): em-dash density in the new subsection — RESOLVED.
- should-fix (cold-read): inline "(D12)" aside / missing footer entry — RESOLVED.

**Iterations**: 1 of 3 (PASS — 0 blockers from either pass; both should-fix
applied; mechanical re-run clean).

## Mutation 4 — 2026-06-10 — content-edit (design.md)

**Diff summary**: Added D15 — review-iteration batching — from the ratified
research-log entry 2026-06-10T14:05Z (log-adversarial gate: iteration 1
NEEDS REVISION with 1 blocker A43 + A44–A49; iteration 2 PASS, A43–A49
verified, polish A50–A53 applied). Findings raised during the user's review
of a frozen-ready Phase-1 artifact queue ([clarification]/[decision]) and
process as ONE batch at review-done: one gate run with whole-batch
re-challenge per iteration, one mutation with a route-out rule for
decision-shaped findings, one cold-read with loop-back; window boundary at
the presentation the user reviews from (D5's immediate re-trigger scoped to
pre-presentation authoring); escape hatch at the D14 cost profile with a
what-moved note. Touched: Part 3 (new "### Review-iteration batching" after
"### Freeze-order preservation", TL;DR sentence, extended S3 prose, D15
footer), Part 2 ("Structure and lifetime" prose + edge-case bullet scoped to
pre-presentation, D15 cross-ref), Part 5 (pre-persist confirmation anchored
as the Step-4b presentation event, flush-session-counts-as-same-session in
the spawn paragraph + edge bullet, D15 cross-ref), Core Concepts ("Relocated
adversarial review" entry gains the re-trigger/batch windows).

**Mechanical checks** (target=design, scope=whole-doc): PASS. Initial run
flagged 2 em-dash densities (both multi-line balanced pairs the checker
reads as unpaired); converted to parentheses/sentences. Final run: 0
findings.

**Cold-read** (scope: whole-doc): PASS. "The authoring-window vs
review-window split is told consistently in all three touched Parts … no
leftover text asserts unconditional immediate per-entry re-triggering."
2 should-fix + 2 suggestions, all applied: Core Concepts currency (the
relocated-adversarial entry now names the per-entry and batch windows); the
Step-4b pre-persist confirmation event anchored in Part 5; the Part-5 edge
bullet's cold-context trade pointer; "present" noun rephrased.

**Findings**:
- should-fix (mechanical): 2 em-dash densities — RESOLVED.
- should-fix (cold-read): D15 vocabulary missing from Core Concepts — RESOLVED.
- should-fix (cold-read): "pre-persist confirmation" unanchored — RESOLVED.
- suggestion (cold-read): edge-bullet self-undercut + "present" noun — APPLIED.

**Iterations**: 1 of 3 (PASS — 0 blockers from either pass; all should-fix and
suggestions applied; mechanical re-run clean).

## Mutation 5 — 2026-06-10 — content-edit (design.md)

**Diff summary**: Applied the ratified B1+B2 batch (D15's first live batch;
research-log entries 15:05Z/15:07Z, gate PASS at 16:40Z after 3 iterations,
A54–A68). New D-records: **D16** — the `minimal` tier sheds `adr.md`; the
Phase-4 audit-trail fold is scoped to `full`/`lite` and `minimal` instead
folds a two-line gate-verdict summary into the PR description (squash-merge
carries it into develop's git log; no `docs/adr/` entry); plus the
lens-semantics pin (lenses = centrally-matched categories + explicit user
additions; Gate-1-no changes run lens-free). **D17** — every third-scope
adversarial spawn persists the §2.5 manifest-plus-sections review file and
returns a thin manifest; orchestrator partial-fetches `## Findings`; log
gate records stay the Phase-4 verdict carrier; both-axes §2.5 access wiring
(phases +1, roles +planner) and location/lifecycle clause recorded as
implementation notes. Touched: Part 1 (tier-map Phase-4 column, Gate-1 lens
pin, References), Part 2 (edge-case bullet, References), Part 3 (TL;DR, D17
paragraphs in §Reuse, lens pin in §Domain priming, References), Part 7
(TL;DR, §The Phase 4 audit trail rewrite + two boundary notes, edge-case
bullet, References).

**Mechanical checks** (target=design, scope=whole-doc): PASS. Initial run
flagged 2 em-dash densities (new D16/D17 paragraphs); converted to
colons/sentences. Final run: 0 blockers, 0 should-fix; 1 standing
suggestion (Part 3 section at 202 lines, warn-tier).

**Cold-read** (scope: whole-doc — 5th design-touching mutation, periodic
escalation; also the D15 one-batch-one-cold-read rule): PASS. "The
Mutation-5 material integrates cleanly: D16's carrier scoping is stated
identically in the Part 1 tier-map column, the Part 2 edge case, the Part 3
D17 paragraph, and Part 7's body." 3 suggestions, 2 applied: the
"only adversarial evidence base" claim made imprecise by D17 (now
"the log's gate records hold the durable adversarial evidence base", with
the review files named as per-iteration detail); Part 1 References D16
entry gains a Part-7 pointer for the fold facet. Length suggestion
recorded, not trimmed (the compressible candidate is ratified D17 wiring
content).

**Findings**:
- should-fix (mechanical): 2 em-dash densities — RESOLVED.
- suggestion (cold-read): Part-7 "only evidence base" imprecise post-D17 — APPLIED.
- suggestion (cold-read): Part-1 D16 footer lacks Part-7 pointer — APPLIED.
- suggestion (mechanical + cold-read): Part 3 §Relocated adversarial review
  at 202 lines (warn-tier) — RECORDED, not trimmed.

**Iterations**: 1 of 3 (PASS — 0 blockers from either pass; both applicable
suggestions applied; mechanical re-run clean).
