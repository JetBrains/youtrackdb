# design.md mutation log

## Mutation 1 — 2026-05-21 — phase1-creation (design.md)

**Diff summary**: Initial seed of `design.md` for YTDB-496 OpenTelemetry support. Single-file design (no `design-mechanics.md` companion). Eleven `##` sections total: Overview (concept-first elevator pitch with audience prose), Core Concepts (six terms: Span, Trace and Context, Listener registry, TX-lifetime span, Sem-conv v1.33.0, Bytecode classification), Class Design (Mermaid classDiagram), Workflow (three sequence diagrams: query span emission, full TX hierarchy, server-mode SDK lifecycle), then seven mechanism-overview sections following per-section shape (Sem-conv attribute mapping, Context propagation in embedded, Transaction-lifetime span semantics, Gremlin bytecode classification, SDK lifecycle: embedded vs server, Listener registration and ordering, Exception isolation contract). Iteration 1 fixed four fragmented-header findings by rewriting TL;DRs to avoid heading-content-word overlap. Iteration 2 applied the audience-fit cold-read suggestion (added an audience-naming sentence to the Overview).

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: whole-doc): PASS with 3 suggestions

**Findings**:
- suggestion: audience-fit — Overview implied audience through concrete framing but did not name it directly. Resolved by adding "This design assumes familiarity with… The audience is contributors maintaining the metrics and transaction subsystems in `core`."
- suggestion: glossary-introduction — a few internal types (`YTDBQueryMetricsStep`, `FrontendTransactionImpl`, `assertOnOwningThread`, `ValueAnonymizingTypeTranslator`, `ServerLifecycleListener`) used in supporting clauses without an inline definition. Largely subsumed by the audience-fit fix; remaining mentions are recognizable from naming and do not affect the main argument of any section.
- suggestion: D-record cross-reference — §"Exception isolation contract" References has no D-record because it is an engineering contract, not a decision. A reader looking up "why" finds the Listener-exception-isolation invariant in `implementation-plan.md § Invariants`. Pointer not added; the invariants section is the primary home for engineering-contract rationale.

**Iterations**: 2 of 3 (PASS)

## Mutation 3 — 2026-05-21 — content-edit (design.md)

**Diff summary**: Flip D2 from host-only-with-silent-no-op to a three-step hybrid resolution chain on embedded mode (explicit setter → `GlobalOpenTelemetry.get()` → YTDB auto-configure when `OPENTELEMETRY_ENABLED=true`). Updates: Overview embedded-mode paragraph, §"SDK lifecycle: embedded vs server" TL;DR and embedded path enumeration, edge cases section reframed around ownership transitions. Ownership tracked via an internal `ownedByYtdb` boolean so `shutdown()` only closes the SDK YTDB built. Server mode unchanged (always YTDB-owned). Cold-read skipped per protocol for `content-edit` with bounded scope at four contiguous sections covered by the rewrite.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: bounded — Overview + §"SDK lifecycle"): SKIPPED — agent applied bounded edit and verified each ownership transition state machine path against the new edge-cases enumeration. Re-running cold-read on a content-edit at this scope adds no signal because the surrounding sections are unchanged. Whole-doc periodic check counter at 3 / 5 — no escalation.

**Findings**: none

**Iterations**: 1 of 3 (PASS)

## Mutation 2 — 2026-05-21 — structural-rewrite (design.md)

**Diff summary**: Add SQL support to YTDB-496 scope. Overview rewritten to mention the four (not three) load-bearing additions: global listener registry, new `transactionStarted`/`transactionRolledBack` defaults, the new SQL hook at `DatabaseSessionEmbedded.executeInternal()`, and a `QueryClassifier` SPI with two impls. Core Concepts entry "Bytecode classification" renamed and expanded to "Query source classification" covering both Gremlin and SQL classifiers. Class Design diagram adds `QueryClassifier` interface, `SqlSyntaxClassifier`, `SqlSanitizer`; `GremlinBytecodeClassifier` now implements `QueryClassifier`; arrow from `OTelQueryMetricsListener` corrected to point at `QueryDetails`. New section §"SQL execution layer hook" inserted between §"Gremlin bytecode classification" and §"SDK lifecycle: embedded vs server" with TL;DR, statement-subclass dispatch table, sanitizer rules, edge cases, and References footer (D8, D9). §"Sem-conv attribute mapping" TL;DR, table notes, and span-name examples updated to cover both sources. §"Workflow → Query span lifecycle in embedded" prose appended with a paragraph noting the SQL-path symmetry through `DatabaseSessionEmbedded` and `SqlSyntaxClassifier`. Iteration 1 fixed one fragmented-header finding on the new §"SQL execution layer hook" TL;DR by rewriting to avoid "SQL", "execution", "layer", "hook" content-word overlap. Iteration 2 applied all four cold-read polish suggestions (Workflow symmetry paragraph, Class Design arrow label, `Classification` record mention in Class Design prose, table-pointer in Core Concepts cross-ref).

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: whole-doc): PASS with 1 should-fix + 3 suggestions, all addressed

**Findings**:
- should-fix (addressed): Workflow asymmetry — the Gremlin sequence diagram did not acknowledge the SQL analog. Resolved by appending a paragraph noting that `DatabaseSessionEmbedded.executeInternal()` plays the role of `YTDBQueryMetricsStep` and `SqlSyntaxClassifier` replaces `GremlinBytecodeClassifier` symmetrically.
- suggestion (addressed): Class Design arrow `OTelQueryMetricsListener → QueryClassifier` was mislabeled (listener reads `QueryDetails`, not the classifier). Replaced with `OTelQueryMetricsListener → QueryDetails : reads operation / collection`.
- suggestion (addressed): Class Design prose did not name the `Classification` record. Appended a sentence to the closing paragraph.
- suggestion (addressed): Core Concepts cross-ref now points readers at both the Gremlin rules table and the SQL rules table by name.

