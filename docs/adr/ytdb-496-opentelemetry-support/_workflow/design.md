# YTDB-496 OpenTelemetry support — Design

## Overview

This design adds a new optional Maven module `youtrackdb-opentelemetry` that turns the existing YTDB listener callbacks into OpenTelemetry spans, so a host running embedded YTDB and an operator running a standalone server both get database telemetry visible in any OTel-compatible trace viewer.

This design assumes familiarity with the existing `QueryMetricsListener` and `TransactionMetricsListener` firing sites in `YTDBQueryMetricsStep` and `FrontendTransactionImpl`, and with the `YTDBTransaction` open / commit / rollback lifecycle. The audience is contributors maintaining the metrics and transaction subsystems in `core`.

Today YTDB has an internal `QueryMetricsListener` SPI that fires only on Gremlin traversal close, plus a `TransactionMetricsListener` that fires on write-transaction commit, but the listeners are per-transaction and the project ships no OTel binding. Native SQL queries (the path used for `db.command(...)`, MATCH, and DDL) currently fire neither listener. The design closes that gap with four load-bearing additions: a global listener registry in `core` so an OTel listener registered once at startup auto-applies to every subsequent transaction; two new default-no-op methods on `TransactionMetricsListener` (`transactionStarted`, `transactionRolledBack`) so the OTel module can emit a TX-lifetime parent span covering queries and commit as children; a new listener fire site in a private helper `executeStatementWithMetrics(SQLStatement, String, Object)` called from both `DatabaseSessionEmbedded.query()` (line 617) and `executeInternal()` (line 702) so every SQL statement type (SELECT, INSERT, UPDATE, DELETE, MATCH, DDL), read-only `db.query(...)` included, flows through the same listener API; and a pair of static-utility classifiers in `core` (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`), called directly from their respective fire sites, that extract `db.operation.name` and `db.collection.name` so spans carry sem-conv v1.33.0 attributes. A thread-local `GremlinSqlSuppression` flag activated by `YTDBGraphQuery.execute` (the Gremlin-to-SQL bridge that translates each traversal step into a SQL query and runs it via `session.query()`) keeps the SQL helper silent during Gremlin-driven SQL so one traversal emits exactly one span.

Other subsystems restructured to fit: the nested `QueryMetricsListener.QueryDetails` gains three `Optional<String>` accessors (operation, collection, namespace) and the nested `TransactionMetricsListener.TransactionDetails` gains one (namespace), `FrontendTransactionImpl.beginInternal()` and `rollbackInternal()` fire the new TX lifecycle calls (covering both Gremlin and native-SQL paths through one chokepoint), the existing exception-isolation try/catch in `FrontendTransactionImpl` and `YTDBQueryMetricsStep` widens from `Exception` to `Throwable` to cover the new fire sites, the SQL hook reuses the existing `FrontendTransaction.getId(): long` accessor (no new tracking-id method), and `GlobalConfiguration` gains four `OPENTELEMETRY_*` entries that drive the server-mode SDK init.

In embedded mode the SDK resolution chain has three steps in priority order: host-provided via `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)`, then `GlobalOpenTelemetry.get()` if the host configured the global, then a YTDB-built SDK auto-configured from `OPENTELEMETRY_*` config when neither of the first two yielded a real instance. The flag is never inert; ownership is tracked so `shutdown()` closes only the SDK YTDB created. In server mode YTDB always owns the SDK because the server is a standalone process; an `OpenTelemetrySdk` built from the same config entries wires through a `ServerLifecycleListener`-based plugin.

The rest of this document covers: Core Concepts (vocabulary primer), Class Design, Workflow, sem-conv attribute mapping, context propagation in embedded, transaction-lifetime span semantics, Gremlin bytecode classification, SQL execution layer hook, SDK lifecycle for embedded vs server, listener registration and ordering, and the exception-isolation contract.

## Core Concepts

This design introduces seven load-bearing ideas. Each is named and used without re-definition later; if a downstream section references one, the relevant definition is here. Each entry pairs the new term with what it replaces, so the delta from the baseline is visible at a glance.

**Span.** An OpenTelemetry record covering one unit of work with a start timestamp, an end timestamp, a name, a kind (CLIENT / SERVER / INTERNAL / PRODUCER / CONSUMER), a status (OK / ERROR), and arbitrary key/value attributes. Replaces "nothing in YTDB" (no prior telemetry primitive). → §"Sem-conv attribute mapping" and §"Class Design".

**Trace and Context.** A trace is a tree of spans bound by a shared `traceId`; each child span carries a `parentSpanId`. `Context` is the OTel mechanism for propagating the current span through the call stack so that a span created inside a method automatically attaches as a child of the surrounding span. Replaces "no parent/child relationship between operations". → §"Context propagation in embedded".

**Listener registry (global).** A pair of `CopyOnWriteArrayList`s of `QueryMetricsListener` and `TransactionMetricsListener` instances held in a process-global `GlobalListenerRegistry` in `core` and exposed via static methods on `YourTracks` (the existing `final` utility class). Snapshotted by `FrontendTransactionImpl.beginInternal()` into per-TX fields before `txStartCounter` increments, so both Gremlin and native-SQL transactions share one fire path. Replaces "per-TX `withQueryListener` only", which made the config flag inert. → §"Listener registration and ordering".

**TX-lifetime span.** The INTERNAL span the OTel TX listener opens on `transactionStarted` and closes on commit / failed-commit / rollback. Acts as the parent of every query span and the commit span emitted between begin and close. Replaces "no transaction-scoped grouping in the trace viewer". → §"Transaction-lifetime span semantics".

**Sem-conv v1.33.0.** OpenTelemetry's stable semantic conventions for database client spans, dictating attribute names (`db.system.name`, `db.query.text`, etc.), their requirement levels (Required / Conditionally Required / Recommended / Opt-In), and the span-name fallback chain. Replaces "no vendor-neutral attribute schema". → §"Sem-conv attribute mapping".

**QueryMonitoringMode.** A per-transaction enum co-located with `QueryMetricsListener` / `TransactionMetricsListener` in `internal/common/profiler/monitoring/`, selecting timing precision. `LIGHTWEIGHT` (default) reads from `GranularTicker` at ~10 ms granularity with no syscall on the hot path; `EXACT` reads from `System.nanoTime()` / `System.currentTimeMillis()` for sub-millisecond precision at the cost of two syscalls per measurement. Hosts opt into `EXACT` per transaction via `YTDBTransaction.withQueryMonitoringMode(EXACT)`. Snapshotted by `FrontendTransactionImpl.beginInternal()` so every fire site in the transaction reports consistent precision. Replaces "always-EXACT timing" implied by the original design. → §"SQL execution layer hook" and §"Gremlin bytecode classification".

**Query source classification.** Two static-helper classifiers in `core` extract `db.operation.name` and `db.collection.name` for the two query sources YTDB supports. The Gremlin classifier walks the TinkerPop `Bytecode` instruction list to resolve the first source step (`V`/`E`/`addV`/`addE`/`drop`) and the first `hasLabel(X)` argument. The SQL classifier reads the parsed `SQLStatement` subclass (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) and the target class from the FROM / INTO / UPDATE clause. Both return `Optional.empty()` when the query shape doesn't yield clean values. Called directly from the existing fire sites: `YTDBQueryMetricsStep` for Gremlin, and the `DatabaseSessionEmbedded.executeStatementWithMetrics` helper for SQL (invoked from both `query()` and `executeInternal()`). No SPI or ServiceLoader; the call sites parse before invoking, so the classifiers piggyback on parsing that runs anyway. Replaces "raw sanitized query string only". → §"Gremlin bytecode classification" (Gremlin rules table) and §"SQL execution layer hook" (SQL rules table and statement-subclass dispatch).

## Class Design

```mermaid
classDiagram
    class QueryMetricsListener {
        <<interface>>
        +queryFinished(QueryDetails, long, long) void
    }
    class TransactionMetricsListener {
        <<interface>>
        +transactionStarted(TransactionDetails) void
        +writeTransactionCommitted(TransactionDetails, long, long) void
        +writeTransactionFailed(TransactionDetails, long, long, Exception) void
        +transactionRolledBack(TransactionDetails) void
    }
    class QueryDetails["QueryMetricsListener.QueryDetails"] {
        <<interface>>
        +getQuery() String
        +getQuerySummary() String
        +getTransactionTrackingId() String
        +getOperationName() Optional~String~
        +getCollectionName() Optional~String~
        +getDatabaseName() Optional~String~
    }
    class TransactionDetails["TransactionMetricsListener.TransactionDetails"] {
        <<interface>>
        +getTransactionTrackingId() String
        +getDatabaseName() Optional~String~
    }
    class OTelQueryMetricsListener {
        -Tracer tracer
        +queryFinished(QueryDetails, long, long) void
    }
    class OTelTransactionMetricsListener {
        -Tracer tracer
        -ThreadLocal~Context~ activeTxContext
        +transactionStarted(TransactionDetails) void
        +writeTransactionCommitted(TransactionDetails, long, long) void
        +writeTransactionFailed(TransactionDetails, long, long, Exception) void
        +transactionRolledBack(TransactionDetails) void
    }
    class YouTrackDBOpenTelemetry {
        -OpenTelemetry openTelemetry
        -boolean ownedByYtdb
        -Tracer tracer
        +setOpenTelemetry(OpenTelemetry) void
        ~setOpenTelemetry(OpenTelemetry, boolean ownedByYtdb) void
        +shutdown() void
        ~tracer() Tracer
    }
    class GremlinBytecodeClassifier {
        <<utility>>
        +classify(Bytecode) Classification$
    }
    class SqlSyntaxClassifier {
        <<utility>>
        +classify(SQLStatement) Classification$
    }
    class SqlSanitizer {
        +sanitize(String rawSql) String
    }
    class Classification {
        +operationName Optional~String~
        +collectionName Optional~String~
    }
    class OpenTelemetryServerPlugin {
        +onAfterActivate() void
        +onBeforeDeactivate() void
    }
    QueryMetricsListener ..> QueryDetails : nests
    TransactionMetricsListener ..> TransactionDetails : nests
    QueryMetricsListener <|.. OTelQueryMetricsListener
    TransactionMetricsListener <|.. OTelTransactionMetricsListener
    OTelQueryMetricsListener --> YouTrackDBOpenTelemetry : tracer()
    OTelTransactionMetricsListener --> YouTrackDBOpenTelemetry : tracer()
    OTelQueryMetricsListener --> QueryDetails : reads operation / collection / namespace
    GremlinBytecodeClassifier --> Classification : returns
    SqlSyntaxClassifier --> Classification : returns
    OpenTelemetryServerPlugin --> YouTrackDBOpenTelemetry : setOpenTelemetry(.., ownedByYtdb=true) / shutdown
