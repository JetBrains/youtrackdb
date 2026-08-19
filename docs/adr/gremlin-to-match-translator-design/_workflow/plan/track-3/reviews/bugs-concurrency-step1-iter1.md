<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: BG1, sev: suggestion, loc: GremlinStepWalker.java:225, anchor: "### BG1 ", cert: C1, basis: "reserved-prefix scan calls startsWith on a possibly-null step label; masked to a native decline by the strategy's RuntimeException net, so results stay correct"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [suggestion] Reserved-prefix scan dereferences a possibly-null step label

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:225` (method `hasReservedPrefixLabel`, lines 220-231)
- **Issue**: `label.startsWith(RESERVED_ALIAS_PREFIX)` assumes every element of `step.getLabels()` is non-null. A step can carry a null label: `g.V().as((String) null)` reaches `AbstractStep.addLabel(null)`, which runs `labels.add(label)` with no null guard (bytecode-confirmed on the `io.youtrackdb` gremlin-core fork, `gremlin-core-3.8.1-af9db90`). The null then flows into `startsWith`, which throws `NullPointerException`.
- **Evidence**: `walk` calls `hasReservedPrefixLabel(steps)` at line 138 before polymorphism resolution. The helper iterates `step.getLabels()` (a `Set<String>` that permits null) and calls `label.startsWith(...)` at line 225 with no null check. `addLabel`'s bytecode (`getfield labels; aload_1; Set.add; pop; return`) has no `requireNonNull`, so `getLabels()` for a null-labelled step yields a set containing null.
- **Refutation considered**: The NPE does not reach the user. `GremlinToMatchStrategy.apply` runs the walk inside `try { ... } catch (RuntimeException e) { declineOnThrow(...) }` (lines 199-205), so an NPE degrades the traversal to a native decline that returns correct results. That is why this is a suggestion, not a correctness defect. The one observable effect is a translation-opportunity miss: a single-step `g.V().as(null)` that translated under Track 2's size gate now takes the native path. A direct caller outside the safety net — the walker's own unit tests invoke `walk` directly — would see the NPE surface rather than a clean decline, which makes the scan fragile against a future refactor that moves it or calls it directly.
- **Suggestion**: guard the null in the inner loop: `if (label != null && label.startsWith(RESERVED_ALIAS_PREFIX))`. This keeps the scan's decline-not-throw contract intact for the exotic null-label shape.

## Evidence base

#### C1 [CONFIRMED] BG1 — null-label NPE in the reserved-prefix scan
Confirmed: `step.getLabels()` may contain null (`AbstractStep.addLabel` adds without a null guard, bytecode-verified), and `hasReservedPrefixLabel` calls `startsWith` on the element without a guard (line 225), reachable via `as((String) null)`; the strategy's `RuntimeException` net (lines 199-205) masks it to a native decline, so results stay correct and severity is suggestion.

#### C2 [REFUTED] Contract change strands a recogniser relying on the walker's dropped `++`
Claim: the walker dropped its unconditional `ctx.stepIndex++` and moved the advance into recognisers, so any recogniser that still expects the walker to advance would either spin the loop or trip the new cursor guard.
Checked: the production registry is the literal `PRODUCTION_RECOGNISERS = Map.of(GraphStep.class, StartStepRecogniser.INSTANCE)` (line 79-80). Grep over `core/src/main/java` finds `StartStepRecogniser` as the only `StepRecogniser` implementation, and the only production writer of `stepIndex` — it advances at line 177, done last after every context mutation. No stale recogniser depends on the old walker `++`. The `StepRecogniser` Javadoc contract now states the advance obligation explicitly.
Verdict: REFUTED. Reference-accuracy caveat: PSI (mcp-steroid) timed out this session, so this rests on grep, which would miss a recogniser registered from another module; the production registry is a single-entry `Map.of` literal, so the risk is confined to a hypothetical out-of-module registration that does not exist in this diff.

#### C3 [REFUTED] Index-driven loop can spin, overrun, or skip an unvalidated step
Claim: `while (ctx.stepIndex < steps.size())` with a recogniser-driven advance could loop forever (recogniser returns true without advancing), overrun the list, or skip a step the walk never validated.
Checked: after each `recognize` returning true, the walker asserts `ctx.stepIndex > indexBefore && ctx.stepIndex <= steps.size()` (line 177) and, under `-da`, applies the exact negation as a defensive decline `if (ctx.stepIndex <= indexBefore || ctx.stepIndex > steps.size()) return null;` (line 187). A non-advancing recogniser declines; an overrunning one declines. `steps` is captured once at line 129 and the walker never mutates the traversal's step list, so `steps.size()` is stable and `steps.get(indexBefore)` is always in-bounds because the loop condition guarantees `indexBefore < steps.size()`.
Verdict: REFUTED.

#### C4 [REFUTED] Removing the size gate lets a multi-step traversal mis-translate or mutate on decline
Claim: with `MAX_RECOGNISED_STEPS` gone, a two-plus-step traversal (`g.V().V()`, `g.V().count()`) could partially translate or leave the native step list mutated after decline.
Checked: `StartStepRecogniser.recognize` rejects any step at `ctx.stepIndex != 0` (line 111), so a second `GraphStep` declines; a step class with no registry entry declines at the walker's `recogniser == null` check (line 167). On any decline the walker returns null and the per-call `WalkerContext` — the only state mutated during the walk — is discarded; `traversal.getSteps()` is never written. No-mutation-on-decline holds with respect to the traversal, matching the smoke and walker tests (`followUpStepDeclinesUnrecognizedStep`, `walk_multiStepTraversal_declinesAtUnrecognizedFollowUpStep`).
Verdict: REFUTED.
