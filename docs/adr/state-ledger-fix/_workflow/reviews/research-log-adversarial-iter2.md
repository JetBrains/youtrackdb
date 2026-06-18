---
review_type: adversarial
scope: research-log
phase: 1
iteration: 2
variant: verdict-producer
target: docs/adr/state-ledger-fix/_workflow/research-log.md
prior_review: docs/adr/state-ledger-fix/_workflow/reviews/research-log-adversarial-iter1.md
verdict: needs-revision
findings: 1
verdicts:
  - id: A1
    sev: should-fix
    verdict: VERIFIED
    note: "D1 now adds the step-2 ledger-tail recovery branch (commit-present, tail-wrong → re-run step-6 append + dedicated git add/commit/push + re-verify), mirroring the missing-commit recovery."
  - id: A2
    sev: should-fix
    verdict: VERIFIED
    note: "D2 now matches the full executable invocation `workflow-startup-precheck.sh --append-ledger --phase C --track`, and Alternatives rejected (a) explicitly rejects the bare `--phase C` substring."
  - id: A3
    sev: should-fix
    verdict: VERIFIED
    note: "D3 adds an explicit `Accepted self-application wrinkle` paragraph: this branch reads the live unfixed file, self-inflicts the bug, expect stale phase=A on resume, append phase=C track=1 by hand as understandable-design did."
  - id: A4
    sev: suggestion
    verdict: VERIFIED
    note: "D1 Why now states the deliberate prose-instructed (not script-auto-append) choice — parity with the four sibling sites, single ledger-writer surface, no new script behavior — and Alternatives rejected (b) elaborates the script-driven option."
  - id: A5
    sev: suggestion
    verdict: VERIFIED
    note: "Surprises entry 6 now records the intentional --ctx divergence (new append D12-accurate; siblings keep `safe`) and the do-NOT-normalize instruction plus the future-cleanup note."
index:
  - id: A6
    sev: should-fix
    anchor: "### A6 "
    loc: "research-log.md §Decision Log D2 (guard regex) vs D1 (--ctx in the step-6 call)"
    cert: "Assumption test: the tightened D2 guard's contiguous substring breaks on the convention-ordered --ctx arg D1 prescribes"
    basis: assumption
evidence_base: "Five prior findings A1-A5 all VERIFIED against the current Decision Log D1/D2/D3, Surprises entry 6, and Baseline — each iter-1 should-fix/suggestion is addressed in the text, grep+Read confirmed against live track-review.md (step 6 lines 581-598, §Phase A Completion step 2 lines 1012-1018), workflow-startup-precheck.sh (--ctx default safe :1576, C) case :1781, last-value-wins :1766), conventions.md §1.7(k) criterion (2) :1362-1376 and §1.7(d). One NEW should-fix (A6): the iter-1 A2 guard-tightening and the A5/D1 --ctx addition now interact — the canonical arg order documented in the script Usage (:18-20, :120) and the existing test (:3365-3366) puts --ctx BEFORE --phase, so the convention-ordered step-6 call `--append-ledger --ctx <level> --phase C --track <N>` does NOT contain D2's contiguous substring `--append-ledger --phase C --track`, making the guard a false-negative against the correct implementation. No blocker. NEEDS REVISION on the one new should-fix."

---

## Findings

