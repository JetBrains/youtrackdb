<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 3, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: "track-7.md:23,32,50 (## Decision Log / Purpose / Plan of Work item 1)", anchor: "### T1 ", cert: C8, basis: "Abstract-superclass and composed-row-projector are framed as interchangeable carriers of projection+shaping+lifecycle, but a projector cannot own the single-stream State machine / clone-isolation (Step-instance state); the 'without exposing mutable lifecycle state' preference compounds the ambiguity and risks under-scoping the base so Track 8 reimplements the lifecycle."}
  - {id: T2, sev: should-fix, loc: "track-7.md:34,52 (## Context / Plan of Work item 3); YTDBMatchPlanStep.java:272,312", anchor: "### T2 ", cert: C7, basis: "The ordered post-process application point must be a single shared stage reachable from BOTH the per-row loop and the group-barrier emitAccumulatedGroupMap (which returns directly, bypassing projectOrSkip); hooking only into the row loop reproduces pre-split A5 and silently no-ops Track 9 ops after group/groupCount."}
  - {id: T3, sev: should-fix, loc: "track-7.md:51,78 (## Plan of Work item 2 / ## Interfaces); GremlinToMatchTranslator.java:74, GremlinStepWalker.java:375", anchor: "### T3 ", cert: C4, basis: "'TranslationResult producers rewired only if the chosen base shape requires it' mis-attributes the trigger: the base shape is internal to the step and never touches TranslationResult/buildResult; the producers get rewired only if the ordered carrier is an adjacent immutable type rather than a new ResultShaping field."}
  - {id: T4, sev: suggestion, loc: "track-7.md:77-78 (## Interfaces In scope)", anchor: "### T4 ", cert: C3, basis: "Existing direct-construction test sites (YTDBMatchPlanStepTest, GremlinToMatchStrategyTest) are not enumerated; pin that the base extraction preserves YTDBMatchPlanStep's two public ctors so the behavior-neutral suites stay green unmodified."}
