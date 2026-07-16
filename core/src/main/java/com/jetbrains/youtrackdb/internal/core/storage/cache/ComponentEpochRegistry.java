package com.jetbrains.youtrackdb.internal.core.storage.cache;

import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-storage registry mapping every component-owned file id to the {@link ApplyPhaseEpoch}
 * of the top-level storage component that owns the file (YTDB-1203).
 *
 * <p>Keys are <em>external</em> composed file ids (storage id in the high 32 bits, see
 * {@code AbstractWriteCache.composeFileId}), matching the ids stored in component
 * {@code fileId} fields and reported by {@code PageFrame.getFileId()}, so entries of
 * different storages can never collide even if registries were ever merged.
 *
 * <p>Population: exclusively through the {@code StorageComponent.addFile}/{@code openFile}
 * funnel — every file a component creates or opens is mapped to that component's epoch
 * (sub-components register their files under the parent component's epoch instance).
 * Registration may run under the storage {@code stateLock} <em>read</em> side (e.g.,
 * {@code SharedLinkBagBTree} creation during normal transactions), so the registry is a
 * lock-free concurrent map and assumes no external locking.
 *
 * <p>Lifecycle invariants:
 *
 * <ul>
 *   <li><b>Entries are never removed.</b> The commit that deletes a file must itself
 *       resolve the deleted file id to the owner's epoch and bump it (see
 *       {@code AtomicOperationBinaryTracking.commitChanges}); removing the entry when the
 *       component is dropped — which happens <em>before</em> that commit applies — would
 *       make the deleting commit miss its own bump.
 *   <li><b>Overwrite on reuse.</b> The disk engine reuses the internal file id when a file
 *       with the same name is recreated after a delete; the recreating component's
 *       registration simply overwrites the stale mapping. (Accepted narrowing: a stale
 *       component reference surviving such a drop/recreate reads the new incarnation's
 *       pages under the dead epoch — such use-after-drop access is already semantically
 *       void and is excluded by the storage {@code stateLock} for live readers.)
 * </ul>
 */
public final class ComponentEpochRegistry {

  private final ConcurrentHashMap<Long, ApplyPhaseEpoch> epochsByFileId =
      new ConcurrentHashMap<>();

  /**
   * Standalone fallback (see {@link #uniform(ApplyPhaseEpoch)}): when non-null, every
   * file id without an explicit entry resolves to this epoch instead of {@code null}.
   * Always {@code null} for production registries.
   */
  @Nullable private final ApplyPhaseEpoch universalEpoch;

  /**
   * Creates a production registry: file ids resolve only through explicit
   * {@link #register} calls, and a miss returns {@code null} so
   * {@code AtomicOperationBinaryTracking.commitChanges} can fail loud on files created
   * or loaded outside the {@code StorageComponent} funnel.
   */
  public ComponentEpochRegistry() {
    this.universalEpoch = null;
  }

  private ComponentEpochRegistry(@Nonnull final ApplyPhaseEpoch universalEpoch) {
    this.universalEpoch = universalEpoch;
  }

  /**
   * Creates a standalone registry (tests, tooling) that resolves <em>every</em> file id
   * to the single given epoch. This mirrors the pre-per-component (storage-wide) epoch
   * semantics for atomic operations constructed outside a storage: commit-time epoch
   * resolution always succeeds and produces exactly one enter/exit pair per commit.
   * Production code must use {@link #ComponentEpochRegistry()} and the component funnel.
   */
  public static ComponentEpochRegistry uniform(@Nonnull final ApplyPhaseEpoch epoch) {
    assert epoch != null : "epoch must not be null";
    return new ComponentEpochRegistry(epoch);
  }

  /**
   * Maps {@code fileId} to the owning component's epoch, overwriting any previous
   * mapping (file-id reuse on same-name recreate). Never removes entries — see the
   * class-level lifecycle invariants.
   */
  public void register(final long fileId, @Nonnull final ApplyPhaseEpoch epoch) {
    assert epoch != null : "epoch must not be null";
    epochsByFileId.put(fileId, epoch);
  }

  /**
   * Returns the epoch guarding the given file, or {@code null} if the file was never
   * registered (production registries only; {@link #uniform(ApplyPhaseEpoch)} registries
   * fall back to their universal epoch and never return {@code null}).
   */
  @Nullable public ApplyPhaseEpoch epochFor(final long fileId) {
    final var epoch = epochsByFileId.get(fileId);
    return epoch != null ? epoch : universalEpoch;
  }
}
