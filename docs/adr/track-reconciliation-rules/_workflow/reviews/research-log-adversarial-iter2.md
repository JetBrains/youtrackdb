<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 1, suggestion: 0, verified: 5, rejected: 0}
index:
  - {id: A1, sev: blocker,    loc: "research-log.md:102", anchor: "### A1 ", cert: "Verdict A1", basis: "VERIFIED — D4.1 defines no-progress on the existing gate-check verdict stream (identity by id, threshold = all STILL OPEN + zero clears + no new fixable, REGRESSION immediate, gates each uncapped loop)"}
  - {id: A2, sev: blocker,    loc: "research-log.md:188", anchor: "### A2 ", cert: "Verdict A2", basis: "VERIFIED — Surprises Full-blast-radius entry now enumerates six cap-3 mechanics with per-mechanic restatements; the 4-file framing is corrected"}
  - {id: A3, sev: should-fix, loc: "research-log.md:75",  anchor: "### A3 ", cert: "Verdict A3", basis: "VERIFIED — D3.1 keeps the single shared counter, gates should-fix on iteration<=3, lets blockers drive past 3, defines post-3 should-fix handling"}
  - {id: A4, sev: should-fix, loc: "research-log.md:35",  anchor: "### A4 ", cert: "Verdict A4", basis: "VERIFIED — D2.1 commits to wiring a one-line carve-out into Limits itself (phases 2,3A,3B,3C filter confirmed), not merely asserting the override"}
  - {id: A5, sev: should-fix, loc: "research-log.md:51",  anchor: "### A5 ", cert: "Verdict A5", basis: "VERIFIED — D3 reframed as the delta: low already loops on blockers within the cap; the change removes the cap; low relies entirely on no-progress detection"}
  - {id: A6, sev: should-fix, loc: "research-log.md:125", anchor: "### A6 ", cert: "Verdict A6", basis: "VERIFIED — D4.2 states orthogonal axes: context pause bounds per-session burn, no-progress bounds convergence; neither substitutes for the other"}
  - {id: A7, sev: should-fix, loc: "research-log.md:188", anchor: "### A7 ", cert: "Assumption test: footprint enumeration is now exhaustive", basis: "the revised Full-blast-radius entry presents itself as exhaustive but still omits four cap-3-keyed sites (527 cost-model 3x, 724 2-of-3, 1092 FAILED N/3, 1106 blockers-persist note), reproducing A2's failure mode"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: "Verdict A1", verdict: VERIFIED, anchor: "#### Verdict A1 — no-progress detection operationally defined (D4.1)"}
  - {id: "Verdict A2", verdict: VERIFIED, anchor: "#### Verdict A2 — blast-radius footprint enumerated (Surprises)"}
  - {id: "Verdict A3", verdict: VERIFIED, anchor: "#### Verdict A3 — medium shared-counter interaction resolved (D3.1)"}
  - {id: "Verdict A4", verdict: VERIFIED, anchor: "#### Verdict A4 — Limits carve-out wired, not asserted (D2.1)"}
  - {id: "Verdict A5", verdict: VERIFIED, anchor: "#### Verdict A5 — low delta reframed (D3)"}
  - {id: "Verdict A6", verdict: VERIFIED, anchor: "#### Verdict A6 — context-pause composition stated (D4.2)"}
  - {id: "Assumption test: footprint enumeration is now exhaustive", verdict: BREAKS, anchor: "#### Assumption test: the revised Full-blast-radius entry enumerates every cap-3-keyed mechanic"}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [blocker]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A1 — no-progress detection operationally defined (D4.1)
