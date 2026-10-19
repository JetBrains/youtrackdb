<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# YTDB-496 OpenTelemetry support — Mechanics

Implementation detail for the six sections listed below. Each section gathers the full pseudo-implementation, mapping tables, and edge cases an implementer needs. The text stands on its own; concepts named here either define themselves locally or appear under the same name in the companion polished view.

TX means transaction. Sem-conv means OpenTelemetry semantic conventions. D-numbers such as
D34 identify design decisions. Section References footers summarize them.

A completion is accepted when it passes its own query gates. It then becomes a candidate for
retention. Admission means storing that candidate in the traversal buffer. Neither acceptance
nor admission guarantees span construction or export.

## Slow-query threshold gating

The resolver and its rule type reuse the same machinery as `QueryMonitoringModeResolver` — only the `T` parameter on `TagRule<T>` differs (`Long` instead of `QueryMonitoringMode`):

```java
public final class SlowQueryThresholdResolver {
    private final List<TagRule<Long>> rules;           // immutable, compiled once at startup
    private final Map<String, OptionalLong> cache;     // caches the rule-walk outcome (empty = no rule
                                                       // matched), never the caller's fallback, so a per-TX
                                                       // override cannot leak across transactions.
                                                       // bounded LRU: Collections.synchronizedMap over
                                                       // LinkedHashMap(1024, 0.75f, true) with access-order
                                                       // eviction; INFO-rate-limited (once per 60 s window)
                                                       // on eviction so unique-tag-per-request misuse cannot
                                                       // exhaust the heap

    public long resolve(Optional<String> tag, long defaultNanos) {
        if (tag.isEmpty()) return defaultNanos;
        // Cache only the rule-walk outcome; apply the (per-call, possibly per-TX) fallback outside it.
        return cache.computeIfAbsent(tag.get(), this::walkRules).orElse(defaultNanos);
    }
    // walkRules returns OptionalLong.of(value) on first-wins match, OptionalLong.empty() when no rule
    // matches; values stored as nanoseconds. The fallback (per-TX override, else global default) is
    // applied by resolve() AFTER the cache lookup, so a fallback that varies by transaction is never
    // baked into the shared cache.

    public static SlowQueryThresholdResolver global() { ... }
}
```

The gate sits inside `OTelQueryMetricsListener.queryFinished`, not in the YTDB fire site. Three reasons: (1) other listeners registered alongside the OTel one (custom host implementations) may have their own gating policy and must continue to see every event; (2) the gate is OTel-specific configuration and lives in the OTel module rather than `core`; (3) `QueryDetails` already carries the inputs the gate needs — `executionTimeNanos` arrives as a method parameter, error state arrives through `QueryDetails.getErrorType()`, and the query tag arrives through `QueryDetails.getQuerySummary()` (same source the OTel sem-conv mapping already reads).

