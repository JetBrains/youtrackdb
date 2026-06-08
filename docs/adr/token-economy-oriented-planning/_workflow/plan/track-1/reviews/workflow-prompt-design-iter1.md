<!--MANIFEST
dimension: workflow-prompt-design
iteration: 1
target: .claude/workflow/prompts/structural-review.md (staged copy)
range: 98c5dd4719..HEAD
findings: 3
verdict: PASS-WITH-SUGGESTIONS
index:
  - id: WP1
    sev: should-fix
    anchor: "### WP1 [should-fix]"
    loc: "prompts/structural-review.md staged lines 213-222 (new bullet) + 426-461 (design-decision triage)"
    cert: n/a
    basis: judgment
  - id: WP2
    sev: suggestion
    anchor: "### WP2 [suggestion]"
    loc: "prompts/structural-review.md staged lines 213-222 (new bullet)"
    cert: n/a
    basis: judgment
  - id: WP3
    sev: suggestion
    anchor: "### WP3 [suggestion]"
    loc: "prompts/structural-review.md staged lines 194-222 (new bullet vs first TRACK SIZING bullet)"
    cert: n/a
    basis: judgment
evidence_base:
  certs: 0
cert_index: []
flags: []
-->

## Findings

### WP1 [should-fix]
**File:** `.claude/workflow/prompts/structural-review.md` (staged lines 213-222, with the §`design-decision` triage at 426-461)
**Axis:** output contract / deterministic decision rules
**Cost:** the sub-agent cannot fill a mandatory output field for the new finding type, producing an unciteable or inconsistently-cited classification

**Issue:** The new bullet directs the reviewer to emit a `design-decision` finding "the same class and severity as an undocumented out-of-bounds track" for an undocumented non-adjacent overlap-split. The output format (lines 372-379) makes `**Classification**` and `**Justification**` mandatory, where `**Justification**` is "one-line citation of the rule from §Classification rules below." But the §`design-decision` triage (lines 429-461) was not extended: its **Track sizing** trigger (lines 435-441) enumerates only the three footprint/maximization cases — over the ceiling, under the floor and folding, or stopped below the ceiling with a mergeable unit unpacked. The non-adjacent overlap-split is none of those, so there is no rule in §Classification rules for the reviewer to cite. A sub-agent hitting this case must either invent a citation, mis-cite the footprint Track-sizing trigger, or stall on the field — non-reproducible output. The producer-side `planning.md` text confirms the intent ("the overlap-split criterion under that review's TRACK SIZING checks"), so the triage list, not the new bullet, is the gap. The companion footprint bullet handles this correctly: it ends with an explicit `(see the design-decision triage below)` pointer (line 211); the new bullet has neither the pointer nor a matching triage entry.

**Suggestion:** Add a fourth case to the **Track sizing** trigger in §`design-decision` (or a sibling bullet under "ANY of these triggers `design-decision`"), e.g. "an undocumented non-adjacent overlap-split — overlapping in-scope files scattered across non-adjacent tracks with no written reason; where the cut goes and whether co-location is feasible are planner judgments, so the user picks." Then append `(see the design-decision triage below)` to the new TRACK SIZING bullet so the reviewer has a citation target for the mandatory `**Justification**` field.

### WP2 [suggestion]
**File:** `.claude/workflow/prompts/structural-review.md` (staged lines 213-222)
**Axis:** deterministic decision rules
**Cost:** the fire predicate keys on an undefined term, so two reviewers may compute different fire sets on the same plan

**Issue:** The whole check fires on "two **non-adjacent** tracks," but the prompt never defines adjacency for the plan the sub-agent is reading. The sub-agent's Inputs list `planning.md` only as `{workflow_path}` procedural guidance and the bullet points at no definition, so the reviewer cannot rely on the producer-side `planning.md` adjacency prose or the track file's terminology section. "Non-adjacent" admits two readings — "not consecutive in the plan's track ordering" (Track 1 vs Track 3) versus "not joined by a dependency edge" — which select different fire sets. The natural reading (consecutive track numbers) is strongly implied by the surrounding ordering language, so the blast radius is a missed-or-spurious advisory finding, not a broken plan; hence suggestion, not should-fix.

**Suggestion:** Pin the reading inline, e.g. "two tracks that are not consecutive in the plan's track ordering" — one clause removes the ambiguity without importing the producer-side definition.

### WP3 [suggestion]
**File:** `.claude/workflow/prompts/structural-review.md` (staged lines 194-222: new bullet vs the first TRACK SIZING bullet)
**Axis:** description discriminability (between sibling criteria, not frontmatter)
**Cost:** a track that trips both the maximization clause and the overlap-split clause yields an undefined one-finding-or-two disposition

**Issue:** The new bullet is conceptually discriminable from the first TRACK SIZING bullet — that one sizes a single track's footprint, this one inspects a cross-track file-overlap relationship neither track's footprint reveals, and the remedy framing ("co-locating shared files… or ordering adjacent") is distinct. The unhandled overlap is the boundary case: a track "stopped below the ceiling with a mergeable autonomous unit left unpacked" (first bullet) where the unpacked unit shares files with a non-adjacent track (new bullet) trips both criteria. The prompt does not say whether that is one finding or two, so the sub-agent will be inconsistent across runs.

**Suggestion:** Add a one-clause disambiguator to the new bullet, e.g. "When the unpacked mergeable unit and the overlap-split are the same pair of tracks, file one finding under the maximization clause and note the overlap as its motivation; file the overlap-split separately only when the sharing tracks are not the under-fill pair."

## Evidence base

(This dimension is evidence-trail-exempt — reason (a): no refutation or certificate phase to persist. No `#### C<n>` entries.)
