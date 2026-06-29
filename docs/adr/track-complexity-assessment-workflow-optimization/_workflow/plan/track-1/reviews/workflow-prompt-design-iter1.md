<!--MANIFEST
dimension: workflow-prompt-design
step: track-1-step-2
target_commit: cfa81a9b2e40d048d5d85af2a0cd419c86834c3e
output_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan/track-1/reviews/workflow-prompt-design-iter1.md
verdict: PASS
finding_count: 1
evidence_base:
  certs: 0
cert_index: []
flags:
  evidence_trail_exempt: true
  exempt_reason: "(a) no refutation or certificate phase to persist"
index:
  - id: WP1
    sev: Minor
    anchor: "### WP1 [Minor] Step-4b seed bash block uses prose-token placeholders for two distinct value sources"
    loc: "staged create-plan/SKILL.md:1285-1288"
    cert: n/a
    basis: judgment
-->

## Findings

### WP1 [Minor] Step-4b seed bash block uses prose-token placeholders for two distinct value sources

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 1285-1288)
- **Axis:** deterministic decision rules
- **Cost:** mild — an LLM substituting the seed call could in principle emit a token outside the script's accepted set; the precheck's loud-reject (`exit 3`) catches it, so the blast radius is a noisy failure, not silent corruption.
- **Issue:** The seed block writes `--design-gate "<yes | no>"` and `--tracks "<number of track files authored>"` as literal prose-token placeholders. `--design-gate` has a tightly enumerated source (the Step-4 part-1 classifier's confirmed `design_gate=yes/no`, which the explanatory paragraph below at line 1294 names correctly), but the inline placeholder leaves the LLM to re-derive the legal value set from the angle-bracket text rather than pointing back at the already-confirmed value. This is the same idiom the prior `--tier "<full | lite | minimal>"` line used, so it is consistent with the file's established style and not a regression — but the two fields differ in kind (`--design-gate` is a confirmed-earlier enum, `--tracks` is a count computed at this very point), and the placeholder text does not signal that difference.
- **Suggestion:** Optional. Tie each placeholder to its source in the placeholder itself, e.g. `--design-gate "<the design_gate value confirmed in Step 4 part 1>"` and `--tracks "<count of plan/track-*.md files just authored>"`, so the substitution rule lives at the call site rather than only in the paragraph below. The explanatory paragraph at lines 1294-1300 already carries this mapping, so this is polish, not a correctness fix.

## Evidence base