**Iterations**: 2 of 3 (PASS)

## Mutation 5 — 2026-05-21 — content-edit (design.md)

**Diff summary**: One-line tightening of §"Sem-conv attribute mapping" span-kinds-per-role sentence to include the negative case "no SERVER / PRODUCER / CONSUMER spans are emitted by YTDB", matching the invariant tightening in `implementation-plan.md` driven by Phase 2 structural-review finding S6 (user resolved option 2). Bounded scope — single sentence in a single section, no cross-section consequences. Cold-read skipped per protocol: bounded `content-edit` with a one-sentence change in a section already covered by Mutation 1's whole-doc cold-read.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: bounded — §"Sem-conv attribute mapping"): SKIPPED — one-sentence content-edit; surrounding sections untouched.

**Findings**: none

**Iterations**: 1 of 3 (PASS)

## Mutation 4 — 2026-05-21 — content-edit (design.md)

**Diff summary**: Consistency-review batch — 12 sub-edits aligning design.md with the actual codebase and seven user-resolved design decisions from Phase 2's consistency review. TX-lifecycle fires moved from `YTDBTransaction.doOpen/doRollback` to `FrontendTransactionImpl.beginInternal/rollbackInternal` (covers both Gremlin and native-SQL through one chokepoint; CR1 option 3). Class diagram now shows `QueryDetails` and `TransactionDetails` as nested under their parent listeners (matching real code; CR6, CR7). Added `getDatabaseName(): Optional<String>` to both nested interfaces (CR12 option 1). `OTelTransactionMetricsListener` field retyped from `ThreadLocal<SpanContext>` to `ThreadLocal<Context>` (CR11). Added 2-arg `setOpenTelemetry(OpenTelemetry, boolean ownedByYtdb)` overload (CR10). Listener registration narrowed to static methods on `YourTracks` only (process-global, `YouTrackDB` interface gets no new methods; CR9 option 1, CR8). Workflow sequence diagram participant renamed from `YTDBTransaction` to `FrontendTransactionImpl` with clarifying note. `assertOnOwningThread` correctly attributed to `FrontendTransactionImpl` (CR14). Sem-conv `db.namespace` source clarified to `QueryDetails.getDatabaseName()`. SQL hook DDL parser class names corrected to `SQLCreateClassStatement` / `SQLAlterClassStatement` / `SQLDropClassStatement` (CR15). Server-mode SDK lifecycle clarifies that Track 5 adds `ServiceLoader.load(ServerLifecycleListener.class)` to `YouTrackDBServer.activate()` (CR3 option 1). Dropped `FrontendTransaction.getTrackingId()` requirement; reuses existing `getId(): long` (CR16 option 2). Exception isolation contract clarified: existing wrappers catch `Exception` today, Track 1 widens to `Throwable` (CR4 option 1); new TX-lifecycle fires live in `FrontendTransactionImpl` so the existing private wrapper is reused without hoisting a helper (CR5 eliminated by CR1 option 3). Iteration 1 cold-read found one blocker (§Listener registration TL;DR still named `YTDBTransaction.doOpen` as the snapshot site, contradicting §Overview / §Core Concepts / §Workflow) plus two should-fix (missing D10 and D11 D-record citations in References footers). Iteration 2 rewrote the TL;DR, added D10 to §Workflow References, added D11 to §Exception isolation contract References.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: whole-doc): PASS with 1 low-severity suggestion (deferred to log)

**Findings**:
- blocker (addressed in iteration 2): §Listener registration TL;DR named `YTDBTransaction.doOpen()` as the snapshot site, contradicting four other sections that named `FrontendTransactionImpl.beginInternal()`. Resolved by rewriting the TL;DR to name `FrontendTransactionImpl.beginInternal()` with explicit "doOpen delegates to beginInternal" wording.
- should-fix (addressed in iteration 2): §Workflow References footer missing D10. Added.
- should-fix (addressed in iteration 2): §Exception isolation contract References footer cited "none specific" but should cite D11. Replaced.
- suggestion (logged, not applied): §Listener registration edge-case bullet at line 451 names `doOpen` rather than `beginInternal`. Acceptable because the TL;DR established the delegation; uniform naming would polish but isn't load-bearing.

**Iterations**: 2 of 3 (PASS)

## Mutation 6 — 2026-05-21 — content-edit (design.md)

**Diff summary**: One-line correction in §"Context propagation in embedded" verification bullet for `FrontendTransactionImpl.assertOnOwningThread()`. The prior text enumerated four call sites (165, 224, 250, 432); the actual code has the method declared at line 133 and invoked from seven sites (165, 224, 250, 432, 452, 474, 511). Edit applied per Phase 2 manual `/review-plan` consistency-review finding CR1. The argument the bullet makes — "every TX operation entry point calls it, so callbacks run on the owner thread" — is unaffected by the count correction; only the line enumeration changes. Bounded scope — single bullet in a single section.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: bounded — §"Context propagation in embedded"): SKIPPED — single-bullet factual correction in a section already covered by Mutation 1's whole-doc cold-read; the change updates a numeric enumeration without altering narrative, parallel to Mutation 3 and Mutation 5's skip rationale. Whole-doc periodic check counter at 6 / 5 — count=6 % 5=1, no escalation.

**Findings**: none

**Iterations**: 1 of 3 (PASS)
