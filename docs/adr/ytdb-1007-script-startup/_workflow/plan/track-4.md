<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 4: Prose consolidation (staged)

## Purpose / Big Picture
After this track lands, the six gate-prose surfaces call or cite the script instead of carrying inline detection bash — `workflow.md § Startup Protocol` shrinks to a ~30-50-line dispatch rule — and every edit sits staged under `staged-workflow/`, ready for the Phase 4 promotion.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 4 rewrites the six prose surfaces to consume the script: `workflow.md § Startup Protocol` becomes a short dispatch rule; `workflow-drift-check.md` and `branch-divergence-check.md` shrink to reference docs; `conventions.md §1.6(h)` keeps its spec and gains a script pointer; `commit-conventions.md § Push failure handling` re-enters via `divergence-only`; `migrate-workflow/SKILL.md` Step 2 reuses `migrate-range`. Every edit is STAGED under `staged-workflow/`, and the script's JSON shape must be final before the prose cites it, which is why this track runs last.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

Six live prose files carry the detection logic and the gate UX today; this track moves the mechanical detection out to the script and leaves the conversational and reference prose behind. Because the plan is workflow-modifying (the `§1.7(b)` marker in Constraints), every edit lands under the staged subtree, not the live tree.

The six surfaces and their target shapes:

- **`workflow.md § Startup Protocol`** — today ~full gate prose. Becomes a ~30-50-line dispatch rule: run `--mode full`, parse the JSON, present the divergence and drift gates the script reports without owning, run the handoff resume protocol when `handoffs` is non-empty, then route on `state.phase` (and on `state.substate` for State C — the shipped `state` object is `{phase, substate}` with no `track` field, so the dispatch re-derives the active track from the `## Checklist` walk).
- **`workflow-drift-check.md`** — the inline Detection and normalization bash is replaced by a citation of the script; the file keeps the conversational three-resolution prose as a reference doc.
- **`branch-divergence-check.md`** — the inline ahead/behind detection bash is replaced by a script citation; the resolution prose stays.
- **`conventions.md §1.6(h)`** — keeps the artifact-walk bash as the readable spec (§1.6 is the declared single source of truth) and gains a one-line pointer to the script implementation. This is a pivot, not a strip.
- **`commit-conventions.md § Push failure handling`** — the mid-session non-fast-forward push re-runs `--mode divergence-only` instead of reloading the divergence gate prose.
- **`migrate-workflow/SKILL.md` Step 2** — reads the `--mode migrate-range` fields (`stamped_artifacts` pairs, `unstamped_files`, `base_sha`, the `git log` range, optional `--bootstrap-sha`) instead of byte-copying the §1.6(h) walk in prose.

The staging discipline this track runs under: every first touch of a live file copies it verbatim into its staged path (§1.7(e)), edits land on the staged copy, reads resolve staged-first (§1.7(d)), and the live tree stays at develop state for the whole branch (§1.7(g), I6). The staged subtree promotes at the Phase 4 commit, after a rebase onto develop (§1.7(f)); the script is already live, so only the prose promotes (D6).

## Plan of Work

The work edits each surface once the JSON contract is frozen by Tracks 1-3. Each edit is a copy-then-edit on first touch under the staged path.

1. **`workflow.md § Startup Protocol` dispatch rewrite.** Replace the inline gate sequence with the ~30-50-line dispatch rule that runs `--mode full` and routes on the JSON.
2. **`workflow-drift-check.md` shrink.** Replace the inline Detection + normalization bash with a script citation; keep the resolution prose.
3. **`branch-divergence-check.md` shrink.** Replace the inline detection bash with a script citation; keep the resolution prose.
4. **`conventions.md §1.6(h)` pivot.** Keep the walk bash as the spec; add the one-line pointer to the script implementation and note the script conforms to this spec (checked by the Track 1 conformance fixture).
5. **`commit-conventions.md § Push failure handling` re-entry.** Route the first in-session non-fast-forward push to `--mode divergence-only`.
6. **`migrate-workflow/SKILL.md` Step 2 reuse.** Read the `migrate-range` JSON fields instead of re-deriving the walk in prose; keep the conversational unstamped-bootstrap prompt in the skill.

