package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import javax.annotation.Nullable;

/**
 * Test-only bridge exposing the package-private apply-phase seams of the
 * {@code atomicoperations} package to tests living in other packages (YTDB-1178).
 *
 * <p>Two seams are re-exported:
 *
 * <ul>
 *   <li>{@link AtomicOperationBinaryTracking.PageApplyHook} installation on a live
 *       atomic operation, so an integration test can dictate the commit-time page-apply
 *       order and pause the writer mid-apply inside the epoch bracket;
 *   <li>the per-storage {@link ApplyPhaseEpoch} owned by {@link AtomicOperationsManager},
 *       so a test can make baseline-relative assertions on the epoch counters (never
 *       absolute ones — any read-only operation also brackets its own empty apply
 *       phase).
 * </ul>
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
   * operation. The operation must be an {@link AtomicOperationBinaryTracking} instance
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
   * Returns the apply-phase epoch of the given manager's storage. Tests must only make
   * baseline-relative assertions on the returned counters.
   */
  public static ApplyPhaseEpoch applyPhaseEpoch(final AtomicOperationsManager manager) {
    return manager.getApplyPhaseEpoch();
  }
}
