<!--
MANIFEST
dimension: workflow-writing-style
iteration: 1
range: 98c5dd4719..HEAD
verdict: PASS_WITH_SUGGESTIONS
summary: Added advisory prose is clean of banned vocabulary, BLUF-led, and within section budgets. One em-dash-cap violation (should-fix) and one negative-parallelism tail (suggestion).
index:
  - id: WS1
    sev: Recommended
    anchor: "#ws1-recommended-em-dash-overuse"
    loc: "docs/adr/token-economy-oriented-planning/_workflow/staged-workflow/.claude/workflow/planning.md:451-461"
    cert: C1
    basis: judgment
  - id: WS2
    sev: Minor
    anchor: "#ws2-minor-negative-parallelism"
    loc: "docs/adr/token-economy-oriented-planning/_workflow/staged-workflow/.claude/workflow/planning.md:471-472"
    cert: C2
    basis: judgment
evidence_base: "## Evidence base"
cert_index:
  - C1
  - C2
flags: []
-->

## Findings

### WS1 [Recommended] em-dash overuse

- File: `docs/adr/token-economy-oriented-planning/_workflow/staged-workflow/.claude/workflow/planning.md` (line 451-461, delta-added *Packing order* paragraph), Axis: em-dash overuse, Cost: two em dashes in one blank-line-bounded paragraph in a planner-facing always-read rule file, Issue: violates `house-style.md § Punctuation and typography → Em-dash discipline` ("At most one em dash per paragraph"). The paragraph carries the italic run-in label dash `*Packing order — prefer overlap at the tie.*` (line 451) **and** a second mid-body dash `exactly as *Maximize first* says — removing a track's review fan-out is the dominant saving` (line 457). The two sibling delta paragraphs (*Cut seam*, *Overlap-split justification*) each carry exactly one (the label dash only), so this is the single over-cap unit. Suggestion: convert the mid-body dash to a period so the label dash is the paragraph's only em dash. Replace `exactly as *Maximize first* says — removing a track's review fan-out is the dominant saving whether or not the packed units share files.` with `exactly as *Maximize first* says. Removing a track's review fan-out is the dominant saving whether or not the packed units share files.`

### WS2 [Minor] negative-parallelism tail

- File: `docs/adr/token-economy-oriented-planning/_workflow/staged-workflow/.claude/workflow/planning.md` (line 471-472, delta-added *Cut seam* paragraph), Axis: banned vocabulary, Cost: faux-symmetric "X, not Y" closing shape, the mild form of the negative-parallelism tell, Issue: `house-style.md § Banned sentence patterns → Negative parallelism` flags "Not just A, but B." and the "X, not Y" cadence as performing emphasis without adding information. The closing clause `so it is the marginal fallback, not the goal.` carries the pattern. Single instance, not the canonical "It's not X — it's Y", so Minor. Suggestion: state the consequence positively, e.g. `so prefer co-location and adjacency where the bounds permit; treat adjacency between unmergeable tracks as the residual fallback.` The author may keep the current phrasing if the contrast with the preceding sentence (adjacency removes no fan-out) is judged load-bearing for the reader.

## Evidence base

#### C1

Confirmed. `grep -c "—"` over `planning.md` lines 451-461 returns 2; over 463-472 and 474-481 returns 1 each. The two dashes in the *Packing order* paragraph are the italic run-in label (451) and the mid-body `says —` (457). House-style § Em-dash discipline caps a blank-line-bounded paragraph at one. Finding holds.

#### C2

Confirmed. Source text of the *Cut seam* paragraph closes with `Adjacency between tracks that cannot share an agent removes no review fan-out, so it is the marginal fallback, not the goal.` The trailing `X, not Y` is the negative-parallelism shape per § Banned sentence patterns. Mild single instance; rendered in full per the refuted/non-passing roster rule since the recommended rewrite is offered with an author-discretion carve-out. Finding holds at Minor.
