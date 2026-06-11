<!-- MANIFEST
findings: 8   severity: {Critical: 0, Recommended: 7, Minor: 1}
index:
  - {id: WS1, sev: Recommended, loc: "staged-workflow/.claude/workflow/track-review.md:600", anchor: "### WS1 ", cert: C1, basis: "3 em dashes in one paragraph; opens with forbidden X — Y — Z triple-clause cadence"}
  - {id: WS2, sev: Recommended, loc: "staged-workflow/.claude/workflow/implementation-review.md:682", anchor: "### WS2 ", cert: C2, basis: "forbidden X — Y — Z triple-clause cadence in one sentence (2 em dashes)"}
  - {id: WS3, sev: Recommended, loc: "plan/track-2.md:66", anchor: "### WS3 ", cert: C3, basis: "4 em dashes in one retrospective paragraph incl. a triple-clause cadence"}
  - {id: WS4, sev: Recommended, loc: "staged-workflow/.claude/workflow/implementation-review.md:184", anchor: "### WS4 ", cert: C4, basis: "2 em dashes in one prose paragraph (em-dash cap is 1)"}
  - {id: WS5, sev: Recommended, loc: "staged-workflow/.claude/workflow/plan-slim-rendering.md:278", anchor: "### WS5 ", cert: C5, basis: "2 em dashes bracketing a parenthetical clause in one sentence"}
  - {id: WS6, sev: Recommended, loc: "staged-workflow/.claude/workflow/workflow.md:644", anchor: "### WS6 ", cert: C6, basis: "2 em dashes in one bullet"}
  - {id: WS7, sev: Recommended, loc: "staged-workflow/.claude/workflow/workflow.md:669", anchor: "### WS7 ", cert: C7, basis: "2 em dashes bracketing a clause in one numbered item"}
  - {id: WS8, sev: Minor, loc: "plan/track-2.md:432", anchor: "### WS8 ", cert: C8, basis: "2 em dashes in one prose paragraph"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/track-review.md` (lines 600-611)
**Axis:** em-dash overuse
**Cost:** three em dashes in one paragraph, opening with the forbidden `X — Y — Z` triple-clause cadence in the lead sentence of the new tier-selection section.

**Issue:** The paragraph opens `The **confirmed tier** (D9) — not step count — selects the Phase-3A panel at the change level.` That is the `X — Y — Z` cadence the house-style names as always a finding (`§ Em-dash discipline`: "Never use the `X — Y — Z` triple-clause cadence"). A third em dash later in the same blank-line-bounded paragraph (`not user interaction level — all tracks execute autonomously...`) pushes the count to three, against the one-per-paragraph cap. This is new prose added by this track (delta added lines 528 and 535).

**Suggestion:** Drop the appositive dashes in the lead and demote the third to a comma:

```
The **confirmed tier** (D9), not step count, selects the Phase-3A panel
at the change level. Read the tier line in `implementation-plan.md`. This
replaces the former Simple / Moderate / Complex step-count axis as the
change-level selector. The selection reads **no per-step risk signal**
(S4): the tier is the change-level driver; the per-step `risk:` tag stays
the Phase-3B gate and the Phase-C triage stays the Phase-3C gate, and the
two never stack into one signal. The reviews still determine *which*
pre-execution passes run, not user interaction level: all tracks execute
autonomously after review, ...
```

### WS2 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/implementation-review.md` (lines 682-684)
**Axis:** em-dash overuse
**Cost:** the forbidden `X — Y — Z` triple-clause cadence inside a single sentence (two em dashes bracketing an appositive).

**Issue:** The closing sentence of the new "This whole deferral mechanism applies only when a `design.md` exists" paragraph reads `A design-destination bloat fix — one that would move live material into the frozen seed — re-routes to the matching track section in **every** tier...`. The bracketing-dash pair is the triple-clause cadence the em-dash rule forbids (`§ Em-dash discipline`). New prose (delta added line 97 / staged line 683).

**Suggestion:** Convert the appositive to a parenthetical so the dashes go away:

```
A design-destination bloat fix (one that would move live material into the
frozen seed) re-routes to the matching track section in **every** tier,
including `full`, because the seed is non-canonical under the carrier flip
(the structural review owns this re-route; see
structural-review.md:orchestrator,reviewer-plan:2,3A,3C).
```

### WS3 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/plan/track-2.md` (lines 66-77, `## Outcomes & Retrospective`)
**Axis:** em-dash overuse
**Cost:** four em dashes in one prose paragraph, one pair forming a triple-clause cadence.

**Issue:** The Phase-A retrospective paragraph carries four em dashes: `the findings overlapped heavily — T2≈R2, T5≈R5≈A2, T3≈R4 — and were all 0-blocker...` (the bracketing pair is the `X — Y — Z` cadence), plus `carve-out — left unfixed it would silently desync...` and `the "xhigh effort pin" overstated the harness — reconciled to D14's...`. `## Outcomes & Retrospective` is not one of the five template-bound section-length exemptions, and the em-dash cap has no carve-out for it, so the one-per-paragraph rule applies in full.

**Suggestion:** Parenthesize the inline list and split the trailing catch-clauses into their own sentences:

```
The gate verification ran once at iteration 2 as a single consolidated pass
over all three review types (the findings overlapped heavily: T2≈R2,
T5≈R5≈A2, T3≈R4, and were all 0-blocker plan-of-work enumeration gaps),
VERIFYING every finding with 0 regressions. Highest-value catches: R2/T2
(the propagation duty's primary `[ ]`-track write path needed
`## Decision Log` in inline-replanning cases 2-3, not only the
completed-track carve-out; left unfixed it would silently desync duplicated
decisions); A6 (`minimal` consistency drops the plan half too, not only the
design half); A1 (the "xhigh effort pin" overstated the harness, reconciled
to D14's session-default degradation caveat).
```

### WS4 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/implementation-review.md` (lines 184-193)
**Axis:** em-dash overuse
**Cost:** two em dashes in one paragraph (cap is one).

**Issue:** The opening paragraph of the new "Tier-driven pass selection" section has `read the **D18 tier line** from `implementation-plan.md` — the single change-level line...` and later `The same line is read on every entry — a fresh `/execute-tracks` State-0 session...`. Two em dashes in one blank-line-bounded paragraph (delta added lines 7 and 12).

**Suggestion:** Replace the first dash with a comma (apposition) and keep at most one:

```
Before launching Step 1, read the **D18 tier line** from
`implementation-plan.md`, the single change-level line `create-plan` writes
at confirmation, carrying the tier (`full` / `lite` / `minimal`) and its
centrally-matched HIGH-risk categories. ... The same line is read on every
entry — a fresh `/execute-tracks` State-0 session and a manual
`/review-plan` re-run both read it from the always-present plan.
```

### WS5 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/plan-slim-rendering.md` (lines 278-285)
**Axis:** em-dash overuse
**Cost:** two em dashes bracketing a parenthetical clause in one sentence.

**Issue:** `The shape mirrors the slim plan's principle — keep what a sub-agent reads for strategy and tactics, shed the high-cadence continuous logs that exist for resume and audit — with one carrier-specific addition: the inline `## Decision Log` is **load-bearing**...`. The bracketing-dash pair exceeds the one-per-paragraph cap (delta added lines 238 and 240).

**Suggestion:** Make the bracketed clause a parenthetical, leaving the colon to carry the addition:

```
The shape mirrors the slim plan's principle (keep what a sub-agent reads
for strategy and tactics, shed the high-cadence continuous logs that exist
for resume and audit) with one carrier-specific addition: the inline
`## Decision Log` is **load-bearing and kept in full** in every tier,
because under D7 it is where the live decision lives.
```

### WS6 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/workflow.md` (lines 644-646)
**Axis:** em-dash overuse
**Cost:** two em dashes in one bullet.

**Issue:** The `**`minimal`, workflow-modifying**` bullet reads `— two commits (promote-staged-workflow, then cleanup); the shed removes the fold's `adr.md` home and the final-artifacts commit, not the rest of Phase 4 — promotion still runs.` Two em dashes in one bullet unit (delta added lines 631 and 633). The sibling bullets above (`full`/`lite` rows) each carry one dash and pass; only this one doubles up.

**Suggestion:** Demote the trailing dash to a semicolon or period:

```
- **`minimal`, workflow-modifying** — two commits (promote-staged-workflow,
  then cleanup); the shed removes the fold's `adr.md` home and the
  final-artifacts commit, not the rest of Phase 4. Promotion still runs.
```

### WS7 [Recommended]
**File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/workflow.md` (lines 669-679)
**Axis:** em-dash overuse
**Cost:** two em dashes bracketing a clause in one numbered item.

**Issue:** Numbered item 2 ("Final-artifacts commit") reads `Then stage the tier's artifacts — `design-final.md` + `design-mechanics-final.md` (if applicable) + `adr.md` in `full`, `adr.md` only in `lite` — and commit with the message...`. The bracketing-dash pair exceeds the cap (delta added lines 638 and 640).

**Suggestion:** Parenthesize the artifact enumeration:

```
Then stage the tier's artifacts (`design-final.md` +
`design-mechanics-final.md` if applicable + `adr.md` in `full`; `adr.md`
only in `lite`) and commit with the message defined in
`prompts/create-final-design.md` § Step 5; push.
```

### WS8 [Minor]
**File:** `docs/adr/plan-slimization/_workflow/plan/track-2.md` (lines 432-439, `## Interfaces and Dependencies`)
**Axis:** em-dash overuse
**Cost:** two em dashes in one prose paragraph.

**Issue:** The `**Dependencies.**` paragraph has `Upstream: Track 1 — the tier vocabulary (glossary)...` and `...so step 7's correctness depends on that section having landed — it has, in Track 1's staged `research.md`)`. Two em dashes in one blank-line-bounded paragraph. Minor because both read as one-per-sentence and the paragraph is dense reference prose, but the cap is per-paragraph.

**Suggestion:** Replace the first dash with a colon (it introduces the list of upstream items) and keep the second:

```
**Dependencies.** Upstream: Track 1 supplies the tier vocabulary (glossary),
the D18 tier-line shape the Phase-2/3A selectors read, the inline-DR track
shape this track's lifecycle, rendering, and propagation rules govern, and
the `research.md` `## Adversarial gate record` section the Phase-4 fold reads
(a cross-track read of a Track 1 file: the fold dereferences a section
Track 1 defined, so step 7's correctness depends on that section having
landed — it has, in Track 1's staged `research.md`). ...
```

## Evidence base

#### C1 track-review.md:600 — CONFIRMED
Per-paragraph em-dash count over the staged file's blank-line-bounded prose paragraph at L600-611 returns 3 (`— not step count —` is two; `— all tracks execute` is the third). Both dash-bearing lines appear as added lines in the track-2 delta (added lines 528, 535), so the paragraph is new prose, in scope. The lead-sentence pair is the `X — Y — Z` cadence `§ Em-dash discipline` names as always a finding.

#### C2 implementation-review.md:682 — CONFIRMED
The sentence at staged L682-684 contains the bracketing em-dash pair `— one that would move live material into the frozen seed —`, the forbidden triple-clause cadence in one sentence. The hunk is new (delta added lines 110-120, em dash at added line 97). Two em dashes in one sentence; cap is one per paragraph.

#### C3 track-2.md:66 — CONFIRMED
The `## Outcomes & Retrospective` paragraph at L66-77 contains four `—` characters by direct count. The section is not among the five template-bound section-length exemptions in `house-style.md § Structural rules`, and `§ Em-dash discipline` applies to every paragraph with no Episodes/retrospective carve-out. The bracketed inline list `— T2≈R2, T5≈R5≈A2, T3≈R4 —` is itself the `X — Y — Z` cadence.

#### C4 implementation-review.md:184 — CONFIRMED
The new section-opening paragraph at staged L184-193 has two `—` characters in one blank-line-bounded paragraph (delta added lines 7 and 12). Cap is one.

#### C5 plan-slim-rendering.md:278 — CONFIRMED
Staged L278-285 paragraph has the bracketing-dash pair `— keep what a sub-agent reads ... for resume and audit —` (delta added lines 238, 240). Two em dashes in one sentence; cap is one per paragraph.

#### C6 workflow.md:644 — CONFIRMED
The `**`minimal`, workflow-modifying**` bullet at L644-646 carries two `—` characters (delta added lines 631, 633). Treated as one bullet unit; two em dashes in it exceed the cap, while the sibling `full`/`lite` bullets above carry one each and pass.

#### C7 workflow.md:669 — CONFIRMED
Numbered item 2 at L669-679 has the bracketing-dash pair `— `design-final.md` ... only in `lite` —` (delta added lines 638, 640). Two em dashes bracketing the artifact enumeration; cap is one.

#### C8 track-2.md:432 — CONFIRMED
The `**Dependencies.**` paragraph at L432-439 has two `—` characters in one blank-line-bounded paragraph. New track-authored prose. Classed Minor because both read as one-per-sentence in dense reference prose, but the cap is per-paragraph.
