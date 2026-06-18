<!--MANIFEST
dimension: workflow-writing-style
target: track-2
iteration: 1
findings_count: 3
evidence_base: 3
cert_index: [C1, C2, C3]
flags: [CONTRACT_OK]
index:
  - id: WS1
    sev: Recommended
    anchor: "### WS1 [Recommended] em-dash cap exceeded in fidelity-check.md hard-invariant paragraph"
    loc: ".claude/agents/fidelity-check.md (staged) line 29"
    cert: C1
    basis: judgment
  - id: WS2
    sev: Recommended
    anchor: "### WS2 [Recommended] em-dash cap exceeded in track-2.md Purpose intro paragraph"
    loc: "plan/track-2.md lines 9-27"
    cert: C2
    basis: judgment
  - id: WS3
    sev: Minor
    anchor: "### WS3 [Minor] em-dash cap exceeded in track-2.md D15 Rationale, with negative-parallelism flourish"
    loc: "plan/track-2.md line 62"
    cert: C3
    basis: judgment
-->

## Findings

### WS1 [Recommended] em-dash cap exceeded in fidelity-check.md hard-invariant paragraph
- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/fidelity-check.md` (line 29)
- Axis: em-dash overuse
- Cost: two em dashes in one blank-line-bounded paragraph in genuinely-new agent prose
- Issue: `house-style.md § Punctuation and typography → Em-dash discipline` caps em dashes at one per paragraph. The "You never read the research log (S2)" paragraph carries two: `…the live code through PSI — never the research log` and `…that is a wiring error — ignore it`. The mechanical check counts em dashes per blank-line-bounded paragraph; two is a finding.
- Suggestion: convert one of the two to a period or colon. For the first: "…and the live code through PSI; never the research log." leaves the second dash as the paragraph's single em dash. Result: "Your sources are the step and track episodes, the frozen `design.md` for the residual, and the live code through PSI; never the research log. … if one is wired into your params file, that is a wiring error — ignore it."

### WS2 [Recommended] em-dash cap exceeded in track-2.md Purpose intro paragraph
- File: `docs/adr/understandable-design/_workflow/plan/track-2.md` (lines 9-27)
- Axis: em-dash overuse
- Cost: five em dashes in one blank-line-bounded paragraph of new authored track prose
- Issue: `house-style.md § Em-dash discipline` caps at one per paragraph. The `## Purpose / Big Picture` body paragraph ("This track wires the Track 1 loop…") carries five: `lite` and `minimal` — which have no design buffer — get (a bracketing pair, two dashes), `this track supplies the missing half — the fidelity-check agent definition… `edit-design` Step 4 — and refreshes` (another bracketing pair), and `…into one `create-plan` invocation — a staged change`. This is well over the cap.
- Suggestion: replace the bracketing dash pairs with parentheses or commas and demote the trailing dashes to periods. For example: "…with track-decision-record absorption as the second check, so `lite` and `minimal` (which have no design buffer) get the same readability help." and "…this track supplies the missing half (the fidelity-check agent definition and its spawn-contract row in `edit-design` Step 4) and refreshes…". Keep at most one em dash in the paragraph.

### WS3 [Minor] em-dash cap exceeded in track-2.md D15 Rationale, with negative-parallelism flourish
- File: `docs/adr/understandable-design/_workflow/plan/track-2.md` (line 62)
- Axis: em-dash overuse
- Cost: three em dashes in one Decision Log Rationale paragraph plus a faux-symmetric "real X, not cosmetic Y" flourish
- Issue: `house-style.md § Em-dash discipline` caps at one per paragraph; the D15 Rationale carries three (`Sub-agent authoring supplies that isolation directly — the plan and track author… regardless of session — so the boundary stops earning its keep`, a bracketing pair, and `committed-and-clean → 4b or dirty → 4a` uses arrows, not dashes — the third dash is the bracketing pair plus the standalone). The same paragraph opens "This is a real machinery change, not a cosmetic convenience", the performative negative-parallelism shape `house-style.md § Banned sentence patterns` flags. Registry-terse decision-log register, but the punctuation cap and sentence-pattern bans are always-on.
- Suggestion: replace the bracketing dash pair with commas — "Sub-agent authoring supplies that isolation directly, since the plan and track author is a fresh cold spawn reading the frozen committed design regardless of session, so the boundary stops earning its keep…" — and restate the opener positively: "This rewrites the `create-plan` auto-resume contract (the routing that keys on whether `design.md` is committed-and-clean → 4b or dirty → 4a, and the `Step 4a ends the session` rule), so it is a machinery change, staged under §1.7(b)."

## Evidence base

#### C1
Confirmed. `grep -nE '—.*—'` on the staged `fidelity-check.md` returns line 29, and the per-paragraph em-dash sweep counts 2 in the blank-line-bounded block (lines 27-29 under `### You never read the research log (S2)`). Both dashes sit in distinct sentences within one paragraph; `§ Em-dash discipline` counts per blank-line-bounded paragraph, so 2 > 1 is a finding. File is genuinely-new (no live counterpart per the delta file's new-file note), so in full scope.

#### C2
Confirmed. Per-paragraph sweep counts 5 em dashes in the `## Purpose / Big Picture` body paragraph (lines 9-27). `track-2.md` is a new house-style-governed track file in full review scope. The dashes form two bracketing pairs plus one standalone, all in one paragraph; well over the 1-per-paragraph cap of `§ Em-dash discipline`.

#### C3
Confirmed. Per-paragraph sweep counts 3 em dashes in the D15 Rationale block (line 62), a Decision Log entry in registry-terse register. The em-dash cap and the `§ Banned sentence patterns` negative-parallelism ban ("This is a real machinery change, not a cosmetic convenience") are both always-on, not document-shape rules, so they apply to the decision log. Severity held at Minor: registry-terse register, one dash is a bracketing pair, and the contrast carries some argumentative weight in justifying the §1.7(b) staging classification.
