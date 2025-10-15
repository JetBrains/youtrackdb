package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
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

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection") final var trackedMap = new EntityEmbeddedMapImpl<String>(
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
    graph.autoExecuteInTx(g -> g.addSchemaClass("EntityWithSchema",
            __.addSchemaProperty("oneLevelMap", PropertyType.EMBEDDEDMAP, PropertyType.STRING),
            __.addSchemaProperty("nestedMap", PropertyType.EMBEDDEDMAP, PropertyType.EMBEDDEDMAP)
        ).addSchemaClass("EntityWithoutSchema")
    );

    final var classes = List.of("EntityWithSchema", "EntityWithoutSchema");

    session.begin();

    final List<RID> entities = new ArrayList<>();
    for (var clazz : classes) {
      for (var init : List.of(true, false)) {

        final var entity = session.newEntity(clazz);
        entity.setProperty("desc", clazz + " - " + (init ? "init" : "no init"));
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
          "UPDATE " + c + " SET " +
              "oneLevelMap['key1'] = 'newvalue1', " +
              "oneLevelMap['key2'] = 'newvalue2', " +
              "nestedMap['key1']['subkey1'] = 'newvalue3', " +
              "nestedMap['key1']['subkey2'] = 'newvalue4', " +
              "nestedMap['key2']['subkey3'] = 'newvalue5'"
      );
    }

    session.commit();
    tx = session.begin();

    for (var eid : entities) {
      final var entity = tx.loadEntity(eid);
      Assert.assertEquals(
          "embeddedMap is different for " + entity.getString("desc"),
          Map.of(
              "key1", "newvalue1",
              "key2", "newvalue2"
          ),
          entity.getEmbeddedMap("oneLevelMap")
      );

      Assert.assertEquals(
          "nestedMap is different for " + entity.getString("desc"),
          Map.of(
              "key1", Map.of("subkey1", "newvalue3", "subkey2", "newvalue4"),
              "key2", Map.of("subkey3", "newvalue5")
          ),
          entity.getEmbeddedMap("nestedMap")
      );
    }
    session.commit();
  }
}
