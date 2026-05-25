# Track 6: Drift gate at /create-plan startup

## Purpose / Big Picture
After this track lands, `/create-plan` re-detects workflow drift at session start, so a re-invocation between planning sessions catches post-rebase drift before any research investment.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add a session-start invocation of the SHA-aware drift gate (rewritten in Track 3) to the `/create-plan` SKILL, between Step 1 (read workflow docs) and Step 1a (handoff scan). Without this gate, a re-invocation after the user rebases the branch to pick up critical workflow changes from develop would silently mutate `_workflow/**` artifacts on top of the drifted shape: stamps still point at the pre-rebase workflow tip, but HEAD's history has advanced to include the newly-imported workflow commits. The gate's `BASE_SHA..HEAD` walk surfaces those imported commits (D10) and routes the user through migration first. Three resolutions stay symmetric with `/execute-tracks` (Migrate now / Defer / Suppress). `/edit-design` runs only inside parent skills, so transitive coverage holds; no separate wiring there.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-24T19:50Z [ctx=info] Review + decomposition complete
- [x] 2026-05-24T20:00Z [ctx=info] Step 1 complete (commit 6bc6bf762b)
- [x] 2026-05-24T20:07Z [ctx=info] Step 2 complete (commit e06217e50f)
- [x] 2026-05-24T20:12Z [ctx=info] Step 3 complete (commit f8e6c406e0)
- [x] 2026-05-24T20:16Z [ctx=info] Step 4 complete (commit eb5d129611)
- [x] 2026-05-24T20:20Z [ctx=info] Step 5 complete (commit 8bb259dc00)
- [x] 2026-05-24T20:20Z [ctx=info] Step implementation complete (5/5 steps)

## Surprises & Discoveries

- 2026-05-24T20:00Z: After Step 1's intro rewrite, line numbers in Step 2's description are stale (+1 shift across the body). Step 2's implementer must re-resolve target lines against the post-Step-1 file rather than trust the roster's literal numbers. See Episodes §Step 1.
- 2026-05-24T20:07Z: Step 3's roster cites lines 392-396 (Migrate-now session-end framing) and 405-415 (Defer recital-surface description); both are stale post-Step-2. Step 2 inserted lines around § Detection's bullet list and § After-the-choice, so those sections sit approximately at lines 393-399 and 408-419 now. Step 3's implementer should re-resolve via grep on "End the session before reaching" and "State shape: a TaskCreate todo" rather than trust the roster's literal numbers. See Episodes §Step 2.
- 2026-05-24T20:12Z: Step 3 left § Defer's final residue-contract paragraph (post-edit lines 442-447) still citing only `workflow.md § What to do before ending a session` as the residue anchor, even though Step 3 caller-symmetrized the adjacent state-shape and recital-surface paragraphs. That paragraph is the residue-contract reference rather than the recital surface, so it falls outside Step 3's scope. Step 5 (`/create-plan` SKILL recital addition) is the natural place to either extend the rewrite to that paragraph or defer to a Phase C finding. See Episodes §Step 3.

## Decision Log

#### DL1: Full caller symmetry for `workflow-drift-check.md` and a new `/create-plan` recital surface

- **Alternatives considered**: Targeted scope (update intro paragraph + add Step 1.5; defer the 11 caller-specific surfaces to a Surprise or a follow-up track); inline replanning (split work across tracks); the chosen Full caller symmetry path.
- **Rationale**: Phase A's technical review iteration 1 ran the boundary-condition audit the Track 5 strategy-refresh signal recommended (implementation-plan.md:277). The audit surfaced 12 caller-specific surfaces in `workflow-drift-check.md` beyond the intro paragraph: 7 "step 4" references, 3 explicit `/execute-tracks` references, 2 "Startup Protocol step 3" references, Migrate-now's `workflow.md § What to do before ending a session` anchor, and § After the choice's "auto-resume decision" / "turn 1" framing. The original Plan-of-Work claim "the rest of the file stays caller-agnostic" was materially wrong, replicating the first→second-caller drift pattern Track 5 lived through with `self-improvement-reflection.md`. Targeted-scope would leave the gate file caller-asymmetric and force future Phase C escalations or a follow-up track; concentrating the cost here is one 5-step decomposition rather than 11 scattered later fixes. The Defer-from-`/create-plan` semantics question (`/create-plan` has no session-end recital protocol, so the TaskCreate todo would die with the session) is coupled to the same audit and is resolved by a minimal recital addition to `/create-plan` Step 5.
- **Risks/Caveats**: Track 6's complexity moves from Simple (2-3 steps) to Moderate (5 steps). No step crosses into HIGH-risk territory; all are markdown edits with `low` risk per `risk-tagging.md`. The session-end recital addition to `/create-plan` Step 5 is the only piece touching that SKILL beyond the gate invocation; the recital body reads from the same TaskCreate todo `workflow.md § What to do before ending a session` already reads for `/execute-tracks`.
- **Implemented in**: Track 6 — Steps 1 through 5 (decomposition below).

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

