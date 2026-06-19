# absorption-check params — Track 1, round 1

- target: tracks
- research_log_path: docs/adr/ai-tells-shrinkage/_workflow/research-log.md
- draft_path: docs/adr/ai-tells-shrinkage/_workflow/plan/
- (no design_path — minimal tier, no design.md seed)

## What to check

Two-way coverage match between the research log's load-bearing decisions (its
`## Decision Log`, including the "### Gate iteration-1 resolutions" block) and
the track's `## Decision Log` inline records (in `plan/track-1.md` under
`draft_path`):

- Every load-bearing research-log decision must appear as a track Decision
  Record. A load-bearing decision missing from the track is a finding.
- A track Decision Record inventing a decision the log lacks is a finding (and,
  if load-bearing, re-opens the adversarial gate).

The load-bearing decisions to match against: the decision test (DR1), the
REMOVE/KEEP set (DR2), chat-only retention of sycophantic openers + signposting
(DR3), the §1.7(k) live-edit opt-out on the no-invocation basis (DR4), curly
quotes kept / knowledge-cutoff removed (DR5/A6), the banned-vocab precision fold
(DR6), the exhaustive consumer-coverage acceptance contract (A1), the Tier-B/chat
four-section subset (A3), and the ai-tells description update (A4). Report
coverage as matched / missing / invented.
