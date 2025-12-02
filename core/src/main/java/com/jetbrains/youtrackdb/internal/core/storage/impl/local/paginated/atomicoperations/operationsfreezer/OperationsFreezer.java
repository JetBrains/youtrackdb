package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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

  private final WaitingList operationsWaitingList = new WaitingList();

  private final AtomicLong freezeIdGen = new AtomicLong();
  private final ConcurrentMap<Long, FreezeParameters> freezeParametersIdMap =
      new ConcurrentHashMap<>();

  private final ThreadLocal<ModifiableInteger> operationDepth =
      ThreadLocal.withInitial(ModifiableInteger::new);

  public void startOperation() {
    final var operationDepth = this.operationDepth.get();
    if (operationDepth.value == 0) {
      operationsCount.increment();

      while (freezeRequests.get() > 0) {
        assert freezeRequests.get() >= 0;

        operationsCount.decrement();

        throwFreezeExceptionIfNeeded();

        final var thread = Thread.currentThread();

        operationsWaitingList.addThreadInWaitingList(thread);

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

  public long freezeOperations(@Nullable Supplier<? extends BaseException> throwException) {
    final var id = freezeIdGen.incrementAndGet();

    freezeRequests.incrementAndGet();

    if (throwException != null) {
      freezeParametersIdMap.put(id, new FreezeParameters(throwException));
    }

    while (operationsCount.sum() > 0) {
      Thread.yield();
    }

    return id;
  }

  public void releaseOperations(final long id) {
    if (id >= 0) {
      freezeParametersIdMap.remove(id);
    }

    final var freezeParametersMap =
        new Long2ObjectOpenHashMap<>(freezeParametersIdMap);
    final long requests = freezeRequests.decrementAndGet();

    if (requests == 0) {
      var idsIterator = freezeParametersMap.keySet().iterator();

      while (idsIterator.hasNext()) {
        final var freezeId = idsIterator.nextLong();
        freezeParametersIdMap.remove(freezeId);
      }

      var node = operationsWaitingList.cutWaitingList();

      while (node != null) {
        LockSupport.unpark(node.item);
        node = node.next;
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
