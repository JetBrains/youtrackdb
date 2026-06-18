<!--MANIFEST
review: workflow-consistency
dimension: workflow cross-file consistency
target: Track 1, Step 1 (YTDB-1140 A→C ledger append) — commit b40d358a00
iteration: 1
findings: 0
index: []
evidence_base:
  certs: 0
cert_index: []
flags: []
-->

## Findings

No cross-file consistency defects in the review-target delta.

Every cross-file reference the new prose and test introduce resolves to a live
referent:

- The step-6 append call `workflow-startup-precheck.sh --append-ledger --ctx
  <level> --phase C --track <N>` matches the script's actual flag interface
  (`--append-ledger` line 139, `--ctx` line 145, `--phase` line 149, `--track`
  line 153) and the documented Usage line 120, including the canonical flag
  order with `--ctx` before `--phase`.
- The `--ctx <level>` reuse-or-`unknown` clause is consistent with the script:
  `--ctx` takes an arbitrary level token (`LEDGER_CTX="$2"`), and the omitted-flag
  default is `safe` (`ctx="${LEDGER_CTX:-safe}"`, line ~1576) — so passing a bare
  `unknown` token is distinct from omitting the flag, exactly as the prose states.
- The test's `REPO_ROOT` anchor claim resolves: `REPO_ROOT = Path(__file__).resolve().parents[3]`
  (line 52) is the same anchor `SCRIPT_PATH` uses (line 53), and is distinct from
  `LIVE_REPO_ROOT` (line 80) / `CONVENTIONS_PATH` (line 3005). The §1.7(b) staging
  rationale (staged co-located copy authoritative) is internally consistent with
  the live module structure.
- The test's region-slice logic is sound: the `^6\. \*\*Append the A.C ledger
  boundary` regex matches the staged step-6 heading (line 581), and the next `### `
  heading is `### Tier-driven review selection…` (line 618), so the sliced region
  contains all three flags and correctly excludes the §Phase A Completion step-2
  prose (line ~1030+) that carries the literal `phase=C track=<N>` and `--phase C`
  recovery snippet — the self-match trap the docstring describes is real and the
  slice avoids it.
- The new test-runner registration tuple lands in the correct test registry list
  alongside the sibling append-path tuples (line ~4132).
- The §Phase A Completion step-2 verification cites `phase=C track=<N>` routing,
  consistent with `workflow.md` §Startup Protocol step 5 (`phase == "C"` →
  mid-track resume, Track Pre-Flight skipped, lines 339-351) and the track file's
  Context section.
- The sibling append sites cited as intentionally divergent resolve to their
  develop-state live form — `implementation-review.md:646` (`--append-ledger
  --phase A`), `track-code-review.md:1403` (`--track <N+1>`) and `:1405`
  (`--phase D`), all without `--ctx`. The prose labels its own `--ctx`-carrying
  append a deliberate divergence, which matches the live sibling state; this is an
  accurate description, not a stale reference.
- The ledger filename `phase-ledger.md` is used consistently across the staged
  track-review.md (step 6 `git add` path, step 2 `tail -1` paths) and the script.

Note on staged-read precedence: the live `.claude/...` files remain at develop
state during this §1.7(b)-staged branch and were resolved as such; the staged
copies under `_workflow/staged-workflow/` were used for the two fixed files. The
expected live-vs-staged differences (the fix self-applies only at Phase 4
promotion) are by design and are not phantom mismatches.

## Evidence base
