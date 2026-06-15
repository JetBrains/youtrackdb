<!--
MANIFEST
dimension: workflow-consistency
iteration: 1
track: 1
range: 6c2e0b5f68b12599aacbcce8b608f5c1489a3159..HEAD
findings: 4
evidence_base: { certs: 0 }
cert_index: []
flags: [evidence-trail-exempt]
index:
  - id: WC1
    sev: Recommended
    anchor: "### WC1"
    loc: ".claude/workflow/conventions.md:9 (staged)"
    cert: n/a
    basis: judgment
  - id: WC2
    sev: Recommended
    anchor: "### WC2"
    loc: ".claude/workflow/workflow.md:726-728 (staged)"
    cert: n/a
    basis: judgment
  - id: WC3
    sev: Minor
    anchor: "### WC3"
    loc: ".claude/workflow/workflow.md:78,117-121 (staged)"
    cert: n/a
    basis: judgment
  - id: WC4
    sev: Minor
    anchor: "### WC4"
    loc: ".claude/workflow/workflow.md:10 (staged)"
    cert: n/a
    basis: judgment
-->

## Findings

### WC1 [Recommended] §Per-tier artifact set TOC summary still says "plan is universal"

- **File:** `.claude/workflow/conventions.md` (staged), line 9 (Document-index row)
- **Axis:** cross-file rule restatement (TOC summary vs section body)
- **Cost:** summary-vs-source drift inside a Track-1-edited file — a reader who trusts the index row gets the pre-change model.
- **Issue:** The Document-index row for `§Per-tier artifact set` reads
  *"Which _workflow artifacts each change tier produces; **log and plan are universal**, the design is full-tier only."* The Referent is the section body the row summarizes (staged `conventions.md` `### Per-tier artifact set`, lines 226-270, and its per-tier matrix). The body was rewritten in this track to the derived-mirror model: the **plan is no longer universal** — it is `lite`/`full` only and dropped in `minimal` (D2) — while the now-universal artifacts are the **phase ledger** and the **plan-review document** (the matrix rows added at lines 236-241 mark `implementation-plan.md` as "— (dropped)" in `minimal`, and add `phase-ledger.md` / `plan-review.md` as "yes" in every tier). The row's claim "plan is universal" directly contradicts the body the same edit produced.
- **Suggestion:** Update the row summary to match the rewritten body, e.g. *"Which _workflow artifacts each change tier produces; the research log, phase ledger, and plan-review doc are universal, the plan is lite/full only, the design is full-tier only."*

### WC2 [Recommended] Stale internal cross-reference to the removed `## Final Artifacts` plan section

- **File:** `.claude/workflow/workflow.md` (staged), lines 726-728
- **Axis:** cross-file rule restatement (stale internal "see … above" pointer)
- **Cost:** broken internal reference introduced by this track's Startup-Protocol rewrite; the pointer resolves to a section that no longer holds what it claims.
- **Issue:** The Phase-4 prose reads *"Tracked in the `## Final Artifacts` section of `implementation-plan.md` (see State D markers in the Startup Protocol table above)."* The Referent is twofold and both halves are now stale: (a) the `## Final Artifacts` section of `implementation-plan.md` was removed under D5/D7 (the staged `conventions.md` §Plan file content, line 316, states *"`## Final Artifacts` is removed"*, and the thinned-plan schematic no longer emits it); (b) "see State D markers in the Startup Protocol table above" points at the Startup Protocol section that THIS track rewrote — its `phase == "D"` branch (staged `workflow.md` line ~336) now reads *"The plan `## Final Artifacts` checkbox is gone — D7; the phase comes from the ledger."* So the cross-reference tells the reader to consult a table for State-D markers that the same file's rewrite deleted.
- **Suggestion:** Re-point this sentence at the ledger / `plan-review.md` Phase-4 carrier (per D7), or at minimum drop the "`## Final Artifacts` section of `implementation-plan.md`" clause and the "State D markers … table" pointer. If the author intends the surrounding Final-Artifacts narrative to be Track 2's rewire, add an explicit Track-2 deferral note here the way the rewritten Startup Protocol section does, so the stale pointer is not left silently contradicting the section above it.

### WC3 [Minor] Phase-2 overview bullet and Session-Lifecycle Mermaid still describe the plan-checkbox State-0 model

- **File:** `.claude/workflow/workflow.md` (staged), line 78 (Phase-2 overview bullet) and lines 117 / 121 (Session Lifecycle Mermaid node + edge labels)
- **Axis:** mermaid vs prose (and overview vs rewritten spec) within one Track-1-edited file
- **Cost:** intra-file contradiction — the diagram and overview describe the removed checkbox model while the rewritten Startup Protocol in the same file says that checkbox is gone.
- **Issue:** Line 78 describes State 0 as *"plan file's `## Plan Review` checklist entry is `[ ]`"*; the Mermaid `READ` node (line 117) is labeled *"Read plan file + track file, Identify state"* and its State-0 edge (line 121) is *"Plan review not yet done (## Plan Review is [ ])"*. The Referent is the rewritten Startup Protocol (lines 192-340), whose `phase == "0"` branch now reads *"The verdict is recorded in `plan-review.md` and a ledger phase entry, not a `## Plan Review` plan checkbox (D7)"* and whose step 1 reads *"The script computes `state` from the phase ledger tail (D3), not from plan checkboxes."* The diagram/overview and the spec now disagree on what drives State 0. This most plausibly falls in Track 2's consumer-rewire scope (the plan Component Map assigns the runtime resume-routing re-point to Track 2, and the rewritten Startup Protocol leaves explicit "Track 2 re-points…" notes) — but unlike those passages these carry no deferral note, so a reader of workflow.md hits an unflagged contradiction.
- **Suggestion:** Either re-label the Mermaid `READ` node / State-0 edge and line-78 bullet to read off the phase ledger, or add the same explicit "Track 2 re-points this" deferral annotation the rewritten Startup Protocol uses, so the un-rewritten diagram is not silently inconsistent with the spec beside it. Confirm with the author which track owns these workflow.md passages.

### WC4 [Minor] §Startup Protocol TOC summary still says "resume from the plan"

- **File:** `.claude/workflow/workflow.md` (staged), line 10 (Document-index row)
- **Axis:** cross-file rule restatement (TOC summary vs rewritten section body)
- **Cost:** cosmetic summary drift; the index row names the wrong resume source.
- **Issue:** The Document-index row for `§Startup Protocol (Auto-Resume)` summarizes it as *"State detection at startup: where to resume **from the plan and track files**."* The Referent is the rewritten section body (lines 192-340), which now derives `state` from the **phase ledger** tail (top-level phase + active track) and the track file's `## Progress` (within-track sub-state) — the plan is read only for `lite`/`full` cross-track orientation, not for state detection. The summary still names the plan as the state source.
- **Suggestion:** Update the row to *"State detection at startup: where to resume — from the phase ledger and track files (lite/full also read the plan for cross-track orientation)."*

## Evidence base

<!-- This dimension is evidence-trail-exempt (no refutation or certificate phase to persist). No #### C<n> certificate entries; manifest evidence_base is certs: 0. -->
