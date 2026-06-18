---
review_type: adversarial
scope: research-log
phase: 1
target: docs/adr/state-ledger-fix/_workflow/research-log.md
iteration: 1
verdict: pass-with-findings
findings: 5
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: "research-log.md §Decision Log D1"
    cert: "Challenge: Decision D1 — Bundle the A→C ledger append into step 6"
    basis: decision
  - id: A2
    sev: should-fix
    anchor: "### A2 "
    loc: "research-log.md §Decision Log D2"
    cert: "Challenge: Decision D2 — doc-presence regression guard"
    basis: decision
  - id: A3
    sev: should-fix
    anchor: "### A3 "
    loc: "research-log.md §Decision Log D3"
    cert: "Assumption test: staging leaves this branch's own Phase A on the buggy live file"
    basis: assumption
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    loc: "research-log.md §Decision Log D1"
    cert: "Challenge: Decision D1 — orchestrator auto-append vs doc instruction"
    basis: decision
  - id: A5
    sev: suggestion
    anchor: "### A5 "
    loc: "research-log.md §Surprises & Discoveries (entry 6, --ctx default)"
    cert: "Assumption test: the two sibling append snippets omit --ctx; D1 adds it"
    basis: assumption
evidence_base: "5 certificates — 3 decision challenges (D1 placement, D2 guard strength, D1 auto-append alternative), 2 assumption tests (D3 self-infliction, D1 --ctx consistency). All premises in the log's Decision Log and Surprises were grep+Read verified against track-review.md, workflow-startup-precheck.sh, workflow.md, conventions.md §1.7(b)/(k), and test_workflow_startup_precheck.py. No blocker: the central fix (append phase=C in step 6) is correct and the script/test support is real. The findings strengthen rationale, harden the guard, and surface an operational wrinkle the log understates."
---

## Findings

### A1 [should-fix]
**Certificate**: Challenge: Decision D1 — Bundle the A→C ledger append into step 6
**Target**: Decision D1
**Challenge**: D1 places the `--append-ledger --phase C --track <N>` call in §What You Do step 6 and adds a ledger-tail check to §Phase A Completion step 2. The placement is correct, but the log names only one rejected alternative (the issue's literal §Phase A Completion-after-step-2 placement) and does not address the strongest *robustness* gap: §Phase A Completion step 2's interruption-recovery path. Step 2 says "if the commit is missing... run step 6 now" (verified at `track-review.md:1015-1018`). After the fix, that recovery re-runs step 6 *including* the append — which is the right behavior and actually a point in D1's favor. But step 2's verification, as D1 describes it, checks the `phase=C track=<N>` ledger *tail*; it does not say what to do when the tail check fails while the *commit* check passes (commit landed, but a hand-edited or partially-written ledger lacks the boundary). Without a "re-run the append, re-commit, re-push" recovery for that sub-case, a green commit + stale ledger silently re-routes the next session to a Phase A re-run — the exact failure mode the fix targets, now relocated into step 2's blind spot.
**Evidence**: `track-review.md:1012-1018` — step 2 verifies commit + clean tree and has a "run step 6 now" path for a *missing commit*, but no path for "commit present, ledger tail wrong." Step 6's append is verified to land in the same commit (`track-review.md:588-592` git add/commit/push of the track file; D1 adds `phase-ledger.md` to that `git add`), so the only way to reach commit-present-but-tail-wrong is a corrupted/hand-edited ledger — a real edge given the ledger is append-only plain text. `determine_state_from_ledger` at `workflow-startup-precheck.sh:1773-1779` reads the last `phase` value, so any tail not ending in `phase=C` routes to `{phase:"A"}` → Phase A re-run.
**Proposed fix**: In D1, specify that §Phase A Completion step 2's new ledger-tail check carries its own recovery: when the commit is present but the tail is not `phase=C track=<N>`, re-run the step-6 append + a dedicated `git add phase-ledger.md && git commit && git push`, then re-verify. This closes the relocated blind spot and makes the verification a true gate, not an advisory read.

### A2 [should-fix]
**Certificate**: Challenge: Decision D2 — doc-presence regression guard
**Target**: Decision D2
**Challenge**: D2's guard is `grep -- '--phase C' .claude/workflow/track-review.md` non-empty. This is a substring presence check, not a behavior check: it passes if `--phase C` appears anywhere in the file — in a comment, in the §Phase A Completion *verification* prose (D1 itself adds a `phase=C track=<N>` tail-check mention to step 2, which already contains the literal `phase=C`), in a future unrelated example, or in a syntactically broken/commented-out append. The guard would already pass *today on this very branch's edits* purely from D1's step-2 verification text, even if the actual append call in step 6 were deleted. So the standing guard does not actually guard the instruction it claims to — it guards the appearance of a string. The log states the gap the bug exposed is "the missing doc instruction," but the guard does not assert the instruction is present *in the right place* (step 6's append site) or *in executable form* (the full `--append-ledger --phase C --track` invocation, not a bare `--phase C` fragment).
**Evidence**: `implementation-review.md:646` and `track-code-review.md:1403-1405` show the canonical append-site shape the guard should match: a full `workflow-startup-precheck.sh --append-ledger --phase <X>` line, not a bare flag. A bare `grep -- '--phase C'` matches the verification prose D1 adds to step 2 (`track-review.md` step 2 will contain `phase=C track=<N>`), so the guard self-satisfies from the wrong location. The cited script tests (`test_workflow_startup_precheck.py:3426, 3447`) already pin the *behavior*; the doc guard's only job is to pin the *call site*, and a substring grep does not do that.
**Proposed fix**: Tighten the guard to match the full executable invocation anchored to the append, e.g. `grep -- 'workflow-startup-precheck.sh --append-ledger --phase C --track' .claude/workflow/track-review.md` (or a Python doc-presence test in the existing test module asserting the line appears under the §What You Do step 6 heading). Either form fails when the real append is dropped, where the bare-fragment grep would not.