Duration-check fragment only. In the complete callback, errors bypass both checks and a
successful heartbeat claim bypasses this fragment. [Time-based sampling](#time-based-sampling)
defines the combined gate order. [Buffered SQL children](#buffered-sql-children) defines
completion routing and cleanup.

```java
@Override
public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos) {
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
  // Use the recorded host Context and explicit start timestamp.
  // Drain means emit buffered SQL snapshots as child spans.
  // Drain only when that parent is recording and sampled.
  // Direct SQL: build a span with the captured host parent and explicit recorded timestamps.
  // See Buffered SQL children for cleanup and Time-based sampling for the combined gates.
}
```

`QueryDetails.getErrorType(): Optional<String>` is a new default-empty accessor added to the listener contract. Both query paths populate it from the caught failure. SQL entry-point catches emit one error completion when parse, setup or cache work fails before a result set exists. They capture context, tag, Gremlin-origin marker, mode and start time before the failing work. They preserve rollback and rethrow the original failure. Once a wrapper exists, it owns completion and closes in a `finally` path after a drain failure. An abandoned result set without `close()` still emits nothing. The accessor exists on the listener contract so any consumer (OTel or custom) can read error state without an additional callback signature.

Direct SQL has no query tag and uses the per-TX fallback. SQL entered inside tagged Gremlin inherits `YTDBContextKeys.QUERY_TAG` captured before execution. `GREMLIN_ORIGIN` is a boolean provenance marker set for tagged and untagged Gremlin execution. An untagged nested Gremlin traversal clears an inherited tag and still stamps `GREMLIN_ORIGIN=true`. Gremlin pins its span name at first `hasNext()` to the tag or `youtrackdb`. SQL uses the sem-conv fallback chain. The resolver reads the inherited tag, not a classifier-derived display summary.

Successful commit spans use the separate `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` gate. Its default `0` emits every successful commit. Failed commits bypass it. Query thresholds, query tags and heartbeat sampling do not govern commit emission.

### Bundled skinny-span filter

Late Gremlin creation applies the query gates before `spanBuilder()`. A rejected traversal has
no span to export. The configured `SkinnySpanFilterProcessor` remains in the SDK pipeline.
It inspects spans at `onEnd` and forwards those with `db.system.name` or a non-YTDB scope.
Its predicate does not select buffered SQL candidates or retain their parent. Implementation shape:

```java
public final class SkinnySpanFilterProcessor implements SpanProcessor {
    private static final String YTDB_SCOPE = "io.youtrackdb";
    private final SpanProcessor delegate;

    public static SpanProcessor wrap(SpanProcessor delegate) {
        return new SkinnySpanFilterProcessor(delegate);
    }

    @Override public void onStart(Context parentContext, ReadWriteSpan span) {
        delegate.onStart(parentContext, span);
    }

    @Override public boolean isStartRequired() { return delegate.isStartRequired(); }
    @Override public boolean isEndRequired()   { return true; }

    @Override public void onEnd(ReadableSpan span) {
        if (isSkinnyYtdbSpan(span)) return;       // drop: do not forward to delegate
        delegate.onEnd(span);
    }

    private boolean isSkinnyYtdbSpan(ReadableSpan span) {
        if (!YTDB_SCOPE.equals(span.getInstrumentationScopeInfo().getName())) return false;
        return span.getAttribute(SemConvAttributes.DB_SYSTEM_NAME) == null;
    }

    @Override public CompletableResultCode forceFlush() { return delegate.forceFlush(); }
    @Override public CompletableResultCode shutdown()   { return delegate.shutdown(); }
}
```

SDK builder wiring uses OpenTelemetry Java SDK 1.66.0 as the reference. This is the chosen latest stable release. It is not an existing repository pin. The implementation plan pins `opentelemetry-bom` 1.66.0 in the POM. Both YTDB-owned paths install the filter through `AutoConfiguredOpenTelemetrySdkBuilder.addSpanProcessorCustomizer(...)`. The resolved YTDB config supplies `dropSkinny` before this hook is registered:

```java
sdkBuilder.addSpanProcessorCustomizer((processor, cfg) ->
    dropSkinny && processor instanceof BatchSpanProcessor
        ? SkinnySpanFilterProcessor.wrap(processor)
        : processor);
```

The hook receives each configured processor and returns its replacement. It wraps the batch processor without changing sibling processors. It runs only when YTDB builds the SDK. Embedded discovery checks `GlobalOpenTelemetry.isSet()` and reads `getOrNoop()` when a global is registered. These methods require SDK 1.57 or later. It does not call `get()` to probe, compare no-op identity or cast the returned global wrapper to `OpenTelemetrySdk`. The explicit setter takes priority. A registered global remains host-owned even when it is no-op.

Host-owned SDKs. When the host has wired its own `OpenTelemetry` via `setOpenTelemetry(externalSdk, ownedByYtdb=false, ...)` (or via `GlobalOpenTelemetry.set(...)` before YTDB resolves), YTDB does not modify the SDK and the bundled filter is therefore not installed. Hosts that want the same default skinny-span suppression call the public factory:

```java
SpanProcessor batch = BatchSpanProcessor.builder(otlpExporter).build();
SpanProcessor filtered = YouTrackDBOpenTelemetry.skinnySpanFilter(batch);
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(filtered)
    .build();
```

`YouTrackDBOpenTelemetry.skinnySpanFilter(SpanProcessor delegate): SpanProcessor` is the only new public-API method this feature adds.

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
`GremlinSpanLifecycleHook.onFirstHasNext` returns a Context derived from the host context with
this key, `QUERY_TAG` and `GREMLIN_ORIGIN`. It adds no span. The step installs that Context only
around delegated database calls and closes each scope in `finally` on the thread that opened it.
Server execution installs it on the Gremlin executor worker, including the per-session executor
path, rather than the network dispatch thread.

Each SQL fire site captures that Context before execution or at wrapper construction. At SQL
completion the OTel listener records histograms and applies the existing SQL gates, including
heartbeat claiming at that moment. A rejected completion creates no buffer entry. An accepted
completion has passed its own query gates and becomes a candidate for retention. It snapshots
the values needed to build its span, then offers the snapshot to the buffer. Admission means
storing the candidate in the traversal buffer. Neither acceptance nor admission guarantees span
construction or export. The snapshot holds recorded start and duration, sanitized query text,
name, error type, row count, classification and other configured attribute values. It copies
parameter values only when the existing parameter opt-in is enabled. It retains no result set, execution plan, transaction or
lazy `QueryDetails` accessor. Custom listeners still receive their SQL completion immediately.

`OPENTELEMETRY_GREMLIN_MAX_BUFFERED_SQL_SPANS` is a non-negative integer with default `128`.
The facade reads it at SDK initialization. Each traversal takes that limit when its state is
created. A full buffer drops the newest candidate. It never blocks for space, spills to disk or
evicts an earlier candidate. `0` disables child retention without disabling the Gremlin span or
SQL histograms. Invalid values fail SDK configuration. This limit bounds entry count. Payload
byte limits belong to the query-text and queue limits, which are separate settings.

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
sampled, discard the candidates. This includes an SDK `DROP` result and `RECORD_ONLY` output
that the normal export pipeline would not export. An accepted SQL child never forces its parent
through a gate. It is never promoted to a root or reparented to the host on rejection.

For a recording, sampled Gremlin parent, build each buffered child with
`recordedHostContext.with(gremlinSpan)` as its explicit parent. Preserve the child's recorded
start and timing-mode endpoint. Do not call `queryFinished` again, rerun its gates, claim another
heartbeat slot or record its histograms twice. Each child builder still uses the configured SDK
sampler. Emit children in buffer admission order, then end the Gremlin span in `finally` using
its recorded timing-mode endpoint. EXACT uses start plus measured active duration.

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

Regression tests cover zero, one, full and overflowing buffers, explicit parent IDs after late
creation, SQL gate rejection, parent gate rejection, parent SDK drop and record-only decisions,
error candidates, close idempotence, cleanup after listener failure, and offer-versus-seal races.
They also verify one histogram sample per completion and no second heartbeat evaluation on drain.

### Edge cases / Gotchas

- Default `0` disables the duration gate and gives operators first-run visibility after `OPENTELEMETRY_ENABLED=true`. Nested SQL still requires buffer space and a retained parent. SDK sampling and export still apply. Operators on read-heavy workloads who want to drop fast successful queries set a positive value (e.g., `100`) and document the choice in their observability runbook. Errors bypass both query gates.
- A query whose duration equals the threshold passes the duration gate, because the comparison is strictly `<` (less-than). A 100 ms query against a 100 ms threshold passes the gate.
- The trace gate runs before span allocation or SQL candidate buffering. It reads the error, original tag and per-TX threshold override. Metrics-enabled listeners record histogram samples before this gate. Parent rejection drops buffered children without changing their already-recorded metrics.
- Tag-rule cache cardinality blow-up: a misuse-pattern host that emits unique tags per request (e.g., a UUID) would otherwise grow each resolver's cache without bound. Both resolvers use a bounded LRU (`Collections.synchronizedMap` over `LinkedHashMap(1024, 0.75f, true)` with access-order eviction, capacity 1024), so the heap footprint is capped at roughly 2 × 200 KB even under sustained unique-tag-per-request abuse. Eviction logs INFO once per 60 s window with the count of evictions in that window; an evicted tag pays the rule walk again on its next access, and rule walks are deterministic so the resolved value stays stable across miss / re-resolve / re-cache. The heartbeat gate has no resolver and contributes no cache.
- A tag that matches no rule resolves to the per-TX override (`withSlowQueryThresholdMillis`) when the transaction set one, else the global default. The cache records the rule-walk outcome — the matched value, or an empty marker when no rule matched — so future identical tags skip the walk; the fallback (per-TX override, else global default) is applied after the cache lookup, at the resolver's fallback slot, so a fallback that varies by transaction is never baked into the shared cache.
- Conflicting rules (two rules match the same tag): first-wins by insertion order. Operators order rules from most-specific to most-general. Duplicate exact rules: the first one wins; subsequent rules log WARN at startup and are dropped.
- The global default is captured at OTel listener construction time and stored in the `defaultThresholdNanos` final field. Mid-process changes to the global default require an SDK rebuild (host calls `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` again, or the server restarts). The tag-rule list is also compiled once at startup; mid-process rule changes are not supported in YTDB-496 (same constraint as for mode rules). The per-TX override is not subject to either constraint: the fire site reads `FrontendTransaction.getSlowQueryThresholdOverrideNanos()` per query and carries it on `QueryDetails`, so a transaction that calls `withSlowQueryThresholdMillis(...)` takes effect immediately without an SDK rebuild.
- Custom (non-OTel) `QueryMetricsListener` implementations are unaffected. The gate lives only in `OTelQueryMetricsListener`; the listener iteration in `iterateAllQueryListeners()` still fires every registered listener for every query. Custom listeners that want their own threshold semantics can call `SlowQueryThresholdResolver.global()` themselves, or implement their own gating logic entirely.
- Threshold-vs-mode coupling. The gate compares against `executionTimeNanos` whose semantic depends on the resolved `QueryMonitoringMode` for the same tag: active-time under `EXACT` (consumer idle excluded), wall-clock under `LIGHTWEIGHT` (consumer idle included). Operators combining a mode-rule and a threshold-rule for the same tag declare a combined intent. For example, `OPENTELEMETRY_QUERY_MODE_TAG_RULES=findHotpath=EXACT` paired with `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES=findHotpath=100` means "emit a hot-path span when DB-side active work exceeded 100 ms" — not "real-time exceeded 100 ms". The same threshold value `100` paired with `LIGHTWEIGHT` for a different tag means "real-time exceeded 100 ms". A single numeric threshold therefore has different operator-facing meaning depending on its companion mode rule; operators tuning thresholds across tags with mixed modes account for this by reading the mode×threshold table in §"Query tagging and per-tag rule resolution". Cross-tag dashboard aggregation slices spans on `db.youtrackdb.duration_semantics` to keep histograms semantically homogeneous. Inside a single Path B traversal both the outer Gremlin span and the inner SQL spans gate on the same resolved threshold because inner SQL inherits the outer's tag via `YTDBContextKeys.QUERY_TAG` on the OTel `Context` (see §"Query tagging and per-tag rule resolution" → tag inheritance); no parent-child threshold drift within a traversal. The resolved threshold may come from the query-tag rule, the per-TX override, or the global default (Table B in §"Query tagging and per-tag rule resolution"); the mode coupling applies identically whichever threshold tier won.
- The filter drops end events only for its delegate. Sibling processors receive skinny spans regardless of registration order. Automatic installation applies only to YTDB-owned SDKs. Hosts opt in through `YouTrackDBOpenTelemetry.skinnySpanFilter(...)` and wrap every processor that should drop skinny spans.
- A child whose own gate accepts it can still be lost when its parent is rejected, the buffer is full or completion is late. The drop counter reports these losses. Parent retention is never forced by a child. Exporter or processor losses can still produce an incomplete backend trace.
- Cross-instrumentation scope safety. The filter's `InstrumentationScopeInfo.name == "io.youtrackdb"` check is the load-bearing safety against collateral damage. A host that wires the YTDB SDK alongside auto-instrumentation for HTTP, gRPC, or its own application code sees those spans flow through `onEnd` unchanged because their scope name differs. Custom YTDB-adjacent code that creates spans via `GlobalOpenTelemetry.getTracer("io.youtrackdb")` (sharing the name) WOULD be subject to the filter; the workaround is to use a distinct scope name (`io.youtrackdb.host`, the host's own package) when authoring custom YTDB-adjacent instrumentation.
- Filter and `forceFlush` / `shutdown`. The chain wrapper delegates both lifecycle calls unconditionally, so flush semantics during graceful shutdown are identical to the delegate's. Spans dropped by the filter were never added to the batch buffer in the first place, so a flush sees nothing for them.

### References
- D-records: D16 (slow-query threshold inside OTel listener, error bypass, per-tag rules via `TagRule<Long>`), D46 (bundled `SkinnySpanFilterProcessor` wrapping `BatchSpanProcessor` in YTDB-owned SDKs; `InstrumentationScopeInfo.name`-scoped to `"io.youtrackdb"`; public `skinnySpanFilter(SpanProcessor)` factory for host-owned SDKs; gated by `OPENTELEMETRY_GREMLIN_DROP_SKINNY_SPANS=true`)

## Time-based sampling

The heartbeat gate uses one `AtomicLong` and one final-field interval. It reads no resolver, tag or cache. The heartbeat runs first at the top of `OTelQueryMetricsListener.queryFinished`. A winning claim makes the completion pass the query trace gate and bypass the slow-query check. If it does not claim, the slow-query gate evaluates as before. Error completions bypass both query gates without advancing the heartbeat clock. A stream of errors does not suppress heartbeat selection of successful queries.

Heartbeat claiming occurs at query completion, before buffer admission or span construction.
A successful claim advances the heartbeat clock even if the selected nested SQL candidate is
later discarded because the buffer is full, the traversal is sealed, or the Gremlin parent is
not retained. Discarding the candidate does not refund the slot or select a replacement. Drain
does not evaluate heartbeat claiming again. SDK sampling and export can also prevent a selected
span from appearing.

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

The gate sits inside `OTelTransactionMetricsListener.writeTransactionCommitted`, not in the YTDB fire site. Same reasoning as the query gate (D16): other transaction listeners registered alongside the OTel one may have their own policy and must continue to see every event; the gate is OTel-specific configuration and lives in the OTel module; `TransactionDetails` already carries the input the gate needs (`executionTimeNanos` arrives as a method parameter; no error state to read because successful commits route through `writeTransactionCommitted` while failed commits route through `writeTransactionFailed` and bypass the gate by construction).

Pseudo-implementation at the top of the callback:

```java
private final long defaultCommitThresholdNanos;  // populated at SDK init from config

@Override
public void writeTransactionCommitted(TransactionDetails details,
                                      long committedAtMillis,
                                      long executionTimeNanos) {
  // Record the enabled commit histogram before the trace gate.
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
  // unchanged — failed commits always emit, carrying error.type
}
```

The `defaultCommitThresholdNanos` final field is initialized at OTel listener construction from `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` multiplied to nanoseconds. Mid-process changes to the global default require an SDK rebuild — same constraint as the query-side threshold default.

### Edge cases / Gotchas

- Default `0` preserves today's always-emit behavior. Every existing test setup that asserts on a commit span continues to pass without configuration change. Hosts that want to drop fast commits configure a positive value explicitly. The query gate's default is also `0` (emit-all) — operators on read-heavy workloads tune the query threshold upward (e.g., to `100` ms) to drop fast successful queries; commits stay at `0` longer because commit volume is bounded by transaction count.
- A commit whose duration equals the threshold emits, because the comparison is strictly `<` (less-than). A 100 ms commit against a 100 ms threshold passes the gate.
- Failed commits (`writeTransactionFailed`) bypass the gate unconditionally. The caught cause populates `error.type` and the span status is set to ERROR. A stream of failing commits emits one span per failure regardless of duration.
- No per-tag override exists in v1. Commits fire at the transaction boundary and have no per-statement query tag context. If operators need per-database or per-host commit thresholds later, the natural extension is a `TagRule<Long>` resolver keyed on `TransactionDetails.getDatabaseName()` and configured via `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_DATABASE_RULES`; the resolver hierarchy already exists (mirrors the query-side `SlowQueryThresholdResolver`), so adding the per-database axis is one row in the OTel listener's gate. Not in v1 scope because no production demand has surfaced.
- The trace gate runs before commit span allocation. It reads only the duration and configured threshold. Metrics-enabled listeners record the commit histogram sample first. A gated-out commit then returns without building a span.
- Custom (non-OTel) `TransactionMetricsListener` implementations are unaffected. The gate lives only in `OTelTransactionMetricsListener`; the listener iteration in the merged-snapshot view still fires every registered listener for every commit. Custom listeners that want their own gating semantics implement it inside their callbacks.

### References
- D-records: D38 (commit-side slow-query threshold inside OTel listener, error bypass, single global threshold, no per-tag rules in v1)

## OpenTelemetry logs integration

The chokepoint is `SLF4JLogManager.log(...)` (`core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/SLF4JLogManager.java:38-103`), the single method every YTDB log call crosses. The logs integration adds a `LogAppenderHook` interface alongside `SLF4JLogManager`, a `CopyOnWriteArrayList<LogAppenderHook>` field on `SLF4JLogManager` itself, an `installAppenderHook(LogAppenderHook)` / `removeAppenderHook(LogAppenderHook)` accessor pair, and one new line inside `log(...)` at the existing emit point (right before `logEventBuilder.log()` on line 98) that iterates the hook list. Before each iteration the manager skips records whose `requesterName` starts with `io.opentelemetry.` or equals `io.youtrackdb.otel.appender`; this name-prefix filter blocks the cross-thread recursive cycle that the OTel exporter would otherwise create through a `jul-to-slf4j` bridge (the exporter writes on its own thread pool where the per-thread re-entrance guard does not apply). Operators who need to admit specific OTel-internal loggers override the prefix via `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` (comma-separated full prefixes; defaults to the two values above). The hook signature is binding-agnostic: it takes the resolved requester class name, the resolved database name, the slf4j `Level`, both the format string and formatted message, the optional `Throwable` and the event timestamp. Hooks fire after the per-logger `isEnabledForLevel(level)` filter SLF4J already applies, so a hook subscribed at `INFO` for a logger configured at `WARN` sees no records below `WARN`. The OTel module ships `OTelLogAppender` as the only built-in implementation; the hook surface is not internal-only because a host that wants OTel-independent log routing can register its own.

Severity mapping (slf4j `event.Level` → OTel sem-conv `severityNumber`):

| slf4j Level | OTel `severityNumber` | OTel `severityText` |
|---|---|---|
| `TRACE` | 1 | `TRACE` |
| `DEBUG` | 5 | `DEBUG` |
| `INFO` | 9 | `INFO` |
| `WARN` | 13 | `WARN` |
| `ERROR` | 17 | `ERROR` |

The mapping has no `FATAL` row because slf4j `event.Level` carries no `FATAL` constant (it stops at `ERROR`). The `OPENTELEMETRY_LOGS_MIN_SEVERITY` config accepts `FATAL` for forward compatibility but treats it identically to `ERROR` (severity number 17).

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
          .setTimestamp(eventEpochNanos, TimeUnit.NANOSECONDS)   // original log-call wall-clock
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
      // hard context: Context.current() picks up the active span automatically.
      builder.emit();
    } catch (RuntimeException | LinkageError | AssertionError t) {
      FALLBACK.log(java.util.logging.Level.WARNING, "OTel log emit failed", t);
    } finally {
      reentrant.set(false);
    }
  }
}
```

The `setTimestamp(eventEpochNanos, NS)` carries the SLF4J `LoggingEvent.getTimeStamp()` value (the wall-clock at the original log call, captured by the SLF4J binding before YTDB's hook fires) lifted from milliseconds to nanoseconds. The OTel sem-conv `Timestamp` field therefore matches the original log-call time even for high-frequency `DEBUG`/`TRACE` records where dispatch latency would otherwise drift `Instant.now()` by tens of microseconds. The `setObservedTimestamp(Instant.now())` follows sem-conv guidance for the "log was observed by the appender" axis. The fallback `Logger.getLogger("io.youtrackdb.otel.appender")` replaces the `Handler.reportError` channel a JUL Handler would have used. The fallback logger is itself part of the SLF4J→JUL or SLF4J→Logback dispatch chain (via slf4j-jdk14 in server mode), so its output reaches the operator's normal log sink without re-entering the OTel hook (the appender's own logger name is filtered out at install time to keep the cycle impossible).

**Body-policy default-deny.** The `body` slot is set from `formatString` when `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY=false` (the default) and from `formattedMessage` when `true`. The default-false matches the trace-pillar discipline of `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=false`: log lines whose format string is `"Query failed: {} with args {}"` ship that string as the body, rather than `"Query failed: SELECT FROM User WHERE id = 42 with args [42]"`. The `exception.stacktrace` attribute (set from `Throwable.getStackTrace()`) and `exception.message` (from `Throwable.getMessage()`) are NOT gated by the same flag because the throwable identity itself is the load-bearing diagnostic signal for failure investigation; hosts that need to redact those install a host-side OTel `LogRecordProcessor` at the collector boundary. The flag is a default-deny knob for the body slot only.

The `SLF4JLogManager` hook iteration runs after the existing `isEnabledForLevel(level)` filter and before the marker/format work the existing path does. Hooks receive both the format string and the formatted message. The appender chooses the body through its message-body flag without reformatting the arguments.

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

**Sem-conv v1.33.0 DB metrics (curated subset):**

| OTel metric name | Stability | Instrument | YTDB source |
|---|---|---|---|
| `db.client.connection.count` | stable | `ObservableLongUpDownCounter` | active session count read from `DatabaseSessionRegistry` (new) |
| `db.client.operation.duration` | stable | `DoubleHistogram` | query and commit listeners record `executionTimeNanos` converted to seconds on every completion, before trace-emission gates. Query samples distinguish outer Gremlin, native SQL and Gremlin-origin SQL. Commit samples remain separate. No profiler-side metric is needed. |
| `db.client.response.returned_rows` | experimental | `DoubleHistogram` | row-count distribution from `QueryDetails.getResultCount(): OptionalLong` (the listener contract adds the accessor; the SQL fire site populates it from the `InstrumentedSqlResultSet` wrapper's per-`next()` row counter, which is correct for both `LocalResultSet` and `CachedResultSetView` inner result-sets per YTDB-820 coordination; the Gremlin path populates it from `YTDBQueryMetricsStep`'s row counter, incremented after each successful `super.next()` on the terminal step, uniformly across Path A and Path B because the step is appended at the pipeline tail by `YTDBQueryMetricsStrategy.apply`) |

**YTDB-specific (`youtrackdb.*` namespace):**

| OTel metric name | Group | Instrument | Profiler source |
|---|---|---|---|
| `youtrackdb.cache.hit_ratio` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.CACHE_HIT_RATIO` (existing — `Ratio`, cache hits over reads, 60s window) |
| `youtrackdb.disk.read_rate_bps` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.DISK_READ_RATE` (existing — `TimeRate`, bytes per second, 60s window) |
| `youtrackdb.disk.write_rate_bps` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.DISK_WRITE_RATE` (existing — `TimeRate`, bytes per second, 60s window) |
| `youtrackdb.cache.page_reads_total` | `cache` | `ObservableLongCounter` | `CoreMetrics.PAGE_READ_COUNT` (new — `Counter`, monotonic page-read count populated by the disk-cache page-fault path) |
| `youtrackdb.cache.evictions_total` | `cache` | `ObservableLongCounter` | `CoreMetrics.FILE_EVICTION_RATE` (existing — converted from rate to monotonic counter at OTel-bridge translation time) |
| `youtrackdb.wal.pending_bytes` | `wal` | `ObservableLongGauge` | `CoreMetrics.WAL_PENDING_BYTES` (new — `Gauge<Long>`, in-flight WAL backlog read at the WAL writer's `flushPending()` site) |
| `youtrackdb.wal.flush_rate_bps` | `wal` | `ObservableDoubleGauge` | `CoreMetrics.WAL_FLUSH_RATE` (new — `TimeRate`, WAL flush bytes per second, 60s window) |
| `youtrackdb.lock.contention_count` | `locks` | `ObservableLongCounter` | `CoreMetrics.LOCK_WAIT_COUNT` (new — `Counter`, monotonic wait-event count populated by `ReadWriteLock` wrappers in the storage layer) |
| `youtrackdb.storage.size_bytes` | `storage` | `ObservableLongGauge` (per `database` attribute) | `CoreMetrics.DATABASE_SIZE_BYTES` (new — per-`MetricScope.Database` `Gauge<Long>` read from the storage layer's `getSizeOnDisk()`) |
| `youtrackdb.transaction.commit_rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_WRITE_RATE` (existing — `TimeRate`, write commits per second, 60s window) |
| `youtrackdb.transaction.rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_RATE` (existing — `TimeRate`, all transactions per second, 60s window) |
| `youtrackdb.transaction.rollback_rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_WRITE_ROLLBACK_RATE` (existing — `TimeRate`, write rollbacks per second, 60s window) |
| `youtrackdb.transaction.active_count` | `transactions` | `ObservableLongGauge` | `CoreMetrics.ACTIVE_TX_COUNT` (existing — per-database `Gauge<Integer>`) |
| `youtrackdb.transaction.oldest_age_seconds` | `transactions` | `ObservableLongGauge` | `CoreMetrics.OLDEST_TX_AGE` (existing — per-database `Gauge<Long>`) |

Sem-conv stability matters for dashboard authors: `stable` metrics will keep their names and semantics in future spec revisions; `experimental` ones may rename or change attribute shapes between sem-conv versions. The bridge surfaces both, but a downstream dashboard that depends on experimental metrics needs to track the sem-conv changelog between v1.33.0 and whatever version YTDB pins next.

`DatabaseSessionRegistry` is a new component. The metrics work adds its active-session accounting and read sites. `Counter` is also a new profiler primitive, with a matching `MetricType` and writer sites for the new monotonic counts. Existing profiler primitives are `Gauge`, `Stopwatch`, `TimeRate` and `Ratio`.

The query listener records the returned-row histogram on each completion when `getResultCount()` is present. Duration samples carry bounded operation, collection, source and duration-semantics dimensions. Public-query totals select outer Gremlin samples plus SQL with `invoked_via=native`. They exclude commits and Gremlin-origin SQL. The SQL entry context captures `YTDBContextKeys.GREMLIN_ORIGIN`, independent of the tag. Its presence gives `invoked_via=gremlin_dsl` for graph-step fallback as well as DSL passthrough. Parent IDs and span attributes read at close cannot identify this provenance.

Profile does not change normal span selection or retention. Path B steps that enter SQL can emit an outer Gremlin span with retained SQL children. Path A profiles the steps of the held plan before execution and reads their costs afterward. Path B retains its executed graph-step plan. Explain renders those plan objects. ClassCount has no plan and its profiling flag is a no-op. Profile metrics are forwarded to TinkerPop separately from OTel histograms.

Histograms are synchronous instruments. The SDK has no asynchronous histogram. The metrics switch and included-group filter govern listener instruments as well as bridge callbacks. Trace gates do not discard metric samples. The shipped rate, count and latency panels consume these metrics through Prometheus.

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

Implementation: `MetricsRegistry` holds a `CopyOnWriteArrayList<Consumer<MetricRegistrationEvent>>` and fires every listener inside `MetricsGroup.init(...)` (the existing `computeIfAbsent` site) when a new metric is created. The fire is synchronous on the registering thread; listeners that need to do heavy work (registering an OTel observable callback is non-trivial) must schedule it onto their own executor. `OTelMetricsBridge` posts to its scheduled executor so the registration thread is not blocked. Each listener fire runs inside `try { listener.accept(event); } catch (Exception | LinkageError | AssertionError t) { LogManager.instance().warn(this, "metric-registration listener threw", t); }`, so a misconfigured listener logs at WARN and the database-open path completes — the API enforces exception isolation rather than relying on listener-side discipline.

`forEachMetric(...)` walks `globalMetrics.mGroup.metrics`, then iterates `perDatabaseMetrics.values()` walking each `DatabaseMetrics.mGroup.metrics`. The walk over `ConcurrentHashMap.entrySet()` is weakly consistent. The bridge subscribes before the walk, then sends both enumeration results and registration events through one serialized registration path. That path de-duplicates by source metric identity. A metric seen by both paths gets one callback. Enumeration and events both carry the database name. Metrics sharing an OTel name use a shared instrument with a `database` attribute on each database-scoped observation.

### Async instrument lifecycle

`OTelMetricsBridge.start()` does three things at SDK init:

1. Subscribe through `registry.addRegistrationListener(...)`. Each event posts a registration task to the bridge executor.
2. Enumerate through `Profiler.getMetricsRegistry().forEachMetric(...)`. Send every result to the same executor. Keep an identity-keyed `Map<Metric<?>, AutoCloseable>` of callback handles. Register each source once, even when the walk and listener both see it. Reuse the instrument for a shared OTel name and attach the database attribute to observations. Each callback captures its source `Metric` reference and reads its current value at SDK collection time.
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

- High-cardinality attributes: a `youtrackdb.storage.size_bytes` per-database gauge fans out one OTel data point per `database` attribute value at every collection cycle. Hosts with 10k+ databases see 10k+ data points per period; the exporter side has to absorb that. The bridge does NOT add per-class or per-RID attributes — `MetricScope.Class` profiler entries collapse to one OTel data point per (metric, database) pair. If per-class breakdown is needed downstream, the host configures a dedicated OTel exporter that views the raw profiler dump.
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

## Quick-start observability stack

The quick-start docker-compose example assembles five containerized services into one Collector pipeline plus three viewer backends, with Grafana provisioning the operator-facing UI surface. The deep mechanism here covers what the upstream tools do, how the Collector pipeline routes the three signals, and what the smoke script actually verifies — material that does not belong in design.md because it is upstream-tool-specific operational detail, not YTDB design.

Grafana is the chosen operator UI, self-hosted or Grafana Cloud. YouTrackDB does not embed or depend on Grafana. The YTDB-owned SDK exports OTLP only to an OTel Collector. Production routes traces to Tempo, logs to Loki and metrics to Prometheus or a compatible store such as Mimir or Grafana Cloud metrics. Other backends work through exporter settings or a host-owned SDK. Shipped dashboards and first-class setup documentation target Grafana only. The rejected alternative is backend-neutral documentation with no shipped dashboards, which leaves operators to assemble the UI. The planned user manual includes "Connect to Grafana" for self-hosted and Cloud endpoints, headers and dashboard import. Other hosted backends have no first-class setup documentation in this deliverable. When the YTDB exporter endpoint is unset, grpc defaults to `http://localhost:4317` and http/protobuf to `http://localhost:4318` with signal-specific `/v1/{signal}` paths.

### Collector pipeline shape

The Collector config (`otel-collector-config.yaml`) defines three pipelines on one process, each consuming the same OTLP receiver pair and routing through shared processors to a signal-specific exporter:

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
      - key: deployment.environment
        value: local
        action: upsert

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls:
      insecure: true
  otlphttp/loki:
    endpoint: http://loki:3100/otlp
  prometheus:
    endpoint: 0.0.0.0:8889
    namespace: youtrackdb
    const_labels:
      deployment_environment: local

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [otlp/jaeger]
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
2. **`batch` runs before `resource`** so the `deployment.environment=local` attribute lands on the batched envelope, not on every individual span / log / metric. The attribute is identical across all data points in a batch, so per-data-point application wastes CPU.
3. **Prometheus is pull-not-push**. The Collector's `prometheus` exporter exposes a `/metrics` HTTP endpoint on `:8889`; Prometheus scrapes it at the 15 s interval configured in `prometheus.yml`. The other two exporters push (`otlp/jaeger` to Jaeger's OTLP receiver on `:4317`; `otlphttp/loki` to Loki's OTLP HTTP path on `:3100/otlp`).

### Grafana datasource provisioning and correlator wiring

Grafana provisions the three datasources at startup via `grafana/provisioning/datasources/datasources.yml`. The quick-start uses Jaeger. Production uses Tempo. Both trace data sources link to Loki and Prometheus-compatible metrics through their supported correlation settings. The local Jaeger configuration is:

```yaml
apiVersion: 1
datasources:
  - name: Jaeger
    type: jaeger
    uid: jaeger
    url: http://jaeger:16686
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

The `tracesToLogsV2` correlator wires Jaeger's span-detail panel "Logs for this span" button to a Loki query filtered by `trace_id` resolved from the span's trace context. The 2-minute window on either side of the span absorbs clock skew between the YTDB host and the Loki ingester; tightening the window risks missing logs emitted near the span boundary. The `filterByTraceID: true` setting tells Grafana to add `|= "<traceId>"` to the Loki LogQL, scoping the result to one trace.

The `tracesToMetrics` correlator points at Prometheus and joins on `service.name`. Operators clicking the "Metrics for this service" link in Jaeger land in a Prometheus panel filtered to `youtrackdb` metrics within the same 2-minute window.

### Dashboard placeholder strategy

The same three dashboard JSON files serve the quick-start, self-hosted production and Grafana Cloud. Their data source inputs select a Jaeger or Tempo trace source, a Loki log source and a Prometheus-compatible metrics source. Trace views use the selected trace source. Latency, rate and count panels query Prometheus metrics. Dashboard import maps these inputs to local UIDs. Quick-start provisioning supplies the local defaults. Exported instance UIDs must not become fixed dependencies of the committed JSON.

The author workflow for any dashboard edit: bring the stack up, open Grafana, edit the dashboard in the UI, export via "Share → Export → Save to file", run `scripts/normalize-dashboard.sh <file>` to replace the captured UIDs with the placeholder strings, commit. The normalize script is a thin `jq` wrapper that operates on the exported JSON deterministically.

### Smoke-script verification semantics

`scripts/smoke.sh` exits non-zero when any pillar fails to land within 30 seconds. The sequence:

1. **Bring up the stack** if not already healthy (`docker compose up -d --wait`).
2. **Run a minimal embedded YTDB query** via a one-shot `java -jar` invocation against the Maven-built artifact. The sample settings and smoke invocation set `OPENTELEMETRY_ENABLED=true`, `OPENTELEMETRY_LOGS_ENABLED=true` and `OPENTELEMETRY_METRICS_ENABLED=true`, with the local Collector endpoint. Other settings keep their shipped defaults. These example overrides do not change product defaults. The query is a fixed `SELECT FROM OUser LIMIT 1` against an in-memory database — small, deterministic, exercises the SQL pillar through `db.query(...)`.
3. **Poll Jaeger** at `http://localhost:16686/api/services` until `youtrackdb` appears, then `http://localhost:16686/api/traces?service=youtrackdb&limit=1` until at least one trace lands. 30 s timeout per pillar.
4. **Poll Loki** at `http://localhost:3100/loki/api/v1/labels` until `service_name` appears, then `http://localhost:3100/loki/api/v1/query?query={service_name="youtrackdb"}` until at least one log record returns.
5. **Poll Prometheus** at `http://localhost:9090/api/v1/label/service_name/values` until `youtrackdb` appears, then `http://localhost:9090/api/v1/query?query=db_client_operation_duration_seconds_count{service_name="youtrackdb"}` until at least one sample returns.
6. **Exit 0** when all three polls succeed. **Exit non-zero with a diagnostic message naming the missing pillar** when any poll times out at 30 s.

The 30 s timeout is the load-bearing constant. It must be longer than the Collector's `batch` timeout (1 s) plus the worst-case exporter push interval plus the Prometheus scrape interval (15 s), with margin for cold-start container readiness. Shorter timeouts cause false negatives on under-resourced CI runners; longer timeouts mask actual failures by retrying until the optional CI job's outer timeout kicks in.

### Edge cases / Gotchas

- **Loki OTLP HTTP path mismatch**. Loki 3.x accepts OTLP on `/otlp/v1/logs`, not the bare `/otlp` path Loki 2.x advertised. The Collector config writes the full path explicitly (`http://loki:3100/otlp`) and the Collector appends `/v1/logs` per OTLP spec. A common operator surprise on a stale Loki version: logs land in Loki but the OTLP receiver returns 404 because the path resolver fell through. The README troubleshooting section names this case and points at the matching image tag.
- **Prometheus scrape lag on a fresh stack**. The first scrape happens 15 s after Prometheus starts. The smoke script's 30 s timeout absorbs this; an operator running queries immediately after bring-up may see "no data" in Grafana for up to 15 s. Documented behavior, not a bug.
- **Collector resource attribute vs span attribute precedence**. The `resource` processor sets `deployment.environment=local` as a resource attribute, which Grafana surfaces alongside span attributes in the trace-detail panel. A host-side `OPENTELEMETRY_SERVICE_NAME` override propagates into the same resource attribute set via the autoconfigure builder; the Collector's `resource` processor uses `action: upsert` so it does not overwrite an already-set `service.name`, only fills it in when absent.
- **Jaeger Elasticsearch backend swap (out-of-scope)**. The example uses Jaeger's all-in-one binary with in-memory storage, which loses traces on container restart. Operators who want persistence beyond a workstation demo migrate to Jaeger's Elasticsearch backend; the swap is a Jaeger config change and the YTDB side does not move. Documented in the README's "production tightening" section as a pointer.
- **Cardinality blow-up via per-tenant span names**. Default span-name format `commit <dbName>` produces one span name per database. Multi-tenant hosts with thousands of databases hit Jaeger's span-name aggregation limits; the design's `OPENTELEMETRY_COMMIT_SPAN_NAME_INCLUDES_DBNAME=false` switch drops dbName from the span name, moving identity to the `db.namespace` attribute where Jaeger indexes it cheaply. The example does not flip the switch (single-database demo), but the README points at the operator-facing tuning.

### References
- D-records: D41 (Quick-start observability stack lands inside YTDB-496 PR as example-files-only deliverable; rationale and alternatives in implementation-plan.md)
- Invariants: example-files-only (no source-code edits in `core`, `server`, or the OTel module), pinned image versions only (no `latest` tags; periodic refresh tracked under follow-up ticket `YTDB-OTel-EXAMPLE-VERSIONS`), Maven `<resources>` exclusion (example never ships inside the built JAR)
