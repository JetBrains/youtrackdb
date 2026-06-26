<!-- MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Substrate + framework"
iteration: 1
verdict: PASS
max_severity: should-fix
findings: 2
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: "track-1.md:197,55 (## Context and Orientation; D1 Risks/Caveats) + design.md:248"
    cert: "Premise: first sealed-type use in the codebase"
    basis: psi-findclass+grep
  - id: T2
    sev: suggestion
    anchor: "T2"
    loc: "track-1.md:118-137,279 (D7; ## Interfaces and Dependencies) + design.md:381-405"
    cert: "Premise: UnsupportedAnalyzedNodeException(Class) extends CommandExecutionException"
    basis: psi-findusages
evidence_base:
  premises: 11
  edge_cases: 2
  integrations: 1
  confirmed: 10
  wrong: 1
  partial: 0
  not_found: 0
-->

# Technical review — Track 1: Substrate + framework (iter 1)

Verdict: PASS (max severity should-fix). Track 1 is a pure-greenfield Java substrate; every existing
type it depends on resolves via PSI, every class it introduces is correctly absent today, and the
sealed-interface + records + pattern-`switch` approach compiles against the project's Java 21 release
level with a direct in-repo precedent (`StorageReadResult`). Two findings, neither blocking: one
codebase-fact correction (the "first sealed-type use" claim is false) and one exception-idiom
convention note.

## Findings

