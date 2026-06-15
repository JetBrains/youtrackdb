# Design mutation log — no-track-for-minimal

## Mutation 1 — 2026-06-15 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` for the plan-as-derived-mirror redesign.
Single file, no mechanics companion (10 sections, 379 lines, under the
length trigger). Overview (concept-first), Core Concepts (five new terms),
Class Design (artifact-home + consumer-topology Mermaid graph), Workflow
(phase-boundary append + resume sequence diagram), and six mechanism sections
covering the phase ledger, resume routing, the thinned plan and plan-review
document, track-file dispositions, the mid-flight tier upgrade, and this
branch's own §1.7(b) staging mode. Carries thirteen seed D-records (D1–D13)
absorbed from the research log's load-bearing decisions.

**Mechanical checks** (target=design): PASS. Initial run found 14 should-fix
(13 decision-cited-without-rationale, 1 dsc-ai-tell em-dash density); fixed in
one round (inline D-record rationales in each footer + de-dashed the §1.7
paragraph); re-run returned 0 findings.

**Cold-read** (scope: whole-doc): PASS. 0 blockers, 0 should-fix, 1
suggestion. Absorption-completeness cross-check passed both ways (every
load-bearing research-log decision appears as a seed D-record; no D-record
invents an unrecorded decision). Gated correctly behind the cleared
log-adversarial gate (S3).

**Findings**:
- suggestion: over-dense inflated-abstraction label "The enabling primitive
  is…" at design.md:19. Recorded, not retried — the line sits in `## Overview`,
  which the mechanical `dsc-ai-tell` check exempts because naming the enabling
  primitive(s) is the prescribed Overview element.

**Iterations**: 1 of 3 (PASS)

## Mutation 2 — 2026-06-15 — content-edit (design.md)

**Diff summary**: Added a rejected-alternative paragraph to `## Resume
routing` recording why the ledger is single-source-by-fact (a two-level
lookup: ledger owns top-level phase + active track, track file owns
within-track sub-state) rather than the single state file. Responds to a
Step-4a user challenge ("why not ledger single source"); strengthens the
D10/D3 rationale so Phase-3A reviewers and Step-4b track Decision Records
inherit the rejected alternative.

**Mechanical checks** (target=design): PASS (bounded, changed-section="Resume
routing"), 0 findings.
**Cold-read** (scope: bounded): PASS, 1 suggestion (applied).

**Findings**:
- suggestion: hard-to-read relative "where" reading as locational — applied
  the reviewer's rewrite ("whereas" + an explicit "granularity is too coarse"
  conclusion).

**Iterations**: 1 of 3 (PASS)
