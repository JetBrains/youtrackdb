<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: S1, sev: should-fix, loc: "implementation-plan.md:44-50 (Component Map)", anchor: "### S1 ", cert: "", basis: "component-intent bullet 7 lines, over the ~5-line soft budget"}
  - {id: S2, sev: should-fix, loc: "plan/track-1.md:36-68 (Decision Log, D1)", anchor: "### S2 ", cert: "", basis: "DR body 33 lines, over the ~30-line soft budget; append-cadence bullet is append-side material owned by Track 2 D1"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### S1 [should-fix]
**Location**: `implementation-plan.md`, Component Map — the `workflow-startup-precheck.sh` intent bullet (lines 44–50).
**Issue**: The `workflow-startup-precheck.sh` component-intent bullet runs 7 rendered lines, over the ~5-line component-intent soft budget. It packs the four functional changes (the `--substate` append flag and validation, the track-scoped reader, the ledger-primary read, the wrap-tolerant `roster_scan` fix) plus the grammar docs and the test surface into one bullet. The Component Map is loaded at every `/execute-tracks` session startup, so the over-budget lines are paid for the plan's life.
**Proposed fix**: Trim the bullet to one short paragraph naming the component's intent at a glance — e.g. "gains the `substate` ledger key (the append flag, reader, and ledger-primary read) plus the wrap-tolerant `roster_scan` fallback fix; ships with its grammar docs and test surface." The per-change detail already lives in Track 1's `## Context and Orientation` and `## Plan of Work`, so no content is lost.
**Classification**: mechanical
**Justification**: BLOAT / component-intent-length — a component intent bullet exceeding ~5 lines is a `mechanical` bloat finding (§Classification rules, "Component-intent length — `mechanical`"). Soft bound: 7 vs ~5.

### S2 [should-fix]
**Location**: `plan/track-1.md`, `## Decision Log` — D1 (lines 36–68).
**Issue**: Track 1's D1 body runs 33 lines (heading through final bullet), over the ~30-line DR soft budget, and carries six bullets (a five-bullet-plus-`**Full design**` form) rather than the natural four. The extra `**Append cadence (the contract Track 2 implements).**` bullet (lines 51–58, 8 lines) is append-side material: Track 1 owns the *read* side, while the append cadence and the `failed-step`-exclusion rationale are canonically owned by Track 2's D1 (`### D1 — the append cadence (append side)`) and by `design.md` §"Resume state machine and the per-track `substate` lifecycle". This is the bloat pattern the rule targets — a DR over ~30 lines that absorbed material belonging to another section. Every pending track file is read on demand at Phase A/B/C.
**Proposed fix**: Trim D1 toward the four-bullet form (Problem / Decision / Rejected / Implemented in). Drop or shorten the `**Append cadence**` bullet to a one-line pointer ("Append cadence is the contract Track 2 implements; see Track 2 D1 and `design.md` §Resume state machine"), since Track 2's D1 already carries the cadence and the `failed-step` exclusion in full. The existing `**Full design**` line already points at the frozen seed's mechanism sections as on-demand provenance.
**Classification**: mechanical
**Justification**: BLOAT / DR-length — a Decision Record body exceeding ~30 lines is a `mechanical` bloat finding (§Classification rules, "DR length — `mechanical`"); the fix moves the long-form append-side material to the matching track section (Track 2's `## Decision Log`, which already owns it). Soft bound: 33 vs ~30.

## Evidence base

No certificates produced — this is a plan-internal structural review that reads no codebase (`certs: 0`).
