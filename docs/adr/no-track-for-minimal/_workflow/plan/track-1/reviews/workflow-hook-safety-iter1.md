<!--MANIFEST
dimension: workflow-hook-safety
iteration: 1
target: "Track 1, Step 1 — ledger primitive in workflow-startup-precheck.sh + tests (17f47c8b62~1..17f47c8b62)"
evidence_base: { certs: 0 }
cert_index: []
flags: [evidence-trail-exempt]
findings:
  - { id: WH1, sev: Recommended, anchor: "wh1-field-value-injection-splits-the-ledger-line", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:1214-1233", cert: n/a, basis: judgment }
  - { id: WH2, sev: Recommended, anchor: "wh2-append-swallows-mkdir-write-rename-failure-yet-exits-0", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:1207-1233", cert: n/a, basis: judgment }
  - { id: WH3, sev: Minor, anchor: "wh3-no-trap-cleanup-of-the-pid-suffixed-temp-file", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:1226-1233", cert: n/a, basis: judgment }
-->

## Findings

### WH1 [Recommended] field-value injection splits the ledger line

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (line 1214-1233)
- Axis: error handling
- Cost: a newline in any `LEDGER_*` value silently splits one event across lines; last-value-wins then mis-resolves resume state

`append_ledger` interpolates the raw `LEDGER_*` accumulators straight into the
event line with no validation:

```sh
line="[$ts] [ctx=$ctx]"
[ -n "$LEDGER_PHASE" ]      && line="$line phase=$LEDGER_PHASE"
...
[ -n "$LEDGER_CATEGORIES" ] && line="$line categories=\"$LEDGER_CATEGORIES\""
```

Two value classes break the pinned grammar. Verified empirically against the
staged script:

- A newline in any field (`--phase $'X\nphase=Done'`) writes two physical
  lines. Because `ledger_tail_value` reads last-value-wins across every line, a
  smuggled `phase=Done` line becomes the resolved phase and routes resume to the
  wrong state. Observed output: a `phase=X` line followed by a bare `phase=Done`
  line.
- An embedded `"` in `categories` (`--categories 'foo"bar'`) closes the quoted
  span early — the on-disk token is `categories="foo"bar"`, which the reader's
  `val="${rest%%\"*}"` truncates to `foo`. The header documents `categories` as
  "the one quoted value (it may carry spaces and commas)"; a `"` is outside that
  alphabet.

This is not an external-attacker injection: the orchestrator is the sole caller
and the header asserts "every other value is a bare metacharacter-free token,"
so normal operation passes controlled tokens. But this primitive is the resume
state machine and the grammar is "the contract Track 2 consumes," so a malformed
value silently corrupting the tail (rather than failing loudly) is a robustness
gap for a foundation other tracks build on. Suggestion: reject (or strip) a
newline in any field and reject a `"` in `categories`, exiting non-zero with a
stderr message — mirror the loud-reject posture the read path already takes for
an unrecognized `phase` (`parse_error`, exit 3).

### WH2 [Recommended] append swallows mkdir / write / rename failure yet exits 0

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (line 1207-1233)
- Axis: error handling
- Cost: a failed boundary append (full disk, unwritable dir) reports success to the orchestrator, which then believes the phase was recorded when it was not

Under the script's intentional no-`set -e` posture, every write step in
`append_ledger` is unchecked:

```sh
mkdir -p "$dir"
...
if [ -f "$ledger" ]; then cat "$ledger" > "$tmp"; else : > "$tmp"; fi
printf '%s\n' "$line" >> "$tmp"
mv -f "$tmp" "$ledger"
```

and the caller unconditionally `exit 0`s:

```sh
if [ "$APPEND_LEDGER" = "1" ]; then
  append_ledger
  exit 0
fi
```

If `mkdir -p` fails (permissions), or `cat`/`printf` to `$tmp` fails (disk
full), or `mv` fails, the append is silently incomplete but the process still
exits 0. The orchestrator calls this "at the same boundaries it flips plan
checkboxes today" and treats success as "the boundary is recorded"; a swallowed
failure means a later resume reads a stale tail and routes to the prior state
with no signal that the boundary was lost. The no-`set -e` posture is correct
for the JSON detection paths (they rely on `|| true`), but this mutation path
has a single linear write sequence whose failure must be surfaced. Suggestion:
check the write+rename (`mv -f "$tmp" "$ledger" || { echo "append-ledger: failed
to publish $ledger" >&2; exit 1; }`, and likewise guard `mkdir -p` and the temp
write), so a failed append fails loudly instead of masquerading as a recorded
boundary.

### WH3 [Minor] no trap cleanup of the PID-suffixed temp file

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (line 1226-1233)
- Axis: /tmp collision
- Cost: a mid-write failure orphans `.phase-ledger.<pid>.tmp` in `_workflow/`; orphans accumulate across failed invocations

The temp file is created in the ledger's own directory and consumed by `mv -f`
on the success path:

```sh
tmp="$dir/.phase-ledger.$$.tmp"
if [ -f "$ledger" ]; then cat "$ledger" > "$tmp"; else : > "$tmp"; fi
printf '%s\n' "$line" >> "$tmp"
mv -f "$tmp" "$ledger"
```

The `$$` suffix correctly satisfies the concurrent-process collision rule (and
the same-directory placement is required for the rename to stay atomic, so this
is the right location — the literal `/tmp` rule does not apply). But there is no
`trap` to reap the temp on a failure between creation and the `mv`. The blast
radius is small: `.gitignore` line 131 (`*.tmp`) keeps an orphan out of commits,
and the drift walk's hardcoded glob set
(`implementation-plan.md`/`design.md`/`design-mechanics.md`/`track-*.md`) never
matches it, so an orphan trips neither the commit nor the drift gate — hence
Minor. The remaining cost is bare orphan accumulation in `_workflow/` across
failed appends. Suggestion: add `trap 'rm -f "$tmp"' RETURN` (or an EXIT trap
scoped to the append) so an interrupted append leaves nothing behind. The
torn-append test (`test_torn_append_leaves_prior_tail_intact`) deliberately
plants such a stray temp to prove the read ignores it, which confirms the
read-side safety but also that no producer-side cleanup exists.

## Evidence base
