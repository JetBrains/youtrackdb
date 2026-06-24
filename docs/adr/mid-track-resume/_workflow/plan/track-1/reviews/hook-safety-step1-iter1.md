<!--MANIFEST
dimension: workflow-hook-safety
step: track-1-step-1
iteration: 1
commit_range: f20a70b7ef~1..f20a70b7ef
findings_total: 0
blockers: 0
evidence_base:
  certs: 0
index: []
cert_index: []
flags: []
-->

## Findings

No operational-safety findings within the reviewed delta.

The delta touches `workflow-startup-precheck.sh` (the `--substate` ledger
primitive, `ledger_tail_value_for_track`, the ledger-first read in
`determine_state_from_ledger`, and the `roster_scan` continuation-line join) and
its Python test file. Across the seven operational-safety axes the delta is
clean:

- **Shell hygiene.** Shebang `#!/usr/bin/env bash` present. The absent global
  `set -e`/`set -u` is documented at the file header (line 90: "matching
  statusline-command.sh … the detection paths rely on defensive `|| true`") — an
  intentional, justified posture, not an omission. Every new expansion is quoted
  (`"$line"`, `"$ledger"`, `"$track"`, `"$LEDGER_SUBSTATE"`); command
  substitution uses `"$(ledger_path)"`; the `case " $line"` space-prefix gives a
  correct token-boundary match. The new code uses `[ ... ]` POSIX tests
  consistently with the rest of the file (house convention for this script),
  not a mix. No backticks, no `eval`.
- **`/tmp` collision safety.** No `/tmp` literal anywhere in the script. The
  append primitive's temp file is `$dir/.phase-ledger.$$.tmp` — a sibling of the
  ledger carrying `$$`, reaped on a `trap 'rm -f "$tmp"' RETURN`. The test
  harness uses `tempfile.TemporaryDirectory(prefix="precheck-git-")`
  (random-suffixed, context-manager-cleaned). Both satisfy the per-process
  uniqueness rule.
- **Idempotency.** The new read paths (`ledger_tail_value_for_track`, the
  ledger-first read) are pure reads — no mutation, naturally idempotent. The
  `--substate` append rides the existing atomic temp-file-plus-rename append; no
  new write path is introduced.
- **Hook performance.** The precheck is a turn-1 startup script, not a
  per-tool-use hook. The wrap-join and the track-scoped reader add only
  in-process string work over the (small) ledger and track files; no `find` over
  large trees, no network, no Maven, no new subprocess on the read path.
- **Secret hygiene.** No credentials, tokens, or secret-bearing env vars in the
  delta.
- **Concurrent-agent safety.** No git mutation, no shared-lockfile or
  Maven-local-repo write added. The append's PID-suffixed temp already guards the
  one write path against a stray concurrent re-invocation.
- **Error handling.** `--substate` joins the existing loud-reject validation
  (`reject_bad_ledger_value "substate" … bare`, exit 3 + stderr diagnostic on a
  space or newline). The ledger-first read reuses the same `jq -nc --arg s`
  STATE_JSON construction as the fallback arm, so JSON escaping stays uniform and
  safe across both paths.

`bash -n` and `python3 -m py_compile` both pass on the staged files.

## Evidence base
