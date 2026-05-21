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
