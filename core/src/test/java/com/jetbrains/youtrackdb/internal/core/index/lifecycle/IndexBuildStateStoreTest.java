package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;

/** Covers durable store serialization, transitions, recovery, and deletion. */
public class IndexBuildStateStoreTest {

  private static final RID DESCRIPTOR = new RecordId(7, 9);

  /** Serialization preserves every durable field, including nullable fields. */
  @Test
  public void serializationRoundTripsCompleteState() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var state = new IndexBuildState(
        1, DESCRIPTOR, IndexLifecycle.MAINTAINED, UUID.randomUUID(), 4L, 17L, 23, true,
        IndexBuildFailure.NONE, "paused");

    var serialized = store.serialize(state);
    assertFalse(serialized.containsKey("storageIdentity"));
    assertFalse(serialized.containsKey("lineageIdentity"));
    assertEquals(state, store.deserialize(serialized));
  }

  /** A stale version fails before an illegal lifecycle transition is inspected. */
  @Test
  public void expectedVersionCheckPrecedesTransitionCheck() {
    var store = storeWithInitial();
    var illegal = replacement(IndexLifecycle.USABLE, UUID.randomUUID(), null);

    var failure = assertThrows(
        ConcurrentModificationException.class, () -> store.update(DESCRIPTOR, 99, illegal));
    assertTrue(failure.getMessage().contains("99"));
  }

  /** A current expected version cannot authorize an unapproved lifecycle transition. */
  @Test
  public void storeRejectsIllegalTransition() {
    var store = storeWithInitial();

    assertThrows(
        IllegalArgumentException.class,
        () -> store.update(
            DESCRIPTOR, 0, replacement(IndexLifecycle.USABLE, UUID.randomUUID(), null)));
  }

  /** An update rejects state belonging to a different descriptor. */
  @Test
  public void updateRejectsReplacementForAnotherDescriptor() {
    var store = storeWithInitial();
    var otherDescriptor = new RecordId(7, 10);
    var replacement = new IndexBuildState(
        1,
        otherDescriptor,
        IndexLifecycle.MAINTAINED,
        UUID.randomUUID(),
        null,
        null,
        0,
        false,
        IndexBuildFailure.NONE,
        null);

    var failure = assertThrows(
        IllegalArgumentException.class, () -> store.update(DESCRIPTOR, 0, replacement));
    assertEquals("Index build state belongs to another descriptor", failure.getMessage());
  }

  /** A durable record rejects a negative storage version. */
  @Test
  public void versionedRecordRejectsNegativeVersion() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> new IndexBuildStateStore.VersionedRecord(Map.of(), -1));

    assertEquals("The record version cannot be negative", failure.getMessage());
  }

  /** An invalid retry changes the build incarnation and clears the owner epoch. */
  @Test
  public void retryRequiresNewIncarnationAndClearedOwner() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var invalid = invalidState();
    store.create(invalid);

    assertThrows(
        IllegalArgumentException.class,
        () -> store.update(
            DESCRIPTOR, 0,
            replacement(IndexLifecycle.MAINTAINED, invalid.buildIncarnation(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.update(
            DESCRIPTOR, 0, replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), 5L)));

    var retried = store.update(
        DESCRIPTOR, 0, replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), null));
    assertNotEquals(invalid.buildIncarnation(), retried.buildState().buildIncarnation());
    assertNull(retried.buildState().ownerEpoch());
  }

  /** Recovery publishes the durable record when the record is present. */
  @Test
  public void recoveryReturnsPresentRecord() {
    var recovered = storeWithInitial().recover(DESCRIPTOR);

    assertEquals(IndexLifecycle.EXISTS, recovered.lifecycle());
    assertEquals(0, recovered.recordVersion());
  }

  /** Recovery creates an invalid quarantine when the durable record is missing. */
  @Test
  public void recoveryQuarantinesMissingRecord() {
    var recovered = new IndexBuildStateStore(new MemoryBackend()).recover(DESCRIPTOR);

    assertEquals(IndexLifecycle.INVALID, recovered.lifecycle());
    assertEquals(IndexBuildFailure.INDEX_BUILD_STATE_MISSING, recovered.buildState().failure());
  }

  /** Deletion removes the durable record under its expected version. */
  @Test
  public void deletionRemovesStateRecord() {
    var store = storeWithInitial();
    store.delete(DESCRIPTOR, 0);
    assertFalse(store.read(DESCRIPTOR).isPresent());
  }

  private static IndexBuildStateStore storeWithInitial() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    store.createInitial(DESCRIPTOR);
    return store;
  }

  private static IndexBuildState replacement(
      IndexLifecycle lifecycle, UUID incarnation, Long ownerEpoch) {
    return new IndexBuildState(
        1,
        DESCRIPTOR,
        lifecycle,
        incarnation,
        ownerEpoch,
        null,
        0,
        false,
        lifecycle == IndexLifecycle.INVALID
            ? IndexBuildFailure.UNIQUE_KEY_CONFLICT
            : IndexBuildFailure.NONE,
        null);
  }

  private static IndexBuildState invalidState() {
    return replacement(IndexLifecycle.INVALID, UUID.randomUUID(), 3L);
  }

  private static final class MemoryBackend implements IndexBuildStateStore.Backend {

    private final Map<RID, IndexBuildStateStore.VersionedRecord> records = new HashMap<>();

    @Override
    public Optional<IndexBuildStateStore.VersionedRecord> read(RID descriptorIdentity) {
      return Optional.ofNullable(records.get(descriptorIdentity));
    }

    @Override
    public IndexBuildStateStore.VersionedRecord create(
        RID descriptorIdentity, Map<String, Object> value) {
      var created = new IndexBuildStateStore.VersionedRecord(value, 0);
      if (records.putIfAbsent(descriptorIdentity, created) != null) {
        throw new IllegalStateException("Index build state already exists");
      }
      return created;
    }

    @Override
    public IndexBuildStateStore.VersionedRecord update(
        RID descriptorIdentity, long expectedVersion, Map<String, Object> value) {
      var current = records.get(descriptorIdentity);
      if (current == null || current.version() != expectedVersion) {
        throw new ConcurrentModificationException("Index build state version changed");
      }
      var replacement = new IndexBuildStateStore.VersionedRecord(value, expectedVersion + 1);
      records.put(descriptorIdentity, replacement);
      return replacement;
    }

    @Override
    public void delete(RID descriptorIdentity, long expectedVersion) {
      var current = records.get(descriptorIdentity);
      if (current == null || current.version() != expectedVersion) {
        throw new ConcurrentModificationException("Index build state version changed");
      }
      records.remove(descriptorIdentity);
    }
  }
}
