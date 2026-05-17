# Design Mutations Log

## Mutation 1 — 2026-05-17 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` with Overview (concept-first, audience-establishing, with assumed-knowledge prose), Core Concepts (House style / dsc-ai-tell / Humanizer gap patterns), Class Design (Mermaid classDiagram of four-artifact topology), Workflow (Mermaid sequenceDiagram of writer/script/cold-read lookup), Internal layout of `house-style.md` (consolidated rule-set section structure with 12 humanizer gaps placed under § Banned analysis patterns), dsc-ai-tell calibration (four regex refinements with empirical data from three known-good ADRs), and Rename: every reference site across the repo (enumerated find-and-replace table). Single-file design (no `design-mechanics.md` companion needed; file is 268 lines, well under the 2,000-line split trigger).

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings)
**Cold-read** (scope: whole-doc): PASS after 2 iterations

**Findings iteration 1** (NEEDS REVISION, 6 should-fix):
- should-fix: (a) Audience-fit — Overview did not name audience
- should-fix: (b) Glossary-introduction — BLUF / ADR / D-record / Invariant notation / known-good-ADRs / YTDB-836-837 scope used without inline framing
- should-fix: (c) Why-before-what — § Workflow opened with mechanism, not motivation
- should-fix: (d) Navigability — § House-style organization and § Find-and-replace surface had opaque headers
- should-fix: TL;DR coverage — § Class Design and § Workflow lacked a TL;DR
- should-fix: Mechanics-file absence not noted — reader would look for `design-mechanics.md` that does not exist

**Fixes applied** (iteration 2):
- Added audience-establishing sentence + assumed-knowledge prose to Overview ¶1
- Added "known-good ADRs (`persist-visible-count`, `index-gc`, `non-durable-wow`) selected as the empirical false-positive baseline" framing
- Renamed § House-style organization → § Internal layout of `house-style.md`
- Renamed § Find-and-replace surface → § Rename: every reference site across the repo
- Added TL;DR to § Class Design and § Workflow
- Prefaced § Workflow with motivation paragraph contrasting before/after
- Appended single-file note to Overview ¶5 closing roadmap

**Findings iteration 2** (PASS, 1 should-fix):
- should-fix: (a) Audience-fit — audience scope too narrow; broaden to include ADR writers, not just `.claude/`-infrastructure maintainers

**Fix applied** (iteration 3 — final):
- Broadened Overview ¶1 to "contributors who maintain or read the project's writing-style infrastructure… plus anyone whose drafting workflow runs against the consolidated `house-style.md`"

**Iterations**: 3 of 3 (PASS — verdict after iteration 2 was already PASS; iteration 3 applied the single remaining should-fix as a courtesy)
