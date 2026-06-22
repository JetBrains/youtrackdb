<!--
review: consistency-gate-verification
iter: 1
role: reviewer-plan
phase: 2
tier: minimal
overall: PASS
verdicts:
  - id: CR1
    prior_sev: should-fix
    verdict: VERIFIED
    loc: "track-1.md:101; research-log.md:84; research-log.md:176"
    cert: "s17=workflow-modifying now in all three sites; ledger + conventions.md §1.7(k) canonical"
  - id: CR2
    prior_sev: suggestion
    verdict: VERIFIED
    loc: "track-1.md:213-214; track-1.md:302"
    cert: "call lines (:2124)/(:2126) added; precheck :2124=detect_drift, :2126=determine_state, ordering holds"
findings: 0
blockers: 0
should_fix: 0
suggestion: 0
evidence_base: "2 verification certificates plus a regression scan against live files. CR1: all three flagged sites (track-1.md:101 D3 heading, research-log.md:84 D3 heading, research-log.md:176 OQ1) now carry the canonical s17=workflow-modifying; conventions.md:1340-1341 confirms the field has exactly two values (workflow-modifying / opt-out) with no `staged` token; phase-ledger.md:1 already carries s17=workflow-modifying. Regression grep for s17=staged across the live _workflow tree returns only the two out-of-scope historical review files (consistency-iter1.md, research-log-adversarial-iter1.md) — zero live track-1.md/research-log.md hits. Both D3 headings still parse as ###; decision intent (stage, not opt-out) preserved. CR2: workflow-startup-precheck.sh:2121=`case`, :2123=detect_divergence, :2124=detect_drift, :2125=scan_handoffs, :2126=determine_state — the added (:2124)/(:2126) citations land on the calls and the detect_drift-before-determine_state ordering is true (2124<2126). consistency-iter1.md new-finding anchor grep = 2 (unaffected). No new findings."
-->

# Consistency gate verification — Track 1 (drift-walk-fix), iteration 1

Verdict-producer pass over the 2 mechanical findings from the iteration-1 consistency review (both ACCEPTED, fixes applied by the orchestrator). Both fixes VERIFIED; regression scan clean; no new findings. Overall PASS. Tier `minimal` (ledger `tier=minimal`): no `design.md`, no `implementation-plan.md`; reads resolve to live files (no `_workflow/staged-workflow/` exists). Bash/python + Markdown change — grep/Read authoritative, no reference-accuracy caveat applies.

## Verdicts

#### Verify CR1: D3 staging-mode token mis-rendered as `s17=staged`
- **Original issue**: The track DR D3 (and the research log's D3 heading + OQ1) rendered the §1.7(b) staging mode as the non-canonical token `s17=staged`. `conventions.md §1.7(k)` (`:1340-1341`) defines the `s17` field with exactly two mutually-exclusive values — `workflow-modifying` (stage) and `opt-out` (edit live); there is no `staged` token. The on-disk ledger was already correct.
- **Fix applied**: Changed the token to the canonical `s17=workflow-modifying` in all three flagged sites and reworded the headings to "staging mode = stage (`s17=workflow-modifying`), not the §1.7(k) opt-out", preserving the decision intent (stage, not opt-out).
- **Re-check**:
  - Search/trace performed: `grep -rn 's17=staged'` and `grep -rn 's17=workflow-modifying'` on `track-1.md` and `research-log.md`; Read of `conventions.md:1336-1346`; `head -1 phase-ledger.md`. Tool: grep + Read (authoritative for Markdown/bash; IDE PSI not applicable).
  - Code location: `track-1.md:101`, `research-log.md:84`, `research-log.md:176` (the three sites); canonical source `conventions.md:1340-1341`; ledger `phase-ledger.md:1`.
  - Current state: All three sites now read `s17=workflow-modifying`. `track-1.md:101` and `research-log.md:84` headings: "### D3: §1.7 staging mode = stage (`s17=workflow-modifying`), not the §1.7(k) opt-out". `research-log.md:176`: "confirmed `s17=workflow-modifying` at the Step-4 tier/mode confirmation." `conventions.md:1340-1341` confirms the two-value field (`workflow-modifying` / `opt-out`) with no `staged` token. `phase-ledger.md:1` carries `s17=workflow-modifying` — the doc now matches the ledger.
- **Regression check**: Grep for `s17=staged` across the whole live `_workflow/` tree returns only the two out-of-scope historical review files (`plan/track-1/reviews/consistency-iter1.md`, `reviews/research-log-adversarial-iter1.md`), which correctly preserve the original token as the record of what was evaluated. Zero live `s17=staged` in `track-1.md` or `research-log.md`. Both D3 headings still parse as level-3 (`^### D3:` matches at both lines). Decision intent (stage, not opt-out) unchanged; descriptive "staged copy" / "run staged" adjective uses left untouched as intended. Clean.
- **Verdict**: VERIFIED

#### Verify CR2: `--mode full` dispatch ordering cited the `case` head, not the calls
- **Original issue**: The ordering citation pointed only at `:2121` (the `case "$MODE" in` head), whereas the calls whose ordering the claim is about are `detect_drift` at `:2124` and `determine_state` at `:2126`. The ordering claim itself (detect_drift before determine_state) was true; only the line anchor was imprecise.
- **Fix applied**: Added the call-line citations `(:2124)` for `detect_drift` and `(:2126)` for `determine_state` at `track-1.md:213-214` and `track-1.md:302`.
- **Re-check**:
  - Search/trace performed: Read of `track-1.md:208-216` and `track-1.md:298-305`; Read of `workflow-startup-precheck.sh:2119-2130`. Tool: Read (authoritative for bash).
  - Code location: `track-1.md:213-214` ("The `--mode full` dispatch (`:2121`) runs `detect_drift` (`:2124`) **before** `determine_state` (`:2126`)") and `track-1.md:302` ("the dispatch already runs `detect_drift` (`:2124`) before `determine_state` (`:2126`)").
  - Current state: `workflow-startup-precheck.sh:2121` = `case "$MODE" in`; `:2122` = `full)`; `:2123` = `detect_divergence`; `:2124` = `detect_drift`; `:2125` = `scan_handoffs`; `:2126` = `determine_state`. The new citations land exactly on the two calls; the dispatch-block anchor `:2121` is retained as the construct reference. The ordering claim holds (2124 < 2126).
- **Regression check**: Checked the surrounding Interfaces and Out-of-scope sections — the added parenthetical line refs are textually local and shift no other reference. `:2121` is still present once as the dispatch-block anchor (intended). Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure-verdict pass, no new inconsistency surfaced by the re-scan)