**Target**: Decision D4 / new D4.1
**Resolution**: D4.1 (research-log.md:102-123) defines no-progress detection on the verdict stream that already exists, rather than inventing a new mechanism. I re-read the cited home: `review-iteration.md` §Gate-check verdict handling (lines 134-161) does emit `VERIFIED` / `REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION` per carried finding `id`, and §Gate verification output (lines 106-111) plus the dimensional gate-check budget (lines 113-132) confirm the per-finding verdict shape D4.1 keys on. All three ambiguity axes A1 raised are now pinned: identity = reviewer-assigned `id` (the unit the gate-check already verdicts by, confirmed at 153-154 "carry the finding into the next iteration ... with the same finding ID"); threshold = every carried finding `STILL OPEN` AND zero net clears AND no new fixable finding (one clear or one new fixable = progress); which loop = each uncapped loop (all-level blocker loop + `high` should-fix loop), with `medium`'s should-fix loop moot because its cap-3 bounds it. `REGRESSION` escalating immediately matches the live rule at 155-161 ("A REGRESSION forces the iteration FAIL even if every other verdict is VERIFIED"). The escalation shape (surface surviving findings + verdict history, like cap-3 exhaustion) is buildable from the existing FAIL/escalate path at lines 848-851 and 1085-1112. The decision is now specified enough to derive an artifact from. Finding closed.

### A2 [blocker]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A2 — blast-radius footprint enumerated (Surprises)
**Target**: Assumption (Surprises "Full blast radius", research-log.md:188-226)
**Resolution**: The Surprises entry is retitled "Full blast radius — cap-3-keyed mechanics uncapping breaks" and opens with "The change is **not** a 4-file prose touch" (research-log.md:188-189), directly correcting the undercount A2 flagged. It enumerates the six mechanics I cited in iter-1 — Progress format ≈765, step 4 cross-session cap+resume read ≈832-847, pre-spawn-split ≈837, step 5 ≈848, step 6 ≈875, checklist seed ≈1256 — and for each states how it is restated under the new policy (drop the `/3` denominator; resume reads running count + no-progress/open-findings state, not a remaining-cap count; etc.). I re-verified every cited line against the live `track-code-review.md`: 765, 832-847, 837, 848, 875, 1256 all carry the claimed text, and the step-4 resume genuinely "read[s] the iteration count from the Progress section to determine how many remain" (line 832-833), so the resume-contract-changes-shape point is real and now captured. The footprint no longer sizes the change as a 4-file touch; the derived artifact has the restatement instructions it needs. Finding closed. (One residual: the entry now claims to be exhaustive but still misses four further sites — raised as the new A7 below, a should-fix, not a re-open of A2, because the core 4-file-undercount is corrected.)

