package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import java.util.Arrays;

/**
 * Tracks page frames and their optimistic stamps accumulated during a multi-page read
 * operation (e.g., B-tree traversal). Validation checks cached-frame identity, per-page
 * stamps, and the apply-phase epoch:
 *
 * <p>Each recorded entry must still be alive and point to the recorded frame. Eviction
 * alone need not invalidate a stamp.
 *
 * <ul>
 *   <li><b>Per-page stamps</b> — a purely <em>temporal</em> check: each stamp proves the
 *       individual page was not modified between being read and being validated. Stamps
 *       alone do NOT guarantee that the pages form a mutually consistent snapshot: a
 *       fallback commit applies its changes page-by-page, so a reader overlapping
 *       that apply window can see a mix of pre- and post-commit pages while every stamp
 *       stays valid.
 *   <li><b>Apply-phase epoch</b> — detects overlapping fallback commits. {@link #reset()}
 *       captures the logical component's {@link ApplyPhaseEpoch} counters and
 *       {@link #validateOrThrow()} re-checks them after the stamp loop; the read fails if
 *       any fallback apply phase overlapped the read window. Page-set commits lock every
 *       frame before the first change. Frame stamps, cached-frame identity and the
 *       pinned-load rules protect readers against those commits.
 * </ul>
 *
 * <p>Stored in {@code AtomicOperation} and reused across optimistic read attempts within
 * the same transaction. {@link #reset()} is called before each attempt.
 *
 * <p>Not thread-safe — each AtomicOperation belongs to a single thread.
 */
public final class OptimisticReadScope {

  private static final int INITIAL_CAPACITY = 8;

  // Private default for standalone identity and stamp checks. Production attempts replace the active
  // epoch at reset with their StorageComponent's logical lock-domain epoch.
  private final ApplyPhaseEpoch defaultApplyPhaseEpoch;
  private ApplyPhaseEpoch applyPhaseEpoch;

  private PageFrame[] frames;
  private CacheEntry[] entries;
  private long[] stamps;
  private int count;

  // Epoch counters captured by reset(); compared against the live epoch in
  // validateOrThrow() to detect an overlapping commit-time apply phase.
  private long enterSeqAtCapture;
  private long exitSeqAtCapture;

  // Production state shared by nested attempts on this operation.
  private int attemptDepth;
  private boolean failed;
  private Throwable loadFailure;
  private boolean[] pinned = new boolean[INITIAL_CAPACITY];

  /**
   * Convenience constructor for standalone use (tests, tooling) where no epoch is shared
   * with concurrent writers: allocates a private {@link ApplyPhaseEpoch} that no writer
   * ever bumps, so epoch validation trivially passes. Cached-frame identity and per-page
   * stamps are still checked. Production code supplies a component epoch to
   * {@link #reset(ApplyPhaseEpoch)}.
   */
  public OptimisticReadScope() {
    this(new ApplyPhaseEpoch());
  }

  public OptimisticReadScope(ApplyPhaseEpoch applyPhaseEpoch) {
    assert applyPhaseEpoch != null : "ApplyPhaseEpoch must not be null";
    this.defaultApplyPhaseEpoch = applyPhaseEpoch;
    this.applyPhaseEpoch = applyPhaseEpoch;
    this.frames = new PageFrame[INITIAL_CAPACITY];
    this.entries = new CacheEntry[INITIAL_CAPACITY];
    this.stamps = new long[INITIAL_CAPACITY];
    this.count = 0;
  }

  /**
   * Records a page frame, its optimistic stamp, and its cached-frame identity. Called by
   * {@code StorageComponent.loadPageOptimistic()} for each page accessed.
   */
  public void record(PageFrame frame, long stamp, CacheEntry entry) {
    java.util.Objects.requireNonNull(entry, "Cache entry must not be null");
    assert frame != null : "PageFrame must not be null";
    assert stamp != 0 : "Stamp must not be zero (exclusive lock was held)";

    if (count == frames.length) {
      grow();
    }
    pinned[count] = false;
    entries[count] = entry;
    frames[count] = frame;
    stamps[count] = stamp;
    count++;
  }