### T1 [should-fix]
**Certificate**: Premise — "first sealed-type use in the codebase"
**Location**: `track-1.md:197` (`## Context and Orientation` — "This is the first use of Java 21
sealed types in the codebase"); `track-1.md:55` (D1 Risks/Caveats — "this is the first sealed-type
use in the codebase, so maintainers need a one-paragraph orientation"); mirrored in `design.md:248`
("This is the first sealed-type use in the codebase").
**Issue**: The claim is wrong. PSI/grep over `core/src/main/java` finds six existing `sealed`
types, including `com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult` —
`public sealed interface StorageReadResult permits RawBuffer, RawPageBuffer` with record variants and
a static factory that `switch`es over the sealed set (`StorageReadResult.java:34`). That is
structurally the exact idiom Track 1 proposes (sealed root + record variants + centralized static
dispatch). The others: `MetricScope`, `RecordIdInternal`, `SqlCommandExecutionResult`,
`SemiJoinDescriptor`, `RidFilterDescriptor`. The downstream reasoning ("maintainers need a
one-paragraph orientation" because the idiom is unprecedented) rests on the false premise, and the
track misses a useful precedent the implementer should mirror for naming/style consistency.
**Proposed fix**: In Phase 4 design reconciliation (design is frozen now), correct the claim to
"first sealed-type use in the SQL/query layer" or "the IR follows the existing sealed-interface +
record-variant idiom already used by `StorageReadResult`." For this Phase A, the decomposition should
point the implementer at `StorageReadResult` as the precedent for sealed-root + static-factory style,
and drop the "needs orientation because it is unprecedented" framing — orient against the existing
example instead. No code-behavior change; the substrate design is unaffected.

### T2 [suggestion]
**Certificate**: Premise — `UnsupportedAnalyzedNodeException(Class)` extends
`CommandExecutionException`
**Location**: `track-1.md:118-137` (D7), `track-1.md:225,279` (`## Plan of Work` step 4;
`## Interfaces and Dependencies` — `UnsupportedAnalyzedNodeException(Class)`); `design.md:381-405`.
**Issue**: Every exception in this hierarchy declares a self-type copy constructor —
`CoreException(CoreException)`, `CommandExecutionException(CommandExecutionException)` — and the one
direct subclass in the tree, `CommandExecutorNotFoundException`, forwards through it. The track
specifies only `UnsupportedAnalyzedNodeException(Class)`. This is a convention, not an enforced
contract: PSI find-usages shows the `CommandExecutionException` copy constructor has a single caller
(`CommandExecutorNotFoundException.java:29`), and `BaseException.wrapException` re-wraps via
`initCause`, not reflective copy-ctor instantiation — so a missing copy constructor will compile and
run. The `(Class)` constructor itself is feasible: `CommandExecutionException` is non-final and has a
public `(String)` constructor, so the new type can `super(astNode.getName())` (or a formatted
message). The exception carries the class by rendering it into the message string, matching D7's
intent.
**Proposed fix**: Optional. If the codebase treats the copy constructor as a house convention for
`CoreException` subclasses, the decomposition can have the implementer add a
`UnsupportedAnalyzedNodeException(UnsupportedAnalyzedNodeException)` copy constructor alongside the
`(Class)` one for consistency. Not required for correctness; leave it to the implementer's judgment
against the sibling exception classes.

## Evidence base

#### Premise: greenfield package `core/.../query/analyzed` is absent
- **Track claim**: `## Context and Orientation` — "The target package `core/.../query/analyzed/`
  ... does not exist on develop ... This track is therefore pure greenfield: it adds new files only
  and modifies no existing class."
- **Search performed**: PSI `JavaPsiFacade.findPackage("...core.query.analyzed")` and `findClass`
  for all six new FQNs.
- **Code location**: package present=false, classCount=0; parent package `...core.query`
  present=true (siblings `collection/`, `live/` exist).
- **Actual behavior**: package absent; all six new classes (`AnalyzedExpr`, `AnalyzedExprVisitor`,
  `AnalyzedExprTransform`, `UnsupportedAnalyzedNodeException`, `BinaryOperator`, `UnaryOperator`)
  ABSENT.
- **Verdict**: CONFIRMED — planned by this track.

#### Premise: `CommandExecutionException` exists and is the right parent for D7
- **Track claim**: D7 — "`UnsupportedAnalyzedNodeException extends CommandExecutionException`";
  `## Interfaces and Dependencies` — "`CommandExecutionException`
  (`...core.exception`) — the parent."
- **Search performed**: PSI `findClass` + constructor + modifier inspection.
- **Code location**: `core/.../exception/CommandExecutionException.java:24`.
- **Actual behavior**: `public class CommandExecutionException extends CoreException` (non-final);
  public constructors `(String)`, `(String,String)`, `(DatabaseSessionEmbedded,String)`,
  `(CommandExecutionException)`. A `(Class)` subclass constructor can chain `super(<message>)`.
- **Verdict**: CONFIRMED.

#### Premise: copy-constructor idiom is convention, not enforced contract
- **Track claim**: implicit — D7 specifies only `UnsupportedAnalyzedNodeException(Class)`.
- **Search performed**: PSI find-usages of the `CommandExecutionException(CommandExecutionException)`
  copy constructor; read of `CoreException` / `BaseException`.
- **Code location**: copy ctor used at `CommandExecutorNotFoundException.java:29` only;
  `BaseException.wrapException` (`BaseException.java:36-63`) re-wraps via `initCause`, no reflective
  copy-ctor newInstance.
- **Actual behavior**: copy constructor is a house convention threaded by hand, not a serialization
  contract. A subclass without one compiles and works.
- **Verdict**: CONFIRMED (drives suggestion T2).

#### Premise: "first sealed-type use in the codebase"
- **Track claim**: `## Context and Orientation` line 197 and D1 Risks/Caveats line 55.
- **Search performed**: grep `sealed interface|public sealed|sealed class|sealed abstract` over
  `core/src/main/java`; read of `StorageReadResult.java`.
- **Code location**: 6 matches; `StorageReadResult.java:34`
  (`public sealed interface StorageReadResult permits RawBuffer, RawPageBuffer`) with record variants
  and a static factory `switch`.
- **Actual behavior**: sealed types already exist in `core`; `StorageReadResult` is the same
  sealed-root + record-variant + centralized-dispatch idiom this track proposes.
- **Verdict**: WRONG.
- **Detail**: The premise is false and the precedent it overlooks is the closest analogue to the
  proposed substrate. Drives finding T1.

#### Premise: project compiles at Java 21 (sealed interfaces, records, pattern `switch`)
- **Track claim**: D1/D2 — Java 21 sealed interface with five immutable record variants, exhaustive
  `switch` with no `default`.
- **Search performed**: grep of root `pom.xml` compiler properties.
- **Code location**: `pom.xml:90-92` — `maven.compiler.source/target/release` all `21`.
- **Actual behavior**: release 21; sealed interfaces, records, and pattern-matching `switch` (with
  exhaustiveness over a sealed permits-list and no `default`) are all GA at 21.
- **Verdict**: CONFIRMED.

#### Premise: `SQLMathExpression.Operator` is a real nested enum distinct from the IR's `BinaryOperator`
- **Track claim**: `## Plan of Work` / D1 — the IR's own `BinaryOperator` enum is "distinct from the
  AST's `SQLMathExpression.Operator`."
- **Search performed**: PSI `findClass` + `findInnerClassByName("Operator")`.
- **Code location**: `core/.../sql/parser/SQLMathExpression.java`; nested `Operator` present,
  `isEnum=true`.
- **Actual behavior**: `SQLMathExpression.Operator` is a real nested enum, so the distinction the
  track draws is grounded; the IR introduces its own separate `BinaryOperator`.
- **Verdict**: CONFIRMED.

#### Premise: `SQLNotBlock` exists (D-record / design reference for `UnaryOp(NOT)`)
- **Track claim**: design `Sealed IR` section — "`UnaryOp` exists for `NOT` (the AST's
  `SQLNotBlock`)."
- **Search performed**: PSI `findClass`.
- **Code location**: `core/.../sql/parser/SQLNotBlock.java`, extends `SQLBooleanExpression`.
- **Actual behavior**: present.
- **Verdict**: CONFIRMED.

#### Premise: `SQLModifier` exists (method-call coercion carrier, D4 rationale)
- **Track claim**: D4 — method-call coercion "carried on `SQLModifier.methodCall`."
- **Search performed**: PSI `findClass`.
- **Code location**: `core/.../sql/parser/SQLModifier.java`.
- **Actual behavior**: present.
- **Verdict**: CONFIRMED (member-level `methodCall` not re-verified — out of Track 1 scope; D4 is
  the lowerer's concern in Track 3).

#### Premise: `SQLBaseIdentifier`, `SQLBooleanExpression`, `SQLJson` exist (orientation references)
- **Track claim**: D6 (`SQLBaseIdentifier` raw-AST-interop alternative); `## Context and
  Orientation` (`SQLBooleanExpression` plus 21 subclasses as the incumbent idiom); design `Lowering
  failures` (`SQLJson` as an example unsupported shape).
- **Search performed**: PSI `findClass`.
- **Code location**: all three present under `core/.../sql/parser/`.
- **Actual behavior**: present.
- **Verdict**: CONFIRMED.

#### Premise: no module-info / package-info / SPI registration needed for the new package
- **Track claim**: `## Interfaces and Dependencies` — "No existing class is modified"; pure
  greenfield with no registration step.
- **Search performed**: `find core/src/main -name module-info.java` (none); `find ... query -name
  package-info.java` (none); `ls query/*/` (siblings exist with no package-info).
- **Code location**: no JPMS module, no package-info convention in `query`.
- **Actual behavior**: adding `query/analyzed/` requires no module/SPI registration; the
  no-modification claim holds, and no affected registration component is missed.
- **Verdict**: CONFIRMED.

#### Premise: D6 `Var(List<String> path)` shape is self-contained (no S10 range-table dependency)
- **Track claim**: D6 — `Var` holds an unresolved lexical `List<String>` name path; the type
  references only `java.util.List`/`String`, not the S10 range-table model.
- **Search performed**: read of D6 + design `Sealed IR` variant list; the new package has no
  imports of any resolution/binding type (greenfield, classCount=0).
- **Code location**: planned record `Var(List<String> path)`.
- **Actual behavior**: shape depends only on JDK types; S10 migration risk is bounded to the lowerer
  and evaluator (Tracks 3/4), as D6 states.
- **Verdict**: CONFIRMED — planned by this track.

#### Edge case: adding a sixth variant — compile-time break vs silent default-recurse
- **Trigger**: a future slice adds a sixth `AnalyzedExpr` variant.
- **Code path trace**:
  1. `AnalyzedExpr.dispatch`'s sealed `switch` (no `default`) — fails to compile until the new
     `case` is added (I3, design line 246/300).
  2. every direct `AnalyzedExprVisitor<T>` implementer (evaluator) — fails to compile, no default
     methods (D9).
  3. `AnalyzedExprTransform` — does NOT break: its recurse-into-children defaults make the new
     variant pass-through silently (D9 Risks/Caveats, design line 369-373).
- **Outcome**: compile-time break on the base visitor + dispatcher (intended); silent pass-through
  on transform passes (the one gap I3 does not cover).
- **Track coverage**: yes — D9 Risks/Caveats and the I3 invariant statement both call out the
  transform-pass gap and require a variant-addition audit. Correctly scoped.

#### Edge case: `transformChildren` reference-identity sharing defeated by an `equals`-copy rebuild
- **Trigger**: a transform pass returns an `equals`-but-distinct copy of an unchanged node.
- **Code path trace**:
  1. `transformChildren` compares each returned child to its input by `==` (reference identity, D8).
  2. an `equals`-copy fails the `==` test → counted as "changed" → a new parent record is allocated,
     defeating structural sharing.
- **Outcome**: correctness is preserved (the tree is still valid); only the sharing optimization is
  lost, and the structural-sharing unit test (asserting `assertSame`) catches it.
- **Track coverage**: yes — D8 Risks/Caveats states the author rule ("return the input reference;
  never rebuild an equal copy"), and `## Validation and Acceptance` specifies an `assertSame`-based
  test. Correctly scoped.

#### Integration: substrate has no live consumer in S0
- **Plan claim**: `## Purpose / Big Picture` — "Nothing reads the IR yet; this track ships the
  substrate alone." Tracks 3 (lowering) and 4 (evaluator) consume the types but live in their own
  tracks.
- **Actual entry point**: none in S0 — the package is greenfield and unreferenced.
- **Caller analysis**: PSI confirms the package and all six classes are absent today, so there are
  zero existing callers to break; downstream consumers are introduced by later tracks that depend on
  Track 1 (inter-track deps in `## Interfaces and Dependencies`).
- **Breaking change risk**: none — additive only, no existing class modified.
- **Verdict**: MATCHES.
