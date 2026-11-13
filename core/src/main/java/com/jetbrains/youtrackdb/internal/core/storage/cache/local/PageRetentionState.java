package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/**
 * Keeps complete DWL copies until writes satisfy durability. Page records belong to the
 * flush executor. Lifecycle callers can query a concurrent file-level hint under writer exclusion.
 * Checkpoint forces cannot acknowledge this state.
 * This state is independent of WAL and backup tracking.
 */
final class PageRetentionState {

  // About 5 MiB at 80 bytes per record, excluding unresolved pages.
  static final int SYNCHRONIZATION_LIMIT = 65_536;
  private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<WriteAttempt>> files =
      new Long2ObjectOpenHashMap<>();
  // Lifecycle callers need only a conservative file-level hint, not access to owner-only maps.
  private final Set<Long> retainedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final BooleanSupplier ownerCheck;
  private long generation;
  private int pendingSynchronization;

  PageRetentionState(final BooleanSupplier ownerCheck) {
    this.ownerCheck = Objects.requireNonNull(ownerCheck);
  }

  /** Publish after append and before data submission. Null segments and non-durable pages skip it. */
  @Nullable WriteAttempt appendSucceeded(
      final CachePointer pointer, @Nullable final Long segment, final boolean durable) {
    checkOwner();
    if (!durable || segment == null) {
      return null;
    }
    final var attempt = new WriteAttempt(pointer.getFileId(), pointer.getPageIndex(),
        ++generation, segment);
    final var pages = files.computeIfAbsent(attempt.fileId,
        ignored -> new Long2ObjectOpenHashMap<>());
    retainedFiles.add(attempt.fileId);
    final var previous = pages.put(attempt.pageIndex, attempt);
    if (previous != null && previous.state == State.SYNC_PENDING) {
      pendingSynchronization--;
    }
    return attempt;
  }

  /** Complete only the matching identity. Report an uncertain batch as failed before any release. */
  void writeSucceeded(@Nullable final WriteAttempt attempt, final boolean fsyncEnabled) {
    checkOwner();
    if (!matching(attempt) || attempt.state != State.WRITE_PENDING) {
      return;
    }
    if (fsyncEnabled) {
      attempt.state = State.SYNC_PENDING;
      pendingSynchronization++;
    } else {
      remove(attempt);
    }
  }

  /** Protect the whole batch when individual outcomes are uncertain. */
  void writesFailed(final Collection<WriteAttempt> attempts) {
    checkOwner();
    for (final var attempt : attempts) {
      if (matching(attempt)) {
        if (attempt.state == State.SYNC_PENDING) {
          pendingSynchronization--;
        }
        attempt.state = State.UNRESOLVED;
      }
    }
  }

  /** The action must force every selected file. A failed force cannot be repaired by another force. */
  void synchronizeOnFlushExecutor(final Synchronization synchronization)
      throws IOException, InterruptedException {
    synchronizeOnFlushExecutor(null, synchronization);
  }

  /** Select external file ids. Scoped work visits only the selected files, not all retained pages. */
  void synchronizeOnFlushExecutor(@Nullable final Set<Long> fileIds,
      final Synchronization synchronization) throws IOException, InterruptedException {
    checkOwner();
    final var covered = collectPending(fileIds);
    try {
      synchronization.run();
    } catch (IOException | InterruptedException | RuntimeException | Error failure) {
      writesFailed(covered);
      throw failure;
    }
    for (final var attempt : covered) {
      if (matching(attempt) && attempt.state == State.SYNC_PENDING) {
        remove(attempt);
      }
    }
  }

  private ArrayList<WriteAttempt> collectPending(@Nullable final Set<Long> fileIds) {
    final var covered = new ArrayList<WriteAttempt>();
    final Set<Long> selectedFiles = fileIds == null ? files.keySet() : fileIds;
    for (final long fileId : selectedFiles) {
      final var pages = files.get(fileId);
      if (pages != null) {
        for (final var attempt : pages.values()) {
          if (attempt.state == State.SYNC_PENDING) {
            covered.add(attempt);
          }
        }
      }
    }
    return covered;
  }

  /** Discard only physically removed contents, while writers remain excluded. */
  void discardAfterRemoval(final long fileId, final long firstRemovedPage) {
    checkOwner();
    final var pages = files.get(fileId);
    if (pages == null) {
      return;
    }
    final var iterator = pages.values().iterator();
    while (iterator.hasNext()) {
      final var attempt = iterator.next();
      if (attempt.pageIndex >= firstRemovedPage) {
        if (attempt.state == State.SYNC_PENDING) {
          pendingSynchronization--;
        }
        iterator.remove();
      }
    }
    if (pages.isEmpty()) {
      files.remove(fileId);
      retainedFiles.remove(fileId);
    }
  }

  /** Safe under lifecycle writer exclusion, which excludes new appends for this file. */
  boolean hasRetainedContents(long fileId) {
    return retainedFiles.contains(fileId);
  }

  /** Return a detached set only when cleanup or the final close guard needs it. */
  Set<Long> retainedSegments() {
    checkOwner();
    final var segments = new HashSet<Long>();
    for (final var pages : files.values()) {
      for (final var attempt : pages.values()) {
        segments.add(attempt.segment);
      }
    }
    return segments;
  }

  boolean hasUnresolvedPages() {
    checkOwner();
    for (final var pages : files.values()) {
      for (final var attempt : pages.values()) {
        if (attempt.state == State.UNRESOLVED) {
          return true;
        }
      }
    }
    return false;
  }

  boolean hasPendingSynchronization() {
    checkOwner();
    return pendingSynchronization > 0;
  }

  boolean synchronizationLimitReached() {
    checkOwner();
    return pendingSynchronization >= SYNCHRONIZATION_LIMIT;
  }

  private boolean matching(@Nullable WriteAttempt attempt) {
    if (attempt == null) {
      return false;
    }
    final var pages = files.get(attempt.fileId);
    return pages != null && pages.get(attempt.pageIndex) == attempt;
  }

  private void remove(WriteAttempt attempt) {
    final var pages = files.get(attempt.fileId);
    pages.remove(attempt.pageIndex);
    if (attempt.state == State.SYNC_PENDING) {
      pendingSynchronization--;
    }
    if (pages.isEmpty()) {
      files.remove(attempt.fileId);
      retainedFiles.remove(attempt.fileId);
    }
  }

  private void checkOwner() {
    if (!ownerCheck.getAsBoolean()) {
      throw new IllegalStateException("Page retention belongs to the flush executor");
    }
  }

  /** Identity equality protects replacement generations without a separate key or retention object. */
  static final class WriteAttempt {
    private final long fileId;
    private final long pageIndex;
    private final long generation;
    private final long segment;
    private State state = State.WRITE_PENDING;

    private WriteAttempt(long fileId, long pageIndex, long generation, long segment) {
      this.fileId = fileId;
      this.pageIndex = pageIndex;
      this.generation = generation;
      this.segment = segment;
    }

    long generation() {
      return generation;
    }
  }

  @FunctionalInterface
  interface Synchronization {
    void run() throws IOException, InterruptedException;
  }

  private enum State {
    WRITE_PENDING, UNRESOLVED, SYNC_PENDING
  }
}
