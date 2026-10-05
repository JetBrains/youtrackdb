package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
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

  /** An earlier position in the same segment replaces the local page LSN. */
  @Test
  public void earlierSharedPositionInSameSegmentReplacesLocalRequirement() throws Exception {
    final var local = new LogSequenceNumber(3, 40);
    final var shared = new LogSequenceNumber(3, 30);

    assertMergedRequirement(local, shared, shared, false);
  }

  /** No dirty pages and no outstanding writes leave the boundary unset. */
  @Test
  public void emptyCacheHasNoDirtySegment() throws Exception {
    assertDirtySegment(null, null, null);
  }

  /** A shared dirty page alone protects its WAL segment after merging. */
  @Test
  public void onlyLocalDirtyPageProtectsItsSegment() throws Exception {
    assertDirtySegment(new LogSequenceNumber(4, 40), null, 4L);
  }

  /** A copied page alone protects its WAL segment until its write succeeds. */
  @Test
  public void onlyTrackedWriteProtectsItsSegment() throws Exception {
    assertDirtySegment(null, new LogSequenceNumber(5, 50), 5L);
  }

  /** An earlier copied page bounds a later local dirty page. */
  @Test
  public void trackerEarlierThanLocalDeterminesBoundary() throws Exception {
    assertDirtySegment(new LogSequenceNumber(4, 40), new LogSequenceNumber(3, 90), 3L);
  }

  /** An earlier local dirty page bounds a later copied page. */
  @Test
  public void localEarlierThanTrackerDeterminesBoundary() throws Exception {
    assertDirtySegment(new LogSequenceNumber(3, 90), new LogSequenceNumber(4, 10), 3L);
  }

  /** Different WAL positions in one segment still protect that same segment. */
  @Test
  public void equalSegmentsWithDifferentPositionsProtectSameBoundary() throws Exception {
    assertDirtySegment(new LogSequenceNumber(3, 90), new LogSequenceNumber(3, 10), 3L);
  }

  private static void assertDirtySegment(
      final LogSequenceNumber dirty,
      final LogSequenceNumber tracked,
      final Long expectedSegment) throws Exception {
    final var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    final var dirtyPages = new ConcurrentHashMap<PageKey, LogSequenceNumber>();
    if (dirty != null) {
      dirtyPages.put(new PageKey(7, 1), dirty);
    }
    setField(cache, "dirtyPages", dirtyPages);
    setField(cache, "localDirtyPages", new HashMap<PageKey, LogSequenceNumber>());
    setField(cache, "localDirtyPagesBySegment", new TreeMap<Long, TreeSet<PageKey>>());
    final var tracker = new PageWriteTracker();
    setField(cache, "pageWriteTracker", tracker);
    if (tracked != null) {
      tracker.pageCopyStarted(mock(CachePointer.class), tracked);
    }

    assertEquals(expectedSegment, cache.executeFindDirtySegment());
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

    dirtyPages.put(pageKey, shared);
    if (local != null) {
      localDirtyPages.put(pageKey, local);
      localDirtyPagesBySegment.put(local.getSegment(), new TreeSet<>(java.util.Set.of(pageKey)));
    }

    setField(cache, "dirtyPages", dirtyPages);
    setField(cache, "localDirtyPages", localDirtyPages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);
    setField(cache, "pageWriteTracker", new PageWriteTracker());

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
    assertEquals(Long.valueOf(expected.getSegment()), cache.executeFindDirtySegment());
  }

  private static void setField(final WOWCache cache, final String name, final Object value)
      throws Exception {
    final Field field = WOWCache.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(cache, value);
  }
}
