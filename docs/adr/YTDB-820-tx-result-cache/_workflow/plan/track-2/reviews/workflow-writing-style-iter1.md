<!-- MANIFEST
reviewer: review-workflow-writing-style
target: docs/adr/YTDB-820-tx-result-cache/_workflow/plan/track-2.md
range: babd8d607687b284687b57ccb882cf838148055b..HEAD
findings: 2
high_water_mark: 0
evidence_base: "## Evidence base"
cert_index:
  - C1
  - C2
flags: []
index:
  - id: WS1
    sev: should-fix
    anchor: "### WS1 "
    loc: "docs/adr/YTDB-820-tx-result-cache/_workflow/plan/track-2.md:297-311"
    cert: C1
    basis: judgment
  - id: WS2
    sev: should-fix
    anchor: "### WS2 "
    loc: "docs/adr/YTDB-820-tx-result-cache/_workflow/plan/track-2.md:394-412"
    cert: C2
    basis: judgment
-->

## Findings

### WS1 [should-fix] Two em dashes in the Step 1 "What was discovered" paragraph

- File: `docs/adr/YTDB-820-tx-result-cache/_workflow/plan/track-2.md` (line 297-311)
- Axis: em-dash overuse
- Cost: two em dashes in one blank-line-bounded paragraph; the house-style cap is one per paragraph (`house-style.md § Em-dash discipline`).
- Issue: The Step 1 `**What was discovered:**` paragraph (lines 297-311) carries two em dashes, both in the inline deferral list: `steps: BC2 — observe reads the projected Result ...; BC3 — MIN/MAX over mixed non-Number ...`. `§ Em-dash discipline` allows at most one em dash per paragraph and the mechanical check counts per blank-line-bounded paragraph.
- Suggestion: Replace the `BC<N> —` separators with colons, which reads as the definition-list form the content actually is:

  ```
  Two review suggestions defer to the track-level pass or later
  steps: BC2: `observe` reads the projected `Result` while `applyMutation` reads the raw
  `Entity`, a value-source coupling the Step 2 classifier gate guards; BC3: MIN/MAX over
  mixed non-Number `Comparable` values can throw `ClassCastException`, ...
  ```

### WS2 [should-fix] Three em dashes in the Step 3 "What was discovered" paragraph

- File: `docs/adr/YTDB-820-tx-result-cache/_workflow/plan/track-2.md` (line 394-412)
- Axis: em-dash overuse
- Cost: three em dashes in one blank-line-bounded paragraph; the cap is one per paragraph, and `§ Em-dash discipline` also bans the multi-clause em-dash cadence (`house-style.md § Em-dash discipline`).
- Issue: The Step 3 `**What was discovered:**` paragraph (lines 394-412) carries three em dashes: line 394 `no native distinct-count — count(distinct(prop)) is computed ...`, line 405 `skip it — unreachable: the plan is single-use ...`, and line 407 `without a null guard — unreachable: a valid SELECT ...`. Two of the three (`skip it —`, `null guard —`) introduce an `unreachable:` justification, where a colon does the same work.
- Suggestion: Keep the first em dash (line 394, the one allowed per paragraph) and convert the two `— unreachable:` joins to colons: `... so prettyPrint/reset/serialize skip it (unreachable: the plan is single-use ...)` and `... without a null guard (unreachable: a valid SELECT always chains at least one step)`. Parenthesizing the BC1/BC2 asides also tightens the long enumeration.

## Evidence base

#### C1 — em-dash count, Step 1 "What was discovered" paragraph (lines 297-311)
Refuted-in-full check (finding survives): counted em dashes in the blank-line-bounded paragraph spanning lines 297-311 (blanks at 296 and 312). Em dashes at line 302 (`BC2 —`) and line 303 (`BC3 —`) → 2 per paragraph, over the cap of 1. The heading-line em dash (line 279, `### Step 1 —`) is a separate paragraph and does not count. Confirmed via `grep '—'` on the Episodes range cross-referenced against blank-line offsets.

#### C2 — em-dash count, Step 3 "What was discovered" paragraph (lines 394-412)
Refuted-in-full check (finding survives): counted em dashes in the blank-line-bounded paragraph spanning lines 394-412 (blanks at 393 and 413). Em dashes at line 394 (`distinct-count —`), line 405 (`skip it —`), line 407 (`null guard —`) → 3 per paragraph, over the cap of 1; also matches the banned multi-clause cadence. The heading-line em dash (line 372) and the line-415 em dash (`What changed from the plan` paragraph, one per paragraph, fine) are separate paragraphs. Confirmed via `grep '—'` cross-referenced against blank-line offsets.

#### Survived checks (one line each)
- Banned vocabulary Tier 1-4: confirmed clean across in-range prose (lines 21-120, 276-435) — zero hits on the full Tier 1/2/3 regex sweep.
- Banned sentence patterns (negative parallelism, throat-clearing, signposting, closing phrases): confirmed clean — zero hits on the `it's not X / not just / at its core / let's break / in conclusion` sweep. The "returns a ROW count ..., not a distinct count" (line 64) and "returns 5, not 2" (line 395) contrasts state load-bearing factual results, not performative "not X, it's Y" depth-performance; not findings.
- Banned analysis patterns (copula avoidance, hedge stacking, filler phrases `in order to` / `due to the fact that`): confirmed clean — zero hits.
- Section length: episode `## Episodes` structured-field blocks are template-bound-exempt per `house-style.md § Structural rules` "Section length cap exception" category (1); not evaluated for length per the task scope.