### A3 [should-fix]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A3 — medium shared-counter interaction resolved (D3.1)
**Target**: Decision D3 (`medium`) / new D3.1
**Resolution**: D3.1 (research-log.md:75-87) names the single-vs-double-counter question and answers it: keep the single shared counter; should-fix findings stop driving new iterations once 3 iterations have run (gated on `iteration ≤ 3`), while a surviving blocker continues to drive iterations past 3 (bounded by D4.1's no-progress detection); a should-fix that re-surfaces in a post-3 blocker-driven iteration is fixed opportunistically when the implementer is already in that code, else surfaced at completion. I re-verified the constraint it builds on: `track-code-review.md:834` reads "The iteration count is shared across all review dimensions (not independent counters)" — exactly as D3.1 quotes — so the single-counter premise is accurate and the resolution does not require new counter machinery. The 4th-iteration should-fix question I posed in iter-1 now has a defined answer. Finding closed.

### A4 [should-fix]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A4 — Limits carve-out wired, not asserted (D2.1)
**Target**: Decision D2 / new D2.1
**Resolution**: D2.1 (research-log.md:35-49) promotes the iter-1 "maybe" into a committed sub-decision: §Limits keeps cap-3-then-escalate as the default for Phases 2/3A/3B and **gains an explicit one-sentence carve-out** routing a Phase-C reader to the override ("Phase-C track code review overrides this per `track-code-review.md` §Review loop: iteration depth is keyed to the per-track complexity tag, with no fixed cap, terminated by no-progress detection"). The Surprises footprint mirrors this: the `review-iteration.md` §Limits bullet is now marked "**edited** (per D2.1)" (research-log.md:224-226), so §Limits is in the touched-files set, not left out. I re-verified the misrouting risk is real: §Limits' TOC filter is `phases=2,3A,3B,3C` (line 7 and line 35), so it does load in Phase C, and its body (lines 37-38) currently says only "Max 3 ... escalate." The override is now wired at the canonical home rather than asserted only at the override site. Finding closed.

### A5 [should-fix]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A5 — low delta reframed (D3)
**Target**: Decision D3 (`low`)
**Resolution**: D3 is reworded to state the change as the delta from today's behavior (research-log.md:51-61): "`low` already loops on blockers within the cap-3 ceiling per `track-code-review.md:688-690`; the change is removing the cap, not introducing the loop." The `low` bullet now reads "the blocker loop `low` already runs today continues until no blockers remain, with the cap **removed**" and adds the termination-dependency point I asked for: "`low` therefore relies entirely on no-progress detection for termination — it has no should-fix cap as a backstop." I re-verified the live `low` behavior: `track-code-review.md:688-690` confirms "a `REGRESSION` verdict or a `blocker` finding forces the loop to continue regardless of complexity," so the reframing is factually accurate — blocker-looping is pre-existing and cap-removal is the actual edit. Finding closed.

### A6 [should-fix]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A6 — context-pause composition stated (D4.2)
**Target**: Assumption (D4 rationale) / new D4.2
**Resolution**: D4.2 (research-log.md:125-139) acknowledges the existing per-iteration context-consumption check and states the composition on orthogonal axes: the context pause bounds **per-session** burn (unchanged — pauses and resumes next session); no-progress detection bounds **convergence** (escalates when findings stop shrinking across iterations, including across a resume). It resolves the looping-forever-across-sessions concern I raised: "a slow-but-real-progress `high` track hits the context pause, hands off, and continues next session (real progress each iteration ⇒ never escalates); a stuck track escalates on the **first** no-progress iteration regardless of context level," and explicitly states neither mechanism substitutes for the other. I re-verified the cited home: `track-code-review.md:813-831` is the mandatory per-iteration context-consumption check halting at `warning` (≥40%) / `critical` (≥50%) and writing a `mid-phase-handoff.md`, and line 832 confirms cross-session resume re-reads loop state. D4.2 now describes how they coexist; the redundant-or-conflicting-bound risk is closed. Finding closed.

### A7 [should-fix]
**Certificate**: Assumption test — the revised Full-blast-radius entry enumerates every cap-3-keyed mechanic
**Target**: Assumption (Surprises "Full blast radius", research-log.md:188-226) — new finding
**Challenge**: The revised footprint corrects the 4-file undercount (so A2 is closed), but it now presents its enumeration as exhaustive — "Each named below with how it is restated under the new policy" (research-log.md:192-193) — while still omitting at least four cap-3-keyed sites in the same file. Because the entry frames itself as the complete list a derived artifact edits from, the planner/author will edit the six listed sites and leave the four unlisted ones carrying the old cap, reproducing the exact self-contradiction A2 warned about (one paragraph says "uncapped," another still says "of 3" / "after 3 iterations"). The omitted sites, all confirmed against the live file: (1) **line 527** — the §Why-paths cost model "up to ~10 agents **× 3 iterations** per track"; this is the one place the cap functions as a *cost* bound on the whole approach, so uncapping makes the worst-case context-cost framing unbounded and the sentence stale — arguably the most consequential omission. (2) **line 724** — the pre-spawn budget note "when an iteration count is already tight (**2 of 3** used)," a second "of 3" framing distinct from the line-837 pre-spawn-split the footprint does list. (3) **line 1092** — the failure-path Progress entry `Track-level code review **FAILED at iteration N/3**`, a third `/3`-bearing Progress template the footprint's "drop the `/3` denominator" instruction must also reach but does not name. (4) **line 1106** — the failure-handler cross-reference "the existing 'If blockers persist **after 3 iterations**, note them' branch," a second citation of the cap-3 exit separate from the step-5 site at 848 that the footprint does list.
**Evidence**: `grep -nE "3 iterations|N/3|of 3|/3" .claude/workflow/track-code-review.md` returns lines 527, 724, 765, 832, 837, 848, 875, 1092, 1256 — nine sites; the footprint enumerates only 765, 832-847, 837, 848, 875, 1256 (six), plus the cross-file `review-agent-selection.md`, `code-review/SKILL.md`, `review-iteration.md` §Limits sites. Lines 527, 724, 1092 are unlisted, and line 1106's "after 3 iterations" phrasing is a second exit-reference the footprint's single step-5 bullet does not cover. I re-read each: 527 is the cost-model `× 3 iterations` (read in full, it is a worst-case context-budget statement, not a correctness gate, which is why it is the cleanest example of the cap doubling as a cost bound D4 now removes); 724 is "2 of 3 used"; 1092 is the `FAILED at iteration N/3` Progress template; 1106 is the failure-path note. None is named in research-log.md:188-226.
**Proposed fix**: Either (a) extend the Full-blast-radius enumeration with the four omitted sites and their restatements — 527's cost model becomes "up to ~10 agents × N iterations, bounded by no-progress detection and the per-session context pause" (and note that the cap's former role as a hard cost bound is now carried by the context pause, not a fixed count); 724's "2 of 3 used" drops the "of 3"; 1092's `FAILED at iteration N/3` drops the `/3` denominator like 765; 1106's "after 3 iterations, note them" becomes "on no-progress escalation with blockers open, note them" like the step-5 restatement — or (b) reword the entry's framing from an exhaustive list to a representative one ("the cap-3-keyed mechanics include, but the artifact author must grep `3 iterations|N/3|of 3` across the file for the complete set"), so the artifact author is sent to find the rest rather than trusting an incomplete list. Option (a) is preferable — a complete enumeration is what makes the planner size the change correctly, which is the whole point of the A2 fix.