```

The diagram covers the production classes the design introduces. Two interfaces in `core` are extended (`TransactionMetricsListener` and `QueryDetails` gain default methods; `QueryMetricsListener` itself stays unchanged but is consumed by a new impl). Two new static-utility classes (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) and one value record (`Classification`) land in `core` next to the existing parsing infrastructure — Gremlin's classifier piggybacks on the bytecode walk `YTDBQueryMetricsStep.produceScript()` already performs, and the SQL classifier dispatches on the `SQLStatement` AST that both `DatabaseSessionEmbedded.query()` and `executeInternal()` already produce via `SQLEngine.parse(...)` before delegating to the `executeStatementWithMetrics` helper. The classifiers are pure functions, called directly from the existing fire sites; no SPI, no ServiceLoader. Five classes in the new OTel module implement the integration (`OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, `YouTrackDBOpenTelemetry`, `SqlSanitizer`, `OpenTelemetryServerPlugin`), keeping the static dependency arrow one-way (`youtrackdb-opentelemetry` → `core`). The producer copies each `Classification(operationName, collectionName)` value into the `QueryDetails` accessors before the listener fires. A small `GremlinSqlSuppression` utility (thread-local re-entrant counter, also in `core`) is consulted by the SQL helper before firing the listener and is activated by `YTDBGraphQuery.execute` for the duration of Gremlin-driven SQL. The diagram omits it as a utility that participates only through static method calls, with no class relationship to model.

The `ThreadLocal<Context>` field on `OTelTransactionMetricsListener` stores the current TX span without calling `Context.makeCurrent()` (which would leak the context across listener boundaries). The query listener reads the ThreadLocal directly when both listeners are co-registered, so query spans nest correctly as children of the TX span regardless of whether the query came in through Gremlin or SQL.

### References
- D-records: D1, D2, D3, D5, D8, D9
- Mechanics: none (single-file design)

## Workflow

### Query span lifecycle in embedded