Ordering constraints and invariants to preserve: this track depends on Tracks 1-3 so the JSON shape the prose cites is final — citing field names that later change would desync the dispatch rule. Every edit lands under `staged-workflow/` (S4 / I6); no live `.claude/workflow/**` or `.claude/skills/**` file changes. The §1.6(h) edit is a pivot that keeps the readable spec, not a strip (D4). Cross-references between the rewritten files must resolve against the script's actual `--mode` outputs.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- The rewritten `workflow.md § Startup Protocol` runs `--mode full` and routes on `divergence`, `drift`, `handoffs`, `state`, and `actions_taken`, with no inline detection bash remaining.
- `workflow-drift-check.md` and `branch-divergence-check.md` no longer carry detection bash; each cites the script and retains its conversational resolution prose.
- `conventions.md §1.6(h)` still carries the artifact-walk bash as the spec and now also points at the script implementation.
- `commit-conventions.md § Push failure handling` routes the first in-session non-fast-forward push to `--mode divergence-only`.
- `migrate-workflow/SKILL.md` Step 2 reads the `migrate-range` fields and no longer byte-copies the walk; the unstamped-bootstrap prompt stays in the skill.
- Every edited file lives under `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/...`; no live `.claude/workflow/**` or `.claude/skills/**` file is modified (S4 / I6).
- Every JSON field name the rewritten prose cites matches the script's actual `--mode` output.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: copy-then-edit on first touch is idempotent — a file already staged is edited in place on its staged copy, not re-copied from live. The Phase 4 promotion is additive (`cp -r` staged over live) and re-entrant per `§1.7(j)`, so an interrupted promotion re-runs as a no-op.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

- Track 1 review note (WB1, context-budget): when Step 6 rewrites
  `migrate-workflow/SKILL.md` Step 2 to consume `migrate-range`, have the
  consumer read the JSON from a `/tmp` file via `Read` with `offset`/`limit`
  rather than inlining the whole blob into the orchestrator. The
  `migrate-range.log_range` array is intentionally uncapped (the migration
  must replay every commit, unlike the drift range's `head -10` display
  cap), so a long branch range could otherwise dump an unbounded commit
  list into context on each invocation.

