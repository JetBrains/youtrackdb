package com.jetbrains.youtrackdb.internal.core.storage.config;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stable per-unit publication targets. Null means missing, not a live all-not-created unit.
 * Readers use these immutable values inside their own component optimistic attempt.
 */
final class CommittedFileStates<T> {

  private final ConcurrentHashMap<String, Target<T>> targets = new ConcurrentHashMap<>();

  T get(String key) {
    var target = targets.get(key);
    return target == null ? null : target.value.get();
  }

  void prepare(String key, T state, AtomicOperation op, AbstractStorage storage,
      String... lockNames) {
    // All metadata callers retain the storage write lock through operation completion.
    // It excludes component writers too, without acquiring a lock that could wait on commit.
    storage.requireFileStateWriterExclusion();
    var domains = new StorageComponent[lockNames.length];
    for (int i = 0; i < domains.length; i++) {
      // These handles carry epoch identities only. They are never used as component locks.
      domains[i] = new StorageComponent(storage, lockNames[i], "", lockNames[i], true) {
      };
    }
    var target = targets.computeIfAbsent(key, ignored -> new Target<>(domains));
    op.preparePublication(target.value, state, domains);
  }

  /** Removes even an unreadable record using the domains retained with its copy target. */
  void prepareRemoval(String key, AtomicOperation op, AbstractStorage storage) {
    storage.requireFileStateWriterExclusion();
    var target = targets.get(key);
    if (target != null) {
      op.preparePublication(target.value, null, target.domains);
    }
  }

  private static final class Target<T> {

    private final AtomicReference<T> value = new AtomicReference<>();
    private final StorageComponent[] domains;

    private Target(StorageComponent[] domains) {
      this.domains = domains;
    }
  }
}