### A3 [should-fix]
**Certificate**: Assumption test: staging leaves this branch's own Phase A on the buggy live file
**Target**: Decision D3 (assumption)
**Challenge**: D3 chooses §1.7(b) staging and reasons that "staging keeps live workflow at develop state while this branch runs its own Phase A, so the fix does not alter the branch's own execution mid-flight." That is correct and is the right call — but it has an unstated operational consequence the log does not surface: because the orchestrator running *this* branch's own Phase A reads the **live** (develop-state) `track-review.md`, and the live file does not yet have the fix, **this branch's own Phase A completion will omit the `phase=C` append and a resume of this branch will re-run Phase A** — the branch self-inflicts the very bug it fixes. §1.7(d) names the implementer and review agents as the staged-first read consumers; the orchestrator executing the Phase A completion procedure reads live by design. The operator running this branch needs to know to expect (and manually work around) the stale `phase=A` on resume, exactly as YTDB-1140's own originating branch (`understandable-design`) did — the MEMORY note records that branch manually appending `phase=C track=1` to route the precheck.
**Evidence**: `conventions.md:1094-1104` §1.7(d) — staged-first reads are scoped to the implementer's per-spawn read site and review agents; the orchestrator's own execution-procedure reads are not re-pointed, so this branch's Phase A runs the unfixed live `track-review.md`. `conventions.md:1362-1376` §1.7(k) criterion (2) confirms `track-review.md` is execution-procedure (fails the opt-out, must stage) — which is exactly why the fix cannot self-apply this branch. The branch is currently at develop tip `5de2481272`, 0 commits ahead (verified), so no staged copy exists yet and the live file is what its own Phase A will read.
**Proposed fix**: Add a one-line operational note to D3 (or §Baseline and re-validation): "Because the fix is staged, this branch's own Phase A completion will hit the bug — expect a stale `phase=A` on resume of this branch and append `phase=C track=1` by hand (as `understandable-design` did) to route the precheck to Phase B/C." This costs nothing, prevents a confused re-run, and documents the self-application paradox the staging decision accepts.