```mermaid
sequenceDiagram
    participant Host as Host app code
    participant TS as host Tracer
    participant G as GraphTraversalSource
    participant Step as YTDBQueryMetricsStep
    participant CLS as GremlinBytecodeClassifier
    participant OQL as OTelQueryMetricsListener
    participant TR as OTel Tracer
    participant EXP as Span exporter

    Host->>TS: spanBuilder("host-op").startSpan()
    Host->>TS: span.makeCurrent()
    Host->>G: g.V().hasLabel("User").toList()
    G->>Step: traversal close
    Step->>CLS: classify(bytecode)
    CLS-->>Step: Classification(SELECT, User)
    Step->>OQL: queryFinished(details, startMs, durNs)
    OQL->>TR: spanBuilder("SELECT User").setParent(Context.current()).startSpan(startMs)
    OQL->>TR: span.end(startMs + durNs/1e6)
    TR-->>EXP: span data
    Host->>TS: hostSpan.end()
    TS-->>EXP: span data
```

The flow shows that the host code's active span becomes the parent of the YTDB query span automatically, because `Context.current()` resolves on the same thread the host called `makeCurrent()` on. The classifier runs in `YTDBQueryMetricsStep` before the listener fires, populating `QueryDetails.getOperationName()` and `getCollectionName()`; the listener uses them to build the sem-conv span name `SELECT User`.

The SQL path is symmetric. A private helper `executeStatementWithMetrics(SQLStatement, String, Object)` in `DatabaseSessionEmbedded`, called from both `query()` and `executeInternal()`, plays the role of `YTDBQueryMetricsStep` as the listener fire site, and `SqlSyntaxClassifier` replaces `GremlinBytecodeClassifier` for the accessor population. Span name construction, attribute mapping, and parent-context resolution are identical from the listener's point of view, because the listener reads `QueryDetails` accessors that have already been populated by whichever classifier ran. See §"SQL execution layer hook" for the SQL-side anatomy.

### Transaction lifecycle with full hierarchy

```mermaid
sequenceDiagram
    participant Host as Host code
    participant TX as FrontendTransactionImpl
    participant OTL as OTelTransactionMetricsListener
    participant OQL as OTelQueryMetricsListener
    participant TR as OTel Tracer

    Host->>TX: tx.begin() (via Gremlin) or session.begin() (native SQL)
    TX->>OTL: transactionStarted(details)
    OTL->>TR: spanBuilder("tx <trackingId>").setSpanKind(INTERNAL).startSpan()
    OTL->>OTL: store Context.current().with(txSpan) in ThreadLocal
    Host->>TX: g.V()...toList()  [query 1]
    TX->>OQL: queryFinished(details, ms, ns)
    OQL->>OTL: getActiveTxContext()
    OTL-->>OQL: ctxWithTxSpan
    OQL->>TR: spanBuilder("SELECT X").setParent(ctxWithTxSpan).startSpan(...)
    TR-->>OQL: span (child of txSpan)
    Host->>TX: tx.commit()
    TX->>OTL: writeTransactionCommitted(details, ms, ns)
    OTL->>TR: spanBuilder("commit").setSpanKind(CLIENT).setParent(ctxWithTxSpan).startSpan(ms)
    OTL->>TR: span.end(ms + ns/1e6)
    OTL->>TR: txSpan.setStatus(OK).end()
    OTL->>OTL: ThreadLocal.remove()
```

The participant box represents `FrontendTransactionImpl` because the listener fires happen inside `beginInternal()` / `rollbackInternal()` / `notifyMetricsListener()`. `YTDBTransaction.doOpen()` / `doRollback()` delegate to those methods, so the Gremlin path and the native-SQL path (`db.command(...)` flows that bypass the Gremlin `YTDBTransaction` wrapper but still reach `FrontendTransactionImpl` via `session.begin()`) share the same fire site.

The TX listener stores the context-carrying-TX-span in a `ThreadLocal<Context>` (not via `Context.makeCurrent()`) because the listener callback returns before any user code runs; `makeCurrent()` would leak into surrounding host code. The query listener consults the ThreadLocal directly via a package-private accessor when both listeners are registered together.

### Server-mode SDK lifecycle

```mermaid
sequenceDiagram
    participant Main as ServerMain.create()
    participant Srv as YouTrackDBServer
    participant Plugin as OpenTelemetryServerPlugin
    participant Cfg as GlobalConfiguration
    participant Sdk as AutoConfiguredSdk
    participant Fac as YouTrackDBOpenTelemetry

    Main->>Srv: activate()
    Srv->>Plugin: onAfterActivate()
    Plugin->>Cfg: OPENTELEMETRY_ENABLED?
    alt enabled
        Plugin->>Cfg: read endpoint, protocol, service.name
        Plugin->>Sdk: builder().setEndpoint(...).build()
        Plugin->>Fac: setOpenTelemetry(sdk.openTelemetry)
    end
    Note over Srv: server runs, transactions emit spans
    Main->>Srv: shutdown()
    Srv->>Plugin: onBeforeDeactivate()
    Plugin->>Fac: shutdown()
    Fac->>Sdk: close()
    Note over Sdk: flushes pending spans
```

The plugin is ServiceLoader-discovered. When the new module is not on the classpath, the plugin doesn't load and the server runs with no OTel cost.

### References
- D-records: D1, D2, D3, D4, D10
- Mechanics: none

## Sem-conv attribute mapping

**TL;DR.** Every emitted query span carries `db.system.name="youtrackdb"` plus a fallback chain of sem-conv attributes filled in to the extent the source allows. Span name follows the v1.33.0 chain: `db.query.summary` → `db.operation.name db.collection.name` → `db.collection.name` → `db.system.name`. Query text comes from the source-appropriate sanitizer: `ValueAnonymizingTypeTranslator` for Gremlin (existing), `SqlSanitizer` for SQL (Track 4).

The full mapping per attribute:

