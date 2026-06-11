<!-- MANIFEST
dimension: workflow-consistency
prefix: WC
findings_total: 1
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - id: WC1
    sev: Minor
    anchor: "#wc1-minor-workflowmd-phase-overview-bullet"
    loc: ".claude/workflow/workflow.md:86"
    cert: n/a
    basis: judgment
-->

## Findings

### WC1 [Minor] workflow.md phase-overview bullet contradicts the new per-tier Final Artifacts table

- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/workflow.md` (line 86)
- **Axis:** cross-file rule restatement (intra-file summary-vs-source drift)
- **Cost:** a reader of the Phase-overview orientation bullet gets a now-wrong summary — it states Phase 4 always produces both durable artifacts, which the tier rewrite this track landed has made false for two of three tiers.
- **Issue:** The §Phases overview bullet reads `Phase 4 (Final Artifacts): /execute-tracks (State D) — produce design-final.md and adr.md`. Step 1 of this track rewrote the authoritative §Final Artifacts section (lines 605-646) to a per-tier matrix where `lite` produces `adr.md` only and `minimal` produces neither (a two-line PR-description fold, no `docs/adr/<dir>/` entry). The orientation bullet still asserts the unconditional `design-final.md` + `adr.md` output and now contradicts its own file's authority section. Referent: the per-tier table at `workflow.md:611-615` (and the matching `prompts/create-final-design.md:84-88` table), against which line 86 resolves as stale.
- **Scope note:** Line 86 is byte-identical to develop (`sed -n '86p'` on both the staged copy and the live file match) and lies outside the cited delta hunks (`22c22`, `605-646`), so it is verbatim-copied content this track did not edit. The contradiction is nonetheless **created by** this track's authority-section rewrite, and the bullet is in the same staged file the track owns and modified. Flagged Minor because the authoritative §Final Artifacts section is correct and unambiguous; only the orientation summary drifted.
- **Suggestion:** When the staged `workflow.md` is next touched (or at the Phase-4 §1.7(f) promotion reconciliation), reword the bullet to be tier-neutral, e.g. `produce the per-tier durable artifacts (full: design-final.md + adr.md; lite: adr.md; minimal: PR-description fold) — follows prompts/create-final-design.md`, so the overview no longer overstates the `minimal`/`lite` output.

## Evidence base
