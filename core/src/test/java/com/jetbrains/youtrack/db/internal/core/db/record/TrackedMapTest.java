package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class TrackedMapTest extends DbTestBase {

  @Test
  public void testPutOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var map = new TrackedMap<String>(doc);
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

    final var map = new TrackedMap<String>(doc);
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

    final var map = new TrackedMap<String>(doc);
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

    final var map = new TrackedMap<String>(doc);
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

    final var map = new TrackedMap<String>(doc);

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

    final var map = new TrackedMap<String>(doc);

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

    final var map = new TrackedMap<String>(doc);

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

    final var trackedMap = new TrackedMap<String>(doc);

    trackedMap.put("key1", "value1");
    trackedMap.put("key2", "value2");
    trackedMap.put("key3", "value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final List<MultiValueChangeEvent<Object, String>> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent<Object, String>(
            ChangeType.REMOVE, "key1", null, "value1"));
    firedEvents.add(
        new MultiValueChangeEvent<Object, String>(
            ChangeType.REMOVE, "key2", null, "value2"));
    firedEvents.add(
        new MultiValueChangeEvent<Object, String>(
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
    final var trackedMap = new TrackedMap<String>(doc);

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

    final var trackedMap = new TrackedMap<String>(doc);
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
    trackedMap.put("key3", "value12");
    trackedMap.remove("key8");
    trackedMap.remove("key3");

    //noinspection unchecked,rawtypes
    Assert.assertEquals(
        trackedMap.returnOriginalState(session,
            (List) trackedMap.getTimeLine().getMultiValueChangeEvents()),
        original);
    session.rollback();
  }

  @Test
  public void testReturnOriginalStateTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedMap = new TrackedMap<String>(doc);
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

    //noinspection unchecked,rawtypes
    Assert.assertEquals(
        trackedMap.returnOriginalState(session,
            (List) trackedMap.getTimeLine().getMultiValueChangeEvents()),
        original);
    session.rollback();
  }
}
