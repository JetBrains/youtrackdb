package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractPageWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartMetadataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileTruncatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.HighLevelTransactionChangeRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofBatch;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofStatus;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Explicit side-file loading for a single-threaded startup lifecycle. Content validation does not
 * prove WAL coverage or database-image ownership. No storage initialization path calls this helper.
 */
public final class ChangedPageTrackerStartup {

  private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
  private static final int MAX_BATCH_RECORDS = 16;

  private ChangedPageTrackerStartup() {
  }

  /** Loads and merges saved marks, but withholds current trust until explicit verification. */
  public static PendingHistory load(Path sideFile) {
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

  /**
   * Verifies retained WAL coverage and conservatively marks every page before resolving trust.
   * The lifecycle owner excludes writers, backup and cuts. The WAL remains owned by that caller.
   * Only one bounded batch is retained, and incomplete or rolled-back units still contribute marks.
   * File creation and deletion preserve all marks and saved trust. Untrusted history stays untrusted.
   */
  public static StartupResult verify(PendingHistory history, WriteAheadLog wal,
      @Nullable ShutdownState shutdown, FileIds files) {
    var coverage = history.coverageLsn();
    if (coverage == null) {
      return notProved(history, Outcome.MISSING_COVERAGE, null, null);
    }
    try {
      if (wal.preOpenExtent() == null) {
        return notProved(history, Outcome.MISSING_EXTENT, null, null);
      }
      var reader = wal.openProofReader(coverage, MAX_RECORD_BYTES);
      if (reader == null) {
        return notProved(history, Outcome.MISSING_READER, null, null);
      }
      while (true) {
        var batch = reader.next(MAX_BATCH_RECORDS);
        if (batch == null) {
          return notProved(history, Outcome.MISSING_BATCH, null, null);
        }
        // Terminal batches can contain records. Their marks precede every terminal decision.
        for (var record : batch.records()) {
          if (record instanceof AbstractPageWALRecord page) {
            history.tracker().mark(validInternalFileId(files, page.getFileId()),
                page.getPageIndex());
          } else if (record instanceof FileCreatedWALRecord created) {
            // Pages can precede creation in the same unit. Keep historical marks and saved trust.
            validInternalFileId(files, created.getFileId());
          } else if (record instanceof FileDeletedWALRecord deleted) {
            validInternalFileId(files, deleted.getFileId());
          } else if (record instanceof FileTruncatedWALRecord
              || record instanceof AtomicUnitStartRecord
              || record instanceof AtomicUnitEndRecord
              || record instanceof AtomicUnitStartMetadataRecord
              || record instanceof MetaDataRecord
              || record instanceof HighLevelTransactionChangeRecord
              || record instanceof EmptyWALRecord) {
            // Truncation preserves identity and marks. The listed markers need no tracker action.
          } else {
            throw new IllegalStateException("Unclassified WAL proof record: "
                + record.getClass().getName());
          }
        }
        // An empty MORE batch is not an end signal.
        if (terminalDecision(batch, shutdown) != ProofDecision.CONTINUE) {
          return resolve(history, batch, shutdown);
        }
      }
    } catch (RuntimeException failure) {
      readerFailed(history, failure);
      throw failure;
    } catch (Error failure) {
      readerFailed(history, failure);
      throw failure;
    }
  }

  private static int validInternalFileId(FileIds files, long externalId) {
    int internalId = files.internalFileId(externalId);
    if (!files.isValidInternalFileId(internalId) || internalId <= 0) {
      throw new IllegalArgumentException("Invalid WAL file ID: external=" + externalId
          + ", internal=" + internalId);
    }
    return internalId;
  }

  /** The caller captures this state before recovery clears the storage dirty flag. */
  public enum ShutdownState {
    CLEAN, UNCLEAN
  }

  /** Startup file inventory. Names identify current files, not their historical incarnations. */
  public interface FileIds {

    /** Converts an external WAL file ID even when that file is absent from the current inventory. */
    int internalFileId(long externalId);

    /** Tracker marks require positive internal IDs. */
    default boolean isValidInternalFileId(int internalId) {
      return internalId > 0;
    }

    /** Returns the current name, or null when the internal ID has no current file. */
    @Nullable String currentName(int internalId);
  }

  public enum Outcome {
    PROVED_HISTORY_TRUSTED, PROVED_HISTORY_UNTRUSTED, COVERAGE_NOT_PROVED, MISSING_COVERAGE, MISSING_EXTENT, MISSING_READER, MISSING_BATCH, READER_FAILURE
  }

  /**
   * Outcome and trust flags must agree. Trust is a resolution-time snapshot that a later replay
   * failure can revoke.
   */
  public record StartupResult(boolean coverageProved, boolean historyTrusted, Outcome outcome,
      @Nullable ProofStatus terminalStatus, @Nullable Throwable cause) {

    public StartupResult {
      boolean consistent = switch (outcome) {
        case PROVED_HISTORY_TRUSTED -> coverageProved && historyTrusted;
        case PROVED_HISTORY_UNTRUSTED -> coverageProved && !historyTrusted;
        default -> !coverageProved && !historyTrusted;
      };
      if (!consistent) {
        throw new IllegalArgumentException("Startup outcome must agree with coverage and trust");
      }
    }
  }

  enum ProofDecision {
    CONTINUE, PROVED, NOT_PROVED
  }

  /**
   * Interprets only terminal policy, not record coverage. The caller must process every record in
   * the batch, including terminal batches, before resolving history. Null inputs fail closed.
   * Empty MORE batches continue only when the shutdown state is known.
   */
  static ProofDecision terminalDecision(@Nullable ProofBatch batch,
      @Nullable ShutdownState shutdown) {
    if (batch == null || batch.status() == null || shutdown == null) {
      return ProofDecision.NOT_PROVED;
    }
    return switch (batch.status()) {
      case MORE -> ProofDecision.CONTINUE;
      case REACHED_END -> ProofDecision.PROVED;
      case BROKEN_PAGE -> shutdown == ShutdownState.UNCLEAN && !batch.readablePageFollows()
          ? ProofDecision.PROVED : ProofDecision.NOT_PROVED;
      default -> ProofDecision.NOT_PROVED;
    };
  }

  /**
   * Derives proof from the terminal batch and shutdown policy without changing marks or coverage.
   * The caller must process all batch records first. Missing, non-terminal and non-proving batches
   * distrust history. A null shutdown state distrusts history for every batch status.
   */
  static StartupResult resolve(PendingHistory pending, @Nullable ProofBatch batch,
      @Nullable ShutdownState shutdown) {
    var terminalStatus = batch == null ? null : batch.status();
    var cause = batch == null ? null : batch.error();
    if (terminalDecision(batch, shutdown) != ProofDecision.PROVED) {
      return notProved(pending, Outcome.COVERAGE_NOT_PROVED, terminalStatus, cause);
    }
    pending.keepSavedTrust();
    boolean trusted = pending.savedTrusted() && pending.resolution() == Resolution.KEPT
        && pending.tracker().isTrusted();
    return new StartupResult(true, trusted,
        trusted ? Outcome.PROVED_HISTORY_TRUSTED : Outcome.PROVED_HISTORY_UNTRUSTED,
        terminalStatus, cause);
  }

  /**
   * Fails closed with a missing-input or terminal-failure diagnostic. A proof outcome is rejected
   * after distrust, so caller misuse cannot retain trust.
   */
  static StartupResult notProved(PendingHistory pending, Outcome outcome,
      @Nullable ProofStatus terminalStatus, @Nullable Throwable cause) {
    pending.distrust();
    var result = new StartupResult(false, false, outcome, terminalStatus, cause);
    warn(outcome, terminalStatus, cause);
    return result;
  }

  /**
   * Attempts trust revocation and always propagates the original reader failure, even if distrust
   * or diagnostics fail. Attaching secondary failures is best effort because suppression can be
   * disabled. Primary failure identity is guaranteed. The caller must abort this startup attempt.
   */
  static <T extends Throwable> void readerFailed(PendingHistory pending, T failure) throws T {
    try {
      try {
        pending.distrust();
      } catch (Throwable secondary) {
        if (secondary != failure) {
          try {
            failure.addSuppressed(secondary);
          } catch (Throwable ignored) {
            // Suppression is best effort and must not replace the reader failure.
          }
        }
      }
      try {
        warn(Outcome.READER_FAILURE, null, failure);
      } catch (Throwable secondary) {
        if (secondary != failure) {
          try {
            failure.addSuppressed(secondary);
          } catch (Throwable ignored) {
            // Suppression is best effort and must not replace the reader failure.
          }
        }
      }
    } finally {
      // Logging can allocate. Even a logging failure must not replace the original reader failure.
      throw failure;
    }
  }

  /** Replay errors revoke trust even after successful proof resolution. */
  public static void replayFailed(PendingHistory pending) {
    pending.distrust();
  }

  /**
   * Resolves history as distrusted before invalidation allocation or I/O, retaining marks and
   * coverage. Removes durable authority before a startup cut or admission. The lifecycle owner
   * excludes writers, backup and cuts, and holds no cache inventory lock. Invalidation failure
   * throws an IOException with the original cause. An Error propagates unchanged.
   */
  public static void durableDistrust(Path sideFile, PendingHistory pending) throws IOException {
    pending.distrust();
    durableDistrust(sideFile, pending, new ChangedPageTrackerFile.FileOperations());
  }

  static void durableDistrust(Path sideFile, PendingHistory pending,
      ChangedPageTrackerFile.FileOperations files) throws IOException {
    pending.distrust();
    var failure = ChangedPageTrackerFile.invalidateWithCause(sideFile, pending.tracker(), files);
    if (failure != null) {
      throw new IOException("Failed to durably distrust changed-page tracker: " + sideFile,
          failure);
    }
  }

  private static void warn(Outcome outcome, @Nullable ProofStatus status,
      @Nullable Throwable cause) {
    LogManager.instance().warn(ChangedPageTrackerStartup.class,
        "Changed-page tracker startup history is untrusted: outcome=" + outcome + ", status="
            + status + ", cause=" + cause,
        cause);
  }

  enum Resolution {
    PENDING, KEPT, DISTRUSTED
  }

  /**
   * Verification inputs and the gated tracker. Follow {@link ChangedPageTracker}'s startup threading
   * contract. Saved trust is metadata, not current backup eligibility. Distrust always wins.
   */
  public static final class PendingHistory {

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

    public ChangedPageTracker tracker() {
      return tracker;
    }

    public @Nullable LogSequenceNumber coverageLsn() {
      return coverageLsn;
    }

    public boolean savedTrusted() {
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