### A6 [should-fix]
**Certificate**: Assumption test: the tightened D2 guard's contiguous substring breaks on the convention-ordered `--ctx` arg D1 prescribes
**Target**: Decision D2 (guard regex) in interaction with Decision D1 (the `--ctx <level>` the step-6 append carries) and Surprises entry 6
**Challenge**: The iter-1 fixes for A2 (tighten the guard to the full invocation) and A5 (D1 adds `--ctx <level>` to the new append) now collide. D2's guard, as written in the current log, is the *contiguous* substring `grep -- 'workflow-startup-precheck.sh --append-ledger --phase C --track' .claude/workflow/track-review.md`. But D1 instructs the implementer to "reuse the `<level>` step 6 already read ... to feed `--ctx`," and the canonical argument order — documented in the script's own Usage banner (`[--ctx <level>] [--phase <token>] [--track <n>]`, `--ctx` first) and matched by the existing append test (`"--append-ledger", "--ctx", "safe", ...` first) — places `--ctx <level>` *between* `--append-ledger` and `--phase`. So the convention-following step-6 line the implementer will write is `workflow-startup-precheck.sh --append-ledger --ctx <level> --phase C --track <N>`, in which the contiguous substring `--append-ledger --phase C --track` does not appear. The guard returns empty and fails against the *correct* implementation — a false negative. The guard tightened to close A2's false-positive now opens a false-negative, and worse, it silently pressures the implementer to write the args in a non-canonical order (`--phase` before `--ctx`) purely to satisfy the grep, fighting the established sibling/Usage convention.
**Evidence**: `workflow-startup-precheck.sh:18-20` and `:120` — Usage documents `--append-ledger [--ctx <level>] [--phase <token>] [--track <n>]`, `--ctx` first. `test_workflow_startup_precheck.py:3364-3366` — the "every field set" append test writes `"--append-ledger", "--ctx", "safe", "--phase", ...` with `--ctx` first, confirming the convention. Current log D2 (research-log.md:68) pins the contiguous form `'workflow-startup-precheck.sh --append-ledger --phase C --track'`; D1 (research-log.md:31-32) mandates the `--ctx <level>` reuse. The two cannot both hold for a convention-ordered call.
**Proposed fix**: Loosen D2's guard so it does not assume `--phase` immediately follows `--append-ledger`. Options, any one suffices: (a) anchor on the order-stable tail only — `grep -- '--append-ledger' track-review.md | grep -- '--phase C --track'`, or a single `grep -E -- '--append-ledger .*--phase C --track'`; (b) anchor on `--phase C --track` alone (still pins phase-token + track-flag together, the load-bearing pair, and cannot be satisfied by D1's step-2 verification prose which carries `phase=C track=<N>` not the flag form `--phase C --track`); (c) the already-offered Python doc-presence test, which can tokenize the line and assert the flags are present regardless of order. Note in D2 that the guard must be order-tolerant for `--ctx`, and (optionally) state the canonical arg order in D1 so the step-6 line and the guard are written consistently.

## Evidence base

#### Assumption test: the tightened D2 guard's contiguous substring breaks on the convention-ordered `--ctx` arg
- **Claim**: D2's guard `grep -- 'workflow-startup-precheck.sh --append-ledger --phase C --track'` will match the step-6 append line D1 prescribes.
- **Stress scenario**: The implementer writes the step-6 append in canonical argument order (the order the script Usage and the existing test use), interposing `--ctx <level>` between `--append-ledger` and `--phase`.
- **Code evidence**: `workflow-startup-precheck.sh:18-20`/`:120` (Usage: `--ctx` listed before `--phase`); `test_workflow_startup_precheck.py:3364-3366` (test writes `--ctx safe` before `--phase`); current log D2 (`research-log.md:68`, contiguous substring) and D1 (`research-log.md:31-32`, reuse `<level>` for `--ctx`). With `--ctx <level>` between `--append-ledger` and `--phase`, the contiguous substring the guard greps for is absent → guard returns empty → false negative against the correct call.
- **Verdict**: BREAKS — the convention-ordered, correct step-6 line does not contain D2's contiguous substring; the iter-1 A2 fix and A5 `--ctx` addition interact to make the guard reject the right implementation. (Survival: WEAK — the guard idea is right; the contiguous-substring form is too brittle once `--ctx` is in the line.)

