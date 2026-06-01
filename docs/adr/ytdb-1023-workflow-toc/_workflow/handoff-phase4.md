# Phase 4 handoff — Final Artifacts (paused between artifacts, adr.md pending)

**Paused:** 2026-06-01
**Phase:** 4 (State D)
**Context level at pause:** warning (36%)
**Branch:** ytdb-1023-workflow-toc
**HEAD:** ea77d2e535 "Add final design document"
**Unpushed:** see session-end report (design-final commit + this handoff commit, pushed at session end)

**Why paused:** `design-final.md` is authored, passed the full `phase4-creation`
mutation discipline, and is committed. `adr.md` is the next unit of work and the
between-artifacts gate in `create-final-design.md` forbids starting it at warning
level. Pausing at the clean between-artifacts boundary rather than mid-`adr.md`.

## Durable artifacts on disk

- `docs/adr/ytdb-1023-workflow-toc/design-final.md` — **DONE**, committed `ea77d2e535`.
  As-built design, 12 H2 sections mirroring `design.md`, deferred-drift basket folded in.
  Mutation discipline: mechanical PASS (10 should-fix cleared), whole-doc cold-read PASS
  (no leaked identifiers, all 4 Human-reader rules pass), 2 em-dash should-fix fixed.
  Logged as **Mutation 13** in `_workflow/design-mutations.md`.
- `adr.md` — **NOT written.** This is the resume target.

## Resume — what the next session does

Re-enter Phase 4 via `prompts/create-final-design.md`. `design-final.md` is done, so
**skip Step 3 Artifact 1**; resume at **Step 3 Artifact 2 (adr.md)**, then Step 4
(promote), Step 5 (final-artifacts commit — stage ONLY `adr.md`, since `design-final.md`
is already committed), Step 6 (cleanup), Step 7 (reflection), Step 8 (inform).

Re-read only the plan-file Decision Records and the per-track Episodes you want to quote
in Key Discoveries; the adr.md plan below is the digest.

### Do NOT redo
- `design-final.md` (committed `ea77d2e535`); its mutation discipline (mechanical + cold-read both PASS); Mutation 13 already logged.
- The startup checks already passed at the earlier session start (branch divergence clean; drift skip #2 plan-complete+Phase-4-active). The next session re-runs them per protocol regardless.

## adr.md authoring plan

Template (per create-final-design.md Artifact 2): `## Summary`, `## Goals`,
`## Constraints`, `## Architecture Notes` (`### Component Map`, `### Decision Records`,
`### Invariants & Contracts`, `### Integration Points`, `### Non-Goals`),
`## Key Discoveries`, `## Token usage telemetry` (locked, after Key Discoveries).

- **Decision Records:** restate **D1, D2, D4–D19** with actual outcomes. **D3 was dropped
  in Phase 1** (see `design-mutations.md` Mutation 2) — do NOT resurrect. Keep `Dn`
  numbering; these are the allowed durable IDs.
- **Ephemeral-identifier rule (durable artifact):** strip Track/Step/finding-IDs
  (`Track N`, `Step M`, `WC1`, `S1`, `M2`, `WB1`, iteration counters). Use prose, file/class
  refs, or commit SHAs. Allowed: `Dn`, `YTDB-1023`, commit SHAs. Re-scan with the §2.3
  pre-commit gate regex before committing (the gate flags `Dn`/`YTDB-1023`/`H1`/`H4` —
  all allowed exceptions, as confirmed for design-final.md).
- **Key Discoveries:** synthesize from step + track episodes. Strip identifiers. Likely
  entries: the four-deep `workflow-reindex.py` reopen cascade (fence-exclusion →
  staged-first lookup → bare-basename/`prompts/` collision → `<skill-dir>/SKILL.md` key →
  live-agent rules-6/7 scope), each a script-resolution gap one layer below the text work;
  the three-layer bootstrap-body recurrence (rule 7 is presence-only, so every body defect
  is gate-invisible and surfaces only under hand-review); the symlinked-worktree repo-root
  telemetry fix (commit `d00cd91f25`).
- **rule_1 residue note (adr.md):** missing-line-1-stamp residue is **49** over the full
  staged-workflow tree and **18** over the mechanically-derived in-scope set (7 staged SKILL
  + 11 staged prompts; the 20 live agents are rule_1-exempt by the live-agent-scope gate).
  An unscoped full-tree `--check` clears the 49 at Phase 4 promotion; the live SKILL/prompt
  rule_7 findings are the other half of that transition.
- **D18 post-merge acceptance procedure** (document in adr.md, NOT run in-branch): after
  squash-merge, the user runs `/migrate-workflow` in a fresh session on **two** candidate
  branches rebased onto post-plan develop, confirming clean completion (stamp-rewrite-only
  normalization or silent skip). Candidate pool: `ytdb-612-rollback-log`,
  `read-cache-concurrency-bug`, `ytdb-614-property-map`, `failed-wal-recovery`. The two picks
  + outcome resolve post-merge and land in the Track 5 completion episode (re-edit) + adr.md.
- **Telemetry section:** run `python3 .claude/scripts/measure-read-share.py` from this
  worktree (Artifact 2 step) and paste stdout as `## Token usage telemetry` after
  `## Key Discoveries`. Baseline cited in episodes: ~72.6% Read share from this worktree.

## Phase 4 remaining commit shape (workflow-modifying; design-final already committed)

1. **Promote** (Step 4): `STAGED_DIR` guard `[ -d .../staged-workflow/.claude ]` true →
   pre-promotion divergence check (`git fetch origin develop`; diff
   `$(git merge-base origin/develop HEAD)..origin/develop` on `.claude/workflow .claude/skills`;
   **halt** if non-empty per §1.7(f)) → `cp -r staged/.claude/. .claude/` →
   `git add .claude/workflow .claude/skills` → commit message **exactly**
   `Promote workflow changes from docs/adr/ytdb-1023-workflow-toc/_workflow/staged-workflow`
   (the implementer live-workflow-path gate keys off this prefix verbatim) → push.
2. **Final artifacts** (Step 5): stage **only** top-level `adr.md` (`design-final.md` already
   committed at `ea77d2e535`); commit `Add final design and ADR` (or `Add ADR`); push.
3. **Cleanup** (Step 6): `git rm -r docs/adr/ytdb-1023-workflow-toc/_workflow/`;
   commit `Remove workflow scaffolding`; push. This removes this handoff, the PAUSED marker
   host (plan file), design.md, track files, design-mutations.md, and the staged subtree.
   Per the resume protocol's Phase 4 cleanup exception, no separate handoff-resolution commit
   is needed once adr.md is committed — cleanup removes the handoff in the same sweep. Also
   remove the MEMORY.md Phase 4 bullets (MEMORY.md is user-global, not in the repo).

Then Step 7 reflection (YouTrack YTDB / dev-workflow, no commit), Step 8 inform user
(Claude does **not** run `gh pr ready`).

## On resume
- Proceed with Artifact 2 (adr.md) per the plan above.
- Phase B base commit (reference): Track 5 Phase B base `b722c11e6e`.