- **Phase A review findings (iteration 1, captured pre-replan 2026-06-03) —
  decomposition guidance.** The Track 4 Phase A reviews (technical / risk /
  adversarial) ran before the Track 5 inline replan; their blocker (A1, the
  unscoped SKILL entry points) became Track 5. The remaining should-fix /
  suggestion items are recorded here so the next Track 4 Phase A
  decomposition threads them into the step bodies rather than re-deriving
  them:
  - **Dispatch rewrite (Step 1) instruction-completeness (R1/A2/A7).** The
    rewritten `workflow.md § Startup Protocol` must preserve every behavior
    the script's JSON does *not* carry: the no-default divergence pick, the
    handoff-resume freeze ("MUST NOT spawn sub-agents / re-run gate-checks /
    recompile episodes" while a handoff is unresolved), the State A Panel-1
    skip, the five State C sub-state resumes, the section-discrepancy edge,
    and "one phase per resume." Route State C on `state.substate`, not
    `state.phase`. Treat a non-zero script exit (Track 2's total parse-error
    contract) as halt-and-surface, not resume. Branch on the non-happy-path
    JSON: `divergence.skipped` / `skip_reason`, `drift.kind`
    (`unstamped` / `merge-base-failed` / `stamped`), and
    `drift.normalization_landed` + `actions_taken` (recite the autonomous
    normalization commit to the user). Treat the ~30-50-line target as a
    soft goal, not a hard cap — size Step 1 by behaviors-preserved.
  - **Migrate Step 2 (Step 6) recovery loop must survive (R3/A4).** The
    `migrate-range` mode emits the walk / fold / range as data, but the
    script never prompts. Keep the agent-side merge-base-failure recovery
    loop in the skill: re-prompt with the combined unstamped +
    `merge_base_failed[].files` set, drop the failed SHAs, enforce the
    session-wide 3-attempt cap, re-invoke with `--bootstrap-sha`, restart the
    fold. Clarify whether Step 2.0's walk is replaced by
    `migrate-range.unstamped_files` (the `migrate-range` mode carries it) so
    D4's "four byte-copies → one" claim holds rather than collapsing to two.
  - **Push-failure re-entry (Step 5) gating must survive (R4).** `--mode
    divergence-only` replaces only the *detection*; keep the
    first-occurrence-in-session guard and the already-Deferred
    record-and-continue suppression. Read both `divergence.detected` and
    `divergence.skipped` / `skip_reason`.
  - **§1.6(h) pivot (Step 4) stale cross-reference (A3).** Reconcile the
    `conventions.md §1.6(h)` "Tracks 3 and 4a copy this block byte-for-byte"
    sentence — replace the byte-copy coordinated-edit framing with
    "the script is the single implementation; this section is the spec it
    conforms to, checked by the Track 1 conformance fixture" — or §1.6(h)
    self-contradicts after the pivot.
  - **Known-debt note (A5).** Carry a durable note into the rewritten
    `workflow-drift-check.md` / `conventions.md §1.6(h)` prose: the script's
    normalization walk inherits the fixed-template path-quoting assumption,
    and guard 2's `awk '{print $2}'` truncates a porcelain path containing a
    space. Otherwise the only record vanishes at the `_workflow/` cleanup.
  - **Cite the live script, not frozen design.md (T3/R5).** JSON field names
    are cited from the shipped `.claude/scripts/workflow-startup-precheck.sh`
    `emit_json`, not from frozen design.md, which diverges on the
    `migrate-range` shape, the omitted `state.track` field, and the substate
    slug form (Phase 4 reconciliations, plan §Final Artifacts).
  - **Parity-delta honesty (A6) and handoff order (R6).** The rewritten
    `branch-divergence-check.md` should note the script bounds the startup
    fetch with `timeout 10` (parity holds except on a slow-but-reachable
    remote, caught by the per-commit push re-check). The `handoffs` array
    order (`ls -t`, most-recent-first) is load-bearing — preserve it and the
    basename-under-plan-dir resolution when wiring the handoff-resume trigger.

## Interfaces and Dependencies

**In scope (all STAGED under `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/`):**
- `.claude/workflow/workflow.md` — § Startup Protocol dispatch rewrite.
- `.claude/workflow/workflow-drift-check.md` — shrink to reference doc.
- `.claude/workflow/branch-divergence-check.md` — shrink to reference doc.
- `.claude/workflow/conventions.md` — §1.6(h) pivot (keep spec + add pointer).
- `.claude/workflow/commit-conventions.md` — § Push failure handling re-entry.
- `.claude/skills/migrate-workflow/SKILL.md` — Step 2 `migrate-range` reuse.

**Out of scope:**
- `.claude/scripts/workflow-startup-precheck.sh` and `.claude/scripts/tests/` — authored live by Tracks 1-3, not staged (§1.7(a) does not govern `.claude/scripts/`; D6).
- Any live `.claude/workflow/**` or `.claude/skills/**` file — stays at develop state until the Phase 4 promotion (S4 / I6).

**Staging discipline:** staged-subtree layout per `§1.7(a)`; the `§1.7(b)` marker is declared in the plan's Constraints; reads resolve staged-first per `§1.7(d)`; first touch copies the live file verbatim then edits per `§1.7(e)`; the Phase 4 promotion rebases onto develop first per `§1.7(f)` and is additive and re-entrant per `§1.7(j)`.

**Dependencies:** depends on Tracks 1, 2, and 3 — the `--mode full`, `divergence-only`, and `migrate-range` JSON shapes (including `state` and `actions_taken`) must be final before the prose cites them. No downstream track consumes this one; the Phase 4 promotion is the next consumer.