## Evidence base

#### Verdict A1 — no-progress detection operationally defined (D4.1)
- **Prior finding**: A1 [blocker] — no-progress detection undefined; mechanism exists nowhere; ambiguous on identity / threshold / which-loop.
- **Re-test**: Read D4.1 (research-log.md:102-123) against the live verdict stream. `review-iteration.md` §Gate-check verdict handling (134-161) emits `VERIFIED`/`REJECTED`/`MOOT`/`STILL OPEN`/`REGRESSION` per finding `id`; §Gate verification output (106-111) and the dimensional gate-check budget (113-132) confirm the per-finding verdict shape. D4.1 keys no-progress on this stream: identity = `id` (matches "carry ... with the same finding ID" at 153-154); threshold = all carried `STILL OPEN` + zero clears + no new fixable; which loop = each uncapped loop, `medium` should-fix moot under its cap. `REGRESSION` immediate-escalate matches 155-161. Escalation shape reuses the existing FAIL/escalate path (848-851, 1085-1112).
- **Verdict**: VERIFIED. The decision is now derivable; the global grep for `no-progress` still returns zero hits in the live files, which is expected — the artifact has not been authored yet, and D4.1 builds it on infrastructure that does exist.

#### Verdict A2 — blast-radius footprint enumerated (Surprises)
- **Prior finding**: A2 [blocker] — footprint undercounted as a 4-file touch; missed ~6 cap-3-keyed mechanics in `track-code-review.md` §Review loop.
- **Re-test**: The Surprises entry (research-log.md:188-226) now opens "not a 4-file prose touch" and enumerates the six mechanics (765/832-847/837/848/875/1256) each with a restatement. Re-verified every cited line against the live file; step-4 resume does read the cap off the Progress line (832-833), so the resume-contract point is captured. §Limits is now marked **edited**.
- **Verdict**: VERIFIED. Core undercount corrected. Residual incompleteness in the (now exhaustive-claiming) enumeration is a fresh should-fix, A7 — not a re-open.

