package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Covers registry fencing against the current storage lineage. */
public class IndexLifecycleRegistryTest {

  /** Every lookup drops a cell created under a stale lineage. */
  @Test
  public void lookupDropsStaleCellAfterLineageRotation() {
    var lineage = new AtomicReference<>(StorageLineageIdentity.random());
    var registry = new IndexLifecycleRegistry(lineage::get);
    var descriptor = new RecordId(7, 9);
    var first = registry.getOrCreate(descriptor, snapshot(descriptor));

    assertSame(first, registry.get(descriptor));
    lineage.set(StorageLineageIdentity.random());
    assertNull(registry.get(descriptor));
    assertThrows(IllegalStateException.class, first::get);

    var replacement = registry.getOrCreate(descriptor, snapshot(descriptor));
    assertNotSame(first, replacement);
  }

  /** Restore cleanup removes every cell carrying an outdated lineage. */
  @Test
  public void dropStaleRemovesCellsAfterLineageRotation() {
    var lineage = new AtomicReference<>(StorageLineageIdentity.random());
    var registry = new IndexLifecycleRegistry(lineage::get);
    var descriptor = new RecordId(7, 9);
    var stale = registry.getOrCreate(descriptor, snapshot(descriptor));

    lineage.set(StorageLineageIdentity.random());
    registry.dropStale();

    assertNull(registry.get(descriptor));
    assertThrows(IllegalStateException.class, stale::snapshot);
  }

  /** Registration retries when lineage rotates between creation and publication. */
  @Test
  public void registrationRetriesAfterLineageRotatesDuringPublication() {
    var firstLineage = StorageLineageIdentity.random();
    var currentLineage = StorageLineageIdentity.random();
    var lineageReads = new AtomicInteger();
    var registry = new IndexLifecycleRegistry(
        () -> lineageReads.getAndIncrement() == 0 ? firstLineage : currentLineage);
    var descriptor = new RecordId(7, 9);
    var initial = snapshot(descriptor);

    var cell = registry.getOrCreate(descriptor, initial);

    assertEquals(4, lineageReads.get());
    assertSame(initial, cell.snapshot());
    assertSame(cell, registry.get(descriptor));
  }

  /** Stale cleanup retains the holder belonging to the current lineage. */
  @Test
  public void dropStaleKeepsCurrentLineageCell() {
    var lineage = new AtomicReference<>(StorageLineageIdentity.random());
    var registry = new IndexLifecycleRegistry(lineage::get);
    var descriptor = new RecordId(7, 9);
    var current = registry.getOrCreate(descriptor, snapshot(descriptor));

    registry.dropStale();

    assertSame(current, registry.get(descriptor));
    assertSame(IndexLifecycle.EXISTS, current.get());
  }

  /** A stale registration request cannot replace the holder serving the current lineage. */
  @Test
  public void staleRegistrationRequestKeepsCurrentLineageHolder() {
    var lineage = new AtomicReference<>(StorageLineageIdentity.random());
    var registry = new IndexLifecycleRegistry(lineage::get);
    var descriptor = new RecordId(7, 9);
    var staleRequest = snapshot(descriptor);
    var previous = registry.getOrCreate(descriptor, staleRequest);

    lineage.set(StorageLineageIdentity.random());
    var currentRequest = snapshot(descriptor);
    var current = registry.getOrCreate(descriptor, currentRequest);
    var staleCallerResult = registry.getOrCreate(descriptor, staleRequest);

    assertSame(current, staleCallerResult);
    assertSame(current, registry.get(descriptor));
    assertSame(currentRequest, current.snapshot());
    assertThrows(IllegalStateException.class, previous::get);
  }

  private static IndexLifecycleSnapshot snapshot(RecordId descriptor) {
    return new IndexLifecycleSnapshot(IndexBuildState.initial(descriptor), 0);
  }
}
