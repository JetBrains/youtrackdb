<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Structural review — iteration 1

Phase 2 structural review of the Gremlin-to-MATCH translator implementation plan.
Plan-internal quality only; no codebase read. Three findings, none a blocker.

## Findings

### S1 [should-fix]
**Location**: Plan file `implementation-plan.md`, Architecture Notes → Decision Records (the count-short-circuit unification is described in Goals line 29-31, Constraints line 42-44, Component Map `SelectExecutionPlanner` bullet line 81 / 113, Integration Points line 298-300, and implemented by Track 5 — but no `#### D<N>` block covers it).
**Issue**: The plan makes a substantial, non-obvious design decision with no Decision Record: unify the exact `count(*)` fast path inside the MATCH engine so SQL, GQL, and translated Gremlin share one snapshot-isolated short-circuit, factored out of `SelectExecutionPlanner.handleHardwiredCountOnClass*` and invoked by `MatchExecutionPlanner`. This is the *one* change the Constraints section carves out as an exception to "Engine surface is preserved … Existing constructors, the IR classes, the execution steps … are not modified" (line 42-44), it edits two engine planner classes, it reverses the prior "decline class-count to keep an O(1) count" rationale (explicitly invalidated by YTDB-609 per design §"Aggregation barrier semantics"), and it has live alternatives (keep declining class-count to `YTDBGraphCountStrategy`; leave the SELECT fast path un-factored). Every comparable non-obvious choice in this plan carries a DR (D1–D10, D-IS-DEFINED, D-TEXT-OPS); this one is traceable only through prose scattered across five plan locations and a design section. A reader cannot find the alternatives / rationale / risks / track-reference in under 10 seconds, which is the DR's job.
**Proposed fix**: Add a Decision Record (e.g. `#### D11: Unify the exact count(*) fast path in the MATCH engine`) with the four-bullet form — **Alternatives considered** (decline single-class count to the reordered `YTDBGraphCountStrategy` as today; leave `handleHardwiredCountOnClass*` SELECT-private); **Rationale** (one count path across three front-ends; the fast path stopped being O(1)/non-SI at YTDB-609 so the old decline rationale no longer holds; `CountFromClassStep` reads the same `countClass` primitive so cost and result are unchanged); **Risks/Caveats** (`YTDBGraphCountStrategy` must stay as a reordered fallback for multi-label / non-polymorphic counts; `CountFromClassStep.canBeCached()==false` keeps these plans uncached); **Implemented in**: Track 5; **Full design**: design.md §"Aggregation barrier semantics". Keep the body within the ~30-line DR budget — the long-form material already lives in the design section, so the DR links rather than duplicates.
**Classification**: design-decision
**Justification**: "Missing Decision Record for a non-obvious choice. The user has the rationale" (§Classification → `design-decision`); the alternatives and rationale are a design call the user must articulate.

### S2 [should-fix]
**Location**: Plan file `implementation-plan.md`, Track 4 checklist intro paragraph, lines 363-373 (the prose block between the track heading and `**Scope:**`).
**Issue**: The intro paragraph runs four sentences ("Merges … diff." / "Fills out … (D-TEXT-OPS)." / "Bare presence forms … (D-IS-DEFINED)." / "Logical steps descend … (D9)."), exceeding the 1–3-sentence checklist-intro cap. Sentences 2–4 enumerate per-recogniser detail (`SQLEndsWithCondition`, the `TraversalFilterStepRecogniser` / `NotFilterStepRecogniser` desugar routing, the And/Or/Not asymmetry) that already lives verbatim in track-4.md's `## Purpose / Big Picture` and `## Plan of Work`. The plan checklist is loaded at every `/execute-tracks` session startup, so this detail is re-paid by every Phase A/B/C session for the life of the plan.
**Proposed fix**: Trim the intro to 1–3 high-level sentences, e.g. keep "Merges predicate translation and the logical-filter steps into one reviewable filtering diff" plus one sentence naming the surface ("the full `P`/`Text`/`TextP` predicate algebra, the `has`/`hasLabel`/`hasId` recognisers, bare-presence `has(key)`/`hasNot(key)` → `IS DEFINED`/`IS NOT DEFINED`, and the `and`/`or`/`not`/`where` logical filters; D-TEXT-OPS, D-IS-DEFINED, D9"). The per-recogniser desugar and AST-node detail stays in track-4.md, where it already is.
**Classification**: mechanical
**Justification**: The TRACK DESCRIPTIONS intro-length rule ("An intro that runs 4+ sentences … has expanded into territory that belongs in the track file"); the fix is a single unambiguous trim that moves no intent (the detail already exists in the track file), so it is `mechanical` per §`mechanical` (scope/format-class single-edit findings).

