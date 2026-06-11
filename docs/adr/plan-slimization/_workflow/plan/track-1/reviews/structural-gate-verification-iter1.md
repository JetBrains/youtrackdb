<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: S3, sev: suggestion, loc: "implementation-plan.md §D19 Rationale (lines ~392-396)", anchor: "### S3 ", cert: "", basis: "D19's 'never re-read by phase machinery' clause contradicts the resolved S2 read-site enumeration, and Track 1 step 1 copies the D19 rationale into staged §1.6(f)"}
verdicts:
  - {id: S1, verdict: VERIFIED}
  - {id: S2, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Verdicts

#### Verify S1: plan invariant S2's two-read-site rule contradicts the Phase-4 verdict fold
- **Original issue**: Plan invariant S2 enumerated exactly two log read
  sites, Track 1's acceptance bullet hardened that into a checked
  assertion, and Track 2 step 7 mandated a Phase-4 fold reading the log's
  resolved gate records (required by D10/D16/D17 and the design). The
  Track 2 implementer could not satisfy both, and the three restatements
  of the invariant disagreed on scope.
- **Fix applied**: The user-approved resolution scopes S2 to
  decision-content reads and names the Phase-4 fold as a sanctioned
  verdict-only read, mirrored across all four plan-side statements. The
  frozen `design.md` Part 2 sentence ("The log may be read in exactly two
  places", line 343) is deferred to Phase 4 reconciliation per the
  design-frozen routing rule.
- **Re-check**:
  - Plan / track file locations:
    - `implementation-plan.md` `#### Invariants`, S2 bullet (lines
      408-413): "The log is read for decision content only at Step 4a/4b
      authoring and by the Phase-2 consistency cross-check; the Phase-4
      fold reads the log's resolved gate verdicts, never decision
      content."
    - `plan/track-1.md` `## Plan of Work` ordering-constraints line
      (lines 187-190): "S2 (decision-content log reads stay exactly two;
      the Phase-4 verdict-only fold is sanctioned)".
    - `plan/track-1.md` `## Validation and Acceptance` (lines 228-231):
      "exactly two decision-content log read sites ... no staged text
      adds a third decision-read site. The Phase-4 fold's verdict-only
      read (Track 2) is the one sanctioned non-decision read."
    - `plan/track-2.md` `## Plan of Work` closing invariants line (lines
      147-151): "the Phase-2 consistency cross-check stays the log's only
      execution-side decision-content read; the Phase-4 fold reads
      resolved gate verdicts only, the sanctioned non-decision read".
  - Current state: the four statements use one consistent taxonomy
    (decision-content reads: two sites; verdict-only fold: sanctioned).
    Track 2 step 7's fold ("reads the log's resolved gate records before
    the `_workflow/` cleanup deletes them") now satisfies the invariant
    rather than falsifying it; "gate records" / "gate verdicts" /
    "gate-verdict records" name the same artifact across the plan, both
    track dependency sections, and design.md line 443.
  - Criteria met: the track-contradiction criterion is cleared; the plan,
    Track 1, and Track 2 no longer assert incompatible read-site rules,
    and the execution-side scoping qualifier that previously existed only
    in Track 2 is now carried by the plan-level invariant itself.
- **Regression check**: Checked every other plan statement about log
  reads: D5 ("Read scope is strictly bounded by S2" — still accurate),
  D10 ("folds the log's resolved gate verdicts" — matches), D16, D17
  (gate review files are a separate artifact, not log reads), D18 ("S2
  forbids Phase-3 reads" — still true, Phase-3 reads remain outside the
  sanctioned set), Track 1 and Track 2 `## Interfaces and Dependencies`
  (both describe the fold as consuming gate-verdict records — clean).
  One residual: D19's rationale clause "never re-read by phase machinery"
  conflicts with the reads S2 sanctions. The clause predates this fix
  (byte-identical at the commit iteration 1 reviewed) and was not
  introduced or relocated by it, so it is not a fix regression; raised
  separately as S3 (suggestion).
- **Verdict**: VERIFIED

#### Verify S2: Component Map missing two Track-1 files and the MECH intent bullet
- **Original issue**: `mid-phase-handoff.md` and `risk-tagging.md` were
  absent from the Component Map, and the MECH node
  (`design-mechanical-checks.py`) was the only diagram component without
  a "what changes and why" annotation bullet.
- **Fix applied**: The T1 subgraph gained an AUX node
  ("mid-phase-handoff.md / risk-tagging.md") and the annotated list
  gained three bullets: the D15 queue block, the D4 shared-source note,
  and D11's backward-compatible live-path edit plus the stub-plan
  fixture.
- **Re-check**:
  - Plan location: `implementation-plan.md` `#### Component Map` — AUX
    node at line 73; new bullets at lines 109-115.
  - Current state: all thirteen Track-1 in-scope files (per
    `plan/track-1.md` `## Interfaces and Dependencies`) now map to a
    diagram node or grouped bullet, the fixture included via the MECH
    bullet's `.claude/scripts/tests/` clause; every T1 component carries
    an intent bullet.
  - Criteria met: the map-vs-in-scope-list cross-check closes with no
    unaccounted file, and the diagram-component annotation criterion is
    satisfied for MECH.
- **Regression check**: Mermaid syntax stays valid (the AUX declaration
  is well-formed; an edge-free node inside a subgraph renders, matching
  the pre-existing RULES node). Bullet content cross-checked against D4,
  D15, D11, the plan Constraints, and track-1 steps 7, 11, and 12 — no
  drift introduced. Track 2's subgraph and bullets are untouched. Clean.
- **Verdict**: VERIFIED

## Findings

### S3 [suggestion]
**Location**: `implementation-plan.md` `#### D19` Rationale bullet (lines ~392-396)
**Issue**: D19's rationale claims the log is "consumed at authoring and
never re-read by phase machinery", but plan invariant S2, as resolved
this iteration, sanctions two post-authoring phase-machinery reads: the
Phase-2 consistency cross-check (decision content) and the Phase-4
verdict fold. The clause already sat in tension with the pre-fix S2
(which named the Phase-2 cross-check), and the approved resolution adds
the fold, so the plan now enumerates reads D19 says never happen. The
replay-immunity conclusion itself survives — the sanctioned reads consume
content, nothing rewrites or re-derives the file, and no §1.6(h) walk
enumerates it — but Track 1 step 1 directs the implementer to write the
staged §1.6(f) entry "with the append-only/replay-immune rationale
(D19)", giving the inaccurate clause a concrete propagation path into
staged conventions text.
**Proposed fix**: Reword the clause to rest on what holds, e.g. "the log
is an append-only ledger that no §1.6(h) walk enumerates and no phase
machinery rewrites or re-derives (its post-authoring reads, the S2
sites, consume content, never format), so it is replay-immune by
construction."
**Classification**: mechanical
**Justification**: §Classification rules — "a single unambiguous edit
that doesn't change plan intent". The authoritative side is already
settled by S1's user-approved S2 resolution, so no design call remains;
the reword direction is dictated by the settled invariant.

## Evidence base

No certificates: this review reads no codebase, per the
structural-review contract. Verdicts and the finding cite plan,
track-file, and design text directly, cross-checked against the
working-tree diff of the applied fixes.
