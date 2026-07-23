package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OperationsFreezer {

  private final LongAdder operationsCount = new LongAdder();
  private final AtomicInteger freezeRequests = new AtomicInteger();

  /**
   * The number of currently registered {@link FreezeKind#OPERATOR} freezes. The schema-commit
   * gate's kind probe: a schema-armed entrant never blocks or parks while this is positive.
   * Counter ordering is load-bearing for the gate's one-sided guarantee (implemented with
   * explicit comments at the arm and retract sites): the arm increments this counter BEFORE
   * {@code freezeRequests} (publish kind-before-count) and the release decrements it AFTER
   * (retract count-before-kind), so any entrant that observes an operator freeze's
   * {@code freezeRequests} contribution also observes its kind. The converse is deliberately not
   * guaranteed: in the release's retract window an entrant can still read the kind as active
   * after the count contribution is gone — a rare, loud, retryable spurious gate throw, accepted
   * by design (tests must not pin no-false-positives).
   */
  private final AtomicInteger operatorFreezeRequests = new AtomicInteger();

  /**
   * The ids of currently registered OPERATOR freezes, so an id-keyed release maps back to the
   * OPERATOR decrement and removes its record. Every registration is expected to release by its
   * real id — the storage-level operator {@code release()} pops the id its paired {@code
   * freeze()} retained — so the set stays bounded by the number of concurrently engaged operator
   * freezes. The {@code -1} sentinel remains only as a guarded fallback for a release with no
   * matching retained id (a double release): it maps to OPERATOR explicitly but cannot remove
   * any record.
   */
  private final Set<Long> operatorFreezeIds = ConcurrentHashMap.newKeySet();

  /**
   * Test-observability only: the number of retained operator freeze-id records. Pinned by the
   * bookkeeping-leak regression — repeated storage-level {@code freeze()}/{@code release()}
   * cycles must not grow this set.
   */
  public int registeredOperatorFreezeIdCount() {
    return operatorFreezeIds.size();
  }

  private final WaitingList operationsWaitingList = new WaitingList();

  private final AtomicLong freezeIdGen = new AtomicLong();
  private final ConcurrentMap<Long, FreezeParameters> freezeParametersIdMap =
      new ConcurrentHashMap<>();

  private final ThreadLocal<ModifiableInteger> operationDepth =
      ThreadLocal.withInitial(ModifiableInteger::new);

  /**
   * Starts an unarmed (data-path) operation: parks under any active freeze unless a throw-mode
   * freeze registered a supplier — byte-for-byte the historical semantics.
   */
  public void startOperation() {
    startOperation(false, null);
  }

  /**
   * Starts an operation, with the schema-commit gate armed when {@code schemaArmed} is true.
   *
   * <p>A schema-armed entrant (a schema-carrying commit's apply, which enters here holding the
   * schema, index-manager, and storage write locks) must never park while an OPERATOR freeze is
   * active — parked, it would convert the freeze into a storage-wide read outage for the freeze's
   * whole (unbounded) duration. Two checkpoints inside this method enforce that:
   *
   * <ul>
   *   <li>the LOOP-TOP gate — evaluated on entry and on every wake (including the operator-arm
   *       cut's wake of an entrant parked behind a transient quiesce), before the depth increment
   *       and with the operations count already re-balanced, mirroring the throw-supplier
   *       discipline;</li>
   *   <li>the PARK-DECISION re-check — evaluated strictly AFTER {@code addThreadInWaitingList}
   *       returned and immediately before the park (the entrant ordering:
   *       enqueue-before-recheck). Together with the arm side's publish-kind-before-count and
   *       cut-after-both-increments orderings this closes the engage-during-enqueue race,
   *       including the cut firing before this entrant enqueued: either the entrant's node made
   *       the cut list (the cut unparks it) or the entrant enqueued after the cut — and then this
   *       re-check, reading the counters after the enqueue, sees the operator freeze whose cut it
   *       missed.</li>
   * </ul>
   *
   * <p>Unarmed (data) entrants keep the historical semantics byte-for-byte: they park under any
   * freeze unless a throw-mode freeze registered a supplier.
   *
   * @param schemaArmed whether this entrant is a schema-carrying commit's apply.
   * @param schemaGate  the shared gate-exception factory, non-null when {@code schemaArmed}.
   */
  public void startOperation(final boolean schemaArmed,
      @Nullable final Supplier<? extends BaseException> schemaGate) {
    if (schemaArmed) {
      // Fail a miswired armed entrant loudly at the API boundary: without this, a null factory
      // would surface only as a bare NPE at gate-evaluation time — inside the freezer, under an
      // active operator freeze, the worst possible diagnosis point.
      Objects.requireNonNull(schemaGate,
          "a schema-armed freezer entrant must supply the gate-exception factory");
    }
    final var operationDepth = this.operationDepth.get();
    if (operationDepth.value == 0) {
      operationsCount.increment();

      while (freezeRequests.get() > 0) {
        assert freezeRequests.get() >= 0;

        operationsCount.decrement();

        // Checkpoint: the schema-armed loop-top gate. Throws BEFORE the depth increment with the
        // operations count re-balanced (decremented just above), so nothing leaks into the
        // freezer's accounting — the same discipline as the throw-supplier path below.
        if (schemaArmed && operatorFreezeRequests.get() > 0) {
          throw schemaGate.get();
        }

        // Pinned contract (user-ruled 2026-07-23): an entrant that was already PARKED
        // under an earlier park-mode freeze and is woken by a throw-mode operator freeze's
        // arm cut re-evaluates here and THROWS the registered supplier's exception
        // deterministically — it does not park through to completion after the release, as the
        // pre-gate code happened to do. Intentional, not a regression: throw-mode means the
        // operator explicitly requested loud failure for write operations, and LockSupport's
        // spurious-wakeup spec never guaranteed park-through anyway.
        throwFreezeExceptionIfNeeded();

        final var thread = Thread.currentThread();

        operationsWaitingList.addThreadInWaitingList(thread);

        // Checkpoint: the schema-armed park-decision re-check, strictly AFTER the enqueue
        // returned (enqueue-before-recheck) and immediately before the park. See the method Javadoc
        // for why this ordering closes the engage-during-enqueue race. Throwing here
        // deliberately leaves this entrant's just-enqueued node linked: the next cut unparks a
        // thread that never parked on it (a benign stray permit — every park site re-checks in
        // a loop) and retains the node only until that cut. Do NOT "fix" that residue by moving
        // this gate above the enqueue — the enqueue-before-recheck ordering is exactly what
        // closes the race.
        if (schemaArmed && operatorFreezeRequests.get() > 0) {
          throw schemaGate.get();
        }

        if (freezeRequests.get() > 0) {
          LockSupport.park(this);
        }

        operationsCount.increment();
      }
    }

    assert freezeRequests.get() >= 0;

    operationDepth.increment();
  }

  public void endOperation() {
    final var operationDepth = this.operationDepth.get();
    if (operationDepth.value <= 0) {
      throw new IllegalStateException("Invalid operation depth " + operationDepth.value);
    } else {
      operationDepth.value--;
    }

    if (operationDepth.value == 0) {
      operationsCount.decrement();
    }
  }

  /**
   * Registers a freeze of the given kind and drains the in-flight operations.
   *
   * <p>OPERATOR arm orderings (load-bearing for the gate): the kind counter is incremented
   * BEFORE {@code freezeRequests} (publish kind-before-count: an entrant that observes the count
   * also observes the kind), and the cut-and-unpark runs strictly AFTER both increments (an
   * entrant that enqueued before the cut is woken by it; one that enqueued after reads the
   * already-published counters in its park-decision re-check). Nesting is tolerated by
   * construction: the operator freeze's own body registers a nested TRANSIENT quiesce (the synch
   * flush), taking {@code freezeRequests} 1&rarr;2&rarr;1 while the kind counter stays 1.
   */
  public long freezeOperations(final FreezeKind kind,
      @Nullable Supplier<? extends BaseException> throwException) {
    final var id = freezeIdGen.incrementAndGet();

    if (kind == FreezeKind.OPERATOR) {
      // Arm ordering, part 1: publish the kind BEFORE the count.
      operatorFreezeRequests.incrementAndGet();
      operatorFreezeIds.add(id);
    }

    freezeRequests.incrementAndGet();

    if (throwException != null) {
      freezeParametersIdMap.put(id, new FreezeParameters(throwException));
    }

    if (kind == FreezeKind.OPERATOR) {
      // Arm ordering, part 2: the operator-arm cut-and-unpark runs strictly AFTER both
      // increments, so every woken entrant re-evaluates against fully published state. The cut
      // detaches a FINITE generation: a woken DATA entrant re-enqueues a fresh node before it
      // re-parks (so no wakeup is ever lost to the eventual release-side cut), and the detached
      // chain never grows under the unpark loop — which is why the arm cuts instead of walking
      // the live list (a non-detaching walk chases the re-enqueueing herd and livelocks; see
      // OperationsFreezerLivenessTest). Operator registrations run concurrently with each other
      // and with releases, so cutWaitingList serializes cutters internally (its monitor — the
      // cut protocol is single-cutter-only; the unserialized two-cutter shape wedges a freezer
      // thread on a link latch forever, the liveness defect the same test pins). The wake
      // is a deliberate, bounded thundering herd: parked DATA entrants wake, re-evaluate,
      // and re-park through the loop (none is admitted — freezeRequests is positive) — unless
      // THIS freeze registered a throw supplier, in which case the woken data entrants throw it
      // deterministically at the loop's supplier check (the pinned contract, user-ruled
      // 2026-07-23; see the comment there); a parked
      // SCHEMA-armed entrant wakes and throws at the loop-top gate instead of staying parked for
      // the operator freeze's whole duration. At most the concurrently parked committers wake,
      // once per operator-freeze engagement, one loop iteration each.
      cutAndUnparkWaiters();
    }

    while (operationsCount.sum() > 0) {
      Thread.yield();
    }

    return id;
  }

  /**
   * Whether an OPERATOR freeze is currently registered — the schema-commit gate's single kind
   * probe (the storage-level probe, the write-lock abort predicate, and the two in-freezer
   * checkpoints all read this counter).
   */
  public boolean isOperatorFreezeActive() {
    return operatorFreezeRequests.get() > 0;
  }

  /**
   * Releases a freeze. The kind is resolved from the registration record: a retained id maps to
   * OPERATOR exactly when its registration recorded it as such (removing the record), and to
   * TRANSIENT otherwise; the {@code -1} sentinel — the guarded fallback for a release with no
   * matching retained id (the storage-level {@code release()} normally passes the real id its
   * paired {@code freeze()} retained) — maps explicitly to the OPERATOR decrement.
   *
   * <p>Retract ordering: the count is decremented BEFORE the kind counter, the mirror of the
   * arm's publish-kind-before-count — an entrant that still observes the count also still
   * observes the kind. Both decrements are CAS-floor (decrement-only-if-positive): a buggy double
   * release can never drive a counter negative and silently disarm the gate, and a concurrent
   * legitimate freeze's registration can never be wiped by a corrective write (the
   * decrement-then-set-zero shape would lose it). Underflow attempts are LOGGED, never thrown —
   * these decrements are reachable from transient-release finallys (the synch and backup
   * bodies), where a throw would mask the frozen body's primary exception. No lockstep
   * cross-counter assert exists: the arm/retract orderings create legal transient windows where
   * the counters disagree, so such an assert would fire spuriously on a healthy system.
   */
  public void releaseOperations(final long id) {
    final FreezeKind kind;
    if (id == -1) {
      // The guarded fallback sentinel: an operator release without a matching retained id.
      kind = FreezeKind.OPERATOR;
    } else {
      kind = operatorFreezeIds.remove(id) ? FreezeKind.OPERATOR : FreezeKind.TRANSIENT_QUIESCE;
    }

    if (id >= 0) {
      freezeParametersIdMap.remove(id);
    }

    final var freezeParametersMap =
        new Long2ObjectOpenHashMap<>(freezeParametersIdMap);

    // Retract ordering, part 1: the count before the kind.
    final var requests = decrementToFloor(freezeRequests);
    if (requests < 0) {
      LogManager.instance()
          .error(this,
              "Freeze release without a matching freeze (id %d): the freeze-request counter was"
                  + " already 0 and stays 0; a double release is a caller bug but must not disarm"
                  + " the freezer",
              null, id);
    }
    if (kind == FreezeKind.OPERATOR) {
      // Retract ordering, part 2: the kind counter after the count.
      if (decrementToFloor(operatorFreezeRequests) < 0) {
        LogManager.instance()
            .error(this,
                "Operator-freeze release without a matching operator freeze (id %d): the"
                    + " operator-kind counter was already 0 and stays 0; the schema-commit gate"
                    + " remains armed for genuine freezes",
                null, id);
      }
    }

    if (requests == 0) {
      var idsIterator = freezeParametersMap.keySet().iterator();

      while (idsIterator.hasNext()) {
        final var freezeId = idsIterator.nextLong();
        freezeParametersIdMap.remove(freezeId);
      }

      cutAndUnparkWaiters();
    }
  }

  /**
   * Detaches the current waiting-list generation and unparks every waiter in it. The WHEN of
   * each call is load-bearing and documented at the call sites (the operator arm's
   * cut-after-both-increments ordering; the release side's freeze-request 1&rarr;0 transition);
   * the detach-walk-unpark mechanics are identical.
   */
  private void cutAndUnparkWaiters() {
    var node = operationsWaitingList.cutWaitingList();
    while (node != null) {
      LockSupport.unpark(node.item);
      node = node.next;
    }
  }

  /**
   * CAS-floor decrement: decrements only while the counter is positive, in a single atomic RMW
   * per attempt. Returns the post-decrement value, or {@code -1} when the counter was already at
   * the floor (an underflow attempt, left untouched). Never the decrement-then-correct shape,
   * whose corrective {@code set(0)} could wipe a concurrent legitimate increment.
   */
  private static int decrementToFloor(final AtomicInteger counter) {
    while (true) {
      final var current = counter.get();
      if (current <= 0) {
        return -1;
      }
      if (counter.compareAndSet(current, current - 1)) {
        return current - 1;
      }
    }
  }

  private void throwFreezeExceptionIfNeeded() {
    for (var freezeParameters : freezeParametersIdMap.values()) {
      throw freezeParameters.throwException.get();
    }
  }

  private record FreezeParameters(@Nonnull Supplier<? extends BaseException> throwException) {

  }
}
