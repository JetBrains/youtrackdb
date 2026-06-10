# Workflow cost-analysis tools

Two transcript analyzers built for the 2026-06-09 workflow-doc-cost study (see the
`workflow-doc-cost-findings` memory). Both read Claude Code JSONL transcripts under
`~/.claude/projects/<encoded-path>/` and reuse `session-stats.py` for live pricing
and `(message.id, requestId)` dedup. Opus 4.8 rates: read $0.50, write-5m $6.25,
output $25 per MTok.

Requires `tiktoken` only if you re-derive calibration factors (not for normal runs):
`pip install tiktoken`.

## wf-doc-cost-analyzer.py — workflow-doc cost (orchestrator + all sub-agents)

Per transcript, detects workflow-doc Reads (process: `.claude/{workflow,agents,skills,docs,output-styles}`;
artifact: `docs/adr/**`), maintains a resident set, and attributes three components:
initial cache-write, warm re-read tail, cold TTL re-write (continuous `rewrite_frac`
model, not a binary threshold). Sizes by char/4 × 1.08 (tiktoken-anchored, NOT the
~2× the original handoff guessed). Sums orchestrator + every `subagents/agent-*.jsonl`.

```bash
# detail for specific sessions (session-id stems)
python3 wf-doc-cost-analyzer.py <sid> [<sid> ...]
# one-line-per-session table + per-phase rollup
python3 wf-doc-cost-analyzer.py --summary <sid> ...
# point at a different project's transcripts
WF_PROJECT_DIR=/home/coder/.claude/projects/<encoded-path> python3 wf-doc-cost-analyzer.py --summary <sid> ...
```

## wf-orch-distribution.py — full orchestrator cost distribution

Distributes each turn's REAL read/write/output cost across resident prefix buckets
by their share of the real prefix, so the modeled total equals the true bill. Buckets:
FLOOR (cold-start fixed prefix), INJECTED (harness/session context re-sent per turn,
not itemized in the transcript), OUTPUT, workflow process/artifact docs, sub-agent
outputs, Task prompts, orchestrator generation, tool output, code reads. Orch-only
(excludes sub-agent transcripts). Prints a per-phase % table + rollup.

```bash
python3 wf-orch-distribution.py                 # default open-speedup 12-session set
python3 wf-orch-distribution.py <sid> [<sid> ...]   # custom set (unknown sids -> "other" phase)
WF_PROJECT_DIR=... python3 wf-orch-distribution.py <sid> ...
```

## wf-prefix-growth.py — per-turn prefix growth and read-term exponent

Built for the workflow economic model (open question #1: is the read cost
super-linear in turns?). Per session, extracts the deduped per-turn
`cache_read` sequence (the resident prefix re-read each turn), fits prefix
against turn index, and separately counts TTL re-warms (`cache_read` dip +
`cache_write` spike) and true compaction (`real_prefix` sustained drop). Fits
the cross-session power law `read$ ∝ T^alpha`. Pure stdlib.

```bash
python3 wf-prefix-growth.py                 # default 12-session study set
python3 wf-prefix-growth.py <sid> ...       # custom set
WF_PROJECT_DIR=... python3 wf-prefix-growth.py <sid> ...
```

Finding: `read$ ∝ T^1.41` (R²=0.85), `total cost ∝ T^1.07`. The prefix grows
~linearly with turn index within a session (slope 1900–8500 tok/turn, R²
0.56–0.86; 57–74% of the read integral from the growth term in
exec-tracks/create-plan, 28–34% in migrate). No compaction fires — `final/peak
real_prefix = 1.00` for all 12 sessions, so the prefix peaks at the last turn
and the read integral is a live, uncapped two-term `READ·(a·T + ½·b·T²)`. The
`T^1.41` is a local slope at T<90, not a ceiling; it climbs toward 2 in longer
sessions. The 1–7 per-session "resets" are 5m-TTL cache re-warms (cold-rewrite,
YTDB-1097), not content reduction. Aggregate bill: 42% read / 36% write / 21%
output / 0.5% input. Reproduces $151.89 orchestrator total exactly.

## Headline result (open-speedup, 12 sessions)

Orchestrator cost ≈ 55% system/harness overhead (floor+injected, re-read every turn),
21% output, 13% workflow docs, 5% delegation. Workflow docs ≈ 11% of *whole-session*
cost; their re-read tail is ~53% of doc cost. Biggest levers = prefix-shrink
(YTDB-1094 tool-schema trim, YTDB-1097 cold-rewrite), not doc trimming.

## Caveats

- char/4 → real factor is 1.08 for prose docs; mixed tool/JSON content ~1.1-1.14.
  The FLOOR/INJECTED split is approximate (transcript itemizes only ~⅔ of the prefix);
  their sum is robust.
- migrate-workflow undercounts docs — it manipulates `_workflow/` artifacts via
  git/Bash, not the Read tool, so those bytes are invisible to a Read-based detector.
- Flat Opus 4.8 rates; no 1M-context (>200K) premium applied, matching session-stats.py.
