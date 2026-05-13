package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;

final class WalTestUtils {

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  private WalTestUtils() {
  }

  /**
   * Prepares the storage for a user-space copy of its raw files while the
   * database is still open, then runs {@code action}.
   *
   * <p>Two hazards have to be neutralized for the copy to produce a recoverable
   * snapshot:
   *
   * <ol>
   *   <li><b>WAL segment rotation.</b> A background fuzzy checkpoint can
   *       delete WAL segments mid-copy and cause {@code FileNotFoundException}
   *       on the reader side. Flushing the WAL and pinning the current
   *       {@code begin} LSN via {@code addCutTillLimit} prevents segment
   *       rotation for the duration of {@code action}.
   *   <li><b>Torn page reads.</b> The background page flusher uses
   *       {@code AsynchronousFileChannel.write} to write 4 KB pages into the
   *       data files. A user-space byte-stream copy can otherwise observe a
   *       half-written page, which then fails magic / checksum verification
   *       during WAL replay and aborts recovery — losing every operation
   *       buffered after the torn page in the WAL. Pausing the background
   *       flusher and draining its in-flight writes before the copy starts
   *       eliminates this race.
   * </ol>
   *
   * <p>Pausing the flusher does <i>not</i> advance on-disk page LSNs, so the
   * subsequent WAL replay on the copy still has real work to do — page-level
   * {@code redo()} dispatch, LSN comparison logic, and recovery dispatch —
   * exactly as the calling test was written to exercise.
   */
  static void withWalProtection(
      DatabaseSessionEmbedded session, ThrowingRunnable action) throws Exception {
    var storage = (DiskStorage) session.getStorage();
    var wal = storage.getWALInstance();
    var writeCache = storage.getWriteCache();

    wal.flush();

    var walBegin = wal.begin();
    wal.addCutTillLimit(walBegin);
    try {
      // Inner try-finally covers BOTH pause and action so that a pause that
      // throws after setting backgroundFlushPaused = true (e.g., barrier
      // wait was interrupted, executor rejected the no-op task) still falls
      // through to resumeBackgroundFlush() and clears the flag. Without
      // this nesting, a failed pause would leave the cache paused for the
      // rest of its lifetime — every subsequent periodic flush would exit
      // at the entry guard and the cache would silently stop writing.
      // resumeBackgroundFlush() is a documented safe no-op when no prior
      // pause set the flag (pinned by
      // WOWCachePauseResumeTest#testResumeWithoutPauseIsNoOp), so it is
      // safe to call even if pauseBackgroundFlush() threw before reaching
      // the volatile-flag write.
      try {
        writeCache.pauseBackgroundFlush();
        action.run();
      } finally {
        writeCache.resumeBackgroundFlush();
      }
    } finally {
      // Outer finally guarantees the WAL cut-till limit is always released,
      // even if resumeBackgroundFlush() itself throws (e.g., a shutdown race
      // makes commitExecutor() reject the re-schedule). A leaked cut-till
      // limit would pin every subsequent test's WAL segments.
      wal.removeCutTillLimit(walBegin);
    }
  }
}
