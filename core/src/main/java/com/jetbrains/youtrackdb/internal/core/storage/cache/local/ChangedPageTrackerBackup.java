package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Internal cross-package backup lifecycle. Begin runs under the caller's writer pause. Completion
 * and abandon run after writers resume. The caller keeps backup exclusion until cleanup finishes.
 * This facade neither owns output streams nor certifies their durability.
 */
public final class ChangedPageTrackerBackup {

  private static final IOException NO_SAFE_COVERAGE = new IOException("No retained safe coverage");

  private ChangedPageTrackerBackup() {
  }

  public record Continuity(UUID trackerIdentifier, UUID generationIdentifier) {
    public Continuity {
      Objects.requireNonNull(trackerIdentifier);
      Objects.requireNonNull(generationIdentifier);
    }
  }

  /** Opaque authority for exactly one boundary on its owning tracker. */
  public static final class SealHandle {
    private final ChangedPageTracker owner;
    private final ChangedPageTracker.SealedGeneration seal;

    private SealHandle(ChangedPageTracker owner, ChangedPageTracker.SealedGeneration seal) {
      this.owner = owner;
      this.seal = seal;
    }
  }

  public enum BeginOutcome {
    NEW_BOUNDARY, PENDING_SEAL
  }

  /** One immutable snapshot, including eligibility and output capability as separate facts. */
  public static final class Context {
    @Nullable private final SealHandle handle;
    private final UUID trackerIdentifier;
    @Nullable private final UUID lastCompletedIdentifier;
    private final boolean trustedAtBoundary;
    private final boolean selectionEligible;
    private final boolean durabilityCapable;

    private Context(@Nullable SealHandle handle, UUID trackerIdentifier,
        @Nullable UUID lastCompletedIdentifier, boolean trusted, @Nullable Continuity head,
        boolean capable) {
      this.handle = handle;
      this.trackerIdentifier = trackerIdentifier;
      this.lastCompletedIdentifier = lastCompletedIdentifier;
      trustedAtBoundary = trusted;
      selectionEligible = trusted && head != null
          && trackerIdentifier.equals(head.trackerIdentifier())
          && Objects.equals(lastCompletedIdentifier, head.generationIdentifier());
      durabilityCapable = capable;
    }

    public @Nullable SealHandle sealHandle() {
      return handle;
    }

    public UUID trackerIdentifier() {
      return trackerIdentifier;
    }

    public @Nullable UUID lastCompletedIdentifier() {
      return lastCompletedIdentifier;
    }

    public @Nullable UUID consumedIdentifier() {
      return handle == null ? null : handle.seal.identifier();
    }

    public boolean trustedAtBoundary() {
      return trustedAtBoundary;
    }

    public boolean selectionEligible() {
      return selectionEligible;
    }

    public boolean durabilityCapable() {
      return durabilityCapable;
    }

    public BeginOutcome outcome() {
      return handle == null ? BeginOutcome.PENDING_SEAL : BeginOutcome.NEW_BOUNDARY;
    }

    @Nullable ChangedPageTracker.SealedGeneration internalSeal() {
      return handle == null ? null : handle.seal;
    }
  }

  static SealHandle handle(ChangedPageTracker tracker,
      ChangedPageTracker.SealedGeneration boundary) {
    return new SealHandle(tracker, boundary);
  }

  static Context context(ChangedPageTracker tracker, @Nullable SealHandle handle, UUID identifier,
      @Nullable UUID completed, boolean trusted, @Nullable Continuity head, boolean capable) {
    return new Context(handle, identifier, completed, trusted, head, capable);
  }

  public static Context begin(ChangedPageTracker tracker, @Nullable Continuity headContinuity,
      boolean durabilityCapable) {
    return tracker.beginBoundary(headContinuity, durabilityCapable);
  }

  public record CompletionFacts(boolean fullRead, boolean completeUnitDurable) {
  }

  /**
   * Caller bridge: sample anchor, transaction table, then cache before entering save order.
   * Preflight resolves that fixed boundary against current retained WAL under save order. It must
   * release all WAL locks before returning. It must not cut WAL or raise the sampled boundary.
   */
  public interface CoverageSource {
    long sampleBoundary() throws IOException;

