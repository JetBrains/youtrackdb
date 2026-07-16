package com.jetbrains.youtrackdb.internal.core.storage.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-component epoch bracketing the commit-time page-apply phase of atomic operations
 * for the files of one top-level storage component.
 *
 * <p>Optimistic multi-page reads validate each page individually via {@link
 * com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame} stamps, but per-page
 * stamps are a purely <em>temporal</em> check: they prove each page was not modified while
 * it was being read, not that the set of pages formed a mutually consistent snapshot. A
 * committing transaction applies its page changes to the read cache one page at a time
 * (in hash order, see {@code AtomicOperationBinaryTracking.commitChanges}), so a reader
 * overlapping that apply window can observe a mix of pre- and post-commit pages with every
 * individual stamp still valid — e.g., a B-tree leaf already shrunk by a split while its
 * new right sibling is not yet visible, producing a false "key absent" result.
 *
 * <p>This class closes that gap with two monotonic counters:
 *
 * <ul>
 *   <li>{@code applyEnterSeq} — incremented immediately <em>before</em> a committing
 *       operation that mutates this component's files starts mutating shared cache state
 *       (file deletion/creation/truncation and the per-page apply loop);
 *   <li>{@code applyExitSeq} — incremented immediately <em>after</em> that section
 *       completes (in a {@code finally} block, so an escaping exception cannot leave the
 *       epoch permanently "in apply" and shut down optimistic reads).
 * </ul>
 *
 * <p>Two independent counters are used instead of a single odd/even parity word because
 * apply phases bumping the <em>same</em> epoch may overlap: an epoch instance is shared
 * between a parent component and its sub-components (e.g., a collection and its position
 * map), and two commits that locked different components of that family can apply
 * concurrently. With parity, a second writer entering while the first is still applying
 * would flip the bit back to "idle" and mask the overlap. With separate counters the idle
 * condition is {@code enterSeq == exitSeq}, which holds only when no apply phase is in
 * flight.
 *
 * <p>Reader protocol (see {@link OptimisticReadScope}):
 *
 * <ol>
 *   <li>At {@link OptimisticReadScope#reset(ApplyPhaseEpoch)}, capture {@code exitSeq}
 *       first, then
 *       {@code enterSeq}. Reading exit before enter is deliberate: if the two captured
 *       values are equal, every apply that had entered by the time {@code enterSeq} was
 *       read had already exited <em>before</em> the capture started, so no apply phase
 *       was in flight at capture time.
 *   <li>After the per-frame stamp validation loop in
 *       {@link OptimisticReadScope#validateOrThrow()}, re-read {@code enterSeq}. The read
 *       is valid only if {@code enterAtCapture == exitAtCapture} (no apply in flight at
 *       capture) and {@code enterSeq() == enterAtCapture} (no apply entered since).
 * </ol>
 *
 * <p>Memory ordering: all accesses go through {@link AtomicLong}, i.e., volatile
 * reads/writes. The writer's {@code enterApplyPhase()} volatile increment happens-before
 * any page mutation it performs, and every mutation happens-before its
 * {@code exitApplyPhase()} increment; a reader that observes {@code enter == exit} with an
 * unchanged {@code enterSeq} therefore observed either none or all of any committed
 * apply's effects — never a partial mix.
 *
 * <p>Ownership (YTDB-1203): one instance per <em>top-level</em>
 * {@code StorageComponent}; sub-components (position map, free-space map, dirty-page
 * bit set) share their parent collection's instance so one optimistic read spanning
 * parent and sub-component files validates a single epoch. Every file id a component
 * creates or opens is mapped to the component's epoch in the per-storage
 * {@link ComponentEpochRegistry} (owned by {@code AtomicOperationsManager}); committing
 * operations resolve their mutated file ids through that registry and bracket every
 * distinct resolved epoch. The epoch is per component rather than per storage so a
 * commit into one component does not spuriously invalidate concurrent optimistic reads
 * of unrelated components, and deliberately <em>not</em> engine-global (on
 * {@code ReadCache}) because on the disk engine a single read cache is shared by all
 * storages of the engine.
 */
public final class ApplyPhaseEpoch {

  private final AtomicLong applyEnterSeq = new AtomicLong();
  private final AtomicLong applyExitSeq = new AtomicLong();

  /**
   * Marks the start of a commit-time apply phase. Must be paired with exactly one
   * {@link #exitApplyPhase()} call in a {@code finally} block.
   */
  public void enterApplyPhase() {
    applyEnterSeq.incrementAndGet();
  }

  /**
   * Marks the end of a commit-time apply phase. Called from a {@code finally} block so
   * that an exception escaping the apply section cannot leave the epoch permanently in
   * the "apply in flight" state (which would disable optimistic reads for good).
   */
  public void exitApplyPhase() {
    applyExitSeq.incrementAndGet();
  }

  /**
   * Returns the current enter sequence (volatile read).
   */
  public long enterSeq() {
    return applyEnterSeq.get();
  }

  /**
   * Returns the current exit sequence (volatile read).
   */
  public long exitSeq() {
    return applyExitSeq.get();
  }
}
