<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: R1, sev: should-fix, loc: "track-1.md:53,196; design.md:248", anchor: "### R1 ", cert: A4, basis: "D1/design claim 'first sealed-type use in the codebase' is CONTRADICTED — >=6 existing sealed types incl. a sealed-interface-with-records analog; the rationale rests on a false premise"}
  - {id: R2, sev: suggestion, loc: "track-1.md:225,279; design.md:142", anchor: "### R2 ", cert: A2, basis: "UnsupportedAnalyzedNodeException(Class) needs an own constructor building the message; CommandExecutionException has no (Class) ctor (only String/copy/session)"}
  - {id: R3, sev: suggestion, loc: "track-1.md:243-253", anchor: "### R3 ", cert: TST1, basis: "transformChildren FuncCall lazy-copy + reference-identity branches need explicit per-branch test rows to hit 70% branch on the helper"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: E1, verdict: LOW, anchor: "#### E1 "}
  - {id: E2, verdict: LOW, anchor: "#### E2 "}
  - {id: A1, verdict: VALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: VALIDATED, anchor: "#### A2 "}
  - {id: A3, verdict: VALIDATED, anchor: "#### A3 "}
  - {id: A4, verdict: CONTRADICTED, anchor: "#### A4 "}
  - {id: TST1, verdict: ACHIEVABLE, anchor: "#### TST1 "}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: Assumption A4 (CONTRADICTED — "first sealed-type use in the codebase")
**Location**: `track-1.md:53` (D1 Risks/Caveats), `track-1.md:196-202` (Context and Orientation), `design.md:248` (Part 1, "This is the first sealed-type use in the codebase").
**Issue**: Both the track's D1 decision record and the design overview assert that `AnalyzedExpr` is the first sealed-type use in the codebase. PSI/grep contradicts this: `core/src/main/java` already holds at least six sealed declarations — `RecordIdInternal`, `StorageReadResult`, `MetricScope`, `RidFilterDescriptor`, `SemiJoinDescriptor`, and `SqlCommandExecutionResult`. The last is a near-identical analog: a `sealed interface` permitting `record` variants (`record Unit()`, `record Results(...)`), and `StorageReadResult` is a sealed interface carrying a `switch` over its variants — the exact `AnalyzedExpr` idiom. This is a feasibility-positive correction, not a blocker: the idiom is already established and compiles cleanly on the project's Java 21 release level, which de-risks the I3 exhaustive-`switch` mechanism. But the D1 risk rationale ("first sealed-type use, so maintainers need a one-paragraph orientation") rests on a false premise — the orientation already exists in those classes, and a reviewer who trusts the claim will look for a precedent that the track says is absent. Likelihood the false claim survives into the frozen design: high (it is stated three times). Impact: low on code, medium on the decision record's credibility and on the "first use" justification for the orientation paragraph.
**Proposed fix**: Decision-record content is frozen during execution, so do not edit D1's text in this track. Record the contradiction as a Phase-4 design-final reconciliation item: reword the "first sealed-type use" claim to "first sealed-type use in the SQL/query layer" (if that narrower scope holds — `StorageReadResult` is in `storage`, `SqlCommandExecutionResult` in `gremlin/sqlcommand`, neither in `query` or `sql/parser`) or to "follows the existing sealed-interface-with-records idiom established by `SqlCommandExecutionResult` and `StorageReadResult`." The orientation paragraph D1 calls for can then point maintainers at those precedents rather than presenting the idiom as unprecedented. No step-level action required beyond noting it in the track's Surprises log when the substrate lands.

### R2 [suggestion]
**Certificate**: Assumption A2 (VALIDATED — `CommandExecutionException` present; constructor shapes enumerated)
**Location**: `track-1.md:225` (Plan of Work step 4), `track-1.md:279` (Interfaces), `design.md:142-144` (class diagram `UnsupportedAnalyzedNodeException(Class)`).
**Issue**: The track and design specify the new exception's public surface as `UnsupportedAnalyzedNodeException(Class)` — a constructor taking the unsupported AST node's class. PSI shows `CommandExecutionException`'s own constructors are `(String)`, `(String, String)`, `(DatabaseSessionEmbedded, String)`, and the copy constructor `(CommandExecutionException)`; none takes a `Class`. So the new subclass's `(Class)` constructor must itself build the diagnostic message string (e.g. `"unsupported analyzed node: " + astClass.getName()`) and call `super(message)`. This is routine and the design's intent is clear (it says "carrying the unsupported AST class name gives an actionable diagnostic"), so the risk is only that an implementer wires `super(astClass)` expecting an inherited `Class` overload that does not exist — a compile error caught immediately, not a latent bug. Likelihood: low. Impact: trivial (one compile-fix iteration at most).
**Proposed fix**: No track change needed. The substrate step that adds the exception should pick the message-building form explicitly: store the `Class` (or its name) as a field if S1+ wants programmatic access to the offending shape, and pass a rendered message to `super(String)`. Worth a one-line note in the step's implementation that the parent has no `(Class)` constructor.

