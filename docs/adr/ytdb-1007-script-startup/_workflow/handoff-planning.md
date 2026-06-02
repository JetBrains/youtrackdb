# Handoff: Phase 1 (planning) — derive plan from approved design (YTDB-1007)

**Paused:** 2026-06-02
**Phase:** 1 (planning)
**Context level at pause:** info (user-requested pause, not context-driven — below the warning trigger; the user asked to derive the plan in a fresh session)
**Branch:** ytdb-1007-script-startup
**HEAD:** 14505dd752c06b483903dcb136acaee3cf26eed1 "Fix CI change-detection to use merge-base diff (#1114)"
**Unpushed:** <no upstream — see workflow.md §What to do before ending a session> (the design + this handoff are committed locally; the push + draft PR happen in the resuming session's `/create-plan` Step 5)

## What I was investigating

YTDB-1007 (Feature, Critical): script the workflow startup precheck (branch
divergence + workflow drift + resume state) into one bash+jq script,
`.claude/scripts/workflow-startup-precheck.sh`, that emits a single JSON blob,
so the ~1,200 lines of gate prose loaded at every session start shrink to a
~30-50-line agent-side dispatch rule. Target: free ~20-25K tokens of startup
context.

**Status: research complete, design done.** `design.md` is created,
auto-reviewed (mechanical PASS + cold-read PASS), and user-approved. The
remaining work is Phase 1 plan derivation ONLY — derive
`implementation-plan.md` + four `plan/track-N.md` files from the approved
design, then run `/create-plan` Step 5 (commit, push, draft PR).

## Already ruled out

- jq-free hand-rolled JSON emitter — rejected; use `jq` (it is present, v1.8.1).
- Python implementation — rejected; bash keeps the §1.6(h) walk close to its spec.
- New `.claude/workflow/scripts/` subtree — rejected; script lives in `.claude/scripts/` (alongside `statusline-command.sh`, `session-stats.py`).
- Script performs force-push/reset and reports them in `actions_taken` — rejected; those are user-gated and conversational (force-with-lease can re-reject; reset needs `git log @{u}..HEAD` + confirmation). Agent runs them from its own bash. `actions_taken` = autonomous normalization commit only.
- Strip the artifact-walk bash from `conventions.md §1.6(h)` — rejected; §1.6(h) keeps the readable spec, the script implements it and cites it in a header comment.
- Extending §1.7 staging to `.claude/scripts/` — rejected; §1.7(a) explicitly scopes staging to `.claude/workflow/**` and `.claude/skills/**` only, so the script + tests are authored live.

## Most promising lead

Approach fully locked. One script, three modes (`--mode {full,divergence-only,migrate-range}`), one jq emit point. `full` mode JSON = `{divergence, drift, handoffs, state, actions_taken}`. The approved design at `_workflow/design.md` is the frozen spec; the plan derives from it. Proposed 4-track shape is in Raw notes below.

## Open questions

None. All four design questions were resolved with the user (see D1-D7). The design's cold-read review surfaced only suggestions (one applied, two recorded).

## Raw notes / partial findings

### Locked decisions (become plan Decision Records D1-D7)

- **D1 — Script location + language.** `.claude/scripts/workflow-startup-precheck.sh`, bash + `jq`. *Alternatives:* Python (breaks §1.6(h) byte-proximity), jq-free emitter (clumsy), `.claude/workflow/scripts/` (new subtree). *Rationale:* bash keeps the walk close to the §1.6(h) spec; jq makes JSON correct by construction; `.claude/scripts/` is the existing scripts home. *Risks:* bash JSON without jq would be fragile (mitigated by requiring jq). *Implemented in:* Track 1.
- **D2 — Three run modes via `--mode` flag.** `{full, divergence-only, migrate-range}`. *Alternatives:* one always-full JSON every caller filters. *Rationale:* the three callers have disjoint needs (full startup; cheap mid-session divergence re-check; migration range+pairs); a flag keeps each output minimal. *Risks:* mode proliferation (capped — a new caller reuses an existing mode). *Implemented in:* Tracks 1, 2, 3 (modes) + Track 4 (callers wired).
- **D3 — `actions_taken` = autonomous mutations only.** The script reports the no-drift normalization commit it performs; force-push/reset stay agent-side (user-gated). *Alternatives:* a two-pass `--apply` mode performing+reporting force-push/reset. *Rationale:* non-goal #1 (script never prompts); the gated commands are conversational (rejection re-routes; reset needs confirmation). *Risks:* recital split across script JSON + agent bash (acceptable). *Implemented in:* Track 3 (+ Track 1 JSON field).
- **D4 — §1.6(h) keeps the spec; script implements it.** The four byte-copies (conventions §1.6(h), drift-check Detection, drift-check normalization recompute, migrate Step 2) collapse to one script implementation + one readable spec. *Alternatives:* strip bash from §1.6(h), point only at script. *Rationale:* §1.6 is the declared single source of truth; keeping a readable spec there is cheap and the parser idioms (§1.6(a1)) live adjacent. *Risks:* spec/script drift (mitigated by a byte-source conformance fixture test). *Implemented in:* Track 1 (walk) + Track 4 (§1.6(h) pointer edit).
- **D5 — Walk-not-compute boundary.** Script absorbs the §1.6(h) *walk* (reading stamps at startup/migration), not §1.6(b) create-time stamp computation (stays in create-plan/edit-design). *Rationale:* reading stamps is a startup concern; computing one is a creation concern. *Implemented in:* Track 1.
- **D6 — Staging asymmetry.** Plan is workflow-modifying → 6 prose edits are STAGED under `staged-workflow/`; the new script + tests are authored LIVE under `.claude/scripts/` (§1.7 doesn't govern that tree). They unify at Phase 4 promotion. This branch dogfoods the OLD inline-bash path for its own `/execute-tracks` sessions; the new path goes live only for the next branch post-merge. *Risks:* a reviewer asks "why isn't the script staged" — design §"Staging asymmetry" answers it. *Implemented in:* spans all tracks (Constraints + Track 4 promotion note).
- **D7 — Test fixtures cover gate paths + every state.** Fixtures under `.claude/scripts/tests/` cover the 4 gate paths (clean/divergence/drift/both) AND every `state.phase` (0/A/C+5 substates/D/Done), plus the normalization commit subject + diff shape, plus the byte-source conformance check. *Rationale:* state determination is the riskiest surface (markdown parse). *Implemented in:* Tracks 1, 2, 3 (each track's own fixtures).

### Invariants (become plan Invariants S1-S4)

- **S1 — Behavior parity.** The script reaches the same on-disk outcomes as today's prose for all four gate paths. Testable via the fixture set.
- **S2 — Script never prompts.** No `--mode` ever reads stdin or asks the user; the conversational gate UX stays in the agent (non-goal #1).
- **S3 — Normalization commit unchanged.** Same subject (`Normalize workflow-sha stamps to <short>`) and line-1-only diff shape + all-or-nothing abort-restore as today's `workflow-drift-check.md § No-drift normalization`.
- **S4 — I6 staging invariant holds.** Live `.claude/workflow/**` and `.claude/skills/**` stay at develop state for the whole branch until the Phase 4 promotion.

### Proposed track shape (exact step counts are Phase A's job)

- **Track 1 — Detection core + modes + JSON (live).** Scaffold script, `--mode` plumbing, jq emitter; divergence detection; drift Phase 1 walk + Phase 2 fold/`git log`; handoff scan; `divergence-only` + `migrate-range` outputs (incl. `(file,sha)` pairs + `--bootstrap-sha`); `actions_taken` assembly. Pure detection, no side effects. `> **Scope:** ~6 steps`.
- **Track 2 — State determination (live).** Markdown state parser (`## Plan Review` / track checkboxes / `## Progress` sub-states / `## Final Artifacts` / section-discrepancy edge) + state fixtures. Riskiest surface, isolated. `> **Depends on:** Track 1`. `> **Scope:** ~4-5 steps`.
- **Track 3 — No-drift normalization + actions_taken wiring (live).** Line-1 rewrite, two diff-shape guards, all-or-nothing commit, feed into `actions_taken`. Only mutating path. `> **Depends on:** Track 1`. `> **Scope:** ~3-4 steps`.
- **Track 4 — Prose consolidation (staged).** `workflow.md § Startup Protocol` → ~30-50-line dispatch rule; `workflow-drift-check.md` + `branch-divergence-check.md` shrink to reference docs; `conventions.md §1.6(h)` pivot (keep spec + add script pointer); `commit-conventions.md § Push failure handling` → `divergence-only` re-entry; `migrate-workflow/SKILL.md` Step 2 → `migrate-range` reuse. ALL STAGED under `staged-workflow/`. `> **Depends on:** Tracks 1, 2, 3` (script JSON shape must be final before prose cites it). `> **Scope:** ~6 steps`.

### Files in play

- **New (LIVE):** `.claude/scripts/workflow-startup-precheck.sh`, `.claude/scripts/tests/` fixtures + harness.
- **Modified (STAGED under `_workflow/staged-workflow/.claude/...`):** `.claude/workflow/workflow.md`, `.claude/workflow/workflow-drift-check.md`, `.claude/workflow/branch-divergence-check.md`, `.claude/workflow/conventions.md` (§1.6(h)), `.claude/workflow/commit-conventions.md`, `.claude/skills/migrate-workflow/SKILL.md`.
- **Callers affected (the dispatch rewrite touches these):** `/execute-tracks` Startup Protocol (workflow.md steps 3/3a/4/5), `/create-plan` Step 1.5, `commit-conventions.md § Push failure handling`, `/migrate-workflow` Step 2.

### Design section names (for the plan's `**Full design**` refs — must resolve at Phase 2 State 0)

Overview, Core Concepts, Component design, Workflow, The JSON contract, State determination, No-drift normalization path, Byte-source consolidation, migrate-range reuse, Staging asymmetry, Mid-session re-entry, Testing strategy.

### Procedural reminders for the resuming planner

- **Constraints MUST carry the canonical §1.7(b) marker verbatim:** `This plan is workflow-modifying: it edits .claude/workflow/** or .claude/skills/**.`
- **Stamp:** every `_workflow/**` artifact created in the resuming session reuses one `$WORKFLOW_SHA` (§1.6(b) paired idiom). `design.md` is already stamped `0676e2446f373e969da86da6748c91d442135161`; the plan + track files get the resuming session's freshly-computed stamp.
- **Tooling:** no Java symbols anywhere in this plan (bash + markdown), so no PSI audits are load-bearing. Reference-accuracy = grep/Read over markdown cross-refs and bash byte-copy contracts.
- **Component Map:** the design's Component-design flowchart (3 modes, 4 callers) is the basis; the plan's Architecture Notes Component Map can be a condensed topology + bullets.

## Resume notes

- **Do NOT redo:** research is complete; `design.md` is created, auto-reviewed PASS, and user-approved — do NOT re-create or re-review it. Any design change goes through `edit-design` (a mutation), never a raw rewrite. The four design questions are resolved (D1-D7). The drift gate (Step 1.5) and handoff scan (Step 1a) will fire on resume; the drift gate should report no drift (design.md stamped at the current workflow HEAD).
- **Next action on resume:** read the deferred Step-4 planning docs (`planning.md`, `design-document-rules.md`), then derive `implementation-plan.md` + the four `plan/track-N.md` files from the approved `design.md` per `/create-plan` Step 4 — Goals/Constraints (with the §1.7(b) marker), Architecture Notes (Component Map + D1-D7 + Invariants S1-S4 + Integration Points + Non-Goals), the 4-track checklist with scope indicators and `**Full design**` refs into the design sections above. Then `/create-plan` Step 5: commit, `git push -u`, open the draft PR (ask for the issue prefix — `YTDB-1007`).
