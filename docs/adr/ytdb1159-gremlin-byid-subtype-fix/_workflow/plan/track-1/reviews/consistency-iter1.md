<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: CR1, sev: should-fix, loc: "YTDBHasLabelProcessTest.java (working tree, uncommitted)", anchor: "### CR1 ", cert: R10, basis: "the four by-id/has-id test methods the docs call 'committed' are absent at HEAD; they exist only as uncommitted working-tree changes"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 12}
cert_index:
  - {id: R10, verdict: PARTIAL, anchor: "#### R10 "}
flags: [CONTRACT_OK]
-->

## Findings

### CR1 [should-fix]
**Certificate**: R10 (Plan ↔ Code)
**Location**: `implementation-plan.md` (Checklist Track 1 entry, line 131: "alongside the four committed methods"); `design.md` § Test strategy (line 247: "Keep the four committed by-id and has-id methods", line 252: "The committed methods already pin the four corners"); `track-1.md` (line 16 "the four committed methods", line 74 "the four already-committed ones", line 100 "keep the four committed methods", line 135 "the existing class-scan and has-id methods still pass"). Code: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`.
**Issue**: All three documents describe the four by-id / has-id test methods (`testPolymorphicByIdHasLabel`, `testNonPolymorphicByIdHasLabel`, `testPolymorphicHasIdHasLabel`, `testNonPolymorphicHasIdHasLabel`) as already **committed**. They are not committed. At `HEAD` the test class contains only eight methods (`testPolymorphicSimple`, `testPolymorphicWithAdditionalHasLabelFiltering`, `testNonPolymorphicSimple`, `testPolymorphicComplex`, `testPolymorphicWithFilters`, `testPolymorphicMultipleLabels`, `testHasLabelWithGraphStepMidTraversal`, `testCompoundQuery`). The four named methods exist only as an uncommitted +52-line working-tree modification (the `M` in `git status`).
**Evidence**: `git show HEAD:.../YTDBHasLabelProcessTest.java | grep 'public void test'` lists the eight methods above and none of the four by-id/has-id ones. `git diff .../YTDBHasLabelProcessTest.java` shows the four methods as added lines. So the methods physically exist in the working tree the execution agent will see, but the word "committed" is factually wrong against the repo baseline. The plan/design also bundle the `checkSize` helper assertion ("`checkSize` asserts both", invariant on line 107) — that helper *is* committed at HEAD (lines 163-166), so only the four scenario methods are mis-described.
**Proposed fix**: Replace "committed" with "existing" (or "already-written / uncommitted working-tree") wherever the four by-id/has-id methods are described — `implementation-plan.md:131`, `design.md:247`, `design.md:252`, `track-1.md:16`, `track-1.md:74`, `track-1.md:100`. The execution-agent-facing intent (keep these four, add three more) is unchanged; only the baseline-state word is corrected.
**Classification**: mechanical
**Justification**: current-state claim, single unambiguous correct rendering (the methods are present-but-uncommitted, not committed); the fix updates only the description and does not change the plan's goal, scope, or the keep-four-add-three test plan.

## Evidence base

### Design ↔ Code

#### R1 YTDBGraphStep by-id branch
- **Document claim**: `design.md` Overview + Bug 1 + Workflow: the by-id branch of `YTDBGraphStep.elements()` (taken when `this.ids.length > 0`) fetches elements by id and filters them with a single `HasContainer.testAll(element, this.hasContainers)` that ignores the `polymorphic` flag.
- **Search performed**: Read `YTDBGraphStep.java` (grep-based file read).
- **Code location**: `core/.../step/sideeffect/YTDBGraphStep.java:99-103`.
- **Actual signature/role**: `if (this.ids != null && this.ids.length > 0) { return IteratorUtils.filter(getElementsByIds.apply(graph, this.ids), element -> HasContainer.testAll(element, this.hasContainers)); }`. The `polymorphic` field (line 36) is read only in the else/class-scan branch (line 119).
- **Verdict**: MATCHES
- **Detail**: —

#### R2 YTDBGraphStep class-scan branch (untouched, polymorphic via SQL extent)
- **Document claim**: `design.md` Class-scan branch + Workflow: the no-id branch builds a polymorphic `FROM <type>` query via `YTDBGraphQueryBuilder` and post-filters labels only when `!polymorphic`; it is correct and stays untouched.
- **Search performed**: Read `YTDBGraphStep.java`.
- **Code location**: `YTDBGraphStep.java:104-132`; non-polymorphic post-filter at `:119-124`.
- **Actual signature/role**: else-branch builds `YTDBGraphQueryBuilder`, partitions containers into `labelContainers` / `rejectedContainers` via `builder.addCondition(...)`, and applies `HasContainer.testAll(element, labelContainers)` only `if (!polymorphic)`.
- **Verdict**: MATCHES
- **Detail**: —

#### R3 addCondition / ConditionType — basis for the "not the addCondition test" claim
- **Document claim**: `design.md` Bug 1 line 158: the partition key is the `T.label` key test, deliberately NOT the `YTDBGraphQueryBuilder.addCondition(...) == LABEL` test the class-scan branch uses, because `addCondition` classifies only `eq`/`within` label predicates as LABEL and demotes the rest to `NOT_CONVERTED`.
- **Search performed**: grep `addCondition` / `enum ConditionType` in `YTDBGraphQueryBuilder.java`.
- **Code location**: `YTDBGraphQueryBuilder.java:48` (`public ConditionType addCondition(HasContainer)`), `:288-289` (`enum ConditionType { LABEL, PREDICATE, NOT_CONVERTED }`), returns `NOT_CONVERTED` at `:61,:94` and `LABEL` at `:76`. Used by the class-scan branch at `YTDBGraphStep.java:110-113`.
- **Actual signature/role**: enum has three members `LABEL, PREDICATE, NOT_CONVERTED`. The by-id partition the plan proposes uses the `T.label.getAccessor().equals(container.getKey())` key test instead.
- **Verdict**: MATCHES
- **Detail**: — (grep-based; the enum-member set and addCondition return paths were read directly.)

#### R4 YTDBHasLabelStep.filter() polymorphic logic
- **Document claim**: `design.md` Class Design + Polymorphic label match: `YTDBHasLabelStep.filter()` tests the concrete `schemaClass.getName()`, returns false on null schema class, returns false when `!polymorphic`, else walks `getAllSuperClasses()`; predicates OR-combined via `anyMatch`; non-YouTrackDB element falls back to `element.label()`.
- **Search performed**: Read `YTDBHasLabelStep.java`; grep `getAllSuperClasses`.
- **Code location**: `YTDBHasLabelStep.java:42-77`; `getAllSuperClasses` defined at `SchemaImmutableClass.java:361`.
- **Actual signature/role**: `test(String)` = `predicates.stream().anyMatch(p -> p.test(className))`; `filter()` checks `instanceof YTDBElementImpl`, `entity.getSchemaClass()` null-guard returns false, concrete-name test, `if (!polymorphic) return false`, `for (var c : schemaClass.getAllSuperClasses())` loop, else-branch `test(traverser.get().label())`.
- **Verdict**: MATCHES
- **Detail**: —

#### R5 by-id elements are YTDBVertexImpl / YTDBEdgeImpl extending YTDBElementImpl
- **Document claim**: `design.md` Class Design line 100-103: by-id elements come only from `YTDBVertexImpl` / `YTDBEdgeImpl`, both YouTrackDB elements, so the `element.label()` fallback is never reached on the by-id path; the matcher's YTDB-element branch (the `instanceof YTDBElementImpl` check it lifts from `filter()`) always fires.
- **Search performed**: grep `class YTDBVertexImpl|class YTDBEdgeImpl|class YTDBElementImpl`.
- **Code location**: `YTDBVertexImpl.java:22` (`extends YTDBElementImpl`), `YTDBEdgeImpl.java:13` (`extends YTDBElementImpl`), `YTDBElementImpl.java:24` (`abstract class YTDBElementImpl implements YTDBElement`). `YTDBGraphStep.vertices()/edges()` construct `new YTDBVertexImpl(...)` / `new YTDBEdgeImpl(...)` (`YTDBGraphStep.java:59,78`).
- **Actual signature/role**: both impls extend `YTDBElementImpl`, so `instanceof YTDBElementImpl` holds for every by-id element.
- **Verdict**: MATCHES
- **Detail**: —

#### R6 Flow: hasLabel folding into the GraphStep
- **Document claim**: `design.md` Workflow + `hasLabel` folding concept: `YTDBGraphStepStrategy` moves a `hasLabel` directly following a GraphStep into the step's `HasContainer` list (regardless of whether the step has ids), so `g.V(id).hasLabel("Parent")` reaches `elements()` in the by-id branch with the label as a container.
- **Search performed**: Read `YTDBGraphStepStrategy.java`.
- **Trace**:
  1. `apply()` → `rebuildTraversal(traversal, polymorphic)` @ `YTDBGraphStepStrategy.java:38`.
  2. On a `GraphStep`, replaces with `YTDBGraphStep`, sets `isTraversalStart = true`, `setPolymorphic(polymorphic)` @ `:106-118`.
  3. On a following `HasStep` while `isTraversalStart` → `hch.getHasContainers().forEach(currentGraphStep::addHasContainer)` @ `:122-128` — folds the label container into the GraphStep. No id check; applies whether or not the step has ids.
  4. A non-directly-following `hasLabel` instead becomes a `YTDBHasLabelStep` @ `:131-148`, using `T.label.getAccessor().equals(hc.getKey())` and `hc.getPredicate()`.
- **Divergence point**: none.
- **Verdict**: MATCHES
- **Detail**: Confirms the design's claim that the by-id branch ends up owning the folded label, and that `HasContainer.getPredicate()` and `T.label.getAccessor()` (track-1.md signatures) exist and are used here.

#### R7 Bug 2: YTDBGraphCountStrategy label-filter branch lacks the id guard
- **Document claim**: `design.md` Bug 2 + Component Map: the label-filter branch (`hasContainers.size() == 1 && isLabelFilter(...)`) lacks the `getIds().length == 0` guard that the empty-containers sibling branch (`g.V().count()`) has, so `g.V(id).hasLabel(X).count()` matches it and drops the id; the fix adds the guard to the label-filter branch.
- **Search performed**: Read `YTDBGraphCountStrategy.java`.
- **Code location**: `YTDBGraphCountStrategy.java:65` (label-filter branch, no id guard) vs `:68` (empty-containers branch, `hasContainers.isEmpty() && step.getIds().length == 0`).
- **Actual signature/role**: `if (hasContainers.size() == 1 && isLabelFilter(hasContainers.getFirst())) { classes = extractLabels(...); } else if (hasContainers.isEmpty() && step.getIds().length == 0) { ... }`. The label-filter branch indeed omits the id check the sibling has.
- **Verdict**: MATCHES
- **Detail**: —

#### R8 YTDBClassCountStep honors polymorphic flag (Bug 2 is id-drop, not polymorphism)
- **Document claim**: `design.md` Bug 2 line 213 + track-1.md line 68-70: `YTDBClassCountStep` already honors the polymorphic flag through `countClass(cl, polymorphic)`, so Bug 2 is an id-drop, not a polymorphism defect; this class stays unchanged (out of scope).
- **Search performed**: Read `YTDBClassCountStep.java`.
- **Code location**: `YTDBClassCountStep.java:39-43`.
- **Actual signature/role**: `klasses.stream().filter(this::filterClass).mapToLong(cl -> session.countClass(cl, polymorphic)).reduce(0, Long::sum)` — passes the `polymorphic` field to `countClass`.
- **Verdict**: MATCHES
- **Detail**: —

#### R9 Component Map / Class Diagram class & method existence
- **Document claim**: `implementation-plan.md` Component Map + `design.md` classDiagram: `YTDBGraphStep` (`-boolean polymorphic`, `-List~HasContainer~ hasContainers`, `elements(...)`), `YTDBHasLabelStep` (`-List~P~ predicates`, `-boolean polymorphic`, `filter()`), `YTDBGraphCountStrategy` (`apply()`), `YTDBLabelMatcher` (new utility, `matches(element, predicates, polymorphic)$`). All but `YTDBLabelMatcher` are pre-existing.
- **Search performed**: Reads of all four step/strategy files; `YTDBLabelMatcher` is the new target-state class (its absence is expected, not a finding, per spawn note).
- **Code location**: `YTDBGraphStep.java:35-36` (`hasContainers`, `polymorphic` fields), `:90` (`elements(BiFunction, Function)`); `YTDBHasLabelStep.java:20-21` (`predicates`, `polymorphic`), `:47` (`filter(Admin)`); `YTDBGraphCountStrategy.java:43` (`apply(Traversal.Admin)`).
- **Actual signature/role**: all fields/methods present with the described shapes. `elements()` signature is `elements(BiFunction<YTDBGraph,Object[],Iterator> getElementsByIds, Function<Result,ElementType> getElement)` — the design's `elements(getByIds, getElement)` paraphrase matches.
- **Verdict**: MATCHES
- **Detail**: `YTDBLabelMatcher` is the new (target-state) class; not verified against current code by design.

### Plan ↔ Code

#### R10 "four committed methods" test-baseline claim
- **Document claim**: plan/design/track repeatedly call the four by-id/has-id test methods "committed" / "already-committed" (see CR1 for line list).
- **Search performed**: `git show HEAD:.../YTDBHasLabelProcessTest.java | grep 'public void test'`; `git diff .../YTDBHasLabelProcessTest.java`; `git diff --stat`.
- **Code location**: working tree `YTDBHasLabelProcessTest.java:112-161` (the four methods); absent at `HEAD`.
- **Actual signature/role**: at HEAD the class has 8 methods, none of which are the four named by-id/has-id ones; those four exist only as a +52-line uncommitted diff.
- **Verdict**: PARTIAL
- **Detail**: The methods exist in the working tree (so the execution agent can build on them) but are not committed; "committed" is factually wrong against the repo baseline. Feeds CR1.

#### R11 Test suite entry point YTDBProcessTest / YTDBGremlinProcessTests
- **Document claim**: Constraints + Test strategy: scenario tests run only through the `YTDBProcessTest` suite, which sets up the graph provider; the suite registers `YTDBHasLabelProcessTest`.
- **Search performed**: Read `YTDBProcessTest.java`; grep `YTDBHasLabelProcessTest` in `suites/`.
- **Code location**: `gremlintest/YTDBProcessTest.java` (`@RunWith(YTDBProcessSuiteEmbedded.class) @GraphProviderClass(provider = YTDBGraphProvider.class, ...)`); `suites/YTDBGremlinProcessTests.java` imports and registers `YTDBHasLabelProcessTest.class` (import line + class-array entry).
- **Actual signature/role**: `YTDBProcessTest` is the suite entry point with the graph provider; `YTDBHasLabelProcessTest` is in the suite's test class list.
- **Verdict**: MATCHES
- **Detail**: —

#### R12 Invariants vs code
- **Document claim**: `implementation-plan.md` Invariants: (a) by-id branch and `YTDBHasLabelStep` produce identical label-match results for the same element/predicate/flag; (b) non-polymorphic by-id queries match only the concrete label; (c) `g.V(id).hasLabel(X).count()` equals `...toList().size()`.
- **Code evidence**: (a) ASPIRATIONAL — the shared `YTDBLabelMatcher` that makes them identical does not exist yet; today the by-id branch uses exact `HasContainer.testAll` (`YTDBGraphStep.java:103`) while `filter()` walks superclasses (`YTDBHasLabelStep.java:60-71`), so they currently DIFFER on polymorphic by-id. (b) ASPIRATIONAL — depends on the matcher honoring `!polymorphic` (same as filter() lines 63-65). (c) ASPIRATIONAL/VIOLATED today — the count id-drop (`YTDBGraphCountStrategy.java:65`) currently violates it on multi-vertex data.
- **Mechanism**: enforced post-fix by the new matcher + the count guard, both in Track 1.
- **Verdict**: ASPIRATIONAL
- **Detail**: These invariants are correctly tagged in the plan as outcomes the single `[ ]` Track 1 implements (each "tested via …" maps to a planned test method). Track 1 is the implementing track, so no `design-decision` escalation is warranted — recorded as ASPIRATIONAL with an implementing track, not a gap.

### Design ↔ Plan

#### R13 Decision Records ↔ design sections + scope consistency
- **Document claim**: D1 (shared helper), D2 (predicate-list package-neutral utility), D3 (id guard not id-aware count step) each cite "Full design: design.md §…". Scope: "~5 files".
- **Search performed**: Cross-read of `implementation-plan.md` D1-D3 against `design.md` § Class Design and § Bug 2; counted in-scope files in track-1.md.
- **Code location**: n/a (document-to-document).
- **Actual signature/role**: D1/D2 → `design.md` § Class Design (shared `YTDBLabelMatcher`, list signature, package neutrality) — present and consistent. D3 → `design.md` § Bug 2 (id guard, `YTDBClassCountStep` not made id-aware) — present and consistent. The Component Map's four bullets (`YTDBLabelMatcher` new, `YTDBGraphStep`, `YTDBHasLabelStep`, `YTDBGraphCountStrategy`) plus the test class = 5 in-scope files, matching "~5 files" and the design classDiagram's four classes + helper. No design choice in the diagrams lacks a DR.
- **Verdict**: MATCHES
- **Detail**: —
