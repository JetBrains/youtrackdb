<!--MANIFEST
dimension: workflow-prompt-design
prefix: WP
iteration: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WP1
    sev: Recommended
    anchor: "#wp1-recommended"
    loc: ".claude/workflow/prompts/design-review.md:404-417"
    cert: n/a
    basis: judgment
  - id: WP2
    sev: Minor
    anchor: "#wp2-minor"
    loc: ".claude/workflow/prompts/design-review.md:197-204"
    cert: n/a
    basis: judgment
-->

## Findings

### WP1 [Recommended] Prose AI-tell findings have no documented slot in the output template

- File: `.claude/workflow/prompts/design-review.md` (lines 404-417)
- Axis: output contract
- Cost: the reviewer is told to gather quote+rule evidence for Prose AI-tell findings (the second Tone exception, line 447) but the output template never says where those findings land or how to shape them, so a reviewer following the template literally emits a bare one-line bullet and drops the required evidence.
- Issue: the `## Structural findings` parenthetical (lines 412-417) routes only **Human-reader** findings into the list ("findings under the Human-reader cold-read additions go in the same list but prefix each with the dimension label and use multi-line bullets to fit the evidence required by the Tone exception"). The new `### Prose AI-tell additions` block (lines 186-215) adds a second class of finding that (a) feeds the same `## Structural findings` list, (b) carries its own Tone-exception evidence requirement (line 447: quote the over-dense sentence or too-terse assertion plus name the house-style rule), and (c) is the **only** finding class that runs on `target=tracks` — yet the parenthetical names only `phase1-creation`, `phase4-creation`, `design-sync` and never mentions Prose AI-tell findings or `target=tracks`. A fresh reviewer reading the output template sees the multi-line dimension-labelled evidence shape gated to the Human-reader dimensions and the default bullet shape `- [<severity>] <description>` for everything else, so the over-dense/too-terse evidence the prose elsewhere demands has no template home and is silently dropped. The instruction-to-emit and the place-to-emit are split across two sections that disagree.
- Suggestion: extend the parenthetical at lines 412-417 to cover the Prose AI-tell additions too, with a worked example mirroring the Human-reader one — e.g. add a sentence: "Findings under the Prose AI-tell additions (all three design kinds **and** `target=tracks`) go in the same list with the same multi-line shape, prefixed `over-dense:` or `too-terse:` — e.g. `[should-fix] over-dense: <quoted sentence + the house-style § it breaks>`." Naming `target=tracks` explicitly closes the gap that the current parenthetical's design-kind-only enumeration leaves open.

### WP2 [Minor] Over-dense bullet calls inflated-abstraction labels a case "the regex set cannot catch," but Step 2 made it regex-detectable

- File: `.claude/workflow/prompts/design-review.md` (lines 197-204)
- Axis: deterministic decision rules
- Cost: a reviewer reading "the judgment cases the `dsc-ai-tell` regex set cannot catch" and then seeing inflated-abstraction labels listed as such a case gets a mildly contradictory division-of-labor signal, since the same track's Step 2 added a regex for exactly that pattern.
- Issue: the over-dense bullet opens "the judgment cases the `dsc-ai-tell` regex set cannot catch" and then lists "inflated-abstraction labels (a subject-slot 'the enabling primitive' …)" as an example. After Step 2, `INFLATED_ABSTRACTION_LABEL_RE` mechanically catches the subject-slot form against a curated closed inflation set. The intended (and correct) division of labor is that the reviewer's judgment catches the inflated labels the closed-set regex misses, but the bullet's framing reads as if the regex catches none of them. This is a clarity wrinkle, not a behavior bug: the reviewer will still flag a judgment-level inflated label, which is what is wanted.
- Suggestion: tighten the framing to name the residual the regex leaves, e.g. "inflated-abstraction labels the closed-set regex misses (a subject-slot 'the enabling primitive' / 'the key abstraction' built from an inflation word not in the regex's curated set, or in a non-subject slot)." This makes the judgment/regex boundary explicit rather than implying the regex catches no inflated labels.

## Evidence base
