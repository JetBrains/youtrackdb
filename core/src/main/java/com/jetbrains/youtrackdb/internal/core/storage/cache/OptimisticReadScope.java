package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import java.util.Arrays;

/**
 * Tracks page frames and their optimistic stamps accumulated during a multi-page read
 * operation (e.g., B-tree traversal). Validation is two-layered:
 *
 * <ul>
 *   <li><b>Per-page stamps</b> — a purely <em>temporal</em> check: each stamp proves the
 *       individual page was not modified between being read and being validated. Stamps
 *       alone do NOT guarantee that the pages form a mutually consistent snapshot: a
 *       committing transaction applies its changes page-by-page, so a reader overlapping
 *       that apply window can see a mix of pre- and post-commit pages while every stamp
 *       stays valid.
 *   <li><b>Apply-phase epoch</b> — the cross-page consistency guarantee. {@link #reset()}
 *       captures the storage's {@link ApplyPhaseEpoch} counters and
 *       {@link #validateOrThrow()} re-checks them after the stamp loop; the read fails if
 *       any commit-time apply phase overlapped the read window.
 * </ul>
 *
 * <p>Stored in {@code AtomicOperation} and reused across optimistic read attempts within
 * the same transaction. {@link #reset()} is called before each attempt.
 *
 * <p>Not thread-safe — each AtomicOperation belongs to a single thread.
 */
public final class OptimisticReadScope {

  private static final int INITIAL_CAPACITY = 8;

  // Per-storage apply-phase epoch shared with all writers of the same storage.
  private final ApplyPhaseEpoch applyPhaseEpoch;

  private PageFrame[] frames;
  private long[] stamps;
  private int count;

  // Epoch counters captured by reset(); compared against the live epoch in
  // validateOrThrow() to detect an overlapping commit-time apply phase.
  private long enterSeqAtCapture;
  private long exitSeqAtCapture;

  // Nesting-detection state (-ea builds only). attemptActive is flipped exclusively
  // by enterAttempt()/exitAttempt(), which are invoked from assert statements in
  // StorageComponent.executeOptimisticStorageRead — with assertions disabled neither
  // runs and both fields stay false, so production builds pay only the dead branch
  // in reset(). A nested executeOptimisticStorageRead call inside an optimistic
  // lambda would wipe the outer scope's stamps via reset(), silently voiding the
  // outer validation; this state machine surfaces that as an AssertionError.
  private boolean attemptActive;
  private boolean nestedResetDetected;

  /**
   * Convenience constructor for standalone use (tests, tooling) where no epoch is shared
   * with concurrent writers: allocates a private {@link ApplyPhaseEpoch} that no writer
   * ever bumps, so epoch validation trivially passes and only per-page stamp validation
   * applies. Production code must pass the storage-wide epoch owned by
   * {@code AtomicOperationsManager} (via {@code AtomicOperationBinaryTracking}), otherwise
   * commit-time apply phases would be invisible to this scope.
   */
  public OptimisticReadScope() {
    this(new ApplyPhaseEpoch());
  }

  public OptimisticReadScope(ApplyPhaseEpoch applyPhaseEpoch) {
    assert applyPhaseEpoch != null : "ApplyPhaseEpoch must not be null";
    this.applyPhaseEpoch = applyPhaseEpoch;
    this.frames = new PageFrame[INITIAL_CAPACITY];
    this.stamps = new long[INITIAL_CAPACITY];
    this.count = 0;
  }

  /**
   * Records a page frame and its optimistic stamp. Called by
   * {@code StorageComponent.loadPageOptimistic()} for each page accessed.
   */
  public void record(PageFrame frame, long stamp) {
    assert frame != null : "PageFrame must not be null";
    assert stamp != 0 : "Stamp must not be zero (exclusive lock was held)";

    if (count == frames.length) {
      grow();
    }
    frames[count] = frame;
    stamps[count] = stamp;
    count++;
  }

  /**
   * Validates all accumulated stamps, then the apply-phase epoch. Throws
   * {@link OptimisticReadFailedException} if any stamp is invalid (page was evicted or
   * modified) or if a commit-time apply phase overlapped the read window (the pages may
   * individually be intact yet form an inconsistent mix of pre- and post-commit state).
   */
  public void validateOrThrow() {
    for (int i = 0; i < count; i++) {
      if (!frames[i].validate(stamps[i])) {
        OptimisticReadStats.onStampAbort();
        throw OptimisticReadFailedException.INSTANCE;
      }
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
      OptimisticReadStats.onEpochAbort();
      throw OptimisticReadFailedException.INSTANCE;
    }
  }

  /**
   * Validates only the most recently recorded stamp. Used during traversals to catch stale
   * pointers early — before following a child pointer read from a potentially evicted page.
   *
   * @throws OptimisticReadFailedException if the last stamp is invalid
   * @throws IllegalStateException         if no stamps have been recorded
   */
  public void validateLastOrThrow() {
    assert count > 0 : "No stamps recorded — cannot validate last";

    if (!frames[count - 1].validate(stamps[count - 1])) {
      OptimisticReadStats.onStampAbort();
      throw OptimisticReadFailedException.INSTANCE;
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
    // Nesting detection (-ea builds only): attemptActive can be true here only when a
    // surrounding executeOptimisticStorageRead is still in flight — i.e., this reset
    // belongs to a nested attempt that is about to wipe the outer scope's stamps.
    // Record the violation; the outer exitAttempt() assert will surface it.
    if (attemptActive) {
      nestedResetDetected = true;
    }

    // Capture the epoch. Exit is read BEFORE enter deliberately: if the two values are
    // equal, every apply phase that had entered by the time enterSeq was read had
    // already exited before the capture began, so no apply was in flight at capture
    // time. (Enter-first could not distinguish that from an apply spanning the capture.)
    exitSeqAtCapture = applyPhaseEpoch.exitSeq();
    enterSeqAtCapture = applyPhaseEpoch.enterSeq();

    // Null out frame references to prevent GC retention
    Arrays.fill(frames, 0, count, null);
    count = 0;
  }

  /**
   * Marks the start of an optimistic read attempt. Called only via {@code assert} from
   * {@code StorageComponent.executeOptimisticStorageRead} (zero cost with assertions
   * disabled). Returns {@code false} — failing the assert — if an attempt is already in
   * flight, i.e., a nested executeOptimisticStorageRead call was made from inside an
   * optimistic lambda.
   */
  public boolean enterAttempt() {
    if (attemptActive) {
      // Nested attempt: also latch the violation so the OUTER exitAttempt() fails.
      // The AssertionError raised here (inside the outer optimistic lambda) is
      // swallowed by the outer fallback catch, so the latch is what actually
      // surfaces the bug to the caller.
      nestedResetDetected = true;
      return false;
    }
    attemptActive = true;
    return true;
  }

  /**
   * Marks the end of an optimistic read attempt (successful or falling back). Called only
   * via {@code assert} from {@code StorageComponent.executeOptimisticStorageRead}.
   * Returns {@code false} — failing the assert — if the attempt was not well-formed:
   * either it was never entered, or a nested reset was detected while it was in flight.
   * Clears the detection state so a subsequent pinned fallback that legitimately starts
   * a fresh optimistic read does not trip a stale flag.
   */
  public boolean exitAttempt() {
    final boolean wellFormed = attemptActive && !nestedResetDetected;
    attemptActive = false;
    nestedResetDetected = false;
    return wellFormed;
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
    stamps = Arrays.copyOf(stamps, newCapacity);
  }
}