| Attribute | Requirement | Source | Notes |
|---|---|---|---|
| `db.system.name` | Required | constant `"youtrackdb"` | sem-conv §"Notes" allows custom value when not on well-known list |
| `db.namespace` | Conditionally Required | `QueryDetails.getDatabaseName()` (Gremlin: `session.getDatabaseName()` at the `YTDBQueryMetricsStep` fire site; SQL: same at the `DatabaseSessionEmbedded.executeStatementWithMetrics` helper, populated by either call site — `query()` or `executeInternal()`) | Track 1 adds the default accessor on both `QueryDetails` and `TransactionDetails`; omitted when the session has no name |
| `db.collection.name` | Conditionally Required | classifier result `.collectionName` | absent for multi-class traversals / anonymous SQL FROM subqueries |
| `db.operation.name` | Conditionally Required | classifier result `.operationName` | one of `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `MATCH` / `CREATE` / `ALTER` / `DROP` |
| `db.query.text` | Recommended | `QueryDetails.getQuery()` | already sanitized: Gremlin via `ValueAnonymizingTypeTranslator`, SQL via `SqlSanitizer` |
| `db.query.summary` | Recommended | `QueryDetails.getQuerySummary()` if set, else `"{operation} {collection}"` if both present | client-provided summary wins |
| `db.response.status_code` | Conditionally Required | YTDB error code if available | currently no canonical YTDB error-code field; omitted in YTDB-496 |
| `error.type` | Conditionally Required (on failure) | exception class FQN | set on TX span at `writeTransactionFailed`, on query span when `statement.execute(...)` throws |
| `server.address` / `server.port` | Recommended | from server config in server mode | embedded mode omits |
| `db.response.returned_rows` | Opt-In | omitted in YTDB-496 | requires counting traversal / result-set results |

Span name fallback examples. Gremlin: a query labeled with `g.with(YTDBQueryConfigParam.querySummary, "findActiveUsers")...` produces `findActiveUsers`. An unlabeled `g.V().hasLabel("User").has("active", true).toList()` produces `SELECT User`. SQL: `db.command("SELECT FROM User WHERE active = true")` produces `SELECT User`. `db.command("MATCH {class:User, as:u}-knows->{class:User, as:f} RETURN u, f")` produces `MATCH User`. A shape that defies classification (Gremlin `g.V().union(...).path()` or SQL `SELECT FROM (SELECT FROM ...)`) produces `youtrackdb`.

Span kinds per role: query span is CLIENT, TX span is INTERNAL, commit span is CLIENT, and no SERVER / PRODUCER / CONSUMER spans are emitted by YTDB. Track 6's lifecycle test asserts the negative case against the in-memory exporter alongside the positive-kind assertions.

### Edge cases / Gotchas

- An empty `db.query.text` (e.g., the rare case where `stringStatement` is null on the SQL path and `statement.getOriginalStatement()` also returns null) is acceptable; the attribute is Recommended, not Required.
- Classifiers MUST NOT throw. Any unexpected bytecode or `SQLStatement` subclass returns `Classification(Optional.empty(), Optional.empty())`. Gremlin tests cover at least: `V`-only, `addV`, `addE`, `drop()`, chained `hasLabel("A").hasLabel("B")` (first label wins), and `V().union(...)` (no clean classification). SQL tests cover each statement type plus FROM-with-subquery and multi-target FROM.
- `db.query.summary` cardinality stays low because the classifier output is low-cardinality. A host that sets `querySummary` to a per-request string defeats this; documented as a host responsibility.
- `db.namespace` resolution depends on the database name being readily available from the transaction context. If unavailable, the attribute is omitted, which is allowed by sem-conv ("if available").

### References
- D-records: D5, D6, D7, D8, D9
- Mechanics: none

## Span timing capture

**TL;DR.** OTel expresses span duration as `endTime - startTime`, never as an attribute. The listener API already passes the wall-clock start (`startedAtMillis`) and the monotonic duration (`executionTimeNanos`) as separate parameters of `queryFinished` and `writeTransactionCommitted`, so the OTel listener builds spans with explicit timestamps without inventing its own clock. The pattern is `setStartTimestamp(startedAtMillis, MILLISECONDS).startSpan()` then `span.end(startedAtMillis + executionTimeNanos / 1_000_000, MILLISECONDS)`, keeping the span's recorded duration aligned with what the fire site measured.

The mapping inside `OTelQueryMetricsListener.queryFinished(...)`:

```java
Span span = tracer.spanBuilder(name)
    .setSpanKind(CLIENT)
    .setStartTimestamp(startedAtMillis, TimeUnit.MILLISECONDS)
    .setParent(parentContext)  // host context or TX context from ThreadLocal
    .startSpan();
