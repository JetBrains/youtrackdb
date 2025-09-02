package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class TrackedListTest extends DbTestBase {

  @Test
  public void testAddNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.enableTracking(doc);
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.ADD, 0, "value1", null);
    trackedList.add("value1");

    Assert.assertEquals(event, trackedList.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(trackedList.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddNotificationTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");

    trackedList.disableTracking(doc);
    trackedList.enableTracking(doc);
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.ADD, 2, "value3", null);

    trackedList.add("value3");
    Assert.assertEquals(event, trackedList.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(trackedList.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddNotificationThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedList);
    trackedList.add("value1");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddNotificationFour() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedList.disableTracking(doc);
    Assert.assertFalse(trackedList.isModified());
    trackedList.enableTracking(doc);

    trackedList.addInternal("value3");
    Assert.assertFalse(trackedList.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddAllNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    final List<String> valuesToAdd = new ArrayList<>();
    valuesToAdd.add("value1");
    valuesToAdd.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final List<MultiValueChangeEvent<Integer, String>> firedEvents =
        new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.ADD, 0, "value1"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.ADD, 1, "value3"));
    trackedList.enableTracking(doc);
    trackedList.addAll(valuesToAdd);

    Assert.assertEquals(firedEvents, trackedList.getTimeLine().getMultiValueChangeEvents());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddAllNotificationTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedList);
    final List<String> valuesToAdd = new ArrayList<>();
    valuesToAdd.add("value1");
    valuesToAdd.add("value3");

    trackedList.addAll(valuesToAdd);

    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddAllNotificationThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    final List<String> valuesToAdd = new ArrayList<>();
    valuesToAdd.add("value1");
    valuesToAdd.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedList.disableTracking(doc);
    trackedList.enableTracking(doc);
    for (var e : valuesToAdd) {
      trackedList.addInternal(e);
    }

    Assert.assertFalse(trackedList.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddIndexNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedList.disableTracking(doc);
    trackedList.enableTracking(doc);

    var event =
        new MultiValueChangeEvent<>(ChangeType.ADD, 1, "value3", null);

    trackedList.add(1, "value3");
    Assert.assertEquals(event, trackedList.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(trackedList.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddIndexNotificationTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    doc.setPropertyInternal("aa", trackedList);
    trackedList.add("value1");
    trackedList.add("value2");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedList.add(1, "value3");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testSetNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedList.enableTracking(doc);
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.UPDATE, 1, "value4", "value2");
    trackedList.set(1, "value4");
    Assert.assertEquals(event, trackedList.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(trackedList.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testSetNotificationTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedList);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedList.set(1, "value4");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedList.enableTracking(doc);
    trackedList.remove("value2");
    var event =
        new MultiValueChangeEvent<>(ChangeType.REMOVE, 1, null, "value2");
    Assert.assertEquals(event, trackedList.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedList);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedList.remove("value2");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationFour() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedList.disableTracking(doc);

    trackedList.remove("value4");
    Assert.assertFalse(trackedList.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveIndexOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedList.enableTracking(doc);

    trackedList.remove(1);
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.REMOVE, 1, null, "value2");
    Assert.assertTrue(trackedList.isModified());
    Assert.assertEquals(event, trackedList.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final List<MultiValueChangeEvent<Integer, String>> firedEvents =
        new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, 2, null, "value3"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, 1, null, "value2"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, 0, null, "value1"));
    trackedList.enableTracking(doc);

    trackedList.clear();
    Assert.assertEquals(firedEvents, trackedList.getTimeLine().getMultiValueChangeEvents());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedList);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedList.clear();
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testReturnOriginalStateOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");
    trackedList.add("value4");
    trackedList.add("value5");

    final List<String> original = new ArrayList<>(trackedList);
    trackedList.enableTracking(doc);
    trackedList.add("value6");
    trackedList.add("value7");
    trackedList.set(2, "value10");
    trackedList.add(1, "value8");
    trackedList.add(1, "value8");
    trackedList.remove(3);
    trackedList.remove("value7");
    trackedList.add(0, "value9");
    trackedList.add(0, "value9");
    trackedList.add(0, "value9");
    trackedList.add(0, "value9");
    trackedList.remove("value9");
    trackedList.remove("value9");
    trackedList.add(4, "value11");

    Assert.assertEquals(
        original,
        trackedList.returnOriginalState(session.getActiveTransaction(),
            trackedList.getTimeLine().getMultiValueChangeEvents()));
    session.rollback();
  }

  @Test
  public void testReturnOriginalStateTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedList = new EntityEmbeddedListImpl<String>(doc);
    trackedList.add("value1");
    trackedList.add("value2");
    trackedList.add("value3");
    trackedList.add("value4");
    trackedList.add("value5");

    final List<String> original = new ArrayList<>(trackedList);
    trackedList.enableTracking(doc);
    trackedList.add("value6");
    trackedList.add("value7");
    trackedList.set(2, "value10");
    trackedList.add(1, "value8");
    trackedList.remove(3);
    trackedList.clear();
    //noinspection RedundantOperationOnEmptyContainer
    trackedList.remove("value7");
    trackedList.add(0, "value9");
    trackedList.add("value11");
    trackedList.add(0, "value12");
    trackedList.add("value12");

    Assert.assertEquals(
        original,
        trackedList.returnOriginalState(session.getActiveTransaction(),
            trackedList.getTimeLine().getMultiValueChangeEvents()));
    session.rollback();
  }

  @Test
  public void testRollBackChangesOne() {
    session.begin();
    final var entity = session.newEntity();

    final var originalList = new ArrayList<String>();
    originalList.add("value1");
    originalList.add("value2");
    originalList.add("value3");
    originalList.add("value4");
    originalList.add("value5");

    var trackedList = entity.newEmbeddedList("list", originalList);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    trackedList.add("value6");
    trackedList.add("value7");
    trackedList.set(2, "value10");
    trackedList.add(1, "value8");
    trackedList.add(1, "value8");
    trackedList.remove(3);
    trackedList.remove("value7");
    trackedList.addFirst("value9");
    trackedList.addFirst("value9");
    trackedList.addFirst("value9");
    trackedList.addFirst("value9");
    trackedList.remove("value9");
    trackedList.remove("value9");
    trackedList.add(4, "value11");

    ((EntityEmbeddedListImpl<?>) trackedList).rollbackChanges(tx);

    Assert.assertEquals(originalList, trackedList);
    session.rollback();
  }

  @Test
  public void testRollBackChangesTwo() {
    session.begin();
    final var entity = session.newEntity();

    final var originalList = new ArrayList<String>();
    originalList.add("value1");
    originalList.add("value2");
    originalList.add("value3");
    originalList.add("value4");
    originalList.add("value5");

    var trackedList = entity.newEmbeddedList("list", originalList);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    trackedList.add("value6");
    trackedList.add("value7");
    trackedList.set(2, "value10");
    trackedList.add(1, "value8");
    trackedList.remove(3);
    trackedList.clear();
    //noinspection RedundantOperationOnEmptyContainer
    trackedList.remove("value7");
    trackedList.addFirst("value9");
    trackedList.add("value11");
    trackedList.addFirst("value12");
    trackedList.add("value12");

    ((EntityEmbeddedListImpl<?>) trackedList).rollbackChanges(tx);

    Assert.assertEquals(originalList, trackedList);
    session.rollback();
  }
}
