package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;

/** Test bridge for inspecting the package-private backup snapshot from commit tests. */
public final class PageSetTrackingAssertions {
  private PageSetTrackingAssertions() {
  }

  /** Starts an empty active generation while the single-threaded fixture has no writers. */
  public static void beginBackup(ChangedPageTracker tracker) {
    tracker.beginBackup();
  }

  /** Reads both dirty tables while background flushing and fixture writers are paused. */
  @SuppressWarnings("unchecked")
  public static Map<Long, LogSequenceNumber> dirtyPages(WOWCache cache, int file)
      throws ReflectiveOperationException {
    var result = new HashMap<Long, LogSequenceNumber>();
    // These private tables have no production inspection API. Keep reflection in this
    // test bridge rather than adding a cache API solely for equality assertions.
    for (String name : new String[] {"dirtyPages", "localDirtyPages"}) {
      var field = WOWCache.class.getDeclaredField(name);
      field.setAccessible(true);
      var pages = (Map<PageKey, LogSequenceNumber>) field.get(cache);
      pages.forEach((key, lsn) -> {
        if (key.fileId == file) {
          result.merge(key.pageIndex, lsn, (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
      });
    }
    return result;
  }

  /** Injects only the incremental backup mark, inside the real dirty-table update. */
  public static void failBackupMark(ChangedPageTracker tracker, int file, long page,
      Throwable failure, java.util.List<String> events) {
    org.mockito.Mockito.doAnswer(call -> {
      events.add("backup failure");
      throw failure;
    }).when(tracker).mark(file, page);
  }

  public static void assertPages(ChangedPageTracker tracker, int file, long... expected) {
    var actual = new ArrayList<Long>();
    tracker.saveOrderLock().lock();
    try {
      tracker.capture().active().forEachCandidate(file, actual::add);
    } finally {
      tracker.saveOrderLock().unlock();
    }
    var wanted = new ArrayList<Long>();
    for (long page : expected) {
      wanted.add(page);
    }
    Assert.assertEquals(wanted, actual);
  }
}