// set sem-conv attributes (db.system.name, db.query.text, ...)
span.end(startedAtMillis + executionTimeNanos / 1_000_000L, TimeUnit.MILLISECONDS);
```

Both values come from the same clock pair the fire site captured for the timing-mode snapshot. Under `LIGHTWEIGHT` the fire site reads `ticker.approximateCurrentTimeMillis()` for the start and `ticker.approximateNanoTime()` for the duration delta; under `EXACT` it reads `System.currentTimeMillis()` and `System.nanoTime()`. The listener sees a consistent pair regardless of mode, so the OTel-recorded duration never drifts from the listener-measured duration.

Implicit `now()` would be wrong here. The listener callback fires *after* the operation completes, so `tracer.spanBuilder(...).startSpan()` without an explicit timestamp would record callback-entry time as the span start — losing the relationship between the span and the actual query timing. Passing `setStartTimestamp(...)` and `span.end(endTs)` makes the span match the measured operation.

The TX-lifetime span uses implicit `now()` for `startSpan()` because `transactionStarted` fires *at* the moment of begin, with no measured start to backdate. The commit-child span inside the TX is built the same way as a query span, with `commitAtMillis` / `commitTimeNanos` from `writeTransactionCommitted` filling the timestamp slots.

### Edge cases / Gotchas

- The listener API's `startedAtMillis` is in milliseconds. Under `EXACT`, the underlying clock is `System.currentTimeMillis()` (~1 ms precision); under `LIGHTWEIGHT`, the ticker resolves at ~10 ms granularity. Sub-millisecond accuracy on the span START is not preserved; the span DURATION retains nanosecond precision because `executionTimeNanos` passes through unchanged. Trace viewers render at millisecond resolution, so this is consistent with how spans display.
- A clock skew between the fire site and the OTel SDK's exporter does not affect span duration, only the absolute placement on a wall-clock timeline. The exporter normalizes timestamps per the backend protocol.
- An OTel-compatible backend that requires strictly-monotonic timestamps within a single trace sees no violation: every YTDB span is built with `(start, end)` from one fire-site clock read, and `end > start` always holds because `executionTimeNanos > 0` for any completed operation.

### References
- D-records: D8, D14
- Invariants: Timing-mode uniformity
- Mechanics: none

## Context propagation in embedded

**TL;DR.** The host application's active span automatically becomes the parent of every YTDB query span. `Context.current()` resolves to the host's span because the listener fires synchronously on the caller's thread per existing YTDB semantics, and transaction operations are pinned to the owner thread via `assertOnOwningThread`. No extra plumbing is needed for the common case; a regression test guards against future threading changes.

The verification:
- `YTDBQueryMetricsStep.close()` calls `listener.queryFinished(...)` directly (no executor wrapping).
- `FrontendTransactionImpl.notifyMetricsListener()` (commit success and failure paths) runs on the committing thread.
- `FrontendTransactionImpl.assertOnOwningThread()` is called by every TX operation entry point (a `private` method declared at line 133, invoked from seven sites: lines 165, 224, 250, 432, 452, 474, 511).
- Result: when a host wraps a YTDB transaction inside `tracer.spanBuilder("host-op").startSpan().makeCurrent()`, `Context.current()` inside the YTDB listener returns the host's context with the host span as the active span.

The async caveat: if a future refactor moves traversal close (or commit) to a worker pool, `Context.current()` on that worker would not see the host span, and the YTDB span would attach to the root of a new trace. The test suite includes a propagation test that fails loudly if this happens; the failure mode (orphan YTDB spans) is also operator-visible in the trace viewer.

Explicit propagation is not exposed in this design. A host that needs to fan out a YTDB query to a custom executor must propagate the OTel `Context` itself per OTel's standard pattern (`Context.taskWrapping(executor)` or `Context.wrap(runnable)`). YTDB does not bridge that case.

### Edge cases / Gotchas

- A host that never opens an outer span sees the YTDB span as the trace root, with a synthetic `traceId`. This is correct OTel behavior.
- Mid-transaction context changes (host pushes a span between two queries) are observed: subsequent queries attach to the newer span. This is OTel's contract.
- The TX-lifetime span (see §"Transaction-lifetime span semantics") uses a `ThreadLocal` to carry its context to subsequent query spans rather than calling `Context.makeCurrent()`. The ThreadLocal is scoped to the TX listener instance and cleared on every TX termination path, guaranteeing no cross-transaction leakage.

### References
- D-records: D4
- Invariants: TX span boundedness
- Mechanics: none

## Transaction-lifetime span semantics

**TL;DR.** Every transaction gets one INTERNAL span covering `begin → close`. Successful write transactions add a CLIENT commit span as a child, overlapping the tail of the TX span. Failed commits set the TX span to ERROR with `error.type` populated. User rollbacks close the TX span with OK status and no commit child. Read-only transactions emit a TX span with query children and no commit child.

Lifecycle states the TX span moves through:

| Trigger | Action |
|---|---|
| `TransactionMetricsListener.transactionStarted` | Open INTERNAL span, store `(span, contextWithSpan)` in `ThreadLocal` |
| `writeTransactionCommitted` | Open CLIENT child commit span at `commitAtMillis`, end at `commitAtMillis + commitTimeNanos/1e6`; end TX span with OK; clear ThreadLocal |
| `writeTransactionFailed` | End TX span with ERROR, `error.type=<cause class FQN>`, `db.response.status_code` if available; no commit span emitted; clear ThreadLocal |
| `transactionRolledBack` | End TX span with OK (rollback is not a failure); no commit span emitted; clear ThreadLocal |
| read-only close | Fires `transactionRolledBack` per existing YTDB semantics; same behavior as above |

The ThreadLocal is the carrier for context propagation to query spans inside the transaction. It holds the OTel `Context` that has the TX span set as current, but the listener does not call `Context.makeCurrent()` because that would leak the context outside YTDB. Instead, the query listener consults a package-private accessor (`OTelTransactionMetricsListener.getActiveTxContext()`) when both listeners are co-registered.

The TX span name uses the `transactionTrackingId` from `TransactionDetails`: `"tx <trackingId>"`. The tracking ID is either client-supplied via `withTrackingId(...)` or YTDB-generated from the internal transaction ID.

### Edge cases / Gotchas

- Nested transactions: per existing YTDB semantics, `TransactionMetricsListener` does not fire for nested (reentrant) inner commits. Only the outermost transaction emits a TX span.
- A listener exception during `transactionStarted` does not register the ThreadLocal; subsequent query spans attach to `Context.current()` (host's context), losing the TX-as-parent relationship. The test suite confirms the transaction completes correctly in this case, and the operator sees orphan query spans, which is the expected fail-safe behavior.
- An OTel SDK shutdown mid-transaction causes the `end()` call to silently drop the span. Acceptable; recovery requires a host-side reconfiguration.

### References
- D-records: D3, D5
- Invariants: TX span boundedness, Span kind by role
- Mechanics: none

## Gremlin bytecode classification

**TL;DR.** The classifier walks the TinkerPop `Bytecode` instruction list to identify the start step (`V`/`E`/`addV`/`addE`/`drop`) and the first label-bearing operator (`hasLabel`, `addV(X)` argument, `addE(X)` argument). Maps the start step to an operation name and the label to a collection name. Returns `Optional.empty()` for both fields when the shape doesn't yield clean values; never throws.

Classification rules:

| First source step | Operation name | Collection name source |
|---|---|---|
| `V()` | `SELECT` | first `hasLabel(X)` argument, else `Optional.empty()` |
| `E()` | `SELECT` | first `hasLabel(X)` argument, else `Optional.empty()` |
| `addV(X)` | `INSERT` | `X` (label argument of addV) |
| `addV()` (no label) | `INSERT` | `Optional.empty()` |
| `addE(X)` | `INSERT` | `X` (label argument of addE) |
| step chain ending with `drop()` | `DELETE` | first `hasLabel(X)` argument before the drop, else `Optional.empty()` |
| anything else | `Optional.empty()` | `Optional.empty()` |

Implementation lives in `core/.../profiler/monitoring/GremlinBytecodeClassifier.java` as a static utility (`Classification classify(Bytecode)`). `YTDBQueryMetricsStep.close()` calls it directly when building the inline `QueryDetails` and stashes the returned `Classification` in two `Optional<String>` fields read back by `QueryDetails.getOperationName()` / `getCollectionName()`. When no listener consults those accessors the work is paid (the call is unconditional inside the fire site), but the cost is one bytecode walk reusing the same instruction-list traversal pattern as the existing `produceScript()` sanitization, measured in microseconds and dominated by the listener call itself.

Timing capture in `YTDBQueryMetricsStep.close()` follows the same per-TX `QueryMonitoringMode` snapshot as the SQL hook (see §"SQL execution layer hook"), so a Gremlin span and the underlying SQL spans for the same query report consistent precision.

### Edge cases / Gotchas

- `g.V().hasLabel("A").hasLabel("B")`: returns `collectionName = "A"` (first wins). This matches sem-conv guidance to capture a single low-cardinality value rather than concatenating.
- `g.V().union(__.hasLabel("A"), __.hasLabel("B"))`: returns `collectionName = Optional.empty()` because the label is inside a sub-traversal, not a top-level instruction. The classifier does not descend into sub-traversals.
- `g.addV().property("label", "X")`: returns `collectionName = Optional.empty()` because the label is not a positional argument of `addV()`. Properties are not inspected.
- Numeric or non-string `hasLabel` argument (TinkerPop allows it via mutation in untyped code): the classifier checks `instanceof String` and returns `Optional.empty()` for non-String arguments.

### References
- D-records: D9
- Invariants: none specific (the classifier is fail-safe by contract)
- Mechanics: none

## SQL execution layer hook

**TL;DR.** A private helper `executeStatementWithMetrics(SQLStatement, String, Object)` in `DatabaseSessionEmbedded`, called from both `query()` (line 617) and `executeInternal()` (line 702), funnels every native database statement (SELECT, INSERT, UPDATE, DELETE, MATCH, DDL — CREATE / ALTER / DROP for DDL). It wraps `statement.execute(...)` with mode-aware timing (per-TX `QueryMonitoringMode` snapshot: LIGHTWEIGHT default, EXACT opt-in) and emits a `QueryDetails` carrying raw text, sanitized form (literals replaced with `?` placeholders), and operation / collection extracted from the parsed AST. A thread-local `GremlinSqlSuppression` flag set by `YTDBGraphQuery.execute(...)` keeps the helper silent during Gremlin-driven SQL so one traversal emits exactly one span.

Hook anatomy in the `executeStatementWithMetrics` helper (both callers pass an already-parsed `SQLStatement` plus the raw SQL text and args):

```text
1. Read currentTx.getGlobalQueryListeners()  ← snapshot from registry (Track 1)
2. If listeners empty OR GremlinSqlSuppression.isActive():
     return statement.execute(this, args, true)         // short-circuit
   // Empty listener list → zero overhead when OTel is off.
   // Suppression active → no nested SQL span inside a Gremlin span.
