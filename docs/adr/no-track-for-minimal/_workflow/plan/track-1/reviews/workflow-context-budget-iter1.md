<!-- MANIFEST
dimension: workflow-context-budget
target: Track 1 — the phase ledger, the new artifact model, the authoring surface
commit_range: 6c2e0b5f68b12599aacbcce8b608f5c1489a3159..HEAD
iteration: 1
high_water_mark: 0
findings: 0
evidence_base: { certs: 0 }
cert_index: []
index: []
flags: { evidence_trail_exempt: true, reason: "(a) no refutation or certificate phase to persist" }
-->

## Findings

None. No always-loaded surface, load-on-demand structural drift, or instant
per-operation consumption impact in this diff; the change is net-favorable on
the per-operation axis. The `workflow-reindex.py --check` deterministic gate
ran clean on the eight changed staged files (exit 0), and a full-repo
`--check` walk also returned clean (no diff-filtered findings, no pre-existing
schema debt to note).

### Budget assessment (no finding raised)

**Axis 1 — always-loaded surface: untouched.**
- `create-plan` SKILL.md `description:` frontmatter is byte-identical live vs
  staged. The description is the only always-loaded part of a SKILL.md, and the
  +44-line body growth is load-on-demand (the body loads only when the skill is
  invoked).
- `CLAUDE.md` is not in the diff. No agent `description:` field is touched.
- `workflow-startup-precheck.sh` is **not** wired as a SessionStart hook
  (`.claude/settings.json` wires only `mcp-steroid-probe.sh`,
  `mcp-steroid-grep-reminder.sh`, and `house-style-write-reminder.sh`). Its
  ~470-line growth — the `--append-ledger` primitive, the
  `reject_bad_ledger_value` / `append_ledger` / `ledger_tail_value` /
  `determine_state_from_ledger` functions, and the header contract block —
  never becomes session `additionalContext`. The script is invoked on demand by
  the orchestrator during `/create-plan` and `/execute-tracks`, and even then
  only its terse JSON stdout enters context, not the script body.

**Axis 2 — load-on-demand discipline: no structural drift.**
- `conventions.md` grew +121 lines, the only changed file over the 100-line
  Axis-2 threshold. Both structural-drift sub-checks return false: (1) the added
  content is canonical convention specification — four §1.1 glossary rows (Phase
  ledger, Derived-mirror plan, Plan-review document, Combined Invariants &
  Constraints), the §1.2 per-tier artifact matrix, the §1.6(f) ledger /
  plan-review exclusion entries, and the §1.7 prefix-list extension to
  `.claude/scripts/**` — not inline rules / recipes / examples that leaked out
  of an always-loaded file; (2) no `CLAUDE.md` pointer to a changed file broke
  (CLAUDE.md references `.claude/workflow/*.md` and `.claude/docs/**` by path
  and none of those paths changed). `conventions-execution.md` (+42),
  `planning.md` (+53), and `workflow.md` (+20) grew within band and stay
  load-on-demand reference docs.

**Axis 3 — instant per-operation consumption: net-favorable.**
- The headline change *reduces* the per-operation working set. `minimal`-tier
  branches drop `implementation-plan.md` entirely; a fresh `/create-plan` or
  `/execute-tracks` resume reads the ledger tail (a one-value line scan through
  `ledger_tail_value`, returned as terse JSON — not slurped into orchestrator
  context as a file) plus the single `plan/track-1.md`, instead of a stub plan
  plus the track file.
- `lite`/`full` plans are thinned: `### Goals`, `### Constraints`, the full
  `### Architecture Notes` / Decision Records, `## Plan Review`, and `## Final
  Artifacts` all leave the plan, which an orchestrator reads at startup. The
  plan is now `## Checklist` + a thin cross-track Component Map — a per-startup
  read *reduction*.
- The content relocated into the track files (the 15th `## Invariants &
  Constraints` section, folded Integration Points, the completion episode) is
  read on demand only when that track enters Phase A/B/C — the same load profile
  as the rest of the track file, not an always-loaded or every-startup cost.
- D7 is an explicit budget-conscious choice: the multi-line Phase-2 audit
  summary stays OUT of the append-only ledger tail (it lives in
  `plan-review.md`) precisely so the tail `determine_state` greps stays terse.
- No new orchestrator-side heavy read, uncapped sub-agent dispatch, inlined
  recipe, or `/tmp`-staging gap is introduced. The two new SKILL.md code blocks
  (the ledger-tier `sed` parse and the `--append-ledger` seeding call) are
  short, single-purpose, and live on-demand in the skill body.

## Evidence base