### R3 [suggestion]
**Certificate**: Testability TST1 (ACHIEVABLE — DB-free record tests exist in `core/.../query/`; `assertSame` already used)
**Location**: `track-1.md:243-253` (Validation and Acceptance — dispatch exhaustiveness + structural sharing).
**Issue**: The substrate's coverage gate (85% line / 70% branch on the new dispatch + transform machinery) is achievable, but the branch-coverage target lands almost entirely on one method: `transformChildren`. Its per-variant logic carries the non-trivial branches — leaf-returns-self, compound recurse-with-all-children-unchanged (return parent by reference), compound recurse-with-one-child-changed (build new parent), and `FuncCall`'s lazy argument-list copy that allocates only on the first changed element (`design.md:351-353`). A test that only asserts "a transform that changes one subtree shares the rest" exercises a subset of these branches and can pass at well under 70% branch on the helper. The `dispatch` switch is fully covered by the exhaustiveness test (one case per variant), so the branch risk is concentrated in `transformChildren` and `FuncCall`'s lazy copy. Likelihood of a coverage miss if the test is written to the two acceptance bullets verbatim: medium. Impact: a coverage-gate failure at Phase C, not a correctness risk.
**Proposed fix**: When decomposition writes the test step, enumerate the `transformChildren` branch cases as explicit test rows rather than the two summary bullets: (a) leaf variant returned by reference; (b) compound variant, no child changed → parent returned by reference (`assertSame`); (c) compound variant, one child changed → new parent, unchanged sibling shared by reference; (d) `FuncCall` with no arg changed → same arg list / same node; (e) `FuncCall` with a middle arg changed → new list, leading args shared. `assertSame` is the right assertion and is already used for identity checks in the sibling `QueryRuntimeValueMultiTest`. This is a decomposition-time note, not a track-file edit.

## Evidence base

#### E1 Exposure: greenfield package, no existing class modified
- **Track claim**: Track 1 adds new files only under `core/.../query/analyzed/` and modifies no existing class; the package is absent on develop (`track-1.md:191-195`, `track-1.md:288-290`).
- **Critical path trace**: There is no critical-path entry to trace. The substrate defines value types (`AnalyzedExpr` + 5 records), two static helpers (`dispatch`, `transformChildren`), two visitor interfaces, and one exception. None is wired into storage, WAL, transactions, indexes, cache, or any executor step in S0 — confirmed by the design's "no live executor consumer" framing (`design.md:14-17`) and the track's "Nothing reads the IR yet" (`track-1.md:7-8`).
- **Blast radius**: Zero on existing behavior. A bug in `dispatch` or `transformChildren` is observable only through the substrate's own unit test, because S0 has no producer or consumer of IR trees. The lowerer (Track 3) and evaluator (Track 4) are the first readers and live in later tracks.
- **Existing safeguards**: PSI `findPackage("com.jetbrains.youtrackdb.internal.core.query.analyzed")` returns null on develop (greenfield confirmed); the parent `query` package exists with subpackages `collection`, `live`. The compiler is the primary safeguard for the I3 exhaustiveness mechanism (sealed `switch`, no `default`).
- **Existing safeguards (tool)**: PSI `JavaPsiFacade.findPackage` / `findClass`, mcp-steroid reachable, project `analyzed-expression` open and matching the working tree.
- **Residual risk**: LOW. The track touches no critical path; a defect cannot reach production in S0.

#### E2 Exposure: new subclass of CommandExecutionException
- **Track claim**: `UnsupportedAnalyzedNodeException extends CommandExecutionException` so lowering failures surface as ordinary SQL execution-time errors (D7, `track-1.md:118-137`).
- **Critical path trace**: The exception type is defined in this track but thrown only in Track 3 (the lowerer). In S0 it is an unthrown, unreferenced new type. No catch site in existing code keys on `UnsupportedAnalyzedNodeException` (it does not exist yet), and adding a subclass cannot change how existing `catch (CommandExecutionException)` sites behave — they already catch the parent.
- **Blast radius**: Zero. PSI shows `CommandExecutionException` has exactly one existing subclass (`CommandExecutorNotFoundException`); adding a second sibling does not alter the hierarchy's existing members or their callers.
- **Existing safeguards**: `CommandExecutionException` confirmed present at `com.jetbrains.youtrackdb.internal.core.exception` via PSI `findClass`. `ClassInheritorsSearch` returned 1 transitive subclass — a minimal hierarchy.
- **Residual risk**: LOW. Pure additive type with no throw site in this track.

