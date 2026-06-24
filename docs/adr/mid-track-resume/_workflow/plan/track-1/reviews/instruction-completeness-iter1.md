<!--MANIFEST
dimension: workflow-instruction-completeness
target: track-1
range: b1621946c39bfb2b24d4d97bd29b60203ff65981..HEAD
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: WI1, sev: Recommended, loc: "workflow-startup-precheck.sh:1978", anchor: "### WI1 ", cert: C1, basis: "ledger-first read emits substate + returns 0 without a track-file presence check; the phase==C consumer assumes a track file and reads it for every substate action"}
  - {id: WI2, sev: Recommended, loc: "workflow-startup-precheck.sh:1981", anchor: "### WI2 ", cert: C2, basis: "no enum-membership guard on the substate read path; an out-of-enum slug emits 1:1 into STATE_JSON and workflow.md step 5 has no otherwise-row to catch it"}
  - {id: WI3, sev: Minor, loc: "design.md:317", anchor: "### WI3 ", cert: C3, basis: "design resolution-flow diagram checks track-file presence before the substate read; the implementation reads substate first regardless — a frozen-design divergence already logged for Phase 4"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
summary: >-
  Three suggestion-level completeness gaps, all at the read-side handshake to the
  workflow.md step-5 consumer (out-of-scope for editing on this track, so each is a
  flag for the consuming track / Phase 4, not a blocker here). The dual-path
  resolution itself is complete: the empty-substate fallback fires correctly, the
  fallback retains failed-step/section-discrepancy, the wrap-fix flushes at all
  three terminators (fence, heading, EOF), and the --substate append rejects the
  bare-token violations. The gaps are (WI1) the ledger path resolves a substate
  even with the track file absent while the step-5 consumer assumes a present
  track file, (WI2) no enum guard on the read so an out-of-enum slug reaches a
  step-5 table with no otherwise-row, (WI3) a frozen design.md diagram orders the
  track-file check before the substate read, opposite the implementation.
-->

## Findings

### WI1 [Recommended] ledger-first read resolves a substate with no track-file presence check, but the step-5 consumer assumes the track file is present

- **Axis:** phase output → next-phase input.
- **Cost:** on a ledger that carries a `substate` for a track whose file is absent, the precheck routes to `phase==C` + a substate whose documented resume action then has no track file to read — an undefined consumer state.
- **Issue.** The ledger-first arm in `determine_state_from_ledger` (`workflow-startup-precheck.sh:1978-1983`) reads the track-scoped `substate`, and when it is non-empty emits `{phase:"C", substate:<slug>}` and `return 0` **without checking the track file exists**. The comment at line 1975 states this is deliberate: "a non-empty `substate` resolves even when the track file is absent or unreadable." The empty-substate fallback below it (line 1988-1997) *does* guard track-file presence and emits `{phase:"A"}` when absent — so the two arms disagree on the track-file-absent case. The consumer, `workflow.md` step 5 `phase == "C"` arm (`workflow.md:339-340`), is documented as "mid-track resume; the first `[ ]` track **has a track file**," and every row of its substate routing table reads that file (`steps-partial` → "Resume from the next `[ ]` step"; `section-discrepancy` → "reconcile the missing Progress entry from the `## Episodes` block"; `review-done-track-open` → "compile the episode"). The ledger path can now hand the consumer a `phase==C` + substate verdict with no backing track file, a combination the consumer's prose does not cover. `test_ledger_substate_resolves_without_track_file` pins this precheck behavior as intentional, so the gap is the *consumer contract*, not the precheck.
- **Suggestion.** This is a flag for the consuming track (workflow.md step 5 is out-of-scope on Track 1; Track 2 / Phase 4 owns the routing prose). Either add a track-file presence guard to the ledger arm symmetric with the fallback (emit `phase=A` when the track file is absent even on a non-empty substate), or add one sentence to the step-5 `phase == "C"` arm covering the ledger-resolved-but-track-file-absent case (e.g. re-derive the track file or fall to State A). Surface it in the track's `## Surprises & Discoveries` as a Track-2/Phase-4 reconciliation item.

### WI2 [Recommended] no enum-membership guard on the substate read, and workflow.md step 5 has no otherwise-row for an unrecognized slug

- **Axis:** conditional branch coverage.
- **Cost:** an out-of-enum substate slug (an append typo, or `failed-step`/`section-discrepancy` smuggled onto the ledger) emits 1:1 into `STATE_JSON` and reaches a step-5 routing table with no fallthrough, leaving the orchestrator with an unmatched decision-table row.
- **Issue.** Neither path constrains the substate to the four committed slugs. The append validation (`reject_bad_ledger_value "substate" … bare`, line 1695) rejects only spaces and newlines — a bare-but-wrong token like `steps-done` or `failed-step` passes. The read path (line 1981) emits the ledger value directly: `jq -nc --arg s "$substate" '{phase:"C", substate:$s}'`, with no enum check. Contrast the sibling `phase` read, which calls `parse_error` on an unrecognized phase token (line 2004) so a corrupt ledger fails loudly. The substate read has no analogous loud-reject. Downstream, `workflow.md` step 5's substate table (`workflow.md:342-349`) is a closed 6-row table with **no "otherwise / unrecognized slug" row** — an out-of-enum slug matches nothing, an undefined routing outcome. The script's own design posture elsewhere is "the enum stays closed" (header comment line ~1059) and loud-reject on malformed input; the substate read is the one resume-critical value that trusts its input unchecked.
- **Suggestion.** Flag for the consuming track. Lowest-cost option: validate `--substate` against the four committed slugs on append (a `case` arm beside the bare-token check), so a wrong slug never reaches the ledger. Defense-in-depth option: have the read arm `parse_error` when the ledger substate is not one of the four committed slugs, mirroring the `phase` guard at line 2004. Independently, add an explicit otherwise-row to the step-5 table. The append-side validation is partly Track 2's contract; the missing step-5 fallthrough is a standalone completeness gap.

### WI3 [Minor] frozen design.md resolution-flow diagram orders the track-file check before the substate read, opposite the implementation

- **Axis:** phase output → next-phase input.
- **Cost:** documentation/implementation divergence in the resolution order; no runtime effect, but a Phase-4 reader reconciling `design-final.md` against the code would find the flow inverted.
- **Issue.** The frozen `design.md` resolution-flow diagram (`design.md:314-325`) checks `{track file present?}` **before** reading the substate, and emits `phase=A, pre-decomposition` when the file is absent — i.e. the track-file guard gates the substate read. The implementation reverses this: it reads the track-scoped substate first (line 1978) and resolves a non-empty value regardless of track-file presence; only the empty-substate fallback consults the track file. This is the structural counterpart of WI1: the design's diagram would never reach the track-file-absent-but-substate-present state that WI1 flags, because its guard runs first. The track file's own Surprises log already records a frozen-design reconciliation item (the wrap-fix terminator shorthand), and the track-file `## Context and Orientation` diagram matches the implementation, so this is a known design-vs-implementation gap to settle at Phase 4, not an execution defect.
- **Suggestion.** Add this diagram-ordering divergence to the existing Phase-4 reconciliation note in `## Surprises & Discoveries` so `design-final.md` records the implemented order (substate read first, track-file guard only on the empty-substate fallback).

## Evidence base

#### C1 CONFIRMED — ledger arm returns without a track-file presence check; consumer assumes presence
Read `workflow-startup-precheck.sh:1966-1998`: the `substate` non-empty branch (1980-1983) emits `STATE_JSON` and `return 0` with no `[ -f "$track_file" ]` test; that test appears only in the empty-substate fallback (1989). Comment 1975 confirms the intent ("resolves even when the track file is absent or unreadable"). Consumer side `workflow.md:339-349`: the `phase == "C"` arm text asserts "the first `[ ]` track has a track file" and every substate row's resume action reads the track file. `test_ledger_substate_resolves_without_track_file` (delta lines 123-156) deliberately asserts resolution with `track-2.md` absent — confirming the precheck behavior is intentional and the gap is the consumer contract.

#### C2 CONFIRMED — substate value unconstrained to the committed enum on both paths; step-5 table has no otherwise-row
Append side: `reject_bad_ledger_value "substate" "$LEDGER_SUBSTATE" bare` (line 1695) plus the body at 1653-1679 reject only newline (any field) and space (bare fields) — no slug-set membership check. Read side: line 1981 emits `--arg s "$substate"` straight into `STATE_JSON` with no enum guard, unlike the `phase` arm's `parse_error` at line 2004. Consumer: `workflow.md:342-349` is a 6-row table (`decomposition-pending`, `section-discrepancy`, `failed-step`, `steps-partial`, `steps-done-review-pending`, `review-done-track-open`) with no trailing otherwise/default row. `_COMMITTED_SUBSTATES` in the test (delta 64-69) lists only the four ledger-path slugs, confirming `failed-step`/`section-discrepancy` are never legitimate ledger values yet nothing rejects them if written.

#### C3 CONFIRMED — design.md diagram orders track-file check before substate read; implementation reverses it
`design.md:314-325` Mermaid flow: `{track file present?}` → no → `emit phase=A`; yes → `read track-scoped substate`. Implementation (`workflow-startup-precheck.sh:1978-1998`) reads substate first (1978), resolves non-empty regardless of track file, and only the empty-substate branch tests `[ -f "$track_file" ]` (1989). The track-file `## Context and Orientation` Mermaid (track-1.md:157-167) matches the implementation order. The track already carries a frozen-design Phase-4 reconciliation note (`## Surprises & Discoveries`, track-1.md:34-40) for the wrap-fix shorthand, establishing Phase 4 as the settle point for design-vs-code divergences.
