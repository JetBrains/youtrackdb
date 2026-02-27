package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import org.junit.Test;

/**
 * Unit tests for {@link MemoryFile#clear()} verifying that unreleased cache entries
 * produce a warning instead of throwing {@code IllegalStateException}.
 *
 * <p>This is a regression test for YTDB-527: before the fix, {@code clear()} threw when
 * it found entries with {@code usagesCount > 0}, causing cascading failures during
 * database drop.
 */
public class MemoryFileClearTest {

  /**
   * Verifies that {@code clear()} logs a warning (and does not throw) when cache entries
   * still have a positive usage count at the time of deletion.
   *
   * <p>A positive {@code usagesCount} indicates the entry was acquired via
   * {@code DirectMemoryOnlyDiskCache.doLoad()} but never released. The old code threw
   * {@code IllegalStateException} here; the fix converts it to a warning so the
   * storage deletion can proceed.
   */
  @Test
  public void testClearWithUnreleasedEntriesLogsWarningInsteadOfThrowing() {
    var readCache = mock(ReadCache.class);
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 1);

    // Add a page — this creates a CacheEntry with cachePointer referrer incremented
    var entry = file.addNewPage(readCache);

    // Simulate an unreleased cache entry: incrementUsages() without a matching
    // decrementUsages(). This is what happens when a page is loaded (doLoad) but the
    // caller does not call releaseFromRead/releaseFromWrite before the storage is deleted.
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (entry) {
      entry.incrementUsages();
    }

    // clear() must NOT throw — it should log a warning and proceed.
    // Before the YTDB-527 fix this line threw IllegalStateException.
    file.clear();
  }
}
