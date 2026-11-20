package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;

/** Entry injection and real pin-state checks for a paused, single-threaded cache fixture. */
public final class PageSetEntryAssertions {
  private PageSetEntryAssertions() {
  }

  @SuppressWarnings("unchecked")
  public static void replace(LockFreeReadCache cache, CacheEntry entry) throws Exception {
    var field = LockFreeReadCache.class.getDeclaredField("data");
    field.setAccessible(true);
    var map = (ConcurrentLongIntHashMap<CacheEntry>) field.get(cache);
    var flush = LockFreeReadCache.class.getDeclaredMethod("flushCurrentThreadReadBatch");
    flush.setAccessible(true);
    flush.invoke(cache);
    var drain = LockFreeReadCache.class.getDeclaredMethod("emptyBuffers");
    drain.setAccessible(true);
    drain.invoke(cache);
    var policyField = LockFreeReadCache.class.getDeclaredField("policy");
    policyField.setAccessible(true);
    var policy = (WTinyLFUPolicy) policyField.get(cache);
    map.compute(entry.getFileId(), entry.getPageIndex(), (file, page, old) -> {
      if (old.getContainer() != null) {
        old.getContainer().remove(old);
      }
      entry.setContainer(null);
      entry.setNext(null);
      entry.setPrev(null);
      policy.onAdd(entry);
      return entry;
    });
  }

  public static void assertPins(CacheEntry entry, int expected) throws Exception {
    // usagesCount is eviction-policy bookkeeping. state is the actual pin counter.
    var field = CacheEntryImpl.class.getDeclaredField("state");
    field.setAccessible(true);
    org.junit.Assert.assertEquals(expected, field.getInt(entry));
  }
}
