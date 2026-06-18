<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Technical review — gate verification (iter 2)

Re-check of the three technical findings (T1 should-fix, T2/T3 suggestion)
after fixes were applied to `track-1.md`. All three fixes landed correctly,
are internally consistent across the cited track-file sections, and their
technical claims check out against the live test module and script anchors.
No fix introduced a regression. Overall: PASS.

Verification basis (no Java in scope; this is a workflow-prose plus
Python-test change). References resolved as workflow paths, `§`-anchors, and
script/test line numbers via Read and grep, not PSI — per the spawn's context
note. The branch is `s17=workflow-modifying` with no `staged-workflow/`
subtree yet, so every `.claude/**` read resolved to the live file per §1.7(d).

## Verdicts

#### Verify T1: D2 guard anchor under-specified
- **Original issue**: D2 / §Plan of Work step 3 / §Interfaces said the guard
  "reads `track-review.md` directly" without pinning the repo-root anchor;
  copying the `CONVENTIONS_PATH`/`LIVE_REPO_ROOT` precedent (`:3005`) would read
  the unfixed live `track-review.md` and false-fail the guard during this
  branch's own Phase B.
- **Fix applied**: D2 now mandates resolving `track-review.md` relative to
  `REPO_ROOT` (`parents[3]`, same anchor as `SCRIPT_PATH:53`), explicitly NOT
  `LIVE_REPO_ROOT` (`:80`/`:3005`), with the §1.7(b)/§1.7(d) staging rationale.
  Short pointers added to §Plan of Work step 3 and the §Interfaces test-file
  bullet.
- **Re-check**:
  - Track-file locations: D2 at `track-1.md:96-107`; §Plan of Work step 3 at
    `:246-248`; §Interfaces test-file bullet at `:308-311`.
  - Current state (presence + consistency): all three sites carry the
    `REPO_ROOT` (`parents[3]`)-not-`LIVE_REPO_ROOT` guidance. D2 (`:97-98`)
    pins `REPO_ROOT` / `Path(__file__).resolve().parents[3]` / "same anchor
    `SCRIPT_PATH` uses at `:53`" and "**not** `LIVE_REPO_ROOT` (`:80`, …
    `:3005`)"; §Plan of Work step 3 (`:247-248`) and §Interfaces (`:310-311`)
    each carry the short "`REPO_ROOT` anchor (the staged copy under staging),
    not `LIVE_REPO_ROOT` — see D2" pointer. The grep over the track file shows
    no surviving "reads … directly" framing that omits the anchor. Internally
    consistent across D2, §Plan of Work, §Interfaces.
  - Technical claim (verified against the actual test module
    `test_workflow_startup_precheck.py`): `:52`
    `REPO_ROOT = Path(__file__).resolve().parents[3]`; `:53`
    `SCRIPT_PATH = REPO_ROOT / ".claude" / "scripts" / "workflow-startup-precheck.sh"`
    (anchored on `REPO_ROOT`); `:80`
    `LIVE_REPO_ROOT = _resolve_live_repo_root()` (the `:56-74` helper walks up
    to the first ancestor holding `.claude/workflow/conventions.md`); `:3005`
    `CONVENTIONS_PATH = LIVE_REPO_ROOT / …` (the precedent that copies
    `LIVE_REPO_ROOT`). The module's own `:77-79` comment confirms
    "`SCRIPT_PATH` deliberately stays anchored at `REPO_ROOT` (parents[3]) so a
    staged copy of this suite exercises the staged script," and `:62-67`
    confirms `LIVE_REPO_ROOT` walks up past the staged-workflow root to the
    real repo root. So under staging a co-located `REPO_ROOT` reads the staged
    *fixed* `track-review.md` carrying the append, while `LIVE_REPO_ROOT` walks
    to the develop-state *unfixed* copy. D2's claim is exactly correct, and the
    "false-fail during this branch's own Phase B" rationale holds: the fix
    self-applies only at Phase 4 promotion (D3), so during Phase B the live
    copy lacks the append.
  - Criteria met: anchor is now pinned (no under-specification), the
    cross-section guidance is consistent, and the technical claim matches the
    test-module anchors at `:52/:53/:80/:3005`.
- **Regression check**: checked the surrounding D2 prose (the order-/whitespace-
  tolerant three-flag rule, the bare-`grep` rejection, the contiguous-string
  rejection) — unchanged and still coherent with the new anchor clause; checked
  §Plan of Work step 3 and §Interfaces flow around the inserted pointers — reads
  cleanly, no dangling reference. Clean.
- **Verdict**: VERIFIED

#### Verify T2: C&O over-emphasized `decomposition-pending`
- **Original issue**: the C&O router paragraph framed `decomposition-pending`
  as the operative C-case substate, but a completed Phase A resolves to
  `steps-partial` → Phase B.
