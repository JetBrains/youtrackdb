# design-author params — phase1-creation round 3 (re-ground flagged passages only)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/research-log.md
- round: 3

## flagged_passages (round-2 mechanical + readability findings — fix in place, prose only, do NOT change Decision-record / Invariant substance)

1. **MECHANICAL `overview-length` (should-fix), Overview @ design.md:4+** — the Overview is over its ~40-line cap. The main bloat is the numbered 1–5 `roster_scan` bug trace that round 2 added to the Overview. That detailed trace is **redundant** with `### Worked trace of the bug` (it already carries the full interleaving). **Fix:** in the Overview, replace the numbered 1–5 trace with a ONE-LINE summary (e.g. "a hard-wrapped roster step is miscounted, so a finished track mis-routes to `steps-partial`") and let `### Worked trace of the bug` own the detail. This trim is the primary lever to get the Overview back under cap.

2. **F1 hard-to-read, design.md:41-46** — the fallback sentence is now 6 lines with a dash-parenthetical gloss of `determine_state` that itself nests a parenthetical. Split into three short sentences: (a) state the two fallback cases the ledger cannot cover; (b) gloss `determine_state` in its own sentence (the precheck's top-level resume resolver — prefers `determine_state_from_ledger`, else walks the plan checkboxes, so it still runs when there is no ledger); (c) state that the fallback gets the wrap-tolerant fix so it is correct when it runs.

3. **F2 hard-to-read, design.md:159-163** — a multi-token bash literal (`[ -n "$LEDGER_SUBSTATE" ] && line="$line substate=$LEDGER_SUBSTATE"`) is wedged into the sentence as the object of "gains". Move the literal to its own fenced code line; let the prose say `append_ledger` gains a pre-`categories` append line, shown below.

4. **F3 over-dense, design.md:65-72** — the four sub-state questions ("has decomposition finished, are steps still running, is code review pending, is the track ready to close") are comma-chained into one prose sentence. Break them onto separate lines or fold them into the four-row table that immediately follows (they map 1:1 to the four slugs). Also add a half-clause connecting why phase `C` covers Phase B execution — the enum is `{0, A, C, D, Done}` with no `B`, so Phase B work is recorded under phase `C` (the doc states the no-`B` fact at ~line 63 but does not connect it here).
