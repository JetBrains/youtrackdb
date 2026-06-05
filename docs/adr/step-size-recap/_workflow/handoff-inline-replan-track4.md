# Handoff: inline replan — add Track 4 (YTDB-1068 reindex rule_1 fix)

**Paused:** 2026-06-05
**Phase:** B complete (Track 3) → inline replan pending (add Track 4)
**Context level at pause:** info (user-initiated pause, not context pressure)
**Branch:** step-size-recap
**HEAD:** 70de2c6c119489870948e5c8c90582e1096ebff8 "Mark Track 3 Phase B (step implementation) complete"
**Unpushed:** 0 commits (the handoff commit itself will be pushed)

## Durable artifacts on disk

- **Track 3 Phase B is COMPLETE and committed.** All 5 steps `[x]` with episodes; `## Progress` `Step implementation` marked `[x]`. Step commits: 1=`25a74f4394`, 2=`a97bea18b5`, 3=`6cb1c00ec7`, 4=`2591bcbf2f`, 5=`7ab849e8f2`. Tip `70de2c6c`.
- **D8 calibration (DL6) landed**: track-footprint checks use a `~20-25`-file track-level ceiling, distinct from per-step `~12`/`~5`. Recorded in `design.md` §"Scope indicators measure file footprint, not steps" (design-mutations Mutation 4), `implementation-plan.md` D8 revision note, and `track-3.md` DL6 + Surprises.
- **Phase B self-improvement reflection already ran this session: 0 findings.** Do NOT re-run Phase B reflection on resume.
- **No Track 4 artifacts exist yet.** The decision to add Track 4 lives only in this handoff.

## Agreed action (no further user decision pending)

