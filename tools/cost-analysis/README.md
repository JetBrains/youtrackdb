# Claude-session cost-analysis tools

Six standalone Python analyzers that decompose what a Claude Code session
costs and why, from the JSONL transcripts under `~/.claude/projects/`. Built
on the 2026-06-09/10 workflow cost study (12 sessions, $151.89 orchestrator),
but generic: point any of them at any project's sessions. Pure stdlib; no
dependencies (`tiktoken` only to re-derive calibration factors).

## Pointing a tool at your sessions

Every tool resolves its transcript directory the same way:

1. `WF_PROJECT_DIR=/home/coder/.claude/projects/<encoded-path>` wins if set.
2. Otherwise, if the requested session IDs all exist in the default study set
   (the open-speedup 12-session validation corpus), that is used — so a bare
   run reproduces the published numbers.
3. Otherwise the current project's own transcript dir, derived from the cwd
   (Claude Code encodes `/a/b/c` as `-a-b-c`). So `cd <your-worktree>` and
   passing your own session-ID stems just works.

```bash
python3 <tool>.py                  # default: the 12-session study set
python3 <tool>.py <sid> [<sid>..]  # your sessions (8-char stems are enough for phase mapping)
WF_PROJECT_DIR=... python3 <tool>.py <sid> ...   # any other project
```

Session IDs not in the study's phase map roll up under the `other` phase.
Pricing is flat Opus 4.8: read $0.50, write-5m $6.25, output $25, input $5
per MTok, matching `session-stats.py` (no 1M-context premium).

## The tools

| Tool | Question it answers | Scope |
|---|---|---|
| `wf-content-type-cost.py` | What content type does each dollar pay for (write/read/output split)? | orch |
| `wf-orch-distribution.py` | How is the bill distributed across prefix buckets (floor/docs/output/...)? | orch |
| `wf-doc-cost-analyzer.py` | What do workflow docs cost (initial write / warm re-read / cold re-write)? | orch + sub-agents |
| `wf-prefix-growth.py` | How does the prefix grow with turns; is the read term super-linear? | orch |
| `wf-lever-map.py` | Which cost coefficient does each optimization lever move, worth how much? | orch |
| `wf-doc-read-discipline.py` | How much does partial-reading docs save; where is it violated? | orch + sub-agents |

### wf-content-type-cost.py

Attributes each turn's real cache-write, cache-read, and output cost to a
content type (FLOOR, model reasoning incl. retained thinking, workflow
process/artifact docs, sub-agent outputs, Task prompts, tool output, code
reads, residual), so the modeled total equals the true bill. The
"model reasoning" row separates generation (output column) from the cost of
re-reading retained thinking on later turns (write/read columns).

### wf-orch-distribution.py

Per-phase percentage distribution of the orchestrator bill across resident
prefix buckets, summing to the true bill. Splits FLOOR (cold-start fixed
prefix) from INJECTED (per-turn harness context). Imports the repo's
`.claude/scripts/session-stats.py` (resolved relative to this file) for
pricing/dedup conventions.

### wf-doc-cost-analyzer.py

Workflow-doc cost across the orchestrator and every
`<sid>/subagents/agent-*.jsonl`. Maintains a resident doc set and attributes
three components: initial cache-write, warm re-read tail, and cold TTL
re-write via a continuous `rewrite_frac = max(0, write − prefix_growth) /
prev_prefix` model (catches partial TTL expiries a binary threshold misses).
`--summary` prints one line per session plus a per-phase rollup.

### wf-prefix-growth.py

Extracts the deduped per-turn `cache_read` sequence (the resident prefix
re-read each turn), fits prefix size against turn index, and fits the
cross-session power law `read$ ∝ T^alpha`. Distinguishes two events that look
alike but cost differently: TTL re-warm (`cache_read` dip + `cache_write`
spike — the 5-minute cache expired and the prefix re-cached at the 12.5×
write rate; YTDB-1097) and true compaction (`real_prefix` sustained drop —
content actually summarized away).

### wf-lever-map.py

Fits each content bucket's resident-vs-turn intercept `a` (base: resident
from turn 1, re-read ~T times) and slope `b` (growth: accumulates with turns,
the quadratic source), splits each bucket's read bill into base vs growth,
and maps each named cost lever to the coefficient it moves. Ends with a
per-lever $/session rollup and a grand savings-ceiling table. Validated:
bucket sums plus uncached input reproduce every per-phase actual to the cent.

### wf-doc-read-discipline.py

Audits the doc-view discipline: for every workflow doc a transcript touched,
the line-range union actually read vs the full file. File geometry comes from
the logs themselves — Read results are `cat -n` numbered, and a read
returning fewer lines than requested pins the file's exact length at study
time (no disk dependency, works for files that no longer exist). Reports
realized savings (the uncovered remainder, priced at the resident tail) and
three violation classes: `big-full` (single full read > 5K tokens),
`chunked-full` (2+ partial reads covering ≥ 90%), `re-read` (lines already
resident in the same transcript).

## Headline findings (12-session study)

- Bill split: 42% cache-read / 36% cache-write / 21% output / 0.5% input;
  the resident prefix (read+write) is 79% of orchestrator cost.
- The prefix grows monotonically to the last turn of every session — zero
  compaction (`final/peak real_prefix = 1.00` across all 12; sessions end
  before compaction fires). The read integral is therefore a live two-term
  `READ·(a·T + ½·b·T²)`; the fitted `read$ ∝ T^1.41` is a local slope at
  T<90 and climbs toward 2 in longer sessions. Total cost ∝ T^1.07.
- Lever ranking by addressable $/session in exec-tracks B+C: bound-thinking
  (YTDB-1098) $7.70, 100% growth-term > floor-trim (YTDB-1094) $5.27, pure
  base > cold-rewrite (YTDB-1097) $5.12, write-side (68% of B+C cache-write
  is TTL re-warm) > doc-views $2.71 > sub-agent routing (YTDB-883) $1.70.
  Growth-share decides compounding: growth levers win long sessions,
  floor-trim wins short or lean phases (it is #1 for migrate).
- Doc-view discipline: already saves ~$15 vs reading every touched doc in
  full (~10% of the bill); full TOC-filtering compliance would recover ~$9–11
  more, half of it at the sub-agent layer (`implementer-rules.md` read whole
  on every implementer spawn is the single largest violation).

## Caveats

- char/4 sizing: factor 1.08 for prose docs, 1.12 for mixed content
  (tiktoken-anchored). The FLOOR/INJECTED split is approximate (the
  transcript itemizes only ~⅔ of the prefix); their sum is robust.
- migrate-workflow undercounts docs: it moves `_workflow/` artifacts through
  git/Bash, not the Read tool, so those bytes are invisible to Read-based
  detectors.
- The per-phase actuals these tools reproduce: B+C $75.22 / impl $18.24 /
  small $20.72 / create-plan $26.31 / migrate $11.40 (orchestrator layer).
