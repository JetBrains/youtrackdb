package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import javax.annotation.Nullable;

/**
 * Test-only bridge exposing the package-private apply-phase seams of the
 * {@code atomicoperations} package to tests living in other packages (YTDB-1178).
 *
 * <p>Three seams are re-exported:
 *
 * <ul>
 *   <li>{@link AtomicOperationBinaryTracking.PageApplyHook} installation on a live
 *       atomic operation, so an integration test can dictate the commit-time page-apply
 *       order and pause the writer in the existing apply step under the epoch.
 *       Installing a hook forces fallback and counts FORCED for a commit that changes pages.
 *       Page-set apply does not call it;
 *   <li>the page-set pause hook, which leaves protocol selection unchanged. A successful page-set
 *       commit that changes pages counts PAGE_SET. The apply hook or force override takes
 *       precedence, counts FORCED, and prevents the pause hook from running;
 *   <li>the per-logical-component {@link ApplyPhaseEpoch} owned by
 *       {@link AtomicOperationsManager}, so a test can make baseline-relative assertions.
 * </ul>
 *
 * <p>Commits without page changes or file creation/deletion count ZERO_CHANGE with either seam
 * installed. File creation/deletion counts FILE_LIFECYCLE, or FORCED with the page-apply hook.
 */
public final class AtomicOperationTestBridge {

  private AtomicOperationTestBridge() {
  }

  /**
   * Public mirror of the package-private
   * {@link AtomicOperationBinaryTracking.PageApplyHook} test seam. See that interface
   * for the full contract.
   */
  public interface TestPageApplyHook {

    /**
     * Returns the order in which the given changed pages of {@code fileId} should be
     * applied, or {@code null} to keep the default order. The returned array must be a
     * permutation of {@code pageIndexes}.
     */
    @Nullable default long[] orderPageApplications(final long fileId, final long[] pageIndexes) {
      return null;
    }

    /**
     * Called immediately before the changes for {@code (fileId, pageIndex)} are applied
     * to the shared read cache. May block to pause the writer mid-apply.
     */
    default void beforePageApply(final long fileId, final long pageIndex) {
    }
  }

  /**
   * Installs (or clears, with {@code null}) a page-apply hook on the given atomic
   * operation. Installing a non-null hook forces the existing apply step under the
   * epoch. Clearing the hook leaves the fallback override in place.
   * The operation must be an {@link AtomicOperationBinaryTracking} instance
   * (the only production implementation, produced by
   * {@link AtomicOperationsManager#startAtomicOperation()}).
   */
  public static void installPageApplyHook(
      final AtomicOperation operation, @Nullable final TestPageApplyHook hook) {
    final var tracking = (AtomicOperationBinaryTracking) operation;
    if (hook == null) {
      tracking.setPageApplyHook(null);
      return;
    }
    tracking.setPageApplyHook(new AtomicOperationBinaryTracking.PageApplyHook() {
      @Override
      public long[] orderPageApplications(final long fileId, final long[] pageIndexes) {
        return hook.orderPageApplications(fileId, pageIndexes);
      }

      @Override
      public void beforePageApply(final long fileId, final long pageIndex) {
        hook.beforePageApply(fileId, pageIndex);
      }
    });
  }

  /**
   * Observes the sorted commit set after all frame locks and after the first publish/unlock.
   * The array and entries are borrowed, read-only observations. Do not load pages or take
   * locks in the callback. A thrown failure follows reservation cleanup, not fallback.
   */
  public interface TestPageSetPauseHook {
    void pause(CacheEntry[] pages, boolean firstPagePublished);
  }

  /**
   * Installs or clears the pause seam without forcing fallback. A successful page-set commit
   * that changes pages counts PAGE_SET. Zero-change commits count ZERO_CHANGE. The apply hook
   * or force override takes precedence and prevents this hook from running.
   */
  public static void installPageSetPauseHook(AtomicOperation operation,
      @Nullable TestPageSetPauseHook hook) {
    ((AtomicOperationBinaryTracking) operation).setPageSetPauseHook(
        hook == null ? null : hook::pause);
  }

  /** Forces this operation to use the fallback protocol and its page-apply hook. */
  public static void forceEpochApply(final AtomicOperation operation) {
    ((AtomicOperationBinaryTracking) operation).setForceEpochApplyForTesting(true);
  }

  /** Returns the apply epoch for one logical lock-name domain. */
  public static ApplyPhaseEpoch applyPhaseEpoch(
      final AtomicOperationsManager manager, final String lockName) {
    return manager.getApplyPhaseEpoch(lockName);
  }
}
