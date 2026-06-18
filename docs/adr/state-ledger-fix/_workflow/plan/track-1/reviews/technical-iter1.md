<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "track-1.md §Decision Log D2 / §Plan of Work step 3 / test_workflow_startup_precheck.py:52,80,3005", anchor: "### T1 ", cert: P9, basis: "guard must read the staged track-review.md; the only workflow-md read precedent (LIVE_REPO_ROOT) reads the live unfixed file -> guard false-fails during this branch's own staged Phase B run"}
  - {id: T2, sev: suggestion, loc: "track-1.md §Context and Orientation / §Purpose", anchor: "### T2 ", cert: P5, basis: "a completed Phase A track file resolves to substate=steps-partial (Phase B), not decomposition-pending; the C&O paragraph names only the decomposition-pending branch, mild reader friction"}
  - {id: T3, sev: suggestion, loc: "track-1.md §Decision Log D1 / §Context and Orientation", anchor: "### T3 ", cert: P3, basis: "step 5 fallback is 'unknown' not 'safe'; an unknown ctx is a valid bare token so no breakage, but D1's prose should note the reused value may be unknown, not always a real level"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 9}
cert_index:
  - {id: P3, verdict: PARTIAL, anchor: "#### P3 "}
  - {id: P5, verdict: PARTIAL, anchor: "#### P5 "}
  - {id: P9, verdict: PARTIAL, anchor: "#### P9 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise P9 (test-module repo-root anchor) + Premise P10 (staging read precedence)
**Location**: `track-1.md` §Decision Log D2 ("reads `track-review.md` directly"), §Plan of Work step 3, §Interfaces in-scope file note; against `.claude/scripts/tests/test_workflow_startup_precheck.py:52` (`REPO_ROOT = parents[3]`), `:80` (`LIVE_REPO_ROOT = _resolve_live_repo_root()`), `:3005` (`CONVENTIONS_PATH = LIVE_REPO_ROOT / ".claude" / "workflow" / "conventions.md"`).
**Issue**: D2 and Plan-of-Work step 3 say the guard "reads `track-review.md` directly" because the test module "already resolves `.claude/workflow/` paths from the repo root." But the module carries **two** distinct repo-root anchors, and during this branch's §1.7(b)-staged lifetime they point at **different** `track-review.md` files:
  - `REPO_ROOT` (`parents[3]`) — when the test is run from the staged mirror (`_workflow/staged-workflow/.claude/scripts/tests/`), this is the **staged-workflow root**, whose `.claude/workflow/track-review.md` is the **staged, fixed** copy carrying the new append.
  - `LIVE_REPO_ROOT` (`_resolve_live_repo_root()`, the walk-up to the ancestor holding `.claude/workflow/conventions.md`) — resolves to the **live** repo root, whose `track-review.md` is the **develop-state, unfixed** copy (no append until the Phase 4 promotion).

  The only existing precedent for a test that reads a workflow-`.md` file (`CONVENTIONS_PATH` at `:3005`) anchors at **`LIVE_REPO_ROOT`** — exactly the anchor that, for this branch, reads the *unfixed* live `track-review.md`. If the new guard copies that precedent, then during Phase B the staged test will read the live file that still lacks the append (precisely D3's accepted self-application wrinkle: the fix self-applies only after Phase 4), the three-flag assertion will not match, and the guard will **false-fail on this very branch** — the implementer would have to special-case or skip it.

  The track never pins which of the two anchors the guard uses. This is the one instruction-completeness gap that can surface as a concrete Phase-B test failure rather than just a prose ambiguity. (Note: this is the inverse of `conventions.md §1.7(d)` reads-precedence, which says "staged copy authoritative when present" — that rule wants the *staged* file read, i.e. `REPO_ROOT`, not the `LIVE_REPO_ROOT` the `conventions.md` conformance test uses for its own different reason.)
**Proposed fix**: Pin the anchor in D2 / Plan-of-Work step 3: the doc-presence guard MUST resolve `track-review.md` relative to **`REPO_ROOT`** (the co-located `parents[3]` root, which under staging is the staged-workflow root holding the fixed copy), explicitly **not** `LIVE_REPO_ROOT`. State the rationale inline (the staged fix is what the guard must assert, mirroring `§1.7(d)` "staged copy authoritative when present"), so the implementer does not pattern-copy the `CONVENTIONS_PATH`/`LIVE_REPO_ROOT` precedent at `:3005` and read the unfixed live file. This keeps the guard green during the branch's own Phase B and after promotion (post-promotion both anchors coincide on the fixed file).

### T2 [suggestion]
**Certificate**: Premise P5 (`determine_c_substate` resolution of a completed Phase A track file)
**Location**: `track-1.md` §Context and Orientation (the `phase == "C"` router paragraph, "A `decomposition-pending` substate enters Phase A Resume … any other substate enters Phase B/C") and §Purpose ("resumes into Phase B/C").
**Issue**: The C&O paragraph describes the C-case substates as if `decomposition-pending` were the operative one for a *completed* Phase A. It is not. After Phase A completes, the track file has `## Progress` "Review + decomposition" `[x]` (done) and a populated `## Concrete Steps` roster of `[ ]` steps. `determine_c_substate` (`workflow-startup-precheck.sh:1690`) short-circuits `decomposition-pending` only when the "Review + decomposition" token is **not** done (`:1693-1697`); with it done and `[ ]` steps remaining it falls through to **`steps-partial`** (`:1722-1725`), which the router (`workflow.md:347`) maps to "resume from the next `[ ]` step" — i.e. Phase B. So the realized post-completion path is `phase=C` → `steps-partial` → Phase B, not `decomposition-pending` → Phase A Resume. The track's Purpose claim ("resumes into Phase B/C") is therefore **correct**, but the C&O paragraph's emphasis on the `decomposition-pending` branch is the half-finished-Phase-A case, not the completed one this fix targets, and a reader could conflate the two. Not a blocker — the fix's behavior is right; this is reader-orientation friction in the explanatory prose.
**Proposed fix**: In §Context and Orientation, add one clause noting that a *completed* Phase A (decomposition written, steps `[ ]`) resolves the C-case to `substate=steps-partial` → Phase B, while `decomposition-pending` (→ Phase A Resume, a near no-op) is the *interrupted-before-decomposition* case. This makes the trace at the end of the section ("the router re-runs Phase A" → after the fix, "the router routes to Phase B") land cleanly.

### T3 [suggestion]
**Certificate**: Premise P3 (`--ctx` default) + Premise P11 (step-5 ctx read fallback)
**Location**: `track-1.md` §Decision Log D1 ("Reuse the `<level>` value step 5 already read … to feed `--ctx`") and §Context and Orientation (step-5 description).
**Issue**: D1 reuses the `<level>` step 5 computed. Per live `track-review.md` sub-step 5 (`:563-571`), that read **falls back to `unknown`** when `/tmp/claude-code-context-usage-$PPID.txt` is missing or unparseable — not to `safe`. So the reused value fed to `--append-ledger --ctx <level>` may be the literal `unknown`. This is **not** a breakage: `unknown` is a valid bare token, the script's `reject_bad_ledger_value "ctx" … bare` (`:1577`) accepts it, and `--ctx`'s own default (`safe`, `:1576`) only applies when the flag is omitted entirely — which the fix does not do. The track's own §Context notes the `unknown` fallback for step 5 but D1's reuse sentence reads as though `<level>` is always a real level (`safe`/`info`/...). Minor prose precision only.
**Proposed fix**: In D1, append "(`unknown` when step 5's statusline read missed — a valid bare `--ctx` token, distinct from the script's `safe` default for an omitted flag)" so the reused-value contract is unambiguous and a future reader does not "fix" a perceived `unknown` leak.

## Evidence base

#### P1: track-review.md §What You Do step 6 is the Phase A commit-and-push point; step 5 performs the statusline ctx read
- **Track claim**: D1 / Context — "step 6 ... stages `track-<N>.md`, commits 'Phase A review and decomposition for <track>', and pushes"; step 5 "already performs a statusline context-level read ... writes a `## Progress` entry tagged `[ctx=<level>]`."
- **Search performed**: Read of live `.claude/workflow/track-review.md` §What You Do, sub-steps 5 and 6.
- **Code location**: `.claude/workflow/track-review.md:560-579` (sub-step 5 ctx read + Progress write), `:581-598` (sub-step 6 commit-and-push).
- **Actual behavior**: Sub-step 5 reads `/tmp/claude-code-context-usage-$PPID.txt`, parses `level=`, appends `- [x] <ISO> [ctx=<level>] Review + decomposition complete` to `## Progress`. Sub-step 6 runs `git add docs/adr/<dir-name>/_workflow/plan/track-<N>.md`, `git commit -m "Phase A review and decomposition for <track>"`, `git push`. Stages explicit paths only ("never `git add -A`").
- **Verdict**: CONFIRMED
- **Detail**: Matches the track exactly. The `<level>` from step 5 is in conversation context for step 6 to reuse with no second read, as D1 claims. Adding `phase-ledger.md` to step 6's `git add` (D1) is a one-line extension of an explicit-path list that already exists.

#### P2: track-review.md §Phase A Completion step 2 runs the porcelain + log checks with a missing-commit recovery branch
- **Track claim**: D1 / Context — step 2 "runs `git status --porcelain` to confirm a clean tree and `git log -1 --oneline` to confirm the tip is the Phase A commit, with a recovery branch that re-runs step 6 if the commit is missing."
- **Search performed**: Read of live `track-review.md` §Phase A Completion.
- **Code location**: `.claude/workflow/track-review.md:1012-1018`.
- **Actual behavior**: "Verify the Phase A commit landed. Run `git status --porcelain`; the working tree must be clean. Run `git log -1 --oneline` and confirm the tip is `Phase A review and decomposition for <track>`. If the commit is missing (e.g., the session was interrupted between step 5 and step 6 of §What You Do), run step 6 now."
- **Verdict**: CONFIRMED
- **Detail**: The site and its existing missing-commit recovery are exactly as the track describes. D1's new ledger-tail check + commit-present-but-tail-wrong recovery branch slots in as a sibling of the existing recovery, mirroring its "run step 6 now" shape. Note one consistency point the fix must respect: after the bundled append lands in step 6, the tree is still clean (the append is committed in the same commit), so step 2's existing `git status --porcelain` empty assertion still holds — the track's "append stays inside the single Phase A commit" invariant is what keeps step 2's clean-tree check valid, and D1 is internally consistent on this.

#### P3: `--ctx` defaults to `safe` when omitted
- **Track claim**: D2 — "the canonical argument order (script Usage line 120) puts `--ctx` before `--phase`"; claim 4 — `--ctx` defaults to `safe` (script lines 105-106, 1576).
- **Search performed**: Read of `workflow-startup-precheck.sh` lines 95-122 and 1567-1599.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:105-106` (comment: "LEDGER_CTX defaults to … `safe`"), `:108` (`LEDGER_CTX=""`), `:1576` (`ctx="${LEDGER_CTX:-safe}"`), `:120` (Usage line).
- **Actual behavior**: `LEDGER_CTX` initialises empty; `append_ledger` sets `ctx="${LEDGER_CTX:-safe}"`, so an omitted/empty `--ctx` resolves to `safe`. The default applies only when the flag is omitted; an explicitly passed value (including `unknown`) is used verbatim after `reject_bad_ledger_value "ctx" "$ctx" bare`.
- **Verdict**: PARTIAL
- **Detail**: The `safe` default is confirmed, but it is the *omitted-flag* default. The fix always passes `--ctx <level>`, so the default is never exercised; the value actually written is whatever step 5 computed, which can be `unknown`. Produces T3 (prose precision).

#### P4: Usage line argument order puts `--ctx` before `--phase` (D2's false-negative hinge)
- **Track claim**: D2 — "a contiguous `--append-ledger --phase C --track` match would be a false-negative against the correct code"; the real call is `--append-ledger --ctx <level> --phase C --track <N>`.
- **Search performed**: Read of `workflow-startup-precheck.sh:120`.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:120`.
- **Actual behavior**: `workflow-startup-precheck.sh --append-ledger [--ctx <level>] [--phase <token>] [--track <n>] …` — `--ctx` precedes `--phase` precedes `--track` in the canonical Usage order.
- **Verdict**: CONFIRMED
- **Detail**: D2's design hinges on exactly this: the real call interposes `--ctx <level>` between `--append-ledger` and `--phase C`, so an order- and whitespace-tolerant three-flag match (`--append-ledger`, `--phase C`, `--track` present in any order) is required; a contiguous-substring guard would false-negative. The arg parser is positional-agnostic (a `while`/`case` loop, `:124-169`), so the flags may legitimately appear in any order, reinforcing the order-independent guard. D2 is sound.

#### P5: a completed Phase A track file resolves the C-case to substate=steps-partial (Phase B), not decomposition-pending
- **Track claim**: Context — "A `decomposition-pending` substate enters Phase A Resume … any other substate enters Phase B/C"; Purpose — "resumes into Phase B/C."
- **Search performed**: Read of `workflow-startup-precheck.sh` `determine_c_substate` and the `C)` case; cross-check with `workflow.md` step-5 substate table.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:1690-1734` (`determine_c_substate`), `:1781-1799` (`C)` case), `.claude/workflow/workflow.md:339-349` (substate routing table).
- **Actual behavior**: `determine_c_substate` short-circuits `decomposition-pending` only when "Review + decomposition" is **not** done (`:1693-1697`). A completed Phase A has it done and a `[ ]`-step roster, so it falls through section-discrepancy (no `[x]` steps) and failed-step (no `[!]`) to **`steps-partial`** (`:1722-1725`). `workflow.md:347` maps `steps-partial` → "Resume from the next `[ ]` step" (Phase B). `decomposition-pending` (`:344`) maps to Phase A Resume and is the *interrupted-before-decomposition* case.
- **Verdict**: PARTIAL
- **Detail**: The Purpose claim ("resumes into Phase B/C") is correct — the realized path is `phase=C` → `steps-partial` → Phase B. But the C&O paragraph foregrounds the `decomposition-pending` branch, which is not the completed-Phase-A case. Produces T2 (reader orientation).

#### P6: workflow.md §Startup Protocol step 5 — phase==A routes to Pre-Flight+Phase A; phase==C skips Pre-Flight, routes on substate
- **Track claim**: claim 7 / Context / Acceptance #2 — "`phase == "A"` routes to Track Pre-Flight + Phase A (read as 'track has no track file yet'); `phase == "C"` routes to mid-track Phase B/C with Pre-Flight skipped — so the recorded boundary alone produces correct routing with no router change."
- **Search performed**: Read of `workflow.md` §Startup Protocol step 5.
- **Code location**: `.claude/workflow/workflow.md:325-354`.
- **Actual behavior**: `phase == "A"` — "the active track has no track file yet (pre-Phase-A) … Run the Track Pre-Flight gate … then Phase A in the same session." `phase == "C"` — "mid-track resume … Route on `state.substate`" with the table at `:342-349`; "The Track Pre-Flight gate is **skipped** on State C resume" (`:351-354`).
- **Verdict**: CONFIRMED
- **Detail**: Routing is exactly as the track claims; no router change is needed. Acceptance criterion #2 is realizable by inspection against this prose plus the script's `C)` case. The recorded `phase=C` boundary alone flips the resume from a Phase-A re-run to a mid-track Phase B/C resume.

#### P7: workflow-startup-precheck.sh already supports the append — `--phase` parser case and the `C)` resume case
- **Track claim**: claim 3 / Context / Out-of-scope — arg parser `--phase` case (line 149), `determine_state_from_ledger` `C)` case (line 1781) emitting `{phase:"C", substate:<track-driven>}`; "no script change."
- **Search performed**: Read of `workflow-startup-precheck.sh:124-169` (arg parser) and `:1773-1806` (`case "$phase"`).
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:149` (`--phase) LEDGER_PHASE="$2"; shift 2`), `:1781-1798` (`C)` case).
- **Actual behavior**: Line 149 is the `--phase` parser case exactly. The `C)` case at `:1781` reads the active `track` from the ledger (defaults to `1` for single-track minimal, `:1785-1787`), resolves `track_file="$plan_dir/_workflow/plan/track-${track}.md"`, and if present emits `{phase:"C", substate:$C_SUBSTATE}` via `determine_c_substate`; if absent emits `{phase:"A", substate:null}` (pre-decomposition).
- **Verdict**: CONFIRMED
- **Detail**: Both line numbers exact; the C-case behavior matches the track's description. No script change is needed for the append to be accepted and read — the out-of-scope claim holds.

