# YTDB-496 OpenTelemetry support

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Expose YouTrackDB query and transaction telemetry through OpenTelemetry so that hosts running embedded YTDB, and operators running standalone YTDB servers, see database calls as spans in their trace viewers (Jaeger, Tempo, Datadog, etc.). The telemetry follows OTel semantic conventions v1.33.0 for database client spans so it lights up in DB-aware tooling without per-vendor adapters. The integration ships in a new optional Maven module `youtrackdb-opentelemetry`; `core` and `server` carry no OTel dependency.

Both Gremlin traversals and native SQL queries emit spans. A Gremlin query emits one CLIENT span with sanitized `db.query.text` produced by the existing `ValueAnonymizingTypeTranslator`, plus `db.operation.name` and `db.collection.name` extracted from the bytecode. A native SQL query (`db.command("SELECT ...")`, MATCH, DDL) emits one CLIENT span with the raw SQL text sanitized into placeholders, plus the operation type and target class parsed from the statement AST. Both share the same `db.system.name=youtrackdb` and attach to the host's active trace context (`Context.current()`). Transactions get their own INTERNAL parent span covering `begin → close`, with the existing commit metrics listener emitting a child CLIENT span for the commit operation; the TX span carries the existing tracking ID.

In embedded mode the SDK resolution chain is: host-provided via `YouTrackDBOpenTelemetry.setOpenTelemetry(...)`, then `GlobalOpenTelemetry.get()` if the host configured the global, then YTDB-built from `OPENTELEMETRY_*` config when neither of the first two yielded a real SDK. The flag is never inert: enabling `OPENTELEMETRY_ENABLED=true` always produces telemetry. In server mode YTDB always owns the SDK because the server is a standalone process.

### Constraints

- **One-way dependency**: `youtrackdb-opentelemetry` depends on `core` for the listener SPI; `core` MUST NOT pull OTel libraries in transitively. YTDB without the OTel module continues to have zero OTel runtime cost.
- **Sem-conv v1.33.0 compliance**: stable semantic conventions for database spans cover attribute names, requirement levels, and sanitization rules. Custom `db.system.name = "youtrackdb"` per §"Notes" of the spec.
- **Host-preferred SDK ownership in embedded**: a host that wires its own `OpenTelemetry` (via setter or `GlobalOpenTelemetry.set(...)`) wins. When no host SDK is found and `OPENTELEMETRY_ENABLED=true`, YTDB auto-configures its own SDK from `OPENTELEMETRY_*` config entries so the flag is never inert. Ownership is tracked so YTDB only closes the SDK it created. In server mode YTDB always owns the SDK because the server is a standalone process.
- **No backward-compat scaffolding**: greenfield emission, ignore `OTEL_SEMCONV_STABILITY_OPT_IN` env var (introduced for instrumentations that already emit a previous version).
- **Listener exception isolation**: callbacks run synchronously on the caller thread; existing try/catch wrapping in the listener firing sites MUST extend to the new lifecycle hooks so a misconfigured OTel SDK never breaks transaction flow.
- **JDK 21+, Maven Wrapper, Spotless on new module**, JUnit 5 tests. The new module is greenfield, no JUnit 4 inertia to preserve.
- **Coverage gate**: 85% line / 70% branch on changed code per CLAUDE.md.

### Architecture Notes

#### Component Map

```mermaid
flowchart TB
    subgraph core["core module"]
        QML["QueryMetricsListener<br/>(existing SPI)"]
        TML["TransactionMetricsListener<br/>(extended: +transactionStarted,<br/>+transactionRolledBack)<br/>nested: TransactionDetails<br/>(+ getDatabaseName)"]
        QD["QueryMetricsListener.QueryDetails<br/>(extended: +getOperationName,<br/>+getCollectionName, +getDatabaseName)"]
        YOU["YourTracks<br/>(+ static global listener registry)"]
        TX["FrontendTransactionImpl<br/>(+ TX lifecycle fires in<br/>beginInternal / rollbackInternal)"]
        STEP["YTDBQueryMetricsStep<br/>(Gremlin source; calls Gremlin classifier)"]
        STRAT["YTDBQueryMetricsStrategy<br/>(gate widened so globally-registered<br/>listeners cause injection)"]
        DSE["DatabaseSessionEmbedded<br/>(SQL source; new listener fire site;<br/>calls SQL classifier)"]
        GCLS["GremlinBytecodeClassifier<br/>(static utility; new)"]
        SCLS["SqlSyntaxClassifier<br/>(static utility; new)"]
        CLR["Classification<br/>(value record; new)"]
        GC["GlobalConfiguration<br/>(+ OPENTELEMETRY_* entries)"]
    end

    subgraph server["server module"]
        SLL["ServerLifecycleListener<br/>(existing; new impl registered)"]
    end

    subgraph otel["youtrackdb-opentelemetry (NEW MODULE)"]
        OTQ["OTelQueryMetricsListener"]
        OTT["OTelTransactionMetricsListener"]
        FAC["YouTrackDBOpenTelemetry<br/>(facade + lifecycle)"]
        SQLS["SqlSanitizer"]
    end

    OTQ -.implements.-> QML
    OTT -.implements.-> TML
    OTQ --> FAC
    OTT --> FAC
    OTQ --> QD
    FAC -- "registers listeners with" --> YOU
    SLL -- "starts / stops" --> FAC
    STEP --> GCLS
    STRAT -- "injects" --> STEP
    DSE --> SCLS
    DSE --> SQLS
    GCLS --> CLR
    SCLS --> CLR
    GC -. read by .-> FAC
```

