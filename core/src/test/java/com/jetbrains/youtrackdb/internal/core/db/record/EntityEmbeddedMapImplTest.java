package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class EntityEmbeddedMapImplTest extends DbTestBase {

  @Test
  public void testPutOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.enableTracking(doc);

    map.put("key1", "value1");

    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.ADD, "key1", "value1", null);
    Assert.assertEquals(event, map.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(map.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testPutTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    map.put("key1", "value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.disableTracking(doc);
    map.enableTracking(doc);

    map.put("key1", "value2");
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.UPDATE, "key1", "value2", "value1");
    Assert.assertEquals(event, map.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(map.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testPutThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    map.put("key1", "value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.disableTracking(doc);
    map.enableTracking(doc);
    map.put("key1", "value1");

    Assert.assertFalse(map.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testPutFour() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    map.put("key1", "value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.disableTracking(doc);
    map.enableTracking(doc);

    map.put("key1", "value1");

    Assert.assertFalse(map.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testPutFive() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.enableTracking(doc);

    map.putInternal("key1", "value1");

    Assert.assertFalse(map.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);

    map.put("key1", "value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.disableTracking(doc);
    map.enableTracking(doc);

    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.REMOVE, "key1", null, "value1");
    map.remove("key1");
    Assert.assertEquals(event, map.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(map.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new EntityEmbeddedMapImpl<String>(doc);

    map.put("key1", "value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    map.disableTracking(doc);
    map.enableTracking(doc);

    map.remove("key2");

    Assert.assertFalse(map.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedMap = new EntityEmbeddedMapImpl<String>(doc);

    trackedMap.put("key1", "value1");
    trackedMap.put("key2", "value2");
    trackedMap.put("key3", "value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final List<MultiValueChangeEvent<Object, String>> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "key1", null, "value1"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "key2", null, "value2"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "key3", null, "value3"));

    trackedMap.enableTracking(doc);
    trackedMap.clear();

    Assert.assertEquals(trackedMap.getTimeLine().getMultiValueChangeEvents(), firedEvents);
    Assert.assertTrue(trackedMap.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    final var trackedMap = new EntityEmbeddedMapImpl<String>(
        doc);

    trackedMap.put("key1", "value1");
    trackedMap.put("key2", "value2");
    trackedMap.put("key3", "value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedMap.clear();

    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testReturnOriginalStateOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedMap = new EntityEmbeddedMapImpl<String>(doc);
    trackedMap.put("key1", "value1");
    trackedMap.put("key2", "value2");
    trackedMap.put("key3", "value3");
    trackedMap.put("key4", "value4");
    trackedMap.put("key5", "value5");
    trackedMap.put("key6", "value6");
    trackedMap.put("key7", "value7");

    final Map<Object, String> original = new HashMap<>(trackedMap);
    trackedMap.enableTracking(doc);
    trackedMap.put("key8", "value8");
    trackedMap.put("key9", "value9");
    trackedMap.put("key2", "value10");
    trackedMap.put("key11", "value11");
    trackedMap.remove("key5");
    trackedMap.remove("key5");
    trackedMap.put("key3", "value12");
    trackedMap.remove("key8");
    trackedMap.remove("key3");

    Assert.assertEquals(
        trackedMap.returnOriginalState(session.getActiveTransaction(),
            trackedMap.getTimeLine().getMultiValueChangeEvents()),
        original);
    session.rollback();
  }

  @Test
  public void testRollBackChangesOne() {
    session.begin();
    final var entity = session.newEntity();

    final var originalMap = new HashMap<String, String>();
    originalMap.put("key1", "value1");
    originalMap.put("key2", "value2");
    originalMap.put("key3", "value3");
    originalMap.put("key4", "value4");
    originalMap.put("key5", "value5");
    originalMap.put("key6", "value6");
    originalMap.put("key7", "value7");

    var trackedMap = entity.newEmbeddedMap("map", originalMap);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    trackedMap.put("key8", "value8");
    trackedMap.put("key9", "value9");
    trackedMap.put("key2", "value10");
    trackedMap.put("key11", "value11");
    trackedMap.remove("key5");
    trackedMap.remove("key5");
    trackedMap.put("key3", "value12");
    trackedMap.remove("key8");
    trackedMap.remove("key3");

    ((EntityEmbeddedMapImpl<?>) trackedMap).rollbackChanges(tx);

    Assert.assertEquals(originalMap, trackedMap);
    session.rollback();
  }

  @Test
  public void testReturnOriginalStateTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedMap = new EntityEmbeddedMapImpl<String>(doc);
    trackedMap.put("key1", "value1");
    trackedMap.put("key2", "value2");
    trackedMap.put("key3", "value3");
    trackedMap.put("key4", "value4");
    trackedMap.put("key5", "value5");
    trackedMap.put("key6", "value6");
    trackedMap.put("key7", "value7");

    final Map<Object, String> original = new HashMap<Object, String>(trackedMap);
    trackedMap.enableTracking(doc);
    trackedMap.put("key8", "value8");
    trackedMap.put("key9", "value9");
    trackedMap.put("key2", "value10");
    trackedMap.put("key11", "value11");
    trackedMap.remove("key5");
    trackedMap.remove("key5");
    trackedMap.clear();
    trackedMap.put("key3", "value12");
    trackedMap.remove("key8");
    trackedMap.remove("key3");

    Assert.assertEquals(
        trackedMap.returnOriginalState(session.getActiveTransaction(),
            trackedMap.getTimeLine().getMultiValueChangeEvents()),
        original);
    session.rollback();
  }

  @Test
  public void testRollbackChangesTwo() {
    session.begin();
    final var entity = session.newEntity();

    final var originalMap = new HashMap<String, String>();
    originalMap.put("key1", "value1");
    originalMap.put("key2", "value2");
    originalMap.put("key3", "value3");
    originalMap.put("key4", "value4");
    originalMap.put("key5", "value5");
    originalMap.put("key6", "value6");
    originalMap.put("key7", "value7");

    var trackedMap = entity.newEmbeddedMap("map", originalMap);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    trackedMap.put("key8", "value8");
    trackedMap.put("key9", "value9");
    trackedMap.put("key2", "value10");
    trackedMap.put("key11", "value11");
    trackedMap.remove("key5");
    trackedMap.remove("key5");
    trackedMap.clear();
    trackedMap.put("key3", "value12");
    trackedMap.remove("key8");
    trackedMap.remove("key3");

    ((EntityEmbeddedMapImpl<?>) trackedMap).rollbackChanges(tx);

    Assert.assertEquals(originalMap, trackedMap);
    session.rollback();
  }

  @Test
  public void updateMapElementViaSql() {
    final var classWithSchema = session.createClass("EntityWithSchema");
    classWithSchema.createProperty("oneLevelMap", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    classWithSchema.createProperty("nestedMap", PropertyType.EMBEDDEDMAP, PropertyType.EMBEDDEDMAP);

    final var classWithoutSchema = session.createClass("EntityWithoutSchema");

    final var classes = List.of(classWithSchema, classWithoutSchema);

    session.begin();

    final List<RID> entities = new ArrayList<>();
    for (var clazz : classes) {
      for (var init : List.of(true, false)) {

        final var entity = session.newEntity(clazz);
        entity.setProperty("desc", clazz.getName() + " - " + (init ? "init" : "no init"));
        entities.add(entity.getIdentity());

        final var oneLevelMap = entity.getOrCreateEmbeddedMap("oneLevelMap");
        final var nestedMap = entity.getOrCreateEmbeddedMap("nestedMap");

        // Unfortunately nested map update doesn't work if the keys are not initialized at the start
        nestedMap.put("key1", session.newEmbeddedMap());
        nestedMap.put("key2", session.newEmbeddedMap());

        if (init) {
          oneLevelMap.put("key1", "value1");
          nestedMap.put("key1", session.newEmbeddedMap(Map.of("subkey1", "subvalue1")));
        }

      }
    }

    session.commit();

    var tx = session.begin();
    for (var c : classes) {

      tx.command(
          "UPDATE " + c.getName() + " SET " +
              "oneLevelMap['key1'] = 'newvalue1', " +
              "oneLevelMap['key2'] = 'newvalue2', " +
              "nestedMap['key1']['subkey1'] = 'newvalue3', " +
              "nestedMap['key1']['subkey2'] = 'newvalue4', " +
              "nestedMap['key2']['subkey3'] = 'newvalue5'");
    }

    session.commit();
    tx = session.begin();

    for (var eid : entities) {
      final var entity = tx.loadEntity(eid);
      Assert.assertEquals(
          "embeddedMap is different for " + entity.getString("desc"),
          Map.of(
              "key1", "newvalue1",
              "key2", "newvalue2"),
          entity.getEmbeddedMap("oneLevelMap"));

      Assert.assertEquals(
          "nestedMap is different for " + entity.getString("desc"),
          Map.of(
              "key1", Map.of("subkey1", "newvalue3", "subkey2", "newvalue4"),
              "key2", Map.of("subkey3", "newvalue5")),
          entity.getEmbeddedMap("nestedMap"));
    }
    session.commit();
  }

  // ---------- residual coverage: ctor / setOwner / putInternal / replace / entrySet ----------

  /**
   * The default no-record ctor enables tracking immediately; mutations are registered as
   * change events without a parent record (the wrapper is used by deserialization paths).
   */
  @Test
  public void defaultCtorEnablesTrackingImmediately() {
    final var map = new EntityEmbeddedMapImpl<String>();
    map.put("k", "v");
    Assert.assertTrue(map.isModified());
    Assert.assertEquals(1, map.getTimeLine().getMultiValueChangeEvents().size());
  }

  /**
   * The size-presizing default ctor matches the no-record ctor on observable behaviour;
   * pin to ensure both overloads stay in lockstep.
   */
  @Test
  public void defaultSizePresizingCtor() {
    final var map = new EntityEmbeddedMapImpl<String>(8);
    map.put("k", "v");
    Assert.assertTrue(map.isModified());
  }

  /**
   * The size-presizing record ctor matches the unsized record ctor for our external
   * assertions (allocation tweak only).
   */
  @Test
  public void recordSizeCtor() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc, 8);
    Assert.assertSame(doc, map.getOwner());
    Assert.assertTrue(map.isEmpty());
    session.rollback();
  }

  /**
   * The origin-map ctor copies all entries via {@code putAll} → {@code put}. Since the
   * tracker is not yet enabled, every put falls into the {@code setDirty} else-branch and
   * the parent flips dirty.
   */
  @Test
  public void originMapCtorCopiesAndDirtiesParent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final Map<String, String> origin = new HashMap<>();
    origin.put("a", "1");
    origin.put("b", "2");
    final var map = new EntityEmbeddedMapImpl<>(doc, origin);
    Assert.assertEquals(2, map.size());
    Assert.assertEquals("1", map.get("a"));
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  /** The empty origin-map ctor is a no-op — the parent stays clean. */
  @Test
  public void originMapEmptyCtorNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    final var map = new EntityEmbeddedMapImpl<String>(doc, new HashMap<>());
    Assert.assertTrue(map.isEmpty());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  /** {@code put(null, …)} throws — production message preserved for grep-friendliness. */
  @Test
  public void putNullKeyThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var ex =
        Assert.assertThrows(IllegalArgumentException.class, () -> map.put(null, "v"));
    Assert.assertEquals("null key not supported by embedded map", ex.getMessage());
    session.rollback();
  }

  /** {@code putInternal(null, …)} also throws. */
  @Test
  public void putInternalNullKeyThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    Assert.assertThrows(IllegalArgumentException.class, () -> map.putInternal(null, "v"));
    session.rollback();
  }

  /**
   * {@code put} on the same key with the same value returns the old value but emits no
   * event — pins the {@code containsKey && oldValue == value} short-circuit branch.
   */
  @Test
  public void putSameValueShortCircuitsEvent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var v = "shared";
    map.put("k", v);
    // pre-enable put flipped the local dirty flag; disable/enable cycle clears it.
    map.disableTracking(doc);
    map.enableTracking(doc);

    final var prev = map.put("k", v);
    Assert.assertEquals(v, prev);
    Assert.assertFalse("identical-value put is a silent re-put", map.isModified());
    session.rollback();
  }

  /**
   * {@code putInternal} on an existing key with a same-instance value short-circuits the
   * owner shuffle. Pins the {@code containsKey && oldValue == value} fast-path.
   */
  @Test
  public void putInternalSameValueShortCircuits() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var v = "shared";
    map.putInternal("k", v);
    map.putInternal("k", v); // second put returns silently
    Assert.assertEquals(v, map.get("k"));
    session.rollback();
  }

  /** {@code clear} on a tracker-enabled map issues one REMOVE event per entry. */
  @Test
  public void clearWithTrackingEmitsRemoveEvents() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("a", "1");
    map.put("b", "2");
    map.enableTracking(doc);

    map.clear();

    Assert.assertTrue(map.isEmpty());
    Assert.assertEquals(2, map.getTimeLine().getMultiValueChangeEvents().size());
    session.rollback();
  }

  // ---------- replace ----------

  /** 3-arg {@code replace} on a matching pair updates and emits UPDATE. */
  @Test
  public void replace3argMatchUpdates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v1");
    map.enableTracking(doc);

    Assert.assertTrue(map.replace("k", "v1", "v2"));
    Assert.assertEquals("v2", map.get("k"));
    Assert.assertEquals(ChangeType.UPDATE,
        map.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  /** 3-arg {@code replace} on a non-matching pair leaves the map untouched. */
  @Test
  public void replace3argMismatchPreservesEntry() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v1");
    Assert.assertFalse(map.replace("k", "wrong", "v2"));
    Assert.assertEquals("v1", map.get("k"));
    session.rollback();
  }

  /** 2-arg {@code replace} on existing key returns the previous value. */
  @Test
  public void replace2argExistingKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v1");
    Assert.assertEquals("v1", map.replace("k", "v2"));
    Assert.assertEquals("v2", map.get("k"));
    session.rollback();
  }

  /** 2-arg {@code replace} on missing key returns null without inserting. */
  @Test
  public void replace2argMissingKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    Assert.assertNull(map.replace("missing", "v"));
    Assert.assertTrue(map.isEmpty());
    session.rollback();
  }

  // ---------- remove(Object, Object) ----------

  /**
   * {@code remove(key, value)} matching pair returns true and emits a REMOVE event.
   * Pins the path through {@code map.remove(key, value)} that fires {@code removeEvent}.
   */
  @Test
  public void removeKeyValueMatchEmitsRemove() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");
    map.enableTracking(doc);

    Assert.assertTrue(map.remove("k", "v"));
    Assert.assertTrue(map.isEmpty());
    Assert.assertEquals(ChangeType.REMOVE,
        map.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  /** {@code remove(key, mismatching-value)} returns false; the entry survives. */
  @Test
  public void removeKeyValueMismatchPreservesEntry() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");
    Assert.assertFalse(map.remove("k", "different"));
    Assert.assertEquals(1, map.size());
    session.rollback();
  }

  // ---------- entrySet (EntrySet / EntryIterator / TrackerEntry) ----------

  /**
   * The entry-set view exposes one entry per key. Setting a value via the entry's
   * {@code setValue} routes through {@code updateEvent}.
   */
  @Test
  public void entrySetSetValueEmitsUpdate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v1");
    map.enableTracking(doc);

    final var it = map.entrySet().iterator();
    Assert.assertTrue(it.hasNext());
    final var entry = it.next();
    final var prev = entry.setValue("v2");

    Assert.assertEquals("v1", prev);
    Assert.assertEquals("v2", map.get("k"));
    Assert.assertEquals(ChangeType.UPDATE,
        map.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  /**
   * {@code EntryIterator.remove()} routes through {@code map.remove(key, value)} —
   * removes the cell for the entry just returned.
   */
  @Test
  public void entrySetIteratorRemoveDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");

    final var it = map.entrySet().iterator();
    Assert.assertTrue(it.hasNext());
    it.next();
    it.remove();
    Assert.assertTrue(map.isEmpty());
    session.rollback();
  }

  /**
   * Calling {@code remove} on the iterator before {@code next} throws — pins the
   * {@code lastEntry == null} guard in the inner {@code EntryIterator}.
   */
  @Test
  public void entrySetIteratorRemoveBeforeNextThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");

    final var it = map.entrySet().iterator();
    Assert.assertThrows(IllegalStateException.class, it::remove);
    session.rollback();
  }

  /**
   * The {@code EntrySet.clear()} delegates to {@code EntityEmbeddedMapImpl.clear()} which
   * empties all entries and (with tracking enabled) emits REMOVE events.
   */
  @Test
  public void entrySetClearDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("a", "1");
    map.put("b", "2");

    map.entrySet().clear();
    Assert.assertTrue(map.isEmpty());
    session.rollback();
  }

  /**
   * The {@code EntrySet.remove(Object)} matches against a {@code Map.Entry}; non-entry
   * arguments return false and entry mismatches are silent.
   */
  @Test
  public void entrySetRemoveNonEntryReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");

    Assert.assertFalse(map.entrySet().remove("not-entry"));
    Assert.assertEquals(1, map.size());
    session.rollback();
  }

  // ---------- delegations ----------

  /** {@code getOrDefault} returns the stored value on hit and the default on miss. */
  @Test
  public void getOrDefaultBranches() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");
    Assert.assertEquals("v", map.getOrDefault("k", "fallback"));
    Assert.assertEquals("fallback", map.getOrDefault("missing", "fallback"));
    session.rollback();
  }

  /** {@code forEach} visits every (key, value) once. */
  @Test
  public void forEachVisitsEachPair() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("a", "1");
    map.put("b", "2");

    final var seen = new HashSet<String>();
    map.forEach((k, v) -> seen.add(k + "=" + v));
    Assert.assertEquals(new HashSet<>(List.of("a=1", "b=2")), seen);
    session.rollback();
  }

  /** {@code containsKey} / {@code containsValue} delegations for hit and miss. */
  @Test
  public void containsKeyAndContainsValueBranches() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");

    Assert.assertTrue(map.containsKey("k"));
    Assert.assertFalse(map.containsKey("missing"));
    Assert.assertTrue(map.containsValue("v"));
    Assert.assertFalse(map.containsValue("missing"));
    session.rollback();
  }

  /** {@code equals}, {@code hashCode}, and {@code toString} delegate to the backing map. */
  @Test
  public void equalsHashCodeToStringDelegate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.put("k", "v");

    Assert.assertEquals(map, map);
    Assert.assertEquals(Map.of("k", "v"), map);
    Assert.assertNotEquals("non-map comparand returns false", "x", map);

    Assert.assertEquals(Map.of("k", "v").hashCode(), map.hashCode());
    Assert.assertNotNull(map.toString());
    Assert.assertTrue(map.toString().contains("k"));
    session.rollback();
  }

  // ---------- setOwner ----------

  /** {@code setOwner(null)} resets the source record. */
  @Test
  public void setOwnerNullClears() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.setOwner(null);
    Assert.assertNull(map.getOwner());
    session.rollback();
  }

  /** {@code setOwner} to a different non-null entity is rejected. */
  @Test
  public void setOwnerToDifferentEntityRejected() {
    session.begin();
    final var doc1 = (EntityImpl) session.newEntity();
    final var doc2 = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc1);
    final var ex = Assert.assertThrows(
        IllegalStateException.class, () -> map.setOwner(doc2));
    Assert.assertTrue(ex.getMessage().startsWith(
        "This map is already owned by data container"));
    session.rollback();
  }

  // ---------- transactionClear / isTransactionModified ----------

  /** {@code transactionClear} resets the tracker per-transaction state. */
  @Test
  public void transactionClearResetsTransactionDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.enableTracking(doc);
    map.put("k", "v");
    Assert.assertTrue(map.isTransactionModified());

    map.transactionClear();
    Assert.assertFalse(map.isTransactionModified());
    Assert.assertNull(map.getTransactionTimeLine());
    session.rollback();
  }

  // ---------- rollback exceptions ----------

  /** {@code rollbackChanges} on a non-tracking map throws. */
  @Test
  public void rollbackChangesWithoutTrackerThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    final var tx = session.getActiveTransaction();
    final var ex =
        Assert.assertThrows(DatabaseException.class, () -> map.rollbackChanges(tx));
    Assert.assertTrue(ex.getMessage().contains("Changes are not tracked"));
    session.rollback();
  }

  /** {@code rollbackChanges} on an empty timeline is a no-op. */
  @Test
  public void rollbackChangesEmptyTimelineNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    map.enableTracking(doc);

    map.rollbackChanges(session.getActiveTransaction());
    Assert.assertTrue(map.isEmpty());
    session.rollback();
  }

  /** {@code isEmbeddedContainer} is always true for the embedded map. */
  @Test
  public void isEmbeddedContainerIsTrue() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityEmbeddedMapImpl<String>(doc);
    Assert.assertTrue(map.isEmbeddedContainer());
    session.rollback();
  }

  /**
   * {@code clear} cascades to nested {@code TrackedMultiValue} values: each nested value's
   * owner is cleared. Pins the cascade-clear branch in the outer-map clear loop.
   *
   * <p>The nested list is created with a {@code null} owner so the outer map can claim
   * ownership via {@code addOwner} → {@code setOwner(outer)} without a conflict. The
   * production cascade-clear path then resets that owner to {@code null} on
   * {@code outer.clear()}.
   */
  @Test
  public void clearCascadesToNestedTrackedValueOwnerClear() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var outer = new EntityEmbeddedMapImpl<EntityEmbeddedListImpl<String>>(doc);
    final var nested = new EntityEmbeddedListImpl<String>((RecordElement) null);
    nested.add("a");
    Assert.assertNull(nested.getOwner());

    outer.put("nested", nested);
    Assert.assertSame("addOwner claims the nested list for the outer map",
        outer, nested.getOwner());

    outer.clear();
    Assert.assertNull("nested owner cleared on outer.clear()", nested.getOwner());
    session.rollback();
  }
}