  /**
   * Validates cached-frame identity and the stamp for every recorded page, then the
   * apply-phase epoch. Throws {@link OptimisticReadFailedException} if an entry is no longer
   * alive or no longer holds its recorded frame, if a stamp is invalid, or if a commit-time
   * fallback apply phase overlapped the read window. Eviction alone need not invalidate stamps.
   */
  public void validateOrThrow() {
    checkFailed();
    for (int i = 0; i < count; i++) {
      validatePageOrThrow(frames[i], stamps[i], entries[i]);
    }
    // Epoch check placement: the essential requirement is only that this check runs
    // AFTER all page reads of the attempt — which holds structurally, because the
    // caller invokes validateOrThrow() once the optimistic lambda has finished reading.
    // Both PageFrame.validate() (StampedLock state read behind an acquire fence) and
    // ApplyPhaseEpoch.enterSeq() (volatile read) are synchronization actions whose
    // mutual order matches program order, so running the epoch check after the stamp
    // loop is the natural, conservative placement — not a load-bearing constraint.
    // Validity requires both that no apply phase was in flight at capture time
    // (enter == exit at capture) and that none entered since (live enterSeq unchanged).
    if (enterSeqAtCapture != exitSeqAtCapture
        || applyPhaseEpoch.enterSeq() != enterSeqAtCapture) {
      throw OptimisticReadFailureCounters.failure(OptimisticReadFailureCounters.Cause.EPOCH);
    }
  }

  /**
   * Validates cached-frame identity and the stamp for the most recently recorded page.
   * Used during traversals to catch stale pointers before following a child pointer read
   * from a potentially evicted page. Does not check the apply-phase epoch.
   *
   * <p>Requires at least one recorded page. An assertion checks this precondition.
   *
   * @throws OptimisticReadFailedException if the last entry is no longer alive or no longer
   *                                      holds its recorded frame, or the last stamp is invalid
   * @throws AssertionError if no page has been recorded and assertions are enabled
   */
  public void validateLastOrThrow() {
    checkFailed();
    assert count > 0 : "No stamps recorded — cannot validate last";

    validatePageOrThrow(frames[count - 1], stamps[count - 1], entries[count - 1]);
  }

  /**
   * Checks identity before the stamp. An entry never becomes alive again after freeze.
   * If eviction follows the liveness read, a passing stamp proves the content was intact at
   * that read. Pool reuse between the identity read and stamp validation invalidates the stamp.
   * File close, clearFile, and file delete must not overlap an optimistic read of the
   * same file. These lifecycle operations can remove the map entry before freezing it.
   */
  public static void validatePageOrThrow(PageFrame frame, long stamp, CacheEntry entry) {
    final var pointer = entry.getCachePointer();
    if (!entry.isAlive() || pointer == null || pointer.getPageFrame() != frame) {
      throw OptimisticReadFailureCounters.failure(
          OptimisticReadFailureCounters.Cause.FRAME_IDENTITY);
    }
    if (!frame.validate(stamp)) {
      throw OptimisticReadFailureCounters.failure(OptimisticReadFailureCounters.Cause.STAMP);
    }
  }

  /**
   * Resets the scope for reuse and captures the apply-phase epoch for the upcoming read
   * attempt. Nulls frame references up to the current count to avoid preventing garbage
   * collection of evicted PageFrames.
   *
   * <p>Contract: this method must never throw — it is invoked outside the try/fallback
   * block of {@code StorageComponent.executeOptimisticStorageRead}, so an exception here
   * would escape past the pinned fallback instead of triggering it.
   */
  public void reset() {
    reset(defaultApplyPhaseEpoch);
  }