- **`QueryMetricsListener` / `TransactionMetricsListener` (core, existing SPI)**: the listener contracts. Their `QueryDetails` and `TransactionDetails` types are nested interfaces inside the respective listener interfaces; the plan qualifies them as `QueryMetricsListener.QueryDetails` and `TransactionMetricsListener.TransactionDetails` on first mention. `TransactionMetricsListener` gains two new default no-op methods (`transactionStarted`, `transactionRolledBack`) so existing implementations keep compiling.
- **`QueryMetricsListener.QueryDetails` (core, existing; extended)**: gains `getOperationName()`, `getCollectionName()`, and `getDatabaseName()` returning `Optional<String>`. Populated by both query sources: `YTDBQueryMetricsStep` for Gremlin via the bytecode classifier (operation/collection) and `session.getDatabaseName()` (namespace); `DatabaseSessionEmbedded` for SQL via the syntax classifier (operation/collection) and the session's own database name (namespace).
- **`YourTracks` (core)**: gains static methods `registerGlobalQueryListener` / `unregisterGlobalQueryListener` / `registerGlobalTransactionListener` / `unregisterGlobalTransactionListener`. The registry is process-global (a static holder in `core/.../profiler/monitoring/`); the transaction factory consults the snapshot at `FrontendTransactionImpl.beginInternal()` time and uses that snapshot for the TX's lifetime. Per-TX `withQueryListener` continues to add listeners on top of the snapshot. The `YouTrackDB` interface gets no new methods, keeping `YouTrackDBRemote` and other implementors untouched.
- **`FrontendTransactionImpl` (core)**: `beginInternal()` / `rollbackInternal()` become the chokepoints for `transactionStarted` / `transactionRolledBack` fires and capture both the global-listener snapshot and the per-TX `QueryMonitoringMode` snapshot. Two new accessors on the `FrontendTransaction` interface: `getQueryMonitoringMode()` exposes the snapshotted mode to Track 4's SQL hook, and `iterateAllQueryListeners(): Iterable<QueryMetricsListener>` exposes the merged view (global snapshot + per-TX list added via `withQueryListener`) to both fire paths so per-TX listeners fire for SQL statements too. `YTDBTransaction.doOpen()` / `doRollback()` are not touched — they delegate to the underlying impl, so Gremlin and SQL paths both go through the same fire sites. See design.md §"Transaction-lifetime span semantics" and §"SQL execution layer hook" for the snapshot-immutability rule, the setter call path, the lifecycle-fire iteration, the `txStartCounter == 0` gating, and the `notifyMetricsListener` wrapper reuse.
- **`YTDBQueryMetricsStep` (core)**: classifies the traversal bytecode by calling the new `GremlinBytecodeClassifier.classify(Bytecode)` static utility (also in `core`) and exposes the result through the enriched `QueryDetails` to the listener callback. Existing fire site, augmented.
- **`YTDBQueryMetricsStrategy` (core)**: TinkerPop strategy that injects `YTDBQueryMetricsStep` into Gremlin traversals. Today the gate routes only on the per-TX listener; Track 1 widens it so a non-empty global query-listener snapshot also causes injection. Without this edit, a host that registers only the OTel listener via the global registry would see no Gremlin spans because the step never gets injected.
- **`DatabaseSessionEmbedded` (core)**: SQL execution layer; carries two parallel call sites — `query()` (lines 617-686) backing `db.query(...)` and the Gremlin bridge, plus `executeInternal()` (lines 702-751) backing `command()` / `execute()`. Track 4 extracts a private helper `executeStatementWithMetrics(SQLStatement, String, Object)` invoked from both call sites; the helper wraps `statement.execute(...)` with a mode-aware timer (reads `currentTx.getQueryMonitoringMode()`; routes to `GranularTicker` for `LIGHTWEIGHT` or `System.nanoTime()` for `EXACT`), short-circuits when `GremlinSqlSuppression.isActive()` is true (so Gremlin-driven SQL does not emit nested children), and emits a `QueryDetails` carrying raw SQL, sanitized text, operation name, and target collection populated from `SqlSyntaxClassifier.classify(SQLStatement)` (a new static utility in `core`, called on the AST `SQLEngine.parse(...)` already produces at each call site). Covers SELECT / INSERT / UPDATE / DELETE / MATCH / DDL through one helper.
- **`YTDBGraphQuery` (core)**: Gremlin-to-SQL bridge whose `execute(session)` (line 23) and `explain(session)` (line 31) each run a SQL query via `session.getActiveTransaction().query(...)`. Track 4 wraps BOTH calls in try-with-resources `GremlinSqlSuppression.activate()` tokens so the SQL helper short-circuits for the duration of Gremlin-driven SQL (the explain wire prevents parasitic SQL spans on Gremlin's `YTDBGraphStep.usedIndexes` introspection path). Counter is re-entrant so nested Gremlin steps inside one another do not interfere.
- **`GremlinSqlSuppression` (core, new)**: process-global static utility holding a ThreadLocal re-entrant counter plus an `AutoCloseable` activation token. Lives in `core/.../profiler/monitoring/` alongside the listener SPI. Exposed methods: `activate(): AutoCloseable` (increments the counter; the returned token's `close()` decrements it) and `isActive(): boolean` (counter > 0). Consulted by the SQL helper before firing the listener; activated by `YTDBGraphQuery.execute`.
- **`GremlinBytecodeClassifier` / `SqlSyntaxClassifier` / `Classification` (core, new)**: two static-utility classes plus a shared value record. Each classifier reads its source-specific input (`Bytecode` for Gremlin, `SQLStatement` for SQL) and returns a `Classification(operationName, collectionName)` value the fire site copies into the `QueryDetails` accessors. Called directly — no SPI, no ServiceLoader.
- **`GlobalConfiguration` (core)**: new entries `OPENTELEMETRY_ENABLED`, `OPENTELEMETRY_EXPORTER_ENDPOINT`, `OPENTELEMETRY_EXPORTER_PROTOCOL`, `OPENTELEMETRY_SERVICE_NAME` for the server-mode SDK init.
- **`OTelQueryMetricsListener` / `OTelTransactionMetricsListener` (new module)**: translate listener callbacks into OTel spans, taking the parent from `Context.current()` so embedded propagation is automatic.
- **`YouTrackDBOpenTelemetry` (new module)**: static facade. `setOpenTelemetry(OpenTelemetry)` for explicit host wiring; falls back to `GlobalOpenTelemetry.get()`. Registers the listeners with the global registry. Idempotent shutdown.
- **`SqlSanitizer` (new module)**: replaces string / numeric / date literals in raw SQL with `?` placeholders for `db.query.text` sanitization. Parameterized queries pass through unchanged. The only classifier-adjacent helper that stays in the OTel module — its output (`db.query.text`) is OTel-specific.

#### D1: Global listener registry

- **Alternatives considered**: keep per-TX `withQueryListener` only (config flag would be inert); auto-injection inside the `g.tx()` factory (chosen alternative for D1 → see Rationale below).
- **Rationale**: a `OPENTELEMETRY_ENABLED=true` flag must actually take effect without the host wiring every transaction by hand. A small global registry in `OYouTrackDB` consulted by the transaction factory is the least invasive way to deliver auto-enrolment while keeping per-TX override semantics intact. Factory-side auto-injection would couple the registry to the Gremlin entry point only and miss any direct `YTDBTransaction` construction.
- **Risks/Caveats**: registration order matters if multiple listeners coexist. The registry is a List preserving insertion order, and the transaction copies the current snapshot at begin time so mid-TX registrations don't take effect.
- **Implemented in**: Track 1 (registry SPI), Track 5 (OTel listener registration).

#### D2: Hybrid SDK ownership — host preferred, YTDB falls back to self-built

- **Alternatives considered**: YTDB always owns SDK in embedded (conflicts with host instrumentation when host has its own SDK); host-only in embedded with silent no-op fallback (sharp edge — operator enables flag, sees nothing, has no signal what went wrong); always-host in both modes (impossible in server mode where YTDB IS the process).
- **Rationale**: in embedded mode the resolution chain on first listener fire is (1) value passed to `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)` if any, (2) `GlobalOpenTelemetry.get()` if it returns a non-no-op SDK, (3) lazy auto-configure from `OPENTELEMETRY_*` config entries via OTel autoconfigure (same path as server mode). Host-provided wins when present so we never duplicate a host's existing SDK; self-built fills the gap so `OPENTELEMETRY_ENABLED=true` always produces telemetry, regardless of whether the host has its own OTel setup. In server mode YTDB always owns the SDK because there is no host to provide one. The facade tracks ownership via an internal boolean so `shutdown()` closes the SDK only when YTDB created it.
- **Risks/Caveats**: a host that sets `OPENTELEMETRY_ENABLED=true` and forgets to wire its OTel sees YTDB silently open a network connection to the configured endpoint (default `http://localhost:4317`). The flag is an explicit opt-in so this is acceptable, and an INFO log records the situation. If the host later calls `setOpenTelemetry(...)` after self-built ran, the facade closes the YTDB-built SDK and switches to the host's instance.
- **Implemented in**: Track 5.
- **Full design**: design.md §"SDK lifecycle: embedded vs server"

#### D3: Full span hierarchy with TX as parent over query and commit

- **Alternatives considered**: query-only spans (loses TX context in viewer); query+commit only without TX parent (commit span "floats" in the trace).
- **Rationale**: a trace viewer's value is showing "this request did X, Y, Z" with timing. Without a TX span as parent, multiple queries inside one TX render as disjoint database calls; with it, the user sees `TX 850ms { query 47ms, query 600ms, commit 50ms }` which matches the operator's mental model. Requires extending `TransactionMetricsListener` with `transactionStarted` and `transactionRolledBack` defaults.
- **Risks/Caveats**: TX spans for read-only transactions get no commit child (no `writeTransactionCommitted` fires). The TX span still closes cleanly because `transactionRolledBack` covers user-initiated rollback (including the silent rollback at end of a read-only TX `close()`).
- **Implemented in**: Track 1 (listener API extension), Track 3 (OTelTransactionMetricsListener).
- **Full design**: design.md §"Transaction-lifetime span semantics"

#### D4: Automatic OTel Context propagation via `Context.current()`

- **Alternatives considered**: explicit `withContext(Context)` per query; pass parent context through `QueryDetails`.
- **Rationale**: the existing `QueryMetricsListener` callback fires synchronously on the caller thread. `YTDBQueryMetricsStep.close()` calls the listener directly, and `assertOnOwningThread` enforces TX operations stay on the owner thread. `Context.current()` therefore returns the host's active span. No additional plumbing.
- **Risks/Caveats**: if a future change moves traversal close to a worker pool, propagation breaks silently. Mitigated by a test that runs a host-context span around a YTDB query and asserts the YTDB query span's parent matches; the test fails loudly if threading changes.
- **Implemented in**: Track 3 (listener implementations) and Track 6 (propagation test).
- **Full design**: design.md §"Context propagation in embedded"

#### D5: Span kinds by role

- **Alternatives considered**: all CLIENT (commit aligns but TX is not a single call); all INTERNAL (loses "database edge" in service maps); mode-aware (CLIENT server, INTERNAL embedded; adds branching for no benefit).
- **Rationale**: sem-conv v1.33.0 defaults DB spans to CLIENT and allows INTERNAL for in-memory DBs. The query and commit are database calls; the TX span is a logical container, not a call, so INTERNAL fits. Picking CLIENT for query in both modes keeps service maps consistent and trace viewers labeling YTDB as a database edge. Query span is CLIENT, TX span is INTERNAL, commit span is CLIENT.
- **Risks/Caveats**: none significant; the choice is observable in viewer styling only.
- **Implemented in**: Track 3.

#### D6: `db.system.name = "youtrackdb"` (custom value)

- **Alternatives considered**: `other_sql` (loses identity; YTDB is not SQL-only).
- **Rationale**: sem-conv §"Notes" mandates the lowercase DBMS name as a custom value when not on the well-known list. `"youtrackdb"` is unambiguous. Future PR to add it to the well-known list is a separate concern.
- **Risks/Caveats**: backends may not recognize the system name for built-in dashboards until it's registered upstream.
- **Implemented in**: Track 3.

#### D7: Delegate sampling to the OTel sampler

- **Alternatives considered**: built-in `queryThresholdNanos` filter (proposed in the YouTrack draft); per-query AlwaysOn.
- **Rationale**: emitting a span and then letting the host's sampler decide is the standard OTel pattern. Built-in threshold filtering is a worse heuristic (a 1 ms failed query carries more signal than a 500 ms scan returning a million rows) and reinvents what OTel SDK already provides (`TraceIdRatioBased`, `ParentBased`, `AlwaysOn`).
- **Risks/Caveats**: under heavy benchmark load (LDBC) the unfiltered span volume can overwhelm an unsampled exporter. The host must configure a sampler. Documented in the embedded section.
- **Implemented in**: Track 3.

#### D8: SQL execution layer hook in `DatabaseSessionEmbedded.executeStatementWithMetrics` helper, called from both `query()` and `executeInternal()`

- **Alternatives considered**: per-statement-type hooks inside each `SQLSelectStatement.execute()` / `SQLMatchStatement.execute()` / etc. (DRY violation across 5+ classes); hook inside `LocalResultSet` constructor (misses DDL and non-idempotent commands); skip SQL entirely (loses observability for MATCH, DDL, `db.command(...)` apps, and underlying SQL beneath Gremlin); hook only in `executeInternal()` (silently misses `db.query(...)` because `query()` has its own duplicated parse+execute body at line 617 that does NOT route through `executeInternal()`).
- **Rationale**: `DatabaseSessionEmbedded` has two parallel SQL paths today: `executeInternal()` (lines 702-751) backs `command()` / `execute()`; `query()` (lines 617-686) is a separate idempotent path used by both native `db.query(...)` callers and the Gremlin bridge (`YTDBGraphQuery.execute` → `transaction.query(...)` → `session.query()`). Track 4 extracts a private helper `executeStatementWithMetrics(SQLStatement, String, Object)` wrapping `statement.execute(this, args, true)` with a timer, listener fire, and `QueryDetails` build, and invokes the helper from both call sites. Both call sites parse via `SQLEngine.parse(...)` before delegating, so the helper takes the already-parsed AST plus the raw SQL text (from `stringStatement` when present, else `statement.getOriginalStatement()`) plus `args`. Elapsed time follows the per-TX `QueryMonitoringMode` snapshot the helper reads from `currentTx.getQueryMonitoringMode()` (Track 1's new accessor): `LIGHTWEIGHT` (default) reads `GranularTicker.approximateNanoTime()` for zero-syscall capture; `EXACT` reads `System.nanoTime()` for sub-millisecond precision. Same pattern as the pre-existing `FrontendTransactionImpl.doCommit` (lines 650-722) and `YTDBQueryMetricsStep.close()` fire sites, so all listener callbacks in one transaction report consistent precision. To prevent Gremlin traversals from double-firing (one Gremlin span at `YTDBQueryMetricsStep.close()` plus one SQL span per underlying `YTDBGraphStep`), `YTDBGraphQuery.execute` activates a thread-local `GremlinSqlSuppression` token (re-entrant counter, auto-closeable) for the duration of the underlying `transaction.query(...)` call; the helper checks `GremlinSqlSuppression.isActive()` at step 2 and short-circuits before any timer read or listener fire. Net result: one Gremlin traversal = one Gremlin span; one native SQL call (via `query`, `command`, or `execute`) = one SQL span.
- **Risks/Caveats**: `stringStatement` can be null when a pre-parsed `SQLStatement` is passed in by an internal recursive call; the fallback is `statement.getOriginalStatement()`. Tracking ID comes from `String.valueOf(currentTx.getId())` — `FrontendTransaction.getId(): long` already exists and returns a stable internal ID, so Track 4 does not add a new accessor. `GremlinSqlSuppression` is a process-global static with a ThreadLocal counter; concurrent transactions on different threads do not interfere. Handled in Track 4.
- **Implemented in**: Track 4.
- **Full design**: design.md §"SQL execution layer hook"

#### D9: Extract `db.operation.name` and `db.collection.name` from both Gremlin bytecode and SQL AST

- **Alternatives considered**: omit both attributes (loses span-name quality and grouping); plugin layer with `QueryClassifier` SPI + `ServiceLoader` (rejected — buys no polymorphism for a single impl per input type and forces an `Object`-typed signature plus a `META-INF/services` manifest); pre-compute on query parse (Gremlin has no parse hook in current code, SQL parses inside `executeInternal`).
- **Rationale**: for Gremlin the bytecode is available in `YTDBQueryMetricsStep` (line 131 `traversal.getBytecode()`); the classifier identifies the start step (`V`/`E`/`addV`/`addE`/...) and the first `hasLabel` or `addV`/`addE` label argument. For SQL the parsed `SQLStatement` is available in `executeInternal` after `SQLEngine.parse()`; the classifier reads the statement subclass (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) and the FROM / INTO / UPDATE clause target class. Both yield low-cardinality values that drive `{db.operation.name} {db.collection.name}` span names per sem-conv. Two static-utility classifiers in `core` (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) piggyback on parsing the fire sites already perform — `produceScript()`'s instruction walk for Gremlin, `SQLEngine.parse(...)`'s unconditional AST production for SQL — and return a `Classification(operationName, collectionName)` value record consumed directly by the fire site.
- **Risks/Caveats**: complex Gremlin traversals (multi-class, no label) and complex SQL (no FROM clause, anonymous tables, multi-target UPDATE / MATCH chains) won't yield clean values. Both accessors return `Optional.empty()` and the span name falls back to `db.system.name`. Documented and tested.
- **Implemented in**: Track 1 (QueryDetails extension + both classifier helpers + Classification record, all in `core`), Track 3 (Gremlin fire-site wiring at `YTDBQueryMetricsStep.close()`), Track 4 (SQL fire-site wiring at `DatabaseSessionEmbedded.executeInternal()`).
- **Full design**: design.md §"Gremlin bytecode classification" and §"SQL execution layer hook"

#### D10: TX lifecycle fires consolidated in `FrontendTransactionImpl`

- **Alternatives considered**: fire from `YTDBTransaction.doOpen()` / `doRollback()` only (covers Gremlin path; `db.command(...)` SQL flows that don't cross `YTDBTransaction` get no TX-parent span); fire from both `YTDBTransaction` and `FrontendTransactionImpl` (risks double TX spans for Gremlin-initiated transactions that traverse both classes).
- **Rationale**: `beginInternal()` and `rollbackInternal()` are the single chokepoints for every TX path in the codebase. Gremlin's `YTDBTransaction.doOpen()` calls `activeSession.begin()` which routes through `beginInternal()`; `DatabaseSessionEmbedded.begin()` calls it directly. Same for rollback (many call sites in `DatabaseSessionEmbedded`, plus the Gremlin path). Putting both fires inside `FrontendTransactionImpl` covers Gremlin and native SQL with one fire site each. The existing private `notifyMetricsListener` wrapper sits in the same class, so the two new fires reuse the same try/catch shape without needing a hoisted helper.
- **Risks/Caveats**: `rollbackInternal()` is called recursively from error paths inside `FrontendTransactionImpl`; the new fire must be gated by `txStartCounter == 0` so nested rollbacks don't emit multiple `transactionRolledBack` callbacks. The snapshot must be captured before `txStartCounter` increments in `beginInternal()` so nested begins reuse the outermost snapshot.
- **Implemented in**: Track 1.
- **Full design**: design.md §"Transaction lifecycle with full hierarchy"

#### D11: Listener wrapper widened from `Exception` to `Throwable`

- **Alternatives considered**: leave the existing wrappers catching `Exception` only (design promise of "catches Throwable" downgrades to current code; an `Error` from OOM in OTel span allocation unwinds the TX).
- **Rationale**: an OTel listener that throws an `Error` (OOME during span allocation, `AssertionError` in a misconfigured SDK) MUST NOT take down the transaction. Both existing wrappers (`FrontendTransactionImpl.notifyMetricsListener:730`, `YTDBQueryMetricsStep:148`) catch `Exception` today; Track 1 widens them to `Throwable` and applies the same shape to the two new TX-lifecycle fires. The widening logs the throwable at WARN and swallows it.
- **Risks/Caveats**: catching `Throwable` masks JVM-fatal errors (`OutOfMemoryError`, `StackOverflowError`) when they originate inside the listener. The log line preserves operator visibility; YTDB's own code paths around the listener call remain free to throw and propagate normally.
- **Implemented in**: Track 1.
- **Full design**: design.md §"Exception isolation contract"

#### D12: Tracer instrumentation version from `YouTrackDBConstants.getRawVersion()`

- **Alternatives considered**: hard-code a version string (drifts); use `getVersion()` which returns `"<v> (build <r>, branch <b>)"` and is too verbose for the version slot.
- **Rationale**: OTel `getTracer(name, version)` expects a clean version string. `getRawVersion()` returns just `"0.5.0-SNAPSHOT"`. The constant lives in `internal.core.YouTrackDBConstants`; the new module is internal too, so accessing it is fine.
- **Risks/Caveats**: none.
- **Implemented in**: Track 3.

#### D13: Test infrastructure using `opentelemetry-sdk-testing`

- **Alternatives considered**: custom in-memory exporter; mock OTel APIs.
- **Rationale**: `io.opentelemetry:opentelemetry-sdk-testing` ships `InMemorySpanExporter`, `OpenTelemetryRule`, and other building blocks designed for instrumentation tests. Using it gives assertions on real SDK behavior (sampler, exporter pipeline, attribute propagation) without re-implementing the test fixtures.
- **Risks/Caveats**: adds a test-scope dependency on the new module. Acceptable.
- **Implemented in**: Track 6.

#### D14: Span timestamps captured via existing listener parameters, not new accessors

- **Alternatives considered**: implicit `tracer.spanBuilder(...).startSpan()` with no timestamp (records callback-entry time as the span start, drifting from the actual query start by however long the listener takes to run); extend `QueryDetails` with `getStartTimestampNanos()` / `getEndTimestampNanos()` accessors returning nanosecond-precision timestamps (buys sub-millisecond start precision under EXACT but adds two slots on a heavily-overridden SPI for precision the trace viewers don't render).
- **Rationale**: the listener API already passes `startedAtMillis` and `executionTimeNanos` as parameters of `queryFinished(...)` (and `commitAtMillis` / `commitTimeNanos` for `writeTransactionCommitted`). The OTel listener consumes them through `setStartTimestamp(startedAtMillis, MILLISECONDS).startSpan()` and `span.end(startedAtMillis + executionTimeNanos / 1_000_000, MILLISECONDS)`. The span's recorded duration matches the fire-site measurement at nanosecond resolution because `executionTimeNanos` is passed through unchanged. Under both `LIGHTWEIGHT` and `EXACT` the two parameters come from the same clock pair the fire site captured for the timing-mode snapshot, so the Timing-mode uniformity invariant holds at the timestamp level. No new SPI surface is needed.
- **Risks/Caveats**: `startedAtMillis` carries millisecond precision (~10 ms under LIGHTWEIGHT, ~1 ms under EXACT), so the span START loses sub-millisecond detail. The span DURATION is preserved at nanosecond precision because `executionTimeNanos` passes through unchanged. Trace viewers render at millisecond resolution, so the loss is invisible in viewer UIs.
- **Implemented in**: Track 3 (OTel listener span mapping at `OTelQueryMetricsListener.queryFinished` and `OTelTransactionMetricsListener.writeTransactionCommitted`).
- **Full design**: design.md §"Span timing capture"

### Invariants

- **One-way dependency**: `core` and `server` carry no OTel imports. Enforced by Maven dependency scope and verified by a static check in the build.
- **TX span boundedness**: every `transactionStarted` callback that fires MUST be followed by exactly one of `writeTransactionCommitted` / `writeTransactionFailed` / `transactionRolledBack`, so OTel never leaks an unclosed span.
- **Listener exception isolation**: an OTel listener throwing inside any callback (including `Throwable`) MUST NOT propagate to the transaction. Track 1 widens the existing `Exception`-only catch in `FrontendTransactionImpl.notifyMetricsListener` and `YTDBQueryMetricsStep.close()` to `Throwable`, and applies the same shape to the two new TX-lifecycle fires.
- **TX lifecycle fire chokepoint**: `transactionStarted` and `transactionRolledBack` fire exclusively from `FrontendTransactionImpl.beginInternal()` and `rollbackInternal()` respectively, both gated by `txStartCounter == 0` so nested begins/rollbacks do not double-fire.
- **`db.system.name = "youtrackdb"`** is a compile-time constant in `YouTrackDBOpenTelemetry`; no caller can override it.
- **Span kind by role**: query span MUST be CLIENT, TX span MUST be INTERNAL, commit span MUST be CLIENT, and no SERVER / PRODUCER / CONSUMER kinds are emitted by YTDB. Checked by tests asserting on `SpanData.getKind()` for the positive cases and by a negative assertion that the in-memory exporter never captures a span with any of the three excluded kinds.
- **Timing-mode uniformity**: every listener fire site in one transaction (`YTDBQueryMetricsStep.close()` for Gremlin, `DatabaseSessionEmbedded.executeStatementWithMetrics` helper for SQL — called from both `query()` and `executeInternal()`, `FrontendTransactionImpl.notifyMetricsListener()` for commit) reads timestamps from the `QueryMonitoringMode` snapshot captured at `beginInternal()` and held immutable for the TX's lifetime. Checked by `OTelTimingModeTest` in Track 6. See design.md §"SQL execution layer hook" for the LIGHTWEIGHT / EXACT routing rules and the mid-TX-mutation carve-out.
- **Gremlin span uniqueness**: one Gremlin traversal emits exactly one query span; the SQL `executeStatementWithMetrics` helper short-circuits when `GremlinSqlSuppression.isActive()` so the Gremlin-to-SQL translation never produces nested child spans. Activation is owned by `YTDBGraphQuery.execute` via a try-with-resources token. Checked by `OTelGremlinSuppressionTest` in Track 6 asserting one span per traversal regardless of how many `YTDBGraphStep` instances the strategy injects.

### Integration Points

- `YourTracks.registerGlobalQueryListener(QueryMetricsListener)` and matching unregister, plus the transaction-listener pair — static methods on the existing `final` utility class (CR9: registry is process-global; the `YouTrackDB` interface gets no new methods).
- `TransactionMetricsListener#transactionStarted(TransactionDetails)` and `#transactionRolledBack(TransactionDetails)`, new default no-op methods on the existing nested `TransactionMetricsListener` SPI. Fire sites: `FrontendTransactionImpl.beginInternal()` and `rollbackInternal()`.
- `QueryMetricsListener.QueryDetails#getOperationName(): Optional<String>`, `#getCollectionName(): Optional<String>`, and `#getDatabaseName(): Optional<String>`, new default accessors on the existing nested interface.
- `TransactionMetricsListener.TransactionDetails#getDatabaseName(): Optional<String>`, new default accessor on the existing nested interface.
- `DatabaseSessionEmbedded.executeStatementWithMetrics(SQLStatement, String, Object)`, new private helper invoked from both `query()` (line 617) and `executeInternal()` (line 702); wraps `statement.execute(...)` for every SQL path including read-only `db.query(...)`. See design.md §"SQL execution layer hook" for the `QueryMonitoringMode` routing, the tracking-ID source (`currentTx.getId()` via `String.valueOf(...)`), the `GremlinSqlSuppression.isActive()` short-circuit, and the timer pattern.
- `GremlinSqlSuppression.activate(): AutoCloseable` and `GremlinSqlSuppression.isActive(): boolean`, new static utility in `core/.../profiler/monitoring/`. Activated by `YTDBGraphQuery.execute(session)` via try-with-resources around the underlying `transaction.query(...)` call; consulted by the SQL helper before firing the listener.
- `FrontendTransaction.getQueryMonitoringMode(): QueryMonitoringMode`, new accessor on the existing interface returning the per-TX snapshot. See design.md §"SQL execution layer hook" for the snapshot-immutability rule and the setter call path from `YTDBTransaction.doOpen()` / `DatabaseSessionEmbedded.begin()`.
- `FrontendTransaction.iterateAllQueryListeners(): Iterable<QueryMetricsListener>`, new accessor returning the merged view: global snapshot (captured at `beginInternal`) followed by per-TX listeners added via `withQueryListener`. Consumed by both `YTDBQueryMetricsStep.close()` (Gremlin path, Track 1 snapshot iteration refactor) and `DatabaseSessionEmbedded.executeStatementWithMetrics` helper (SQL path, Track 4) so per-TX listeners fire for SQL statements as well as Gremlin traversals.
- `GremlinBytecodeClassifier.classify(Bytecode): Classification` and `SqlSyntaxClassifier.classify(SQLStatement): Classification`, two static-utility classes in `core` called directly from `YTDBQueryMetricsStep.close()` and the `DatabaseSessionEmbedded.executeStatementWithMetrics` helper (invoked from both `query()` and `executeInternal()`) respectively. No SPI, no ServiceLoader; the `Classification` value record is the shared return type.
- `YouTrackDBOpenTelemetry.setOpenTelemetry(OpenTelemetry)` and `shutdown()`, new module entry points. A package-private 2-arg variant `setOpenTelemetry(OpenTelemetry, boolean ownedByYtdb)` exists for `OpenTelemetryServerPlugin` to signal server-mode ownership.
- `YouTrackDBServer.activate()` extended with `ServiceLoader.load(ServerLifecycleListener.class)` so the new `OpenTelemetryServerPlugin` auto-registers without explicit operator wiring (CR3: existing code uses only manual `registerLifecycleListener`; Track 5 adds the ServiceLoader call).
- `ServerLifecycleListener.onAfterActivate()` / `onBeforeDeactivate()`, bound by a new `OpenTelemetryServerPlugin` in the new module.

### Non-Goals

- **OTel Metrics signal (histograms, counters)**: out of scope. Span-only telemetry. Percentile metrics are explicitly deferred in the YouTrack design article and tracked separately.
- **TinkerPop `ProfileStep` / native YTDB Explain integration**: out of scope. The Gremlin-to-SQL explain bridge is a parallel concern, not part of the OTel emission path.
- **`OTEL_SEMCONV_STABILITY_OPT_IN` env var**: greenfield emission of stable v1.33.0 conventions, no legacy version to switch between.

## Checklist

- [ ] Track 1: Foundation extension in `core` for OTel-readiness
  > Extend the listener SPI in `core` so the OTel module can install against it: new default methods on `TransactionMetricsListener`, new `Optional<String>` accessors on `QueryDetails` and `TransactionDetails`, a `Classification` value record, two static-utility classifier classes (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) living next to the existing parsing infrastructure, a process-global listener registry on `YourTracks`, TX-lifecycle fires inside `FrontendTransactionImpl`, a strategy-gate widening, snapshot iteration at call sites, Throwable-widened exception wrappers, a `QueryMonitoringMode` snapshot field on `FrontendTransactionImpl` plus a `getQueryMonitoringMode()` accessor on `FrontendTransaction` so Track 4's SQL hook reads the same mode the Gremlin path already honors, and an `iterateAllQueryListeners(): Iterable<QueryMetricsListener>` accessor on `FrontendTransaction` returning the merged global-snapshot + per-TX-list view so both fire paths honor per-TX `withQueryListener` listeners. No behavior change for transactions without registered listeners. Detailed description in `plan/track-1.md`.
  > **Scope:** ~8 steps (single coherent stream of core SPI work; each step is one commit-sized unit) covering: (1) two `TransactionMetricsListener` defaults + `TransactionDetails` namespace accessor, (2) three `QueryDetails` accessors, (3) `Classification` record + two classifier helpers, (4) `GlobalListenerRegistry` + four `YourTracks` static methods, (5) `beginInternal()` snapshot capture + `QueryMonitoringMode` snapshot + accessor + setter + lifecycle fires, (6) strategy gate widening, (7) snapshot iteration refactor + `YTDBQueryMetricsStep` mode-read source switch + exception-wrapper widening to `Throwable`, (8) JUnit tests.

- [ ] Track 2: `youtrackdb-opentelemetry` Maven module skeleton
  > Create the new module under the root reactor with parent inheritance, OTel BOM-driven dependencies, Spotless config, and an empty package layout ready for the listener implementations. Adds a Maven dependency-arrow check so `core` never gains an OTel import. Detailed description in `plan/track-2.md`.
  > **Scope:** ~3 steps covering module pom, root reactor wiring, dependency direction check.

- [ ] Track 3: OTel listener implementations
  > Implement `OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, and the `YouTrackDBOpenTelemetry` facade inside the new module. Maps every Gremlin callback to sem-conv-compliant spans with the right kind, attributes, and parent context — Gremlin classification is owned by Track 1, the listener just reads pre-populated `QueryDetails` accessors. SQL source wiring lands in Track 4; registration logic in Track 5. Detailed description in `plan/track-3.md`.
  > **Scope:** ~5 steps covering query listener, TX listener, facade, sem-conv attribute mapping, span-name fallback.
  > **Depends on:** Track 1, Track 2

- [ ] Track 4: SQL execution layer helper, Gremlin-SQL suppression, and SQL query sanitizer
  > Extract a private helper `executeStatementWithMetrics(SQLStatement, String, Object)` on `DatabaseSessionEmbedded` and invoke it from both `query()` (line 617) and `executeInternal()` (line 702) so every native SQL statement (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) — read-only `db.query(...)` included — flows through `QueryMetricsListener.queryFinished(...)`. Add a `GremlinSqlSuppression` static utility (re-entrant ThreadLocal counter + `AutoCloseable` token) in `core/.../profiler/monitoring/` and wrap the underlying `transaction.query(...)` call in `YTDBGraphQuery.execute(session)` so the SQL helper short-circuits during Gremlin-driven SQL. Implement `SqlSanitizer` (literal-to-`?` replacement) inside the OTel module. The SQL classifier lands in Track 1 (as a `core` static utility); Track 4's helper calls it directly to populate the `QueryDetails` operation/collection accessors. Detailed description in `plan/track-4.md`.
  > **Scope:** ~6 steps covering: (1) extract `executeStatementWithMetrics` helper + wire from `executeInternal()`, (2) wire helper from `query()` (after the `isIdempotent` check); (3) `GremlinSqlSuppression` utility class in `core`; (4) activate suppression in `YTDBGraphQuery.execute`; (5) `SqlSanitizer` regex implementation in the OTel module; (6) JUnit tests covering helper coverage of both call sites, suppression entry/exit, sanitizer edge cases. Tracking ID comes from the existing `currentTx.getId()` via `String.valueOf(...)`; no new `FrontendTransaction.getTrackingId()` accessor needed.
  > **Depends on:** Track 1, Track 2, Track 3

- [ ] Track 5: Configuration parameters and lifecycle integration
  > Add `GlobalConfiguration` entries, wire embedded mode (host-provided `OpenTelemetry` via setter + `GlobalOpenTelemetry.get()` fallback + lazy auto-configure from `OPENTELEMETRY_*` entries), add a `ServerLifecycleListener`-based plugin that initializes/closes the SDK in server mode based on the config flag, and add `ServiceLoader.load(ServerLifecycleListener.class)` to `YouTrackDBServer.activate()` so the plugin auto-discovers (the existing code only honors explicit `registerLifecycleListener` calls — Track 5 adds the discovery loop). After this track the OTel module auto-enrols when enabled. Detailed description in `plan/track-5.md`.
  > **Scope:** ~6 steps covering config entries, embedded facade with 2-arg `setOpenTelemetry`, server plugin, ServiceLoader discovery in `YouTrackDBServer`, SDK init/close, idempotence.
  > **Depends on:** Track 2, Track 3

- [ ] Track 6: Test suite using `opentelemetry-sdk-testing`
  > Cover the listener-to-span mapping for both Gremlin and SQL paths (sem-conv attributes, span kinds, hierarchy parent/child links), context propagation in embedded (host span becomes parent), server-mode lifecycle (init/close), the exception-isolation invariant, the timing-mode uniformity invariant, the Gremlin span uniqueness invariant (one traversal = one span, no SQL children — verifies `GremlinSqlSuppression` behavior), and regression coverage that `db.query("SELECT ...")` emits a SQL span. Uses `InMemorySpanExporter` so assertions run against real SDK behavior. Detailed description in `plan/track-6.md`.
  > **Scope:** ~10 steps covering Gremlin attribute mapping, SQL attribute mapping (`db.command` / `db.execute` / `db.query` separately), hierarchy, context propagation, lifecycle, exception isolation, timing-mode uniformity, Gremlin span uniqueness via `OTelGremlinSuppressionTest`, `db.query` coverage via `OTelDbQuerySpanTest`, coverage gate.
  > **Depends on:** Track 3, Track 4, Track 5

## Plan Review
- [x] Plan review (consistency + structural) — passed at iteration 3 (consistency: 2 iterations; structural: 2 iterations; combined Phase 2 manual re-run after Mutation 9).

**Auto-fixed (mechanical)**: CR1 (`QueryMonitoringMode` Core Concepts entry rewording in `design.md` — "co-located with `QueryMetricsListener` / `TransactionMetricsListener` in `internal/common/profiler/monitoring/`" replaces the misleading "on the existing `QueryMetricsListener` API"), CR2 (Track 1 scope-indicator and Concrete Deliverables aligned to match the Plan-of-Work edit count), CR4 (new `design.md` § "Span timing capture" section + new D14 in `implementation-plan.md` documenting span timestamps via existing listener parameters; no new SPI accessors), CR12 (two summary lines at plan L77 + L210 re-worded to match the iteration-2 immutability semantics), S18 (`FrontendTransactionImpl` Component Map bullet at plan L77 trimmed from 7 sentences to 4; design-level material delegated to `design.md` § "Transaction-lifetime span semantics" and § "SQL execution layer hook" cross-references), S19 (Timing-mode uniformity invariant at plan L201 trimmed; LIGHTWEIGHT / EXACT routing rules delegated to the same design section), S20 (two integration-point bullets at plan L209 + L210 trimmed; rules delegated to the same design section), S22 (duplicate `getQueryMonitoringMode()` sentence in plan L77 bullet removed during the iter-3 trim), S23 (plan L77 bullet sentence-count brought to ≤ 4, aligned with sibling Component Map bullets).

**Escalated (design decisions)**: CR3 (user picked Wariant A — re-route `YTDBQueryMetricsStep` mode read at fire time; later refactored under CR5 — see below), CR5 (user picked Wariant C — `QueryMonitoringMode` snapshot is immutable for the TX's lifetime; mid-TX `YTDBTransaction.withQueryMonitoringMode(...)` mutates the YTDBTransaction field but does not affect the active TX's snapshot, with effect deferred to the next `begin()`; `YTDBQueryMetricsStep` keeps the ctor cache but switches its read source from `ytdbTx.getQueryMonitoringMode()` to `txStateAccessor.getQueryMonitoringMode()` — the immutable snapshot — making per-iteration consistency follow from snapshot immutability), S17 (user picked Wariant B — keep Track 1 whole; reduce scope indicator from `~10 steps` to `~8 steps` with an explanatory note "single coherent stream of core SPI work; each step is one commit-sized unit" and an enumerated 8-item comma list mapping to the 8 narrative edits), S21 (user picked option (a) — leave D14 `**Implemented in**: Track 3` as-is, consistent with the plan's convention of listing the primary-functionality track, not the testing track).

**Design mutations during this Phase 2 re-run**: Mutation 10 (`section-add`: new `## Span timing capture` + Core Concepts entry reword) and Mutation 11 (`content-edit`: § "SQL execution layer hook" Edge cases gained the snapshot-immutability bullet + the EXACT opt-in bullet tightened to "before that transaction begins"). Both logged in `design-mutations.md`.

**Prior plan-review history (preserved for traceability):**

- Manual `/review-plan` re-run after Mutation 8 — passed at iteration 1. Auto-fixed: S15 (stale `plan/track-1.md` L117 "Out of scope" bullet still claiming Track 1 "only exposes the SPI slot" — rewritten to scope out-of-scope to the fire-site wiring with Track 1 owning the helpers), S16 (D9's "Implemented in" line in `implementation-plan.md` extended from Track 1 only to Track 1 + Track 3 + Track 4). Escalated: none. See commit `64243821b0 Plan review autonomous fixes for ytdb-496-opentelemetry-support` for the per-finding resolutions.

- Manual `/review-plan` re-run after Mutation 7 — passed. Auto-fixed: CR1 (`assertOnOwningThread` call-site enumeration in `design.md`), CR2 (`YTDBQueryMetricsStrategy.apply()` line citations in `plan/track-1.md`), S12 (duplicate `Out of scope:` blocks in `plan/track-4.md`), S14 (Track 1 intro paragraph word count). Escalated: S13 (user resolved "leave as-is" on the `design.md` § "Class Design" diagram class count; Mutation 8 later brought the count back to 12 anyway).
- Prior manual `/review-plan` round 1 fixes: CR-R1, CR-R3, CR-R4, S7, S8, S9, S11 (mechanical); CR-R2, S10 (escalated). See commit `3a579afa8e Plan review autonomous fixes for ytdb-496-opentelemetry-support` for the per-finding resolutions.
- Prior autonomous Phase 2 fixes: CR2, CR6, CR7, CR8, CR10, CR11, CR13, CR14, CR15, CR17, CR18, S1, S2, S3, S5 (mechanical); CR1, CR3, CR4, CR5, CR9, CR12, CR16, S4, S6 (escalated). See `805dc04ab3 [YTDB-496] Add initial implementation plan and design` and `ca7f5231c6 Plan review autonomous fixes for ytdb-496-opentelemetry-support`.

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
