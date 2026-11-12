package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/**
 * Retains the newest complete double write log copy until data writes satisfy durability.
 *
 * <p>Every operation requires flush-executor ownership, checked by the supplied owner check.
 * The check must recognize replacement executor workers without allowing concurrent access.
 * Checkpoint threads must never call this component. Every query also requires the owner check.
 * A close-time query must run as a task on the flush executor.
 * This state is independent of WAL and backup tracking.
 */
final class PageRetentionState {

  private final HashMap<PageKey, Retention> pages = new HashMap<>();
  private final BooleanSupplier ownerCheck;
  private long generation;

  PageRetentionState(final BooleanSupplier ownerCheck) {
    this.ownerCheck = Objects.requireNonNull(ownerCheck);
  }

  /**
   * Publishes protection after a successful append, before submission of any covered data write.
   * Call once per covered page. A failed append must not call this method. A null segment denotes
   * a no-operation DWL. Non-durable pages and no-operation appends create no record.
   */
  @Nullable WriteAttempt appendSucceeded(
      final CachePointer pointer, @Nullable final Long segment, final boolean durable) {
    checkOwner();
    if (!durable || segment == null) {
      return null;
    }
    // The generation identifies one write attempt.
    // Older completion or failure reports cannot resolve or release a newer record.
    final var attempt = new WriteAttempt(
        new PageKey(pointer.getFileId(), pointer.getPageIndex()), ++generation);
    pages.put(attempt.page(), new Retention(attempt, segment));
    return attempt;
  }

  /**
   * Completes only matching page coordinates and generation. Fsync-off writes release at once.
   * Report success only after the batch outcome is certain. An uncertain batch must instead pass
   * every attempt to {@link #writesFailed(Collection)} before any fsync-off release.
   */
  void writeSucceeded(@Nullable final WriteAttempt attempt, final boolean fsyncEnabled) {
    checkOwner();
    final var retention = matching(attempt);
    if (retention == null || retention.state == State.UNRESOLVED) {
      return;
    }
    if (fsyncEnabled) {
      retention.state = State.SYNC_PENDING;
    } else {
      pages.remove(attempt.page());
    }
  }

  /**
   * Marks every affected attempt unresolved. Use the whole batch when outcomes are uncertain,
   * including attempts that reported success before the batch failure became known.
   */
  void writesFailed(final Collection<WriteAttempt> attempts) {
    checkOwner();
    for (final var attempt : attempts) {
      final var retention = matching(attempt);
      if (retention != null) {
        retention.state = State.UNRESOLVED;
      }
    }
  }

  /**
   * Runs a complete data-file synchronization on the flush executor and releases covered writes.
   * The action must synchronize all data files. Failure makes covered pending writes unresolved.
   * A later synchronization cannot release them. Only successful writes already pending at entry
   * qualify. A checkpoint cannot acknowledge through this API.
   */
  void synchronizeOnFlushExecutor(final Synchronization synchronization)
      throws IOException, InterruptedException {
    synchronizeOnFlushExecutor(null, synchronization);
  }

  /**
   * Synchronizes only external file ids as returned by {@link CachePointer#getFileId()}.
   * A null set selects all files.
   * The action must synchronize every selected file before it returns successfully.
   */
  void synchronizeOnFlushExecutor(@Nullable final Set<Long> fileIds,
      final Synchronization synchronization) throws IOException, InterruptedException {
    checkOwner();
    final var covered = pages.values().stream()
        .filter(retention -> retention.state == State.SYNC_PENDING)
        .filter(retention -> fileIds == null
            || fileIds.contains(retention.attempt.page().fileId()))
        .toList();
    try {
      synchronization.run();
    } catch (IOException | InterruptedException | RuntimeException | Error failure) {
      for (final var retention : covered) {
        if (retention.state == State.SYNC_PENDING) {
          retention.state = State.UNRESOLVED;
        }
      }
      throw failure;
    }
    for (final var retention : covered) {
      if (retention.state == State.SYNC_PENDING) {
        // The action may replace or discard a covered record before it returns.
        pages.remove(retention.attempt.page(), retention);
      }
    }
  }

  /**
   * Discards only contents that physical deletion or shrink has successfully removed.
   * Pass zero for a whole file, or the first removed page for shrink. Cache purge, close and
   * failed physical operations must not call this method. File writers must remain excluded.
   * The file id is an external id as returned by {@link CachePointer#getFileId()}.
   */
  void discardAfterRemoval(final long fileId, final long firstRemovedPage) {
    checkOwner();
    pages.keySet().removeIf(page -> page.fileId() == fileId
        && page.pageIndex() >= firstRemovedPage);
  }

  /** Returns a detached set for selective DWL cleanup, including pending and failed writes. */
  Set<Long> retainedSegments() {
    checkOwner();
    final var segments = new HashSet<Long>();
    for (final var retention : pages.values()) {
      segments.add(retention.segment);
    }
    return segments;
  }

  /** Pending attempts are not unresolved failures, but still retain their DWL copies. */
  boolean hasUnresolvedPages() {
    checkOwner();
    return pages.values().stream().anyMatch(retention -> retention.state == State.UNRESOLVED);
  }

  boolean hasPendingSynchronization() {
    checkOwner();
    return pages.values().stream().anyMatch(retention -> retention.state == State.SYNC_PENDING);
  }

  @Nullable private Retention matching(@Nullable final WriteAttempt attempt) {
    if (attempt == null) {
      return null;
    }
    final var retention = pages.get(attempt.page());
    return retention != null && retention.attempt.generation() == attempt.generation()
        ? retention : null;
  }

  private void checkOwner() {
    if (!ownerCheck.getAsBoolean()) {
      throw new IllegalStateException("Page retention belongs to the flush executor");
    }
  }

  private record PageKey(long fileId, long pageIndex) {
  }

  record WriteAttempt(PageKey page, long generation) {
  }

  @FunctionalInterface
  interface Synchronization {
    void run() throws IOException, InterruptedException;
  }

  private enum State {
    WRITE_PENDING, UNRESOLVED, SYNC_PENDING
  }

  private static final class Retention {
    private final WriteAttempt attempt;
    private final long segment;
    private State state = State.WRITE_PENDING;

    private Retention(final WriteAttempt attempt, final long segment) {
      this.attempt = attempt;
      this.segment = segment;
    }
  }
}
