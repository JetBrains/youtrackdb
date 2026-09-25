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

/** Covers durable store serialization, transitions, linked reads, and deletion. */
public class IndexBuildStateStoreTest {

  private static final RID DESCRIPTOR = new RecordId(7, 9);

  /** Serialization preserves every durable field, including nullable fields. */
  @Test
  public void serializationRoundTripsCompleteState() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var state =
        new IndexBuildState(
            1,
            DESCRIPTOR,
            IndexLifecycle.MAINTAINED,
            UUID.randomUUID(),
            4L,
            17L,
            23,
            true,
            IndexBuildFailure.NONE,
            "paused");

    var serialized = store.serialize(state);
    assertFalse(serialized.containsKey("storageIdentity"));
    assertFalse(serialized.containsKey("lineageIdentity"));
    assertFalse(serialized.containsKey("ownerLineage"));
    assertEquals(state, store.deserialize(serialized));
  }

  /** Every durable field rejects values with a different runtime type. */
  @Test
  public void decodingRejectsWrongTypeForEveryField() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var valid = store.serialize(IndexBuildState.initial(DESCRIPTOR));
    var fields =
        new String[] {
            "formatVersion",
            "descriptorIdentity",
            "lifecycle",
            "buildIncarnation",
            "ownerEpoch",
            "completionCut",
            "completedUnits",
            "suspended",
            "failure",
            "failureMessage"
        };

    for (var field : fields) {
      var malformed = new HashMap<>(valid);
      malformed.put(field, wrongTypeFor(field));
      assertThrows(
          "The " + field + " field must reject a wrong type",
          IllegalArgumentException.class,
          () -> store.deserialize(malformed));
    }
  }

  private static Object wrongTypeFor(String field) {
    return switch (field) {
      case "formatVersion" -> 4_294_967_297L;
      case "ownerEpoch", "completionCut", "completedUnits" -> Integer.valueOf(1);
      case "suspended" -> "false";
      default -> Boolean.FALSE;
    };
  }

  /** A stale version fails before an illegal lifecycle transition is inspected. */
  @Test
  public void expectedVersionCheckPrecedesTransitionCheck() {
    var fixture = storeWithInitial();
    var illegal = replacement(IndexLifecycle.USABLE, UUID.randomUUID(), null);
    var staleExpected = new IndexLifecycleSnapshot(IndexBuildState.initial(DESCRIPTOR), 99);

    var failure =
        assertThrows(
            ConcurrentModificationException.class,
            () -> fixture.store().publish(DESCRIPTOR, fixture.link(), staleExpected, illegal));

    assertTrue(failure.getMessage().contains("99"));
  }

  /** A current expected version cannot authorize an unapproved lifecycle transition. */
  @Test
  public void storeRejectsIllegalTransition() {
    var fixture = storeWithInitial();

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.store().publish(
            DESCRIPTOR,
            fixture.link(),
            fixture.store().read(DESCRIPTOR, fixture.link()),
            replacement(IndexLifecycle.USABLE, UUID.randomUUID(), null)));
  }

  /** An update rejects state belonging to a different descriptor. */
  @Test
  public void updateRejectsReplacementForAnotherDescriptor() {
    var fixture = storeWithInitial();
    var otherDescriptor = new RecordId(7, 10);
    var replacement =
        new IndexBuildState(
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

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.store().publish(
                DESCRIPTOR,
                fixture.link(),
                fixture.store().read(DESCRIPTOR, fixture.link()),
                replacement));

    assertEquals("Index build state belongs to another descriptor", failure.getMessage());
  }

  /** A linked record for another descriptor fails ownership validation. */
  @Test
  public void linkedReadRejectsRecordForAnotherDescriptor() {
    var backend = new MemoryBackend();
    var store = new IndexBuildStateStore(backend);
    var other = new RecordId(7, 10);
    var created = store.createInitial(other, null);

    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> store.read(DESCRIPTOR, created.identity()));

    assertEquals("Index build state belongs to another descriptor", failure.getMessage());
  }

  /** A missing linked record has a typed signal distinct from decode failures. */
  @Test
  public void linkedReadUsesTypedMissingRecordSignal() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var missing = new RecordId(19, 77);

    var failure = assertThrows(
        IndexBuildStateStore.MissingLinkedRecordException.class,
        () -> store.read(DESCRIPTOR, missing));

    assertEquals("Linked index build state record is missing", failure.getMessage());
  }

  /** A durable record rejects a negative storage version. */
  @Test
  public void versionedRecordRejectsNegativeVersion() {
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IndexBuildStateStore.VersionedRecord(Map.of(), -1));

    assertEquals("The record version cannot be negative", failure.getMessage());
  }

  /** An invalid retry changes the build incarnation and clears the owner epoch. */
  @Test
  public void retryRequiresNewIncarnationAndClearedOwner() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var invalid = invalidState();
    var created = store.create(invalid, null);

    assertThrows(
        IllegalArgumentException.class,
        () -> store.publish(
            DESCRIPTOR,
            created.identity(),
            store.read(DESCRIPTOR, created.identity()),
            replacement(IndexLifecycle.MAINTAINED, invalid.buildIncarnation(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.publish(
            DESCRIPTOR,
            created.identity(),
            store.read(DESCRIPTOR, created.identity()),
            replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), 5L)));

    var retried =
        store.publish(
            DESCRIPTOR,
            created.identity(),
            store.read(DESCRIPTOR, created.identity()),
            replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), null));
    assertNotEquals(invalid.buildIncarnation(), retried.buildState().buildIncarnation());
    assertNull(retried.buildState().ownerEpoch());
  }

  /** An equal rebuilt snapshot publishes under the value comparison that excludes owner epoch. */
  @Test
  public void ownerEpochExcludedValueComparisonAcceptsRebuiltSnapshot() {
    var fixture = storeWithInitial();
    var durable = fixture.store().read(DESCRIPTOR, fixture.link());
    var rebuiltExpected =
        new IndexLifecycleSnapshot(durable.buildState(), durable.recordVersion());

    var published =
        fixture.store().publish(
            DESCRIPTOR,
            fixture.link(),
            rebuiltExpected,
            replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), null));

    assertEquals(IndexLifecycle.MAINTAINED, published.lifecycle());
    assertEquals(1, published.recordVersion());
  }

  /** An owner-free expected snapshot authorizes publication against an owned durable value. */
  @Test
  public void publicationIgnoresOwnerEpochDifference() {
    var store = new IndexBuildStateStore(new MemoryBackend());
    var owned = replacement(IndexLifecycle.EXISTS, UUID.randomUUID(), 71L);
    var created = store.create(owned, null);
    var expected =
        new IndexLifecycleSnapshot(
            owned.withoutProcessOwnership(), created.snapshot().recordVersion());

    var published =
        store.publish(
            DESCRIPTOR,
            created.identity(),
            expected,
            replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), null));

    assertEquals(IndexLifecycle.MAINTAINED, published.lifecycle());
    assertEquals(1, published.recordVersion());
  }

  /** Same-revision lifecycle, progress, and failure differences still reject publication. */
  @Test
  public void publicationRejectsSameRevisionChangesOutsideOwnerEpoch() {
    var fixture = storeWithInitial();
    var durable = fixture.store().read(DESCRIPTOR, fixture.link());
    var state = durable.buildState();
    var divergentStates =
        new IndexBuildState[] {
            new IndexBuildState(
                state.formatVersion(),
                state.descriptorIdentity(),
                IndexLifecycle.MAINTAINED,
                state.buildIncarnation(),
                state.ownerEpoch(),
                state.completionCut(),
                state.completedUnits(),
                state.suspended(),
                state.failure(),
                state.failureMessage()),
            new IndexBuildState(
                state.formatVersion(),
                state.descriptorIdentity(),
                state.lifecycle(),
                state.buildIncarnation(),
                state.ownerEpoch(),
                state.completionCut(),
                1,
                state.suspended(),
                state.failure(),
                state.failureMessage()),
            new IndexBuildState(
                state.formatVersion(),
                state.descriptorIdentity(),
                state.lifecycle(),
                state.buildIncarnation(),
                state.ownerEpoch(),
                state.completionCut(),
                state.completedUnits(),
                state.suspended(),
                state.failure(),
                "different failure detail")
        };

    for (var divergentState : divergentStates) {
      var expected =
          new IndexLifecycleSnapshot(divergentState, durable.recordVersion());
      assertThrows(
          ConcurrentModificationException.class,
          () -> fixture.store().publish(
              DESCRIPTOR,
              fixture.link(),
              expected,
              replacement(IndexLifecycle.MAINTAINED, UUID.randomUUID(), null)));
      assertEquals(durable, fixture.store().read(DESCRIPTOR, fixture.link()));
    }
  }

  /** Deletion removes the durable record under its expected version. */
  @Test
  public void deletionRemovesStateRecord() {
    var fixture = storeWithInitial();
    fixture.store().delete(fixture.link(), 0);
    assertFalse(fixture.backend().read(fixture.link()).isPresent());
  }

  private static StoreFixture storeWithInitial() {
    var backend = new MemoryBackend();
    var store = new IndexBuildStateStore(backend);
    var created = store.createInitial(DESCRIPTOR, null);
    return new StoreFixture(store, backend, created.identity());
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

  private record StoreFixture(IndexBuildStateStore store, MemoryBackend backend, RID link) {
  }

  private static final class MemoryBackend implements IndexBuildStateStore.Backend {

    private final Map<RID, IndexBuildStateStore.VersionedRecord> records = new HashMap<>();
    private long nextPosition;

    @Override
    public Optional<IndexBuildStateStore.VersionedRecord> read(RID recordIdentity) {
      return Optional.ofNullable(records.get(recordIdentity));
    }

    @Override
    public IndexBuildStateStore.CreatedRecord create(Map<String, Object> value) {
      var identity = new RecordId(19, nextPosition++);
      var created = new IndexBuildStateStore.VersionedRecord(value, 0);
      records.put(identity, created);
      return new IndexBuildStateStore.CreatedRecord(identity, created);
    }

    @Override
    public IndexBuildStateStore.VersionedRecord update(
        RID recordIdentity, long expectedVersion, Map<String, Object> value) {
      var current = records.get(recordIdentity);
      if (current == null || current.version() != expectedVersion) {
        throw new ConcurrentModificationException("Index build state version changed");
      }
      var replacement = new IndexBuildStateStore.VersionedRecord(value, expectedVersion + 1);
      records.put(recordIdentity, replacement);
      return replacement;
    }

    @Override
    public void delete(RID recordIdentity, long expectedVersion) {
      var current = records.get(recordIdentity);
      if (current == null || current.version() != expectedVersion) {
        throw new ConcurrentModificationException("Index build state version changed");
      }
      records.remove(recordIdentity);
    }
  }
}
