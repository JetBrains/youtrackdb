<!--MANIFEST
agent: review-workflow-consistency
dimension: workflow-consistency
prefix: WC
findings: 3
evidence_base:
  certs: 0
index:
  - id: WC1
    sev: Recommended
    anchor: "#wc1-recommended--design-reviewmd-step-4b-restatement-names-two-axes-not-three"
    loc: ".claude/workflow/prompts/design-review.md:247-248"
    cert: n/a
    basis: judgment
  - id: WC2
    sev: Recommended
    anchor: "#wc2-recommended--design-reviewmd-findings-prefix-list-omits-the-hard-to-read-axis"
    loc: ".claude/workflow/prompts/design-review.md:429-430"
    cert: n/a
    basis: judgment
  - id: WC3
    sev: Minor
    anchor: "#wc3-minor--design-reviewmd-applies-to-rationale-names-two-axes-not-three"
    loc: ".claude/workflow/prompts/design-review.md:193"
    cert: n/a
    basis: judgment
cert_index: []
flags:
  evidence_trail_exempt: true
  exempt_reason: "(a) no refutation or certificate phase to persist"
-->

## Findings

### WC1 [Recommended] — `design-review.md` Step-4b restatement names two axes, not three

- File: `.claude/workflow/prompts/design-review.md` (line 247-248)
- Axis: mermaid vs prose / mirrored-section sync (a block restated downstream of its own definition)
- Cost: the Step-4b track cold-read reviewer runs a two-axis scan and never reaches the new Plain-language lens; the operative instruction contradicts the block it points at.

Step 1 raised the `### Prose AI-tell additions` block from two axes to three: the lead-in now reads "**three axes**" (`:195`), a `**Hard-to-read**` bullet was added (`:211`), and the TOC row (`:23`), block summary comment (`:187`), and §Tone-and-depth second exception (`:465`) all name `Plain language`. But the §Track-scoped cold-read (Step 4b) section restates the same block at `:247-248` as "Run that block's over-dense **and too-terse** scan on the plan-at-start track sections" — naming only the two original axes. This restatement is the operative instruction for `target=tracks`, which is exactly where the three-axis block is meant to run (`:189-195` names `target=tracks` explicitly). A reviewer following `:247` runs the old two-axis scan; a reviewer following the block heading runs three. The Referent is the `### Prose AI-tell additions` block at `:186-217`, which the `:247` sentence claims to invoke verbatim ("Run that block's … scan") but now under-describes.

Suggestion: change "over-dense and too-terse scan" at `:247-248` to "over-dense, too-terse, and hard-to-read scan" (or "over-dense / too-terse / hard-to-read scan" to match the TOC and summary phrasing), so the operative Step-4b instruction matches the three-axis block it restates.

### WC2 [Recommended] — `design-review.md` findings-prefix list omits the hard-to-read axis

- File: `.claude/workflow/prompts/design-review.md` (line 429-430)
- Axis: mirrored-section sync (a findings-routing enumeration parallel to the block's axis set)
- Cost: a reviewer who flags a Plain-language finding has no matching finding prefix; the prefix list disagrees with the §Tone-and-depth exception at `:465`, which already names "the hard-to-read one."

The findings-routing parenthetical at `:428-432` tells the reviewer to prefix Prose-AI-tell findings `over-dense:` or `too-terse:` — e.g. `[should-fix] over-dense: <quoted sentence + the house-style § it breaks>`. After Step 1 added the third (hard-to-read / Plain language) axis to the block and named it in the §Tone-and-depth second exception (`:465`, "the over-dense sentence (or the too-terse assertion, or the hard-to-read one)"), this prefix list still offers only two prefixes. The Referent is the same `### Prose AI-tell additions` block whose axis set now has three members; the prefix enumeration is the routing complement of that set and drifted out of sync with it.

Suggestion: add a `hard-to-read:` prefix to the `over-dense:` / `too-terse:` list at `:429-430`, so a Plain-language finding has a routing prefix that matches the three-axis block and the `:465` exception.

### WC3 [Minor] — `design-review.md` applies-to rationale names two axes, not three

- File: `.claude/workflow/prompts/design-review.md` (line 193)
- Axis: mirrored-section sync (the block's own applies-to rationale, one sentence above its three-axis lead-in)
- Cost: cosmetic under-description; the rationale for why the block runs on `target=tracks` still says the track prose carries "the same over-dense / too-terse failures," omitting the hard-to-read axis the same paragraph then introduces.

The block's applies-to paragraph at `:189-195` motivates running on the Step-4b track cold-read because "plan-at-start track prose carries the same over-dense / too-terse failures as design prose" (`:193`), then immediately says to scan "on **three axes**" (`:195`). The rationale names two failure kinds while the instruction it justifies names three. This is the motivation sentence, not the scan instruction (so it is lower-impact than WC1), but it now reads inconsistently within its own paragraph. The Referent is the three-axis bullet set at `:197-216` that the rationale precedes.

Suggestion: extend the `:193` phrasing to "over-dense / too-terse / hard-to-read failures" (or "the same prose-clarity failures") so the rationale matches the three-axis scan it introduces.

## Evidence base