### A4 [suggestion]
**Certificate**: Challenge: Decision D1 — orchestrator auto-append vs doc instruction
**Target**: Decision D1
**Challenge**: D1 frames the fix as a *doc instruction* added to step 6 ("the script and its tests already support the append; only the orchestrator-facing doc instruction is missing"). An alternative the log never names: make the append a side effect that does not depend on the orchestrator faithfully running a prose step at all. The script already owns ledger writes; a `--complete-phase-a` (or equivalent) helper, or having the existing track-file-commit path emit the boundary, would remove the human/LLM-in-the-loop failure mode that produced YTDB-1140 in the first place — a prose step an orchestrator can skip. The whole bug class is "orchestrator did not run a documented step." Re-encoding the transition as a prose step keeps that class alive: a future orchestrator can drop the new step exactly as it dropped the (never-existing) old one. The chosen approach survives because it matches the established sibling pattern (every other transition is a prose-instructed append: `implementation-review.md:646`, `track-code-review.md:1403/1405`) and consistency has real value, but the rationale should say *why* prose-instruction is preferred over a more robust script-driven append rather than treating "doc-only" as the obvious framing.
**Evidence**: All three existing append sites are prose-instructed orchestrator steps, not script-internal side effects (`implementation-review.md:646`, `track-code-review.md:1403-1405`), so D1 is consistent with the codebase — but that same pattern is what left the A→C gap unfilled. The script's `--append-ledger` is a thin generic writer (`workflow-startup-precheck.sh:120, 1576`); nothing structurally prevents a phase-completion-specific subcommand.
**Proposed fix**: Add one sentence to D1's **Why** explaining the deliberate choice to stay prose-instructed (parity with the four sibling append sites, single ledger-writer surface, no new script behavior to test) over a script-driven auto-append — so the consistency rationale is explicit and the robustness trade-off is on record.

