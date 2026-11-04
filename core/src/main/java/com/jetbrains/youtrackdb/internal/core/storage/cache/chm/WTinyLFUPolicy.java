package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageCacheCounters;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Window TinyLFU eviction policy https://arxiv.org/pdf/1512.00727.pdf.
 */
public final class WTinyLFUPolicy {

  private static final int EDEN_PERCENT = 20;
  private static final int PROBATIONARY_PERCENT = 20;

  private volatile int maxSize;
  private final ConcurrentLongIntHashMap<CacheEntry> data;
  private final Admittor admittor;

  private final AtomicInteger cacheSize;
  private final PageCacheCounters.Eviction counters;

  private final LRUList eden = new LRUList();
  private final LRUList probation = new LRUList();
  private final LRUList protection = new LRUList();

  private int maxEdenSize;
  private int maxProtectedSize;
  private int maxSecondLevelSize;

  WTinyLFUPolicy(
      final ConcurrentLongIntHashMap<CacheEntry> data,
      final Admittor admittor,
      final AtomicInteger cacheSize) {
    this(data, admittor, cacheSize,
        new PageCacheCounters.Eviction());
  }

  WTinyLFUPolicy(final ConcurrentLongIntHashMap<CacheEntry> data, final Admittor admittor,
      final AtomicInteger cacheSize,
      final PageCacheCounters.Eviction counters) {
    this.counters = counters;
    this.data = data;
    this.admittor = admittor;
    this.cacheSize = cacheSize;
  }

  public void setMaxSize(final int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("Maximum cache size must be positive");
    }

    this.maxSize = maxSize;
    calculateMaxSizes();

    admittor.ensureCapacity(maxSize);
    while (protection.size() > maxProtectedSize) {
      probation.moveToTheTail(protection.poll());
    }
    while (probation.size() + protection.size() > maxSecondLevelSize) {
      eden.moveToTheTail(probation.poll());
    }
    purgeEden();
  }

  public int getMaxSize() {
    return maxSize;
  }

  public void onAccess(CacheEntry cacheEntry) {
    admittor.increment(
        ConcurrentLongIntHashMap.hashForFrequencySketch(
            cacheEntry.getFileId(), cacheEntry.getPageIndex()));

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

    assert protection.size() <= maxProtectedSize;
    assert probation.size() + protection.size() <= maxSecondLevelSize;
  }

  void onAdd(final CacheEntry cacheEntry) {
    admittor.increment(
        ConcurrentLongIntHashMap.hashForFrequencySketch(
            cacheEntry.getFileId(), cacheEntry.getPageIndex()));

    if (cacheEntry.isAlive()) {
      assert !eden.contains(cacheEntry);
      assert !probation.contains(cacheEntry);
      assert !protection.contains(cacheEntry);

      eden.moveToTheTail(cacheEntry);

      purgeEden();
    }

    assert protection.size() <= maxProtectedSize;
    assert probation.size() + protection.size() <= maxSecondLevelSize;
  }

  private void purgeEden() {
    // A pin can defeat removal. Visit a bounded number of candidates, then leave excess cached.
    int attempts = eden.size() + probation.size();
    while (eden.size() > maxEdenSize && attempts-- > 0) {
      final var candidate = eden.poll();
      assert candidate != null;

      if (probation.size() + protection.size() < maxSecondLevelSize) {
        probation.moveToTheTail(candidate);
      } else {
        final var victim = probation.peek();

        final var candidateKeyHashCode =
            ConcurrentLongIntHashMap.hashForFrequencySketch(
                candidate.getFileId(), candidate.getPageIndex());
        final var victimKeyHashCode =
            ConcurrentLongIntHashMap.hashForFrequencySketch(
                victim.getFileId(), victim.getPageIndex());

        final var candidateFrequency = admittor.frequency(candidateKeyHashCode);
        final var victimFrequency = admittor.frequency(victimKeyHashCode);

        if (candidateFrequency >= victimFrequency) {
          probation.poll();
          probation.moveToTheTail(candidate);

          if (victim.freeze()) {
            final var removed =
                data.remove(victim.getFileId(), victim.getPageIndex(), victim);
            victim.makeDead();

            if (removed) {
              cacheSize.decrementAndGet();
            }

            releaseCacheReference(victim);
          } else {
            eden.moveToTheTail(victim);
          }
        } else {
          if (candidate.freeze()) {
            final var removed =
                data.remove(candidate.getFileId(), candidate.getPageIndex(), candidate);
            candidate.makeDead();

            if (removed) {
              cacheSize.decrementAndGet();
            }

            releaseCacheReference(candidate);
          } else {
            eden.moveToTheTail(candidate);
          }
        }
      }
    }

    if (eden.size() > maxEdenSize) {
      counters.recordEvictionStop();
      // Measure the largest excess eviction leaves when it stops, not queued-add drain lag.
      counters.recordExcess(eden.size() + probation.size() + protection.size() - (long) maxSize);
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
    releaseCacheReference(cacheEntry);
  }

  /**
   * Releases the cache reference without a frame lock. Pins prevent eviction of live users.
   * The frame pool locks the frame on final return. Never hold that lock during reference drop.
   */
  private static void releaseCacheReference(CacheEntry entry) {
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
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
        && data.size() == cacheSize.get();
  }

  void assertConsistency() {
    var allEntries = new ArrayList<CacheEntry>();
    data.forEachValue(allEntries::add);

    for (final var cacheEntry : allEntries) {
      assert eden.contains(cacheEntry)
          || protection.contains(cacheEntry)
          || probation.contains(cacheEntry);
    }

    var counter = 0;
    for (final var cacheEntry : eden) {
      assert data.get(cacheEntry.getFileId(), cacheEntry.getPageIndex()) == cacheEntry;
      counter++;
    }

    for (final var cacheEntry : probation) {
      assert data.get(cacheEntry.getFileId(), cacheEntry.getPageIndex()) == cacheEntry;
      counter++;
    }

    for (final var cacheEntry : protection) {
      assert data.get(cacheEntry.getFileId(), cacheEntry.getPageIndex()) == cacheEntry;
      counter++;
    }

    assert counter == data.size();
  }
}