- [x] Technical: PASS at iteration 2 (5 findings, all accepted) — T1 (boundary-condition audit) drove the Plan-of-Work expansion from 2-3 steps to 5 work areas covering 12 caller-specific surfaces; T2 added the Defer-from-`/create-plan` recital at Step 5; T3 / T4 fold into decomposition (canonical `[ -d "$PLAN_DIR/_workflow" ]` wording, `<dir-name>` resolver-block ordering); T5 was a counting fix applied inline.

## Context and Orientation

`.claude/workflow/workflow-drift-check.md` is the gate `/execute-tracks` invokes in its turn-1 startup, between the Branch Divergence Check and the handoff scan. After Track 3 lands, the Detection logic reads per-artifact stamps in the active plan's `_workflow/` (D13) and the Migrate-now resolution wording points users at an in-branch `/migrate-workflow`. Skip conditions (active plan's `_workflow/` doesn't exist; active plan complete plus Phase 4 active; empty diff) carry over with scopes tightened by Track 3.

`.claude/skills/create-plan/SKILL.md` (the slash command implementing `/create-plan`) runs:
- Step 1: read workflow docs (`conventions.md`, `research.md`)
- Step 1a: handoff scan (resume protocol if `handoff-*.md` exists)
- Step 1b: create `_workflow/` directory
- Step 2: ask user for the aim
- Step 3-5: research → planning → commit + push

Two re-invocation cases the current flow misses:
- The user runs `/create-plan` Session A, hits the context-warning threshold, runs `/clear`, then re-invokes `/create-plan` to continue. The branch may have rebased onto a newer develop between sessions.
- The user proactively rebases the branch onto a newer develop between Session A and Session B to pull in critical workflow changes ASAP. After the rebase, HEAD's history contains imported workflow commits, but the branch's `_workflow/**` artifacts still carry pre-rebase stamps. Without a gate at `/create-plan` startup, Session B writes new-format sections atop drifted artifacts.

The SHA-stamp design (Track 1) and the SHA-aware gate (Track 3) handle the rebase case natively: the stamp records the workflow-SHA the artifact was synced to, the range `BASE_SHA..HEAD` walks every workflow commit reachable from HEAD since that stamp, and rebase-imported commits show up in that walk (D10). Track 6 wires `/create-plan` to invoke that gate at startup.

## Plan of Work

Five structural areas, per DL1's Full caller symmetry resolution. Each maps to one decomposed step.

1. **Generalize `workflow-drift-check.md`'s intro paragraph** to name both callers — `/execute-tracks` (turn 1) and `/create-plan` (between Step 1 and Step 1a). Today's intro mentions only `/execute-tracks` at line 3.

2. **Sweep caller-specific references in the body of `workflow-drift-check.md`** for caller-agnostic wording. The boundary-condition audit (technical review iteration 1) enumerated the surfaces: 7 "step 4" references (line 5 in the intro covered by area 1; lines 139, 144, 265, 282, 324, 453 in the body), 3 explicit `/execute-tracks` references (lines 261, 432, 466), 2 "Startup Protocol step 3" references (line 4 in the intro covered by area 1; line 462 in the body). Rewrite to caller-agnostic forms ("the calling session's next startup step", "the calling session") or qualify by caller where the anchor genuinely differs. Bash blocks and decision logic stay unchanged; this is a prose sweep.

3. **Update Migrate-now's session-end framing in `workflow-drift-check.md`** so the "End the session before reaching `workflow.md § What to do before ending a session`" anchor reads correctly for both callers. `/execute-tracks` uses that anchor; `/create-plan` ends the session naturally after Step 5 (commit + push). One paragraph covers both branches rather than two parallel paragraphs.

