<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: T1, sev: suggestion, loc: .claude/workflow/workflow.md:695, anchor: "### T1 ", cert: P7, basis: "Two benign non-contradiction git rm mentions (workflow.md:695,:757) unlisted; a Phase-C git-rm grep will hit them"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 12}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: PARTIAL, anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: E1, verdict: CONFIRMED, anchor: "#### E1 "}
  - {id: I1, verdict: MATCHES, anchor: "#### I1 "}
  - {id: I2, verdict: MATCHES, anchor: "#### I2 "}
  - {id: D1, verdict: CONFIRMED, anchor: "#### D1 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [suggestion]
**Certificate**: Premise P7 (enumeration completeness — verdict PARTIAL)
**Location**: Track `## Context and Orientation` "Descriptive sites" list and `## Interfaces and Dependencies` in-scope list; source `.claude/workflow/workflow.md:695` and `:757`.
**Issue**: The track enumerates six descriptive `_workflow/`-cleanup mentions (create-final-design.md:617, workflow.md:769, commit-conventions.md:153, conventions-execution.md:372 and :747, mid-phase-handoff.md:493) and asserts these plus the two operative sites are the complete set. A full `grep -rn "git rm"` over `.claude/workflow/` also returns two more `git rm` mentions inside `workflow.md` § Final Artifacts itself — `:695` ("before the cleanup `git rm` runs, Phase 4 folds the log") and `:757` ("the `git rm` below deletes the log, so the verdict trail is captured...") — plus `conventions-execution.md:383` ("blanket recursive `rm` above"). None of the three reproduces the bare-`-r` command shape or asserts the false "sweeps untracked files" behavior; they are sequencing/forward-pointer prose, so leaving them unedited introduces no contradiction with the fix (the enumeration of *contradiction-bearing* descriptive sites is complete, and the enumeration of *operative* sites is complete — no cleanup command is missed, so the bug is fully closed). The gap is only that these mentions are unlisted, and a Phase-C `review-workflow-consistency` reviewer that greps `git rm` will surface them and may query why they were untouched.
**Proposed fix**: Add a one-line note to the track's `## Context and Orientation` verified-negative/benign block naming `workflow.md:695`, `:757`, and `conventions-execution.md:383` as intentionally-unedited benign `git rm` mentions (they describe sequencing, not the command's flags or untracked-reach), so a later consistency reviewer does not re-flag them. No behavior change; documentation-completeness only.

## Evidence base

#### P1 Operative site — create-final-design.md § Step 6 command
- **Track claim**: `.claude/workflow/prompts/create-final-design.md` § Step 6, ~line 609, carries the bare `git rm -r docs/adr/<dir-name>/_workflow/` command the Phase-4 orchestrator runs.
- **Search performed**: `grep -rn "git rm -r docs/adr" .claude/workflow/`; Read of create-final-design.md lines 585-639.
- **Code location**: `.claude/workflow/prompts/create-final-design.md:609` (inside the `**Step 6 — Cleanup commit...**` bash fence at 608-612).
- **Actual behavior**: Line 609 is `git rm -r docs/adr/<dir-name>/_workflow/`, immediately followed by `git commit -m "Remove workflow scaffolding"` (610) and `git push` (611). It is a live executable command block, not prose. Anchor "~line 609" is exact.
- **Verdict**: CONFIRMED
- **Detail**: n/a — exact match, bare `-r` with no `-f`, confirming the diagnosed bug is live at this operative site.

#### P2 Operative site — workflow.md § Final Artifacts mirror command
- **Track claim**: `.claude/workflow/workflow.md` § Final Artifacts, ~line 764, carries the mirror command; the two operative sites are documented as mirrors that must stay in step.
- **Search performed**: `grep -rn "git rm -r docs/adr" .claude/workflow/`; Read of workflow.md lines 685-779.
- **Code location**: `.claude/workflow/workflow.md:764` (item 3, `**Cleanup commit.** Run \`git rm -r docs/adr/<dir-name>/_workflow/\``).
- **Actual behavior**: Line 764 states the same bare `git rm -r docs/adr/<dir-name>/_workflow/`. commit-conventions.md:153 explicitly points to `workflow.md § Final Artifacts` as the definition site, and create-final-design.md § Step 6 restates it — the mirror relationship is real and load-bearing. Anchor "~line 764" is exact.
- **Verdict**: CONFIRMED
- **Detail**: The two operative sites are genuine mirrors; the track's requirement that both change in step is correct — editing one alone would reintroduce a doc contradiction.