#### P8: the existing script tests at :3426 (--phase C append) and :3447 (phase=C/track=1 read) exist and exercise the claim
- **Track claim**: claim 6 / Acceptance #1 — tests at `test_workflow_startup_precheck.py:3426` (the `--phase C` append) and `:3447` (the `phase=C`/`track=1` read) exist and exercise what the track claims.
- **Search performed**: Read of `test_workflow_startup_precheck.py:3417-3461`.
- **Code location**: `.claude/scripts/tests/test_workflow_startup_precheck.py:3426` (inside `test_append_ledger_appends_does_not_overwrite`), `:3447` (inside `test_append_ledger_last_value_wins_on_read`).
- **Actual behavior**: `:3426` runs `run_precheck("--append-ledger", "--phase", "C", "--track", "1", …)` and asserts `phase=C` lands on line 2. `:3447` runs `--append-ledger --phase C --track 1 --tier full`, writes a track-1 fixture, then asserts `_state(run_precheck("--mode", "full", …)) == {"phase": "C", "substate": "decomposition-pending"}` — the latest `phase=C`/`track=1` wins over the earlier `phase=A`.
- **Verdict**: CONFIRMED
- **Detail**: Both tests exist at the cited lines and exercise the append + read the track relies on. (Side note grounding T2: the `:3447` fixture's track file has `Review + decomposition` `[ ]`, so `determine_c_substate` returns `decomposition-pending` — the *pre-decomposition* shape, confirming that `steps-partial` is the *post-decomposition* shape the real completed-Phase-A case yields.)

#### P9: the test module resolves workflow `.md` paths via two distinct repo-root anchors
- **Track claim**: D2 / Plan-of-Work step 3 — the guard "can read `track-review.md` directly" because the module "already resolves `.claude/workflow/` paths from the repo root."
- **Search performed**: Read of `test_workflow_startup_precheck.py:52-80` and `:3005`.
- **Code location**: `:52` (`REPO_ROOT = Path(__file__).resolve().parents[3]`), `:56-74` (`_resolve_live_repo_root`), `:80` (`LIVE_REPO_ROOT`), `:3005` (`CONVENTIONS_PATH = LIVE_REPO_ROOT / ".claude" / "workflow" / "conventions.md"`).
- **Actual behavior**: Two anchors exist. `REPO_ROOT` is `parents[3]` — under the staged mirror (`_workflow/staged-workflow/.claude/scripts/tests/`) this is the staged-workflow root. `LIVE_REPO_ROOT` walks up to the first ancestor holding `.claude/workflow/conventions.md` — the *live* repo root even from a staged checkout. The only existing workflow-`.md` read (`CONVENTIONS_PATH`) uses `LIVE_REPO_ROOT` "so the … conformance byte-source resolves against the live `conventions.md`."
- **Verdict**: PARTIAL
- **Detail**: "Resolves … from the repo root" is true but under-specified — there are two roots that diverge during staging, and the existing precedent uses the one that reads the *live (unfixed)* file. Produces T1 (should-fix).

#### P10: §1.7 staging keeps both the live and staged copies in the working tree; reads prefer the staged copy when present
- **Track claim**: D3 — edits are staged under `_workflow/staged-workflow/.claude/`; "the orchestrator running this branch's own Phase A reads the **live** (develop-state) `track-review.md`, which lacks the fix."
- **Search performed**: Read of `conventions.md §1.7` TOC + bodies (`:34-38`, `:955-974`, `:1095-1141`); `find` for `track-review.md` / staged-workflow in the working tree.
- **Code location**: `conventions.md:961-964` (staged path layout), `:1095-1134` (§(d) reads precedence — "Read the staged copy when it exists, else the live file"); working tree: only `./.claude/workflow/track-review.md` exists, no `staged-workflow/` yet.
- **Actual behavior**: During the branch, the fixed `track-review.md` lives at `_workflow/staged-workflow/.claude/workflow/track-review.md` and the live path keeps the develop-state copy until Phase 4 promotion. §1.7(d) says readers take the staged copy when present. No staged subtree exists yet (Phase A, no edits) — correct.
- **Verdict**: CONFIRMED
- **Detail**: D3's self-application wrinkle is sound: the *orchestrator* reading the live `track-review.md` self-inflicts the bug until promotion, hence the documented hand-append of `phase=C track=1`. T1 is the *test* analogue of this same wrinkle: the guard must be told to read the **staged** copy (`REPO_ROOT`), the inverse of the orchestrator's unavoidable live read, so the test asserts the fix that is actually being made.

#### P11: track-review.md sub-step 5 ctx read falls back to `unknown`, not `safe`
- **Track claim**: Context — step 5 "reads the context level … falls back to `unknown` on a miss."
- **Search performed**: Read of live `track-review.md:560-579`.
- **Code location**: `.claude/workflow/track-review.md:563-571`.
- **Actual behavior**: "Read `/tmp/claude-code-context-usage-$PPID.txt` and parse the `level=` value. If the file is missing or the parse fails, use `unknown` per the D12 fallback rule — do not skip the write."
- **Verdict**: CONFIRMED
- **Detail**: Step 5's miss fallback is `unknown`. Combined with P3 (the script default `safe` applies only to an omitted flag), the reused `<level>` fed to `--ctx` may be `unknown`. Harmless (valid bare token) but produces T3 (D1 prose precision).
