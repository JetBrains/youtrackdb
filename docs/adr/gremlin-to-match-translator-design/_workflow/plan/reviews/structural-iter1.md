<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: S1, sev: should-fix, loc: "implementation-plan.md:445", anchor: "### S1 ", cert: "", basis: "Track 6 checklist intro runs 4 substantive sentences over the 1-3 cap; per-session re-read cost, detail duplicated in track-6.md"}
  - {id: S2, sev: suggestion, loc: "implementation-plan.md:370,405", anchor: "### S2 ", cert: "", basis: "Completed Tracks 2 & 3 Strategy-refresh notes name pre-split downstream ranges (Tracks 3-6, 4-6) the A1 split superseded"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

# Structural review — iteration 1 (post-split State-0 re-run)

Phase 2 structural review of the Gremlin-to-MATCH translator implementation plan
after the A1 inline replan split former Track 4 into Track 4 (predicate surface)
+ Track 5 (logical filters + sub-walker + `GremlinPlanCache` D5), renumbering old
Track 5→6 (result shaping) and old Track 6→7 (advanced + hardening). Plan-internal
quality only; no codebase read. Design gate = yes.

The split is structurally executable. The intended chain 1 → (2, 3) → 4 → 5 → 6 → 7
is acyclic: every pending track's `**Depends on:**` points strictly backward
(4→{3,1}, 5→{4,1}, 6→{4,5,1}, 7→{6}), no forward references. Every cross-track
reference in the pending track files resolves to the correct renumbered track; no
phantom (Track 8+) references exist. Track 4 and Track 5 do not contradict each
other on the seam (NotStep ownership, `has(key)` vs `hasNot(key)`, the inline-literal
→ positional-parameter handoff, and AND-composition reuse all agree). The Checklist,
the Implementation-state table, and the Architecture-Notes `**Implemented in**` lines
(D5→Track 5, D11→Track 6, D8→Track 7) name one consistent 7-track structure. Scope
footprints for the pending tracks (~16 / ~16 / ~20 / ~20) all sit inside the
two-sided `~12–25` bound; the Track 4→5 split from a realized ~29–38-file merged
track is documented in both track files (A1, user-approved 2026-07-15) — a
documented split passes. No superseded DRs, no `- [ ] Step:` / *(provisional)*
markers, no per-section or plan-file bloat over budget (plan is 499 lines).

Two minor findings, neither a blocker.

## Findings

### S1 [should-fix]
**Location**: Plan file `implementation-plan.md`, Track 6 checklist intro paragraph, lines 445-457 (the `>` prose block between the track heading and `**Scope:**`).
**Issue**: The intro runs four substantive sentences plus the `Detail in plan/track-6.md.` pointer, over the 1–3-sentence checklist-intro cap. Sentence 2 alone is a ~9-line enumeration naming specific classes and flags (`GremlinProjectionAssembler`, `EntityImpl.hasProperty(key)`, `OrderGlobalStep` / `RangeGlobalStep`, the `count`/`sum`/`min`/`max`/`mean`/`group`/`groupCount` set, `SQLProjection` / `SQLGroupBy`, the count short-circuit, `dropNullRows` / `dropOnAbsent`); sentences 3–4 add the boundary-output-type pin and the shared by-modulator translator. This per-recogniser detail already lives near-verbatim in `plan/track-6.md`'s `## Purpose / Big Picture` (line 9) and `## Context and Orientation`. The plan checklist is loaded at every `/execute-tracks` session startup, so the duplicated detail is re-paid by every Phase A/B/C session for the life of the plan. (For contrast, Track 4's intro is 3 substantive sentences + pointer and Track 7's is 2 + pointer — both within cap; only Track 6 is over. Track 6 is old Track 5 renumbered, so this is a pre-existing intro, not introduced by the split.)
**Proposed fix**: Trim the intro to 1–3 high-level sentences, e.g. keep sentence 1 ("Merges the four result-producing step families — step labels + dedup, projections, order/pagination, and aggregations") plus one sentence naming the two load-bearing hazards ("distinguishing absent from null-valued properties via `EntityImpl.hasProperty`, and unifying the exact `count(*)` fast path through the shared engine count short-circuit; D11") and the `Detail in plan/track-6.md.` pointer. The class/flag inventory stays in track-6.md, where it already is.
**Classification**: mechanical
**Justification**: The TRACK DESCRIPTIONS intro-length rule ("An intro that runs 4+ sentences … has expanded into territory that belongs in the track file"); the fix is a single unambiguous trim that moves no intent (the detail already exists in the track file), so it is `mechanical` per §`mechanical` (scope/format-class single-edit findings).

### S2 [suggestion]
**Location**: Plan file `implementation-plan.md`, the `**Strategy refresh:**` notes inside the completed Track 2 entry (line 370, "no scope, dependency, or ordering change to **Tracks 3–6**") and the completed Track 3 entry (line 405, "Scope, dependencies, and ordering for **Tracks 4–6** unchanged").
**Issue**: Both ranges name the pre-split downstream track count (six tracks total). The A1 split later added a track and renumbered, so the plan now carries seven tracks (Track 7 is the advanced + hardening track). Read as current forward-looking guidance, "Tracks 3–6" / "Tracks 4–6" no longer denote "all downstream tracks." A reader arriving at the resume protocol could momentarily mistake these for a live claim that Track 7 is out of scope for those refresh assessments.
**Proposed fix**: No mechanical range-widening. These are completion-boundary audit assessments recorded when the plan had six tracks; widening "Tracks 3–6" to "Tracks 3–7" would assert a broader "unchanged" claim across the split boundary that the A1 restructure made false (the split changed downstream scope, so the original "unchanged" conclusion cannot simply be extended to the new Track 7). If the user judges the notes worth touching, the correct edit is a one-clause annotation that these ranges are as-of-completion (pre-A1-split) assessments — not a range rewrite. Editing completed-track content is user-pause-gated. Leaving them as-is is defensible: they are explicitly labeled completion-boundary history and do not contradict any live track.
**Classification**: design-decision
**Justification**: Editing completed-track (`[x]`) content is user-pause-gated, and whether/how to annotate a superseded historical assessment is a planner judgment, not a clean mechanical stale-range sweep (§`design-decision` — the fix requires the user's rationale, and over-widening would introduce a false claim).

## Evidence base

No certificates — this review reads no codebase and produces plan-quality findings only.

Mechanical checks run (all clean unless noted in a finding above):
- Plan-file length: 499 lines — well under the ~1,500-line / ~30K-token budget.
- Dependency DAG acyclic and forward-ordered: every pending `**Depends on:**` points to a lower-numbered track (4→{3,1}, 5→{4,1}, 6→{4,5,1}, 7→{6}); no cycle, no forward reference.
- Track sizing (pending): T4 ~16, T5 ~16, T6 ~20, T7 ~20 — all inside the `~12–25` two-sided bound. Documented Track 4↔5 split (A1) passes.
- No `- [ ] Step:` items or `(provisional)` markers.
- No `(SUPERSEDED …)` / "see DN" DR blocks retained in any pending track's `## Decision Log`.
- Architecture-Notes `**Implemented in**` reassignments verified against the 7-track structure: D5→Track 5, D11→Track 6, D8→Track 7; D9 per-class entries "Tracks 2–7"; all other DRs unchanged and consistent.
- No phantom track references (no "Track 8"+). Every "Track N" reference in the plan and the four pending track files resolves to the correct renumbered track.
- Design gate yes: design.md exists (2113 lines), has an Overview, a `classDiagram`, a `sequenceDiagram`/flowcharts, and dedicated sections for the load-bearing topics (Aggregation barrier semantics, Parameter binding, Union semantics divergence, Schema polymorphism, Track 5 commitment, Boundary-step lifecycle). Its track-number labels are pre-split (e.g. logical filters tagged Track 4, projections Track 5, union/list-shaping Track 6) — expected: the design is frozen post-Phase-1 and reconciled in Phase 4, so these are not findings. The class diagram (~21 classes) and sequence diagram (~11 participants) exceed the soft ~12 / ~8 caps, but this predates the split, passed the prior gate, and is unactionable on a frozen doc — noted, not filed.