#### A1 Assumption: Java 21, sealed + records + pattern-matching switch are standard (no preview)
- **Track claim**: A Java 21 sealed interface permitting record variants with an exhaustive `switch` enforces I3 at compile time (`track-1.md:38-56`, `design.md:244-248`).
- **Evidence search**: Grep over `pom.xml` for compiler release/preview settings.
- **Code evidence**: `pom.xml:90-92` set `maven.compiler.source/target/release = 21`; no `--enable-preview` or `<enablePreview>` anywhere. Sealed classes (Java 17), records (Java 16), and pattern-matching `switch` (Java 21 final) are all standard features at release 21.
- **Verdict**: VALIDATED. The I3 compile-time-exhaustiveness mechanism rests on stable language features, not a preview flag the build might reject.

#### A2 Assumption: CommandExecutionException exists at the stated FQN; constructor shapes
- **Track claim**: `CommandExecutionException` (`com.jetbrains.youtrackdb.internal.core.exception`) is the established base for SQL execution-time errors and the parent of the new exception (D7, `track-1.md:125-127`, `track-1.md:288-290`).
- **Evidence search**: PSI `JavaPsiFacade.findClass(...)` + constructor enumeration + `ClassInheritorsSearch`.
- **Code evidence**: Class present at the exact FQN. Constructors: `(String)`, `(String, String)`, `(DatabaseSessionEmbedded, String)`, and a copy constructor `(CommandExecutionException)`. One existing subclass (`CommandExecutorNotFoundException`).
- **Verdict**: VALIDATED. Caveat surfaced as R2: no `(Class)` constructor on the parent, so the new subclass must build its own message and call `super(String)`.

#### A3 Assumption: target package and its parent are well-formed for a new greenfield package
- **Track claim**: `core/.../query/analyzed/` does not exist on develop, confirmed absent via PSI (`track-1.md:191-195`).
- **Evidence search**: PSI `findPackage` on the analyzed package and its parent `query` package.
- **Code evidence**: `analyzed` package present = false. `query` package present = true, with subpackages `collection`, `live` and self-contained classes (`Result`, `ResultSet`, `BasicResult`, …). Adding an `analyzed` subpackage is a clean addition under an existing parent.
- **Verdict**: VALIDATED.

#### A4 Assumption: "first sealed-type use in the codebase"
- **Track claim**: `AnalyzedExpr` is the first sealed-type use in the codebase, so maintainers need a one-paragraph orientation (D1 Risks/Caveats `track-1.md:53`; Context and Orientation `track-1.md:196-202`; `design.md:248`).
- **Evidence search**: Grep `sealed interface|sealed class` over `core/src/main/java`; PSI/file inspection of the matches.
- **Code evidence**: At least six existing sealed declarations: `RecordIdInternal`, `StorageReadResult`, `MetricScope`, `RidFilterDescriptor`, `SemiJoinDescriptor`, `SqlCommandExecutionResult`. `SqlCommandExecutionResult` is a `sealed interface` permitting `record` variants (`record Unit() implements ...`, `record Results(CloseableIterator<Object>) implements ...`) — the same sealed-interface-with-records shape as `AnalyzedExpr`. `StorageReadResult` is a `sealed interface ... permits RawBuffer, RawPageBuffer` carrying a `switch`.
- **Verdict**: CONTRADICTED. Produces R1. The contradiction is feasibility-positive (precedent exists, idiom proven on Java 21) but the stated rationale is wrong.

#### TST1 Testability: substrate unit test (dispatch exhaustiveness + structural sharing)
- **Coverage target**: 85% line / 70% branch on the new dispatch + transform machinery.
- **Difficulty assessment**: The substrate is pure data + static helpers with no database dependency. `dispatch` is fully covered by one test visitor returning a per-variant marker (one `switch` case per variant). The branch-coverage weight falls on `transformChildren`: leaf-return-self, compound recurse-no-change (return-by-reference), compound recurse-one-change (rebuild), and `FuncCall`'s lazy first-changed-element list copy. A test written to the two summary acceptance bullets verbatim can under-cover those branches — see R3.
- **Existing test infrastructure**: DB-free unit tests already live in `core/src/test/java/.../query/` — `ResultDefaultMethodsTest`, `BasicResultSetDefaultMethodsTest`, `QueryRuntimeValueMultiTest`. JUnit 4 (`org.junit.Test`, `org.junit.Assert`). `Assert.assertSame` is already used for reference-identity assertions in `QueryRuntimeValueMultiTest:99,110` — the exact assertion the D8 structural-sharing test needs. No `TestUtilsFixture`/DB fixture is required for record tests.
- **Feasibility**: ACHIEVABLE. The coverage target is reachable with the branch enumeration in R3; the assertion style and test home already exist for an identical pattern.
