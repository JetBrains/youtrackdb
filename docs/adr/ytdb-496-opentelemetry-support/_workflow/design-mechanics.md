<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# YTDB-496 OpenTelemetry support — Mechanics

Implementation detail for the sections listed below. Each section gathers the full pseudo-implementation, mapping tables, and edge cases an implementer needs. The text stands on its own; concepts named here either define themselves locally or appear under the same name in the companion polished view.

YTDB means YouTrackDB. SDK means OpenTelemetry software development kit. TX means transaction.
Sem-conv means OpenTelemetry semantic conventions. D-numbers such as
D34 identify design decisions. Section References footers summarize them.
SLF4J means Simple Logging Facade for Java. OTLP means OpenTelemetry Protocol.

In the OTel capture sections, acceptance means passing the completion's query trace gates.
OTel buffer admission means storing that candidate in the traversal buffer.
Local admission means retaining a slow-query record under the separate local capture policy.
Neither admission contract guarantees span construction or export.

## Local monitoring

Local monitoring is database-owned state, independent of OpenTelemetry (OTel) export.
**B1 Activation and owners.** An engine-manager activation runs from a `startup()` that sets the manager active
to the next `shutdown()` that clears it.
Each activation owns one process monitoring owner, its database owners and never-reused generations.
The process monitoring owner holds process health and the global retained-memory ceiling.
Embedded engines and servers share this process owner.
A later `startup()` creates a new activation.
The finished activation stays current until the next `startup()`.
Each open database instance owns its records, health results and history positions.
Bind that owner reference at operation start together with the listener snapshot.
Completion and correlation use that reference, never a new lookup by database name.
A late completion cannot enter a replacement owner after drop and re-create.
Close marks the owner closed before releasing state. Drop affects no other database.
Reopen and re-create allocate fresh owners.
Engine-manager shutdown closes the activation's remaining owners.
Late publication or correlation calls on closed owners drop without waiting or recreating state.

### Slow-query admission and retention

The local enable flag defaults to off. Register the local consumer only when enabled.
A positive local threshold is independent of query trace gates and commit trace gates.
Use existing timing meanings and threshold-resolution concepts without moving OTel configuration.
The independent local enable and threshold settings have undecided configuration names.
The enable default is false. The threshold is in milliseconds and must be positive when enabled.
Its numerical default and retention limits remain open.

`CoreQueryProvenance` is a core helper used by `YTDBQueryMetricsStep` after its listener guard.
It activates whenever the captured query-listener snapshot is nonempty, including local-only capture.
At first `hasNext()`, it derives Context from the embedded host or from root in server mode.
It sets `GREMLIN_ORIGIN=true` and sets the traversal tag or clears an inherited `QUERY_TAG`.
The step scopes this Context around delegated iteration and resource close.
It restores the previous Context in `finally` on the same thread.
SQL entry captures tag and provenance and carries them on completion details.
The local consumer rejects SQL with the marker and keeps the outer Gremlin completion instead.
Native SQL without the marker remains eligible, including when the OTel module is absent.
The optional `GremlinSpanLifecycleHook` receives this core Context and adds its private buffer key.
It preserves tag, origin and host-span parentage. Only its private key selects OTel buffering.
Core provenance changes neither trace routing nor listener dispatch order.
Custom listeners on this path receive the same tag inheritance without an OTel hook.
Retain public completions only. Suppress nested implementation queries to prevent double counting.
A fast error or heartbeat candidate does not qualify merely because OTel accepted it.
Reject fast completions before text rendering, record construction or correlation allocation.
Both consumers absent preserves the listener-first disabled path without new Context or allocation work.

Bound record count, retained text and payload bytes per database under a process-wide ceiling.
Keep each database's retained records separate.
A publication may evict only older records of its own database.
Evict only the count and bytes needed for the candidate, then attempt global reservation.
Stop after a bounded number of failed reservations. Never empty the whole list to compete for budget.
If admission still fails, drop the candidate and count a gap. Eviction also records visible loss.
Global admission never exceeds the ceiling. Budget reservation acquires no database lock.
Query, commit and storage threads use bounded non-waiting observation handoff.
Reserve count and byte capacity before enqueue. Pending payloads count inside the global ceiling.
Overflow drops the observation and increments a bounded loss counter that does not need queue space.
Release reservations after consumption or drop. Numerical handoff and retry limits remain open.
Coalesce health triggers into a bounded pending flag per owner instead of a queue of changes.
Sanitization and serialization run outside short publication critical sections.
**B2 Close never waits.** Every close path calls only `beginClose` on the owners it ends.
The close paths are storage shutdown, database close, drop, idle auto-close, factory close,
engine teardown, shutdown handlers and server `deinit()`.
No close path waits on health work. Commit and timer paths never wait on health work either.
The health publication attempt never waits. SDK close is the only telemetry shutdown wait.
It waits as long as the configured SDK processor and exporter timeouts allow.
YouTrackDB adds no further bound. A zero timeout can make that wait unbounded.

**Lock ordering.** One short per-database publication guard protects records and associations.
Eviction and correlation resolution use that same guard. There is no separate association lock.
The health serialization guard is separate and never nests with a publication guard.
Acquire neither guard while holding an engine-manager, storage or security lock.
This includes `engineLock` and storage `stateLock`.
Acquire no engine-manager or storage lock while holding health serialization.
Call no engine manager, storage, security, exporter or other database owner under either guard.
Global budget operations use non-waiting atomic reservation and release, with bounded retries.
They take no locks and invoke no callbacks. Producers never wait to acquire a publication guard.
Each owner reserves one final-transition slot when it is created.
`beginClose` marks the owner closed to ordinary publication and correlation.
It also sets the final-transition flag in that slot and triggers the evaluator.
The operation is atomic, idempotent and lock-free. It never waits.
Cleanup releases records and associations without waiting and runs outside storage locks.
The owner's health scope stays until its flag clears or its activation ends.
Ordinary late publication and correlation calls on a closed owner drop.
Retained limits do not bound sanitizer working memory. Validation measures that memory separately.

An immutable record captures database identity, user name, source, sanitized query text,
summary, tag when present, timing mode, measured duration, completion timestamps,
outcome and available row count. Capture values rather than live references.
Outcome contains only status, error type and an optional typed error code.
Never store exception messages or derive codes from messages.
Retain no transaction, result set, execution plan, parameter map or deferred accessor.
Use the existing source-appropriate sanitizers. Sanitization failure omits text.
Never substitute raw SQL, raw Gremlin or raw scripts. Parameters remain off by default.
Do not retain executable inputs for replay.
Slow-query read permission reveals all records of the authorized database, including user names.
The permission is intended for administrators because sanitized text can expose schema.
Detailed access enforcement is later Track 104 design work.

### Optional trace correlation

Core stores an opaque association separately from the immutable record.
Core never depends on the OTel module or creates an SDK span to obtain an identifier.
Only admitted records create association work and pending state.
Rejected completions allocate neither correlation work nor pending state.
Use a completion identity to reconcile local admission with OTel publication in either listener order.
An OTel callback that runs first must not create pending state for an unadmitted completion.
Admission and publication must reconcile the actual outcome without depending on listener order.
The correlation lifetime and exact bounded reconciliation mechanism remain open.

| Current association | Observation | Next association |
|---|---|---|
| pending | Actual emitted query-span trace identifier published | available |
| pending | No query span will be published or association expires | terminal unavailable |
| available or terminal unavailable | Duplicate or late resolution | unchanged |
| Any | Record evicted or owner closed | association released, late updates dropped |

Keep pending state bounded by admitted retained state and its expiry.
An association must resolve unavailable for gate rejection, non-publication or expiry.
Existing late span construction, parent retention and SDK sampling still apply.
Available means a confirmed query-span association, not confirmed backend retention.
Label ambient host correlation separately. Never predict a trace identifier.
An event snapshots both record and correlation at creation.
Later correlation never mutates an event payload or its replay.
No update resurrects evicted records or retains expired association state.

### Health evaluation and ordered publication

A bounded evaluator runs on coalesced observation changes or at a bounded interval outside queries.
All readers receive the latest evaluated result. Reader count never causes more evaluation.
Use lifecycle readiness and explicit storage or database failures where available.
Use runtime observations only when their meaning and freshness are authoritative.
Slow-query volume does not prove corruption. Absent telemetry does not prove database failure.

| State | Meaning |
|---|---|
| unhealthy | A confirmed observed failure applies |
| degraded | An authoritative degradation rule applies without confirmed failure |
| unknown | Required observations are absent or stale and no confirmed failure or degradation takes precedence |
| healthy | All required observations are current and successful |

Report incomplete coverage separately even when failure or degradation takes precedence.
A result carries state, reasons, observation freshness, state version, generation and position.
One serialization point per scope assigns state version, transition position and timestamp together.
Publish current result and immutable history as one atomic pair after preparing both.
A reader never sees a current position before that position exists in its paired history.
Readers see a result paired with the exact generation and position it reflects.
Assigned position defines order even when wall clocks move or export completes out of order.
Prepare observations before health serialization without retaining engine-manager or storage locks.
Never hold health serialization across calls into the engine manager, storage, security or export.
Export the current result as an OTel metric and transitions as structured OTel log events.
Carry assigned generation, version, position and timestamp through export.
**B4 Order.** A consumer drops a non-final result whose owner is already closed.
Each OTel health consumer keeps, per scope, the result with the highest
generation and position. An older result never replaces a newer one in the metric.
Transition log events still export with their assigned order.
A closed scope's final result stays until one metric collection reports it or the SDK closes.
Keep state and reason payloads bounded. Reasons contain fixed reason codes, status,
error type and optional typed error codes only. Never include exception messages or raw query values.
Final metric encoding and authoritative rules remain open.
Signal enable flags still govern export. No exporter callback reevaluates health.
Ordinary log capture and histogram contracts remain unchanged.
Health-transition retention has separate count and byte bounds under the process-wide ceiling.
Slow-query retention and volume cannot consume that separate history capacity.

### Generations, history and shutdown

A generation identifies a position sequence. Allocate a never-reused generation whenever
positions restart, including close, drop, re-create and process restart.
Within one process, generations increase in allocation order.
A history cursor binds generation, position, scope and filter context.
A cursor provides no authority and cannot restore revoked access.
Concurrent publication gives a consistent page boundary without skipping later admitted records.
Foreign generations, future positions and expired positions return explicit reset or gap answers.
History is bounded in memory. No durable ledger, exactly-once delivery or restart persistence exists.
Track 104 will define the access interface. Track 105 will define MCP event adaptation.

**B3 Final publication.** Any thread that holds a scope's health serialization and finds
its final-transition flag set publishes the final not-ready result and history as one pair.
It clears the flag in the same critical section. The evaluator does this on its next run.
The export handoff runs after the guard is released and never waits.
A handoff failure is logged and stops nothing. Publication works without the OTel module.
Export never evaluates health. Cleanup releases records and associations without waiting.
The owner's health scope stays until its flag clears or its activation ends.

**B5 One attempt before SDK close.** Before the facade exit hook closes a YTDB-owned SDK,
it calls `beginClose` on every owner of the current activation.
Every facade close path then makes one final publication pass.
For each set flag, the pass tries the scope's health serialization without waiting.
If the guard is free, the pass publishes as in B3. If it is busy, the pass skips that scope.
The facade then hands off and closes.
Explicit `shutdown()` runs the same pass but begins no close.
The pass and the close run outside facade resolution synchronization and outside every
engine, factory, `YouTrackDBImpl`, storage and monitoring lock.
The handoff never takes facade resolution synchronization.
Server `deinit()` calls `beginClose` on the server factory's owners before `gremlinServer.stop()`.
When `shutdownEngineOnExit && !registerByPath` holds, it also covers the process owner
and every owner of the activation. It then stops Gremlin and calls `onBeforeDeactivate`.
That callback invokes facade shutdown, which makes the pass and closes the owned SDK.
An ordinary embedded close and idle auto-close leave final publication to the evaluator.

**B6 SDK ownership and one close.** Core never closes an SDK.
Engine shutdown leaves it open for a later activation.
The facade closes a YTDB-owned SDK from that instance's exit hook or explicit `shutdown()`.
Replacement through `setOpenTelemetry` also uses explicit `shutdown()`.
Each instance has a close-once flag. A later or concurrent caller returns at once
and does not close it again. Closing one SDK never prevents a later SDK from closing.
YTDB-owned construction disables the SDK's own hook and registers that instance's facade hook.
`YouTrackDBEnginesManager.removeShutdownHook()` does not remove the facade hook.
The facade never closes a host-owned SDK and adds no host lifecycle obligation.
Do not rely on the relative order of independent Java Virtual Machine (JVM) shutdown hooks.

**B7 Best effort.** YouTrackDB tries to export the final not-ready state.
It never guarantees that export.
Hook order, a busy scope, explicit shutdown while the engine runs, a host-owned SDK,
a removed core hook and backend failure can each lose it.
Explicit shutdown of a YTDB-owned SDK closes it at once even while the engine runs.
A handoff to a closed SDK drops.

**B8 Readers.** Not-ready is a readiness observation mapped to one of the four health states.
The authoritative lifecycle rule maps it to healthy, degraded, unhealthy or unknown.
Readers show unknown when samples stop or become stale.
A stopped process cannot report its own health.
Shutdown closes owners and releases retained records, histories and associations.
Validation covers close versus publication, drop versus correlation, reopen isolation,
generation resets, atomic health and history reads, cursor races and budget exhaustion.
Exercise correlation resolution versus eviction under the shared publication guard.
Exercise storage close while health evaluation runs and assert that close never waits on it.
Race engine shutdown with evaluation and assert shutdown never waits on health serialization.
Assert evaluation never acquires an engine-manager or storage lock under health serialization.
- **T1:** Run the core hook before the facade hook, and in reverse.
  Assert no close path waits on health work, the pass never waits and the SDK closes once.
- **T2:** Exercise the signal path, then `System.exit`, then the facade hook.
  Assert the pass never waits and the SDK closes once.
- **T3:** Run a server under `ServerShutdownHook`.
  Assert `beginClose` runs before `onBeforeDeactivate`, then one pass and one close.
  Assert engine teardown handoffs after SDK close drop without error.
- **T4:** Run `YouTrackDBServer(false)` and shut down the server while the engine runs.
  Assert the SDK closes at server shutdown. Label the process-owner final as best effort.
- **T5:** Run engine-manager `shutdown()`, then `startup()`, then exit.
  Assert fresh owners, a higher generation and a reused SDK.
  Assert a delayed old final does not replace the new result.
- **T6:** Deliver a delayed ready V10 handoff after final not-ready V11 in the same generation.
  Assert the consumer keeps V11. V10 and V11 identify successive health-result positions.
- **T7:** Hold health serialization during the pass.
  Assert the pass skips that scope without waiting and the SDK closes.
- **T8:** Make a handoff throw.
  Assert the failure is logged, the SDK closes and no thread blocks on health work.
- **T9:** Run replacement while another thread resolves the facade and the evaluator hands off.
  Assert no deadlock and one close outside resolution synchronization.
- **T10:** Run concurrent explicit shutdown and the exit hook.
  Assert one SDK close and that the caller that does not close returns at once.
