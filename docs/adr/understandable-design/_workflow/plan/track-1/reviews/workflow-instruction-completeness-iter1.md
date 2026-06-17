<!--
MANIFEST
producer: review-workflow-instruction-completeness
target_range: 4d3962c97441218d8a78272e92f18b83955bef37..HEAD
scope: Track 1 — the two-role authoring loop, wired into design creation
verdict: changes-requested
counts: { blocker: 0, should-fix: 2, suggestion: 3, total: 5 }
index:
  - id: WI1
    sev: should-fix
    anchor: "#wi1-author-params-research-log-output-path-not-enumerated"
    loc: ".claude/skills/edit-design/SKILL.md:497-507"
    cert: C1
    basis: judgment
  - id: WI2
    sev: should-fix
    anchor: "#wi2-mechanical-first-author-respawn-has-no-budget-decrement"
    loc: ".claude/skills/edit-design/SKILL.md:720-737"
    cert: C2
    basis: judgment
  - id: WI3
    sev: suggestion
    anchor: "#wi3-author-spawn-total-failure-has-no-recovery-path"
    loc: ".claude/skills/edit-design/SKILL.md:958-979"
    cert: C3
    basis: judgment
  - id: WI4
    sev: suggestion
    anchor: "#wi4-dual-clean-predicate-severity-mismatch-between-the-two-checks"
    loc: ".claude/skills/edit-design/SKILL.md:730-734"
    cert: C4
    basis: judgment
  - id: WI5
    sev: suggestion
    anchor: "#wi5-iteration-budget-resume-after-context-clear-is-undefined"
    loc: ".claude/skills/edit-design/SKILL.md:686-737"
    cert: C5
    basis: judgment
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3, C4, C5]
flags: []
-->

## Findings

### WI1 [should-fix] Author params file: `research_log_path` and `output_path` not enumerated in the shared spawn contract

- **File:** `.claude/skills/edit-design/SKILL.md` (lines 497-507)
- **Axis:** sub-agent handshake
- **Cost:** the author's params file is under-specified against what the `design-author` agent definition requires to read; the orchestrator improvises which keys to write, and a forgotten `research_log_path` strands the author with no log to ground from on round 1.
- **Issue:** the "Per-agent parameters go in a params file" paragraph enumerates the per-role keys as: *"the auditor's slice `range`, each role's `target` / `target_path` / `output_path`, the author's `round` and `flagged_passages`, the absorption check's `research_log_path` and `draft_path`."* This assigns `research_log_path` to the absorption check **only**. But `design-author.md § Inputs` lists `research_log_path` ("the research log to ground from") and `output_path` ("where to write") as the author's own params, and Step 6 round 1 says the author "reads the research log / seed and the code, then writes every section to `output_path`." The author cannot ground on round 1 without `research_log_path` in its params file, yet the SKILL's only enumeration of author params names just `round` and `flagged_passages`. `output_path` is arguably covered by the "each role's ... `output_path`" clause, but `research_log_path` is assigned exclusively to a different role. The producer→consumer contract (Step 4 writes the params file; the author reads it) is the spot where a missing key silently strands the consuming agent.
- **Suggestion:** extend the author's slot in the enumeration to read "the author's `target`, `research_log_path`, `output_path`, `design_path` (track/full path), `round`, and `flagged_passages`," matching `design-author.md § Inputs` key-for-key. Alternatively, add a one-line cross-reference: "the author's full param set is `design-author.md § Inputs`." Either makes the handshake complete instead of leaving the orchestrator to infer the author needs the log path.

### WI2 [should-fix] The mechanical-first author re-spawn has no budget decrement and no inner cap

