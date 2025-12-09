package com.jetbrains.youtrackdb.internal.core.db.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

/**
 *
 */
public class DocumentTrackingNestedCollectionsTest extends DbTestBase {

  @Test
  public void testTrackingNestedSet() {
    session.begin();
    RID orid;
    var entity = (EntityImpl) session.newEntity();
    var objects = entity.<EmbeddedSet<Object>>newEmbeddedSet("objects");

    var subObjects = session.newEmbeddedSet();
    objects.add(subObjects);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    orid = entity.getIdentity();

    objects = entity.getOrCreateEmbeddedSet("objects");
    subObjects = objects.iterator().next();

    var nestedDoc = (EntityImpl) session.newEmbeddedEntity();
    subObjects.add(nestedDoc);
    session.commit();

    session.begin();
    entity = session.load(orid);
    objects = entity.getProperty("objects");
    subObjects = objects.iterator().next();

    assertFalse(subObjects.isEmpty());
    session.commit();
  }

  @Test
  public void testChangesValuesNestedTrackingSet() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    var objects = session.newEmbeddedSet();

    document.setProperty("objects", objects);
    var subObjects = session.newEmbeddedSet();
    objects.add(subObjects);

    var nestedDoc = (EntityImpl) session.newEmbeddedEntity();
    subObjects.add(nestedDoc);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    objects = document.getProperty("objects");
    subObjects = (EmbeddedSet<Object>) objects.iterator().next();
    subObjects.add("one");

    assertTrue(document.isDirty());
    var nestedTimiline =
        ((TrackedMultiValue<Object, Object>) subObjects).getTransactionTimeLine();
    assertEquals(1, nestedTimiline.getMultiValueChangeEvents().size());
    var multiValueChangeEvents =
        nestedTimiline.getMultiValueChangeEvents();
    assertEquals("one", multiValueChangeEvents.get(0).getValue());
    session.commit();
  }

  @Test
  public void testChangesValuesNestedTrackingList() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    var objects = session.newEmbeddedList();

    document.setEmbeddedList("objects", objects);
    var subObjects = session.newEmbeddedList();
    objects.add(subObjects);

    var nestedDoc = (EntityImpl) session.newEmbeddedEntity();
    subObjects.add(nestedDoc);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    objects = document.getEmbeddedList("objects");
    subObjects = (EmbeddedList<Object>) objects.iterator().next();
    subObjects.add("one");
    subObjects.add(session.newEmbeddedEntity());

    assertTrue(document.isDirty());
    var multiValueChangeEvents =
        ((TrackedMultiValue<Object, Object>) subObjects).getTransactionTimeLine()
            .getMultiValueChangeEvents();
    assertEquals(1, multiValueChangeEvents.get(0).getKey());
    assertEquals("one", multiValueChangeEvents.get(0).getValue());
    assertEquals(2, multiValueChangeEvents.get(1).getKey());
    assertTrue(multiValueChangeEvents.get(1).getValue() instanceof EntityImpl);
    session.commit();
  }

  @Test
  public void testChangesValuesNestedTrackingMap() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var objects = entity.<EmbeddedMap<Object>>newEmbeddedMap("objects");

    var subObjects = session.newEmbeddedMap();
    objects.put("first", subObjects);

    var nestedDoc = session.newEmbeddedEntity();
    subObjects.put("one", nestedDoc);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    objects = entity.getOrCreateEmbeddedMap("objects");
    subObjects = objects.values().iterator().next();
    subObjects.put("one", "String");
    subObjects.put("two", session.newEmbeddedEntity());

    assertTrue(entity.isDirty());
    var multiValueChangeEvents =
        ((TrackedMultiValue<?, ?>) subObjects).getTransactionTimeLine()
            .getMultiValueChangeEvents();
    assertEquals("one", multiValueChangeEvents.get(0).getKey());
    assertEquals("String", multiValueChangeEvents.get(0).getValue());
    assertEquals("two", multiValueChangeEvents.get(1).getKey());
    assertTrue(multiValueChangeEvents.get(1).getValue() instanceof EntityImpl);
    session.commit();
  }
}
