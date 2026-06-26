<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 4, suggestion: 0}
index:
  - {id: S1, sev: should-fix, loc: "plan/track-4.md ## Decision Log #### D11", anchor: "### S1 ", cert: "", basis: "DR body ~45 lines, exceeds ~30-line soft cap; absorbed collation/session worked passages"}
  - {id: S2, sev: should-fix, loc: "plan/track-2.md ## Decision Log #### D17", anchor: "### S2 ", cert: "", basis: "DR body ~39 lines, exceeds ~30-line soft cap; absorbed dispatch-chain code block"}
  - {id: S3, sev: should-fix, loc: "plan/track-1.md ## Decision Log #### D8", anchor: "### S3 ", cert: "", basis: "DR body ~34 lines, exceeds ~30-line soft cap; absorbed depth-10 structural-sharing walk-through"}
  - {id: S4, sev: should-fix, loc: "plan/track-2.md ## Decision Log #### D5-R", anchor: "### S4 ", cert: "", basis: "DR body ~33 lines, marginally over the ~30-line soft cap; absorbed the 4-part promotion-engine list"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### S1 [should-fix]
**Location**: `plan/track-4.md`, `## Decision Log`, `#### D11: IR comparison evaluator replicates SQLBinaryCondition.evaluate`
**Issue**: The DR body runs ~45 lines, well over the ~30-line soft cap for a Decision Record. The four-bullet form (alternatives / rationale / risks / implemented-in) plus an optional `**Full design**` line is naturally a 10–20 line block. D11 absorbed long-form material into its `**Rationale**` bullet: two named worked sub-passages (`**Collation.**` and `**Session threading.**`) plus a third paragraph on the slow-path/fast-path parity equivalence. That is mechanism-walkthrough prose, not decision rationale, and it duplicates `design.md §"Comparison: replicate the AST sequence"` (the frozen seed already carries the full collation + session-threading + slow-path derivation).
**Proposed fix**: Trim D11 back toward the four-bullet form. Keep a one- or two-sentence rationale naming the two nuances (collation and the EQ/NE session difference) and the conclusion that delegating to the parser-operator instance reproduces both by construction. Move the `**Collation.**` / `**Session threading.**` / slow-path worked passages into a separate prose passage inside the same track's `## Decision Log` (e.g. directly under the DR, outside the four bullets) or rely on the existing `**Full design**` pointer to the frozen seed's mechanism section, which already holds this content verbatim. `design.md` is frozen; this fix edits only the track DR, so no design edit is needed.
**Classification**: mechanical
**Justification**: §`mechanical` "DR length … — `mechanical` (long-form material moves to the matching track section)"; Bloat-check **DR-length**, should-fix, body exceeds ~30 lines.

### S2 [should-fix]
**Location**: `plan/track-2.md`, `## Decision Log`, `#### D17: The extraction touches the live AST arithmetic hot path`
**Issue**: The DR body runs ~39 lines, over the ~30-line soft cap. The overflow is a fenced `text` code block embedded in the `**Rationale**` bullet drawing the four-line `operator.apply → apply(Object,Object) → apply(Number,Operator,Number) → operation.apply` dispatch chain, plus the paragraph that labels the "two-hop" virtual dispatches. This is a layered-diagram / mechanism walkthrough — the kind of long-form material the bloat rule names — and it duplicates the dispatch-chain diagram in `design.md §"NumericOps: one shared promotion engine"` (Edge cases / Gotchas).
**Proposed fix**: Trim D17 to the four-bullet form. Keep the rationale claim (perf-neutrality rests on leaving the existing two-hop virtual dispatch intact; the lift-and-shift adds only a monomorphic, inlinable static delegation). Move the fenced dispatch-chain diagram and the two-hop-label paragraph into a separate prose passage in the same track's `## Decision Log`, or drop it in favor of the `**Full design**` pointer to the frozen seed's mechanism section. The same dispatch-chain text already appears in track-2's `## Context and Orientation` discussion, so this is also partly de-duplication within the track. No design edit needed (seed is frozen).
**Classification**: mechanical
**Justification**: §`mechanical` "DR length … — `mechanical`"; Bloat-check **DR-length**, should-fix, body exceeds ~30 lines (layered diagram / worked walkthrough moves to the matching track section).

### S3 [should-fix]
**Location**: `plan/track-1.md`, `## Decision Log`, `#### D8: AnalyzedExprTransform with structural sharing`
**Issue**: The DR body runs ~34 lines, over the ~30-line soft cap. The `**Rationale**` bullet carries a full worked example — the one-ancestor-at-a-time walk of a fold firing at depth 10 of a 50-node tree, tracing the rebuild up the parent chain and the by-reference sharing of the ~40 off-path subtrees. That worked example is the long-form material the DR-length rule calls out; it duplicates the equivalent worked example in `design.md §"Transform passes and structural sharing"`.
**Proposed fix**: Trim D8 to the four-bullet form. Keep a one- or two-sentence rationale stating the rule (recurse one level; return the input node when every child returns by reference, build a new parent only when a child changed; reference identity, not value equality) and why it matters (a deep rewrite allocates only the path-to-root nodes, sharing untouched subtrees). Move the depth-10 walk-through into a separate prose passage in the same track's `## Decision Log`, or rely on the `**Full design**` pointer to the frozen seed. No design edit needed.
**Classification**: mechanical
**Justification**: §`mechanical` "DR length … — `mechanical`"; Bloat-check **DR-length**, should-fix, body exceeds ~30 lines (worked example moves to the matching track section).

### S4 [should-fix]
**Location**: `plan/track-2.md`, `## Decision Log`, `#### D5-R: NumericOps extraction is a whole-enum lift-and-shift`
**Issue**: The DR body runs ~33 lines, marginally over the ~30-line soft cap. The overflow is the four-part numbered enumeration of the shared promotion engine (typed-pair overloads / fallback / shared widening entry / `toLong`) embedded in the `**Rationale**` bullet. The same four-part list is already stated in track-2's `## Context and Orientation` and in `design.md §"NumericOps: one shared promotion engine"`, so the DR copy is duplicative long-form material.
**Proposed fix**: Trim D5-R toward the four-bullet form. Keep the rationale claim (whole-enum lift-and-shift gives a clean single home with no straddling shared-widening seam, and the existing math-test suite is the self-verifying gate) and replace the inlined four-part engine enumeration with a one-line reference to it (the engine's parts are inventoried in `## Context and Orientation`). This is a soft-cap marginal case (~33 vs ~30); the orchestrator may leave it if the trim would cost clarity, but the duplication of the engine-parts list is the cheap removal.
**Classification**: mechanical
**Justification**: §`mechanical` "DR length … — `mechanical`"; Bloat-check **DR-length**, should-fix, body exceeds ~30 lines (duplicated enumeration moves to / references the matching track section).

## Evidence base

This review reads no codebase and produces no certificates (`certs: 0`). All four findings are mechanical line-count matches against the ~30-line DR-length soft cap (measured: D11 ~45, D17 ~39, D8 ~34, D5-R ~33 lines, counting from each `#### D<N>:` heading through its final bullet, excluding trailing blanks and the next heading). DRs at or within the soft tolerance were not flagged (D6-R ~30, track-4 D15 ~31, track-3 D12 ~29). No ordering, dependency, sizing, contradiction, missing-DR, decision-traceability, design-document-structure, superseded-DR, or plan-file-budget defects were found: track ordering is acyclic (T1, T2 independent; T3→T1; T4→T1,T2,T3 — no earlier-on-later dependency); no non-consecutive tracks share in-scope files; all four sizing justifications are present and written (the gate the under-floor tracks owe); the Component Map, all intent bullets (≤5 lines), all checklist intro paragraphs (≤3 sentences), and the design document (Overview, 11-class classDiagram, flowchart + 6-participant sequenceDiagram, all Mermaid, each prose-paired, NumericOps hot-path dedicated section) are complete; the design-seed D-records all have substantively-equivalent track DRs (seed↔track fidelity clean); and the D5/D5-R partial-supersession and the D6-R "one logical decision carried in two tracks" relationship are both documented in-record, not retained-superseded blocks.
