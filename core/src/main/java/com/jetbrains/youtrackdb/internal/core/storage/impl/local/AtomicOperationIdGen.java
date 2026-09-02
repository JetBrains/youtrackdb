package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import java.util.concurrent.atomic.AtomicLong;

/** Generates logical operation identifiers without permitting rewind or numeric wraparound. */
public final class AtomicOperationIdGen {

  private final AtomicLong idGen = new AtomicLong();

  public long nextId() {
    while (true) {
      final var current = idGen.get();
      if (current == Long.MAX_VALUE) {
        throw new IllegalStateException("Logical operation identifier space is exhausted");
      }
      if (idGen.compareAndSet(current, current + 1)) {
        return current + 1;
      }
    }
  }

  /** Advances the highest-issued identifier while rejecting a stale or negative floor. */
  public void advanceToAtLeast(final long highestIssued) {
    if (highestIssued < 0) {
      throw new IllegalArgumentException("Logical operation identifier must not be negative");
    }

    while (true) {
      final var current = idGen.get();
      if (highestIssued < current) {
        throw new IllegalStateException("Logical operation identifier cannot rewind");
      }
      if (highestIssued == current || idGen.compareAndSet(current, highestIssued)) {
        return;
      }
    }
  }

  /** Retained for source compatibility and applies the same monotonic floor semantics. */
  public void setStartId(final long id) {
    advanceToAtLeast(id);
  }

  public long getLastId() {
    return idGen.get();
  }
}
