package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Explicit side-file loading for a single-threaded startup lifecycle. Content validation does not
 * prove WAL coverage or database-image ownership. No storage initialization path calls this helper.
 */
final class ChangedPageTrackerStartup {

  private ChangedPageTrackerStartup() {
  }

  /** Loads and merges saved marks, but withholds current trust until explicit verification. */
  static PendingHistory load(Path sideFile) {
    ChangedPageTrackerFile.Loaded loaded;
    if (sideFile.getFileName().toString().endsWith(ChangedPageTrackerFile.TEMPORARY_SUFFIX)) {
      LogManager.instance().warn(ChangedPageTrackerStartup.class,
          "Changed-page tracker temporary file has no authority: " + sideFile);
      loaded = new ChangedPageTrackerFile.Loaded(new ChangedPageTracker(), null);
    } else {
      try {
        loaded = ChangedPageTrackerFile.load(sideFile);
      } catch (IOException failure) {
        LogManager.instance().warn(ChangedPageTrackerStartup.class,
            "Failed to load changed-page tracker side file: " + sideFile, failure);
        loaded = new ChangedPageTrackerFile.Loaded(new ChangedPageTracker(), null);
      }
    }
    var tracker = loaded.tracker();
    boolean savedTrusted = tracker.isTrusted();
    tracker.startupVerificationPending(true);
    tracker.mergeLoadedSealed();
    return new PendingHistory(tracker, loaded.coverageLsn(), savedTrusted);
  }

  enum Resolution {
    PENDING, KEPT, DISTRUSTED
  }

  /**
   * Verification inputs and the gated tracker. Follow {@link ChangedPageTracker}'s startup threading
   * contract. Saved trust is metadata, not current backup eligibility. Distrust always wins.
   */
  static final class PendingHistory {

    private final ChangedPageTracker tracker;
    @Nullable private final LogSequenceNumber coverageLsn;
    private final boolean savedTrusted;
    private Resolution resolution = Resolution.PENDING;

    private PendingHistory(ChangedPageTracker tracker, @Nullable LogSequenceNumber coverageLsn,
        boolean savedTrusted) {
      this.tracker = tracker;
      this.coverageLsn = coverageLsn;
      this.savedTrusted = savedTrusted;
    }

    ChangedPageTracker tracker() {
      return tracker;
    }

    @Nullable LogSequenceNumber coverageLsn() {
      return coverageLsn;
    }

    boolean savedTrusted() {
      return savedTrusted;
    }

    Resolution resolution() {
      return resolution;
    }

    /** Keeps saved trust without upgrading history. Repeated keeps do not undo distrust. */
    void keepSavedTrust() {
      if (resolution == Resolution.PENDING) {
        tracker.startupVerificationPending(false);
        resolution = Resolution.KEPT;
      }
    }

    /** Revokes history, including after a keep. Repeated distrust calls have no further effect. */
    void distrust() {
      if (resolution != Resolution.DISTRUSTED) {
        tracker.invalidate();
        tracker.startupVerificationPending(false);
        resolution = Resolution.DISTRUSTED;
      }
    }
  }
}