  /**
   * Resets this reusable scope for an optimistic attempt in one logical component domain.
   * Sequential attempts may use different domains, but one attempt remains single-domain.
   */
  public void reset(ApplyPhaseEpoch componentApplyPhaseEpoch) {
    assert componentApplyPhaseEpoch != null : "ApplyPhaseEpoch must not be null";
    if (attemptDepth > 0) {
      // A nested scope cannot replace either the stamps or the outer epoch capture.
      if (applyPhaseEpoch != componentApplyPhaseEpoch) {
        fail(OptimisticReadFailureCounters.Cause.EPOCH);
      }
      return;
    }
    failed = false;
    loadFailure = null;
    applyPhaseEpoch = componentApplyPhaseEpoch;

    // Capture the epoch. Exit is read BEFORE enter deliberately: if the two values are
    // equal, every apply phase that had entered by the time enterSeq was read had
    // already exited before the capture began, so no apply was in flight at capture
    // time. (Enter-first could not distinguish that from an apply spanning the capture.)
    exitSeqAtCapture = applyPhaseEpoch.exitSeq();
    enterSeqAtCapture = applyPhaseEpoch.enterSeq();

    // Null out frame references to prevent GC retention
    Arrays.fill(frames, 0, count, null);
    Arrays.fill(entries, 0, count, null);
    count = 0;
  }

  /** Starts an attempt without clearing an enclosing attempt's state. */
  public void beginAttempt(ApplyPhaseEpoch epoch) {
    reset(epoch);
    attemptDepth++;
  }

  /** Ends one nesting level. The outermost exit clears the failure state. */
  public void endAttempt() {
    if (--attemptDepth == 0) {
      failed = false;
      loadFailure = null;
    }
  }

  /**
   * Latches any load Error or a post-load RuntimeException that a locked read may not repeat.
   * Only the outermost wrapper reports it, after traversal locals have been cleaned up.
   */
  public void preserveLoadFailure(Throwable failure) {
    failed = true;
    if (loadFailure == null) {
      loadFailure = failure;
    }
  }

  /** Reports the original kept load failure at the outermost attempt boundary only. */
  public void checkLoadFailure() {
    if (loadFailure instanceof RuntimeException runtimeFailure) {
      throw runtimeFailure;
    }
    if (loadFailure instanceof Error error) {
      throw error;
    }
  }

  public boolean isOutermostAttempt() {
    return attemptDepth == 1;
  }

  public boolean isAttemptActive() {
    return attemptDepth > 0;
  }

  public boolean isFailed() {
    return failed;
  }

  /** Counts the first pinned-load failure only. Final checks do not count it again. */
  public void fail(OptimisticReadFailureCounters.Cause cause) {
    if (!failed) {
      failed = true;
      OptimisticReadFailureCounters.failure(cause);
    }
  }

  public void checkFailed() {
    // Traversals can still hold already-closed locals. Do not expose the original throwable here.
    if (failed) {
      throw OptimisticReadFailedException.INSTANCE;
    }
  }

  public void recordPinned(PageFrame frame, long stamp, CacheEntry entry) {
    record(frame, stamp, entry);
    pinned[count - 1] = true;
  }

  public boolean isPinned(int index) {
    return pinned[index];
  }

  /**
   * Returns the number of page frames currently tracked.
   */
  public int count() {
    return count;
  }

  /**
   * Returns the page frame at the given index. Used after validation succeeds to
   * record optimistic accesses in the read cache's frequency sketch.
   */
  public PageFrame getFrame(int index) {
    assert index >= 0 && index < count : "Index out of bounds: " + index;
    return frames[index];
  }

  private void grow() {
    int newCapacity = frames.length * 2;
    frames = Arrays.copyOf(frames, newCapacity);
    entries = Arrays.copyOf(entries, newCapacity);
    stamps = Arrays.copyOf(stamps, newCapacity);
    pinned = Arrays.copyOf(pinned, newCapacity);
  }
}