- **Fix applied**: C&O now adds a clause stating a completed Phase A
  (`## Progress` "Review + decomposition" `[x]`, populated `[ ]` roster)
  resolves the C-case to `steps-partial` → Phase B; `decomposition-pending` is
  the interrupted-before-decomposition case.
- **Re-check**:
  - Track-file location: §Context and Orientation router paragraph,
    `track-1.md:203-208`.
  - Current state: `:206-208` reads "For a *completed* Phase A — `## Progress`
    'Review + decomposition' `[x]` and a populated `[ ]` step roster —
    `determine_c_substate` resolves the C-case to `steps-partial`, which routes
    to Phase B; `decomposition-pending` is the distinct
    interrupted-before-decomposition case." The clause is present and the prior
    `decomposition-pending`-as-operative framing is corrected.
  - Substate claim (verified against `determine_c_substate` in
    `workflow-startup-precheck.sh`): `:1690` function entry; `:1692-1697` the
    `decomposition-pending` short-circuit fires only when
    `PROGRESS_TOKEN != "done"` (Review + decomposition not `[x]`); `:1722-1725`
    the `steps-partial` case fires on `ROSTER_HAS_TODO == 1` (any `[ ]` step
    remains). For a completed Phase A — Progress `[x]` (token `done`,
    short-circuit not taken) plus a populated `[ ]` roster — control reaches
    `:1723` and returns `steps-partial`, after passing the
    `section-discrepancy` (`:1704-1715`) and `failed-step` (`:1717-1721`)
    checks that do not match a freshly-decomposed all-`[ ]` roster. Claim
    correct. Router map (`workflow.md:347`): `steps-partial` → "Resume from the
    next `[ ]` step", which enters Phase B; `:344` maps `decomposition-pending`
    → "Enter `track-review.md` §Phase A Resume", the distinct interrupted
    case. Both halves of the C&O clause match the script and the router.
  - Criteria met: the operative C-case substate for a completed Phase A is now
    correctly stated as `steps-partial`, and `decomposition-pending` is
    correctly relegated to the interrupted case.
- **Regression check**: checked the rest of the router paragraph (`:197-213`,
  the `phase == "A"` vs `phase == "C"` description and the `determine_state_from_ledger`
  `C)`-case reference at script `:1781`) — the new clause is consistent with the
  surrounding `phase == "C"` description and does not contradict the
  `decomposition-pending`-is-near-no-op statement at `:203-204`. Clean.
- **Verdict**: VERIFIED

#### Verify T3: D1 `<level>` reuse read as always a real level
- **Original issue**: D1 implied the reused `<level>` is always
  `safe`/`info`/…; step 5's statusline read falls back to `unknown` on a miss.
- **Fix applied**: D1 now appends "(`unknown` when step 5's statusline read
  missed — a valid bare `--ctx` token, distinct from the script's `safe`
  default for an omitted flag)".
- **Re-check**:
  - Track-file location: D1, `track-1.md:48-51`.
  - Current state: `:49-51` reads "Reuse the `<level>` value step 5 already
    read … to feed `--ctx` (`unknown` when step 5's statusline read missed — a
    valid bare `--ctx` token, distinct from the script's `safe` default for an
    omitted flag)." Clause present; matches §Context and Orientation `:187`
    ("falls back to `unknown` on a miss"), so the two descriptions of step 5's
    fallback agree.
  - Claim (verified against `workflow-startup-precheck.sh`): `:1576`
    `ctx="${LEDGER_CTX:-safe}"` — the `safe` default applies only when
    `LEDGER_CTX` is unset/empty (an omitted `--ctx`); `:1577`
    `reject_bad_ledger_value "ctx" "$ctx" bare` validates the value as a bare
    token (the `:1574-1575` comment: "ctx and the bare-token fields reject
    spaces and newlines"). `unknown` is a non-empty, space-/newline-free token,
    so it passes validation and is carried verbatim — it is an accepted bare
    `--ctx` value, distinct from the `safe` default that fires only on an
    omitted flag. Claim correct, matching the reviewer's cited `:1576`/`:1577`.
  - Criteria met: D1 no longer implies `<level>` is always a real level; the
    `unknown` fallback and its distinction from the `safe` omitted-flag default
    are both stated correctly.
- **Regression check**: checked the rest of D1 (the step-6 placement, the
  `git add phase-ledger.md`, the step-2 ledger-tail verification with its
  recovery branch) and D1's Why/Alternatives — the inserted parenthetical does
  not disturb the surrounding sentence or the four-bullet DR shape. Clean.
- **Verdict**: VERIFIED

## Findings

No new findings. All three prior findings VERIFIED; the fixes are present,
internally consistent, and technically correct against the live anchors.

## Summary

PASS — T1 VERIFIED, T2 VERIFIED, T3 VERIFIED, 0 new findings, 0 regressions.