evidence_base: {section: "## Evidence base", certs: 10, matches: 4}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: PARTIAL,   anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7,  verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8,  verdict: PARTIAL,   anchor: "#### C8 "}
  - {id: C9,  verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

# Track 7 technical review — iteration 1 (post-split)

No blocker. The 2026-07-27 split already resolved the pre-split blocker (`MultiPlanMatchStep extends YTDBMatchPlanStep` cannot compile): this Track 7 extracts a shared boundary base from the `final` `YTDBMatchPlanStep` and keeps that class the single-plan concrete form, which is feasible against the current code. Three should-fixes sharpen the decomposition before it lands, all about how the base and the ordered carrier are framed rather than whether they can be built; one suggestion pins the behavior-neutral test surface. All four load-bearing premises from the spawn are CONFIRMED against source.

**Tooling note.** mcp-steroid was reachable and the project (`design.md`, rooted at the youtrackdb working tree) was open, but `steroid_execute_code` timed out on every call this session — including a trivial `project.basePath` warmup — so the kotlinc compile cycle, not any single PSI query, exceeds the ~60s MCP HTTP limit. This reproduces the exact condition all three pre-split review sessions hit. PSI find-class / find-usages was therefore unavailable after one retry. Evidence below is direct `Read` of source and `grep`/`find`. Every existence and class-shape claim is a declaration read (exact, no reference-accuracy exposure). The one reference-accuracy claim — the sole construction site (C3) — is mitigated by enumerating every textual occurrence of `YTDBMatchPlanStep` in `core/src/main` and confirming no reflective / factory construction; its residual grep caveat is recorded on C3.

## Findings

### T1 [should-fix]
**Certificate**: C8 (Premise — base-shape carrier feasibility), supported by C1
**Location**: `track-7.md` `## Purpose / Big Picture` (line 32), `## Plan of Work` item 1 (line 50), `## Decision Log` base-shape bullet (line 23). Source: `YTDBMatchPlanStep.java:88,106,127,138-164,340-517`.
**Issue**: The track presents "abstract superclass" and "composed row-projector" as interchangeable carriers of the same three responsibilities — row projection, `ResultShaping` read, and "the `ExecutionStream` open/drain/close lifecycle" (Purpose line 32; Plan-of-Work item 1 line 50 says "move ... the `ExecutionStream` open/drain/close lifecycle into an abstract superclass (or a composed row-projector)"). The two shapes are not interchangeable for that set. The lifecycle is Step-instance mutable state: the private `State` machine (`YTDBMatchPlanStep.java:138-164`), the `openStream` / `armingGraph` fields, `plan.reset` / `plan.close`, and the clone-isolation `clone()` that deep-copies the plan against an isolated child `BasicCommandContext` (`:485-517`, with the documented parent-context invariant at `:494-503`). A composed *row-projector* by role owns immutable projection config (`boundaryAlias`, `outputType`, `returnClass`, `shaping`, `presenceKeySet`) and per-row projection — it cannot own the stream lifecycle or the clone-isolation point. So the composed-projector option shares only projection+shaping and leaves each step to own its lifecycle; the abstract-superclass option can additionally share the single-stream lifecycle through protected template hooks. The `## Decision Log` bullet compounds the ambiguity: it names "the `ExecutionStream` lifecycle" as a reuse driver but then prefers "the smallest surface that lets both boundary steps share projection + shaping **without exposing mutable lifecycle state**" — which biases toward the projector, i.e. toward *not* sharing the lifecycle. D8 (revised) and pre-split C10/T5 established that Track 8's `MultiPlanMatchStep` must reuse exactly the single-stream lifecycle + close-all + clone-isolation semantics; if decomposition picks the projector reading, Track 8 reimplements all of it and re-opens the clone-isolation risk the split was meant to retire.
**Proposed fix**: In decomposition, state plainly that the base shares projection + `ResultShaping` read for certain, and decide (or pin a criterion for) whether the single-stream lifecycle is *also* shared — noting that `MultiPlanMatchStep`'s lifecycle genuinely differs (a `List` of plans, one live stream at a time), so sharing it means an abstract base with a `protected` "advance to next stream" hook plus base-owned `reset`/`close`/`clone`, not a stateless projector. Reconcile the `## Decision Log` "without exposing mutable lifecycle state" clause with the Purpose/Plan-of-Work claim that the lifecycle is in the base (encapsulate the mutable fields behind protected hooks rather than exclude the lifecycle from the base).

### T2 [should-fix]
**Certificate**: C7 (Edge — two boundary emission paths)
**Location**: `track-7.md` `## Context and Orientation` (line 34), `## Plan of Work` item 3 (line 52). Source: `YTDBMatchPlanStep.java:260-305` (per-row loop → `projectOrSkip`), `:272-273` + `:311-327` (group-barrier `emitAccumulatedGroupMap`).
**Issue**: The track wires the ordered post-process carrier to be "read by the boundary base's projection" (item 3) and "wired through the boundary base's projection" (line 34). The boundary has two distinct emission paths, not one. `processNextStart` returns through the group barrier `emitAccumulatedGroupMap(ctx)` at `:272-273` **before** the per-row `while` loop that calls `projectOrSkip` — the group/`groupCount` accumulate path (`:311-327`) builds its map and generates a traverser directly, never touching `projectOrSkip`. Track 7's own acceptance ("no-op when the op list is empty") passes regardless of where the application hook lands, because the op list is always empty today — so a mis-placed framework is invisible to this track and only detonates in Track 9. If Track 7 anchors the ordered-op application only in the per-row projection path, Track 9's real ops silently no-op after a group/aggregate terminator (`groupCount().unfold()`), which pre-split A5 flagged and which the plan's own scope allows list-shapers to follow. This is a foundation-placement decision Track 7 owns.
**Proposed fix**: Decomposition should specify the ordered post-process as a single shared application stage invoked on **every** emission path — the per-row loop, the group-barrier `emitAccumulatedGroupMap`, and (reachable for) the future `LIST` / multi-plan consumers — rather than a hook inside `projectOrSkip` or the row loop alone. Even with only placeholder ops, add a unit test that drives the ordered stage on both the per-row and the accumulate-map paths so the placement is pinned before Track 9 registers real ops.

### T3 [should-fix]
**Certificate**: C4 (Premise/Integration — TranslationResult producers)
**Location**: `track-7.md` `## Plan of Work` item 2 (line 51) and `## Interfaces and Dependencies` "In scope (modified)" (line 78). Source: `GremlinToMatchTranslator.java:74-100` (`TranslationResult` record), `GremlinStepWalker.java:375-416` (`buildResult`), `GremlinToMatchStrategy.java:432-447` (splice).
**Issue**: Both item 2 and the Interfaces line gate the `GremlinToMatchTranslator` / `GremlinStepWalker` rewire on "only if the chosen **base shape** requires it." The base shape does not drive that rewire. `TranslationResult` is a data record (`GremlinToMatchTranslator.TranslationResult`, `:74`) and the strategy constructs `YTDBMatchPlanStep` from it at the splice site (`:437`); whether the base is an abstract superclass or a composed projector is internal to the step and never changes the record or `buildResult`. What *does* force a producer rewire is the other decomposition choice — the ordered-carrier representation. If the carrier is a new field on `ResultShaping`, `buildResult` already passes `ctx.shaping()` through unchanged (`GremlinStepWalker.java:415`) and neither `TranslationResult` nor `buildResult` changes. If the carrier is an adjacent immutable type, then `WalkerContext` needs a new field, `buildResult` must read it, `TranslationResult` gains a component, and the boundary ctor gains a parameter. The stated condition attaches the rewire to the wrong decision, so a decomposer choosing the adjacent-type carrier could read "base shape doesn't require it" and leave the carrier stranded in `WalkerContext`, never reaching the step.
**Proposed fix**: Re-point the condition: the `TranslationResult` producers are rewired iff the ordered carrier is an adjacent immutable type (not a new `ResultShaping` field); the base-shape choice never requires touching them. State this in item 2 and the Interfaces line.

### T4 [suggestion]
**Certificate**: C3 (Integration — construction sites)
**Location**: `track-7.md` `## Interfaces and Dependencies` "In scope" (lines 77-78).
**Issue**: The in-scope surface lists the production files but omits the existing tests that construct `YTDBMatchPlanStep` directly and drive its lifecycle: `YTDBMatchPlanStepTest` (constructs at `:262,329,345,863`) and `GremlinToMatchStrategyTest` (`:174`). Because Track 7 keeps `YTDBMatchPlanStep` a concrete class with its two public constructors (5-arg → 7-arg, `YTDBMatchPlanStep.java:177,200`), these tests should stay green unmodified — which is precisely the behavior-neutral acceptance signal the track relies on. Making the constructor-preservation explicit prevents a decomposition that moves the constructors onto the base (or changes their signature) from silently breaking the guard suites the track cites.
**Proposed fix**: Add the two test classes to the behavior-neutral acceptance surface and pin that the base extraction preserves `YTDBMatchPlanStep`'s two public constructor signatures, so the existing direct-construction tests pass with no assertion changes.

## Evidence base

#### C1 Premise: `YTDBMatchPlanStep` class shape (final, private lifecycle, private projectOrSkip, single ResultShaping field)
- **Track claim**: `## Context and Orientation` / `## Interfaces` Signatures — "`public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractStep<S, E> implements AutoCloseable`", private lifecycle (`plan` field, lazily-started `ExecutionStream`, private `State` enum), private `projectOrSkip` switch reading a single `ResultShaping` field.
- **Search performed**: `Read` of `YTDBMatchPlanStep.java` (full); `grep` for the class declaration. PSI find-class unavailable (compile-cycle timeout).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java:88`
- **Actual behavior**: `public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractStep<S, E> implements AutoCloseable` (`:88`). Private `plan` (non-final, `:106`, with a JMM rationale for non-final so `clone()` can install its own copy), private `State` enum (`:138-162`) and `state` field (`:164`), private `openStream` / `armingGraph` (`:127,130`), single `private final ResultShaping shaping` (`:119`). Private lifecycle: `openArming` (`:340`), `releaseStream` (`:397`), `releaseStreamAndClosePlan` (`:412`), `close` (`:465`), `clone` (`:485`). `private Object projectOrSkip(Result row)` is an exhaustive arrow `switch (outputType)` over four cases with no `default` (`:523-530`). Two public ctors: 5-arg (`:177`) delegating to 7-arg (`:200`).
- **Verdict**: CONFIRMED
- **Detail**: Exactly as the track and the spawn's load-bearing audit state. The `final` + all-private-machinery shape is why the pre-split "extends" premise failed; the split's shared-base approach is the correct response.

#### C2 Premise: `ResultShaping` is a 7-component immutable record with `withX` builders + `NONE`
- **Track claim**: `## Interfaces` Signatures — "`ResultShaping` (7-flag immutable record with `withX` builders + `NONE`)".
- **Search performed**: `Read` of `ResultShaping.java` (full).
- **Code location**: `core/src/main/java/.../translator/step/ResultShaping.java:31-93`
- **Actual behavior**: `public record ResultShaping(boolean dropNullRows, boolean dropOnAbsent, @Nonnull List<String> presencePropertyKeys, boolean wrapMapValuesInLists, boolean accumulateMap, boolean unwrapSingletonMap, boolean elementMapTokens)` — seven components (six booleans + one `List<String>`). `public static final ResultShaping NONE` (`:44`), a compact ctor doing `List.copyOf` (`:48`), and seven `withX` builders (`:52-92`), each reconstructing the full record.
- **Verdict**: CONFIRMED
- **Detail**: "7-flag" is loose (one component is a list, not a flag) but the count and shape are correct. Extending it for the ordered carrier is routine — it touches `NONE`, the compact ctor, all seven `withX`, and both `YTDBMatchPlanStep` ctors (the 5-arg delegates with `NONE`, still compiles). This is the seam T3's "new field on `ResultShaping`" option uses.

#### C3 Premise/Integration: sole production construction site of `YTDBMatchPlanStep`
- **Track claim**: `## Context and Orientation` / Plan-of-Work item 2 — "`GremlinToMatchStrategy` (`replaceAllStepsWithBoundary`) is the sole site that constructs `YTDBMatchPlanStep`."
- **Search performed**: `grep -rn 'new YTDBMatchPlanStep'` over `core/src`; `grep -rn 'YTDBMatchPlanStep'` over `core/src/main` (every occurrence); reflective-construction check (`YTDBMatchPlanStep.class` / `newInstance` / `getConstructor`). PSI find-usages unavailable (compile-cycle timeout).
- **Code location**: `GremlinToMatchStrategy.java:437` (inside `replaceAllStepsWithBoundary`, `:432`)
- **Actual behavior**: The only `new YTDBMatchPlanStep(` in `core/src/main` is `GremlinToMatchStrategy.java:437`. Every other main-source occurrence is non-constructing: the import (`:8`), an idempotency `instanceof YTDBMatchPlanStep<?,?>` scan (`:343`), comments (`:241`, and `YTDBGraphStepStrategy.java:35`), the class/ctor/error-string/`clone` declarations in the class itself. No reflective or factory construction anywhere. Test construction sites: `GremlinToMatchStrategyTest.java:174`, `YTDBMatchPlanStepTest.java:262,329,345,863`.
- **Verdict**: CONFIRMED
- **Detail**: Reference-accuracy caveat (grep, not PSI): grep would miss a `new` split across a line break or a construction via a star-imported factory, but the full textual enumeration of `YTDBMatchPlanStep` in main source shows one `new` site plus non-constructing references only, and no reflective path, which bounds the risk. The production claim holds; the *tests* also construct the class directly, which T4 folds into the behavior-neutral surface.

#### C4 Premise/Integration: `TranslationResult` producers and what forces their rewire
- **Track claim**: Plan-of-Work item 2 / Interfaces — rewire `GremlinToMatchTranslator` and `GremlinStepWalker.buildResult` "only if the chosen base shape requires it."
- **Search performed**: `grep` for `TranslationResult` / `buildResult`; `Read` of `GremlinToMatchTranslator.java` (full), `GremlinStepWalker.buildResult`, and the strategy splice path.
- **Code location**: `GremlinToMatchTranslator.java:74-100` (`record TranslationResult`); `GremlinStepWalker.java:375-416` (`buildResult`); `GremlinToMatchStrategy.java:432-447` (`replaceAllStepsWithBoundary`)
- **Actual behavior**: `TranslationResult` is a nested record on `GremlinToMatchTranslator` carrying `inputs`, `boundaryAlias`, `outputType`, `returnClass`, `inputParameters`, `cacheEligible`, `shaping`. `buildResult` constructs it from the `WalkerContext`, passing `ctx.shaping()` (`:415`). The strategy builds `YTDBMatchPlanStep` from the record fields at `:436-444`. The base-shape choice (abstract superclass vs composed projector) is internal to the step and does not appear in the record or `buildResult`.
- **Verdict**: PARTIAL
- **Detail**: The producers exist as described, but the track's rewire condition is mis-attributed. The rewire is driven by the ordered-carrier representation, not the base shape: a new `ResultShaping` field flows through `ctx.shaping()` untouched (no producer change); an adjacent immutable type forces `WalkerContext` + `buildResult` + `TranslationResult` + boundary-ctor changes. → T3.

#### C5 Premise: `MultiPlanMatchStep` does not yet exist (planned by Track 8)
- **Track claim**: the base is what "Track 8's `MultiPlanMatchStep` extends"; `MultiPlanMatchStep` is out of scope here.
- **Search performed**: `find` for `MultiPlanMatchStep.java`; `grep -rn 'MultiPlanMatchStep' --include=*.java`.
- **Code location**: NOT FOUND (no Java source)
- **Actual behavior**: No `MultiPlanMatchStep` in any Java source (only `_workflow/` planning docs). Confirmed as a Track 8 planned class.
- **Verdict**: CONFIRMED
- **Detail**: Correctly out of Track 7's scope; the track names it only as the downstream consumer of the base.

#### C6 Premise: `BoundaryOutputType` has four constants; `LIST` is Track 9
- **Track claim**: `## Plan of Work` item 4 / Validation — behavior-neutral over "element / MAP / SINGLE_VALUE / SCALAR paths"; `BoundaryOutputType.LIST` is out of scope (Track 9).
- **Search performed**: `Read` of `BoundaryOutputType.java`; `projectOrSkip` switch cases.
- **Code location**: `BoundaryOutputType.java`; `YTDBMatchPlanStep.java:524-529`
- **Actual behavior**: The `projectOrSkip` switch covers exactly `ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR` — four cases, no `LIST`. Matches the four paths the track names.
- **Verdict**: CONFIRMED
- **Detail**: The pre-split A8 "five upstream output types" residue is gone from this track file; the four-path framing is accurate. `LIST` (and the exhaustive-switch break it causes) is correctly deferred to Track 9.

#### C7 Edge: two boundary emission paths — per-row `projectOrSkip` and the group-barrier bypass
- **Trigger**: A future ordered op (Track 9) after a `group()` / `groupCount()` terminator, e.g. `groupCount().unfold()`.
- **Code path trace**:
  1. `processNextStart()` @ `YTDBMatchPlanStep.java:260`
  2. `if (shaping.accumulateMap()) return emitAccumulatedGroupMap(ctx);` @ `:272-273` — returns before the per-row loop
  3. `emitAccumulatedGroupMap` @ `:311-327` — drains all rows into one map and `generate(...)`s a traverser directly; never calls `projectOrSkip`
  4. the ordinary path @ `:275-286` — per-row `while` loop calling `projectOrSkip(openStream.next(ctx))`
- **Outcome**: An application hook placed inside `projectOrSkip` or the per-row loop is unreachable on the group-barrier path. Track 7 stays green (empty op list = no-op everywhere), so the mis-placement surfaces only when Track 9 registers real ops.
- **Track coverage**: no — the track wires the carrier to "the boundary base's projection" without distinguishing the two emission paths. → T2.

#### C8 Premise: abstract-superclass vs composed-row-projector are not interchangeable carriers of the lifecycle
- **Track claim**: Purpose / Plan-of-Work item 1 — projection + `ResultShaping` read + "the `ExecutionStream` open/drain/close lifecycle" move "into an abstract superclass (or a composed row-projector)", the two treated as equivalent options.
- **Search performed**: `Read` of `YTDBMatchPlanStep.java` lifecycle members and `clone()`.
- **Code location**: `YTDBMatchPlanStep.java:106,127,130,138-164,340-517`
- **Actual behavior**: The lifecycle is Step-instance mutable state: the `State` machine, `openStream`/`armingGraph`, `plan.reset`/`plan.close`, and the clone-isolation `clone()` that deep-copies the plan against an isolated child `BasicCommandContext` with a documented parent-context invariant (`:494-503`). A row-projector by role owns immutable projection config + per-row projection; it cannot own stream lifecycle or the per-execution clone-isolation point.
- **Verdict**: PARTIAL
- **Detail**: The projection+shaping half moves cleanly into either shape; the lifecycle half does not move into a projector. So the two options differ in what they share (projector → projection only, Track 8 reimplements lifecycle; abstract base → lifecycle shared via protected hooks), and the track's "either carrier holds all three" framing plus the "without exposing mutable lifecycle state" preference need reconciliation against what D8/pre-split C10 say Track 8 must reuse. → T1.

#### C9 Premise: an ordered `List` carrier obviates the `tailLimit` sentinel problem
- **Track claim**: `## Decision Log` — the carrier is "an ordered `List` of list-shaping ops applied in declared order"; order-less booleans cannot encode `reverse().unfold()` vs `unfold().reverse()`.
- **Search performed**: `Read` of the track `## Decision Log`; cross-read of pre-split adversarial A2/A5 and technical T3.
- **Code location**: track-7.md:24,34; design rationale in pre-split reviews
- **Actual behavior**: A `List<PostProcessOp>` where a `TAIL` op carries its own `n` represents declared order directly, and "no tail" is simply the absence of a `TAIL` op — so the `int tailLimit` sentinel hazard the pre-split T3/A5 flagged (`0` is a legal `tail` arg, so `0` cannot mean "unset") does not arise. `reverse().unfold()` and `unfold().reverse()` become two distinct lists, which the track's acceptance line unit-tests.
- **Verdict**: CONFIRMED
- **Detail**: The ordered-List decision is the correct resolution of pre-split A2 and simultaneously retires the A5 sentinel concern. No finding; recorded as a positive premise the decomposition should hold to (do not collapse the list back into booleans + an int).

#### C10 Premise: the D7 idempotency scan stays valid under Track 7's base extraction
- **Track claim**: implicit — Track 7 is behavior-neutral and does not touch strategy gating; D7 broadening to the base is Track 8's.
- **Search performed**: `grep` for `instanceof YTDBMatchPlanStep`; `Read` of D7 in the plan.
- **Code location**: `GremlinToMatchStrategy.java:343` (`if (step instanceof YTDBMatchPlanStep<?, ?>)`); plan D7 (implemented-in note)
- **Actual behavior**: The idempotency scan keys on `instanceof YTDBMatchPlanStep`. Track 7 keeps `YTDBMatchPlanStep` the concrete constructed type (C1, C3), so the scan still detects the boundary after Track 7. D7 explicitly defers broadening the scan to the boundary base to Track 8 ("broadened to the boundary base in Track 8 when `MultiPlanMatchStep` lands").
- **Verdict**: CONFIRMED
- **Detail**: No Track 7 action needed; the base extraction is transparent to the D7 scan. Recorded so decomposition does not prematurely broaden the scan (a Track 8 concern) or worry it breaks.
