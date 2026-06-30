<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 0, suggestion: 1, verified: 7, rejected: 0}
index:
  - {id: A1, sev: blocker,    loc: "research-log.md:102", anchor: "### A1 ", cert: "Verdict A1", basis: "VERIFIED (re-confirmed closed) — D4.1 unchanged since iter2; no-progress defined on the existing gate-check verdict stream"}
  - {id: A2, sev: blocker,    loc: "research-log.md:188", anchor: "### A2 ", cert: "Verdict A2", basis: "VERIFIED (re-confirmed closed) — the 4-file undercount is corrected; the major-sites enumeration plus grep-as-source-of-truth supply the restatement instructions"}
  - {id: A3, sev: should-fix, loc: "research-log.md:75",  anchor: "### A3 ", cert: "Verdict A3", basis: "VERIFIED (re-confirmed closed) — D3.1 unchanged; single shared counter, should-fix gated on iteration<=3, blockers drive past 3"}
  - {id: A4, sev: should-fix, loc: "research-log.md:35",  anchor: "### A4 ", cert: "Verdict A4", basis: "VERIFIED (re-confirmed closed) — D2.1 wires the carve-out into Limits; footprint marks Limits edited"}
  - {id: A5, sev: should-fix, loc: "research-log.md:51",  anchor: "### A5 ", cert: "Verdict A5", basis: "VERIFIED (re-confirmed closed) — D3 reframed as the cap-removal delta; low relies on no-progress detection"}
  - {id: A6, sev: should-fix, loc: "research-log.md:125", anchor: "### A6 ", cert: "Verdict A6", basis: "VERIFIED (re-confirmed closed) — D4.2 states orthogonal axes (per-session burn vs convergence)"}
  - {id: A7, sev: should-fix, loc: "research-log.md:192", anchor: "### A7 ", cert: "Verdict A7", basis: "VERIFIED — exhaustive framing dropped ('not exhaustive (A7)'), grep cited as source of truth, all current hits listed, cost-model (491/527) and failure/budget (724/1092/1106) bullets added"}
  - {id: A8, sev: suggestion, loc: "research-log.md:195", anchor: "### A8 ", cert: "Assumption test: the parenthetical hit list equals the cited grep output", basis: "the inline 'full hit set' list includes 491 and 685, which the cited grep regex does not return (both say 'three iterations' spelled out); the list and the grep command it claims to be disagree"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
cert_index:
  - {id: "Verdict A1", verdict: VERIFIED, anchor: "#### Verdict A1 — no-progress detection still operationally defined (D4.1)"}
  - {id: "Verdict A2", verdict: VERIFIED, anchor: "#### Verdict A2 — blast-radius footprint still corrected (Surprises)"}
  - {id: "Verdict A3", verdict: VERIFIED, anchor: "#### Verdict A3 — medium shared-counter interaction still resolved (D3.1)"}
  - {id: "Verdict A4", verdict: VERIFIED, anchor: "#### Verdict A4 — Limits carve-out still wired (D2.1)"}
  - {id: "Verdict A5", verdict: VERIFIED, anchor: "#### Verdict A5 — low delta still reframed (D3)"}
  - {id: "Verdict A6", verdict: VERIFIED, anchor: "#### Verdict A6 — context-pause composition still stated (D4.2)"}
  - {id: "Verdict A7", verdict: VERIFIED, anchor: "#### Verdict A7 — exhaustive framing dropped; grep made the source of truth; omitted sites added"}
  - {id: "Assumption test: the parenthetical hit list equals the cited grep output", verdict: FRAGILE, anchor: "#### Assumption test: the inline 'full hit set' line equals the output of the grep it cites"}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [blocker]
**Verdict**: VERIFIED (finding remains closed)
**Certificate**: Verdict A1 — no-progress detection still operationally defined (D4.1)
**Target**: Decision D4 / D4.1
**Resolution**: D4.1 (research-log.md:102-123) is unchanged from the iter2-closed state. It still defines no-progress detection on the verdict stream `review-iteration.md` §Gate-check verdict handling already emits per carried finding, pinning all three axes A1 raised: identity = reviewer `id`, threshold = every carried finding `STILL OPEN` AND zero net clears AND no new fixable finding, which-loop = each uncapped loop (`medium` should-fix moot under its cap until a blocker carries it past 3), with `REGRESSION` escalating immediately. The iter3 revision touched only the Surprises footprint (A7), not D4.1, so the iter2 verdict stands. Remains closed.

### A2 [blocker]
**Verdict**: VERIFIED (finding remains closed)
**Certificate**: Verdict A2 — blast-radius footprint still corrected (Surprises)
**Target**: Assumption (Surprises "Full blast radius", research-log.md:188-238)
**Resolution**: The core A2 defect — the 4-file undercount — stays corrected. The entry still opens "The change is **not** a 4-file prose touch" (research-log.md:189) and now enumerates the major in-file mechanics (dial site ≈685, Progress ≈765, step 4 ≈832-847, pre-spawn-split ≈837, step 5 ≈848, step 6 ≈875, checklist seed ≈1256) plus the three cross-file sites, each with a restatement. The A7 revision strengthened this further by adding the cost-model and failure/budget bullets. The footprint no longer sizes the change as a 4-file touch and the derived artifact has its restatement instructions. Remains closed. (The residual is now about the *accuracy* of the parenthetical hit list, not the *completeness* of the major-sites coverage — raised as A8 below, a suggestion.)

### A3 [should-fix]
**Verdict**: VERIFIED (finding remains closed)
**Certificate**: Verdict A3 — medium shared-counter interaction still resolved (D3.1)
**Target**: Decision D3 (`medium`) / D3.1
**Resolution**: D3.1 (research-log.md:75-87) is unchanged from iter2: single shared counter; should-fix gated on `iteration ≤ 3`; a surviving blocker drives iterations past 3 (bounded by D4.1); post-3 should-fix fixed opportunistically or surfaced at completion. The constraint it builds on, `track-code-review.md:834` "shared across all review dimensions (not independent counters)", re-confirmed present. The iter3 edit did not touch D3.1. Remains closed.

### A4 [should-fix]
**Verdict**: VERIFIED (finding remains closed)
**Certificate**: Verdict A4 — Limits carve-out still wired (D2.1)
**Target**: Decision D2 / D2.1
**Resolution**: D2.1 (research-log.md:35-49) is unchanged: §Limits keeps cap-3-then-escalate for Phases 2/3A/3B and gains the explicit one-sentence Phase-C carve-out routing a reader to the override. The footprint still marks §Limits "**edited** (per D2.1)" (research-log.md:236-238). The misrouting risk re-verified — §Limits' TOC filter `phases=2,3A,3B,3C` does load in Phase C (`review-iteration.md:35`). The iter3 edit did not touch D2.1. Remains closed.

### A5 [should-fix]
**Verdict**: VERIFIED (finding remains closed)
**Certificate**: Verdict A5 — low delta still reframed (D3)
**Target**: Decision D3 (`low`)
**Resolution**: D3 (research-log.md:51-61) still states the change as the cap-removal delta — "`low` already loops on blockers within the cap-3 ceiling per `track-code-review.md:688-690`; the change is removing the cap, not introducing the loop" — and keeps the termination-dependency point ("`low` therefore relies entirely on no-progress detection for termination"). Live `low` re-confirmed at `track-code-review.md:685-695`: "a `REGRESSION` verdict or a `blocker` finding forces the loop to continue regardless of complexity." The iter3 edit did not touch D3. Remains closed.

### A6 [should-fix]
**Verdict**: VERIFIED (finding remains closed)
**Certificate**: Verdict A6 — context-pause composition still stated (D4.2)
**Target**: Assumption (D4 rationale) / D4.2
**Resolution**: D4.2 (research-log.md:125-139) is unchanged: orthogonal axes (per-session burn via the context pause; convergence via no-progress detection), the slow-progress-forever case resolved (real progress ⇒ never escalates; stuck ⇒ escalates on first no-progress iteration regardless of context level), neither mechanism substitutes for the other. Cited home `track-code-review.md:813-831` (context check) and :832 (resume re-read) re-confirmed present. The iter3 edit did not touch D4.2. Remains closed.

### A7 [should-fix]
**Verdict**: VERIFIED (finding closed)
**Certificate**: Verdict A7 — exhaustive framing dropped; grep made the source of truth; omitted sites added
**Target**: Assumption (Surprises "Full blast radius", research-log.md:188-238)
**Resolution**: All three remediation items the gate asked for are present in the revised entry, and the core A7 defect — an exhaustive-claiming enumeration silently omitting cap-3 sites — is genuinely closed:
1. **Exhaustive framing dropped.** The iter2 entry claimed "Each named below with how it is restated." The revision now reads "the list below is the major sites, **not exhaustive** (A7)" (research-log.md:194-195) and the bullet header is "The major sites and how each is restated" (research-log.md:197). The self-described-complete framing that produced A7's failure mode is gone.
2. **Grep cited as the source of truth.** research-log.md:192-194 now states "The complete set is the output of `grep -nE '3 iterations|N/3|/3|of 3' .claude/workflow/track-code-review.md` — the author runs that grep and restates every hit." I re-ran that exact command: it returns `527, 724, 765, 832, 837, 848, 875, 1092, 1106, 1256`. So the artifact author is sent to a live, deterministic source rather than trusting a static list — the structural fix A7's proposed-fix option (b) recommended, layered on top of option (a)'s enumeration.
3. **Every current hit listed, with the previously-omitted sites now carrying bullets.** The four sites A7 flagged as omitted are now present: **527** in the Cost-models bullet ("× 3 iterations per track ... use the cap as a **cost bound** the change removes; reword to a representative/typical count", research-log.md:221-224); **724** ("2 of 3 used"), **1092** (`FAILED at iteration N/3`), and **1106** ("If blockers persist after 3 iterations, note them") all in the Failure/budget-mentions bullet (research-log.md:225-228), restated against the no-progress exit and the dropped `/3` denominator.
The exhaustive-but-incomplete contradiction A7 raised cannot recur: the entry no longer claims completeness and routes the author to the grep. Finding closed.

### A8 [suggestion]
**Certificate**: Assumption test — the inline "full hit set" line equals the output of the grep it cites
**Target**: Assumption (Surprises "Full blast radius", research-log.md:195-197) — new finding
**Challenge**: The A7 revision introduced an inline list it labels "The full hit set as of this writing: lines 491, 527, 685 (the dial site), 724, 765, 832, 837, 848, 875, 1092, 1106, 1256" (research-log.md:195-197), placed immediately after the sentence that names the grep as the complete-set source. That juxtaposition reads as "this list is the grep output," but the cited grep does not return two of the listed lines. The actual `grep -nE '3 iterations|N/3|/3|of 3' .claude/workflow/track-code-review.md` returns ten lines — `527, 724, 765, 832, 837, 848, 875, 1092, 1106, 1256` — and **omits 491 and 685**, because both spell the count as "three iterations" rather than the digit `3` (line 491 "× three iterations = eighteen sub-agent spawns"; line 685 "iterate to convergence within the cap-3 ceiling (run the full three iterations...)"). So the static "full hit set" line is internally inconsistent with the grep command it sits beside: it adds two non-matching lines and presents them as that command's output. This is the residual, much-reduced form of A7's failure mode — a static list that disagrees with its stated source — not a re-open of A7, because the entry already says "not exhaustive" and routes the author to re-run the grep (so the static list does not gate the artifact; the author edits from the live grep output, and 491/685 are independently covered by the Cost-models and dial-site bullets, which catch the spelled-out "three iterations" hits the regex misses).
**Evidence**: `grep -nE '3 iterations|N/3|/3|of 3' .claude/workflow/track-code-review.md` → `527 724 765 832 837 848 875 1092 1106 1256` (re-run this session; 491 and 685 absent). `sed -n '491p'` and `sed -n '685p'` both contain "three iterations" (spelled-out word, not the digit), confirming they fall outside the cited regex. research-log.md:195-197 lists 491 and 685 inside the "full hit set" sentence anchored to that grep.
**Proposed fix**: Either (a) relabel the inline list so it is not presented as the grep output — e.g., "the cap-keyed sites, including the two spelled-out 'three iterations' hits at 491 and 685 that the digit-only grep misses, are: ..." (this turns the inconsistency into a deliberate superset and documents *why* the author must not rely on the grep alone); or (b) widen the cited grep to also catch the spelled-out form (`grep -nE '3 iterations|three iterations|N/3|/3|of 3'`) so the command and the list agree and the author's re-run actually surfaces 491 and 685. Option (b) is preferable: it makes the "run that grep and restate every hit" instruction self-sufficient, since the spelled-out cost-model site at 491 is exactly the cap-as-cost-bound case the change most needs the author to find. Left as a suggestion because the entry's "not exhaustive" framing plus the explicit Cost-models/dial-site bullets already prevent 491/685 from being dropped by a derived artifact; the cost is a reader briefly trusting a list that does not match its cited command.

## Evidence base

#### Verdict A1 — no-progress detection still operationally defined (D4.1)
- **Prior finding**: A1 [blocker], VERIFIED at iter2 — D4.1 defines no-progress on the existing gate-check verdict stream (identity / threshold / which-loop all pinned).
- **Re-test**: Read D4.1 (research-log.md:102-123) in the current log. Text is byte-identical to the iter2-closed state; the iter3 revision edited only the Surprises footprint (A7). The verdict-stream home `review-iteration.md` §Gate-check verdict handling still backs the definition.
- **Verdict**: VERIFIED. Remains closed; no regression.

#### Verdict A2 — blast-radius footprint still corrected (Surprises)
- **Prior finding**: A2 [blocker], VERIFIED at iter2 — the 4-file undercount corrected; major mechanics enumerated.
- **Re-test**: The entry still opens "not a 4-file prose touch" (research-log.md:189) and the A7 revision *expanded* the enumeration (added cost-model and failure/budget bullets). Major-sites coverage is broader than at iter2, not narrower.
- **Verdict**: VERIFIED. Core undercount stays corrected. Residual is parenthetical-list accuracy (A8, suggestion), not coverage completeness.

#### Verdict A3 — medium shared-counter interaction still resolved (D3.1)
- **Prior finding**: A3 [should-fix], VERIFIED at iter2.
- **Re-test**: D3.1 (research-log.md:75-87) unchanged; `track-code-review.md:834` single-counter constraint re-confirmed.
- **Verdict**: VERIFIED. Remains closed.

#### Verdict A4 — Limits carve-out still wired (D2.1)
- **Prior finding**: A4 [should-fix], VERIFIED at iter2.
- **Re-test**: D2.1 (research-log.md:35-49) unchanged; footprint still marks §Limits "edited" (236-238); `review-iteration.md:35` phase filter `2,3A,3B,3C` re-confirmed (loads in Phase C).
- **Verdict**: VERIFIED. Remains closed.

#### Verdict A5 — low delta still reframed (D3)
- **Prior finding**: A5 [should-fix], VERIFIED at iter2.
- **Re-test**: D3 (research-log.md:51-61) unchanged; live `low` blocker-loop behavior re-confirmed at `track-code-review.md:685-695`.
- **Verdict**: VERIFIED. Remains closed.

#### Verdict A6 — context-pause composition still stated (D4.2)
- **Prior finding**: A6 [should-fix], VERIFIED at iter2.
- **Re-test**: D4.2 (research-log.md:125-139) unchanged; `track-code-review.md:813-831` context check and :832 resume re-read re-confirmed.
- **Verdict**: VERIFIED. Remains closed.

#### Verdict A7 — exhaustive framing dropped; grep made the source of truth; omitted sites added
- **Prior finding**: A7 [should-fix], the sole open finding from iter2 — the revised footprint claimed to be exhaustive while omitting cap-3 sites (491/527/724/1092/1106), reproducing A2's failure mode at lower volume.
- **Re-test**: Read the revised entry (research-log.md:188-238) against the live `track-code-review.md` and a re-run of the cited grep.
  1. Framing: now "the list below is the major sites, **not exhaustive** (A7)" (194-195) and "The major sites and how each is restated" (197) — exhaustive claim removed.
  2. Source of truth: grep `-nE '3 iterations|N/3|/3|of 3'` cited as "the complete set" (192-194); re-ran it this session → `527, 724, 765, 832, 837, 848, 875, 1092, 1106, 1256`.
  3. Omitted sites: 527 now in Cost-models bullet (221-224, including the cap-as-cost-bound restatement A7 called the most consequential); 724/1092/1106 now in Failure/budget bullet (225-228).
- **Verdict**: VERIFIED. The exhaustive-but-incomplete contradiction is structurally precluded (no completeness claim + grep-as-source-of-truth). A7's three required remediation items are all present. Finding closed. One narrower residual — the static parenthetical list disagrees with the cited grep on two lines — is a fresh, lower-severity issue (A8), not an A7 re-open.

#### Assumption test: the inline "full hit set" line equals the output of the grep it cites
- **Claim**: The "full hit set as of this writing: lines 491, 527, 685 ..., 1106, 1256" (research-log.md:195-197), sitting one clause after "the output of `grep -nE '3 iterations|N/3|/3|of 3'`", is that grep's output.
- **Stress scenario**: A reader treats the inline list as authoritative and trusts that re-running the cited grep reproduces it.
- **Code evidence**: `grep -nE '3 iterations|N/3|/3|of 3' .claude/workflow/track-code-review.md` → `527 724 765 832 837 848 875 1092 1106 1256` (10 lines). The list adds 491 and 685, which the regex does not match — `sed -n '491p'` is "× three iterations = eighteen sub-agent spawns" and `sed -n '685p'` is "the full three iterations" / "cap-3 ceiling"; both spell the count, so neither matches `3 iterations`/`/3`/`of 3`. The list and its cited command disagree by two lines.
- **Verdict**: FRAGILE. The list is a deliberate-looking superset of the grep but is presented as the grep output, so it is internally inconsistent with its own cited source. Low impact: the "not exhaustive" framing plus the Cost-models (491) and dial-site (685) bullets already prevent the spelled-out sites from being dropped, so a derived artifact is not harmed — only a reader is briefly misled about what the grep returns. → A8 (suggestion).

---

**Reference-accuracy note.** This is a workflow-prose review, so references were verified as workflow file paths / `§`-anchors / line numbers via grep, `sed`, and Read against the live `.claude/workflow/track-code-review.md`, `review-iteration.md`, and the revised `research-log.md` (per this prompt's workflow-machinery criteria), not as Java FQNs via PSI. The cited grep was re-run this session and its output cross-checked line-by-line against the log's enumeration; lines 491 and 685 were individually inspected to confirm they fall outside the regex. PSI is the wrong tool for prose-reference checking and was not needed.
