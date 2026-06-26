<!--MANIFEST
review: bugs-concurrency
track: 1
step: 1
iter: 1
range: 96b269cfcca705da0c157047561a07fb36aa01c6~1..96b269cfcca705da0c157047561a07fb36aa01c6
verdict: PASS
blocker_count: 0
finding_count: 0
findings_under_recheck: 0
evidence_base: present
cert_index: []
flags: []
index: []
-->

# Bugs & Concurrency Review — Track 1, Step 1 (iter 1)

## Findings

No bugs, concurrency issues, resource leaks, or null-safety defects found in this
dimension. The step is a greenfield, data-only IR substrate with no shared mutable
state, no resources, no concurrency surface, and no live consumer; the only non-trivial
logic (the `transformChildren` recurse-and-share algorithm, including the `FuncCall`
lazy-copy loop, and the `dispatch` routing) was traced through every arm and is correct,
with the tests exercising each arm. Thread-safety, race-condition, deadlock,
resource-leak, RID-handling, and transaction-lifecycle categories are inapplicable by
construction and are therefore omitted.

## Evidence base

#### C1: No concurrency or resource surface exists in the diff
- **Verdict:** CONFIRMED — the five record variants are immutable (record final fields); `dispatch`, `transformChildren`, and every `AnalyzedExprTransform` default are `static` or stateless instance methods that read only their arguments and locals; there is no field, no static mutable state, no lock, no `AutoCloseable`, no direct buffer, no I/O, no RID, and no transaction handle anywhere in the seven files. Thread-safety / race / deadlock / resource-leak / RID / state-machine analysis has no target. (PSI: new package `com.jetbrains.youtrackdb.internal.core.query.analyzed` holds exactly the six main classes plus the test; no pre-existing collision.)

#### C2: `transformChildren` recurse-and-share logic is correct on every arm
- **Verdict:** CONFIRMED — leaf arms (`Var`, `Const`) return the input by reference; `BinaryOp`/`UnaryOp` rebuild only when a child returns a `!=` instance via the `left == b.left() && right == b.right()` (resp. `operand == u.operand()`) short-circuit; the `FuncCall` arm keeps `newArgs` null until the first changed argument, copies the unchanged prefix `args.subList(0, i)` once, then appends every subsequent transformed element. Traced for all-unchanged, first-change-at-index-0, mid-list change, and change-after-allocation; no off-by-one. Children are recursed through `dispatch(child, t)` (not `transformChildren`), so a transform's variant override is honored — the intended D2/D9 entry-point indirection. Tests (a)–(f), the right-child-only `BinaryOp` case, the nested-spine case, and the equal-but-rebuilt negative case cover every arm.

#### C3: `UnsupportedAnalyzedNodeException` constructor calls resolve to real supers
- **Verdict:** CONFIRMED — PSI shows `CommandExecutionException` declares `(String)`, `(String, String)`, `(DatabaseSessionEmbedded, String)`, and the copy ctor `(CommandExecutionException)`, with no `(Class)` ctor. `super("unsupported analyzed node: " + astNodeClass.getName())` binds to `(String)`; `super(exception)` binds to the copy ctor because `UnsupportedAnalyzedNodeException` IS-A `CommandExecutionException`. Ancestry bottoms out at `RuntimeException` (unchecked), so no checked-exception declaration burden is introduced.

#### C4: Null-tolerance of the substrate helpers (refuted as a finding for this slice)
- **Verdict:** REFUTED — not a defect in the diff. The record canonical constructors do not null-validate, so a caller could build `FuncCall(name, null)` or a tree with a null child; `transformChildren`'s `f.args().size()` / `args.get(i)` and `dispatch`'s `switch (null)` would then NPE. This requires a caller to construct a malformed tree, and this slice ships no producer (S0 has no live consumer; the lowering pass that builds trees lands in Track 3 under invariant I2, which guarantees a complete tree or a throw). The design (D6/D8) treats IR trees as well-formed by construction. `Const(null)` is intentionally valid (a SQL `NULL` literal). No code path in the diff constructs a null-bearing tree, and there is no consumer to mishandle one, so no bug exists at this slice. Forward-looking note only: when Track 3/Track 4 produce and walk trees, the well-formedness contract (non-null args list, non-null children) becomes load-bearing and should be honored by the lowerer rather than re-checked in the substrate.
