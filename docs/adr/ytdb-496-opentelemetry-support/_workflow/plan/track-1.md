# Track 1: Foundation extension in `core` for OTel-readiness

## Purpose / Big Picture

After this track lands, `core` carries the four SPI surfaces the OTel module needs: a global listener registry on `YouTrackDB`, two new no-op default methods on `TransactionMetricsListener` (begin and rollback), and two new accessors on `QueryDetails`. No behavior changes for existing users.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Extend the existing listener SPI with the hooks the OTel module needs: two new default-no-op methods on `TransactionMetricsListener` (begin / rollback), a global listener registry on `OYouTrackDB`, and `QueryDetails` accessors for the bytecode-derived operation/collection. No behavior change yet; every existing call path stays green.

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

- `internal/common/profiler/monitoring/QueryMetricsListener.java` carries the existing `QueryDetails` nested interface and the single `queryFinished` callback.
- `internal/common/profiler/monitoring/TransactionMetricsListener.java` carries the two existing default no-op methods `writeTransactionCommitted` and `writeTransactionFailed`.
- `internal/common/profiler/monitoring/YTDBQueryMetricsStep.java` is the TinkerPop step injected by `YTDBQueryMetricsStrategy`. Its `close()` method (around line 116) builds an anonymous `QueryDetails` and fires the listener.
- `internal/core/tx/YTDBTransaction.java` is the public-facing transaction. `doOpen()` (around line 154) calls `activeSession.begin()`; `doRollback()` (around line 186) calls `activeSession.rollback()`.
- `api/YourTracks.java` and the public `YouTrackDB` interface are the factory entry points the host application uses.

The track touches none of the actual emission code (that lives in Track 3). It adds API surfaces and wires the transaction factory to consult the global registry when constructing a new `YTDBTransaction`. Concrete deliverables:

1. Two new default-no-op methods on `TransactionMetricsListener`: `transactionStarted(TransactionDetails)` fired from `YTDBTransaction.doOpen()`; `transactionRolledBack(TransactionDetails)` fired from `YTDBTransaction.doRollback()`.
2. Two new accessors on `QueryDetails`: `Optional<String> getOperationName()` and `Optional<String> getCollectionName()`. Both default to `Optional.empty()` in the existing inline implementation so unrelated tests keep passing; `YTDBQueryMetricsStep` populates them only when the classifier resolves a value (the classifier itself lands in Track 3, this track exposes the slot).
3. A global listener registry on `YouTrackDB`: `registerGlobalQueryListener(QueryMetricsListener)`, `unregisterGlobalQueryListener(QueryMetricsListener)`, and the matching pair for `TransactionMetricsListener`. The registry is a `CopyOnWriteArrayList` so reads are lock-free.
4. Snapshot wiring in the transaction factory: when `YTDBTransaction` is constructed, it copies the current registry snapshot into its own per-TX listener list. Per-TX `withQueryListener` adds on top of the snapshot.
5. Exception isolation extension: the existing try/catch around `writeTransactionCommitted` / `writeTransactionFailed` (in `FrontendTransactionImpl`) is extended to wrap the new `transactionStarted` and `transactionRolledBack` calls.

## Plan of Work

The track lands in five edits, sequenced so each commit leaves the test suite green.

The first edit adds the two new methods to `TransactionMetricsListener` as `default` no-ops. Because both have default implementations, every existing call site and every existing test class compiles unchanged.

The second edit extends `QueryDetails` with the two `Optional<String>` accessors as default methods returning `Optional.empty()`. The inline impl in `YTDBQueryMetricsStep` does not need to override yet.

The third edit creates the global registry. The natural home is a static holder under `internal/common/profiler/monitoring/` (kept package-private with public accessor methods on `YouTrackDB` and `YourTracks`). Reading the registry is a `List.copyOf(...)` snapshot to avoid concurrent modification during transaction construction.

The fourth edit wires `YTDBTransaction`'s constructor to take the registry snapshot. It also wires `doOpen()` to fire `transactionStarted` for every listener in the snapshot, and `doRollback()` to fire `transactionRolledBack`. The two new fire sites reuse the exception-isolation wrapper from `FrontendTransactionImpl.notifyMetricsListener`.

The fifth edit adds JUnit 4 tests under `core/src/test/java/.../profiler/monitoring/`: a test class proving that registering a listener globally causes it to receive begin / commit / rollback callbacks for a transaction, and a test class proving that an exception thrown from any listener method does not break the transaction.

Ordering matters because the registry is consumed by the transaction constructor. The first two edits are independent and could swap order without consequence; the third must precede the fourth; the fifth follows the fourth.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 1, the following behaviors hold:

- Registering a `QueryMetricsListener` via `YouTrackDB.registerGlobalQueryListener(listener)` causes that listener to receive `queryFinished` callbacks for every subsequent query in every newly-opened transaction. Listeners registered after a transaction is open do not fire for that transaction.
- Registering a `TransactionMetricsListener` via the matching global method causes that listener to receive `transactionStarted`, then exactly one of `writeTransactionCommitted`, `writeTransactionFailed`, or `transactionRolledBack` for every newly-opened transaction.
- An exception thrown from any listener callback does not propagate to the application. The transaction continues; subsequent listeners in the registry still fire.
- Per-TX `withQueryListener` continues to work for transactions that have been started, adding listeners on top of the global-registry snapshot.
- `QueryDetails.getOperationName()` and `getCollectionName()` return `Optional.empty()` for every query (the classifier wiring lands in Track 3).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java`
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/TransactionMetricsListener.java`
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java` (slot enrichment only; classifier in Track 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/YTDBTransaction.java`
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (extend existing try/catch wrappers)
- `core/src/main/java/com/jetbrains/youtrackdb/api/YouTrackDB.java`
- `core/src/main/java/com/jetbrains/youtrackdb/api/YourTracks.java`
- New static holder file under `internal/common/profiler/monitoring/` for the global registry.

Out of scope:
- Every file in `server/`, `embedded/`, `tests/`. Foundation does not need to know about server lifecycle or embedded host.
- The OTel module (does not exist yet, lands in Track 2).
- The Gremlin bytecode classifier implementation (Track 3 owns it; this track only exposes the `QueryDetails` accessor slot).

Inter-track dependencies:
- Provides for Track 3: the listener SPI extensions and the `QueryDetails` accessor slots.
- Provides for Track 4: the `QueryClassifier` SPI slot the SQL classifier registers through.
- Provides for Track 5: the global registry SPI used by `YouTrackDBOpenTelemetry.setOpenTelemetry()` to install the OTel listeners.

Library / function signatures introduced:

```java
public interface TransactionMetricsListener {
  default void transactionStarted(TransactionDetails txDetails) {}
  default void transactionRolledBack(TransactionDetails txDetails) {}
  // existing methods unchanged
}

public interface QueryDetails {
  default Optional<String> getOperationName() { return Optional.empty(); }
  default Optional<String> getCollectionName() { return Optional.empty(); }
  // existing methods unchanged
}

// on YouTrackDB / YourTracks
void registerGlobalQueryListener(QueryMetricsListener listener);
void unregisterGlobalQueryListener(QueryMetricsListener listener);
void registerGlobalTransactionListener(TransactionMetricsListener listener);
void unregisterGlobalTransactionListener(TransactionMetricsListener listener);
```