#### Verdict A3 — medium shared-counter interaction resolved (D3.1)
- **Prior finding**: A3 [should-fix] — `medium` needs two bounds on one shared counter; 4th-iteration should-fix behavior undefined.
- **Re-test**: D3.1 (research-log.md:75-87) keeps the single counter, gates should-fix on `iteration ≤ 3`, lets blockers drive past 3 under D4.1, defines post-3 should-fix as opportunistic-or-surfaced. Constraint re-verified at `track-code-review.md:834` ("shared across all review dimensions (not independent counters)"). No new counter machinery required.
- **Verdict**: VERIFIED.

#### Verdict A4 — Limits carve-out wired, not asserted (D2.1)
- **Prior finding**: A4 [should-fix] — §Limits stays canonical and contradicts the new Phase-C policy; override asserted, not wired.
- **Re-test**: D2.1 (research-log.md:35-49) commits the one-line carve-out into §Limits and the footprint marks §Limits **edited** (224-226). Misrouting risk re-verified: §Limits filter is `phases=2,3A,3B,3C` (7, 35), body says only "Max 3 ... escalate" (37-38), so a Phase-C reader does land on it. Override now announced at the canonical home.
- **Verdict**: VERIFIED.

#### Verdict A5 — low delta reframed (D3)
- **Prior finding**: A5 [should-fix] — "iterate until no blockers" framed as new; the real delta is cap removal; `low` termination depends entirely on no-progress detection.
- **Re-test**: D3 reworded (research-log.md:51-61) to state the delta (cap removal) and the termination dependency. Live `low` re-verified at `track-code-review.md:688-690` — blocker/REGRESSION already forces continuation regardless of complexity, so the reframing is factually correct.
- **Verdict**: VERIFIED.

#### Verdict A6 — context-pause composition stated (D4.2)
- **Prior finding**: A6 [should-fix] — D4 reasons as if no cost bound exists; the per-iteration context pause already bounds per-session burn; composition unaddressed (risk: redundant/conflicting bound, slow-progress track loops across sessions forever).
- **Re-test**: D4.2 (research-log.md:125-139) states orthogonal axes (per-session burn vs convergence), resolves the slow-progress-forever case (real progress ⇒ never escalates; stuck ⇒ escalates on first no-progress iteration regardless of context level), and states neither substitutes for the other. Re-verified the context check at `track-code-review.md:813-831` and the resume re-read at 832.
- **Verdict**: VERIFIED.

#### Assumption test: the revised Full-blast-radius entry enumerates every cap-3-keyed mechanic
- **Claim**: The revised Surprises entry ("Each named below with how it is restated", research-log.md:192-193) is the complete list of cap-3-keyed mechanics a derived artifact must edit.
- **Stress scenario**: A planner/author edits exactly the listed six in-file sites plus the three cross-file sites, trusting the enumeration, and ships.
- **Code evidence**: `grep -nE "3 iterations|N/3|of 3|/3" track-code-review.md` → 527, 724, 765, 832, 837, 848, 875, 1092, 1256. Footprint lists 765/832-847/837/848/875/1256 only. Omitted: 527 (cost-model `× 3 iterations`, the cap-as-cost-bound), 724 ("2 of 3 used"), 1092 (`FAILED at iteration N/3`), plus the line-1106 "after 3 iterations, note them" failure-path cross-reference separate from the listed step-5 site at 848. Each re-read in full to confirm it is genuinely cap-keyed and load-bearing for the uncapping.
- **Verdict**: BREAKS. The exhaustive-claiming enumeration still omits four sites, reproducing A2's self-contradiction failure mode at lower volume. → A7 (should-fix).

---

**Reference-accuracy note.** This is a workflow-prose review, so references were verified as workflow file paths / `§`-anchors / line numbers via grep and Read against the live files (per this prompt's workflow-machinery criteria), not as Java FQNs via PSI. mcp-steroid is reachable but `steroid_list_projects` reports the open projects as `transactional-schema` and `analyzed-expression`, neither matching this working tree — a cwd mismatch. That mismatch is not load-bearing here: PSI is the wrong tool for prose-reference checking, and every line citation above was confirmed by direct Read of the live `track-code-review.md` and `review-iteration.md`.
