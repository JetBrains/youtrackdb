<!-- MANIFEST
dimension: workflow-writing-style
target: track-1 (full diff 2775833bc3..HEAD)
scope: staged-workflow delta (11 docs) + track-1.md authored prose
findings: 4
evidence_base: 4
cert_index:
  - { id: WS1, cert: C1 }
  - { id: WS2, cert: C2 }
  - { id: WS3, cert: C3 }
  - { id: WS4, cert: C4 }
index:
  - { id: WS1, sev: Recommended, anchor: "WS1", loc: "create-plan/SKILL.md Step 2 (research-log write paragraph)", cert: C1, basis: judgment, flags: [] }
  - { id: WS2, sev: Recommended, anchor: "WS2", loc: "create-plan/SKILL.md Step 4 review-hold batching (three-step batch, item 3)", cert: C2, basis: judgment, flags: [] }
  - { id: WS3, sev: Recommended, anchor: "WS3", loc: "conventions-execution.md §Verdict-producer manifest variant (Phase-0→1 gate paragraph)", cert: C3, basis: judgment, flags: [] }
  - { id: WS4, sev: Recommended, anchor: "WS4", loc: "research.md §The research log (Read-scope discipline paragraph)", cert: C4, basis: judgment, flags: [] }
flags: []
-->

## Findings

### WS1 [Recommended] Three em dashes in the research-log write paragraph

- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (Step 2, the "Once the user provides the aim, write the research log's `## Initial request`" paragraph)
- **Axis:** em-dash overrun
- **Cost:** three em dashes in one blank-line-bounded paragraph in a load-bearing SKILL body; the house-style cap is one per paragraph and `dsc-ai-tell` flags more than one
- **Issue:** Violates `house-style.md § Em-dash discipline` ("At most one em dash per paragraph"). The paragraph carries an appositive pair around the verbatim aim plus a third dash before the unstamped clause:

  > write the **research log's `## Initial request`** — the verbatim aim — as the first durable Phase-0 action. … The log is created **unstamped** — it is on the `§1.6(f)` never-stamped list (D19) …

- **Suggestion:** Drop the appositive dashes to a parenthetical and convert the third to a period. Replacement:

  > write the **research log's `## Initial request`** (the verbatim aim) as the first durable Phase-0 action. … The log is created **unstamped**: it is on the `§1.6(f)` never-stamped list (D19) …

### WS2 [Recommended] Double-dash interruption in the D15 cold-read batch item

- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (§ Step 4 review-hold batching, three-step batch, item 3 "One cold-read, with loop-back.")
- **Axis:** em-dash overrun
- **Cost:** two em dashes bracketing a mid-clause interruption in a numbered procedure step
- **Issue:** Violates `house-style.md § Em-dash discipline` (≤1 per unit). The offending clause:

  > A decision-shaped cold-read finding **re-enters the gate step** (step 1) and the batch cannot close — nor the artifact re-present — while a log entry is open.

- **Suggestion:** Fold the interruption into the main clause:

  > A decision-shaped cold-read finding **re-enters the gate step** (step 1); while a log entry is open the batch cannot close and the artifact cannot re-present.

### WS3 [Recommended] Double-dash interruption in the Phase-0→1 gate variant paragraph

- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/conventions-execution.md` (§Verdict-producer manifest variant, the appended "The Phase-0→1 research-log adversarial gate … is a verdict-producing gate" paragraph)
- **Axis:** em-dash overrun
- **Cost:** two em dashes bracketing a mid-clause aside that the reader must hold the main clause across
- **Issue:** Violates `house-style.md § Em-dash discipline` (≤1 per paragraph). The clause:

  > This resolves D17's open implementation question — the gate's re-challenge runs use the verdict variant, not a fold of verdicts into the finding set — which is why the `planner`/`1` access this section grants covers both the gate writer …

- **Suggestion:** Split into two sentences and drop the dashes:

  > This resolves D17's open implementation question: the gate's re-challenge runs use the verdict variant, not a fold of verdicts into the finding set. That is why the `planner`/`1` access this section grants covers both the gate writer (`reviewer-adversarial` at phase `1`) and the `create-plan` reader (`planner` at phase `1`) on the variant row.

### WS4 [Recommended] Double-dash interruption in the read-scope discipline paragraph

- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/research.md` (§The research log, "Read-scope discipline (S2)." paragraph)
- **Axis:** em-dash overrun
- **Cost:** two em dashes bracketing the "exactly two places" enumeration; the `→` arrow in the same paragraph is not an em dash and is fine
- **Issue:** Violates `house-style.md § Em-dash discipline` (≤1 per paragraph). The clause:

  > the log is read for decision *content* in exactly two places — at Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2 consistency review (as a cross-check) — and is never cross-linked from the artifacts it seeds.

- **Suggestion:** Replace the dash pair with a colon-introduced list closed by a period, then start a new sentence:

  > the log is read for decision *content* in exactly two places: at Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2 consistency review (as a cross-check). It is never cross-linked from the artifacts it seeds.

## Evidence base

#### C1 [WS1] survived
Em-dash count on the Step 2 research-log-write paragraph (`SKILL.md` lines 237-249, one blank-line-bounded paragraph): three `—` characters outside fenced code — two around "the verbatim aim" appositive (line 238), one before "it is on the `§1.6(f)` never-stamped list" (line 247). House-style cap is one per paragraph; confirmed violation.

#### C2 [WS2] survived
Em-dash count on the three-step-batch item 3 (`SKILL.md` lines 1044-1049): two `—` characters bracketing "nor the artifact re-present" (line 1047), counted by `grep -o '—' | wc -l` = 2 over the item's blank-line-bounded span. Confirmed violation.

#### C3 [WS3] survived
Em-dash count on the appended Phase-0→1 gate paragraph (`conventions-execution.md` lines 632-640): two `—` characters bracketing "the gate's re-challenge runs use the verdict variant, not a fold of verdicts into the finding set" (lines 636-638), `grep -o '—' | wc -l` = 2. The `→` in `Phase-0→1` is an arrow, not an em dash, and was excluded. Confirmed violation.

#### C4 [WS4] survived
Em-dash count on the Read-scope discipline paragraph (`research.md` lines 87-96): two `—` characters bracketing the "exactly two places" enumeration (lines 88, 90), `grep -o '—' | wc -l` = 2. The `→` in "log → carrier" is an arrow, excluded. Confirmed violation.

#### Coverage note (banned-vocabulary and pattern sweeps — all clean)
- Tier 1-4 banned-vocabulary grep over the added (`>`) delta lines and over `track-1.md`: no hits. (`harness` appears twice in `track-1.md` only as "harness surface" — the literal test/spawn-harness noun, not the Tier-2 metaphorical "harness the power of"; not a finding.)
- Banned sentence patterns — throat-clearing, signposting, closing connectives, sycophantic openers, hedge-stacking, copula avoidance ("serves as"/"stands as"), generic positive conclusions, persuasive-authority tropes: no hits in the delta. The `X, not Y` constructions the negation grep surfaced are corrective appositives (state-what-is-true plus an exclusion), not the banned "It's not X — it's Y" / "Not just A, but B" performance-of-depth shape.
- Title Case H2+ in new prose: none. ("Architecture Notes format" / "Architecture Notes rules" in `planning.md` are pre-existing live headings outside the delta and sit on the `§ Structural rules` scaffold carve-out anyway.)
- Section length: the longest new sections (`design-review.md` §Track-scoped cold-read, `create-plan/SKILL.md` §Step 4 review-hold batching, `planning.md` §Tier classification) exceed the 200-word soft cap but carry no padding pattern (no banned term, no banned sentence pattern, no elegant variation). Under `house-style.md § Structural rules` "Padding-based finding criterion" length alone is not a finding; no length finding raised.