4. **Add Step 1.5 to `.claude/skills/create-plan/SKILL.md`** between the `<dir-name>` resolver block (SKILL.md:26-29) and Step 1a (SKILL.md:31). Step 1.5 invokes `.claude/workflow/workflow-drift-check.md`'s detection: run the bash, present the three-resolution prompt on drift, end the session on Migrate now (mirroring Track 3's in-branch wording — user runs `/migrate-workflow` in this worktree, then re-invokes `/create-plan`), continue silently on no-drift / Defer / Suppress. Ordering: Step 1.5 runs after the resolver (so `$PLAN_DIR` is defined) and before Step 1b's `mkdir` (so the gate's Skip-#1 check `[ -d "$PLAN_DIR/_workflow" ]` sees the pre-creation state on fresh invocations).

5. **Add a minimal session-end recital to `/create-plan` Step 5** so Defer-from-`/create-plan` has a host. After Step 5's `git push` (SKILL.md:419-423) and before Step 5's PR-prefix prompt (SKILL.md:424), append a recital that reads the deferred-drift TaskCreate todo (if any) and prints the same `Deferred workflow drift: <count> commits since <short-stamp-base-SHA>` line `workflow.md § What to do before ending a session` reads for `/execute-tracks`. Recital fires before the PR is opened so the user sees it in the same session.

Track 3's `workflow-drift-check.md` Detection / Defer / After-the-choice prose already accommodates this expansion at the structural level — Steps 2-3 rewrite caller-specific phrasing without re-architecting the gate's behavior.

## Concrete Steps