#### P3 Descriptive site — "sweeps automatically" prose (both operative files)
- **Track claim**: `create-final-design.md:617` and `workflow.md:769` both assert the recursive `git rm` "sweeps the review-file directories automatically" — true only for tracked files.
- **Search performed**: Read of create-final-design.md:614-621 and workflow.md:764-772.
- **Code location**: `create-final-design.md:617` ("The recursive `git rm -r` sweeps the `reviews/` directories automatically — no `plan/*`-globbing removal is needed...") and `workflow.md:769` ("The blanket recursive `git rm` sweeps the review-file directories automatically; no `plan/*`-globbing removal is needed...").
- **Actual behavior**: Both read exactly as the track claims. The "sweeps automatically" claim is scoped to committed/tracked review files; it says nothing about untracked cold-read output/params/`.pyc` remnants, which is precisely the false-implication the fix must reconcile. Wording differs slightly between the two (`reviews/` vs `review-file directories`) but the claim is equivalent.
- **Verdict**: CONFIRMED
- **Detail**: Both descriptive sites are as described; the reconciliation D2 calls for is warranted.

#### P4 Descriptive site — commit-conventions.md Phase-4 cleanup table row
- **Track claim**: `commit-conventions.md:153` is the Phase-4-cleanup row of the commit-type table naming `git rm -r docs/adr/<dir>/_workflow/`.
- **Search performed**: Read of commit-conventions.md:146-153.
- **Code location**: `commit-conventions.md:153` — table row `| **Phase 4 cleanup** | \`Remove workflow scaffolding\` — single commit that runs \`git rm -r docs/adr/<dir>/_workflow/\` after the final-artifacts commit | (see \`workflow.md\` § Final Artifacts) |`.
- **Actual behavior**: A table row, descriptive, naming the bare `-r` command and cross-pointing to the operative site. Matches the track's characterization exactly.
- **Verdict**: CONFIRMED
- **Detail**: n/a.

#### P5 Descriptive sites — conventions-execution.md:372 and :747
- **Track claim**: `conventions-execution.md:372` and `:747` are two blanket-`git rm -r _workflow/` prose mentions.
- **Search performed**: Read of conventions-execution.md:365-383 and 740-755.
- **Code location**: `:372` ("The Phase 4 cleanup is a blanket recursive `git rm -r _workflow/` (in `workflow.md` § Final Artifacts and `create-final-design.md`), which removes `plan/track-N/reviews/`...automatically...") and `:747` ("...the Phase 4 cleanup's blanket recursive `git rm -r _workflow/` sweeps `_workflow/reviews/` along with the rest...").
- **Actual behavior**: Both are prose mentions naming the bare `-r` shape, as claimed. Both attribute the automatic sweep to the recursive `git rm` — the same tracked-only-scope implication as P3.
- **Verdict**: CONFIRMED
- **Detail**: n/a.

#### P6 Descriptive site — mid-phase-handoff.md:493
- **Track claim**: `mid-phase-handoff.md:493` is the descriptive "`git rm -r`s `_workflow/`" mention in the Phase-4 handoff-cleanup exception.
- **Search performed**: Read of mid-phase-handoff.md:485-502.
- **Code location**: `mid-phase-handoff.md:493` — "The Phase 4 cleanup commit (Step 6 of `create-final-design.md`) `git rm -r`s `_workflow/` and removes the handoff file, PAUSED marker host (the plan file), and `MEMORY.md` entry in the same commit."
- **Actual behavior**: Descriptive prose, bare `-r`, inside the "Phase 4 cleanup exception" bullet, as claimed. Cross-reference "Step 6 of `create-final-design.md`" resolves — the operative command is at create-final-design.md § Step 6 (P1). This site also asserts the single-commit contract ("in the same commit"), which the fix must preserve.
- **Verdict**: CONFIRMED
- **Detail**: n/a.

