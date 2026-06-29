<!--
MANIFEST
dimension: workflow-hook-safety
iteration: 1
verdict: PASS
findings: 2
blockers: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WH1
    sev: suggestion
    anchor: "### WH1 "
    loc: ".claude/scripts/tests/test_workflow_startup_precheck.py:130-182"
    cert: n/a
    basis: differential-fuzz
  - id: WH2
    sev: suggestion
    anchor: "### WH2 "
    loc: ".claude/scripts/tests/test_workflow_startup_precheck.py:112-118"
    cert: n/a
    basis: static-read
-->

## Findings

### WH1 [suggestion] Python reader replica advertises line-start anchoring the shell readers do not deliver

- File: `.claude/scripts/tests/test_workflow_startup_precheck.py` (delta lines 130-182, the `_ledger_tail_value` / `_ledger_tail_value_for_track` replicas)
- Axis: Python script (test-fidelity, replica-vs-shell divergence)
- Cost: a divergent replica passes while the schema it pins does not match the real shell reader Track 2 will build against; here the replica is strictly *more* permissive than the shell.

The two replicas anchor the key match with `re.search(rf"(?:^|\s){re.escape(key)}=(.*)$", line)` — the `(?:^|\s)` alternation matches a key at column 0 (line start). The shell readers do **not**: `ledger_tail_value` (staged script line 1840) scans `case " $line" in *" $key="*)` and then strips with `rest="${line#*" $key="}"`, which requires a literal space before the key. A line beginning with a bare `key=` (no leading ` `) is not matched, and the strip returns the line unchanged, yielding a wrong value.

I confirmed this by differential fuzz: I extracted the two shell reader functions into an isolated harness (overriding `ledger_path` to point at a fixture file) and compared 14 crafted ledgers against the Python replica. Thirteen agreed; one diverged:

```
input:  "design_gate=yes phase=A\n"   (key at column 0, no [ISO] prefix)
shell  ledger_tail_value("design_gate") -> "design_gate=yes"   (wrong)
python _ledger_tail_value(...)          -> "yes"               (the advertised contract)
```

The shell readers are correct for every **reachable** input: `append_ledger` always prepends `[<ISO>] [ctx=<level>]`, so no emitted ledger line ever starts with a bare `key=`, and the script never feeds itself a column-0 line. No test exercises a column-0 line either, so the divergence is invisible today. The hazard is forward-looking: the replica is the frozen schema contract Track 2 wires its real readers against, and the shell reader's own comment (staged script line 1830-1831) explicitly claims it anchors "either at line start (`key=`) or after a space (` key=`)" — an advertised behavior the implementation does not actually provide. If a future hand-authored or migrated ledger ever drops the timestamp prefix, the replica would bless column-0 reads the shell silently mishandles.

Suggestion: pick one direction and make the comment, the shell, and the replica agree. Either (a) correct the shell reader's comment to state it requires a leading space and change the replica's regex to `r"(?:^| )" + ...` (mirroring the shell's actual ` key=` scan), or (b) make the shell genuinely anchor at line start (e.g., test `case "$line" in "$key="*)` in addition to the ` $key=` case) and keep the replica as is. Low urgency — unreachable through the current emitter — but the replica is exactly the artifact whose fidelity the step calls out as load-bearing.

### WH2 [suggestion] `run_precheck` subprocess invocation has no timeout

- File: `.claude/scripts/tests/test_workflow_startup_precheck.py` (lines 112-118, `run_precheck`)
- Axis: Python script (external tool invocation without timeout)
- Cost: a wedged `git` / `mv` / filesystem stall under one of the many `run_precheck` calls hangs the test runner indefinitely instead of failing the suite.

`run_precheck` calls `subprocess.run(["bash", str(SCRIPT_PATH), *args], capture_output=True, text=True, check=False, cwd=...)` with no `timeout=`. The precheck script shells out to `git` (branch resolution, divergence/drift walks) and, on the append path the new tests exercise, to `mkdir`/`cat`/`mv`. Track 1 adds nine new `run_precheck`-driven tests, each an unbounded external invocation. In practice the script is fast and local, so this never bites, and the gap pre-exists in `run_precheck` rather than being introduced here — but the dimension's Python-script rule is that external tool invocations carry a timeout.

Suggestion: add a generous `timeout=` (e.g. `timeout=30`) to the `subprocess.run` call so a hung child fails loudly rather than blocking the runner.

## Evidence base