#### Verdict basis: A1 — recovery branch for commit-present-but-tail-wrong
- **Prior finding**: D1 must add a recovery branch to §Phase A Completion step 2 for "commit present, ledger tail wrong."
- **Current text**: D1 (`research-log.md:33-39`) adds the `phase=C track=<N>` tail-check to step 2 "**with its own recovery branch**: when the Phase A commit is present but the ledger tail is not `phase=C track=<N>` ... re-run the step-6 append, make a dedicated `git add phase-ledger.md && git commit && git push`, then re-verify. This mirrors step 2's existing missing-commit recovery."
- **Cross-check**: Live `track-review.md:1015-1018` confirms step 2's existing missing-commit recovery ("run step 6 now") the new branch mirrors; `workflow-startup-precheck.sh:1766` (last-value-wins) + `:1781` (C) case) confirm a non-`phase=C` tail routes to Phase A re-run, the failure the recovery closes. The recovery's `git add phase-ledger.md` is correct: the ledger is a `_workflow/` artifact, not a `.claude/**` file, so it is not staged under §1.7(b) and is committed directly.
- **Verdict**: VERIFIED.

#### Verdict basis: A2 — full-invocation guard
- **Prior finding**: D2's guard must match the full `--append-ledger --phase C --track` invocation, not a bare `--phase C` substring.
- **Current text**: D2 (`research-log.md:62-86`) now matches `'workflow-startup-precheck.sh --append-ledger --phase C --track'` and Alternatives rejected (a) explicitly drops the bare `--phase C` substring as self-satisfied by the step-2 verification prose.
- **Verdict**: VERIFIED. (Note: the resulting contiguous form introduces the new brittleness captured in A6 — VERIFIED that the finding was addressed, but the chosen form needs the A6 loosening.)

#### Verdict basis: A3 — self-application wrinkle documented
- **Prior finding**: D3/Baseline must document that this branch's own Phase A hits the bug under staging.
- **Current text**: D3 (`research-log.md:104-111`) adds "**Accepted self-application wrinkle:**" — the orchestrator reads the live develop-state file lacking the fix, "this branch self-inflicts the bug. Expect a stale `phase=A` on resume ... append `phase=C track=1` by hand ... exactly as the originating branch `understandable-design` did."
- **Cross-check**: `conventions.md:1362-1376` §1.7(k) criterion (2) confirms `track-review.md` is execution-procedure (a running phase reads it as orchestration loop) → fails the opt-out, must stage → the wrinkle is real and correctly attributed to staging.
- **Verdict**: VERIFIED.

#### Verdict basis: A4 — prose-instructed-vs-script-auto-append rationale
- **Prior finding**: D1's Why should state the deliberate prose-instructed choice over a script-driven auto-append.
- **Current text**: D1 Why (`research-log.md:44-50`) states the transition "stays a prose-instructed orchestrator append (not a new script-driven auto-append subcommand) for parity with the four sibling append sites ..., a single ledger-writer surface, and no new script behavior to test"; Alternatives rejected (b) details the `--complete-phase-a` script-driven option and why it is broader than the bug warrants.
- **Cross-check**: The four sibling prose-instructed append contexts confirmed live — `implementation-review.md:646` (`--phase A`), `track-code-review.md:1403/1405` (`--track`/`--phase D`), `inline-replanning.md:169/249`, `mid-phase-handoff.md:186/187`; all are prose-instructed orchestrator steps, so the parity claim holds.
- **Verdict**: VERIFIED.

#### Verdict basis: A5 — intentional --ctx divergence noted
- **Prior finding**: Note the intentional `--ctx` divergence between the new append and its siblings.
- **Current text**: Surprises entry 6 (`research-log.md:144-153`) records "the new step-6 append carries `--ctx <level>` (D12-accurate) while the two siblings keep the `safe` default. This divergence is intentional — do NOT 'normalize' the new append back to the no-`--ctx` sibling form ... candidate cleanup for a future workflow-prose pass."
- **Cross-check**: `workflow-startup-precheck.sh:1576` (`ctx="${LEDGER_CTX:-safe}"`) confirms the default; the two sibling snippets carry no `--ctx`, confirming the divergence the entry documents.
- **Verdict**: VERIFIED.