- **T11:** Exercise a host-owned SDK and a removed core hook.
  Label export best effort. Assert the facade never closes a host-owned SDK.
- **T12:** Exercise `closeAll()` under `engineLock` and idle auto-close under the monitor.
  Assert they call only `beginClose` and never wait on health work.
- **T13:** Close a scope and collect its kept metric value.
  Assert the kept value is released after one collection.
  Then deliver a delayed ready result published before the owner closed.
  Assert the consumer drops it and keeps no metric value for that closed scope.

Exercise competing database reservations and assert bounded retries and same-database eviction.
Exercise handoff overflow and coalesced health triggers without exceeding pending-memory bounds.
Check tagged and untagged Path B capture without the OTel module.
Retain only the outer Gremlin completion while native SQL remains eligible.
Include concurrent eviction, cursor reads and slow-consumer pressure without growing per-reader queues.

### Open questions

1. Which numerical slow-capture limits should ship?
   Capture stays off by default with a positive threshold and bounded count, text and bytes.
2. Which correlation lifetime and expiry limits should ship?
   Keep admitted-only work, immutable event snapshots and listener-order independence.
3. Which health observations and state rules are authoritative?
   Confirm freshness limits and unknown coverage before runtime-derived degradation rules.

### References

- Constraints: independent local monitoring, non-waiting handoff and bounded ownership.
- Deferred design: Track 104 access and presentation, Track 105 MCP, Track 106 implementation plan.

## Slow-query threshold gating

The resolver and its rule type reuse the same machinery as `QueryMonitoringModeResolver` — only the `T` parameter on `TagRule<T>` differs (`Long` instead of `QueryMonitoringMode`):

```java
public final class SlowQueryThresholdResolver {
    private final List<TagRule<Long>> rules; // immutable, compiled once at startup

    public long resolve(Optional<String> tag, long defaultNanos) {
        if (tag.isEmpty()) {
            return defaultNanos;
        }
        return walkRules(tag.get()).orElse(defaultNanos);
    }
    // Return OptionalLong.of(nanos) for the first matching rule, or OptionalLong.empty().
    // Apply the caller's fallback after the walk and retain no per-tag result.
    // Exact and prefix rules compare strings. Regex rules share precompiled Pattern objects.
    // Each regex lookup uses a local Matcher with the existing matching operation.

    public static SlowQueryThresholdResolver global() { ... }
}
```

A listener fire site is the core code that constructs completion details and invokes listeners.
The gate sits inside `OTelQueryMetricsListener.queryFinished`. It is separate from the fire site. Three reasons: (1) other listeners registered alongside the OTel one (custom host implementations) may have their own gating policy and must continue to see every event; (2) the gate is OTel-specific configuration and lives in the OTel module rather than `core`; (3) `QueryDetails` already carries the inputs the gate needs — `executionTimeNanos` arrives as a method parameter, error state arrives through `QueryDetails.getErrorType()`, and the query tag arrives through `QueryDetails.getQuerySummary()` (same source the OTel sem-conv mapping already reads).

