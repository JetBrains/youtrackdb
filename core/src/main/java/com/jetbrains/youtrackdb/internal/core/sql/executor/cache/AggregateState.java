package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionAverage.computeAverage;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The replay state for one cached single-aggregate query ({@code SELECT
 * COUNT|SUM|AVG|MIN|MAX|COUNT(DISTINCT prop) FROM C [WHERE p]}). It is seeded at cache-put by the
 * aggregate side-tap, which {@link #observe}s every contributing record before the aggregation step
 * collapses them into a scalar; at view construction the delta builder {@link #copy copies} it and
 * {@link #applyMutation replays} the transaction's post-populate mutations onto the copy, so {@link
 * #toResult} returns exactly the scalar a fresh uncached execution would compute at that moment.
 *
 * <p><b>Storage parity.</b> SUM and AVG fold their values through the same {@link
 * PropertyTypeInternal#increment} call storage's {@code SQLFunctionSum.sum} / {@code
 * SQLFunctionAverage.sum} make: the first contributing value is seeded verbatim, every later value is
 * {@code increment}ed onto the running {@link #sumAccumulator}. Because {@code PropertyTypeInternal}
 * exposes no symmetric subtract, a mutation that changes the contributing set (T&rarr;T value change,
 * T&rarr;F drop, F&rarr;T add) triggers a full re-fold of {@link #contributingValues} from scratch
 * rather than an incremental adjust. This reproduces storage's numeric-promotion, Long-overflow, and
 * Long&rarr;Double precision-loss behaviour bit-for-bit. AVG additionally tracks the contributor
 * count in {@link #count} and finalises through the same type-dispatched division {@code
 * SQLFunctionAverage.computeAverage} uses (integer truncation for Integer/Long, {@code HALF_UP} for
 * BigDecimal), not a plain {@code sum / count}.
 *
 * <p><b>MIN/MAX.</b> The running extremum is held in {@link #currentScalar} with the RID that
 * produced it in {@link #extremumRid}. Per-value material lives in {@link #contributingValues} so the
 * two extremum-leaving transitions (the holder drops out, or the holder's value moves away from the
 * extremum direction) can recompute the new extremum with an O(n) scan; every other transition is
 * O(1) via a single comparison against {@link #currentScalar}. The "was extremum" state is decided by RID
 * identity ({@code rid.equals(extremumRid)}), never by {@code Number.equals}, to sidestep the
 * cross-{@code Number}-subtype equality hazard.
 *
 * <p><b>COUNT(DISTINCT).</b> {@link #distinctBuckets} maps each distinct value to the set of
 * RIDs currently contributing it; the scalar is the live bucket count, recomputed at {@link
 * #toResult}. Bucket keys use raw {@code Object.equals}/{@code hashCode}, mirroring {@code
 * SQLFunctionDistinct}'s {@code LinkedHashSet<Object>}, so {@code Long(5)} and {@code Integer(5)} are
 * distinct buckets exactly as in storage.
 *
 * <p><b>Membership-derived dispatch (collapse safety).</b> Every {@link #applyMutation} derives
 * its transition from two facts rather than from the operation type: {@code wasContributing} from
 * cache membership ({@link #contributingValues}{@code .containsKey(rid)}, or {@link #contributingRids}
 * for COUNT), and {@code nowContributing = (status == DELETED) ? false : matchAfter}. {@code status}
 * is consulted only to fold {@code DELETED} into {@code nowContributing = false}, never as a
 * stand-in for the before-state. This is required because {@code addRecordOperation} keeps a
 * collapsed pre-populate CREATE typed {@code CREATED} while bumping its version past populate, so a
 * {@code CREATED} op can carry a record already observed by the populate-time tap and already a
 * contributor; keying on {@code op.type} would misread it as brand-new and leave a stale contributor.
 *
 * <p><b>Memory cap.</b> The cache installs a contributor cap and a one-shot overflow callback at put
 * time (mirroring {@link CachedEntry}'s record cap, but bounding the per-contributor collections
 * rather than {@code results}: high-cardinality COUNT(DISTINCT)/MIN/MAX is the real OOM vector for an
 * aggregate, whose {@code results} holds a single scalar row). The first {@link #observe} that pushes
 * a tracked collection past the cap fires the callback once; the cache then removes the entry and
 * routes the key non-cacheable, while the in-flight populate still completes.
 *
 * <p>Single-transaction state observed only by the owning thread; no field is synchronised.
 */
public final class AggregateState {

  /** One of the {@code AGGREGATE_*} {@link CacheableShape} values; selects the fold/dispatch logic. */
  private final CacheableShape kind;

  /**
   * The property the aggregate reads from each contributing record ({@code SUM(price)} &rarr; {@code
   * price}). {@code null} for {@code COUNT(*)}, which observes membership only and reads no value.
   */
  @Nullable private final String propertyName;

  /** The projection alias the single scalar row carries in {@link #toResult} (e.g. {@code count(*)}). */
  private final String alias;

  // ---- COUNT(*) ----
  /** RIDs contributing to a {@code COUNT(*)}; the scalar is its size. Unused for the other kinds. */
  private final Set<RID> contributingRids = new HashSet<>();

  // ---- SUM / AVG / MIN / MAX / COUNT_DISTINCT ----
  /**
   * The post-state value each contributing RID supplies, kept so a set-changing mutation can re-fold
   * (SUM/AVG), rescan for a new extremum (MIN/MAX), or re-bucket (COUNT_DISTINCT). This is the blocking
   * dependency every value-aggregate carries; its O(n) memory is the price of incremental replay.
   *
   * <p>A {@link LinkedHashMap} so iteration order equals contributor insertion order, which is the
   * populate-time observe order (== storage's plan scan order). The SUM/AVG re-fold folds these values
   * in iteration order, so an insertion-ordered map reproduces storage's scan-order fold bit-for-bit;
   * a plain {@code HashMap} would fold in hash-bucket order and diverge on order-sensitive arithmetic
   * (IEEE-754 float/double non-associativity, Integer&rarr;Long overflow-promotion typing). {@link
   * #copy} preserves the ordering ({@code LinkedHashMap.putAll} from a {@code LinkedHashMap} source
   * copies in source iteration order).
   */
  private final Map<RID, Object> contributingValues = new LinkedHashMap<>();

  // ---- SUM / AVG ----
  /** Running SUM/AVG total, folded through {@link PropertyTypeInternal#increment}. Seeded null. */
  @Nullable private Number sumAccumulator;

  /** Contributor count, the AVG divisor; recomputed alongside {@link #sumAccumulator} on every re-fold. */
  private int count;

  /**
   * Whether {@link #sumAccumulator}/{@link #count} are stale and a re-fold is owed before the next read.
   * SUM/AVG contributor changes (observe-time seeds and replay-time add/remove/update) set this rather
   * than re-folding eagerly; {@link #ensureSumFolded} performs the single O(n) fold on the next read of
   * the scalar. Deferring collapses a build that replays {@code m} mutations from {@code m} folds
   * (O(m&middot;n)) to one fold (O(n + m)) without weakening storage-fold parity: only the final
   * scalar is observable, and a single fold over the final contributor set is the same fold a fresh
   * execution runs.
   */
  private boolean sumDirty;

  // ---- MIN / MAX ----
  /** The current extremum value (MIN/MAX). */
  @Nullable private Object currentScalar;

  /** The RID that produced {@link #currentScalar}; the "was extremum" check uses RID identity against it. */
  @Nullable private RID extremumRid;

  // ---- COUNT_DISTINCT / DISTINCT_VALUES ----
  /**
   * Distinct value &rarr; contributing RIDs. The {@code AGGREGATE_COUNT_DISTINCT} scalar is the live
   * bucket count; the {@code DISTINCT_VALUES} view emits the bucket keys as rows. A {@link LinkedHashMap}
   * so {@link #distinctValuesInOrder} yields keys in first-occurrence (observe == scan) order, matching
   * a fresh {@code SELECT distinct(prop)}; a delta F-&gt;T transition appends a new value after the
   * populate-time values, where a fresh execution also emits a tx-created record's new value.
   */
  private final Map<Object, Set<RID>> distinctBuckets = new LinkedHashMap<>();

  // ---- Memory cap (installed by the cache at put time) ----
  private int maxContributors = Integer.MAX_VALUE;

  @Nullable private Runnable onOverflow;

  private boolean overflowed;

  public AggregateState(
      @Nonnull CacheableShape kind, @Nullable String propertyName, @Nonnull String alias) {
    assert kind.isAggregate() : "AggregateState requires an AGGREGATE_* shape, got " + kind;
    this.kind = kind;
    this.propertyName = propertyName;
    this.alias = alias;
  }

  public CacheableShape getKind() {
    return kind;
  }

  @Nullable public String getPropertyName() {
    return propertyName;
  }

  public String getAlias() {
    return alias;
  }

  /**
   * Installs the contributor cap and the one-shot overflow callback the cache fires when an {@link
   * #observe} crosses it. Called once by the cache when the entry is stored. A cap of {@code
   * Integer.MAX_VALUE} (the default for a state never stored) disables the check.
   */
  public void setOverflowGuard(int maxContributors, @Nonnull Runnable onOverflow) {
    this.maxContributors = maxContributors;
    this.onOverflow = onOverflow;
  }

  /**
   * Observes one contributing record at populate time (called by the side-tap before the aggregation
   * step collapses the stream). Reads the RID and, for value aggregates, the target property from the
   * post-projection {@link Result}, then folds it into the running state exactly as storage would. The
   * side-tap drives the populating plan once, so every contributing RID is observed exactly once per
   * populate.
   */
  public void observe(@Nonnull Result result) {
    var rid = result.getIdentity();
    // The aggregate side-tap forwards post-projection records that always carry an identity; a null
    // RID would corrupt the per-RID contributor maps the replay depends on. Assert it (no-op without
    // -ea) so a broken upstream invariant fails loudly in tests rather than silently miscounting.
    assert rid != null : "aggregate tap observed a result with a null identity";
    if (kind == CacheableShape.AGGREGATE_COUNT) {
      contributingRids.add(rid);
      checkOverflow(contributingRids.size());
      return;
    }
    var value = propertyName == null ? null : result.<Object>getProperty(propertyName);
    if (value == null) {
      // Storage's aggregate functions skip null values entirely (they never enter the running total,
      // the extremum comparison, or a distinct bucket), so a null contributor is not tracked here
      // either; it simply does not contribute, matching fresh execution.
      return;
    }
    addContributor(rid, value);
    checkOverflow(trackedSize());
  }

  /**
   * Replays one post-populate mutation onto this (copied) state during delta build. {@code status} is
   * the {@link RecordOperation} type; {@code matchAfter} is whether the post-mutation record still
   * satisfies the query's WHERE (membership only). The transition is derived from cache membership and
   * {@code nowContributing}, never from {@code status} alone. For a record that still
   * contributes, the post-state value is read from the mutated record.
   */
  public void applyMutation(@Nonnull RecordAbstract record, byte status, boolean matchAfter) {
    var rid = record.getIdentity();
    var wasContributing = isContributing(rid);
    var nowContributing = status != RecordOperation.DELETED && matchAfter;

    if (!wasContributing && !nowContributing) {
      // F->F: a non-contributor that still does not contribute. No-op for every kind.
      return;
    }

    if (kind == CacheableShape.AGGREGATE_COUNT) {
      // COUNT(*) tracks membership only; the post-state value is irrelevant.
      if (nowContributing) {
        contributingRids.add(rid);
      } else {
        contributingRids.remove(rid);
      }
      return;
    }

    // Value aggregates read the new value only when the record still contributes; a drop (T->F) needs
    // no value because the contributor is being removed.
    Object newValue = null;
    if (nowContributing) {
      newValue = readValue(record);
      if (newValue == null) {
        // A null post-state value does not contribute (storage skips nulls), so a record that "matches"
        // WHERE but has a null aggregate property is treated as a non-contributor.
        nowContributing = false;
      }
    }

    if (!wasContributing) {
      // F->T add of a genuinely new contributor (or a collapsed pre-populate create the membership
      // test correctly sees as new because the populate-time tap never observed it).
      if (nowContributing) {
        addContributor(rid, newValue);
      }
      return;
    }

    if (!nowContributing) {
      // T->F drop of an existing contributor.
      removeContributor(rid);
      return;
    }

    // T->T: an existing contributor whose value may have changed. Update the per-RID value, then
    // re-derive the scalar per kind (full re-fold for SUM/AVG, comparison/scan for MIN/MAX, re-bucket
    // for COUNT_DISTINCT).
    updateContributor(rid, newValue);
  }

  /** Whether {@code rid} currently contributes, by cache membership, not by op type. */
  private boolean isContributing(@Nonnull RID rid) {
    if (kind == CacheableShape.AGGREGATE_COUNT) {
      return contributingRids.contains(rid);
    }
    return contributingValues.containsKey(rid);
  }

  /** Reads the aggregate's target property off a mutated record, as an Entity (null when no property). */
  @Nullable private Object readValue(@Nonnull RecordAbstract record) {
    if (propertyName == null) {
      return null;
    }
    // A value aggregate's target is always a schema property on an Entity record; non-Entity records
    // (Blobs) never satisfy a property predicate and are class-filtered out before reaching here.
    if (record instanceof Entity entity) {
      return entity.getProperty(propertyName);
    }
    return null;
  }

  /** F->T add: route to the kind-specific accumulator and record the per-RID value. */
  private void addContributor(@Nonnull RID rid, @Nonnull Object value) {
    switch (kind) {
      case AGGREGATE_SUM, AGGREGATE_AVG -> {
        contributingValues.put(rid, value);
        sumDirty = true;
      }
      case AGGREGATE_MIN, AGGREGATE_MAX -> {
        contributingValues.put(rid, value);
        if (currentScalar == null || beatsExtremum(value, currentScalar)) {
          currentScalar = value;
          extremumRid = rid;
        }
      }
      case AGGREGATE_COUNT_DISTINCT -> {
        contributingValues.put(rid, value);
        distinctBuckets.computeIfAbsent(value, k -> new HashSet<>()).add(rid);
      }
      default -> throw new IllegalStateException("addContributor on non-value kind " + kind);
    }
  }

  /** T->F drop: remove the per-RID value and re-derive the scalar per kind. */
  private void removeContributor(@Nonnull RID rid) {
    switch (kind) {
      case AGGREGATE_SUM, AGGREGATE_AVG -> {
        contributingValues.remove(rid);
        sumDirty = true;
      }
      case AGGREGATE_MIN, AGGREGATE_MAX -> {
        var wasExtremum = rid.equals(extremumRid);
        contributingValues.remove(rid);
        if (wasExtremum) {
          recomputeExtremum();
        }
      }
      case AGGREGATE_COUNT_DISTINCT -> {
        var oldKey = contributingValues.remove(rid);
        removeFromBucket(oldKey, rid);
      }
      default -> throw new IllegalStateException("removeContributor on non-value kind " + kind);
    }
  }

  /** T->T value change of an existing contributor: update per-RID value and re-derive the scalar. */
  private void updateContributor(@Nonnull RID rid, @Nonnull Object newValue) {
    switch (kind) {
      case AGGREGATE_SUM, AGGREGATE_AVG -> {
        contributingValues.put(rid, newValue);
        sumDirty = true;
      }
      case AGGREGATE_MIN, AGGREGATE_MAX -> {
        var wasExtremum = rid.equals(extremumRid);
        contributingValues.put(rid, newValue);
        if (!wasExtremum) {
          // A non-holder moved; it can only become the new extremum, never invalidate the old one.
          if (currentScalar == null || beatsExtremum(newValue, currentScalar)) {
            currentScalar = newValue;
            extremumRid = rid;
          }
        } else if (staysInExtremumDirection(newValue, currentScalar)) {
          // The holder moved further into (or stayed at) the extremum direction: it is still the
          // extremum, only its value changed. O(1).
          currentScalar = newValue;
        } else {
          // The holder moved away from the extremum direction; the new extremum is unknown without a
          // scan over the remaining contributors. O(n).
          recomputeExtremum();
        }
      }
      case AGGREGATE_COUNT_DISTINCT -> {
        var oldKey = contributingValues.put(rid, newValue);
        if (oldKey != null && oldKey.equals(newValue)) {
          // Same bucket in DISTINCT terms: nothing changes.
          return;
        }
        removeFromBucket(oldKey, rid);
        distinctBuckets.computeIfAbsent(newValue, k -> new HashSet<>()).add(rid);
      }
      default -> throw new IllegalStateException("updateContributor on non-value kind " + kind);
    }
  }

  /**
   * Folds {@link #sumAccumulator}/{@link #count} from {@link #contributingValues} only when {@link
   * #sumDirty} is set, then clears the flag. Called lazily on every read of the SUM/AVG scalar so the
   * fold runs once per build (after the replay loop has applied all membership/value changes) rather
   * than once per mutation.
   */
  private void ensureSumFolded() {
    if (sumDirty) {
      refoldSum();
      sumDirty = false;
    }
  }

  /**
   * Re-folds the entire {@link #contributingValues} through {@link PropertyTypeInternal#increment}
   * from a verbatim first-value seed, exactly as storage's SUM/AVG accumulate. A full re-fold (rather
   * than an incremental add/subtract) is required because {@code PropertyTypeInternal} exposes no
   * symmetric subtract, and only re-folding from scratch reproduces storage's numeric-promotion and
   * overflow behaviour bit-for-bit. The fold walks {@link #contributingValues} in insertion (==
   * observe == scan) order so the result matches storage's scan-order fold bit-for-bit. Also recomputes
   * {@link #count} (the AVG divisor).
   */
  private void refoldSum() {
    Number acc = null;
    var n = 0;
    for (var v : contributingValues.values()) {
      var num = (Number) v;
      if (acc == null) {
        // FIRST TIME: seed verbatim, matching SQLFunctionSum.sum's first-value branch.
        acc = num;
      } else {
        acc = PropertyTypeInternal.increment(acc, num);
      }
      n++;
    }
    sumAccumulator = acc;
    count = n;
  }

  /** Recomputes the MIN/MAX extremum by scanning {@link #contributingValues}. O(n). */
  private void recomputeExtremum() {
    currentScalar = null;
    extremumRid = null;
    for (var e : contributingValues.entrySet()) {
      var value = e.getValue();
      if (currentScalar == null || beatsExtremum(value, currentScalar)) {
        currentScalar = value;
        extremumRid = e.getKey();
      }
    }
  }

  /** Removes {@code rid} from {@code key}'s distinct bucket, dropping the bucket when it empties. */
  private void removeFromBucket(@Nullable Object key, @Nonnull RID rid) {
    if (key == null) {
      return;
    }
    var bucket = distinctBuckets.get(key);
    if (bucket != null) {
      bucket.remove(rid);
      if (bucket.isEmpty()) {
        distinctBuckets.remove(key);
      }
    }
  }

  /**
   * Whether {@code candidate} beats {@code current} in this kind's extremum direction (MAX: strictly
   * greater; MIN: strictly less). Cross-{@code Number}-subtype comparison goes through {@code
   * PropertyTypeInternal.castComparableNumber} first, exactly as {@code SQLFunctionMin}/{@code
   * SQLFunctionMax} do, so e.g. {@code Integer} vs {@code Long} compares numerically.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private boolean beatsExtremum(@Nonnull Object candidate, @Nonnull Object current) {
    var c = candidate;
    var cur = current;
    if (c instanceof Number && cur instanceof Number) {
      var casted = PropertyTypeInternal.castComparableNumber((Number) c, (Number) cur);
      c = casted[0];
      cur = casted[1];
    }
    var cmp = ((Comparable) c).compareTo(cur);
    return kind == CacheableShape.AGGREGATE_MAX ? cmp > 0 : cmp < 0;
  }

  /**
   * Whether {@code newValue} keeps the holder at-or-beyond the current extremum in its direction (MAX:
   * {@code new >= old}; MIN: {@code new <= old}). When true the holder is still the extremum and the
   * scalar can be updated in O(1); when false the holder lost the extremum and a rescan is needed.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private boolean staysInExtremumDirection(@Nonnull Object newValue, @Nonnull Object oldExtremum) {
    var nv = newValue;
    var oe = oldExtremum;
    if (nv instanceof Number && oe instanceof Number) {
      var casted = PropertyTypeInternal.castComparableNumber((Number) nv, (Number) oe);
      nv = casted[0];
      oe = casted[1];
    }
    var cmp = ((Comparable) nv).compareTo(oe);
    return kind == CacheableShape.AGGREGATE_MAX ? cmp >= 0 : cmp <= 0;
  }

  /** The number of tracked contributors for the cap check; COUNT(*) uses its own RID set. */
  private int trackedSize() {
    if (kind == CacheableShape.AGGREGATE_COUNT) {
      return contributingRids.size();
    }
    // For every value aggregate (including COUNT_DISTINCT, whose bucket map carries at most one entry
    // per contributing RID) contributingValues is the authoritative, largest collection to bound.
    return contributingValues.size();
  }

  /**
   * Fires the one-shot overflow callback the first time a tracked collection crosses the cap. The
   * cache reacts by removing the entry and routing the key non-cacheable; the in-flight populate still
   * completes (the populating view holds the entry directly), so the result is served once but never
   * retained for reuse.
   */
  private void checkOverflow(int size) {
    if (!overflowed && size > maxContributors) {
      overflowed = true;
      if (onOverflow != null) {
        onOverflow.run();
      }
    }
  }

  /**
   * A deep-enough copy for replay: a fresh {@code AggregateState} with new mutable containers (so
   * {@link #applyMutation} on the copy never disturbs the entry's seeded state) but reuse of the
   * underlying immutable RID, {@code Number}, and bucket-key references. The overflow guard is NOT
   * copied: the cap fires only during populate ({@link #observe}); a delta-build copy never observes
   * new contributors, only replays a bounded set of mutations.
   */
  @Nonnull
  public AggregateState copy() {
    var c = new AggregateState(kind, propertyName, alias);
    c.contributingRids.addAll(contributingRids);
    c.contributingValues.putAll(contributingValues);
    c.sumAccumulator = sumAccumulator;
    c.count = count;
    c.sumDirty = sumDirty;
    c.currentScalar = currentScalar;
    c.extremumRid = extremumRid;
    for (var e : distinctBuckets.entrySet()) {
      c.distinctBuckets.put(e.getKey(), new HashSet<>(e.getValue()));
    }
    return c;
  }

  /**
   * The single scalar row this aggregate produces, shaped like the original execution's output: one
   * property under the projection {@link #alias} carrying the finalised scalar. SUM over an empty set
   * is {@code 0} (matching {@code SQLFunctionSum.getResult}); COUNT is the contributor count;
   * COUNT(DISTINCT) is the live bucket count; AVG finalises through the same type-dispatched division
   * storage uses; MIN/MAX is the current extremum (null when no contributor remains).
   */
  @Nonnull
  public Result toResult(@Nullable DatabaseSessionEmbedded session) {
    var row = new ResultInternal(session, 1);
    row.setProperty(alias, scalar());
    return row;
  }

  /**
   * Whether a fresh execution emits NO row for the current contributor set, so the cached scalar view
   * must suppress its single row to match. A non-GROUP-BY aggregate over an empty input set emits one
   * row only for {@code COUNT} (count = 0); {@code SUM} / {@code AVG} / {@code MIN} / {@code MAX} emit
   * zero rows (verified against the engine: a fresh {@code SELECT min(x) FROM C WHERE <no-match>}
   * returns no rows, while {@code count(*)} returns a single {@code 0}). Suppression therefore applies
   * exactly when a value aggregate has no remaining contributor; COUNT and COUNT_DISTINCT always emit.
   */
  public boolean emitsNoRow() {
    return switch (kind) {
      case AGGREGATE_SUM, AGGREGATE_AVG, AGGREGATE_MIN, AGGREGATE_MAX ->
          contributingValues.isEmpty();
      default -> false;
    };
  }

  /**
   * The distinct values currently held, in first-occurrence order, one per {@code DISTINCT_VALUES}
   * output row. Backed by {@link #distinctBuckets}'s insertion order. The caller wraps each value as a
   * {@code {alias: value}} row, reproducing a fresh {@code SELECT distinct(prop)} result.
   */
  @Nonnull
  public List<Object> distinctValuesInOrder() {
    return new ArrayList<>(distinctBuckets.keySet());
  }

  /** The finalised scalar value per kind (the value {@link #toResult} stores under the alias). */
  @Nullable private Object scalar() {
    return switch (kind) {
      case AGGREGATE_COUNT -> (long) contributingRids.size();
      case AGGREGATE_COUNT_DISTINCT -> (long) distinctBuckets.size();
      // SQLFunctionSum.getResult returns 0 (an int) for an empty sum, not null; match it exactly.
      case AGGREGATE_SUM -> {
        ensureSumFolded();
        yield sumAccumulator == null ? 0 : sumAccumulator;
      }
      case AGGREGATE_AVG -> {
        ensureSumFolded();
        yield computeAverage(sumAccumulator, count);
      }
      case AGGREGATE_MIN, AGGREGATE_MAX -> currentScalar;
      default -> throw new IllegalStateException("scalar() on non-aggregate kind " + kind);
    };
  }

  // ---- Test / inspection accessors (package-private; the replay path uses the methods above) ----

  @Nullable Number getSumAccumulator() {
    ensureSumFolded();
    return sumAccumulator;
  }

  int getCount() {
    ensureSumFolded();
    return count;
  }

  @Nullable RID getExtremumRid() {
    return extremumRid;
  }

  Set<RID> getContributingRids() {
    return contributingRids;
  }

  Map<RID, Object> getContributingValues() {
    return contributingValues;
  }

  Map<Object, Set<RID>> getDistinctBuckets() {
    return distinctBuckets;
  }

  boolean isOverflowed() {
    return overflowed;
  }
}
