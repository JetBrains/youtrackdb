package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.IdentityHashMap;
import javax.annotation.Nullable;

/** Tracks recovery requirements transferred from dirty pages into page-write attempts. */
final class PageWriteTracker {

  private final IdentityHashMap<CachePointer, PageRequirement> requirements =
      new IdentityHashMap<>();

  /**
   * Transfers a dirty-page requirement to a copy attempt.
   *
   * <p>The pointer identifies one live page incarnation. A later copy of the same pointer
   * contains every earlier change, so its successful write may satisfy the retained minimum.
   */
  @Nullable PageWriteAttempt pageCopyStarted(
      final CachePointer pointer, @Nullable final LogSequenceNumber dirtyLsn) {
    var requirement = requirements.get(pointer);
    if (requirement == null) {
      if (dirtyLsn == null) {
        return null;
      }
      requirement = new PageRequirement(dirtyLsn);
      requirements.put(pointer, requirement);
    } else {
      requirement.lsn = LogSequenceNumberUtils.earliest(requirement.lsn, dirtyLsn);
    }

    requirement.generation++;
    return new PageWriteAttempt(pointer, requirement.generation);
  }

  /** Releases the requirement only when this attempt is the newest copy of the page. */
  void pageWriteCompleted(@Nullable final PageWriteAttempt attempt) {
    if (attempt == null) {
      return;
    }

    final var requirement = requirements.get(attempt.pointer);
    if (requirement != null && requirement.generation == attempt.generation) {
      requirements.remove(attempt.pointer);
    }
  }

  /** Drops requirements for file contents removed by truncate, close, or deletion. */
  void discardPages(final long fileId, final long minPageIndex) {
    requirements
        .keySet()
        .removeIf(
            pointer -> pointer.getFileId() == fileId && pointer.getPageIndex() >= minPageIndex);
  }

  /** Returns the earliest copied or failed page requirement. */
  @Nullable LogSequenceNumber earliestNotWrittenLsn() {
    LogSequenceNumber earliest = null;
    for (final var requirement : requirements.values()) {
      earliest = LogSequenceNumberUtils.earliest(earliest, requirement.lsn);
    }
    return earliest;
  }

  record PageWriteAttempt(CachePointer pointer, long generation) {
  }

  private static final class PageRequirement {

    private LogSequenceNumber lsn;
    private long generation;

    private PageRequirement(final LogSequenceNumber lsn) {
      this.lsn = lsn;
    }
  }
}