Duration-check fragment only. In the complete callback, errors bypass both checks and a
successful heartbeat claim bypasses this fragment. [Time-based sampling](#time-based-sampling)
defines the combined gate order. [Buffered SQL children](#buffered-sql-children) defines
completion routing and cleanup.

```java
@Override
public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos) {
  // Resolve and clamp the real completion endpoint once at callback entry.
  // Reuse it for elapsed-duration histograms, snapshots and span end.
  // Record enabled query histograms before the trace gate.
  boolean isError = details.getErrorType().isPresent();
  if (!isError) {
    long perTxOrGlobal = details.getSlowQueryThresholdOverrideNanos().orElse(defaultThresholdNanos);
    long thresholdNanos = SlowQueryThresholdResolver.global()
        .resolve(Optional.ofNullable(details.getQuerySummary()), perTxOrGlobal);
    if (thresholdNanos > 0 && executionTimeNanos < thresholdNanos) {
      // A rejected Gremlin completion also discards and counts its buffered SQL candidates.
      return;  // no span allocation on either path
    }
  }
  // Nested SQL: offer an immutable completion snapshot to the captured traversal buffer.
  // Gremlin: build the late parent, the real Gremlin SDK span created at traversal completion.
  // Use the recorded embedded host Context or server root, with the explicit start timestamp.
  // Drain means emit buffered SQL snapshots as child spans.
  // Drain only when that parent is recording and sampled.
  // Direct SQL: use its embedded host parent or server root, with recorded real timestamps.
  // See Buffered SQL children for cleanup and Time-based sampling for the combined gates.
}
```

`QueryDetails.getErrorType(): Optional<String>` is a new default-empty accessor added to the listener contract. Both query paths populate it from the caught failure. SQL entry-point catches emit one error completion when parse, setup or cache work fails before a result set exists. SQL entry first checks the existing query-listener list through the presence check. With no listeners, it performs no telemetry Context read, clock read, snapshot or allocation. Otherwise it captures one operation listener snapshot, Context, tag, Gremlin-origin marker, mode and early-failure start time before the failing work. Pass that snapshot and captured entry state to the wrapper without repeating listener selection, Context capture or mode resolution. Successful SQL timestamps cover result consumption from wrapper construction through delegated close. They exclude successful parse, setup and execution work completed earlier. Early failures use the guarded entry window. A standalone factory captures its own guarded state only when no entry state exists. They preserve rollback and rethrow the original failure. Once a wrapper exists, it owns completion and closes in a `finally` path after a drain failure. Close the delegated result set before capturing the endpoint and dispatching listeners once. Preserve an earlier execution failure for telemetry. Report a close failure only when no earlier failure exists. Keep delegate exception propagation and normal suppression unchanged. An abandoned result set without `close()` still emits nothing. The accessor exists on the listener contract so any consumer (OTel or custom) can read error state without an additional callback signature.

Direct SQL has no query tag and uses the per-TX fallback. SQL entered inside tagged Gremlin inherits `YTDBContextKeys.QUERY_TAG` captured before execution. `GREMLIN_ORIGIN` is a boolean provenance marker set for tagged and untagged Gremlin execution. An untagged nested Gremlin traversal clears an inherited tag and still stamps `GREMLIN_ORIGIN=true`. Gremlin pins its span name at first `hasNext()` to the tag or `youtrackdb`. SQL uses the sem-conv fallback chain. The resolver reads the inherited tag, not a classifier-derived display summary.

Successful commits use the separate `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` gate. Its
default `0` accepts every successful commit candidate. Failed commits bypass it. Query thresholds,
query tags and heartbeat sampling do not govern commit selection. The [SDK sampling contract](#sdk-sampling-contract)
applies to accepted SQL, Gremlin and commit candidates.

Required listener-first regression tests use counters for Context access, clock reads and
instrumentation factories. With empty listener lists, assert zero calls for SQL success and
early failure, Gremlin strategy and first `hasNext()`, successful and failed commit, log-hook
dispatch and metric-registration events. Assert ordinary database and SLF4J behavior is
unchanged. With listeners present, verify capture starts after the presence check and the
non-empty operation snapshot still completes and cleans up after global unregistration.
Assert zero boundary-classification construction without query listeners. A Gremlin traversal
strategy is a pass that inspects or rewrites a traversal before execution. User-requested
ProfileStep detection and plan-profiling wiring must still work with no listener.
Verify that SQL entry and wrapper share one operation snapshot and that commit notification
never rereads mutable lists. Race live log-hook and metric-registration removal with snapshot
capture. An empty captured snapshot must prepare no hook arguments or registration event.
Later removal must leave captured callbacks and cleanup intact.

### SDK sampling contract

A gate is a YouTrackDB check that selects a completion as a candidate for span construction.
The query slow threshold, heartbeat and error rule select SQL and Gremlin candidates. The
separate commit threshold and failure rule select commit candidates. Gates run before span
construction. Nested SQL also needs buffer admission and a retained Gremlin parent.

A sampler is the configured OpenTelemetry SDK policy evaluated by `startSpan()`. For an
SDK-backed tracer, `startSpan()` evaluates that sampler for each candidate. This includes
errors, heartbeat selections, late Gremlin parents and buffered SQL children. A no-op
OpenTelemetry instance instead returns non-recording spans without an SDK sampling decision.
YouTrackDB preserves both behaviors. Its gates cannot override an SDK sampling decision.
The emit-all defaults disable duration rejection. They do not promise recorded or exported spans.

The export column describes the default sampled-only SDK processor pipeline. `RECORD_ONLY`
spans reach recording-span processors, but the default pipeline does not export them. Custom
host processors can apply a different export policy.

| SDK decision | Result | Default sampled-only export pipeline |
|---|---|---|
| `DROP` | A non-recording span | No export |
| `RECORD_ONLY` | A recording span without the sampled flag | No export |
| `RECORD_AND_SAMPLE` | A recording span with the sampled flag | Eligible for export |

For example, a `ParentBased` sampler configured to drop children of an unsampled host parent
can drop a slow query, an error or a heartbeat candidate that passed every YouTrackDB gate.
A custom sampler can return `RECORD_ONLY` for the same candidate. YouTrackDB never forces
sampling by changing parent flags, changing parentage or creating a replacement root after a
sampling decision. Server root parenting is a fixed role policy, not a sampling workaround.
YouTrackDB adds no SDK sampler implementation, sampling-ratio setting or SDK-sampler
configuration entry. The heartbeat setting controls candidate selection before SDK sampling.

Only a recording, sampled Gremlin parent permits buffered SQL construction. An SDK `DROP` or
`RECORD_ONLY` parent discards the buffer as `parent_not_emitted`. This rule also applies when a
custom host pipeline could export an unsampled recording parent. Each constructed SQL child
still receives its own SDK sampling decision. A sampled parent does not guarantee a sampled
child. SDK child sampling losses remain outside the buffered-candidate drop counter.
Even sampled spans can be lost during processing or export. Histogram recording stays before
trace gates and does not depend on SDK span sampling.

Required regression tests must cover direct SQL, Gremlin and commits with sampled and unsampled
host parents. They must cover error and heartbeat candidates under SDK `DROP` and `RECORD_ONLY`,
child sampling loss and parent-loss accounting. They must confirm that sampling does not rerun
gates or refund heartbeat slots.

YTDB-owned SDKs leave sampler selection to OpenTelemetry SDK autoconfiguration. This includes
the `otel.traces.sampler` and `otel.traces.sampler.arg` properties and their
`OTEL_TRACES_SAMPLER` and `OTEL_TRACES_SAMPLER_ARG` environment variables. Without an override,
they use the autoconfiguration default `parentbased_always_on`. Host-owned instances retain
their existing sampler. YouTrackDB adds no SDK-sampler setting. YTDB-owned SDKs also use the
processors supplied by autoconfiguration. Host-owned processor configuration stays unchanged.

SDK builder wiring uses OpenTelemetry Java SDK 1.66.0 as the reference release. The implementation
plan pins the OpenTelemetry bill of materials (BOM), `opentelemetry-bom`, at 1.66.0 in the Maven
project object model (POM), `pom.xml`. The BOM aligns API and SDK dependency versions.
Embedded discovery checks `GlobalOpenTelemetry.isSet()` and reads `getOrNoop()` when registered.
These methods require `opentelemetry-api` 1.57 or later. Discovery does not call `get()`, compare no-op identity
or cast the global wrapper to `OpenTelemetrySdk`. The explicit setter takes priority.
A registered global remains host-owned even when it is no-op.

Track 3 (G1: Listener detail contracts) adds the `core` compile dependency on
`io.opentelemetry:opentelemetry-context`, managed by the existing OpenTelemetry BOM at 1.66.0.
Its transitive `opentelemetry-common` brings the combined JAR size to about 54 KiB.
`QueryDetails.getParentContext()`, `InstrumentedSqlResultSet.capturedContext` and
`YTDBContextKeys` use OpenTelemetry Context types directly, including
`io.opentelemetry.context.Context`. This adds no SDK or exporter to core. Track 3 excludes
both Context and common artifacts from embedded shading and relocation. They remain ordinary
transitive dependencies to avoid duplicate classes with a host application's OpenTelemetry.
A YTDB-owned neutral carrier is rejected because new hook signatures and adapter state for
Path B tag inheritance and cross-thread parent capture provide no user benefit.

Goal 1 includes `youtrackdb-opentelemetry` and its runtime dependencies in the standard server
distribution and server Docker image. Forecast Track 102 (G8: Server distribution packaging)
depends on group 8 activation work in Tracks 48–49. It covers server assembly and Dockerfile
packaging, distribution size growth measurement, licence notices for added libraries and
verification that the plugin stays inactive at the default `OPENTELEMETRY_ENABLED=false`.
Only `OPENTELEMETRY_ENABLED=true` activates telemetry. Embedded users add the optional module
themselves. Embedded integration bundling and telemetry enabled by default are non-goals.

### Span attribute work

A recording span is a span for which `span.isRecording()` returns true. Both `RECORD_ONLY`
and `RECORD_AND_SAMPLE` satisfy that check. `DROP` and no-op instances do not. This contract
controls attribute work without changing the SDK sampling contract above.

Set the following sampler inputs on the builder before `startSpan()` when available:
`db.system.name`, `db.namespace`, `db.operation.name`, `db.collection.name`, `db.query.summary`,
`error.type`, available `db.response.status_code`, configured `server.address` and `server.port`,
`youtrackdb.gremlin.path`, `youtrackdb.sql.invoked_via` and `youtrackdb.duration_semantics`.
Resolve operation and collection independently across the whole call. Commit supplies the applicable subset with its known operation.
Absent values stay absent. Reuse constants, captured provenance and memoized classification.
This work is required even if the sampler drops the span. It lets a host sampler select by
query identity, source, timing semantics or error. The split preserves the configured sampler
and the SDK decision-handling contract. Only the listed builder attributes are guaranteed
sampler inputs. Deferred attributes are unavailable during sampling. A host that needs equivalent
information must supply independent inputs, such as parent Context values, before query execution.
`db.response.status_code` uses the string form of `ErrorCode.getCode()` when the captured
failure is a `CoreException` with a non-null `getErrorCode()` value. Never infer a code from
message text. Only a minority of exception types set one. Completion details carry this optional
fact alongside `error.type` for queries and failed commits. Late construction makes both facts
available before `startSpan()`. Copy the optional code into buffered SQL snapshots without
retaining the exception. Remote Gremlin `ResponseStatusCode` is outside this change.
This design defines no general YTDB builder-customization hook. YTDB deliberately defers
`db.query.text` despite the sem-conv builder-time recommendation. This avoids rendering text
for non-recording spans. Samplers cannot inspect this attribute.

After `startSpan()`, check `span.isRecording()` before reading or converting the remaining
span-only values or calling their setters. This includes sanitized query text, opted-in
parameters, explicit tracking ID, returned rows, structural and advisor flags, profile flags,
cache outcome and custom attributes. The companion's vendor-attribute inventory documents
keys, value types and absence rules. It is not a runtime allowlist for `getCustomAttrs()` or
OTel export. Preserve documented value types and absence rules. Numeric custom values are
exact span-only measurements and never metric dimensions. Custom listeners keep their
access to details.
`OPENTELEMETRY_QUERY_INCLUDE_RETURNED_ROWS` is a boolean with default `false`, captured at
listener installation like the parameter opt-in. Only `true` enables the span attribute
`db.response.returned_rows` when its count is present. It does not control returned-row
histograms. Set error status only in this block. Do not require the
sampled flag for enrichment. Host processors may consume `RECORD_ONLY` spans. Always call
`end` in `finally`, even when the span does not record. Use this flow for direct SQL, Gremlin,
commits and each buffered child when it is constructed. Predefine attribute keys at startup.
Do not construct a per-span map or `Attributes` set for the deferred values before the check.

Some work serves another consumer. Enabled histograms still need their own dimensions before
trace gates. Name and sampler inputs can require classification before recording is known.
Custom listeners can read lazy accessors independently. These are not deferred span-only work.

Nested SQL is the snapshot exception. At SQL completion it must copy all configured values
needed later, including sanitized text and opted-in parameters. It cannot retain a result,
plan, transaction or lazy accessor until the late parent exists. Preserve this immutable
snapshot contract even if the parent or child is later dropped. Populate each child builder
with the documented sampler-input subset before `startSpan()`, including when the SDK later
drops the child. Defer only the remaining span attributes, their SDK attribute-set construction
and conversions not required by the snapshot until that child's own `isRecording()` check.
A recording parent does not eliminate that child check.

Required tests must count expensive accessor calls and attribute conversions for SDK `DROP`,
`RECORD_ONLY`, `RECORD_AND_SAMPLE` and no-op instances. Assert sampler inputs at builder time,
remaining attributes and ERROR status for recording spans, and unconditional end calls. Cover
direct SQL, Gremlin, successful and failed commits, plus children dropped by their own sampler.
Keep snapshot-copy assertions and one histogram sample per completion regardless of sampling.

### Late-parent construction

The chosen alternative is to buffer SQL completion data locally and build the parent at traversal
close. SQL does not need a parent span ID during execution because it creates no span then.
The SDK generates the real Gremlin span ID at close. Child builders use that real span as their
explicit parent. No chosen-ID injection or hand-built `SpanData` is needed.

Rejected alternatives remain:

- Collector `tail_sampling` requires shipping every candidate span before deciding what to keep.
  Local completion buffering avoids that traffic.
- A pre-generated `SpanContext` during traversal execution cannot later become the same recording
  span through the high-level `Tracer` API. The SDK's `IdGenerator` owns the span ID. Local
  buffering needs no placeholder ID.

### Buffered SQL children

`GremlinTraversalState` is an OTel-module object for one traversal. It holds the recorded host
Context, start timestamp, fixed query name, a bounded list of `PendingSqlSpan` values and a sealed
flag. `PendingSqlSpan` holds captured SQL completion data, not an SDK span. A sealed buffer is
closed to all further offers. It rejects subsequent candidates as `traversal_closed`. This flag
is separate from Java sealed interfaces. A private OTel-module Context key carries the state.
Core `CoreQueryProvenance` first supplies `QUERY_TAG` and `GREMLIN_ORIGIN` after the listener guard.
`GremlinSpanLifecycleHook.onFirstHasNext` preserves that Context and adds this private key.
The base Context derives from the embedded host or from root in server mode. It adds no span.
The step installs that Context only around delegated database calls.
It closes each scope in `finally` on the thread that opened it.
Server execution derives traversal state from `Context.root()` on the Gremlin executor worker,
including per-session workers. It ignores ambient worker spans. The outer request span uses
`SERVER` with `setNoParent()`. Buffered SQL uses `INTERNAL` with its real late Gremlin parent.
Direct SQL known to handle an incoming remote request uses `SERVER` with `setNoParent()`.
Local server SQL uses `INTERNAL` with explicit root parenting. Absence of a traversal buffer
selects direct completion routing and does not establish a remote request role. Standalone server commit work uses
`INTERNAL` with root parenting. No remote parent is extracted in this change.

Each SQL entry captures that Context before execution. A standalone factory without entry state
performs its own guarded capture. At SQL
completion the OTel listener records histograms and applies the existing SQL gates, including
heartbeat claiming at that moment. A rejected completion creates no buffer entry. An accepted
completion has passed its own query gates and becomes a candidate for retention. It snapshots
the values needed to build its span, then offers the snapshot to the buffer. Admission means
storing the candidate in the traversal buffer. Neither acceptance nor admission guarantees span
construction or export. The snapshot holds recorded real start and completion endpoints, unchanged listener duration, sanitized query text,
name, error type, optional typed error code, classification and other configured attribute
values. It copies row count for span enrichment only when the returned-rows opt-in is enabled.
Histogram row recording already occurred independently of that opt-in. It copies parameter
values only when the existing parameter opt-in is enabled. These copies are required
snapshot work before the child's SDK decision. Set the documented sampler inputs on each child
builder before `startSpan()`. Defer only the remaining span attributes, their SDK attribute-set
construction and non-snapshot conversions until that child's own `isRecording()` check.
See [Span attribute work](#span-attribute-work).
The snapshot retains no result set, execution plan, transaction or lazy `QueryDetails` accessor. Custom listeners still receive their SQL completion immediately.

`OPENTELEMETRY_GREMLIN_MAX_BUFFERED_SQL_SPANS` is a non-negative integer with default `128`.
The `YouTrackDBOpenTelemetry` facade reads it at SDK initialization. Each traversal takes that limit when its state is
created. A full buffer drops the newest candidate. It never blocks for space, spills to disk or
evicts an earlier candidate. `0` disables child retention without disabling the Gremlin span or
SQL histograms. Invalid values fail SDK configuration. This limit bounds entry count.
The separate query-text limit bounds each retained text value. Neither the buffer count nor
SDK queue capacities bound total payload bytes.

The buffer serializes offer and seal through a short synchronized critical section. Snapshot
creation, span building and exporter calls run outside it. Traversal close seals and detaches the
list once. A racing SQL completion either enters the detached list or is rejected as late.
The traversal does not wait for open SQL results. Their later completion is dropped and counted.
After detaching, the state releases its list references. The listener owns the detached batch
and clears unconsumed entries in its final cleanup, including when parent construction fails.
An abandoned traversal with no close emits no parent or buffered children. Its state becomes
collectible with the traversal and any remaining captured SQL contexts. No timeout or background
cleanup task is introduced.

After resources close, Gremlin completion records histograms and evaluates its existing gates.
If those reject the parent, discard every buffered candidate. Otherwise build the Gremlin span
with the recorded host parent, name and explicit start timestamp. If it is not recording or not
sampled, discard the candidates. This includes SDK `DROP` and `RECORD_ONLY` decisions, even
when a custom host processor pipeline could export the unsampled recording parent. An accepted SQL child never forces its parent
through a gate. It is never promoted to a root or reparented to the host on rejection.

For a recording, sampled Gremlin parent, build each buffered child with
`recordedHostContext.with(gremlinSpan)` as its explicit parent. Preserve the child's recorded
real start and completion endpoint. Do not call `queryFinished` again, rerun its gates, claim another
heartbeat slot or record its histograms twice. Each child builder still uses the configured SDK
sampler. Emit children in buffer admission order, then end the Gremlin span in `finally` using
its recorded real completion endpoint. EXACT active time is a separate `youtrackdb.active_time_nanos` attribute on recording query spans. Gates keep their unchanged active-time input.

`hook.onClose` seals and clears entries not detached by the listener in `finally` after dispatch.
Listener cleanup owns detached entries. Both cleanup paths count unconsumed candidates once as
`parent_not_emitted`. If Gremlin span construction or listener work fails, the normal isolation
warning reports it. Repeated close cannot re-emit. Error SQL uses the same buffer limit and
parent requirement as successful SQL.
An error bypasses its own duration gate, but capacity exhaustion can still discard its candidate.

One process-wide monotonic counter records discarded candidates while the OTel listener is
installed. With metrics and the `queries` group enabled, expose it as the `LongCounter`
`youtrackdb.otel.gremlin.sql_spans.dropped`, unit `{span}`. Use one bounded `reason` attribute:
`capacity`, `parent_not_emitted` or `traversal_closed`. The backing total advances even when
metrics export is off. A rejected offer increments once. Parent rejection or cleanup adds the
number of remaining entries once. SQL rejected by its own gates was never a candidate and does
not increment this counter. SDK child sampling and exporter losses are outside this counter.
No query tag, query text or transaction ID is a counter dimension.

Required buffer regression tests must cover zero, one, full and overflowing buffers, explicit
parent IDs after late creation, SQL gate rejection, parent gate rejection, parent SDK drop and
record-only decisions, error candidates, close idempotence, cleanup after listener failure and
offer-versus-seal races. They must also verify one histogram sample per completion and no second
heartbeat evaluation on drain.

### Edge cases / Gotchas

- Default `0` disables duration rejection when no tag rule or per-transaction override supplies a positive threshold. Duration alone then does not prevent first-run telemetry after `OPENTELEMETRY_ENABLED=true`. Buffer retention, parent retention, SDK sampling and export still determine whether a span appears. Operators on read-heavy workloads who want to drop fast successful queries set a positive value (e.g., `100`) and document the choice in their observability runbook. Errors bypass both query gates.
- A query whose duration equals the threshold passes the duration gate, because the comparison is strictly `<` (less-than). A 100 ms query against a 100 ms threshold passes the gate.
- The trace gate runs before span allocation or SQL candidate buffering. It reads the error, original tag and per-TX threshold override. Metrics-enabled listeners record histogram samples before this gate. Parent rejection drops buffered children without changing their already-recorded metrics.
- Resolver memory under high-cardinality tag input: both resolvers retain only immutable startup rules and precompiled patterns. They store zero observed tags and need no locks or shared writes during resolution. Retained memory depends on configuration, not distinct-tag count. A lookup uses local regex matchers. Every tagged lookup walks rules until the first match. There is no admission, full-cache case or eviction work. Exact, prefix and regex semantics, insertion-order precedence and invalid-rule handling remain unchanged. The heartbeat gate has no resolver.
- A tag that matches no rule uses the per-TX override (`withSlowQueryThresholdMillis`) when set, else the global default. Apply that fallback after each walk. Do not retain it. Different transaction defaults therefore cannot leak through shared state. Regression tests cover repeated tags, high-cardinality input, concurrent walks, overlapping rule kinds and unmatched tags with different fallbacks.
- Conflicting rules (two rules match the same tag): first-wins by insertion order. Operators order rules from most-specific to most-general. Duplicate exact rules: the first one wins; subsequent rules log WARN at startup and are dropped.
- The global default is captured at OTel listener construction time and stored in the `defaultThresholdNanos` final field. Mid-process changes to the global default require an SDK rebuild (host calls `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` again, or the server restarts). The tag-rule list is also compiled once at startup; mid-process rule changes are not supported in YTDB-496 (same constraint as for mode rules). The per-TX override is not subject to either constraint: the fire site reads `FrontendTransaction.getSlowQueryThresholdOverrideNanos()` per query and carries it on `QueryDetails`, so a transaction that calls `withSlowQueryThresholdMillis(...)` takes effect immediately without an SDK rebuild.
- Custom (non-OTel) `QueryMetricsListener` implementations are unaffected. The gate lives only in `OTelQueryMetricsListener`; the listener iteration in `iterateAllQueryListeners()` still fires every registered listener for every query. Custom listeners that want their own threshold semantics can call `SlowQueryThresholdResolver.global()` themselves, or implement their own gating logic entirely.
- Threshold-vs-mode coupling. The gate compares against `executionTimeNanos` whose semantic depends on the resolved `QueryMonitoringMode` for the same tag: active-time under `EXACT` (consumer idle excluded), wall-clock under `LIGHTWEIGHT` (consumer idle included). Operators combining a mode-rule and a threshold-rule for the same tag declare a combined intent. For example, `OPENTELEMETRY_QUERY_MODE_TAG_RULES=findHotpath=EXACT` paired with `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES=findHotpath=100` means the duration check accepts a hot-path completion with at least 100 ms of DB-side active work. The same threshold value `100` paired with `LIGHTWEIGHT` for a different tag accepts a completion with at least 100 ms of wall-clock time. Acceptance does not guarantee span construction, recording or export. A single numeric threshold therefore has different operator-facing meaning depending on its companion mode rule; operators tuning thresholds across tags with mixed modes account for this by reading the mode×threshold table in §"Query tagging and per-tag rule resolution". Cross-tag dashboard aggregation slices on `youtrackdb.duration_semantics` to compare populations with the same gate measurement. Span and standard histogram durations always cover real elapsed time. Inside a Path B traversal, nested SQL inherits the outer Gremlin tag through `YTDBContextKeys.QUERY_TAG`. Both queries use the same resolution rules and retain their own captured values. A matching threshold rule gives equal thresholds. Otherwise equality requires an unchanged applicable TX fallback between capture points. A changed fallback applies to later queries, including nested SQL started later. The resolved threshold may come from the query-tag rule, the per-TX override, or the global default (Table B in §"Query tagging and per-tag rule resolution"); the mode coupling applies identically whichever threshold tier won.
- A child whose own gate accepts it can still be lost when its parent is rejected, the buffer is full or completion is late. The drop counter reports these losses. Parent retention is never forced by a child. Exporter or processor losses can still produce an incomplete backend trace.

### References
- D-records: D16 (slow-query threshold inside OTel listener, error bypass, per-tag rules via `TagRule<Long>`)
- D46 — skinny-span export filtering, superseded by late Gremlin span construction:
  The bundled skinny-span filter suppressed rejected Gremlin spans after allocation. Late gate evaluation before construction supersedes
  that decision. This entry preserves decision history only.

## Time-based sampling

The heartbeat gate uses one `AtomicLong` and one final-field interval. It reads no resolver, tag or cache. The heartbeat runs first at the top of `OTelQueryMetricsListener.queryFinished`. A winning claim makes the completion pass the query trace gate and bypass the slow-query check. If it does not claim, the slow-query gate evaluates as before. Error completions bypass both query gates without advancing the heartbeat clock. A stream of errors does not suppress heartbeat selection of successful queries.

Heartbeat claiming occurs at query completion, before buffer admission or span construction.
A successful claim advances the heartbeat clock even if the selected nested SQL candidate is
later discarded because the buffer is full, the traversal is sealed, or the Gremlin parent is
not retained. Discarding the candidate does not refund the slot or select a replacement. Drain
does not evaluate heartbeat claiming again. The [SDK sampling contract](#sdk-sampling-contract)
applies to heartbeat candidates. SDK sampling and export can prevent a selected span from appearing.

Error completions bypass heartbeat and slow-query checks without advancing the heartbeat clock.
Direct SQL and Gremlin error completions proceed to span construction. Nested SQL error
completions use the same bounded buffer and parent-retention rules as successful SQL. They can
be discarded when the buffer is full, after traversal sealing, or when the Gremlin parent is not
retained. Any emitted error span carries the error type and sanitized query text. SDK sampling
and export still apply.

Pseudo-implementation:

```java
private final AtomicLong lastHeartbeatNanos = new AtomicLong(0);
private final long defaultHeartbeatNanos;  // populated at SDK init from config

@Override
public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos) {
  // Record enabled query histograms before the trace gates.
  boolean isError = details.getErrorType().isPresent();
  if (!isError) {
    if (defaultHeartbeatNanos > 0 && tryClaimHeartbeat(defaultHeartbeatNanos)) {
      // Heartbeat slot claimed. Bypass the slow-query check and continue to completion routing.
    } else {
      Optional<String> tag = Optional.ofNullable(details.getQuerySummary());
      long thresholdNanos = SlowQueryThresholdResolver.global()
          .resolve(tag, details.getSlowQueryThresholdOverrideNanos().orElse(defaultThresholdNanos));
      if (thresholdNanos > 0 && executionTimeNanos < thresholdNanos) {
        // A rejected Gremlin completion must discard, count and clear its SQL candidates.
        return;  // Both gates rejected this completion.
      }
    }
  }
  // Route accepted nested SQL to its captured traversal buffer.
  // Route Gremlin completion through seal, parent creation and conditional drain.
  // Build direct SQL immediately. Use the cleanup contract in Buffered SQL children.
  // A Gremlin rejection or failure must discard, count and clear its remaining candidates.
}

private boolean tryClaimHeartbeat(long heartbeatNanos) {
  long now = System.nanoTime();
  long last = lastHeartbeatNanos.get();
  if (now - last < heartbeatNanos) return false;
  return lastHeartbeatNanos.compareAndSet(last, now);
}
```

The `AtomicLong.compareAndSet` resolves the multi-thread race: under load several queries finish within the same heartbeat window and all see `now - last >= heartbeatNanos`, but only the first CAS succeeds; losers fall through to the slow-query gate. Under load, exactly one successful completion per heartbeat window claims the slot. Other successful completions fall through to the slow-query check. Errors bypass both checks. Accepted completions then follow the routing and retention rules above.

### Edge cases / Gotchas

- The heartbeat clock is process-global per `OTelQueryMetricsListener` instance (one `AtomicLong`), not per-thread. This permits at most one successful heartbeat claim per interval regardless of QPS or thread count. Per-thread heartbeat would permit one claim per thread per interval, which scales noise with parallelism.
- No per-tag override exists in v1. The heartbeat samples the workload as a whole; whichever tag arrives first after the interval claims the slot. This biases visibility toward high-QPS tags by construction, which is the intent: heartbeat answers "what is the system spending its time on right now". Operators who need per-tag sampling streams configure downstream OTel pipeline filtering on `db.query.summary` against the global heartbeat output.
- A slow query that wins the heartbeat CAS passes the query trace gate once. The composition order skips the slow-query check after a heartbeat claim. Normal routing, retention and SDK sampling still apply.
- Error completions bypass both query gates without advancing the heartbeat clock. Nested SQL still needs buffer capacity and a retained parent. An error every N ms does not suppress heartbeat selection of successful queries.
- Heartbeat-emitted spans look identical to slow-query-emitted spans in the trace viewer; no `sample.reason` attribute distinguishes them in YTDB-496. Operators wanting to tell "this was a heartbeat sample" apart from "this was actually slow" compare span duration against the configured slow-query threshold downstream. Adding an explicit attribute is a future-ticket concern (low cost; would land alongside the structured-attributes extension).
- Mid-process changes to the heartbeat interval require an SDK rebuild, same constraint as the slow-query threshold default.
- A heartbeat interval shorter than the CAS cost (single uncontended CAS on a hot AtomicLong, ~10 ns) is wasted: the gate evaluates more often than it can possibly emit. Practical minimum is around 1 ms; sensible production defaults are 100 ms to 10 s.
- Disabling the heartbeat (interval = 0) makes the gate evaluate `if (0 > 0 && …)`, which short-circuits on the first conjunct. Zero overhead for the disabled case.
- Custom (non-OTel) `QueryMetricsListener` implementations are unaffected. The heartbeat gate lives only in `OTelQueryMetricsListener`; the listener iteration in `iterateAllQueryListeners()` still fires every registered listener for every query. Custom listeners wanting their own sampling implement it in their own callback.

### References
- D-records: D18 (time-based heartbeat sampling alongside slow-query gate, AtomicLong CAS for one successful heartbeat claim per window, single global interval with no per-tag override)

## Commit slow-query gating

The [SDK sampling contract](#sdk-sampling-contract) applies to successful and failed commit
candidates. Gate acceptance never overrides the configured SDK sampler.

The gate sits inside `OTelTransactionMetricsListener.writeTransactionCommitted`, not in the YTDB fire site. Same reasoning as the query gate (D16): other transaction listeners registered alongside the OTel one may have their own policy and must continue to see every event; the gate is OTel-specific configuration and lives in the OTel module; `TransactionDetails` already carries the input the gate needs (`executionTimeNanos` arrives as a method parameter; no error state to read because successful commits route through `writeTransactionCommitted` while failed commits route through `writeTransactionFailed` and bypass the gate by construction).

Before commit timing starts, the core entry checks `isTransactionMetricsEnabled()`. With no
listeners it commits normally without telemetry Context, clocks, snapshots or details objects.
With listeners present, freeze the combined transaction snapshot and per-TX listener list before
timing starts. Pass that operation snapshot to success or failure notification. Notification
does not reread a mutable listener list. The OTel callback reads current Context only on this guarded path. Embedded commit can use that host parent. Server commit uses `INTERNAL` with `setNoParent()`.

Pseudo-implementation at the top of the callback:

```java
private final long defaultCommitThresholdNanos;  // populated at SDK init from config

@Override
public void writeTransactionCommitted(TransactionDetails details,
                                      long committedAtMillis,
                                      long executionTimeNanos) {
  // Resolve and clamp the real completion endpoint once at callback entry.
  // Record elapsed duration before the trace gate and reuse the endpoint for span end.
  if (defaultCommitThresholdNanos > 0 && executionTimeNanos < defaultCommitThresholdNanos) {
    return;  // fast successful commit — skip span allocation
  }
  // existing span construction (sem-conv attributes, parent context, span.end)
}

@Override
public void writeTransactionFailed(TransactionDetails details,
                                   long failedAtMillis,
                                   long executionTimeNanos,
                                   Exception cause) {
  // Capture the same real endpoint and record the enabled failure histogram before building.
  // Failed commits always reach span construction with error.type. SDK sampling still applies.
}
```

The `defaultCommitThresholdNanos` final field is initialized at OTel listener construction from `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` multiplied to nanoseconds. Mid-process changes to the global default require an SDK rebuild — same constraint as the query-side threshold default.

### Edge cases / Gotchas

- Default `0` accepts every successful commit candidate. It does not bypass SDK sampling.
  Hosts that want to reject fast commits configure a positive value. The query default is also
  `0` (emit-all). Read-heavy workloads can raise the query threshold independently. Commit
  volume is bounded by transaction count.
- A commit whose duration equals the threshold passes the gate because the comparison is
  strictly `<` (less-than). A 100 ms commit against a 100 ms threshold becomes a candidate.
  The [SDK sampling contract](#sdk-sampling-contract) still applies.
- Failed commits (`writeTransactionFailed`) bypass the duration gate unconditionally. Each
  failure becomes a candidate regardless of duration. The caught cause supplies `error.type`
  and ERROR status for recording spans. SDK sampling can still drop or record-only the candidate.
- No per-tag override exists in v1. Commits fire at the transaction boundary and have no per-statement query tag context. If operators need per-database or per-host commit thresholds later, the natural extension is a `TagRule<Long>` resolver keyed on `TransactionDetails.getDatabaseName()` and configured via `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_DATABASE_RULES`; the resolver hierarchy already exists (mirrors the query-side `SlowQueryThresholdResolver`), so adding the per-database axis is one row in the OTel listener's gate. Not in v1 scope because no production demand has surfaced.
- The trace gate runs before commit span allocation. It reads only the duration and configured threshold. Metrics-enabled listeners record the commit histogram sample first. A gated-out commit then returns without building a span.
- Custom (non-OTel) `TransactionMetricsListener` implementations are unaffected. The gate lives only in `OTelTransactionMetricsListener`; the listener iteration in the merged-snapshot view still fires every registered listener for every commit. Custom listeners that want their own gating semantics implement it inside their callbacks.

### References
- D-records: D38 (commit-side slow-query threshold inside OTel listener, error bypass, single global threshold, no per-tag rules in v1)

## OpenTelemetry logs integration

The chokepoint is `SLF4JLogManager.log(...)` (`core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/SLF4JLogManager.java:38-103`), the single method every YTDB log call crosses. The logs integration adds a `LogAppenderHook` interface alongside `SLF4JLogManager`, a `CopyOnWriteArrayList<LogAppenderHook>` field on `SLF4JLogManager` itself, an `installAppenderHook(LogAppenderHook)` / `removeAppenderHook(LogAppenderHook)` accessor pair, and guarded hook dispatch at the existing emit point, before `logEventBuilder.log()` on line 98. Dispatch checks the hook list before any hook-specific preparation. No hooks means no telemetry Context read, clock read, formatting or event allocation. The ordinary SLF4J path remains independent. After a positive presence check, capture the iteration snapshot and stop hook dispatch if it is empty. Prepare hook arguments only for a non-empty snapshot. Later removal does not cancel captured callbacks. Before each iteration the manager skips records whose `requesterName` starts with `io.opentelemetry.` or equals `io.youtrackdb.otel.appender`; this name-prefix filter blocks the cross-thread recursive cycle that the OTel exporter would otherwise create through a `jul-to-slf4j` bridge (the exporter writes on its own thread pool where the per-thread re-entrance guard does not apply). Operators who need to admit specific OTel-internal loggers override the prefix via `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` (comma-separated full prefixes; defaults to the two values above). The hook signature is binding-agnostic: it takes the resolved requester class name, the resolved database name, the slf4j `Level`, both the format string and formatted message, the optional `Throwable` and the event timestamp. Hooks fire after the per-logger `isEnabledForLevel(level)` filter SLF4J already applies, so a hook subscribed at `INFO` for a logger configured at `WARN` sees no records below `WARN`. The OTel module ships `OTelLogAppender` as the only built-in implementation. `LogAppenderHook` is a public internal SPI (service provider interface) in the core logging package. The OpenTelemetry module and host implementations can implement it. It is not part of the supported public product API.

Severity mapping (slf4j `event.Level` → OTel sem-conv `severityNumber`):

| slf4j Level | OTel `severityNumber` | OTel `severityText` |
|---|---|---|
| `TRACE` | 1 | `TRACE` |
| `DEBUG` | 5 | `DEBUG` |
| `INFO` | 9 | `INFO` |
| `WARN` | 13 | `WARN` |
| `ERROR` | 17 | `ERROR` |

The mapping has no `FATAL` row because slf4j `event.Level` carries no `FATAL` constant (it stops at `ERROR`). The `OPENTELEMETRY_LOGS_MIN_SEVERITY` config accepts `FATAL` for forward compatibility but treats it identically to `ERROR` (severity number 17).

The following callback runs only after guarded dispatch found a non-empty hook snapshot.
Its severity and re-entrance checks precede Context, the observation clock and builder work.

Pseudo-implementation:

```java
public final class OTelLogAppender implements LogAppenderHook {
  private final Logger otelLogger;
  private final int minSeverityNumber;
  private final boolean includeMessageBody;            // OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY (default false)
  private final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false);
  private static final java.util.logging.Logger FALLBACK =
      java.util.logging.Logger.getLogger("io.youtrackdb.otel.appender");

  public OTelLogAppender(LoggerProvider provider, Severity minSeverity, boolean includeMessageBody) {
    this.otelLogger = provider.get("io.youtrackdb");
    this.minSeverityNumber = minSeverity.getSeverityNumber();
    this.includeMessageBody = includeMessageBody;
  }

  @Override
  public void onLog(String requesterName, String dbName,
                    org.slf4j.event.Level level,
                    String formatString, String formattedMessage,
                    Throwable thrown, long eventEpochNanos) {
    if (reentrant.get()) return;
    Severity severity = mapSlf4jLevelToOtel(level);
    if (severity.getSeverityNumber() < minSeverityNumber) return;

    reentrant.set(true);
    try {
      // Body-policy default-deny: ship the SLF4J format string unless the host opts in.
      // Keeps parameter values (raw SQL, record content, user identifiers) out of the log body.
      String body = includeMessageBody ? formattedMessage : formatString;
      LogRecordBuilder builder = otelLogger.logRecordBuilder()
          .setSeverity(severity)
          .setSeverityText(level.name())
          .setTimestamp(eventEpochNanos, TimeUnit.NANOSECONDS)   // exposed event time or guarded hook time
          .setObservedTimestamp(Instant.now())                    // appender invocation wall-clock
          .setBody(body);

      if (requesterName != null) {
        builder.setAttribute(AttributeKey.stringKey("code.namespace"), requesterName);
      }
      if (dbName != null) {
        builder.setAttribute(AttributeKey.stringKey("db.namespace"), dbName);
      }
      if (thrown != null) {
        builder.setAttribute(AttributeKey.stringKey("exception.type"), thrown.getClass().getName());
        builder.setAttribute(AttributeKey.stringKey("exception.message"),
            thrown.getMessage() == null ? "" : thrown.getMessage());
        builder.setAttribute(AttributeKey.stringKey("exception.stacktrace"),
            stackTraceToString(thrown));
      }
      // Context.current() is read for correlation only after guarded hook dispatch.
      // The callback's severity and re-entrance checks have also passed.
      builder.emit();
    } catch (RuntimeException | LinkageError | AssertionError t) {
      FALLBACK.log(java.util.logging.Level.WARNING, "OTel log emit failed", t);
    } finally {
      reentrant.set(false);
    }
  }
}
```

Before normal SLF4J emission, reuse an event timestamp only when the logging path explicitly exposes one. Otherwise capture a hook event timestamp after confirming a non-empty hook snapshot. The current `SLF4JLogManager` path exposes no event timestamp before `logEventBuilder.log()`. Its `setTimestamp(eventEpochNanos, NS)` therefore represents guarded hook-dispatch time. It does not guarantee backend event time or original caller-entry time. `setObservedTimestamp(Instant.now())` records when the appender observes the record. The fallback `Logger.getLogger("io.youtrackdb.otel.appender")` replaces the `Handler.reportError` channel a Java Util Logging (JUL) Handler would have used. The fallback logger is itself part of the SLF4J→JUL or SLF4J→Logback dispatch chain (via slf4j-jdk14 in server mode), so its output reaches the operator's normal log sink without re-entering the OTel hook (the appender's own logger name is filtered out at install time to keep the cycle impossible).

**Body-policy default-deny.** The `body` slot is set from `formatString` when `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY=false` (the default) and from `formattedMessage` when `true`. The default-false matches the trace-pillar discipline of `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=false`: log lines whose format string is `"Query failed: {} with args {}"` ship that string as the body, rather than `"Query failed: SELECT FROM User WHERE id = 42 with args [42]"`. The `exception.stacktrace` attribute (set from `Throwable.getStackTrace()`) and `exception.message` (from `Throwable.getMessage()`) are NOT gated by the same flag because the throwable identity itself is the load-bearing diagnostic signal for failure investigation; hosts that need to redact those install a host-side OTel `LogRecordProcessor` at the collector boundary. The flag is a default-deny knob for the body slot only.

The `SLF4JLogManager` hook branch runs after the existing `isEnabledForLevel(level)` filter. Check hook presence before preparing hook arguments. Capture the iteration snapshot and stop hook dispatch if it is empty. Reuse the ordinary path's formatted message. Reuse an event timestamp only when explicitly exposed. Otherwise capture a hook timestamp after confirming the non-empty snapshot. Prepare any separate hook-only message only on that guarded path. Hooks receive both the format string and the formatted message before the normal SLF4J emit. The appender chooses the body through its message-body flag without reformatting the arguments.

### Hard-context correlation across threads

D34 uses the host span active around traversal execution. Gremlin, SQL and commit spans are built
at completion and are never made current. The traversal Context carries tag, origin and buffer
values while preserving that host span. Logs during traversal and nested SQL work therefore carry
the host span ID. They carry no YTDB query or commit span ID. Without a host span, they have no
trace correlation. Late query span emission does not rewrite a log record already emitted.

Correlation requires the host context on the thread that writes the log. In server mode traversal
work runs on a Gremlin executor worker, including the per-session executor. Install traversal
Context values there. Network-thread Context alone cannot correlate worker logs. Server requests
have no remote parent in this design. A host using its own executor propagates its Context with
`Context.taskWrapping(...)` or `Context.wrap(...)`. Every receiving-thread scope must close on
that thread in `finally`. Background storage or exporter threads have no automatic query context.

### Edge cases / Gotchas

- A host that binds `slf4j-nop` (no-op SLF4J provider): SLF4J's per-logger `isEnabledForLevel(...)` returns `false` for every level under the NoOp provider, so `SLF4JLogManager.log(...)` short-circuits before the hook iteration and `OTelLogAppender.onLog(...)` is never invoked. This is the documented behavior — a host that explicitly disables logging gets no OTel logs either. Operators who want OTel logs without SLF4J output bind any non-NoOp provider with its level set high enough to suppress local output (e.g., `slf4j-jdk14` with `INFO` floor).
- High-frequency `DEBUG` / `TRACE` records (a tight loop logging every page read at `DEBUG`): SLF4J's per-logger level filter drops them before the hook iteration when the bound provider's level is set higher. When the bound level admits them, the severity floor drops them at the top of `onLog(...)` before any `LogRecordBuilder` allocation. Operators tune `OPENTELEMETRY_LOGS_MIN_SEVERITY` to control OTel-side volume independently from the bound provider's level config.
- An exception thrown from `OTelLogAppender.onLog(...)`: the `SLF4JLogManager` hook iteration wraps each hook call in `try { hook.onLog(...); } catch (Exception | LinkageError | AssertionError t) { /* fallback */ }` (same union as the listener wrappers per the exception-isolation contract). A throwing hook is logged via the same fallback `Logger.getLogger("io.youtrackdb.otel.appender")` channel and the next hook still fires; YTDB's own log call returns normally. `VirtualMachineError` and `ThreadDeath` propagate.
- Concurrent `setOpenTelemetry` calls during active logging: when the facade swaps SDKs, the previously-registered `OTelLogAppender` is removed via `SLF4JLogManager.removeAppenderHook(oldHook)` before the new one is installed, so each log record reaches exactly one OTel pipeline. The window between remove and install is small but non-zero; log records in that window are dropped on the OTel side but still reach the bound SLF4J provider (the existing path is untouched). Multiple `LogAppenderHook` implementations coexist by being separate list entries.
- Bootstrap-time logs (records emitted before `OPENTELEMETRY_LOGS_ENABLED` is read): these reach only the bound SLF4J provider, which is the desired behavior. Operators who need OTel coverage of bootstrap logs configure `OPENTELEMETRY_LOGS_ENABLED=true` via a JVM property (`-Dyoutrackdb.opentelemetry.logs.enabled=true`) so the flag is set before the first log call.
- Recursive logging from inside the OTel exporter: if the OTel exporter emits a log via `LogManager`, the hook would see its own log and feed it back into OTel, creating an unbounded loop. Two complementary guards cover the cases. The thread-local `reentrant` guard above breaks the same-thread cycle by short-circuiting `onLog(...)` when an `onLog(...)` is already on the stack for this thread. The cross-thread cycle (OTel exporter writes a log on its own thread pool via `jul-to-slf4j` bridge, which routes JUL into SLF4J and back into `LogManager`) is caught by the `requesterName`-prefix filter in `SLF4JLogManager.log(...)`: records whose requester logger name starts with `io.opentelemetry.` (the OTel SDK's own logger namespace) or equals `io.youtrackdb.otel.appender` (our fallback channel) skip the hook iteration entirely. The `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` config entry lets operators tune the prefix list when a deployment needs to admit specific OTel-internal logger names.
- Hosts already using the OTel `opentelemetry-instrumentation-logback-appender` (or its log4j equivalent) on their SLF4J binding: that path catches every log record SLF4J emits, including YTDB's. Running both side-by-side double-emits each YTDB log record (once via the host's appender, once via YTDB's hook). Operators run one or the other, not both. The hook is the recommended path because it carries the `Context.current()` read at the YTDB invocation site, not at the host's appender callback site (potentially on a different thread).

### References
- D-records: D34 (single-chokepoint `LogAppenderHook` invoked inside `SLF4JLogManager.log`, severity-floor pre-filter, host-span correlation from `Context.current()` during traversal work), D35 (thread-local re-entrance guard against recursive logging from inside the OTel exporter)
- Invariants: Listener exception isolation (the hook follows the same isolation contract; a throw from `onLog` is caught at the iteration site and reported via the fallback `Logger.getLogger("io.youtrackdb.otel.appender")` channel)

## Metrics integration

The bridge surfaces two layers: sem-conv DB metrics with names defined by the OpenTelemetry spec, and YTDB-specific gauges under the `youtrackdb.*` namespace. The CoreMetrics inventory carries some of these today; this design adds the rest alongside the `MetricGroup` enum that drives the included-groups filter. Each row is annotated `(existing)` when its profiler source already lives in `core/.../profiler/metrics/CoreMetrics.java`, or `(new)` when this design adds the underlying `MetricDefinition` plus the writer site that populates it.

**Sem-conv v1.33.0 DB metrics for embedded caller-facing work (curated subset):**

| OTel metric name | Stability | Instrument | YTDB source |
|---|---|---|---|
| `db.client.operation.duration` | stable | `DoubleHistogram`, unit `s` | embedded query and commit listeners record the non-negative difference between recorded real completion and start endpoints, converted to seconds, before trace gates. EXACT active time remains a separate span attribute and gate input. Use advisory buckets `[0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10]`. |
| `db.client.response.returned_rows` | experimental | `DoubleHistogram` | embedded row-count distribution from `QueryDetails.getResultCount(): OptionalLong` (the listener contract adds the accessor; the SQL fire site populates it from the `InstrumentedSqlResultSet` wrapper's per-`next()` row counter, which is correct for both `LocalResultSet` and `CachedResultSetView` inner result-sets per YTDB-820 coordination; the Gremlin path populates it from `YTDBQueryMetricsStep`'s row counter, incremented after each successful `super.next()` on the terminal step, uniformly across Path A and Path B because the step is appended at the pipeline tail by `YTDBQueryMetricsStrategy.apply`) |

**YTDB-specific (`youtrackdb.*` namespace):**

| OTel metric name | Group | Instrument | Profiler source |
|---|---|---|---|
| `youtrackdb.server.operation.duration` | `queries` or `transactions`, by listener | `DoubleHistogram`, unit `s` | server query and commit execution. Use the same elapsed measurement, closed query or commit schema and duration buckets as the embedded duration instrument. |
| `youtrackdb.server.response.returned_rows` | `queries` | `DoubleHistogram`, unit `{row}` | server query returned rows. Use the same measurement, closed query schema and row buckets as the embedded returned-row instrument. |
| `youtrackdb.session.active_count` | `queries` | `ObservableLongUpDownCounter`, unit `{session}` | count successfully opened, not-yet-closed database session objects from the new `DatabaseSessionRegistry`, including embedded and internal schema sessions. Idle open sessions count. Failed opens do not count. Repeated close does not decrement twice. One process-wide observation with no attributes. Sessions are not pooled connections. |
| `youtrackdb.cache.hit_ratio` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.CACHE_HIT_RATIO` (existing — `Ratio`, cache hits over reads, 60s window) |
| `youtrackdb.disk.read_rate_bps` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.DISK_READ_RATE` (existing — `TimeRate`, bytes per second, 60s window) |
| `youtrackdb.disk.write_rate_bps` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.DISK_WRITE_RATE` (existing — `TimeRate`, bytes per second, 60s window) |
| `youtrackdb.cache.page_reads` | `cache` | `ObservableLongCounter`, unit `{page}` | new cumulative monotonic page-read counter populated by the disk-cache page-fault path |
| `youtrackdb.cache.evictions` | `cache` | `ObservableLongCounter`, unit `{page}` | new cumulative monotonic page-eviction counter. Page eviction events exist without a counter. `FILE_EVICTION_RATE` counts file-handle eviction and is not this source. |
| `youtrackdb.wal.pending_bytes` | `wal` | `ObservableLongGauge` | `CoreMetrics.WAL_PENDING_BYTES` (new — `Gauge<Long>`, in-flight write-ahead log (WAL) backlog read at the WAL writer's `flushPending()` site) |
| `youtrackdb.wal.flush_rate_bps` | `wal` | `ObservableDoubleGauge` | `CoreMetrics.WAL_FLUSH_RATE` (new — `TimeRate`, WAL flush bytes per second, 60s window) |
| `youtrackdb.lock.contention_count` | `locks` | `ObservableLongCounter` | `CoreMetrics.LOCK_WAIT_COUNT` (new — `Counter`, monotonic wait-event count populated by `ReadWriteLock` wrappers in the storage layer) |
| `youtrackdb.storage.size_bytes` | `storage` | `ObservableLongGauge` (per `db.namespace` attribute) | `CoreMetrics.DATABASE_SIZE_BYTES` (new — per-`MetricScope.Database` `Gauge<Long>` read from the storage layer's `getSizeOnDisk()`) |
| `youtrackdb.transaction.commit_rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_WRITE_RATE` (existing — `TimeRate`, write commits per second, 60s window) |
| `youtrackdb.transaction.rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_RATE` (existing — `TimeRate`, all transactions per second, 60s window) |
| `youtrackdb.transaction.rollback_rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_WRITE_ROLLBACK_RATE` (existing — `TimeRate`, write rollbacks per second, 60s window) |
| `youtrackdb.transaction.active_count` | `transactions` | `ObservableLongGauge` | `CoreMetrics.ACTIVE_TX_COUNT` (existing — per-database `Gauge<Integer>`) |
| `youtrackdb.transaction.oldest_age_seconds` | `transactions` | `ObservableLongGauge` | `CoreMetrics.OLDEST_TX_AGE` (existing — per-database `Gauge<Long>`) |

D42 also reserves these high-level advisory metrics. Each may move to a follow-up change.
Their engine sources remain open questions. No detailed engine design is specified here.

| OTel metric name | Group | Meaning | Instrument and unit | Labels | Engine source question |
|---|---|---|---|---|---|
| `youtrackdb.cache.eviction_rate` | `cache` | page evictions per second over the reporting window | `ObservableDoubleGauge`, `{page}/s` | none, global page cache | page eviction events exist, but there is no cumulative counter. Which source and reporting window supply this rate? `FILE_EVICTION_RATE` is file-handle eviction. May move to a follow-up change. |
| `youtrackdb.query.index_lookup_ratio` | `queries` | index lookups divided by index lookups plus full scans over the reporting window. Omit when the denominator is zero. | `ObservableDoubleGauge`, `1` | `db.namespace` | no profiler source exists today. Which engine observation sites and window supply the two populations? May move to a follow-up change. |
| `youtrackdb.connection.pool_saturation` | `queries` | acquired slots divided by total configured capacity across embedded database pools for a database. Omit without a positive known capacity. | `ObservableDoubleGauge`, `1` | `db.namespace` | no saturation source exists today. Capacity exists only for the embedded database pool. Which source supplies occupancy and capacity together? May move to a follow-up change. |

Counter names follow OTel naming guidance without a redundant total suffix. Both page counters
need real cumulative monotonic sources. `TimeRate` exposes only a windowed rate and cannot be
converted into either cumulative counter. Rate integration in the bridge is not a valid source.
The page-eviction count and advisory eviction rate describe the same event population.

Sem-conv stability matters for dashboard authors: `stable` metrics will keep their names and semantics in future spec revisions; `experimental` ones may rename or change attribute shapes between sem-conv versions. The bridge surfaces both, but a downstream dashboard that depends on experimental metrics needs to track the sem-conv changelog between v1.33.0 and whatever version YTDB pins next.

`DatabaseSessionRegistry` is a new component. The metrics work adds its active-session accounting and read sites. Tests cover successful and failed opens, idle sessions, internal schema sessions and repeated close. `Counter` is also a new profiler primitive, with a matching `MetricType` and writer sites for the new monotonic counts. Existing profiler primitives are `Gauge`, `Stopwatch`, `TimeRate` and `Ratio`.

Embedded caller-facing work uses the two standard `db.client.*` instruments above. Server-mode request execution and local internal server work use `youtrackdb.server.operation.duration` and `youtrackdb.server.response.returned_rows`. Choose the instrument by deployment before cache lookup. Never combine the embedded and server populations in a client instrument. Query and commit durations share the selected duration instrument but keep their respective schemas. The query listener records returned rows on each completion when `getResultCount()` is present. Both deployments use the same duration and row buckets. Each histogram uses the complete schema below. An optional attribute is absent when its source is unavailable. Strings preserve supplied case. Integer ports keep their integer type.

| Histogram and recording source | Complete sample attributes |
|---|---|
| `db.client.operation.duration` embedded or `youtrackdb.server.operation.duration` server, query listener, group `queries` | `db.system.name="youtrackdb"`, available `db.namespace`, eligible `db.operation.name` and `db.collection.name`, host tag or generated `db.query.summary` when available, `error.type` and available string `db.response.status_code` only on failure, configured `server.address` and integer `server.port` only in server mode, `youtrackdb.duration_semantics`, and exactly one query-source field: `youtrackdb.gremlin.path` for Gremlin or `youtrackdb.sql.invoked_via` for SQL |
| `db.client.operation.duration` embedded or `youtrackdb.server.operation.duration` server, commit listener, group `transactions` | `db.system.name="youtrackdb"`, available `db.namespace`, `db.operation.name="commit"`, `error.type` and available string `db.response.status_code` only on failure, configured `server.address` and integer `server.port` only in server mode, `youtrackdb.duration_semantics="wall_clock"`. No collection, summary or query-source field. |
| `db.client.response.returned_rows` embedded or `youtrackdb.server.response.returned_rows` server, query listener, group `queries`, unit `{row}` | exactly the query-duration attribute list, including the same optional-value rules |

These lists are closed. No query text, parameter, tracking ID, numeric active time, arbitrary
custom attribute or structural flag is a histogram dimension. The query summary retains the
existing tag cardinality responsibility. Operation and collection use independent whole-call eligibility tests, as on spans.
Omit only the attribute whose eligibility cannot be proved. Commit supplies its known operation
under that same rule and has no collection. Query summary selection never changes the original tag used by resolvers. Generated summaries contain only recognized operations
and static targets. The optional typed response code is a failure-only dimension next to
`error.type` in both duration instruments and the shared query returned-row schema. It is absent
on success and on failures without a typed code. Stored-procedure identity and network peer
are unavailable from these fire sites, so those optional dimensions are absent. Duration and row
histograms share dimensions but keep distinct instrument identities in the cache. Record one
sample per completion even when trace gates or SDK sampling drop the span. Skip rows only when
`getResultCount()` is empty. Use row advisory buckets
`[1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000]`.

Elapsed duration always includes consumer idle time in both timing modes. The
`youtrackdb.duration_semantics` field describes the unchanged listener measurement used by
gates. EXACT spans expose that active measurement as `youtrackdb.active_time_nanos` only
after the recording check. Reuse each captured real endpoint for metrics and spans. A custom
producer without an endpoint uses one callback-entry timestamp. Clamp an endpoint before the
start to the start once before metrics, snapshot retention or span building. Buffered SQL snapshots preserve both endpoints and active time.

Tests must compare exact attribute key sets, types and absence rules for all three schema rows
and both instrument families. Assert that server-mode completions never record in client
instruments and that buckets match across deployments.
Cover an operation without a collection, a collection without one operation, single and multiple
collections, generated summaries and host tags, successful and failed queries and commits,
both deployments, both timing modes, zero returned rows and missing counts.
Cover typed-code failures, null-code failures and failures outside `CoreException` for queries
and commits. Assert string codes on builders and metric samples, absent codes on success and
untyped failures, and no message parsing. Cache tests distinguish absent and different codes.
Toggle `OPENTELEMETRY_QUERY_INCLUDE_RETURNED_ROWS` with present and missing counts. Assert
span opt-in behavior and identical histogram samples with the setting off and on.
Assert profile, conditional cache-outcome and truncation attributes with their documented
types and absence rules. Keep exact numeric custom values off every histogram schema.
A consumer pause must increase span and histogram elapsed duration without changing EXACT
active time or its threshold decision. Drain must preserve the recorded SQL endpoints and add
no second metric sample.

Public-query totals select outer Gremlin samples plus SQL with `invoked_via=native`. They exclude commits and Gremlin-origin SQL. The SQL entry context captures `YTDBContextKeys.GREMLIN_ORIGIN`, independent of the tag. Its presence gives `invoked_via=gremlin_dsl` for graph-step fallback as well as domain-specific language (DSL) passthrough. Parent IDs and span attributes read at close cannot identify this provenance.

Profile does not change normal span selection or retention. Path B steps that enter SQL can emit an outer Gremlin span with retained SQL children. Path A profiles the steps of the held plan before execution and reads their costs afterward. Path B retains its executed graph-step plan. Explain renders those plan objects. ClassCount has no plan and its profiling flag is a no-op. Profile metrics are forwarded to TinkerPop separately from OTel histograms.

Histograms are synchronous instruments. The SDK has no asynchronous histogram. The metrics switch and included-group filter govern listener instruments as well as bridge callbacks. Trace gates do not discard metric samples. The shipped rate, count and latency panels consume these metrics through Prometheus.

### Histogram attribute reuse

`Attributes` is the immutable OpenTelemetry set of typed key/value pairs attached to a metric
sample. Query and commit listeners reuse precomputed sets instead of rebuilding them on each
completion. Apply the metrics switch and included-group filter before any cache lookup or
attribute work. Metric recording remains before trace gates and independent of span sampling.

Each listener owns a fixed `AtomicReferenceArray` with 1024 slots. A slot holds one immutable
entry with an instrument identity, that instrument's complete existing dimension tuple and its
`Attributes`. Instrument identity means the instrument object reference. Select the embedded or server
instrument before lookup. Their distinct references cannot share a cache entry. The dimension tuple is
the complete typed set of dimension values and absence markers for one instrument. It distinguishes
absent values from empty strings. The cache key uses exactly the instrument's current attribute
list. It adds no metric dimension and removes none. Instruments with different attribute lists cannot share an entry.
Never add query text, tag or transaction ID to a key unless the existing schema requires it.

A direct-mapped atomic cache sends each key to one slot by its hash. Atomic reads and replacement
safely publish immutable entries across threads. Compute the hash and compare sample components
without allocating a tuple or map. Read the slot once and keep the entry in a local reference.
A hit requires the same instrument reference and equality of every typed dimension component,
including presence markers. Include the optional typed response code in hashing and equality
for each schema that carries it. Distinguish an absent code from a present code.
Compare strings by value. Hash equality alone is never a hit.
Reuse that local entry's `Attributes` so concurrent replacement cannot change this sample's set.
On a miss, build the full correct set and an immutable entry outside shared state.
Try one compare-and-set replacement against the entry just read. A collision replaces that one entry if the compare-and-set succeeds. A lost
race keeps the newly built set local to this sample. Do not retry, wait, scan or use an overflow
map. Cache state never changes sample values or decides whether to record a sample.

The cache retains at most 1024 entries per listener, regardless of distinct database or
collection values. It bounds entry count rather than payload bytes. Concurrent misses may
briefly hold additional local sets until their calls finish. Unregistration prevents inclusion
in future transaction snapshots. Existing snapshots may still invoke the listener and use its
bounded cache. Release the cache with the listener after its final retained snapshot and in-flight
callback finish. Unregistration alone is not final release. A full cache needs no special path
because every miss targets one slot. Repeated collisions can reduce reuse but cannot truncate attributes or drop samples.
The cache preserves the selected instrument name, complete attribute list, absent-value rules
and source selection. It bounds retained attribute sets, not SDK aggregation cardinality.
Distinct summaries and namespaces can still create many metric series.

Tests must cover hit reuse without new keys or sets, colliding tuples, absent versus empty
values, two instruments with different schemas, two distinct instrument objects with identical
dimension values, and concurrent replacement loss. Cover callbacks retained across unregistration
and cache lifetime through the final retained snapshot and in-flight callback. Compare all
recorded attribute sets and sample counts with the uncached construction. Assert that disabled
instruments do no cache work and SDK-dropped spans still produce their enabled metric samples.

### MetricsRegistry enumeration and lazy-registration API

`OTelMetricsBridge` cannot rely on call-site knowledge of every `MetricDefinition` — this design adds enough new ones that a hand-maintained mirror list would drift. Two new public-API methods on `MetricsRegistry` close the gap:

```java
public interface MetricVisitor {
  void visit(String fullyQualifiedName, MetricDefinition<?, ?> def, Metric<?> metric,
      @Nullable String databaseName);
}

public void forEachMetric(MetricVisitor visitor);  // walks GLOBAL + every DatabaseMetrics group, calls visitor exactly once per metric instance currently registered

public record MetricRegistrationEvent(
    String fullyQualifiedName,
    MetricDefinition<?, ?> def,
    Metric<?> metric,
    @Nullable String databaseName  // null for GLOBAL scope metrics
) {}

public void addRegistrationListener(Consumer<MetricRegistrationEvent> listener);
public void removeRegistrationListener(Consumer<MetricRegistrationEvent> listener);
```

Implementation: `MetricsRegistry` holds a `CopyOnWriteArrayList<Consumer<MetricRegistrationEvent>>`. It checks listener presence before capturing an iteration snapshot. If that snapshot is empty, it stops event dispatch before allocating a registration event. Otherwise it fires the captured snapshot inside `MetricsGroup.init(...)` (the existing `computeIfAbsent` site) when a new metric is created. Later removal does not cancel callbacks captured for that event. The fire is synchronous on the registering thread. Listeners that need to do heavy work (registering an OTel observable callback is non-trivial) must schedule it onto their own executor. `OTelMetricsBridge` posts to its scheduled executor so the registration thread is not blocked. Each listener fire runs inside `try { listener.accept(event); } catch (Exception | LinkageError | AssertionError t) { LogManager.instance().warn(this, "metric-registration listener threw", t); }`, so a misconfigured listener logs at WARN and the database-open path completes — the API enforces exception isolation rather than relying on listener-side discipline.

`forEachMetric(...)` walks `globalMetrics.mGroup.metrics`, then iterates `perDatabaseMetrics.values()` walking each `DatabaseMetrics.mGroup.metrics`. The walk over `ConcurrentHashMap.entrySet()` is weakly consistent. The bridge subscribes before the walk, then sends both enumeration results and registration events through one serialized registration path. That path de-duplicates by source metric identity. A metric seen by both paths gets one callback. Enumeration and events both carry the database name. Metrics sharing an OTel name use a shared instrument with a `db.namespace` attribute on each database-scoped observation.

### Async instrument lifecycle

`OTelMetricsBridge.start()` does three things at SDK init:

1. Subscribe through `registry.addRegistrationListener(...)`. Each event posts a registration task to the bridge executor.
2. Enumerate through `Profiler.getMetricsRegistry().forEachMetric(...)`. Send every result to the same executor. Keep an identity-keyed `Map<Metric<?>, AutoCloseable>` of callback handles. Register each source once, even when the walk and listener both see it. Reuse the instrument for a shared OTel name and attach the `db.namespace` attribute to observations. Each callback captures its source `Metric` reference. At collection time it first checks that its bridge registration is active. Only then does it read the value or prepare observation attributes.
3. Schedule a periodic task at `OPENTELEMETRY_METRICS_PERIOD_MILLIS`. The SDK's `PeriodicMetricReader` drives the actual export; the bridge's task re-reads YTDB-side time-windowed metrics (`TimeRate`, `Ratio`) via `getRate()`/`getRatio()` independently of the OTel reader. `Meter` flushes those windows internally every `flushRateTicks`; the task keeps the value the exporter sees fresh between polls.

`OTelMetricsBridge.stop()` removes the listener and cancels the scheduled task. It prevents queued registrations from installing callbacks after stop. It closes each observable callback handle through `AutoCloseable.close()` and clears the identity map. Stopping a stopped bridge is a no-op. Synchronous histograms need no callback handle.

`OTelMetricsBridge.refresh()` drains pending bridge registration work for tests. It does not collect SDK metrics. Tests then call `InMemoryMetricReader.collectAllMetrics()` to invoke observable callbacks and collect histogram data without waiting for a periodic export.

### Counter group filter

Operators with cardinality constraints opt into subsets via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS`. The bridge classifies each profiler metric into one of six groups at registration time, reading a `group()` annotation on `MetricDefinition` (an enum with values `QUERIES`, `CACHE`, `STORAGE`, `WAL`, `LOCKS`, `TRANSACTIONS`, set per `Metric` at construction time — this design adds the enum and the annotation). When the included-groups set is non-empty, the bridge skips `register()` for metrics whose group is excluded; the corresponding OTel metric simply does not appear at the exporter.

Group classification table (drives both `MetricDefinition.group()` returns and the filter parsing):

| Group | Examples (profiler-side) |
|---|---|
| `queries` | query duration histogram, query throughput, result-row distribution |
| `cache` | page-cache hit ratio, page reads, eviction count |
| `storage` | per-database size, page count, allocated bytes |
| `wal` | pending bytes, flush rate, segment count |
| `locks` | contention count, wait time histogram, deadlock count |
| `transactions` | active count, commit rate, rollback count |

Operators wanting metrics on storage health but not query throughput configure `OPENTELEMETRY_METRICS_INCLUDED_GROUPS=storage,wal,locks`. The default empty value enables every group, matching "metrics ON = everything" semantics; a host that wants nothing at all leaves `OPENTELEMETRY_METRICS_ENABLED=false` rather than emptying the included list.

### Edge cases / Gotchas

- High-cardinality attributes: a `youtrackdb.storage.size_bytes` per-database gauge fans out one OTel data point per `db.namespace` attribute value at every collection cycle. Hosts with 10k+ databases see 10k+ data points per period; the exporter side has to absorb that. The bridge does NOT add per-class or per-RID attributes — `MetricScope.Class` profiler entries collapse to one OTel data point per (metric, `db.namespace`) pair. If per-class breakdown is needed downstream, the host configures a dedicated OTel exporter that views the raw profiler dump.
- Bridge thread vs profiler-thread contention: the profiler's own collection threads (the `ScheduledExecutorService` passed to `Profiler` constructor) may be updating a `TimeRate` or `Ratio` while the bridge's callback reads through it. Both sides use lock-free reads on the metric primitives (`Gauge.value()` is a volatile read; `TimeRate.getRate()` snapshots an `AtomicReference`), so the contention surface is the `AtomicReference` CAS in `Meter`'s internal flush. Worst case: the bridge reads a sample one window behind reality. Acceptable for 10-second collection cadence.
- Exporter back-pressure: OTel `PeriodicMetricReader` blocks the SDK's internal export thread when the configured exporter (OTLP, Prometheus) backs up. The bridge's scheduled task is independent of the reader — it advances time-windowed YTDB metrics regardless of exporter state — so back-pressure on the OTel side does not stall YTDB's in-JVM metric collection.
- SDK swap during active metrics: when `setOpenTelemetry` swaps SDKs, the old `OTelMetricsBridge.stop()` runs before the new one's `start()`, so each source callback is registered against exactly one `SdkMeterProvider` at a time. The window between stop and start is small; any metric reader poll in that window sees zero data points (the SDK-side callbacks are unregistered).
- Disabled metrics with a host-wired SDK: when `OPENTELEMETRY_ENABLED=true` and `OPENTELEMETRY_METRICS_ENABLED=false`, the bridge is not started even if the host's OTel SDK carries a real `MeterProvider`. The host's other instrumentations continue to emit; only YTDB's bridge stays silent. This is intentional — a host that wants YTDB metrics specifically must opt them in independently of the master switch.
- Profiler not initialized: if the bridge is started before `Profiler.onStartup()` completes (race during very early bootstrap), the initial `forEachMetric(...)` walk visits zero metrics. The `addRegistrationListener(...)` subscription still fires for every metric `Profiler.onStartup()` subsequently creates, so the bridge ends up fully wired without a separate retry path. No WARN is emitted in this case — the late-arriving registration events are the expected wiring sequence under early bootstrap.
- Late-DB-open registration event: when `databaseMetric(def, dbName)` first runs for a new database, the registration listener fires synchronously on the registering thread. The bridge handler posts the OTel observable-callback registration to its own `ScheduledExecutorService` and returns immediately, so the database-open path is not blocked by OTel-side allocation. The first metric reader poll after the registration sees the new instrument; polls before the registration land see no data point for that database (consistent with the database simply not having been registered yet).
- Concurrent enumeration race: a database opened concurrently with `start()`'s `forEachMetric(...)` walk may or may not be visited by the walk — `ConcurrentHashMap.entrySet()` is weakly consistent. Either outcome is correct: the `addRegistrationListener(...)` subscription registered before the walk catches anything the walk missed, so every metric registers exactly once across the combination of walk + listener fires. The serialized registration path uses source metric identity to handle a metric seen by both paths. Database names are observation attributes, so two databases with the same metric name remain distinct.

### References
- D-records: D36 (OTelMetricsBridge surfaces `Profiler.getMetricsRegistry()` via OTel async instruments at a configurable period, with a scheduled task re-reading YTDB-side `TimeRate`/`Ratio` rates, flushed internally by `Meter`, independently of the OTel reader), D37 (group-based opt-in via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` with six-group taxonomy `queries`/`cache`/`storage`/`wal`/`locks`/`transactions`)
- Invariants: Listener exception isolation (exceptions in observable callbacks are caught and reported via OTel's own error handler, never propagating to the profiler), Counter source untouched (the bridge reads; the profiler keeps writing on its own threads independent of OTel state)

## Telemetry limits and loss counters

The facade preserves SDK ownership. YTDB-owned SDKs support standard exporter-selection
settings such as `otel.traces.exporter`, `otel.logs.exporter` and `otel.metrics.exporter`, with
their environment equivalents. The default OTLP pipeline uses autoconfiguration's batch span
and log processors. Standard `otel.bsp.*` and `otel.blrp.*` properties apply only to exporters
that use these batch processors. Console/logging exporters can use simple processors instead.
Host-owned pipelines stay untouched. YTDB log and metric switches keep their existing scope.

Both batch queues default to 2048 records, both batches to 512 records. The span worker delay
is 5000 ms and the log worker delay is 1000 ms. Each processor's export wait timeout defaults
to 30000 ms. The `max.queue.size` and `max.export.batch.size` suffixes accept positive integers.
The `schedule.delay` and `export.timeout` suffixes accept whole numbers with optional `ns`, `us`,
`ms`, `s`, `m`, `h` or `d` suffixes. Unitless values mean milliseconds. Negative values fail
configuration. A full batch can export before the scheduled delay expires. The next delay starts
after export completion. An export timeout of zero means an unbounded processor wait.
Environment names use uppercase with dots replaced by underscores. System properties take
precedence. Preserve SDK validation, including clamping a batch larger than its queue with a
warning.

The SDK rejects an offer when its queue is full. It does not wait for capacity or spill to disk.
This is not a lock-free guarantee. The 1.66.0 span queue uses JCTools, a concurrent-queue library.
The log queue uses `ArrayBlockingQueue`. Do not replace either queue. Each worker can hold a
separate batch while records enter its queue. Encoding and transport retries add state outside those bounds. SDK
queue capacities and the per-traversal SQL limit bound counts, not total heap usage.
With no drain, 2048 slots hold about 205 ms of arrivals at 10,000 records per second, or 20 ms
at 100,000. These are capacity calculations, not measured stall tolerances.

YTDB adds only `OPENTELEMETRY_QUERY_TEXT_MAX_LENGTH` for query text. The facade validates a
non-negative integer and captures it per OTel listener installation. The default is 4096
Unicode code points per value. `0` omits query-text attributes. Apply it to every YTDB-produced
query-text attribute, including `db.query.text`, scripts, complete passthrough text and the three
possible `youtrackdb.gremlin.embedded_sql.N` values. Tags and classified summaries are
identifiers rather than source text and keep their contracts. Parameters remain a separate opt-in.

Sanitize the complete input through its source-specific sanitizer before taking a prefix.
SQL scripts use a separate branch that sanitizes their complete parsed script representation.
Scripts in other languages carry no query text. A null statement, missing script representation
or sanitizer failure never falls back to raw script text. Never truncate raw SQL before parsing.
Apply the query-text limit only to successfully sanitized output.
For a positive limit, find the prefix end by code points and copy only that prefix. Do not split
a UTF-16 surrogate pair and do not append an ellipsis. A value exactly at the limit is unchanged.
At limit zero, resolve sanitized text only to determine whether a non-empty value was omitted.
Attach and retain no text. Set one boolean `youtrackdb.query.text_truncated=true` if any
non-empty sanitized value was shortened or omitted. Otherwise omit the marker. Sanitizer failure
is not truncation and does not by itself set the marker. The marker describes YTDB's own action,
independent of further SDK or collector truncation.

Direct SQL and Gremlin run this work only after `isRecording()`. Accepted nested SQL must apply
it while copying its immutable snapshot before buffer admission. Store the bounded strings and
one marker flag without retaining the full sanitized strings or lazy accessors. At drain,
attach those values only if the child records. The snapshot exception and recording-only
attribute contract remain intact. Custom listeners retain access to their usual query details.
A host-owned SDK receives the same bounded YTDB values without changing its global span limits.
The existing nested-SQL buffer size, synchronization, parent retention and drop accounting stay
unchanged. Sanitization may still allocate a complete rendering transiently. This design makes
no byte-budget claim for that work, parameters, log bodies, exceptions or other attributes.

Use SDK self-metrics rather than a second accounting path. SDK 1.66.0 uses the `LEGACY` internal
telemetry schema by default. Its legacy self-metrics need no separate experimental enable flag
when autoconfiguration supplies an exporting meter provider.
`otel.experimental.sdk.telemetry.version=latest` selects different names and labels.
The default schema supplies these monotonic counters. Units count records, not failed requests.

| Loss | Metric and instrumentation scope | Selection and meaning |
|---|---|---|
| Span queue overflow | `processedSpans`, scope `io.opentelemetry.sdk.trace` | `processorType=BatchSpanProcessor`, `dropped=true`. One increment per rejected span. |
| Log queue overflow | `processedLogs`, scope `io.opentelemetry.sdk.logs` | `processorType=BatchLogRecordProcessor`, `dropped=true`. One increment per rejected log record. |
| OTLP export failure | `otlp.exporter.exported`, scope `io.opentelemetry.exporters.otlp-grpc` or `io.opentelemetry.exporters.otlp-http` | `success=false`, `type=span`, `log` or `metric`. Counts records in an export that finishes with failure. |

These counters cover their entire processor or exporter. In a shared SDK they include records
from other instrumentations. Their documented attributes cannot isolate YTDB losses. Do not
present them as YTDB-only loss totals.

`queueSize` in the corresponding processor scope reports pending records with `processorType`.
The `dropped=false` processed series counts records handed to export. It does not prove delivery.
Do not add it to exporter failures as a success total. A failed export counts its batch records
once at exporter completion after transport retry handling, rather than once per retry attempt.
Do not infer total losses by subtracting exported totals from query totals. Gates, SDK sampling,
child retention and incomplete operations describe different populations.

Autoconfiguration wires its meter provider into the batch processors and OTLP exporters.
When `OPENTELEMETRY_METRICS_ENABLED=true`, the owned SDK exports available self-metrics through
its normal metric reader and selected metric exporter. No export visibility is promised when
that exporter is disabled. These SDK instruments bypass the YTDB database-group allow-list.
Metrics off configures no self-metric export path in a YTDB-owned installation. It adds no
private counter reader. The buffered-child backing total keeps its always-advance rule.

For a host-owned SDK, the switch disables only YTDB bridge and listener metrics. Host-configured
self-metrics may continue exporting unchanged. Programmatically configured hosts must supply a
meter provider to their batch processors and OTLP exporters and register an exporting metric
reader. Do not cast, replace or wrap host providers, processors or exporters. A no-op provider
or a pipeline without these instruments gives unknown loss counts. YouTrackDB adds no queue
alias, loss-counter alias, exporter decorator, additional sampler or new metric group.

These counters cover queue overflow and exporter-reported failures. SDK 1.66.0's default schema
does not count processor rejection after shutdown. Processor wait expiry alone does not prove
that a transport failed, and collector partial rejection or downstream loss is not a local
failure count. SDK diagnostics and collector monitoring complement the counters. If metrics
share a failed destination, the backend may receive no loss report until recovery, and it may
never receive one before process exit. Absence of samples must never render as zero loss.
An operator-selected SDK telemetry schema can change names and labels. Keep SDK-native names
and document the selected schema rather than manufacturing duplicate vendor counters.

Required tests cover truncation below, at and above the limit, supplementary Unicode characters,
zero, empty text, invalid configuration and sanitizer failure. Verify complete parsed SQL-script
sanitization, omitted text for other languages and no raw fallback for missing representations.
Assert that zero retains no text and sanitizer failure alone sets no marker. Cover embedded SQL,
recording-only work, bounded snapshot strings and marker propagation. Assert that names, tags,
rule results and custom listener text stay unchanged. With tiny SDK queues and a deliberately
stalled test exporter, verify finite retention, rejected offers and the exact drop series.
Exercise failed OTLP completion and successful retry without double counting. Collect through
an in-memory metric reader so a failed remote endpoint cannot hide the assertion. Test metrics
off, group filtering and a host-owned SDK whose configuration and lifecycle remain untouched.
Assert that host SDK self-metrics continue with YTDB metrics off and that shared counters include
other instrumentations. Cover owned standard exporter selection with batch and simple processors.
Verify that BSP and BLRP settings affect only batch pipelines and no metric exporter gives no
exported self-metric visibility.

Reference sources for SDK 1.66.0:
- [Span builder defaults](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessorBuilder.java) and [log builder defaults](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk/logs/src/main/java/io/opentelemetry/sdk/logs/export/BatchLogRecordProcessorBuilder.java).
- [Internal telemetry schema selection](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk-extensions/autoconfigure/src/main/java/io/opentelemetry/sdk/autoconfigure/InternalTelemetryConfiguration.java).
- [Tracer autoconfiguration](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk-extensions/autoconfigure/src/main/java/io/opentelemetry/sdk/autoconfigure/TracerProviderConfiguration.java) and [logger autoconfiguration](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk-extensions/autoconfigure/src/main/java/io/opentelemetry/sdk/autoconfigure/LoggerProviderConfiguration.java).
- [Span queue counters](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/LegacySpanProcessorInstrumentation.java), [log queue counters](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/sdk/logs/src/main/java/io/opentelemetry/sdk/logs/export/LegacyLogRecordProcessorInstrumentation.java) and [export counters](https://github.com/open-telemetry/opentelemetry-java/blob/v1.66.0/exporters/common/src/main/java/io/opentelemetry/exporter/internal/metrics/LegacyExporterMetrics.java).

## JMH benchmark gate

Add benchmark fixtures to the existing `jmh-ldbc` benchmark module in later implementation tracks.
The module is named for the Linked Data Benchmark Council (LDBC). A fixture is the dataset,
operation, setup and configuration used by a benchmark. JMH means Java Microbenchmark Harness.
An arm is one workload/configuration side of a comparison. Measure full database calls with
result consumption and close inside the measured operation. Use separate fixtures for direct
SQL, translated Gremlin Path A and fallback Path B. Verify the selected path outside the timed
region. Keep each fixture's data, transaction boundaries, query-result cache setting and result
count identical between off and enabled arms. Consume results through the JMH black hole to
prevent dead-code elimination. Reset the process-global telemetry installation between forks,
never concurrently.

Land backward-compatible off fixtures before comparing instrumentation changes, or use one
trusted compatible harness against both revisions. If the base lacks a matching fixture,
report the off comparison as inconclusive. Never substitute another workload or report a pass.
Version exact query strings, the dataset generator, expected result counts, cache setting,
tag lengths, ordered rule expressions and match distribution with each fixture. Publish them
as fixture metadata.

Use seven scenario families:
1. Off: timing mode not applicable. No telemetry or local-capture listener and no log hook. Run tiny queries
   and scans in candidate and base revisions. A no-op SDK with registered listeners is not off.
2. Mostly rejected: threshold `100` ms, heartbeat `0`, short successful queries. Run LIGHTWEIGHT
   and EXACT for direct SQL, Path A and Path B. Check that at least 99% of completions fail their
   own duration gate in each arm. Record histograms anyway.
3. Emit-all: threshold `0`, short successful queries. Run LIGHTWEIGHT and EXACT for direct SQL,
   Path A and Path B. Each mode has one accepted SQL child per Path B traversal and no child on
   Path A. Keep the buffer at its default 128 entries.
4. Scans: 10,000 fully consumed rows in both LIGHTWEIGHT and EXACT. Run direct SQL and Path A
   with one timing layer, and Path B with both Gremlin and SQL timing. Threshold `0`, no consumer
   sleeps or `.profile()`.
5. Concurrent tags: mostly rejected Gremlin at 1 and 8 threads. Configure 16 ordered rules
   containing exact, prefix and simple regex cases. Exercise early, late and no match. Use both
   32 repeated tags and a stream of distinct short tags. Set the global threshold fallback and
   every threshold rule to `100` ms. Set the transaction mode fallback and every mode rule to
   LIGHTWEIGHT. Keep heartbeat zero and transaction threshold overrides absent. Verify at least
   99% rejection separately for early-match, late-match and no-match inputs. Report repeated and
   distinct tags separately. The 2–12% estimate covers repeated tags with reusable histogram
   dimensions only. Treat the distinct-tag variant as unestimated until measured. Its accepted
   15% budget applies independently.
6. Logs: invoke `LogManager` once per operation with a fixed INFO template and arguments, no
   exception and message-body opt-in off. Use an admitting, non-noop SLF4J provider with a local
   discard sink. Run 1 and 8 threads. Off performs the same local log call without the OTel hook.

7. Local capture only: OTel off, no SDK, no OTel listener and no histograms. Enable local capture
   with a positive threshold and LIGHTWEIGHT timing for the same tiny SQL, Path A and Path B
   fixtures. Verify at least 99% rejected completions. Fast rejection precedes text rendering,
   record construction and correlation allocation. Use the matched off denominator.
   The accepted throughput-loss budget is 10%, copied from the LIGHTWEIGHT OTel arm with
   slow-query filtering. Numerical capture defaults remain open.

Measure local capture alone, OTel alone and both together against matched baselines.
Existing OTel fixtures keep local capture off. The combined arm has no new numerical budget.
Local-only and combined slowdown estimates remain unestimated until measured.
Report throughput, tail latency, allocation and retained-memory behavior separately.
Validate concurrent publication, eviction, cursor reads and slow-consumer pressure.
Health evaluation is bounded outside queries and independent of reader count.
No query completion performs network requests, backend queries or event delivery.

Only timing mode differs between each OTel workload's LIGHTWEIGHT and EXACT arms. Keep thresholds,
rejection checks, SQL children, buffer size and histograms identical. EXACT uses paired
`System.nanoTime()` reads per delegated `hasNext()` and `next()` call. Short queries with few
rows add few pairs. SQL and Path A pay one timing layer. Path B with SQL children pays two.
Each EXACT arm shares its LIGHTWEIGHT arm's candidate-off denominator. Reports show both modes
side by side for each path and workload. Report their throughput-loss gap without a gap budget.
Accepted risk: the gate grows by nine arms, which adds benchmark time per run.

Each OTel-enabled query fixture enables metrics and the queries group. Keep the histogram dimension
schema unchanged and report cache hit or collision pressure as fixture metadata. Warm the cache
for the repeated-dimension fixture. Include a separate collision-heavy diagnostic variant.
The logs fixture enables SDK metrics to observe queue losses. Other signal switches match each
fixture's declared purpose. The owned SDK uses its normal default sampler with root contexts,
so every accepted span is recording and sampled. Do not use a ratio sampler to reduce cost.

Use actual batch processors with their default queue and batch sizes. In the primary run,
replace every enabled signal's exporter with an immediate-success counting/discard exporter.
Keep the normal metric reader at a fixed reported interval. No signal performs network export.
SDK workers still execute in the benchmark JVM. Use SDK processor self-metrics for queue drops
and counting-exporter results for export reconciliation. These custom exporters do not supply
OTLP-exporter self-metrics. Normal counter updates remain in the processing paths being measured.
Read counters, flush and reconcile outside measurement at iteration boundaries. A primary run
with unexpected child loss, queue drops or export failure is invalid.
Add an OTLP-to-healthy-collector diagnostic run with the same workload. Keep that collector
outside the benchmark CPU allocation and report its transport and location. Network variance
must not decide the primary budget result. Stalled-export tests validate bounded loss separately.

Use the comparison workflow's requested 8-vCPU Linux x86-64 profile and verify actual CPU
availability. A vCPU is a virtual CPU allocated to the benchmark job. Add a matching telemetry
job to the nightly workflow or document its independently verified equivalent allocation.
Existing bare-metal nightly results are not automatically comparable with this profile.
Use JDK 21. Pin heap size and garbage collector across each comparison. Fit the warmed dataset
in memory. Record CPU model, actual available CPUs, memory, JDK build, JVM options, SDK version,
revisions and all scenario parameters.
Run arms sequentially on the same runner with alternating order. Use at least 3 independent JVM
forks per arm, 5 one-second warmup iterations and 10 one-second measurement iterations per fork.
If warmup has not stabilized, lengthen it and rerun both arms. Never compare different runner
profiles or merge data from different configurations into one score.

Throughput loss is `100 * (1 - Qscenario / Qoff)`, where Q is operations per second. Candidate-off
uses base-off as its denominator. All enabled scenarios use their own candidate-off fixture.
The accepted alert budgets are 1% for off, 10% for LIGHTWEIGHT mostly rejected,
10% for local-capture-only mostly rejected with OTel off, 35% for
LIGHTWEIGHT emit-all SQL and Path A, 40% for LIGHTWEIGHT emit-all Path B, 30% for single-layer
EXACT scans, 45% for layered EXACT scans, 15% for LIGHTWEIGHT concurrent tags and 50% for logs.
New arm estimates and budgets below are proposals pending user confirmation. Estimates describe
throughput loss, not measurements. Each budget allows modest headroom above the estimated upper
bound. Short-query EXACT arms allow a small clock premium. LIGHTWEIGHT scans avoid per-call
clock reads but retain spans and histograms.

| New arm | Proposed throughput-loss estimate | Proposed alert budget relative to off |
|---|---|---|
| Mostly rejected, EXACT, SQL | 0.5–6% | 12% |
| Mostly rejected, EXACT, Path A | 0.5–9% | 12% |
| Mostly rejected, EXACT, Path B | 0.5–10% | 15% |
| Emit-all, EXACT, SQL | 2–27% | 38% |
| Emit-all, EXACT, Path A | 3–32% | 38% |
| Emit-all, EXACT, Path B | 5–38% | 45% |
| Scans, LIGHTWEIGHT, SQL | 1–15% | 20% |
| Scans, LIGHTWEIGHT, Path A | 1–15% | 20% |
| Scans, LIGHTWEIGHT, Path B | 2–25% | 30% |

Treat every thread count and query path independently. Each arm is compared only with its own
off arm. The EXACT-minus-LIGHTWEIGHT gap is reported, not budgeted.
Do not average a regression away across scenarios. The collision-heavy and OTLP diagnostic
variants report results without these budgets because they change the main assumptions.

Extend `jmh-ldbc/jmh-compare.py` for paired comparisons. Key results by full benchmark identity,
all parameters, thread count, mode and score unit. Explicitly pair scenario and off arms while
retaining every other key. Add telemetry-suite report rendering. Reuse the non-overlap and
relative-error filters only after replacing error coercion with explicit validation and
inconclusive results. A confidence interval describes uncertainty in the benchmark score.
Relative score error is `100 * scoreError / score`, using the JMH-reported confidence-interval
half-width. Flag a budget breach only when the point-estimate loss exceeds the budget, the
score intervals do not overlap and each relative score error is at most 10%. This reports a
point-estimate breach with evidence of a difference from off. It does not establish that loss
exceeds the budget at the reported confidence level. Overlap, missing, malformed or non-finite
errors, non-positive scores, missing pairs and excessive noise make the result inconclusive.
They must not become zero error or pass. The 10% noise ceiling may leave a 1% off comparison
inconclusive. Three forks are a floor, not a precision guarantee. Publish raw JMH JSON and reports.
Report bytes per operation with the JMH garbage-collection profiler, collection time and process
CPU per operation. Measure p99 in a separate sample-time pass with the same fixture. Keep p99
and allocation diagnostic until measurements justify separate budgets.

Wire reports into `.github/workflows/ldbc-jmh-compare.yml` and the nightly workflow
`ldbc-jmh-nightly.yml` in later benchmark code work. Extend parsing, rendering, nightly suite
discovery and `jmh-to-influxdb.sh` persistence together with `jmh-regression-alert.py`.
Preserve full benchmark identity, all scenario parameters, thread count, mode and score unit
throughout stored history. Prevent different arms from sharing an InfluxDB series key.

Preserve the nominal 10% run-over-run and 5% rolling-average alert thresholds. Explicitly
exclude the current run from history. Compare only with the previous completed run and an
average of preceding completed runs. The current nightly workflow pushes results before the
alerter runs. Its script does not exclude that run, so the run-over-run check compares the run
with itself and the rolling average includes it. The script correction belongs to later
benchmark code work. The PR comparison keeps its 5% base-versus-candidate threshold for
identical arms.

Performance breaches are reporting-only in continuous integration (CI). They do not block
merge. Existing budgets, fixtures, runner and confidence rules remain user-accepted.
New arm estimates and budgets remain pending user confirmation. Inconclusive
evidence gets an explicit warning and a rerun recommendation. Execution and artifact-integrity
failures keep the workflows' existing failure behavior. No benchmark code or workflow is added
by this design edit. Measured reports must distinguish this policy from correctness tests.

## Quick-start observability stack

The local Docker Compose setup runs a pinned, tested grafana/otel-lgtm release beside
YouTrackDB and the later web application. LGTM supplies Loki, Grafana, Tempo and Prometheus.
Choose the exact release during packaging and verify its collection and provisioning behavior.
The complete delivery needs database monitoring and later application capabilities.
Example files alone are not the delivery boundary. Keep setup files outside module JAR resources.

The later web application embeds Grafana panels. Core stays independent of Grafana.
Track 104 defines application access, login and embedding. Track 105 defines optional MCP access.
This section defines neither interface. Production may run telemetry stores separately.
Exporter selection, host-owned SDK support and unset-endpoint behavior remain unchanged.
Publish only OTLP receivers and the later application entry port, with loopback-only host mappings.
Keep Grafana, Loki, Prometheus and Tempo ports internal. Do not add diagnostic host mappings.
Smoke checks run against internal container-network endpoints.
Set `GF_AUTH_ANONYMOUS_ENABLED=false` explicitly to override the LGTM image default.
Reach Grafana only through the later gateway. Track 104 defines access and login details.
Generate credentials at first start into untracked `secrets/` beside the Compose file.
Use owner-only permissions on that directory and its credential files.
Mount each file only into services that need it. Keep fixed credentials out of example files.
Never print credentials in container output, `scripts/logs.sh` output or exported telemetry.
Document file locations, permissions and non-disclosure in the example README.
Do not use shared administrator credentials as the application login.

### Collector pipeline shape

LGTM's collection layer routes OTLP traces to Tempo, logs to Loki and metrics to Prometheus.
The selected release determines concrete internal endpoints and collection configuration.
The following local example shows logical routing with separate container services.
It is not an extra quick-start service or a claim about LGTM's internal configuration.
It uses plaintext and unauthenticated container-network traffic and is not a production configuration.
Production requires Transport Layer Security (TLS) and client authentication at receivers.
Production also requires authenticated TLS connections to telemetry backends.
All receiver addresses below are container-internal. Publish OTLP receivers on loopback only.
Keep backend and scrape endpoints internal without host mappings.

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 512
    spike_limit_mib: 128
  batch:
    timeout: 1s
    send_batch_size: 1024
  resource:
    attributes:
      - key: deployment.environment.name
        value: local
        action: upsert

exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true
  otlphttp/loki:
    endpoint: http://loki:3100/otlp
  prometheus:
    endpoint: 0.0.0.0:8889
    namespace: youtrackdb
    const_labels:
      deployment_environment_name: local

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [otlp/tempo]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [otlphttp/loki]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [prometheus]
```

Three details matter:

1. **`memory_limiter` runs first** in every pipeline. Order matters — the limiter must see batches before the `batch` processor accumulates them, otherwise a runaway emit overruns the limit before the limiter rejects it.
2. **`resource` modifies each resource group.** Batching does not change resource-attribute scope or merge distinct resources into one resource. Running `batch` before `resource` is an example pipeline choice. It is not a requirement for resource-level attribute placement.
3. **Prometheus collection.** The separate Collector example exposes metrics on `:8889` for scraping. Traces and logs use push export. Validate the selected LGTM release's actual metrics path and scrape cadence before fixing smoke timing.

### Grafana datasource provisioning and correlator wiring

Provision Tempo, Loki and Prometheus data sources using the selected LGTM release's endpoints.
Link Tempo traces to Loki and Prometheus with supported correlation settings.
The following shape shows a separate-service deployment. Resolve internal URLs for LGTM.

```yaml
apiVersion: 1
datasources:
  - name: Tempo
    type: tempo
    uid: tempo
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        spanStartTimeShift: -2m
        spanEndTimeShift: 2m
        tags: [{ key: service.name, value: service_name }]
        filterByTraceID: true
        filterBySpanID: false
      tracesToMetrics:
        datasourceUid: prometheus
        spanStartTimeShift: -2m
        spanEndTimeShift: 2m
        tags: [{ key: service.name, value: service_name }]
  - name: Loki
    type: loki
    uid: loki
    url: http://loki:3100
  - name: Prometheus
    type: prometheus
    uid: prometheus
    url: http://prometheus:9090
```

The `tracesToLogsV2` settings link a Tempo span to Loki logs by trace identifier.
The example's two-minute shifts accommodate clock skew. Verify correlation with the pinned release.
Logs carry the host span current at emission. Completion-built query and commit spans never
become current and never rewrite earlier log correlation.

The `tracesToMetrics` settings join Prometheus measurements by `service.name`.
The link does not imply that a local slow-query record has a retained backend trace.

### Dashboard placeholder strategy

Provision five JSON dashboards: overview, queries, storage, logs and health.
JSON means JavaScript Object Notation. Use Tempo, Loki and Prometheus data source inputs.
Import maps those inputs to local unique identifiers (UIDs).
Do not commit fixed instance UIDs.
The logs dashboard searches exported Loki content within bounded time ranges.
The health dashboard shows exported state, reasons, freshness and structured transitions.
Missing or stale authority shows unknown. Local current health and history do not depend on export.
The later application presents local health and slow queries. Track 104 defines that presentation.

Dashboard authoring uses the authenticated gateway defined by the later Track 104 design.
Bring the stack up and open Grafana through that gateway, not through a host Grafana port.
Edit the dashboard in the user interface and export through "Share → Export → Save to file".
Run `scripts/normalize-dashboard.sh <file>` to replace captured UIDs with placeholder strings.
Commit the normalized file. The normalize script is a thin `jq` wrapper with deterministic output.

### Smoke-script verification semantics

`scripts/smoke.sh` exits non-zero when any pillar fails to land within 30 seconds. The sequence:

1. **Bring up the stack** if not already healthy (`docker compose up -d --wait`).
2. **Run a minimal embedded YTDB query** via a one-shot `java -jar` invocation against the Maven-built artifact. The sample settings and smoke invocation set `OPENTELEMETRY_ENABLED=true`, `OPENTELEMETRY_LOGS_ENABLED=true` and `OPENTELEMETRY_METRICS_ENABLED=true`, with the local Collector endpoint. Other settings keep their shipped defaults. These example overrides do not change product defaults. The query is a fixed `SELECT FROM OUser LIMIT 1` against an in-memory database — small, deterministic, exercises the SQL pillar through `db.query(...)`.
3. **Check Tempo** through the pinned release's supported trace search and lookup paths. Confirm the known query trace arrives within the existing 30-second per-signal timeout. Use internal container-network endpoints without host mappings.
4. **Poll Loki** over the container network at the selected release's internal `/loki/api/v1/labels` endpoint until `service_name` appears, then `/loki/api/v1/query?query={service_name="youtrackdb"}` until at least one log record returns.
5. **Poll Prometheus** over the container network at the selected release's internal `/api/v1/label/service_name/values` endpoint until `youtrackdb` appears, then `/api/v1/query?query=db_client_operation_duration_seconds_count{service_name="youtrackdb"}` until at least one sample returns.
6. **Check monitoring capabilities**: bounded log search, metric graphs, trace lookup,
   current health, health transitions and retained slow queries.
   Explicitly enable local capture and execute a query above its configured positive threshold.
   This test override keeps the product capture default off.
   Check authenticated panels once Track 104 defines access.
   Stop collection or disable export and confirm local monitoring still works.
7. **Exit 0** only when all checks pass. Exit non-zero with a diagnostic naming each missing
   signal or capability. Preserve the existing 30-second per-signal timeout.

Validate collection, export and scrape intervals against that timeout for the pinned release.
Do not assume that startup readiness means telemetry has arrived.

### Edge cases / Gotchas

- **Loki OTLP HTTP path mismatch**. Loki 3.x accepts OTLP on `/otlp/v1/logs`, not the bare `/otlp` path Loki 2.x advertised. The Collector config writes the full path explicitly (`http://loki:3100/otlp`) and the Collector appends `/v1/logs` per OTLP spec. A common operator surprise on a stale Loki version: logs land in Loki but the OTLP receiver returns 404 because the path resolver fell through. The README troubleshooting section names this case and points at the matching image tag.
- **Fresh-stack lag**. Use the selected release's actual scrape cadence when diagnosing missing measurements. Startup and export readiness are separate.
- **Collector resource attribute vs span attribute precedence**. The `resource` processor sets `deployment.environment.name=local` as a resource attribute, which Grafana surfaces alongside span attributes in the trace-detail panel. A host-side `OPENTELEMETRY_SERVICE_NAME` override propagates into the same resource attribute set via the autoconfigure builder; the Collector's `resource` processor upserts `deployment.environment.name=local`, replacing an existing environment value. It does not modify `service.name` because no action targets that key. Use `insert` instead to preserve a supplied environment value.
- **Development storage**. Document LGTM retention and restart loss for the selected release. Local packaging provides no production storage or security guarantees.
- **Span-name cardinality**. Keep the existing `OPENTELEMETRY_COMMIT_SPAN_NAME_INCLUDES_DBNAME` contract. Backend choice does not remove cardinality costs.

### References
- D-records: D41 (LGTM, five dashboards and later unified application capabilities define delivery).
- Invariants: pinned tested releases, loopback-only entry ports and internal telemetry stores.
- Credentials: generated at first start in owner-only files and excluded from logs and telemetry.
- Packaging: example files stay outside Maven resources and the module JAR.
- No alerting rules, remediation or tuning recommendations are added.
