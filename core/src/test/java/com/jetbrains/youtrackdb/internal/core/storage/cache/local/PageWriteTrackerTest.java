package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import org.junit.Test;

/** Tests success-based recovery protection for copied and failed page writes. */
public class PageWriteTrackerTest {

  /** A failed copy remains protected until a later copy of the same page succeeds. */
  @Test
  public void successfulRetryReleasesFailedPageRequirement() {
    final var tracker = new PageWriteTracker();
    final var pointer = pointer(7, 1);
    final var lsn = new LogSequenceNumber(3, 30);

    tracker.pageCopyStarted(pointer, lsn);
    final var retry = tracker.pageCopyStarted(pointer, null);

    assertEquals(lsn, tracker.earliestNotWrittenLsn());
    tracker.pageWriteCompleted(retry);
    assertNull(tracker.earliestNotWrittenLsn());
  }

  /** An older completion cannot clear protection transferred to a newer page copy. */
  @Test
  public void olderAttemptCannotReleaseNewerAttempt() {
    final var tracker = new PageWriteTracker();
    final var pointer = pointer(7, 1);
    final var lsn = new LogSequenceNumber(4, 20);

    final var first = tracker.pageCopyStarted(pointer, lsn);
    final var second = tracker.pageCopyStarted(pointer, null);
    tracker.pageWriteCompleted(first);

    assertEquals(lsn, tracker.earliestNotWrittenLsn());
    tracker.pageWriteCompleted(second);
    assertNull(tracker.earliestNotWrittenLsn());
  }

  /** Out-of-order registration preserves the smallest recovery position. */
  @Test
  public void smallerPositionRegisteredLaterRemainsProtected() {
    final var tracker = new PageWriteTracker();
    final var pointer = pointer(7, 1);
    final var later = new LogSequenceNumber(5, 40);
    final var earlier = new LogSequenceNumber(4, 10);

    tracker.pageCopyStarted(pointer, later);
    tracker.pageCopyStarted(pointer, earlier);

    assertEquals(earlier, tracker.earliestNotWrittenLsn());
  }

  /** Two pointer identities with equal numeric coordinates remain separate incarnations. */
  @Test
  public void reusedFileCoordinatesDoNotShareProtection() {
    final var tracker = new PageWriteTracker();
    final var oldPointer = pointer(7, 1);
    final var newPointer = pointer(7, 1);
    final var oldLsn = new LogSequenceNumber(3, 10);
    final var newLsn = new LogSequenceNumber(6, 10);

    tracker.pageCopyStarted(oldPointer, oldLsn);
    final var newAttempt = tracker.pageCopyStarted(newPointer, newLsn);
    tracker.pageWriteCompleted(newAttempt);

    assertEquals(oldLsn, tracker.earliestNotWrittenLsn());
    tracker.discardPages(7, 0);
    assertNull(tracker.earliestNotWrittenLsn());
  }

  private static CachePointer pointer(final long fileId, final long pageIndex) {
    final var pointer = mock(CachePointer.class);
    when(pointer.getFileId()).thenReturn(fileId);
    when(pointer.getPageIndex()).thenReturn((int) pageIndex);
    return pointer;
  }
}