The user chose **Track 4 in this branch** over a separate PR (self-contained — no rebase-on-develop needed for this branch's CI; branch is already multi-issue dev-workflow machinery and surfaced the bug). Execute an inline replan (`inline-replanning.md` §Process, case 1 "New track") to add **Track 4: fix YTDB-1068** — the `workflow-reindex.py` rule_1 false-positive on staged copies.

## The fix (grounded — do not re-derive)

- `check_rule_1_stamp_present` at `.claude/scripts/workflow-reindex.py:1544` exempts only non-`docs/adr/` paths (line 1562), then demands a line-1 `workflow-sha` stamp on everything else.
- The script's `IN_SCOPE_GLOBS` (lines 142-155) are ALL the staged-workflow mirror (`docs/adr/*/_workflow/staged-workflow/.claude/...`). Per §1.7(e) those are verbatim copies of UNSTAMPED live files, so they correctly carry no stamp → rule_1 false-positives on every one. CI `workflow-toc-check.yml` runs `--check` on non-draft PRs → fails at ready-time.
- **Fix:** exempt the staged-workflow subtree from rule_1. The regex already exists: `_STAGED_PREFIX_RE = re.compile(r"^docs/adr/[^/]+/_workflow/staged-workflow/")` at line 167 — reuse it (`if _STAGED_PREFIX_RE.match(parsed.path): return []`).
- **WRINKLE to resolve in Track 4 Phase A:** once the staged mirror is exempt, rule_1 may have no remaining in-scope target in THIS script (its globs are entirely the mirror). The plan's own stamped artifacts (`implementation-plan.md`, `design.md`, `plan/track-N.md`) ARE stamped per §1.6(f) but are NOT in this script's `IN_SCOPE_GLOBS`. Decide: keep rule_1 as a harmless guard, or document it as enforced elsewhere (the drift gate). The docstring at lines 1547-1560 currently says "rule 1 as a presence check for staged copies only" — that is now WRONG and must be synced.
- **Regression test:** an unstamped staged-mirror file must pass rule_1 (`--check` clean). Confirm the test home (look under `.claude/scripts/tests/`) and add/extend a case. The branch's own 18 staged copies are a live fixture.
- **Doc-sync:** the rule_1 docstring; and `conventions.md` §1.6(f)/§1.7(e) cross-ref only if the script's design text claims staged copies are stamped.
- **LIVE edit, not staged.** `.claude/scripts/` is outside the §1.7 staging scope (the marker covers `.claude/workflow/**` and `.claude/skills/**` only). Track 4 edits live files directly; the **I6 invariant is UNAFFECTED** (I6 constrains the staged set, not scripts). CI runs the live script from the branch tree, so the fix on this branch unblocks this branch's own gate.

## Inline-replan artifacts to produce next session

Run these in order (`inline-replanning.md` §Process + §"Updating plan and track files" case 1):

1. **edit-design** (`/edit-design` or the Skill) — `mutation_kind=content-edit`, `target=design`, `changed_section="Constraints: mirror, staging, and self-application"`, no mechanics companion. Add an Edge-cases bullet documenting the reindex rule_1 staged-mirror exemption (D9) and a `- D9: ...` line in that section's References footer. This gives DR D9 a resolvable `**Full design**` target. Logs as design-mutations Mutation 5.
2. **`implementation-plan.md`**:
   - Add DR **D9** immediately after D8 (before `#### Non-Goals`, line 130): standard DR format (Alternatives / Rationale / Risks-Caveats / **Implemented in:** Track 4 / **Full design:** `design.md §"Constraints: mirror, staging, and self-application"`).
   - Add the **Track 4** checklist entry after Track 3 (line 163): intro paragraph + `> **Scope:** ~N steps covering the rule_1 staged-mirror exemption, the regression test, and the docstring/cross-ref sync.` + `> **Depends on:** none (independent)`. Use **`~N steps`** (live convention, per D8's Non-Goal — matches Tracks 1-3).
   - **Reset `## Plan Review`** (line 166) to `- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of /execute-tracks` (inline-replanning §6 — routes the FOLLOWING session through State 0).
3. **Create `plan/track-4.md`** in the 14-section ExecPlan shape (line-1 `workflow-sha` stamp via the §1.6(b) idiom; track files ARE stamped). Track-level sections filled (Purpose, Context = the rule_1 bug, Plan of Work = fix + wrinkle + test + doc-sync, Interfaces = in-scope `.claude/scripts/workflow-reindex.py` + its test + the docstring [LIVE edits, NOT staged]; out-of-scope = the staged tree; independent track, I6 unaffected note, Validation track-level acceptance). Per-step sections as Phase A placeholders. `## Progress` with the 4 pre-seeded checkpoints. `## Base commit` = HEAD at the inline-replan commit (capture fresh).
4. **Structural review preview** (advisory, inline-replanning §4): spawn the structural-review sub-agent on the revised plan (`plan_path` + `plan_dir`). Fail-fast only; the real gate is the next session's State 0. Iterate max 3 on structural blockers.
5. **Commit** `Inline replan: add Track 4 (YTDB-1068 reindex rule_1 fix)` — stage `implementation-plan.md`, `plan/track-4.md`, `design.md`, `design-mutations.md`. Push.
6. **Update PR #1123**: title → `[YTDB-1062, YTDB-1068] Step sizing and reviewer routing`; add a description paragraph for the reindex fix (`gh pr edit 1123`).
7. **End that session.** The session after enters State 0 (re-review plan with Track 4), then resumes Track 3 Phase C → Track 3 completion → Track 4 Phase A/B/C → Phase 4.

## Resume notes

- **Do NOT redo:** Track 3 Phase B (complete, committed); the D8/DL6 calibration (landed); the Phase B reflection (ran this session, 0 findings).
- **Do NOT route straight to Track 3 Phase C.** This handoff file is the authoritative pause signal (`mid-phase-handoff.md` §Detection) — it overrides the precheck's state routing. Execute the inline replan FIRST. Only after the replan commit resets Plan Review does the FOLLOWING session do State 0 then Track 3 Phase C.
- The inline replan adds an INDEPENDENT track; no existing track or DR is invalidated, so this is a clean case-1 add (not a revision of Tracks 1-3).
- **YTDB-1068 remains the draft blocker** for PR #1123 until the Track 4 fix lands AND Phase 4 completes. The PR stays draft regardless this session.
- Delete this handoff (and its `## Plan Review` PAUSED marker + the MEMORY.md resume pointer) once the inline-replan commit (step 5) lands, per the resume protocol.
