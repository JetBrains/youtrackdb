package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import org.junit.Test;

/** Covers lifecycle persistence through one durable descriptor link. */
public class IndexBuildStateStorageBackendTest extends DbTestBase {

  /** A descriptor link reads exactly the lifecycle record named by the link. */
  @Test
  public void descriptorLinkReadsOneDurableSnapshot() {
    var descriptor = createDescriptor();
    var store = session.getStorage().getIndexBuildStateStore();
    var created = store.createInitial(descriptor, null);
    writeLifecycleLink(descriptor, created.identity());

    var read = store.read(descriptor, readLifecycleLink(descriptor));

    assertEquals(created.snapshot(), read);
    assertNotSame(created.snapshot(), read);
    assertEquals(IndexLifecycle.EXISTS, read.lifecycle());
    assertFalse(read.buildState().suspended());
  }

  /** A descriptor without a lifecycle link fails before any lifecycle record read. */
  @Test
  public void absentDescriptorLinkFailsClearly() {
    var descriptor = createDescriptor();
    var store = session.getStorage().getIndexBuildStateStore();

    var failure =
        assertThrows(IllegalStateException.class, () -> store.read(descriptor, null));

    assertEquals("Index descriptor has no lifecycle record link", failure.getMessage());
  }

  /** A descriptor link to a deleted or unallocated record fails clearly. */
  @Test
  public void linkToMissingLifecycleRecordFailsClearly() {
    var descriptor = createDescriptor();
    var collectionId =
        session
            .getStorage()
            .getCollectionIdByName(MetadataDefault.INDEX_BUILD_STATE_COLLECTION_NAME);
    var missing = new RecordId(collectionId, Long.MAX_VALUE - 7);
    session.disableLinkConsistencyCheck();
    try {
      writeLifecycleLink(descriptor, missing);
    } finally {
      session.enableLinkConsistencyCheck();
    }

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> session
                .getStorage()
                .getIndexBuildStateStore()
                .read(descriptor, readLifecycleLink(descriptor)));

    assertEquals("Linked index build state record is missing", failure.getMessage());
  }

  /** A present descriptor link rejects creation of a second lifecycle record. */
  @Test
  public void presentDescriptorLinkRejectsSecondLifecycleRecord() {
    var descriptor = createDescriptor();
    var store = session.getStorage().getIndexBuildStateStore();
    var created = store.createInitial(descriptor, null);
    writeLifecycleLink(descriptor, created.identity());

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> store.createInitial(descriptor, readLifecycleLink(descriptor)));

    assertEquals("Index descriptor already has a lifecycle record link", failure.getMessage());
  }

  /** Deletion removes the linked lifecycle record through the backend. */
  @Test
  public void deletionRemovesLifecycleRecord() {
    var fixture = createLinkedState();

    fixture.store().delete(fixture.lifecycle(), fixture.created().recordVersion());

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.store().read(fixture.descriptor(), fixture.lifecycle()));
    assertEquals("Linked index build state record is missing", failure.getMessage());
  }

  /** Deletion rejects an expected revision older than the durable record. */
  @Test
  public void staleRevisionCannotDeleteLifecycleRecord() {
    var fixture = createLinkedState();
    var published =
        fixture.store().publish(
            fixture.descriptor(),
            fixture.lifecycle(),
            fixture.created(),
            maintainedState(fixture.descriptor()));

    assertThrows(
        com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException.class,
        () -> fixture.store().delete(fixture.lifecycle(), fixture.created().recordVersion()));
    assertEquals(published, fixture.store().read(fixture.descriptor(), fixture.lifecycle()));
  }

  /** A rebuilt snapshot publishes under the value comparison that excludes owner epoch. */
  @Test
  public void ownerEpochExcludedValueComparisonPublishesRebuiltSnapshot() {
    var fixture = createLinkedState();
    var rebuiltExpected =
        new IndexLifecycleSnapshot(fixture.created().buildState(),
            fixture.created().recordVersion());

    var published =
        fixture.store().publish(
            fixture.descriptor(),
            fixture.lifecycle(),
            rebuiltExpected,
            maintainedState(fixture.descriptor()));

    assertEquals(IndexLifecycle.MAINTAINED, published.lifecycle());
    assertTrue(published.recordVersion() > fixture.created().recordVersion());
    assertEquals(published, fixture.store().read(fixture.descriptor(), fixture.lifecycle()));
  }

  /** A publication naming another descriptor fails before durable data changes. */
  @Test
  public void publicationRejectsAnotherDescriptor() {
    var fixture = createLinkedState();
    var otherDescriptor = new RecordId(0, 42);

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.store().publish(
                fixture.descriptor(),
                fixture.lifecycle(),
                fixture.created(),
                maintainedState(otherDescriptor)));

    assertEquals("Index build state belongs to another descriptor", failure.getMessage());
    assertEquals(
        fixture.created(), fixture.store().read(fixture.descriptor(), fixture.lifecycle()));
  }

  /** An older expected revision cannot replace a newer durable snapshot. */
  @Test
  public void olderExpectedRevisionCannotReplaceNewerSnapshot() {
    var fixture = createLinkedState();
    var published =
        fixture.store().publish(
            fixture.descriptor(),
            fixture.lifecycle(),
            fixture.created(),
            maintainedState(fixture.descriptor()));

    assertThrows(
        ConcurrentModificationException.class,
        () -> fixture.store().publish(
            fixture.descriptor(),
            fixture.lifecycle(),
            fixture.created(),
            maintainedState(fixture.descriptor())));
    assertEquals(published, fixture.store().read(fixture.descriptor(), fixture.lifecycle()));
  }

  private RID createDescriptor() {
    return session.computeInTx(transaction -> transaction.newEntity().getIdentity());
  }

  private void writeLifecycleLink(RID descriptor, RID lifecycle) {
    session.executeInTx(
        transaction -> transaction.loadEntity(descriptor).setLink(Index.LIFECYCLE_RECORD,
            lifecycle));
  }

  private RID readLifecycleLink(RID descriptor) {
    return session.computeInTx(
        transaction -> transaction.loadEntity(descriptor).getLink(Index.LIFECYCLE_RECORD));
  }

  private LinkedState createLinkedState() {
    var descriptor = createDescriptor();
    var store = session.getStorage().getIndexBuildStateStore();
    var created = store.createInitial(descriptor, null);
    writeLifecycleLink(descriptor, created.identity());
    return new LinkedState(descriptor, created.identity(), created.snapshot(), store);
  }

  private static IndexBuildState maintainedState(RID descriptor) {
    return new IndexBuildState(
        IndexBuildState.CURRENT_FORMAT_VERSION,
        descriptor,
        IndexLifecycle.MAINTAINED,
        UUID.randomUUID(),
        null,
        null,
        0,
        false,
        IndexBuildFailure.NONE,
        null);
  }

  private record LinkedState(
      RID descriptor,
      RID lifecycle,
      IndexLifecycleSnapshot created,
      IndexBuildStateStore store) {
  }
}