3. Read mode = currentTx.getQueryMonitoringMode() (snapshot field added by Track 1; default LIGHTWEIGHT)
   if LIGHTWEIGHT:
     ticker = YouTrackDBEnginesManager.instance().getTicker()
     startMillis = ticker.approximateCurrentTimeMillis()
     startNanos  = ticker.approximateNanoTime()         // no syscalls
   else (EXACT):
     startMillis = System.currentTimeMillis()
     startNanos  = System.nanoTime()                    // two syscalls
4. Run statement.execute(this, args, true) inside the existing try/catch
5. elapsedNanos = (mode == LIGHTWEIGHT)
                    ? ticker.approximateNanoTime() - startNanos
                    : System.nanoTime() - startNanos
6. Build QueryDetails (rawSql, args, statement, trackingId), fire listeners.queryFinished(...)
   wrapped in try/catch (Throwable) so listener exceptions don't break the query
```

Both call sites do the parsing themselves before calling the helper. `query()` (line 617) parses, asserts `isIdempotent()`, then calls the helper. `executeInternal()` (line 702) uses the pre-parsed statement if its caller supplied one and otherwise calls `SQLEngine.parse(...)`, then calls the helper. The raw SQL text passed to the helper comes from `stringStatement` when non-null, else from `statement.getOriginalStatement()`. The helper itself never parses.

The `QueryDetails` impl is lazy: `getQuery()` calls `SqlSanitizer.sanitize(rawSql)` (from the OTel module) on first access; `getOperationName()` and `getCollectionName()` call `SqlSyntaxClassifier.classify(statement)` (a static utility in `core`) on first access. Hosts that don't read these accessors pay no sanitization or classification cost — the parsed `SQLStatement` is already available because `SQLEngine.parse(...)` runs unconditionally to execute the query.

Timing capture follows the per-TX `QueryMonitoringMode` snapshotted at `beginInternal()` time (Track 1 adds the snapshot field on `FrontendTransactionImpl` alongside the listener snapshot). LIGHTWEIGHT (default per the existing listener API) reads from `GranularTicker` at 10 ms granularity, with no syscall on the hot path. EXACT reads from `System.nanoTime()` / `System.currentTimeMillis()`, paying two syscalls per query for sub-millisecond precision. The same pattern lives in `FrontendTransactionImpl.doCommit` lines 650-722 for commit timing and in `YTDBQueryMetricsStep.close()` for Gremlin query timing, so a single per-TX mode setting governs every fire site in the transaction.

The Gremlin path does not double-fire. Gremlin traversals route through `session.query()`, which would otherwise re-enter the helper, but `YTDBGraphQuery.execute(...)` activates a thread-local `GremlinSqlSuppression` token (re-entrant counter, auto-closeable) for the duration of the underlying `transaction.query(...)` call. The helper checks `GremlinSqlSuppression.isActive()` at step 2 and short-circuits before any timer read or listener fire, so a Gremlin traversal emits exactly one span (the Gremlin one at `YTDBQueryMetricsStep.close()`) and no SQL children. This preserves the OTel sem-conv alignment of one user-facing operation to one span and prevents leaking the Gremlin-to-SQL translation as observable trace noise.

The `SqlSyntaxClassifier` dispatches on the `SQLStatement` subclass:

| Statement subclass | Operation name | Collection name source |
|---|---|---|
| `SQLSelectStatement` | `SELECT` | first FROM target class, else `Optional.empty()` |
| `SQLInsertStatement` | `INSERT` | INTO target class |
| `SQLUpdateStatement` | `UPDATE` | UPDATE target class |
| `SQLDeleteStatement` | `DELETE` | DELETE target class |
| `SQLMatchStatement` | `MATCH` | first pattern node's class, else `Optional.empty()` |
| `SQLCreateClassStatement` | `CREATE` | class name from the statement |
| `SQLAlterClassStatement` | `ALTER` | class name from the statement |
| `SQLDropClassStatement` | `DROP` | class name from the statement |
| anything else | `Optional.empty()` | `Optional.empty()` |

The `SqlSanitizer` runs a conservative regex pass over the raw SQL: replaces single-quoted string literals (handling escaped quotes), numeric literals, boolean literals, and date / timestamp literals with `?`. Already-parameterized text passes through unchanged because the literal patterns don't match `?` placeholders.

### Edge cases / Gotchas

- `stringStatement` can be null when an internal recursive call passes a pre-parsed `SQLStatement`. The hook falls back to `statement.getOriginalStatement()` for the raw SQL. If both are null (unusual), the hook emits `db.query.text=""` and the span still carries operation / collection.
- DDL statements have no literals to sanitize. `CREATE INDEX User.email UNIQUE` passes through `SqlSanitizer` unchanged.
- A statement with multi-target FROM (`SELECT FROM User, Order WHERE ...`) yields `collectionName = "User"` (first wins) per sem-conv guidance to keep cardinality low. An anonymous FROM subquery yields `Optional.empty()`.
- The transaction tracking ID comes from `String.valueOf(currentTx.getId())` — `FrontendTransaction.getId(): long` already exists at line 215 and returns a stable internal ID. No new accessor is added in Track 4.
- An exception thrown by `statement.execute(...)` propagates as before. The hook still fires the listener with the elapsed time and the SQL, with the span status set to ERROR and `error.type` populated, before the exception re-throws. The fire is wrapped so a listener exception during error handling doesn't mask the original.
- Under LIGHTWEIGHT (default), query durations shorter than the ticker's granularity (~10 ms) round to zero or one tick. Acceptable for trace viewers, which render at millisecond resolution anyway. A host that needs sub-millisecond precision must opt into EXACT via `YTDBTransaction.withQueryMonitoringMode(EXACT)` before that transaction begins.
- The per-TX `QueryMonitoringMode` snapshot is immutable for the transaction's lifetime. `YTDBTransaction.withQueryMonitoringMode(...)` called mid-TX (after the TX has begun) mutates the YTDBTransaction field but does not affect the active TX's snapshot, so the call takes effect on the next `begin()` cycle. This keeps the Gremlin step, SQL hook, and commit timer reading from one frozen source for the TX's duration, satisfying the Timing-mode uniformity invariant without per-iteration re-reads.
- Gremlin SQL suppression is a thread-local counter (re-entrant, not a boolean). `YTDBGraphQuery.execute(session)` wraps the underlying `transaction.query(...)` call in a try-with-resources `GremlinSqlSuppression.activate()` token; nested Gremlin steps inside one another increment / decrement the counter. The helper checks `GremlinSqlSuppression.isActive()` (counter > 0) before any timer read or listener fire. Counter scope is thread-local, so concurrent transactions on different threads do not interfere. Cleanup runs in `AutoCloseable.close()` even if the SQL call throws, so an exception inside Gremlin does not leak the suppression state to the next operation on that thread.

### References
- D-records: D8, D9
- Invariants: TX span boundedness, Listener exception isolation, Timing-mode uniformity (every fire site in a transaction uses the snapshot mode), Gremlin span uniqueness (one Gremlin traversal emits one span; no SQL children)
- Mechanics: none

## SDK lifecycle: embedded vs server

**TL;DR.** Hybrid ownership model. The host owns the `OpenTelemetry` instance when it has wired one (either explicitly through the setter or globally via `GlobalOpenTelemetry.set(...)`). When `OPENTELEMETRY_ENABLED=true` and the host has wired nothing, YTDB auto-configures its own SDK from `OPENTELEMETRY_*` config entries — the same path server mode takes. The facade tracks ownership so `shutdown()` closes only the SDK YTDB created. In server mode YTDB always owns the SDK because the server is a standalone process.

Embedded path — three-step resolution on first listener fire:

1. **Explicit setter wins**: if the host called `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)`, use that instance. Ownership = host. `shutdown()` will not close it.
2. **GlobalOpenTelemetry fallback**: if the host called `GlobalOpenTelemetry.set(otel)` somewhere in its bootstrap (the standard OTel pattern), `GlobalOpenTelemetry.get()` returns the real SDK. Use that instance. Ownership = host. `shutdown()` will not close it.
3. **YTDB auto-configure**: if neither of the above produced a real SDK and `OPENTELEMETRY_ENABLED=true`, the facade builds an `OpenTelemetrySdk` via `AutoConfiguredOpenTelemetrySdk` using the `OPENTELEMETRY_*` config entries (endpoint, protocol, service name). Ownership = YTDB. `shutdown()` closes this SDK.

If `OPENTELEMETRY_ENABLED=false` (default), step 3 is skipped and the facade returns no-op tracer; YTDB emits nothing regardless of any host wiring. The flag is the master switch.

Server path:

1. `ServerMain.create()` builds a `YouTrackDBServer` and calls `activate()`.
2. The server discovers `ServerLifecycleListener` implementations via the `ServiceLoader.load(ServerLifecycleListener.class)` call Track 5 adds to `YouTrackDBServer.activate()` (the existing code only honors explicit `registerLifecycleListener(...)` calls), appends them to the existing `lifecycleListeners` list, and calls `onBeforeActivate` on each.
3. After databases load, the server calls `onAfterActivate` on each lifecycle listener.
4. `OpenTelemetryServerPlugin.onAfterActivate()` reads config: if `OPENTELEMETRY_ENABLED=true`, builds an `AutoConfiguredOpenTelemetrySdk` with the configured endpoint, protocol, and service name; calls `YouTrackDBOpenTelemetry.setOpenTelemetry(sdk.getOpenTelemetrySdk())` with ownership=YTDB (the plugin signals server-mode ownership through an internal method variant).
5. Transactions run, spans emit.
6. On shutdown, `server.shutdown()` calls `onBeforeDeactivate` on every plugin. `OpenTelemetryServerPlugin.onBeforeDeactivate()` calls `YouTrackDBOpenTelemetry.shutdown()`, which unregisters listeners and closes the SDK (`OpenTelemetrySdk.close()` flushes pending spans).

Idempotence and ownership transitions:
- `setOpenTelemetry` called when YTDB has already auto-configured its own SDK: the facade closes the YTDB-owned SDK first, then installs the host's instance and flips ownership to host.
- `setOpenTelemetry` called twice with host-owned SDKs in a row: the facade does not close anything (host owns lifecycle for both); it just swaps the reference and re-registers listeners against the new tracer.
- `shutdown` called twice is a no-op the second time.
- An exception during SDK shutdown is logged but does not propagate.

### Edge cases / Gotchas

- A host that sets `OPENTELEMETRY_ENABLED=true` without wiring any OTel sees YTDB open a network connection to the configured endpoint (default `http://localhost:4317`) on first listener fire. The flag is an explicit opt-in so this is acceptable; the auto-configure path logs one INFO line with the resolved endpoint so the operator sees what happened. Hosts that do not want this MUST keep `OPENTELEMETRY_ENABLED=false`.
- Embedded host that calls `YouTrackDB.close()` without calling `YouTrackDBOpenTelemetry.shutdown()`: when ownership is YTDB the SDK keeps running until the JVM exits and OTel's own shutdown hook flushes pending spans. When ownership is host, the SDK was never ours to close.
- Server with `OPENTELEMETRY_ENABLED=false` (default): the plugin's `onAfterActivate` returns immediately. Zero OTel runtime cost. The listeners never register.
- Race on auto-configure: the facade synchronizes the resolution chain so two concurrent first-listener-fires produce exactly one SDK build.
- An invalid endpoint in `OPENTELEMETRY_EXPORTER_ENDPOINT`: the autoconfigure builder fails fast with a clear log message. In server mode the server does not start; in embedded mode the listener fire fails once (the exception is caught by the isolation wrapper), and subsequent fires re-attempt resolution (which fails the same way until the operator fixes config).

