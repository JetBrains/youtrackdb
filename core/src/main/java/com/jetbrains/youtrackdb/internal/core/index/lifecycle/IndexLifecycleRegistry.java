package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Storage-scoped lifecycle cells keyed by durable index descriptor identity. */
public final class IndexLifecycleRegistry {

  private final ConcurrentHashMap<RID, Registration> cells = new ConcurrentHashMap<>();
  private final Supplier<StorageLineageIdentity> currentLineage;

  public IndexLifecycleRegistry(Supplier<StorageLineageIdentity> currentLineage) {
    this.currentLineage = Objects.requireNonNull(currentLineage, "currentLineage");
  }

  public IndexLifecycleCell getOrCreate(
      @Nonnull RID descriptorIdentity, @Nonnull IndexLifecycleSnapshot initialSnapshot) {
    if (!descriptorIdentity.equals(initialSnapshot.buildState().descriptorIdentity())) {
      throw new IllegalArgumentException("The lifecycle snapshot belongs to another descriptor");
    }

    while (true) {
      var registration = cells.compute(descriptorIdentity, (ignored, existing) -> {
        var lineage = currentLineage.get();
        if (existing != null && existing.lineageIdentity().equals(lineage)) {
          return existing;
        }
        if (existing != null) {
          existing.cell().retire();
        }
        return new Registration(lineage, new IndexLifecycleCell(initialSnapshot));
      });
      if (registration.lineageIdentity().equals(currentLineage.get())) {
        return registration.cell();
      }
      registration.cell().retire();
      cells.remove(descriptorIdentity, registration);
    }
  }

  /** Publishes durable recovery state while preserving a current-lineage cell. */
  public IndexLifecycleCell recover(
      @Nonnull RID descriptorIdentity, @Nonnull IndexLifecycleSnapshot recoveredSnapshot) {
    if (!descriptorIdentity.equals(recoveredSnapshot.buildState().descriptorIdentity())) {
      throw new IllegalArgumentException("The lifecycle snapshot belongs to another descriptor");
    }

    while (true) {
      var registration = cells.compute(
          descriptorIdentity,
          (ignored, existing) -> {
            var lineage = currentLineage.get();
            if (existing != null && existing.lineageIdentity().equals(lineage)) {
              existing.cell().recover(recoveredSnapshot);
              return existing;
            }
            if (existing != null) {
              existing.cell().retire();
            }
            return new Registration(lineage, new IndexLifecycleCell(recoveredSnapshot));
          });
      if (registration.lineageIdentity().equals(currentLineage.get())) {
        return registration.cell();
      }
      registration.cell().retire();
      cells.remove(descriptorIdentity, registration);
    }
  }

  @Nullable public IndexLifecycleCell get(@Nonnull RID descriptorIdentity) {
    while (true) {
      var registration = cells.get(descriptorIdentity);
      if (registration == null) {
        return null;
      }
      if (registration.lineageIdentity().equals(currentLineage.get())) {
        return registration.cell();
      }
      registration.cell().retire();
      cells.remove(descriptorIdentity, registration);
    }
  }

  public void remove(@Nonnull RID descriptorIdentity) {
    cells.remove(descriptorIdentity);
  }

  /** Drops all cells created under a previous storage lineage. */
  public void dropStale() {
    var lineage = currentLineage.get();
    cells.forEach((descriptorIdentity, ignored) -> cells.computeIfPresent(descriptorIdentity,
        (descriptor, registration) -> {
          if (registration.lineageIdentity().equals(lineage)) {
            return registration;
          }
          registration.cell().retire();
          return null;
        }));
  }

  private record Registration(
      StorageLineageIdentity lineageIdentity, IndexLifecycleCell cell) {

    private Registration {
      Objects.requireNonNull(lineageIdentity, "lineageIdentity");
      Objects.requireNonNull(cell, "cell");
    }
  }
}