### A5 [suggestion]
**Certificate**: Assumption test: the two sibling append snippets omit --ctx; D1 adds it
**Target**: Decision D1 / Surprises entry 6 (assumption)
**Challenge**: Surprises entry 6 records that the two sibling append snippets (State 0→A, track completion) omit `--ctx` and rely on the script's `safe` default, calling this "inconsistent with D12's mandated accurate read, but pre-existing and out of this fix's scope." D1 then *adds* `--ctx <level>` to the new step-6 append (reusing the D12 read). This is the right call, but it creates a new local inconsistency: the new A→C append will carry an accurate `[ctx=…]` while its two siblings still hard-default to `safe`. A reader of the ledger will see one accurate ctx and two always-`safe` entries with no signal that the difference is incidental. The assumption that "matching D12 here, leaving siblings alone" is clearly better holds, but the divergence should be acknowledged so a future reader does not "fix" the new append back to the sibling (no-`--ctx`) form for consistency, silently regressing the accurate read.
**Evidence**: `workflow-startup-precheck.sh:1576` (`ctx="${LEDGER_CTX:-safe}"`) and the arg parser at `:145-147` confirm `--ctx` is optional and unvalidated beyond rejecting spaces/newlines (`bare` check at `:1577`), so the D12 fallback token `unknown` (`track-review.md:564-565`) is accepted verbatim — the reuse is mechanically sound. The two sibling snippets at `implementation-review.md:646` and `track-code-review.md:1403/1405` carry no `--ctx`, confirming the divergence.
**Proposed fix**: In Surprises entry 6 or D1, add a half-sentence: "the new step-6 append carries `--ctx <level>` (D12-accurate) while the two siblings keep the `safe` default; this divergence is intentional, not to be 'normalized' back to no-`--ctx`." Optionally note the siblings are a candidate cleanup for a future workflow-prose pass (out of this fix's scope, as the log already states).

## Evidence base

#### Challenge: Decision D1 — Bundle the A→C ledger append into step 6
- **Chosen approach**: Place `--append-ledger --phase C --track <N>` in `track-review.md` §What You Do step 6, before its `git commit`, add `phase-ledger.md` to the step's `git add`, reuse the D12 `<level>` for `--ctx`, and add a `phase=C track=<N>` tail-check to §Phase A Completion step 2.
- **Best rejected alternative**: The issue's literal placement in §Phase A Completion after step 2 (correctly rejected by D1 — it dirties the just-verified-clean tree).
- **Counterargument trace**:
  1. In the interruption-recovery scenario, §Phase A Completion step 2 has a "commit missing → run step 6 now" path (`track-review.md:1015-1018`); after the fix this correctly re-runs the append (a point in D1's favor).
  2. But step 2's new tail-check has no recovery for "commit present, ledger tail not `phase=C track=<N>`" (a corrupted/hand-edited append-only text ledger).
  3. `determine_state_from_ledger` reads the last `phase` value (`workflow-startup-precheck.sh:1766-1779`); any non-`phase=C` tail routes to `{phase:"A"}` → Phase A re-run — the relocated original failure.
- **Codebase evidence**: `track-review.md:1012-1018` (step 2 recovery only for missing commit); `track-review.md:560-592` (step 6 D12 read + the git add/commit/push the append rides); `workflow-startup-precheck.sh:1773-1779` (last-`phase`-wins read).
- **Survival test**: WEAK — the placement is correct and the clean-tree/D12-reuse rationale holds, but the step-2 verification needs an explicit ledger-tail recovery branch to be a true gate.

#### Challenge: Decision D2 — doc-presence regression guard
- **Chosen approach**: `grep -- '--phase C' .claude/workflow/track-review.md` non-empty as a standing regression guard.
- **Best rejected alternative**: A doc-presence test in `test_workflow_startup_precheck.py` (or the full-invocation grep) asserting the executable append line is present at the step-6 site.
- **Counterargument trace**:
  1. The bare `--phase C` substring appears in D1's own step-2 verification prose (`phase=C track=<N>` tail-check text), so the guard self-satisfies from the wrong location.
  2. It would pass even with the real step-6 append deleted, in a comment, or syntactically broken.
  3. So the guard pins a string's appearance, not the instruction's presence-in-place-and-form — it cannot catch the regression class it was written for.
- **Codebase evidence**: `implementation-review.md:646`, `track-code-review.md:1403-1405` (canonical full-invocation append shape the guard should match); `test_workflow_startup_precheck.py:3426, 3447` (behavior already pinned by script tests, leaving the guard's only job = call-site presence).
- **Survival test**: WEAK — a guard is the right idea (CLAUDE.md requires a regression check), but the bare-substring form does not actually guard the instruction.

#### Assumption test: staging leaves this branch's own Phase A on the buggy live file
- **Claim**: D3 — staging means "the fix does not alter the branch's own execution mid-flight" (presented as a clean benefit).
- **Stress scenario**: This branch reaches Phase A completion for its single track and the session ends; the user re-runs `/execute-tracks`.
- **Code evidence**: `conventions.md:1094-1104` §1.7(d) scopes staged-first reads to the implementer and review agents; the orchestrator's own execution-procedure reads stay live, so this branch's Phase A runs the unfixed live `track-review.md`, omits the `phase=C` append, and the precheck reads `phase=A` (`workflow-startup-precheck.sh:1773-1779`) → Phase A re-run. `conventions.md:1362-1376` confirms `track-review.md` is execution-procedure (must stage), which is precisely why it cannot self-apply. MEMORY records the originating branch `understandable-design` manually appending `phase=C track=1` to work around this.
- **Verdict**: HOLDS (the decision is correct) but the consequence is undocumented — the branch self-inflicts the bug and the operator gets no warning.

#### Challenge: Decision D1 — orchestrator auto-append vs doc instruction
- **Chosen approach**: Re-encode the A→C transition as a prose orchestrator step (parity with the three existing append sites).
- **Best rejected alternative**: A script-driven append (a phase-completion subcommand or commit-path side effect) that does not depend on the orchestrator running a prose step.
- **Counterargument trace**:
  1. YTDB-1140's root cause is "orchestrator did not run a documented step."
  2. A prose step keeps that failure class alive — a future orchestrator can drop the new step as it dropped the never-existing old one.
  3. A script-owned append removes the human/LLM-in-the-loop failure mode entirely.
- **Codebase evidence**: All three existing append sites are prose-instructed (`implementation-review.md:646`, `track-code-review.md:1403-1405`) — consistency favors prose, but that same pattern produced the gap; `workflow-startup-precheck.sh:120, 1576` shows the writer is a thin generic flag with no structural barrier to a phase-completion-specific helper.
- **Survival test**: YES — the chosen approach survives on consistency grounds, but the rationale should state why prose-instruction is preferred over a more robust script-driven append.

#### Assumption test: the two sibling append snippets omit --ctx; D1 adds it
- **Claim**: Surprises entry 6 — the sibling snippets' `--ctx` omission is "pre-existing and out of scope"; D1 adds `--ctx <level>` to the new append.
- **Stress scenario**: A future reader normalizes the new append back to the sibling (no-`--ctx`) form for consistency, regressing the D12-accurate read.
- **Code evidence**: `workflow-startup-precheck.sh:1576` (`ctx` defaults to `safe`), `:145-147` (`--ctx` optional), `:1577` (`bare` validation accepts `unknown`); `track-review.md:564-565` (D12 fallback emits `unknown`); siblings at `implementation-review.md:646`, `track-code-review.md:1403/1405` carry no `--ctx`.
- **Verdict**: HOLDS — adding `--ctx` is mechanically sound and correct, but the intentional divergence from the siblings should be noted so it is not "fixed" back.