- **File:** `.claude/skills/edit-design/SKILL.md` (lines 720-737)
- **Axis:** loop termination
- **Cost:** a draft that keeps tripping a mechanical `blocker` re-spawns the author indefinitely; the per-round budget decrement (step 5) never fires because step 4's dual-clean evaluation is never reached, so the documented `iteration_budget` backstop does not bound this sub-loop.
- **Issue:** Step 6's dual-clean inner loop, step 2: *"Run mechanical checks (Step 3) on the just-written draft. If any `blocker`, iterate mechanical-first (re-spawn the author with the mechanical findings as flagged passages) before spending the round's auditor and second-check spawns."* The budget is decremented only at step 5 ("Decrement the iteration budget"), which runs after step 3 (spawn the pair) and step 4 (evaluate dual-clean). The mechanical-first re-spawn in step 2 loops back to author-write without reaching step 5, so an author that cannot clear a mechanical blocker (e.g. a stamp-position check it cannot satisfy, or a link-resolution failure that is not the author's to fix) re-spawns with no termination guarantee. The interactive single-agent loop avoids this because it re-runs mechanical inside one budgeted iteration; the creation-kind inner loop's step ordering puts the mechanical re-spawn outside the decrement.
- **Suggestion:** state that the mechanical-first re-spawn consumes a round of the `iteration_budget` (decrement before looping back to step 1), or add an explicit inner cap on mechanical-first re-spawns ("re-spawn at most once for a mechanical blocker; if it persists, escalate to the user as a non-self-correcting mechanical failure"). Either gives the mechanical-first sub-loop the termination guarantee the rest of Step 6 has.

### WI3 [suggestion] Author spawn total failure (crash / empty / no draft on disk) has no recovery path

- **File:** `.claude/skills/edit-design/SKILL.md` (lines 958-979)
- **Axis:** error and recovery path
- **Cost:** the author is the sole writer and the other three roles are read-only; if the author crashes or returns without writing `output_path` on round 1, there is no draft on disk for the auditor/absorption to read and no defined recovery, so the loop proceeds against a missing or empty file.
- **Issue:** the Failure modes section handles two author/review failure shapes: *"A review sub-agent times out or returns malformed output"* (re-run once, then `INCONCLUSIVE`) and *"The author spawn returns the draft inline instead of a thin summary"* (proceed with the on-disk draft, do not re-spawn). Neither covers the author spawn **failing to produce a draft at all** — a crash, a tool-permission error mid-task (D3 warns the allow-list could omit a needed tool, which "fails mid-task"), or a return with no write to `output_path`. The "returns the draft inline" recovery explicitly relies on "the draft is on disk at `output_path`"; the no-draft case has the opposite precondition and is unhandled. The auditor and absorption check would then read a missing or stale file with no guard.
- **Suggestion:** add a Failure-modes bullet: "The author spawn fails or writes no draft to `output_path`: confirm the file exists and is non-empty before spawning the per-round pair; if absent, re-spawn the author once (a transient tool error), and if it fails again, stop and surface to the user — the loop cannot proceed without a draft, since no read-only role can write one." This mirrors the D3 risk note that an author missing a needed tool fails mid-task.

### WI4 [suggestion] Dual-clean predicate uses a different severity bar for the two checks

- **File:** `.claude/skills/edit-design/SKILL.md` (lines 730-734)
- **Axis:** state marker transition
- **Cost:** a round's dual-clean transition is ambiguous when the second check returns only a `suggestion`: the auditor clause excludes `suggestion` from blocking, the second-check clause appears to count all findings, so whether the round advances is reader-dependent.
- **Issue:** Step 6 step 4: *"The round is dual-clean when the auditor returns no `blocker`/`should-fix` finding AND the second check returns none."* The auditor half names the severity bar (`blocker`/`should-fix`, so `suggestion` does not block), but the second-check half says "returns none," which reads as all severities including `suggestion`. The Outcomes block (step "All blockers and should-fix findings cleared ... PASS") and S5 both frame the exit on blocker+should-fix, so a `suggestion`-only absorption finding should not block dual-clean — but the literal step-4 wording could be read to keep the loop open on a lone `suggestion`, wasting a round.
- **Suggestion:** align the two halves: "...AND the second check returns no `blocker`/`should-fix` finding." This matches the auditor clause, the Outcomes block, and S5, and removes the read where a `suggestion` blocks the dual-clean transition.

### WI5 [suggestion] Inner-loop iteration-budget state is not file-backed for resume after a context-clear