### S3 [should-fix]
**Location**: Plan file `implementation-plan.md`, Track 6 checklist intro paragraph, lines 404-413.
**Issue**: The intro paragraph runs four sentences ("Completes … feature." / "Handles `UnionStep` … (D8)." / "Adds the four list-shaping terminators … (D3)." / "Final hardening: … benchmarks."), exceeding the 1–3-sentence cap. Sentences 2–4 carry the `MultiPlanMatchStep` mechanism, the `BoundaryOutputType.LIST` / `unfoldOutput` / `reverseOutput` / `tailLimit` flag inventory, and the Cucumber/JMH hardening detail that is already stated in track-6.md's `## Purpose / Big Picture` and `## Context and Orientation`. Same per-session re-read cost as S2.
**Proposed fix**: Trim to 1–3 sentences, e.g. "Completes the recognized set and hardens the feature: `union(...)` via `MultiPlanMatchStep` (D8) and the four list-shaping terminators (`fold`/`unfold`/`reverse`/`tail`) as last-step recognisers, then the full TinkerPop Cucumber suite green with the strategy registered plus a Gremlin-on-vs-off JMH baseline." The flag inventory and concatenation mechanism stay in track-6.md.
**Classification**: mechanical
**Justification**: Same rule as S2 — TRACK DESCRIPTIONS intro-length (4+ sentences); single unambiguous trim, no intent change (detail duplicated in the track file), so `mechanical`.

## Evidence base

certs: 0 (structural review reads no codebase and emits no certificates).

Mechanical checks run:
- Plan-file length: 425 lines / ~6.3K tokens (chars/4) — well under the ~1,500-line / ~30K-token budget.
- DR body lengths (heading through final bullet): D1=12, D2=14, D3=14, D4=12, D5=14, D6=13, D7=11, D8=15, D9=15, D10=13, D-IS-DEFINED=15, D-TEXT-OPS=17 — all within the ~30-line cap.
- No `- [ ] Step:` items or `(provisional)` markers (deferred to Phase 3, correctly absent).
- No `(SUPERSEDED …)` / "see DN" DR blocks retained.
- All 12 DRs carry an `**Implemented in**: Track N` reference.
- All six tracks carry a `**Scope:**` line; five `**Depends on:**` lines (Track 1 is the foundation, none); Track 1's under-floor `**Size:**` justification present (line 324).
- Dependency DAG acyclic and forward-ordered: T1←T2←T3←T4(+T1)←T5(+T1)←T6; every `Depends on` points to a lower-numbered track.
- Track sizing: T1 ~7 (under floor, justified), T2 ~19, T3 ~15, T4 ~20, T5 ~20, T6 ~20 — none over the ~20-25 ceiling; no undocumented non-consecutive file-overlap split (`MatchWhereBuilder` T1→T4 and `MatchExecutionPlanner` T2→T5 splits are both documented via D6/D-TEXT-OPS and D2/Aggregation-barrier respectively).
- design.md exists (2088 lines), has an Overview (line 4), a `classDiagram` (line 172), a `sequenceDiagram` (line 435), and dedicated sections for the load-bearing/complex topics (Aggregation barrier semantics, Parameter binding, Union semantics divergence, Schema polymorphism, Boundary-step lifecycle, Track 5 commitment). Broadly consistent with the plan's Architecture Notes, DRs, and track descriptions.
