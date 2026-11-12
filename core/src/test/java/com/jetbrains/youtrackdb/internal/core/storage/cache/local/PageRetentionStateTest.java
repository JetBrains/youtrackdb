package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

/** Checks the complete durability lifetime without a cache, database or physical DWL. */
public class PageRetentionStateTest {

  private final Thread owner = Thread.currentThread();
  private final PageRetentionState state =
      new PageRetentionState(() -> Thread.currentThread() == owner);

  /** Pending publication retains every page before submission, without inventing failures. */
  @Test
  public void appendPublishesAllPagesAndReturnsDetachedSegmentSet() {
    assertState(Set.of(), false, false);
    state.appendSucceeded(pointer(1, 0), 10L, true);
    state.appendSucceeded(pointer(1, 1), 10L, true);
    state.appendSucceeded(pointer(2, 0), 20L, true);
    assertState(Set.of(10L, 20L), false, false);
    state.retainedSegments().clear();
    assertState(Set.of(10L, 20L), false, false);
  }

  /** Failed writes stay unresolved even after a successful synchronization of torn bytes. */
  @Test
  public void failedBatchCannotBeResolvedBySynchronization()
      throws IOException, InterruptedException {
    final var first = append(1, 0, 10);
    final var second = append(2, 0, 20);
    state.writesFailed(List.of(first, second));
    assertState(Set.of(10L, 20L), true, false);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(10L, 20L), true, false);
  }

  /** Uncertain batches protect all attempts, including an apparent success awaiting fsync. */
  @Test
  public void uncertainBatchMakesEveryAffectedPageUnresolved()
      throws IOException, InterruptedException {
    final var first = append(1, 0, 10);
    final var second = append(1, 1, 20);
    final var unrelated = append(2, 0, 30);
    state.writeSucceeded(first, true);
    state.writeSucceeded(unrelated, true);
    state.writesFailed(List.of(first, second));
    assertState(Set.of(10L, 20L, 30L), true, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(10L, 20L), true, false);
  }

  /** Only a newer successful append can replace unresolved protection without removing content. */
  @Test
  public void unresolvedRecordSurvivesEveryNonReplacementOperation()
      throws IOException, InterruptedException {
    final var first = append(1, 1, 10);
    state.writesFailed(List.of(first));
    state.writesFailed(List.of());
    state.writesFailed(List.of(first));
    state.writeSucceeded(first, false);
    state.writeSucceeded(first, true);
    assertNull(state.appendSucceeded(pointer(1, 1), null, true));
    assertNull(state.appendSucceeded(pointer(1, 1), 20L, false));
    assertState(Set.of(10L), true, false);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertThrows(IOException.class,
        () -> state.synchronizeOnFlushExecutor(PageRetentionStateTest::failPhysicalOperation));
    assertState(Set.of(10L), true, false);
    append(1, 1, 20);
    assertState(Set.of(20L), false, false);
  }

  /** A newer copy moves only that page, so other pages can still require the older segment. */
  @Test
  public void newestCopyReplacesOnlyItsOwnRetentionRecord() {
    final var page = pointer(1, 0);
    final var first = state.appendSucceeded(page, 10L, true);
    final var other = append(1, 1, 10);
    state.writesFailed(List.of(first, other));
    final var retry = state.appendSucceeded(page, 20L, true);
    state.writeSucceeded(retry, false);
    assertState(Set.of(10L), true, false);
    state.writeSucceeded(other, false);
    assertState(Set.of(10L), true, false);
    state.discardAfterRemoval(1, 1);
    assertState(Set.of(), false, false);
  }

  /** Successful writes wait for successful fsync, while failed and pending writes stay protected. */
  @Test
  public void synchronizationReleasesOnlySuccessfulWrites()
      throws IOException, InterruptedException {
    final var success = append(1, 0, 10);
    final var failure = append(1, 1, 20);
    append(1, 2, 30);
    state.writeSucceeded(success, true);
    state.writesFailed(List.of(failure));
    assertState(Set.of(10L, 20L, 30L), true, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(20L, 30L), true, false);
  }

  /** Fsync disabled releases matching writes directly, without invoking synchronization. */
  @Test
  public void fsyncDisabledReleasesSuccessfulRetryImmediately() {
    final var page = pointer(1, 0);
    final var failure = state.appendSucceeded(page, 10L, true);
    state.writesFailed(List.of(failure));
    final var retry = state.appendSucceeded(page, 20L, true);
    state.writeSucceeded(retry, false);
    assertState(Set.of(), false, false);
  }

  /** An old success or failure cannot change the state or segment of a newer attempt. */
  @Test
  public void staleCompletionsCannotResolveOrFailNewGeneration()
      throws IOException, InterruptedException {
    final var page = pointer(1, 0);
    final var first = state.appendSucceeded(page, 10L, true);
    final var second = state.appendSucceeded(page, 20L, true);
    assertNotEquals(first.generation(), second.generation());
    state.writeSucceeded(first, false);
    state.writesFailed(List.of(first));
    assertState(Set.of(20L), false, false);
    state.writeSucceeded(second, true);
    state.writesFailed(List.of(first));
    assertState(Set.of(20L), false, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(), false, false);
  }

  /** A sync snapshot cannot release a replacement generation or a write completed after entry. */
  @Test
  public void synchronizationCoversOnlySuccessfulGenerationsAtEntry()
      throws IOException, InterruptedException {
    final var page = pointer(1, 0);
    final var first = state.appendSucceeded(page, 10L, true);
    final var late = append(1, 1, 30);
    state.writeSucceeded(first, true);
    state.synchronizeOnFlushExecutor(() -> {
      final var replacement = state.appendSucceeded(page, 20L, true);
      state.writeSucceeded(replacement, true);
      state.writeSucceeded(late, true);
    });
    assertState(Set.of(20L, 30L), false, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(), false, false);
  }

  /** Successful sync keeps a replacement pending until its own data write and sync complete. */
  @Test
  public void successfulSynchronizationKeepsReplacementAppendedDuringAction()
      throws IOException, InterruptedException {
    final var first = append(1, 0, 10);
    state.writeSucceeded(first, true);
    final var replacement = new AtomicReference<PageRetentionState.WriteAttempt>();
    state.synchronizeOnFlushExecutor(Set.of(1L),
        () -> replacement.set(append(1, 0, 20)));
    assertState(Set.of(20L), false, false);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(20L), false, false);
    state.writeSucceeded(replacement.get(), true);
    assertState(Set.of(20L), false, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(), false, false);
  }

  /** Failed sync cannot mark a replacement unresolved, even if its data write already succeeded. */
  @Test
  public void failedSynchronizationKeepsReplacementAppendedDuringAction()
      throws IOException, InterruptedException {
    final var first = append(1, 0, 10);
    state.writeSucceeded(first, true);
    final var failure = new IOException("Injected fsync failure after replacement");
    assertSame(failure, assertThrows(IOException.class,
        () -> state.synchronizeOnFlushExecutor(Set.of(1L), () -> {
          state.writeSucceeded(append(1, 0, 20), true);
          throw failure;
        })));
    assertState(Set.of(20L), false, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(), false, false);
  }

  /** Neither successful nor failed sync restores a record discarded by the synchronization action. */
  @Test
  public void synchronizationDoesNotRestoreRecordDiscardedDuringAction()
      throws IOException, InterruptedException {
    for (final boolean failSync : new boolean[] {false, true}) {
      final var first = append(1, 0, 10);
      state.writeSucceeded(first, true);
      final PageRetentionState.Synchronization action = () -> {
        state.discardAfterRemoval(1, 0);
        if (failSync) {
          throw new IOException("Injected fsync failure after removal");
        }
      };
      if (failSync) {
        assertThrows(IOException.class, () -> state.synchronizeOnFlushExecutor(Set.of(1L), action));
      } else {
        state.synchronizeOnFlushExecutor(Set.of(1L), action);
      }
      assertState(Set.of(), false, false);
      state.synchronizeOnFlushExecutor(() -> {
      });
      assertState(Set.of(), false, false);
    }
  }

  /** Failure discovered during synchronization prevents release of the snapshotted attempt. */
  @Test
  public void synchronizationNeverReleasesAnAttemptThatBecameUnresolved()
      throws IOException, InterruptedException {
    final var attempt = append(1, 0, 10);
    state.writeSucceeded(attempt, true);
    state.synchronizeOnFlushExecutor(() -> state.writesFailed(List.of(attempt)));
    assertState(Set.of(10L), true, false);
  }

  /** A scoped sync acknowledges only the selected external file ids. An empty set covers none. */
  @Test
  public void scopedSynchronizationKeepsOtherFilesProtected()
      throws IOException, InterruptedException {
    final var first = append(1, 0, 10);
    final var second = append(2, 0, 20);
    state.writeSucceeded(first, true);
    state.writeSucceeded(second, true);
    state.synchronizeOnFlushExecutor(Set.of(), () -> {
    });
    assertState(Set.of(10L, 20L), false, true);
    state.synchronizeOnFlushExecutor(Set.of(1L), () -> {
    });
    assertState(Set.of(20L), false, true);
    state.synchronizeOnFlushExecutor(Set.of(2L), () -> {
    });
    assertState(Set.of(), false, false);
  }

  /** Failed fsync makes covered writes unresolved, so a later successful fsync cannot release them. */
  @Test
  public void failedSynchronizationCannotBeRepairedByLaterSynchronization()
      throws IOException, InterruptedException {
    final var first = append(1, 1, 10);
    state.writeSucceeded(first, true);
    final var failure = new IOException("Injected fsync failure");
    assertEquals(failure, assertThrows(IOException.class,
        () -> state.synchronizeOnFlushExecutor(() -> {
          throw failure;
        })));
    assertState(Set.of(10L), true, false);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(10L), true, false);
    final var retry = append(1, 1, 20);
    state.writeSucceeded(retry, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(), false, false);
  }

  /** A failed scoped sync leaves other files, pending writes and replacement generations alone. */
  @Test
  public void failedSynchronizationAffectsOnlyMatchingCoveredPendingAttempts()
      throws IOException, InterruptedException {
    final var covered = append(1, 0, 10);
    final var replaced = append(1, 1, 20);
    final var removed = append(1, 9, 30);
    final var failedDuringSync = append(1, 3, 40);
    final var otherFile = append(2, 0, 50);
    final var pending = append(1, 4, 60);
    for (final var attempt : List.of(covered, replaced, removed, failedDuringSync, otherFile)) {
      state.writeSucceeded(attempt, true);
    }
    assertThrows(InterruptedException.class,
        () -> state.synchronizeOnFlushExecutor(Set.of(1L), () -> {
          state.discardAfterRemoval(1, 9);
          state.writesFailed(List.of(failedDuringSync));
          state.writeSucceeded(append(1, 1, 70), true);
          state.writeSucceeded(append(1, 5, 80), true);
          throw new InterruptedException("Injected interruption");
        }));
    // Removed generations cannot be restored by the failure report.
    state.writeSucceeded(pending, true);
    assertState(Set.of(10L, 40L, 50L, 60L, 70L, 80L), true, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(10L, 40L), true, false);
  }

  /** Unchecked synchronization failures also retain covered writes as unresolved. */
  @Test
  public void uncheckedSynchronizationFailureKeepsProtection() {
    final var first = append(1, 0, 10);
    state.writeSucceeded(first, true);
    assertThrows(IllegalStateException.class,
        () -> state.synchronizeOnFlushExecutor(() -> {
          throw new IllegalStateException("Injected failure");
        }));
    assertState(Set.of(10L), true, false);
  }

  /**
   * An Error is rethrown unchanged and makes covered writes unresolved.
   * Later successful synchronization retains those writes and releases only the uncovered file.
   */
  @Test
  public void synchronizationErrorRetainsCoveredWritesAndLeavesOtherFilesPending()
      throws IOException, InterruptedException {
    final var first = append(1, 0, 10);
    final var second = append(1, 1, 20);
    final var uncovered = append(2, 0, 30);
    for (final var attempt : List.of(first, second, uncovered)) {
      state.writeSucceeded(attempt, true);
    }
    final var failure = new AssertionError("Injected synchronization error");
    assertSame(failure, assertThrows(AssertionError.class,
        () -> state.synchronizeOnFlushExecutor(Set.of(1L), () -> {
          throw failure;
        })));
    assertState(Set.of(10L, 20L, 30L), true, true);
    state.synchronizeOnFlushExecutor(Set.of(1L), () -> {
    });
    assertState(Set.of(10L, 20L, 30L), true, true);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertState(Set.of(10L, 20L), true, false);
  }

  /** A late success cannot release an unresolved attempt in either fsync mode. */
  @Test
  public void lateSuccessCannotResolveFailedGeneration() throws IOException, InterruptedException {
    for (final boolean fsyncEnabled : new boolean[] {false, true}) {
      final var attempt = append(1, 1, 10);
      state.writesFailed(List.of(attempt));
      state.writeSucceeded(attempt, fsyncEnabled);
      assertState(Set.of(10L), true, false);
      state.synchronizeOnFlushExecutor(() -> {
      });
      assertState(Set.of(10L), true, false);
      state.discardAfterRemoval(1, 0);
    }
  }

  /** Reloaded page (1,1) replaces a failed pointer and ignores its late reports in both fsync modes. */
  @Test
  public void replacementPointerDoesNotAcceptOldCompletion()
      throws IOException, InterruptedException {
    for (final boolean fsyncEnabled : new boolean[] {false, true}) {
      final var oldPage = new CachePointer((Pointer) null, null, 1, 1);
      final var newPage = new CachePointer((Pointer) null, null, 1, 1);
      assertEquals(oldPage, newPage);
      final var oldAttempt = state.appendSucceeded(oldPage, 10L, true);
      state.writesFailed(List.of(oldAttempt));
      final var newAttempt = state.appendSucceeded(newPage, 20L, true);
      assertNotEquals(oldAttempt.generation(), newAttempt.generation());
      state.writeSucceeded(oldAttempt, false);
      state.writesFailed(List.of(oldAttempt));
      assertState(Set.of(20L), false, false);
      state.writeSucceeded(newAttempt, fsyncEnabled);
      if (fsyncEnabled) {
        assertState(Set.of(20L), false, true);
        state.synchronizeOnFlushExecutor(() -> {
        });
      }
      assertState(Set.of(), false, false);
      state.writeSucceeded(oldAttempt, true);
      state.writesFailed(List.of(oldAttempt));
      assertState(Set.of(), false, false);
    }
  }

  /** Removal and reuse of the same pointer must not reset its attempt generation. */
  @Test
  public void discardedOrReleasedPointerCannotReuseAnOldGeneration() {
    final var page = pointer(1, 0);
    final var first = state.appendSucceeded(page, 10L, true);
    state.discardAfterRemoval(1, 0);
    final var second = state.appendSucceeded(page, 20L, true);
    state.writeSucceeded(first, false);
    assertState(Set.of(20L), false, false);
    state.writeSucceeded(second, false);
    final var third = state.appendSucceeded(page, 30L, true);
    state.writeSucceeded(second, false);
    assertNotEquals(second.generation(), third.generation());
    assertState(Set.of(30L), false, false);
  }

  /** Successful shrink discards only its suffix, and successful delete discards only its file. */
  @Test
  public void physicalRemovalDiscardsOnlySupersededContents() {
    final var lower = append(1, 1, 10);
    final var boundary = append(1, 2, 20);
    final var upper = append(1, 3, 30);
    final var otherFile = append(2, 2, 40);
    state.writesFailed(List.of(lower, boundary));
    state.writeSucceeded(upper, true);
    state.writeSucceeded(otherFile, true);
    state.discardAfterRemoval(1, 2);
    assertState(Set.of(10L, 40L), true, true);
    state.discardAfterRemoval(1, 0);
    assertState(Set.of(40L), false, true);
    state.discardAfterRemoval(2, 0);
    assertState(Set.of(), false, false);
  }

  /** Failure notifications, queries and sync do not remove content. Only discard removes it. */
  @Test
  public void unresolvedRecordStaysUntilExplicitSuccessfulRemoval()
      throws IOException, InterruptedException {
    final var attempt = append(1, 2, 10);
    state.writesFailed(List.of(attempt));
    state.writesFailed(List.of());
    state.writeSucceeded(null, false);
    state.synchronizeOnFlushExecutor(() -> {
    });
    assertThrows(IOException.class,
        () -> state.synchronizeOnFlushExecutor(PageRetentionStateTest::failPhysicalOperation));
    assertState(Set.of(10L), true, false);
    state.discardAfterRemoval(2, 0);
    state.discardAfterRemoval(1, 3);
    assertState(Set.of(10L), true, false);
    state.discardAfterRemoval(1, 2);
    assertState(Set.of(), false, false);
  }

  /** Non-durable and no-operation appends create no attempts or retention records. */
  @Test
  public void excludedFilesAndNullAttemptsDoNotAffectDurableProtection() {
    final var durable = append(1, 0, 10);
    assertNull(state.appendSucceeded(pointer(2, 0), 20L, false));
    assertNull(state.appendSucceeded(pointer(3, 0), null, true));
    state.writeSucceeded(null, false);
    state.writesFailed(Arrays.asList(null, durable));
    assertState(Set.of(10L), true, false);
  }

  /** Every entry point rejects a non-owner before it can read, mutate or invoke synchronization. */
  @Test
  public void nonOwnerCannotUseAnyEntryPoint() throws Throwable {
    final var attempt = append(1, 0, 10);
    state.writeSucceeded(attempt, true);
    final List<ThrowingRunnable> operations = List.of(
        () -> state.appendSucceeded(pointer(1, 0), 20L, true),
        () -> state.appendSucceeded(pointer(2, 0), null, false),
        () -> state.writeSucceeded(attempt, false),
        () -> state.writeSucceeded(null, true),
        () -> state.writesFailed(List.of(attempt)),
        () -> state.writesFailed(List.of()),
        () -> state.discardAfterRemoval(1, 0),
        () -> state.synchronizeOnFlushExecutor(() -> {
          throw new AssertionError("Non-owner reached synchronization");
        }),
        () -> state.synchronizeOnFlushExecutor(Set.of(1L), () -> {
          throw new AssertionError("Non-owner reached scoped synchronization");
        }),
        state::retainedSegments,
        state::hasUnresolvedPages,
        state::hasPendingSynchronization);
    for (final var operation : operations) {
      runOnOtherThread(() -> assertThrows(IllegalStateException.class, operation));
      assertState(Set.of(10L), false, true);
    }
  }

  /** A first query from a checkpoint cannot claim ownership before the flush executor uses it. */
  @Test
  public void firstUseFromNonOwnerCannotBindState() throws Throwable {
    runOnOtherThread(() -> assertThrows(IllegalStateException.class, state::retainedSegments));
    runOnOtherThread(() -> assertThrows(IllegalStateException.class,
        () -> state.appendSucceeded(pointer(1, 0), 10L, true)));
    assertState(Set.of(), false, false);
    append(1, 0, 20);
    assertState(Set.of(20L), false, false);
  }

  /** The explicit check accepts a replacement executor worker after the previous worker stops. */
  @Test
  public void ownerCheckCanRecognizeReplacementWorker() throws Throwable {
    final var executorWorker = new AtomicReference<>(Thread.currentThread());
    final var retention = new PageRetentionState(
        () -> Thread.currentThread() == executorWorker.get());
    final var attempt = retention.appendSucceeded(pointer(1, 0), 10L, true);
    runOnOtherThread(() -> {
      executorWorker.set(Thread.currentThread());
      retention.writeSucceeded(attempt, true);
      assertTrue(retention.hasPendingSynchronization());
      retention.synchronizeOnFlushExecutor(() -> {
      });
      assertEquals(Set.of(), retention.retainedSegments());
      assertFalse(retention.hasUnresolvedPages());
    });
    assertThrows(IllegalStateException.class, retention::retainedSegments);
  }

  private static void runOnOtherThread(ThrowingRunnable operation) throws Throwable {
    final var failure = new AtomicReference<Throwable>();
    final var thread = new Thread(() -> {
      try {
        operation.run();
      } catch (Throwable thrown) {
        failure.set(thrown);
      }
    });
    thread.setDaemon(true);
    thread.start();
    thread.join(5000);
    assertFalse("Worker did not finish within five seconds", thread.isAlive());
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  private PageRetentionState.WriteAttempt append(long fileId, long pageIndex, long segment) {
    return state.appendSucceeded(pointer(fileId, pageIndex), segment, true);
  }

  private void assertState(Set<Long> segments, boolean unresolved, boolean syncPending) {
    assertEquals(segments, state.retainedSegments());
    if (unresolved) {
      assertTrue(state.hasUnresolvedPages());
    } else {
      assertFalse(state.hasUnresolvedPages());
    }
    assertEquals(syncPending, state.hasPendingSynchronization());
  }

  private static void failPhysicalOperation() throws IOException {
    throw new IOException("Injected physical operation failure");
  }

  private static CachePointer pointer(long fileId, long pageIndex) {
    final var pointer = mock(CachePointer.class);
    when(pointer.getFileId()).thenReturn(fileId);
    when(pointer.getPageIndex()).thenReturn((int) pageIndex);
    return pointer;
  }
}