1. Generalize `.claude/workflow/workflow-drift-check.md`'s intro paragraph (lines 3-5 plus adjacent prose) to name both callers: `/execute-tracks` (turn 1) and `/create-plan` (between Step 1 and Step 1a). Line 5's "step 4" reference is rewritten as part of the intro generalization (out of scope for Step 2's body sweep); line 4's "Startup Protocol step 3" reference is similarly handled here. — risk: low (default)  [x]
2. Sweep `.claude/workflow/workflow-drift-check.md` body for caller-specific wording. Rewrite the 6 body "step 4" references (lines 139, 144, 265, 282, 324, 453), the 3 explicit `/execute-tracks` references (lines 261, 432, 466), the 1 body "Startup Protocol step 3" reference (line 462), the body "turn 1" / "handoff scan" references at line 276, and § After-the-choice's "auto-resume decision" framing at line 453 — to caller-agnostic forms ("the calling session's next startup step", "the calling session") or caller-qualified forms where the anchor genuinely differs. Bash blocks and decision logic stay unchanged. — risk: medium (override: 10+ coordinated edit sites in one file requiring mutual consistency; the same coordinate-edit pattern Track 5 Step 1 used the override for, warranting focal-point Phase C review across the workflow-consistency dimension)  [x]
3. Update `.claude/workflow/workflow-drift-check.md` Migrate-now § session-end framing (anchor "End the session before reaching `workflow.md § What to do before ending a session`" at lines 392-396) and Defer state-shape's recital-surface description (lines 405-415) to handle both callers conditionally. The Migrate-now rewrite folds both branches into one paragraph: `/execute-tracks` exits before `workflow.md § What to do before ending a session`; `/create-plan` exits before Step 5's commit/push. The Defer rewrite names per-caller recital surfaces: `/execute-tracks` reads the todo at `workflow.md § What to do before ending a session`; `/create-plan` reads it at the recital added in Step 5 of this track. — risk: low (default)  [x]
4. Add Step 1.5 (Workflow drift check) to `.claude/skills/create-plan/SKILL.md` between the `<dir-name>` resolver block (SKILL.md:26-29) and Step 1a (SKILL.md:31). The new step invokes `.claude/workflow/workflow-drift-check.md`'s detection: runs the bash, presents the three-resolution prompt on drift, ends the session on Migrate now with in-branch wording ("end the session; run `/migrate-workflow` from this worktree; re-invoke `/create-plan` afterward"), continues silently on no-drift / Defer / Suppress. Ordering: Step 1.5 must run after the resolver (so `$PLAN_DIR` is defined) and before Step 1b's `mkdir` (so the gate's Skip-#1 check `[ -d "$PLAN_DIR/_workflow" ]` sees the pre-creation state on fresh invocations). — risk: low (default)  [x]
5. Add a minimal session-end recital to `.claude/skills/create-plan/SKILL.md` Step 5. After the `git push -u origin <branch>` block (SKILL.md:419-423) and before the PR-prefix prompt (SKILL.md:424), insert a recital that reads the deferred-drift TaskCreate todo (if any was created in this session by Step 1.5's Defer resolution) and prints the same `Deferred workflow drift: <count> commits since <short-stamp-base-SHA>` line shape `workflow.md § What to do before ending a session` uses for `/execute-tracks`. The recital fires before the PR opens so the user sees it in the same session. If no todo exists, the recital is a silent no-op. — risk: low (default)  [x]

## Episodes

### Step 1 — commit 6bc6bf762b, 2026-05-24T20:00Z [ctx=info]
**What was done:** Rewrote the intro paragraph of `.claude/workflow/workflow-drift-check.md` (lines 3-14 in the post-edit file) to name both callers of the drift gate: `/execute-tracks` (turn 1, between Branch Divergence Check at workflow.md § Startup Protocol step 3 and the handoff scan at step 4) and `/create-plan` (between Step 1's workflow-docs read and Step 1a's handoff scan). The per-caller anchors fold into a single parenthetical on each caller, so the line-4 "Startup Protocol step 3" and line-5 "step 4" references that previously named only `/execute-tracks`'s gate position are now caller-qualified inline. The "Detection is one git log…" paragraph (lines 16-26) and everything below stay untouched; Step 2 sweeps the body's caller-specific phrasing, Step 3 handles Migrate-now / Defer / After-the-choice anchors.

**What was discovered:** Line numbers Phase A's audit cited in Step 2's roster description are stale post-Step-1. The rewritten intro grew from 11 to 12 lines, shifting every body reference by +1. Affected: the body "step 4" references (originally lines 139, 144, 265, 282, 324, 453), the `/execute-tracks` references (originally lines 261, 432, 466), and the "Startup Protocol step 3" body reference (originally line 462). Step 2's implementer must re-resolve these against the post-Step-1 file.

**What changed from the plan:** none

**Key files:**
- `.claude/workflow/workflow-drift-check.md` (modified)

**Critical context:** none

### Step 2 — commit e06217e50f, 2026-05-24T20:07Z [ctx=info]
**What was done:** Swept the body of `.claude/workflow/workflow-drift-check.md` for caller-specific wording, leaving the intro paragraph (lines 3-14, generalized by Step 1) untouched. Six body sites rewritten to caller-agnostic phrasing: four "startup continues to step 4" instances became "startup continues to the calling session's next startup step" (the `git log` outcome bullets at the bottom of § Detection, the diff-shape-guard success exit, the no-drift normalization post-commit exit, and the Skip-conditions first-match exit); the explicit `/execute-tracks` session-halt in the diff-shape-guard abort became "the calling session"; the "Detection runs in turn 1 before the handoff scan" framing in the Commit-shape paragraph became "Detection runs at session start before any phase work"; the "across `/execute-tracks` invocations" sentinel reasoning under § Defer became "across calling-session invocations"; the § After-the-choice "step 4 (handoff scan) and the auto-resume decision proceeds" sentence became "the calling session's next startup step (the handoff scan in both callers) and proceeds". The Remote-authoritative re-entry paragraph was caller-qualified rather than generalized; its lead-in now reads "forward-looking note (`/execute-tracks` only)" with an explicit sentence naming the scope, because the Branch Divergence Check that triggers Remote-authoritative reset lives only in `/execute-tracks`'s Startup Protocol; the embedded `/execute-tracks` anchors stay as legitimate caller-specific references.

**What was discovered:** The Phase A audit's "10+ coordinated sites in one file" resolved to six logical edit hunks (one hunk grouped the two adjacent step-4 lines in § Detection's outcome bullets; the rest were individual sites). The "Startup Protocol step 3" reference at body line 470 (post-Step-1 numbering) sits inside the Remote-authoritative re-entry paragraph and stayed as a legitimate caller-qualified anchor. The orchestrator's heads-up about Step 1's +1 line-number shift held: every roster line number was off by one, so re-resolving via grep was the right approach. Cross-track caveat: a future track that teaches the Branch Divergence gate to route post-reset back into the drift gate (the "step 3a" gap the Remote-authoritative paragraph anticipates) will need to drop the `/execute-tracks` qualifier and re-symmetrize that paragraph for both callers.

**What changed from the plan:** none

**Key files:**
- `.claude/workflow/workflow-drift-check.md` (modified)

**Critical context:** none

### Step 3 — commit f8e6c406e0, 2026-05-24T20:12Z [ctx=info]
**What was done:** Rewrote two paragraphs in `.claude/workflow/workflow-drift-check.md` to be caller-symmetric across `/execute-tracks` and `/create-plan`. The Migrate-now session-end paragraph (post-Step-2 lines 396-401, now 396-406) folds both callers into one paragraph: `/execute-tracks` exits before `workflow.md § What to do before ending a session`; `/create-plan` exits before Step 5's commit and push. The "no phase work has run" tail now scopes the Branch Divergence Check residue note to `/execute-tracks` only. The Defer state-shape paragraph (post-Step-2 lines 409-419, now 414-429) names per-caller recital surfaces rather than a single "session-end summary" target: `/execute-tracks` reads the todo at `workflow.md § What to do before ending a session`; `/create-plan` reads it at the recital that Step 5 of this track will add. The TaskCreate-unavailable fallback now recites "from whichever recital surface the calling session uses" to stay symmetric.

**What was discovered:** The orchestrator's heads-up about stale roster line numbers held; grep on the anchor phrases ("End the session before reaching", "State shape: a TaskCreate todo") was the right path, and the actual line numbers were 396 and 409 in the post-Step-2 file rather than the roster's 392-396 / 405-415. Adjacent surface noticed but deliberately left for later: § Defer's final residue-contract paragraph (post-edit lines 442-447) still cites only `workflow.md § What to do before ending a session`. That paragraph is the residue-contract surface, not the recital surface Step 3 targets, so it falls outside this step's scope. Step 5 (the `/create-plan` SKILL recital addition) is the natural place to either fold in that paragraph's caller-symmetrization or defer to a Phase C finding.

**What changed from the plan:** none

**Key files:**
- `.claude/workflow/workflow-drift-check.md` (modified)

**Critical context:** The Step 3 rewrite anticipates a recital surface that does not yet exist on disk ("the recital added in Step 5 of `/create-plan`"). The gate file is forward-referential between this commit and Step 5's commit. Once Step 5 lands, the gate file's description and `create-plan/SKILL.md`'s recital match; until then, `/create-plan` has no Defer recital host and the gate file describes Step 5's contract rather than today's behavior. Consistent with the Plan-of-Work's "Steps 2-3 rewrite caller-specific phrasing" model and the Decision Log entry coupling the recital addition to Step 5.

### Step 4 — commit eb5d129611, 2026-05-24T20:16Z [ctx=info]
**What was done:** Inserted a new "Step 1.5 — Workflow drift check" section into `.claude/skills/create-plan/SKILL.md` between the `<dir-name>` resolver block (lines 26-29) and Step 1a (now line 65). The new section is a thin orchestration handoff: it defers to `.claude/workflow/workflow-drift-check.md` (whose intro paragraph, body, Migrate-now wording, and Defer wording were already generalized for `/create-plan` by Steps 1-3 of this track), runs the gate's § Detection + § Skip conditions + § No-drift normalization + § Resolutions verbatim against `$PLAN_DIR = docs/adr/<dir-name>`, and recaps the three resolutions with caller-specific framing. Migrate now ends the `/create-plan` session and asks the user to run `/migrate-workflow` from this worktree, then re-invoke `/create-plan` afterward (matching the in-branch theme of Track 3). Defer points at the TaskCreate todo that Step 5's recital (not yet on disk) will read. Suppress continues silently. Ordering preserves the gate's Skip-#1 semantics: the new step runs after `$PLAN_DIR` is defined and before Step 1b's `mkdir`.

**What was discovered:** The ephemeral-identifier pre-commit gate produced seven matches, all of them references to other named sections of the same SKILL.md (Step 1a, Step 1b, Step 2, Step 5, Step 1.5). The SKILL.md has used "Step N" labels as durable in-document section headings since before this track; they are self-contained references inside a non-`_workflow/`, non-`.claude/workflow/` file that survives in the repo, not the workflow-internal "Step N of Track N" labels the rule forbids. The matches pass through as allowed self-contained section labels rather than durable leaks of workflow ephemera. Phase C reviewers can independently confirm by reading the file.

**What changed from the plan:** none

**Key files:**
- `.claude/skills/create-plan/SKILL.md` (modified)

**Critical context:** The new Step 1.5 description references a Step 5 recital that does not yet exist on disk; Step 5 of this track (the SKILL.md Step 5 recital addition) is still `[ ]` in the roster. Between this commit and Step 5's commit, `/create-plan`'s Step 1.5 documents a Defer recital host that Step 5 of the SKILL itself has not yet implemented. This forward reference is consistent with the forward reference Step 3 already introduced in `workflow-drift-check.md`'s § Defer paragraph. Once Step 5 lands, the SKILL's Step 1.5 description, Step 5's recital body, and `workflow-drift-check.md`'s § Defer paragraph all align.

### Step 5 — commit 8bb259dc00, 2026-05-24T20:20Z [ctx=info]
**What was done:** Inserted a new sub-step 3 ("Deferred-drift recital") into `.claude/skills/create-plan/SKILL.md` Step 5, between the `git push -u origin <branch>` block and the PR-prefix prompt. The recital reads the deferred-drift TaskCreate todo when Step 1.5's Defer resolution created one earlier in the session and recites the `Deferred workflow drift: <count> commits since <short-stamp-base-SHA>` line verbatim (plus the unstamped variant), followed by an instruction to run `/migrate-workflow` from this worktree. The recital is a silent no-op when no Defer resolution fired. Existing sub-steps 3-6 (PR-prefix prompt, title compose, body compose, `gh pr create`) shifted to 4-7 to preserve the linear "push then recite then open PR" reading; no other sites reference Step 5's sub-step numbers, so the shift is local.

**What was discovered:** The orchestrator's heads-up about line-number drift held; Step 4's Step 1.5 insertion shifted the push block from SKILL.md:419-423 to SKILL.md:453-457 and the PR-prefix prompt from :424 to :458. Grep on `git push -u origin` and the PR-prefix prompt's adjacent phrasing was the right resolver; the roster's cited literal numbers would have missed by 34 lines. The pre-commit ephemeral-identifier gate produced two matches ("Step 1.5" inside the new recital prose); both resolve to self-contained intra-document section references inside the same SKILL.md, matching the precedent Step 4 set with its seven similar matches. The matches pass through as allowed in-document section labels rather than forbidden workflow-internal "Track N / Step N" leaks.

**What changed from the plan:** none

**Key files:**
- `.claude/skills/create-plan/SKILL.md` (modified)

**Critical context:** The three-site forward-reference chain that Steps 3 and 4 introduced now aligns on disk. `workflow-drift-check.md` § Defer describes the recital, `create-plan/SKILL.md` Step 1.5's Defer resolution points at it, and Step 5 sub-step 3 implements it. The Surprises note about `workflow-drift-check.md` § Defer's final residue-contract paragraph (post-Step-3 lines 442-447) is deliberately untouched per the orchestrator's scope note; that paragraph lives in `workflow-drift-check.md`, not `create-plan/SKILL.md`, and Phase C will adjudicate the extend-or-defer call.

## Validation and Acceptance

After Track 6 lands:

- `/create-plan` SKILL contains a Step 1.5 between the `<dir-name>` resolver block and Step 1a that invokes the drift gate defined in `workflow-drift-check.md`.
- Step 1.5 runs after the `<dir-name>` resolver (so `$PLAN_DIR` is defined) and before Step 1b's `mkdir`, so the gate's Skip-#1 check (`[ -d "$PLAN_DIR/_workflow" ]`) sees the pre-creation state on fresh invocations.
- On a branch with `_workflow/**` artifacts whose stamps lie behind workflow commits reachable from HEAD (rebase or merge advanced HEAD since artifacts were last migrated), invoking `/create-plan` triggers the three-resolution prompt with no default before any research begins.
- On a fresh `/create-plan` invocation where the resolved `docs/adr/<dir-name>/_workflow/` doesn't exist, the gate skips silently and proceeds to Step 1a.
- `workflow-drift-check.md`'s intro paragraph names both `/execute-tracks` (turn 1) and `/create-plan` (between Step 1 and Step 1a) as callers.
- Every "step 4" / "Startup Protocol step 3" reference in `workflow-drift-check.md` reads correctly from either caller — generalized ("the calling session's next startup step") or qualified by caller name where the anchor genuinely differs.
- Every `/execute-tracks`-explicit mention in `workflow-drift-check.md` is either generalized ("the calling session") or kept caller-specific where it is truly `/execute-tracks`-only (with `/create-plan` symmetry handled in adjacent prose).
- Migrate-now resolution wording in both `/create-plan` SKILL Step 1.5 and `workflow-drift-check.md` points at in-branch `/migrate-workflow` re-invocation; neither mentions `develop` worktree or `cd ../develop`.
- Migrate-now's session-end framing in `workflow-drift-check.md` reads correctly for both callers — `/execute-tracks` exits before `workflow.md § What to do before ending a session`, `/create-plan` exits before Step 5's commit/push.
- Defer-from-`/create-plan` produces an in-session recital before Step 5 opens the draft PR; the recital reads the same `Deferred workflow drift: <count> commits since <short-stamp-base-SHA>` TaskCreate todo `workflow.md § What to do before ending a session` reads from `/execute-tracks`.
- § After the choice in `workflow-drift-check.md` reads correctly from either caller.
- On a `/create-plan` resume where `handoff-*.md` exists in `$PLAN_DIR/_workflow/`, Step 1.5 fires before Step 1a; Migrate-now leaves the handoff file on disk for the next invocation, Defer/Suppress proceed to Step 1a's handoff resume in the same session.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1** — `git revert <SHA>` restores `.claude/workflow/workflow-drift-check.md`'s intro paragraph (and lines 4-5's caller-specific anchors) to its Track-3-completion state. The intro generalization is a self-contained prose edit; reverting it leaves the body's caller-specific references untouched (those land in Steps 2 / 3). The drift gate's behavior is unchanged by Step 1 alone (callers are documentation surface).
- **Step 2** — `git revert <SHA>` restores the body of `.claude/workflow/workflow-drift-check.md` to its Step-1-completion state. The 10+-site sweep lands in one commit, so the revert drops every caller-agnostic rewrite as a unit; no partial-revert mode is supported (and none needed — the sweep is one consistency boundary). After revert, the body still names `/execute-tracks`-specific anchors throughout; the intro generalization from Step 1 survives (intro and body are separate edit boundaries).
- **Step 3** — `git revert <SHA>` restores Migrate-now's session-end framing and Defer's state-shape to their Step-2-completion state. The revert drops the per-caller conditional prose in both sections; the body's other caller-agnostic rewrites from Step 2 survive (those touch different lines).
- **Step 4** — `git revert <SHA>` restores `.claude/skills/create-plan/SKILL.md` to its pre-Step-4 state. Step 1.5 disappears; the SKILL's Step 1 → Step 1a → Step 1b flow returns to pre-Track-6 behavior (no drift gate on `/create-plan` startup). Existing `/create-plan` sessions continue working; the only regression after revert is the absence of the drift gate the track was wiring in.
- **Step 5** — `git revert <SHA>` restores `.claude/skills/create-plan/SKILL.md` Step 5 to its Step-4-completion state. The session-end recital between `git push` and the PR-prefix prompt disappears; Defer-from-`/create-plan` (added in Step 4) reverts to the no-host shape DL1 documented as the alternative resolution. The two changes (Step 1.5 from Step 4 and the recital from Step 5) are separately revertable because they land in two commits at different SKILL line ranges.

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/create-plan/SKILL.md` (new Step 1.5 between the `<dir-name>` resolver block and Step 1a; session-end recital added to Step 5; Migrate-now wording mirroring Track 3)
- `.claude/workflow/workflow-drift-check.md` (intro paragraph generalization to name both callers; sweep of 11 caller-specific surfaces in Detection / Resolutions / Defer / After-the-choice prose; Migrate-now and Defer session-end-anchor adjustments — Track 3 owns the underlying bash and decision-logic structure)

**Out-of-scope files:**
- `.claude/skills/edit-design/SKILL.md` — `/edit-design` is reached only through `/create-plan` and `/execute-tracks`; transitive coverage holds.
- `.claude/skills/migrate-workflow/SKILL.md` (Tracks 4a and 4b)
- `.claude/workflow/conventions.md` (Track 1)

**Inter-track dependencies:**
- **Depends on:** Track 3. Track 6 wires `/create-plan` to invoke the gate; the Detection logic and in-branch Migrate-now wording come from Track 3. Without Track 3, the gate's Migrate-now resolution still mentions `develop` worktree, inconsistent with the in-branch theme.
- Transitive dependencies on Tracks 1 (stamp format) and 2 (stamp writers) flow through Track 3.

**External interfaces:**
- The Step 1.5 invocation runs the same bash detection block as the `/execute-tracks` turn-1 gate. No new external commands.

## Base commit

555efea03aab1df18d7bb0c154cf626250839249