#### P7 Enumeration completeness — no operative site missed; three benign mentions unlisted
- **Track claim**: The two operative + six descriptive sites are the ONLY operative/contradiction-bearing `git rm -r` `_workflow/` sites, with one out-of-scope fixture.
- **Search performed**: `grep -rn "git rm" .claude/`; `grep -rn "git rm\|rm -rf\|recursive.*git\|blanket recursive" .claude/workflow/`.
- **Code location**: All operative + descriptive sites confirmed (P1-P6). Additional matches: `workflow.md:695` ("before the cleanup `git rm` runs, Phase 4 folds the log"), `workflow.md:757` ("the `git rm` below deletes the log"), `conventions-execution.md:383` ("the blanket recursive `rm` above").
- **Actual behavior**: The two operative sites (P1, P2) are the complete set of live cleanup commands — no cleanup command is missed, so the bug is fully closed by fixing them. The six named descriptive sites are the complete set of *contradiction-bearing* mentions. The three additional mentions (:695, :757, :383) are sequencing/forward-pointer prose: none reproduces the bare-`-r` command form and none asserts the false untracked-sweep behavior, so leaving them unedited creates no contradiction. They are simply unlisted, which a Phase-C `git rm` grep will surface.
- **Verdict**: PARTIAL
- **Detail**: Produces finding T1 (suggestion). The completeness claim holds for what matters (no missed operative site → no live-bug residue; no missed contradiction). The gap is documentation-only: three benign mentions are not called out as intentionally-untouched.

#### P8 Verified-negative fixture is out-of-scope illustrative text
- **Track claim**: The sixth `git rm -r` grep match at `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33` is illustrative fixture body text, not a live instruction; do not touch it.
- **Search performed**: `grep -rn "git rm" .claude/`; Read of review-file-valid-strategic.md:25-39.
- **Code location**: `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33` — "The blanket recursive `git rm -r _workflow/` already sweeps review files." — inside a `### T2 [should-fix] Phase 4 cleanup sweep needs confirmation` finding body under `## Findings` in a review-file schema fixture.
- **Actual behavior**: The line is the descriptive body of a fictional finding `T2` inside a test fixture that exercises the count-validation regex (`## Findings` / `### T<N>` anchors / `## Evidence base`). It is not a live cleanup instruction and not a real claim about the production command. Editing it would corrupt an unrelated regex-fixture and is correctly out of scope.
- **Verdict**: CONFIRMED
- **Detail**: The out-of-scope classification is correct; D2's Risks note that this file "must not be touched" is sound.

#### E1 Edge case — git rm -rf force-discards local modifications on a to-be-deleted file
- **Trigger**: `_workflow/` holds a tracked-but-locally-modified file (`design-mutations.md`) plus untracked siblings when the cleanup command runs.
- **Code path trace**:
  1. `git rm -r <dir>` @ create-final-design.md:609 / workflow.md:764 — with a modified tracked file present, exits 1 ("the following file has local modifications") and stages nothing. (Independently reproduced this session: exit=1.)
  2. Proposed `git rm -rf <dir>` (D1) — force flag overrides the local-modification guard; stages deletions of all tracked files. Exit 0. But untracked siblings remain on disk. (Reproduced: `untracked.md` survived.)
  3. Proposed follow-up `rm -rf <dir>` (D1) — deletes the remaining untracked siblings. Directory fully gone. (Reproduced: dir fully removed.)
  4. `git commit` — `git status` shows only staged deletions (`D`), so the single commit captures everything; no second commit needed. (Reproduced: `git status --porcelain` = `D sub/tracked.md` only.)
