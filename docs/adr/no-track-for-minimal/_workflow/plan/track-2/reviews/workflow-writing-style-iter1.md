<!--
MANIFEST
dimension: workflow-writing-style
iteration: 1
verdict: PASS
finding_count: 4
counts: { blocker: 0, should-fix: 0, suggestion: 4 }
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: WS1
    sev: Recommended
    anchor: "### WS1"
    loc: "docs/adr/no-track-for-minimal/_workflow/plan/track-2.md:609-613"
    cert: C1
    basis: judgment
  - id: WS2
    sev: Recommended
    anchor: "### WS2"
    loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/mid-phase-handoff.md:206-216"
    cert: C2
    basis: judgment
  - id: WS3
    sev: Minor
    anchor: "### WS3"
    loc: "docs/adr/no-track-for-minimal/_workflow/plan/track-2.md:125"
    cert: C3
    basis: judgment
  - id: WS4
    sev: Minor
    anchor: "### WS4"
    loc: "docs/adr/no-track-for-minimal/_workflow/plan/track-2.md:149"
    cert: C4
    basis: judgment
-->

## Findings

### WS1 [Recommended] Two em dashes in the Idempotence prose paragraph

File: `docs/adr/no-track-for-minimal/_workflow/plan/track-2.md` (lines 609-613). Axis: em-dash overuse. Cost: two em dashes in one blank-line-bounded prose paragraph, against the house-style cap of one per paragraph.

The `## Idempotence and Recovery` paragraph carries two em dashes: line 609 "The re-points are idempotent — re-applying a marker/tier/signal re-point…" and line 613 "no live workflow — the I6 invariant holds…". `house-style.md § Em-dash discipline` caps em dashes at one per paragraph; the mechanical check counts per blank-line-bounded paragraph outside fenced code, and this is one such paragraph.

Suggestion: convert the second to a colon or period. Replace line 613 `(no DB, no live workflow — the I6 invariant holds: live `.claude/**` stays at develop until Phase-4 promotion)` with `(no DB, no live workflow; the I6 invariant holds, so live `.claude/**` stays at develop until Phase-4 promotion)`. That leaves the single em dash on line 609.

### WS2 [Recommended] Two em dashes in the staged "Clear-on-resolution" paragraph

File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/mid-phase-handoff.md` (lines 206-216). Axis: em-dash overuse. Cost: two em dashes in one added prose paragraph, against the one-per-paragraph cap. This is track-authored text (delta lines 480-491), so it is in scope.

The `**Clear-on-resolution.**` paragraph uses an em dash at "The ledger is append-only — there is no unpause op" (line 206) and again at "before treating it as an active pause — otherwise a resolved pause re-discovers next session as a recurring false Abort" (line 216). Per `house-style.md § Em-dash discipline`, one of the two must go.

Suggestion: turn the first into a colon — "The ledger is append-only: there is no unpause op, so a resolved pause's `paused=` event is never deleted." Keep the second em dash, which carries the "otherwise" contrast.

### WS3 [Minor] Negative-parallelism tail "not just a human cue"

File: `docs/adr/no-track-for-minimal/_workflow/plan/track-2.md` (line 125). Axis: banned vocabulary (banned sentence patterns: roundabout negation / negative parallelism). Cost: a low-grade "Not just A, but B" tell in the D8 Rationale.

The clause reads "…is machine-read by `determine_state` on resume, not just a human cue." `house-style.md § Banned sentence patterns` lists "Not just A, but B" under negative parallelism and asks for a positive statement. The positive claim already leads (the event is machine-read), so the "not just" tail is the only soft tell here, which is why this is Minor rather than the Critical "It's not X, it's Y" inversion.

Suggestion: state the contrast positively — "…is machine-read by `determine_state` on resume, where the old plan marker was only a human cue." Or drop the tail if the machine-read point already carries the paragraph.

### WS4 [Minor] Negative-parallelism tail "a tier-line writer, not only a reader"

File: `docs/adr/no-track-for-minimal/_workflow/plan/track-2.md` (line 149). Axis: banned vocabulary (banned sentence patterns: negative parallelism). Cost: a "not only X" tell in the D11 Rationale.

The clause reads "`inline-replanning` is a tier-line writer, not only a reader: an ESCALATE upgrade rewrites the tier line…". The "not only a reader" framing is the negative-parallelism shape; the surrounding sentence already states the positive fact (it writes the tier line). Minor, since the positive claim leads and the contrast is doing light work.

Suggestion: lead with the positive — "`inline-replanning` writes the tier line, it does not only read it: an ESCALATE upgrade rewrites the tier line…" Or recast as "`inline-replanning` both reads and writes the tier line: an ESCALATE upgrade rewrites it…".

## Evidence base

#### C1 — WS1 em-dash count (confirmed)
Mechanical per-paragraph em-dash count of the `## Idempotence and Recovery` paragraph (track-2.md 606-615) returns 2 (`grep -o '—' | wc -l` = 2): line 609 and line 613. One blank-line-bounded prose paragraph, no fenced code. Exceeds the one-per-paragraph cap in `house-style.md § Em-dash discipline`. Finding survives.

#### C2 — WS2 em-dash count (confirmed)
The `**Clear-on-resolution.**` paragraph in the staged `mid-phase-handoff.md` (lines 206-216) returns an em-dash count of 2. Cross-checked as track-authored: it matches delta lines 480-491 (added `>` lines), so it is in-scope new prose, not verbatim-copied live content. Exceeds the cap. Finding survives.

#### C3 — WS3 negative parallelism (confirmed, low severity)
Grep for negative-parallelism shapes flagged track-2.md:125 "not just a human cue." Read in context (D8 Rationale, lines 121-127): the positive claim leads ("machine-read by `determine_state`"), the "not just" tail is an emphatic contrast. Matches `§ Banned sentence patterns` "Not just A, but B" but is the weak appositive form, not the load-bearing "It's not X, it's Y" inversion, so it reads harder than its plain rewrite only marginally. Minor.

#### C4 — WS4 negative parallelism (confirmed, low severity)
Grep flagged track-2.md:149 "a tier-line writer, not only a reader." Read in context (D11 Rationale, lines 149-153): the colon-led elaboration states the positive fact (rewrites the tier line, writes a design seed). The "not only a reader" framing matches `§ Banned sentence patterns` negative parallelism; the positive claim leads, so it reads only slightly harder than a direct "both reads and writes." Minor.