### References
- D-records: D2
- Mechanics: none

## Listener registration and ordering

**TL;DR.** A process-global registry in `core` (a pair of `CopyOnWriteArrayList`s for query and transaction listeners, exposed via static methods on `YourTracks`) holds listeners installed before transactions begin. `FrontendTransactionImpl.beginInternal()` snapshots the registry into per-TX fields before `txStartCounter` increments, and the transaction uses that snapshot for its lifetime. `YTDBTransaction.doOpen()` delegates to `beginInternal()`, so Gremlin and native-SQL transactions share the same snapshot site. Per-TX `withQueryListener` continues to work, adding listeners on top of the snapshot for that transaction only. The OTel module installs its listeners through this registry; nothing about the existing per-TX API changes.

Ordering rules:
- Within the registry, listeners fire in insertion order.
- For a given transaction, registry-snapshot listeners fire before per-TX listeners added via `withQueryListener`.
- A listener registered after a transaction has begun is not seen by that transaction; it takes effect from the next transaction onward.

Registration API additions — static methods on the existing `final` utility class `YourTracks` (the registry is process-global; the `YouTrackDB` interface gets no new methods, keeping `YouTrackDBRemote` and other implementors untouched):

```java
public static void registerGlobalQueryListener(QueryMetricsListener listener);
public static void unregisterGlobalQueryListener(QueryMetricsListener listener);
public static void registerGlobalTransactionListener(TransactionMetricsListener listener);
public static void unregisterGlobalTransactionListener(TransactionMetricsListener listener);
```