- **Outcome**: Correct handling. `-f` discarding the uncommitted edit is safe because the file is being deleted — nothing downstream reads it after cleanup. The `rm -rf` runs before the single `git commit`, so the single-cleanup-commit contract holds.
- **Track coverage**: Yes — D1 Risks explicitly reason that force-discarding modifications to an about-to-be-removed file loses nothing, and that the `rm -rf` adds no commit. The independent repro confirms the diagnosis (research log's repro) exactly.

#### I1 Integration — §1.7 staging mode selection (staged vs opt-out)
- **Plan claim**: D3 selects §1.7 staged mode over the §1.7(k) prose-rule opt-out, because both operative sites are Phase-4 execution procedure, not judgment-layer prose.
- **Actual entry point**: `conventions.md` §1.7(k) opt-out criteria at lines 1380-1390; criterion 2 (line 1382-1386): "Every edited file's in-branch consumer is judgment-layer... Files a running phase reads as executable procedure... stay staged even on an otherwise-qualifying plan." Ledger `s17=workflow-modifying` at `_workflow/phase-ledger.md:1`.
- **Caller analysis**: create-final-design.md § Step 6 (P1) and workflow.md § Final Artifacts (P2) are executable command blocks a running Phase 4 orchestrator runs — the exact "executable procedure" class criterion 2 keeps staged. §1.7(k) criterion 2 therefore fails, so the opt-out does not qualify; staged mode is mandatory. The phase ledger records `s17=workflow-modifying` (the staging token), consistent with D3.
- **Breaking change risk**: None. D3's classification matches §1.7(k) as written; the staged routing to `_workflow/staged-workflow/.claude/workflow/...` and Phase-4 promotion is the correct path.
- **Verdict**: MATCHES

#### I2 Integration — staged-mode self-application gap does not affect this branch
- **Plan claim**: D3 Risks/Caveats — staging means the fix goes live only at Phase-4 promotion, so this branch's own Phase 4 runs the old (buggy) command; but the gap is absent for this branch because `design_gate=no` produces no `design-mutations.md`, no `edit-design phase4-creation` loop, and (as a minimal single-track change) no plan-marker flip — so nothing dirty exists under `_workflow/` for the old command to choke on.
- **Actual entry point**: Ledger `_workflow/phase-ledger.md:1` — `design_gate=no tracks=1`. edit-design phase4-creation loop (the `design-mutations.md` writer) runs only under `design_gate=yes` per create-final-design.md § Step 6 prose (line 616 references "the design-mutations log"). The final-artifacts commit stages only top-level artifacts (create-final-design.md:588-593) and never `_workflow/`.
- **Caller analysis**: With `design_gate=no` and a single minimal track, this branch's Phase 4 authors no `design-mutations.md` and flips no plan marker (no plan file exists). The only `_workflow/` files at this branch's cleanup are already-committed tracked artifacts (research-log.md, plan/track-1.md, review files) with no local modifications — the bare `git rm -r` handles those. So this branch's own cleanup does not hit the bug even pre-promotion.
- **Breaking change risk**: None — the self-application gap is a pre-existing property of `design_gate=yes` staged branches, not a regression this fix introduces, and it does not block this branch's own Phase 4.
- **Verdict**: MATCHES

#### D1 Decision coherence — fix resolves the diagnosed failure and preserves both contracts
- **Track claim**: D1 (`git rm -rf` + `rm -rf`) resolves the tracked-modified + untracked-remnant failure; it preserves the single-cleanup-commit contract and the existing `plan/*`-globbing warning.
- **Search performed**: Independent empirical git repro (temp repo, modified tracked file + untracked sibling); Read of the `plan/*`-glob warning at create-final-design.md:617-619 and workflow.md:769-771.
- **Code location**: Repro confirmed the three-part diagnosis (see E1). The `plan/*`-glob caution is present verbatim at both operative sites ("no `plan/*`-globbing removal is needed (and would risk catching the `plan/track-N.md` files)") and also at conventions-execution.md:377-383.
- **Actual behavior**: The proposed fix resolves the failure (E1 trace, all steps reproduced). The `rm -rf` operates on untracked/already-staged-for-deletion files and runs before the single `git commit`, so no second commit is created — single-cleanup-commit contract holds. The Plan of Work explicitly states the `plan/*`-glob warning "reasoning stays unchanged," and word-level prose edits to add `-f` and the follow-up `rm -rf` do not disturb that caution.
- **Verdict**: CONFIRMED
- **Detail**: D1 is feasible and contract-preserving. D2 (reconcile every contradiction-bearing descriptive site) and D3 (staged mode) are likewise coherent (P3-P6, I1). No blocker or should-fix.