- **File:** `.claude/skills/edit-design/SKILL.md` (lines 686-737)
- **Axis:** gate resume path
- **Cost:** if the session clears mid-loop (a multi-round author/auditor loop is long and context-heavy by the design's own cost analysis), the orchestrator cannot recover the remaining `iteration_budget` or the current round number from a file, so a resumed loop either restarts the budget or guesses.
- **Issue:** the S3 freeze-order gate is resumable because its state lives in the research log's `## Adversarial gate record` (Step 4 reads it from the file). The inner loop's own progress — current round, remaining `iteration_budget`, the standing `flagged_passages` — is held only in the orchestrator's working memory. The review log (Step 7) is appended once per mutation at the end, not per round, so a mid-loop clear leaves no on-disk marker of how many rounds were spent. The dual-clean draft itself is on disk, but the budget counter is not. This is the same class of gap the workflow flags elsewhere (gate state must be readable from a file, not memory).
- **Suggestion:** note that the per-round loop state (round number, remaining budget) should be recoverable — either by reconstructing the round count from the draft's revision history / review-log breadcrumbs, or by writing a one-line per-round marker to a scratch file under `_workflow/plan/`. At minimum, state the resume rule explicitly ("on resume mid-loop, re-derive the round from the latest params files written under `_workflow/plan/`; if indeterminate, restart the budget — the loop is idempotent because the author re-grounds"). Low severity because the loop is idempotent and budget-restart is safe, but the resume behavior is currently undefined.

## Evidence base

#### C1: Author params `research_log_path` / `output_path` enumeration gap — confirmed
Cross-checked the producer (SKILL Step 4 params enumeration, line ~500-504: keys assigned to author = `round`, `flagged_passages`; `research_log_path` assigned to absorption check only) against the consumer (`design-author.md § Inputs`, lines 60-67: author requires `research_log_path`, `output_path`, `target`, `design_path`, `round`, `flagged_passages`). Round-1 author duty (SKILL Step 6 step 1, line 705-708: "reads the research log / seed ... writes every section to `output_path`") confirms the author consumes `research_log_path` it is not enumerated to receive. Handshake incomplete on the `research_log_path` key.

#### C2: Mechanical-first re-spawn unbounded — confirmed
Step 6 inner-loop step ordering: step 2 (line 720-723) re-spawns the author on a mechanical blocker "before spending the round's auditor and second-check spawns"; the only budget decrement is step 5 (line 736-737), which runs after step 3/4. Traced that the mechanical-first re-spawn loops back to author-write without passing step 5, so the documented `iteration_budget` does not bound it. No inner cap stated. Contrast: interactive loop (line 774-780) re-runs mechanical inside one budgeted iteration, so it is bounded.

#### C3: Author total-failure recovery absent — confirmed
Read the Failure modes section (lines 960-979) in full. Two author/review failure shapes handled: review sub-agent timeout/malformed (line 964-970) and author returns-draft-inline (line 971-974). The inline-return recovery's stated precondition is "the draft is on disk at `output_path`." No bullet covers the author producing no draft (crash, tool-permission failure mid-task per D3 line 61, empty return). Read-only roles (auditor/absorption/comprehension) cannot write a substitute, so the no-draft state is unrecoverable as specified.

#### C4: Dual-clean predicate severity mismatch — confirmed
Step 6 step 4 (line 730-731): auditor clause = "no `blocker`/`should-fix` finding"; second-check clause = "returns none." Compared against the Outcomes block (line 784, exit on "All blockers and should-fix findings cleared") and S5 (track-1.md line 482, exit on both checks clean). The Outcomes/S5 framing uses blocker+should-fix; the literal step-4 second-check clause says "none," producing the ambiguity on a `suggestion`-only second-check result.

#### C5: Inner-loop budget not file-backed for resume — confirmed
Compared the S3 gate (file-backed, resumable: Step 4 line 535-538 reads the gate verdict from the research log's `## Adversarial gate record`) against the inner-loop budget/round state (Step 6, lines 686-737: held in working memory; no per-round file write). Step 7 (line 796+) appends the review log once per mutation, not per round, so there is no mid-loop on-disk marker. Loop is idempotent (author re-grounds), so severity is suggestion, but resume behavior is undefined.
