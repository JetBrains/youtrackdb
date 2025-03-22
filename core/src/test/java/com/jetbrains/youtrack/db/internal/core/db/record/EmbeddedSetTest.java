package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class EmbeddedSetTest extends DbTestBase {

  @Test
  public void testAddOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    trackedSet.enableTracking(doc);
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.ADD, "value1", "value1", null);
    trackedSet.add("value1");
    Assert.assertEquals(event, trackedSet.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(trackedSet.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedSet);
    trackedSet.add("value1");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    trackedSet.enableTracking(doc);
    trackedSet.addInternal("value1");

    Assert.assertFalse(trackedSet.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddFour() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EmbeddedSetImpl<String>(doc);

    trackedSet.add("value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.disableTracking(doc);
    trackedSet.enableTracking(doc);

    trackedSet.add("value1");
    Assert.assertFalse(trackedSet.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.enableTracking(doc);
    trackedSet.remove("value2");
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.REMOVE, "value2", null, "value2");
    Assert.assertEquals(trackedSet.getTimeLine().getMultiValueChangeEvents().getFirst(), event);
    Assert.assertTrue(trackedSet.isModified());
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

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedSet);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.remove("value2");
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

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedSet.disableTracking(doc);
    trackedSet.enableTracking(doc);
    trackedSet.remove("value5");
    Assert.assertFalse(trackedSet.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final List<MultiValueChangeEvent<String, String>> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "value1", null, "value1"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "value2", null, "value2"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "value3", null, "value3"));

    trackedSet.enableTracking(doc);
    trackedSet.clear();

    Assert.assertEquals(new HashSet<>(firedEvents),
        new HashSet<>(trackedSet.getTimeLine().getMultiValueChangeEvents()));
    Assert.assertTrue(trackedSet.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection") final var trackedSet = new EmbeddedSetImpl<String>(
        doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.clear();

    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testReturnOriginalState() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedSet = new EmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");
    trackedSet.add("value4");
    trackedSet.add("value5");

    final Set<String> original = new HashSet<String>(trackedSet);
    trackedSet.enableTracking(doc);
    trackedSet.add("value6");
    trackedSet.remove("value2");
    trackedSet.remove("value5");
    trackedSet.add("value7");
    trackedSet.add("value8");
    trackedSet.remove("value7");
    trackedSet.add("value9");
    trackedSet.add("value10");

    Assert.assertEquals(
        original,
        trackedSet.returnOriginalState(session,
            (List) trackedSet.getTimeLine().getMultiValueChangeEvents()));
    session.rollback();
  }

  @Test
  public void testStackOverflowOnRecursion() {
    session.begin();
    final var entity = (EntityImpl) session.newEmbeddedEntity();
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection") final var trackedSet = new EmbeddedSetImpl<EntityImpl>(
        entity);
    trackedSet.add(entity);
    session.rollback();
  }
}