`YouTrackDBOpenTelemetry.setOpenTelemetry(otel)` registers a `OTelQueryMetricsListener` and an `OTelTransactionMetricsListener` instance. Subsequent `setOpenTelemetry` calls first `unregister` the previously-registered instances before registering fresh ones tied to the new SDK. `YouTrackDBOpenTelemetry.shutdown()` unregisters them.

### Edge cases / Gotchas

- Concurrent `register` and transaction `doOpen`: the registry uses `CopyOnWriteArrayList`, so a `doOpen` reads a consistent snapshot even if a `register` is in flight. The behavior is "either the listener is in the snapshot or it isn't", deterministic per transaction.
- A listener implementation that holds a reference to the YTDB transaction: the listener outlives the transaction (it lives in the global registry). The listener MUST NOT cache transaction-scoped state; if it needs per-TX state, use `TransactionDetails.getTransactionTrackingId()` as the keying value.
- Duplicate registration of the same listener instance: the registry deduplicates by reference identity. A second `register` of the same instance is a no-op.

### References
- D-records: D1
- Invariants: TX span boundedness
- Mechanics: none

## Exception isolation contract

**TL;DR.** Every listener callback fires inside a try/catch in the YTDB firing site. A throw from any listener (a bug, a misconfigured OTel SDK, an OOM in span allocation) is logged at WARN and swallowed, so the transaction lifecycle continues. The protection extends to the two new methods `transactionStarted` and `transactionRolledBack` introduced in Track 1.

Current isolation sites:
- `YTDBQueryMetricsStep.close()` wraps `listener.queryFinished(...)` in `try { ... } catch (Exception e) { ... }` today (line 148). Track 1 widens the catch to `Throwable` and changes the call to iterate the per-TX listener snapshot.
- `FrontendTransactionImpl.notifyMetricsListener()` (line 712) wraps the commit success and failure paths in `try { ... } catch (Exception e) { ... }` today (line 730). Track 1 widens to `Throwable` and iterates the snapshot.

New isolation sites in this design (all in `FrontendTransactionImpl` so the existing private wrapper shape is reused without a hoist):
- `FrontendTransactionImpl.beginInternal()` wraps the loop calling `listener.transactionStarted(...)` per registered listener.
- `FrontendTransactionImpl.rollbackInternal()` wraps the loop calling `listener.transactionRolledBack(...)` per registered listener, gated by `txStartCounter == 0` so nested rollbacks don't double-fire.

The wrapper catches `Throwable` (Track 1 widens both existing wrappers from `Exception` to `Throwable`), because an `Error` from an OOM during span allocation would otherwise unwind the transaction. The log entry uses the listener class name to point the operator at the responsible component.

When one listener in the snapshot throws, subsequent listeners in the iteration still fire. This is important because the OTel listener may be installed alongside a custom host listener; a bug in the host listener must not prevent OTel emission, and vice versa.

### Edge cases / Gotchas

- A listener that throws on every call generates one log line per call. At LDBC benchmark loads (~10k qps) this is a log flood. Acceptable in YTDB-496; if it becomes operationally painful, a follow-up adds rate limiting on the log.
- The TX span's ThreadLocal is set inside the try/catch wrapper. If `transactionStarted` throws after the ThreadLocal is set but before the listener returns, the ThreadLocal remains set for the duration of the transaction. The `clear` paths in `writeTransactionCommitted` / `writeTransactionFailed` / `transactionRolledBack` run inside their own try/catch and clear the ThreadLocal in a `finally`.
- An exception in `OpenTelemetrySdk.close()` during server shutdown is logged but not propagated; the server shutdown completes regardless.

### References
- D-records: D11 (wrapper widened from `Exception` to `Throwable`)
- Invariants: Listener exception isolation
- Mechanics: none