    @Nullable WriteAheadLog.CutPreflight preflightCut(long fixedBoundary) throws IOException;
  }

  public enum Status {
    SAVED_CONTINUITY, SAVED_UNTRUSTED, SAVE_FAILED_INVALIDATED, SAVE_FAILED_INVALIDATION_FAILED, COVERAGE_UNAVAILABLE, MERGED_NOT_CONSUMED, NO_BOUNDARY
  }

  public static Status complete(ChangedPageTracker tracker, Context context, Path sideFile,
      CompletionFacts facts, CoverageSource coverageSource) {
    return complete(tracker, context, sideFile, facts, coverageSource,
        new ChangedPageTrackerFile.FileOperations());
  }

  static Status complete(ChangedPageTracker tracker, Context context, Path sideFile,
      CompletionFacts facts, CoverageSource source, ChangedPageTrackerFile.FileOperations files) {
    var handle = ownedHandle(tracker, context);
    if (handle == null) {
      return Status.NO_BOUNDARY;
    }
    if (!context.durabilityCapable() || !facts.completeUnitDurable()) {
      return abandon(tracker, context);
    }
    Long fixed = null;
    try {
      fixed = source.sampleBoundary();
    } catch (IOException | RuntimeException unavailable) {
      // Cleanup occurs under save order after sampling has released any external resources.
    }
    var order = tracker.saveOrderLock();
    order.lock();
    try {
      tracker.validateBoundary(handle.seal);
      WriteAheadLog.CutPreflight preflight = null;
      try {
        if (fixed != null && fixed >= 0) {
          preflight = source.preflightCut(fixed);
        }
      } catch (IOException | RuntimeException unavailable) {
        // No guessed coverage can replace a failed current-inventory preflight.
      }
      if (preflight == null || preflight.coverageLsn() == null
          || preflight.coverageLsn().getSegment() < 0
          || preflight.coverageLsn().getPosition() < 0
          || preflight.effectiveBoundary() < 0 || preflight.effectiveBoundary() > fixed) {
        tracker.invalidate();
        tracker.mergeBack(handle.seal);
        try {
          ChangedPageTrackerFile.invalidate(sideFile, tracker, files);
        } catch (Error cleanupFailure) {
          // Completion-only reporting preserves the durable output's success.
        }
        ChangedPageTrackerFile.warn(files, "Backup completion coverage unavailable: ", sideFile,
            NO_SAFE_COVERAGE);
        return Status.COVERAGE_UNAVAILABLE;
      }
      tracker.stageCompletion(handle.seal, facts.fullRead());
      try {
        tracker.beforeCompletionCapture();
        ChangedPageTrackerFile.SaveResult result;
        try {
          result = ChangedPageTrackerFile.save(sideFile, tracker, preflight.coverageLsn(), files);
        } catch (Error publicationFailure) {
          return failureStatus(tracker);
        }
        if (result != ChangedPageTrackerFile.SaveResult.SAVED) {
          return failureStatus(tracker);
        }
        return tracker.completionInstalled(handle.seal)
            ? Status.SAVED_CONTINUITY : Status.SAVED_UNTRUSTED;
      } finally {
        tracker.clearCompletion();
      }
    } finally {
      order.unlock();
    }
  }

  private static Status failureStatus(ChangedPageTracker tracker) {
    return tracker.trustedFileMayExist()
        ? Status.SAVE_FAILED_INVALIDATION_FAILED : Status.SAVE_FAILED_INVALIDATED;
  }

  public static Status abandon(ChangedPageTracker tracker, Context context) {
    var handle = ownedHandle(tracker, context);
    if (handle == null) {
      return Status.NO_BOUNDARY;
    }
    tracker.mergeBack(handle.seal);
    return Status.MERGED_NOT_CONSUMED;
  }

  @Nullable private static SealHandle ownedHandle(ChangedPageTracker tracker, Context context) {
    var handle = context.sealHandle();
    if (handle != null && handle.owner != tracker) {
      tracker.invalidate();
      throw new IllegalArgumentException("Backup boundary belongs to another tracker");
    }
    return handle;
  }
}
