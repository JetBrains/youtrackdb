# Track 1: Foundation extension in `core` for OTel-readiness

## Purpose / Big Picture

After this track lands, `core` carries the SPI surfaces the OTel module needs: a process-global listener registry exposed as static methods on `YourTracks`, two new default no-op methods on `TransactionMetricsListener` (begin and rollback), three new default accessors on `QueryMetricsListener.QueryDetails` (operation / collection / namespace), one new accessor on `TransactionMetricsListener.TransactionDetails` (namespace), a `Classification` value record plus two static-utility classifiers (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) called directly from the existing fire sites. The TX lifecycle fires consolidate inside `FrontendTransactionImpl.beginInternal()` / `rollbackInternal()` so both Gremlin and native-SQL paths emit the same callbacks. The existing exception-isolation wrappers widen from `Exception` to `Throwable`. No behavior changes for transactions without registered listeners.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Extend the existing listener SPI with the hooks the OTel module needs: two new default-no-op methods on `TransactionMetricsListener` (begin / rollback), a process-global listener registry exposed as static methods on `YourTracks`, accessors on the nested `QueryDetails` and `TransactionDetails` interfaces for the classifier-derived operation/collection plus the database name, two static-utility classifier classes (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) and a `Classification` value record living in `core` next to the existing parsing code, and a strategy-gate update so globally-registered listeners actually receive callbacks. No behavior change yet; every existing call path stays green.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

The relevant code lives entirely under `core/src/main/java/com/jetbrains/youtrackdb/`:

- `internal/common/profiler/monitoring/QueryMetricsListener.java` carries the existing nested interface `QueryMetricsListener.QueryDetails` and the single `queryFinished` callback. Today `queryFinished` fires only when `ytdbTx.getQueryMetricsListener()` is non-NO_OP — a single per-TX listener.
- `internal/common/profiler/monitoring/TransactionMetricsListener.java` carries the existing nested `TransactionMetricsListener.TransactionDetails` interface and two existing default no-op methods (`writeTransactionCommitted`, `writeTransactionFailed`).
- `internal/common/profiler/monitoring/YTDBQueryMetricsStep.java` is the TinkerPop step injected by `YTDBQueryMetricsStrategy`. Its `close()` method (around line 116) builds an anonymous `QueryDetails` and fires the listener inside a `try { ... } catch (Exception e) { ... }` (line 148). The step itself is injected only when `YTDBQueryMetricsStrategy.apply()` sees `ytdbTx.isQueryMetricsEnabled()` return true — and that method returns true only when a per-TX `withQueryListener` was set, so a globally-registered listener with no per-TX wiring never sees the step at all.
- `internal/core/gremlin/YTDBTransaction.java` is the Gremlin-side TX wrapper (a `final` subclass of TinkerPop's `AbstractTransaction`). Its `doOpen()` (line 154) calls `activeSession.begin()`; `doRollback()` (line 186) calls `activeSession.rollback()`. Both delegate to the underlying `FrontendTransactionImpl`. This track does not touch `YTDBTransaction` — the new TX-lifecycle fires live in `FrontendTransactionImpl` so both Gremlin and native-SQL paths emit them.
- `internal/core/tx/FrontendTransactionImpl.java` is the underlying TX manager. `beginInternal()` (line 164) and `rollbackInternal()` (line 356) are the single chokepoints for every TX begin and rollback in the codebase (Gremlin delegates here; `DatabaseSessionEmbedded` calls them directly from six call sites). The existing private `notifyMetricsListener()` (line 712) carries an `Exception`-only try/catch wrapper for commit success/failure fires.
- `api/YourTracks.java` is a `final` utility class with a private constructor and only static factory methods. The public `YouTrackDB` interface is the factory product. The process-global registry exposes static methods on `YourTracks` only; the `YouTrackDB` interface gets no new methods (so `YouTrackDBRemote` and any other implementors keep compiling unchanged).

The track touches none of the actual emission code (that lives in Track 3). It adds API surfaces, consolidates the TX-lifecycle fires inside `FrontendTransactionImpl`, refactors the existing single-listener call sites to iterate the per-TX snapshot, and widens the existing exception-isolation wrappers from `Exception` to `Throwable`. Concrete deliverables:

1. Two new default-no-op methods on `TransactionMetricsListener`: `transactionStarted(TransactionDetails)` and `transactionRolledBack(TransactionDetails)`. Fires happen in `FrontendTransactionImpl.beginInternal()` (after the `txStartCounter == 0` outermost branch enters BEGUN status) and `rollbackInternal()` (gated by `txStartCounter == 0` so nested rollbacks don't double-fire).
2. Three new default accessors on `QueryMetricsListener.QueryDetails`: `Optional<String> getOperationName()`, `Optional<String> getCollectionName()`, `Optional<String> getDatabaseName()`. All default to `Optional.empty()` so existing inline implementations keep working; `YTDBQueryMetricsStep` populates them only when the classifier resolves a value (the classifier itself lands in Track 3, this track exposes the slot) and from `session.getDatabaseName()` for the namespace.
3. One new default accessor on `TransactionMetricsListener.TransactionDetails`: `Optional<String> getDatabaseName()`. Populated at the fire site in `FrontendTransactionImpl` from `session.getDatabaseName()`.
4. A `Classification` value record plus two static-utility classifier classes (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) in `core/.../profiler/monitoring/`. Each classifier exposes one static method (`Classification classify(Bytecode)` / `Classification classify(SQLStatement)`); the fire sites call them directly. No SPI, no `ServiceLoader`, no `META-INF/services` manifest — the parsing logic the classifiers contain is already happening at the fire sites (`produceScript()` already walks the bytecode for Gremlin sanitization; `SQLEngine.parse(...)` already produces the AST for SQL execution), so piggybacking on existing parsing costs almost nothing and avoids a plugin layer for code that has exactly one impl per input type.
5. A process-global listener registry: a package-private static holder under `internal/common/profiler/monitoring/` (e.g., `GlobalListenerRegistry`) with `CopyOnWriteArrayList` per listener type, exposed via static methods on `YourTracks`: `registerGlobalQueryListener(QueryMetricsListener)`, `unregisterGlobalQueryListener(QueryMetricsListener)`, plus the transaction-listener pair.
6. Snapshot wiring at TX begin: `FrontendTransactionImpl.beginInternal()` captures the global registry snapshots (`List<QueryMetricsListener>` and `List<TransactionMetricsListener>`) and the active `QueryMonitoringMode` into new fields before incrementing `txStartCounter` so nested begins reuse the outermost snapshot. Default mode is `LIGHTWEIGHT`; `YTDBTransaction.withQueryMonitoringMode(EXACT)` propagates the override into the snapshot via a new package-private setter on `FrontendTransactionImpl`. A new `getQueryMonitoringMode()` accessor on the `FrontendTransaction` interface lets the SQL hook in Track 4 read the snapshotted mode from inside `DatabaseSessionEmbedded.executeInternal()`. The fields are cleared on outermost commit / rollback.
7. Strategy gate update: `YTDBQueryMetricsStrategy.apply()` (declared at line 23; the gate check at line 36) and `YTDBTransaction.isQueryMetricsEnabled()` widen to also return true when the global query snapshot is non-empty, so a globally-registered listener actually causes the step to inject.
8. Existing single-listener call sites refactored to iterate the per-TX snapshot: `YTDBQueryMetricsStep.close()` (fires `queryFinished` against every registered query listener instead of a single per-TX one), `FrontendTransactionImpl.notifyMetricsListener()` (same for commit success/failure). Per-TX `withQueryListener` continues to work by inserting one entry into the same snapshot.
9. Exception-isolation widening: the existing `} catch (Exception e) {` blocks in `FrontendTransactionImpl.notifyMetricsListener:730` and `YTDBQueryMetricsStep:148` widen to `} catch (Throwable t) {`. The two new TX-lifecycle fires reuse the same wrapper shape in `FrontendTransactionImpl`.

## Plan of Work

The track lands in eight edits, sequenced so each commit leaves the test suite green.

The first edit adds the two new methods to `TransactionMetricsListener` as `default` no-ops, plus the `getDatabaseName()` default accessor on the nested `TransactionDetails`. Because both have default implementations, every existing call site and every existing test class compiles unchanged.

The second edit extends `QueryMetricsListener.QueryDetails` with the three `Optional<String>` accessors (`getOperationName`, `getCollectionName`, `getDatabaseName`) as default methods returning `Optional.empty()`. The inline impl in `YTDBQueryMetricsStep` does not need to override yet.

The third edit defines the `Classification` record plus the two static-utility classifier classes under `internal/common/profiler/monitoring/`. The record is `(Optional<String> operationName, Optional<String> collectionName)` with a `Classification.EMPTY` constant. `GremlinBytecodeClassifier` exposes `static Classification classify(Bytecode bytecode)`, walking the instruction list to extract the start step and the first `hasLabel`/`addV`/`addE` label. `SqlSyntaxClassifier` exposes `static Classification classify(SQLStatement statement)`, dispatching on the AST subclass. Both helpers are pure functions, fail-safe (any unexpected input returns `Classification.EMPTY`), and unit-testable in isolation without spinning up a transaction. Track 3 wires the Gremlin classifier into `YTDBQueryMetricsStep.close()`; Track 4 wires the SQL classifier into `DatabaseSessionEmbedded.executeInternal()`.

The fourth edit creates the process-global registry: a package-private static holder (e.g., `GlobalListenerRegistry`) under `internal/common/profiler/monitoring/` with a `CopyOnWriteArrayList` per listener type, plus `snapshotQueryListeners()` / `snapshotTransactionListeners()` returning immutable `List.copyOf(...)` snapshots. `YourTracks` gains four public static methods (`registerGlobalQueryListener` / `unregisterGlobalQueryListener` plus the transaction-listener pair) that delegate to the holder.

The fifth edit wires `FrontendTransactionImpl.beginInternal()` to capture the global query and transaction snapshots into two new fields before `txStartCounter` increments, plus a third field holding the active `QueryMonitoringMode` (default `LIGHTWEIGHT`). A package-private setter `setQueryMonitoringModeSnapshot(QueryMonitoringMode)` lets `YTDBTransaction.withQueryMonitoringMode(...)` propagate the per-TX override into the snapshot. A public `getQueryMonitoringMode()` accessor on `FrontendTransaction` returns the snapshotted mode for downstream consumers (Track 4's SQL hook reads it from `currentTx`). The same method then iterates the transaction snapshot calling `transactionStarted(details)` against each listener, inside the existing exception-isolation wrapper shape. `rollbackInternal()` iterates the snapshot calling `transactionRolledBack(details)` when `txStartCounter == 0` (so nested rollbacks don't double-fire). On outermost commit / rollback the snapshots clear.

The sixth edit updates the strategy gate: `YTDBQueryMetricsStrategy.apply()` (declared at line 23; the gate check at line 36) and `YTDBTransaction.isQueryMetricsEnabled()` widen the gate to also return true when the global query snapshot is non-empty. Without this edit a globally-registered listener never sees `queryFinished` because the step is never injected.

The seventh edit refactors the single-listener call sites: `YTDBQueryMetricsStep.close()` now iterates every query listener in the per-TX snapshot (which includes any per-TX `withQueryListener` plus the global snapshot taken at begin), and `FrontendTransactionImpl.notifyMetricsListener()` iterates the transaction snapshot for commit success/failure. Each per-iteration call sits inside the existing try/catch wrapper, widened to `Throwable`.

The eighth edit adds JUnit 4 tests under `core/src/test/java/.../profiler/monitoring/`: a test class proving that registering a listener globally causes it to receive begin / commit / rollback callbacks for a transaction (covering both Gremlin and native-SQL paths through one shared fire site), a test class proving that `Throwable` thrown from any listener method does not break the transaction, and a test class proving that listeners registered after a transaction begins are not seen by that transaction.

Ordering: edits 1, 2, 3 are independent (any order). Edit 4 must precede edits 5, 6, 7. Edit 5 precedes edit 7 (snapshot field exists). Edit 6 is independent of edit 5 but precedes edit 8. Edit 8 last.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 1, the following behaviors hold:

- Registering a `QueryMetricsListener` via `YourTracks.registerGlobalQueryListener(listener)` causes that listener to receive `queryFinished` callbacks for every subsequent query in every newly-opened transaction, on both the Gremlin path (`YTDBQueryMetricsStep.close()`) and (once Track 4 wires it in) the native-SQL path. Listeners registered after a transaction is open do not fire for that transaction.
- Registering a `TransactionMetricsListener` via `YourTracks.registerGlobalTransactionListener(listener)` causes that listener to receive `transactionStarted` (fired from `FrontendTransactionImpl.beginInternal()` on the outermost begin), then exactly one of `writeTransactionCommitted` / `writeTransactionFailed` / `transactionRolledBack` for every newly-opened transaction, regardless of whether it originated from Gremlin or native SQL.
- Nested begins do not emit a second `transactionStarted` and nested rollbacks do not emit a second `transactionRolledBack` — both fires are gated by `txStartCounter == 0`.
- A `Throwable` (including `Error`) thrown from any listener callback is caught and logged at WARN; the transaction continues; subsequent listeners in the snapshot still fire.
- Per-TX `withQueryListener` continues to work for transactions that have been started, adding listeners on top of the global-registry snapshot.
- `QueryMetricsListener.QueryDetails.getOperationName()`, `getCollectionName()`, and `getDatabaseName()` are reachable; the first two return `Optional.empty()` until Track 3 / Track 4 wire classifiers; `getDatabaseName()` returns the session's database name when the fire site populates it, else `Optional.empty()`.
- `YTDBQueryMetricsStrategy.apply()` injects `YTDBQueryMetricsStep` when the global query snapshot is non-empty even without a per-TX `withQueryListener` call.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java` (extend nested `QueryDetails`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/TransactionMetricsListener.java` (extend nested `TransactionDetails`; add two new no-op methods on the outer interface)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java` (slot enrichment, iterate per-TX snapshot at `close()`, widen catch to `Throwable`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStrategy.java` (gate widening so the step injects when the global query snapshot is non-empty)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (snapshot capture in `beginInternal` for listeners + `QueryMonitoringMode`, lifecycle fires in `beginInternal` + `rollbackInternal`, iterate transaction snapshot in `notifyMetricsListener`, widen wrappers to `Throwable`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` (one new accessor `getQueryMonitoringMode(): QueryMonitoringMode` returning the snapshotted mode; default no new mutator on the interface — the snapshot setter is package-private on the impl)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBTransaction.java` (`withQueryMonitoringMode` propagates the mode into the underlying `FrontendTransactionImpl` snapshot via the new package-private setter; existing `getQueryMonitoringMode()` accessor unchanged)
- `core/src/main/java/com/jetbrains/youtrackdb/api/YourTracks.java` (four new static methods delegating to the new registry holder)
- New `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/GlobalListenerRegistry.java` (package-private holder; `CopyOnWriteArrayList` per listener type plus snapshot methods)
- New `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/Classification.java` (small value record + `EMPTY` constant)
- New `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/GremlinBytecodeClassifier.java` (static utility — `Classification classify(Bytecode)`)
- New `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/SqlSyntaxClassifier.java` (static utility — `Classification classify(SQLStatement)`)

Out of scope:
- `core/src/main/java/com/jetbrains/youtrackdb/api/YouTrackDB.java` — no new methods; the registry is process-global on `YourTracks` only (CR9 resolution).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBTransaction.java` `doOpen()` / `doRollback()` — untouched; the new TX-lifecycle fires live in `FrontendTransactionImpl` so both Gremlin and native-SQL paths emit them. (Note: `YTDBTransaction.withQueryMonitoringMode(...)` IS touched to propagate the mode into the underlying snapshot — see In-scope above.)
- Every file in `server/`, `embedded/`, `tests/`. Foundation does not need to know about server lifecycle or embedded host.
- The OTel module (does not exist yet, lands in Track 2).
- The fire-site wiring that calls the classifiers (Track 3 wires `GremlinBytecodeClassifier` into `YTDBQueryMetricsStep.close()`; Track 4 wires `SqlSyntaxClassifier` into `DatabaseSessionEmbedded.executeInternal()`). Track 1 ships the classifier helpers themselves; the consumer tracks wire them at the fire sites.

Inter-track dependencies:
- Provides for Track 3: the listener SPI extensions, the `QueryDetails` / `TransactionDetails` accessor slots, the `GremlinBytecodeClassifier` static helper called by `YTDBQueryMetricsStep.close()` to populate operation/collection on the inline `QueryDetails`, the iteration shape in `YTDBQueryMetricsStep.close()`.
- Provides for Track 4: the `SqlSyntaxClassifier` static helper called by `DatabaseSessionEmbedded.executeInternal()` to populate operation/collection on the inline SQL `QueryDetails`; the global query snapshot accessible from `currentTx`; the `currentTx.getQueryMonitoringMode()` accessor returning the snapshotted `LIGHTWEIGHT` / `EXACT` value the SQL hook uses to route timing capture (LIGHTWEIGHT → `GranularTicker`, EXACT → `System.nanoTime()`).
- Provides for Track 5: the global registry static methods used by `YouTrackDBOpenTelemetry.setOpenTelemetry()` to install the OTel listeners.

Library / function signatures introduced:

```java
// existing nested interface, three new default accessors
public interface QueryMetricsListener {
  interface QueryDetails {
    default Optional<String> getOperationName() { return Optional.empty(); }
    default Optional<String> getCollectionName() { return Optional.empty(); }
    default Optional<String> getDatabaseName() { return Optional.empty(); }
    // existing methods unchanged
  }
  // existing method unchanged
}

// existing nested interface, one new default accessor; two new outer methods
public interface TransactionMetricsListener {
  interface TransactionDetails {
    default Optional<String> getDatabaseName() { return Optional.empty(); }
    // existing methods unchanged
  }
  default void transactionStarted(TransactionDetails txDetails) {}
  default void transactionRolledBack(TransactionDetails txDetails) {}
  // existing methods unchanged
}

// new value record + two static-utility classifiers in core
public record Classification(
    Optional<String> operationName,
    Optional<String> collectionName) {
  public static final Classification EMPTY =
      new Classification(Optional.empty(), Optional.empty());
}

public final class GremlinBytecodeClassifier {
  private GremlinBytecodeClassifier() {}
  public static Classification classify(Bytecode bytecode);  // never throws; returns EMPTY for unrecognized shapes
}

public final class SqlSyntaxClassifier {
  private SqlSyntaxClassifier() {}
  public static Classification classify(SQLStatement statement);  // never throws; returns EMPTY for unrecognized shapes
}

// static methods on the existing final utility class YourTracks
public static void registerGlobalQueryListener(QueryMetricsListener listener);
public static void unregisterGlobalQueryListener(QueryMetricsListener listener);
public static void registerGlobalTransactionListener(TransactionMetricsListener listener);
public static void unregisterGlobalTransactionListener(TransactionMetricsListener listener);
```
