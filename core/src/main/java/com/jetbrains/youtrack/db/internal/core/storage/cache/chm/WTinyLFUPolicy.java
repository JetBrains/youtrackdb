package com.jetbrains.youtrack.db.internal.core.storage.cache.chm;

import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Window TinyLFU eviction policy https://arxiv.org/pdf/1512.00727.pdf.
 */
public final class WTinyLFUPolicy {

  private static final int EDEN_PERCENT = 20;
  private static final int PROBATIONARY_PERCENT = 20;

  private volatile int maxSize;
  private final ConcurrentHashMap<PageKey, CacheEntry> data;
  private final Admittor admittor;

  private final AtomicInteger cacheSize;

  private final LRUList eden = new LRUList();
  private final LRUList probation = new LRUList();
  private final LRUList protection = new LRUList();

  private int maxEdenSize;
  private int maxProtectedSize;
  private int maxSecondLevelSize;

  WTinyLFUPolicy(
      final ConcurrentHashMap<PageKey, CacheEntry> data,
      final Admittor admittor,
      final AtomicInteger cacheSize) {
    this.data = data;
    this.admittor = admittor;
    this.cacheSize = cacheSize;
  }

  public void setMaxSize(final int maxSize) {
    if (eden.size() + protection.size() + probation.size() > maxSize) {
      throw new IllegalStateException(
          "Can set maximum cache size to "
              + maxSize
              + " because current cache size is bigger than requested");
    }

    this.maxSize = maxSize;
    calculateMaxSizes();

    admittor.ensureCapacity(maxSize);
  }

  public int getMaxSize() {
    return maxSize;
  }

  public void onAccess(CacheEntry cacheEntry) {
    admittor.increment(cacheEntry.getPageKey().hashCode());

    if (!cacheEntry.isDead()) {
      if (probation.contains(cacheEntry)) {
        probation.remove(cacheEntry);
        protection.moveToTheTail(cacheEntry);

        if (protection.size() > maxProtectedSize) {
          cacheEntry = protection.poll();

          probation.moveToTheTail(cacheEntry);
        }
      } else if (protection.contains(cacheEntry)) {
        protection.moveToTheTail(cacheEntry);
      } else if (eden.contains(cacheEntry)) {
        eden.moveToTheTail(cacheEntry);
      }
    }

    assert eden.size() <= maxEdenSize;
    assert protection.size() <= maxProtectedSize;
    assert probation.size() + protection.size() <= maxSecondLevelSize;
  }

  void onAdd(final CacheEntry cacheEntry) {
    admittor.increment(cacheEntry.getPageKey().hashCode());

    if (cacheEntry.isAlive()) {
      assert !eden.contains(cacheEntry);
      assert !probation.contains(cacheEntry);
      assert !protection.contains(cacheEntry);

      eden.moveToTheTail(cacheEntry);

      purgeEden();
    }

    assert eden.size() <= maxEdenSize;
    assert protection.size() <= maxProtectedSize;
    assert probation.size() + protection.size() <= maxSecondLevelSize;
  }

  private void purgeEden() {
    while (eden.size() > maxEdenSize) {
      final var candidate = eden.poll();
      assert candidate != null;

      if (probation.size() + protection.size() < maxSecondLevelSize) {
        probation.moveToTheTail(candidate);
      } else {
        final var victim = probation.peek();

        final var candidateKeyHashCode = candidate.getPageKey().hashCode();
        final var victimKeyHashCode = victim.getPageKey().hashCode();

        final var candidateFrequency = admittor.frequency(candidateKeyHashCode);
        final var victimFrequency = admittor.frequency(victimKeyHashCode);

        if (candidateFrequency >= victimFrequency) {
          probation.poll();
          probation.moveToTheTail(candidate);

          if (victim.freeze()) {
            final var removed = data.remove(victim.getPageKey(), victim);
            victim.makeDead();

            if (removed) {
              cacheSize.decrementAndGet();
            }

            final var pointer = victim.getCachePointer();

            pointer.decrementReadersReferrer();
            victim.clearCachePointer();
          } else {
            eden.moveToTheTail(victim);
          }
        } else {
          if (candidate.freeze()) {
            final var removed = data.remove(candidate.getPageKey(), candidate);
            candidate.makeDead();

            if (removed) {
              cacheSize.decrementAndGet();
            }

            final var pointer = candidate.getCachePointer();

            pointer.decrementReadersReferrer();
            candidate.clearCachePointer();
          } else {
            eden.moveToTheTail(candidate);
          }
        }
      }
    }

    assert protection.size() <= maxProtectedSize;
  }

  void onRemove(final CacheEntry cacheEntry) {
    assert cacheEntry.isFrozen();

    if (probation.contains(cacheEntry)) {
      probation.remove(cacheEntry);
    } else if (protection.contains(cacheEntry)) {
      protection.remove(cacheEntry);
    } else if (eden.contains(cacheEntry)) {
      eden.remove(cacheEntry);
    }

    cacheEntry.makeDead();

    final var cachePointer = cacheEntry.getCachePointer();
    cachePointer.decrementReadersReferrer();
    cacheEntry.clearCachePointer();
  }

  private void calculateMaxSizes() {
    maxEdenSize = maxSize * EDEN_PERCENT / 100;
    maxProtectedSize = maxSize - maxEdenSize - (maxSize - maxEdenSize) * PROBATIONARY_PERCENT / 100;
    maxSecondLevelSize = maxSize - maxEdenSize;
  }

  Iterator<CacheEntry> eden() {
    return eden.iterator();
  }

  Iterator<CacheEntry> protection() {
    return protection.iterator();
  }

  Iterator<CacheEntry> probation() {
    return probation.iterator();
  }

  void assertSize() {
    assert eden.size() + probation.size() + protection.size() == cacheSize.get()
        && data.size() == cacheSize.get()
        && cacheSize.get() <= maxSize;
  }

  void assertConsistency() {
    for (final var cacheEntry : data.values()) {
      assert eden.contains(cacheEntry)
          || protection.contains(cacheEntry)
          || probation.contains(cacheEntry);
    }

    var counter = 0;
    for (final var cacheEntry : eden) {
      assert data.get(cacheEntry.getPageKey()) == cacheEntry;
      counter++;
    }

    for (final var cacheEntry : probation) {
      assert data.get(cacheEntry.getPageKey()) == cacheEntry;
      counter++;
    }

    for (final var cacheEntry : protection) {
      assert data.get(cacheEntry.getPageKey()) == cacheEntry;
      counter++;
    }

    assert counter == data.size();
  }
}
