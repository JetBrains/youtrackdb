package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.mockito.Mockito;

/** Tests merging shared dirty-page requirements into executor-local indexes. */
public class WOWCacheDirtyPageMergeTest {

  /** A shared requirement is added when no local requirement exists. */
  @Test
  public void absentLocalRequirementAddsSharedRequirement() throws Exception {
    final var shared = new LogSequenceNumber(3, 30);

    assertMergedRequirement(null, shared, shared, false);
  }

  /** An earlier shared requirement replaces the local requirement in every index. */
  @Test
  public void earlierSharedRequirementReplacesLocalRequirement() throws Exception {
    final var local = new LogSequenceNumber(4, 40);
    final var shared = new LogSequenceNumber(3, 30);

    assertMergedRequirement(local, shared, shared, false);
  }

  /** An equal shared requirement retains the local reference in every index. */
  @Test
  public void equalSharedRequirementRetainsLocalReference() throws Exception {
    final var local = new LogSequenceNumber(3, 30);
    final var shared = new LogSequenceNumber(3, 30);

    assertMergedRequirement(local, shared, local, false);
  }

  /** A later shared requirement retains the earlier local requirement in every index. */
  @Test
  public void laterSharedRequirementRetainsLocalRequirement() throws Exception {
    final var local = new LogSequenceNumber(3, 30);
    final var shared = new LogSequenceNumber(4, 40);

    assertMergedRequirement(local, shared, local, true);
  }

  private static void assertMergedRequirement(
      final LogSequenceNumber local,
      final LogSequenceNumber shared,
      final LogSequenceNumber expected,
      final boolean sharedRemainsDirty) throws Exception {
    final var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    final var pageKey = new PageKey(7, 1);
    final var dirtyPages = new ConcurrentHashMap<PageKey, LogSequenceNumber>();
    final var localDirtyPages = new HashMap<PageKey, LogSequenceNumber>();
    final var localDirtyPagesBySegment = new TreeMap<Long, TreeSet<PageKey>>();
    final var localDirtyPageCountsByLsn = new TreeMap<LogSequenceNumber, Integer>();

    dirtyPages.put(pageKey, shared);
    if (local != null) {
      localDirtyPages.put(pageKey, local);
      localDirtyPagesBySegment.put(local.getSegment(), new TreeSet<>(java.util.Set.of(pageKey)));
      localDirtyPageCountsByLsn.put(local, 1);
    }

    setField(cache, "dirtyPages", dirtyPages);
    setField(cache, "localDirtyPages", localDirtyPages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);
    setField(cache, "localDirtyPageCountsByLsn", localDirtyPageCountsByLsn);

    final Method convert = WOWCache.class.getDeclaredMethod("convertSharedDirtyPagesToLocal");
    convert.setAccessible(true);
    convert.invoke(cache);

    if (sharedRemainsDirty) {
      assertEquals(Map.of(pageKey, shared), dirtyPages);
    } else {
      assertTrue("merged shared dirty entry must be consumed", dirtyPages.isEmpty());
    }
    assertEquals(Map.of(pageKey, expected), localDirtyPages);
    assertSame(expected, localDirtyPages.get(pageKey));
    assertEquals(java.util.Set.of(expected.getSegment()), localDirtyPagesBySegment.keySet());
    assertEquals(java.util.Set.of(pageKey), localDirtyPagesBySegment.get(expected.getSegment()));
    assertEquals(1, localDirtyPageCountsByLsn.size());
    assertSame(expected, localDirtyPageCountsByLsn.firstKey());
    assertEquals(Integer.valueOf(1), localDirtyPageCountsByLsn.get(expected));
  }

  private static void setField(final WOWCache cache, final String name, final Object value)
      throws Exception {
    final Field field = WOWCache.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(cache, value);
  }
}
