package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class DocumentTrackingTest extends BaseDBTest {
  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    if (!session.getMetadata().getSchema().existsClass("DocumentTrackingTestClass")) {
      final var trackedClass =
          session.getMetadata().getSchema().createClass("DocumentTrackingTestClass");
      trackedClass.createProperty("embeddedlist", PropertyType.EMBEDDEDLIST);
      trackedClass.createProperty("embeddedmap", PropertyType.EMBEDDEDMAP);
      trackedClass.createProperty("embeddedset", PropertyType.EMBEDDEDSET);
      trackedClass.createProperty("linkset", PropertyType.LINKSET);
      trackedClass.createProperty("linklist", PropertyType.LINKLIST);
      trackedClass.createProperty("linkmap", PropertyType.LINKMAP);
    }
  }

  public void testDocumentEmbeddedListTrackingAfterSave() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list, PropertyType.EMBEDDEDLIST);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, 1, "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(),
        List.of("embeddedlist"));
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterSave() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Map<String, String> map = session.newEmbeddedMap();
    map.put("key1", "value1");

    document.setProperty("embeddedmap", map, PropertyType.EMBEDDEDMAP);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.getProperty("embeddedmap");
    trackedMap.put("key2", "value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "key2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedmap"));
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterSave() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Set<String> set = session.newEmbeddedSet();
    set.add("value1");

    document.setProperty("embeddedset", set, PropertyType.EMBEDDEDSET);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<String> trackedSet = document.getProperty("embeddedset");
    trackedSet.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "value2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedset"));
    session.rollback();
  }

  public void testDocumentLinkSetTrackingAfterSave() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final var set = session.newLinkSet();
    set.add(docOne.getIdentity());

    document.setProperty("linkset", set, PropertyType.LINKSET);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<RID> trackedSet = document.getProperty("linkset");
    trackedSet.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkset");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linkset"));
    session.rollback();
  }

  public void testDocumentLinkListTrackingAfterSave() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final var list = session.newLinkList();
    list.add(docOne.getIdentity());

    document.setProperty("linklist", list, PropertyType.LINKLIST);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);

    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final List<RID> trackedList = document.getProperty("linklist");
    trackedList.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linklist");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linklist"));
    session.rollback();
  }

  public void testDocumentLinkMapTrackingAfterSave() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final var map = session.newLinkMap();
    map.put("key1", docOne.getIdentity());

    document.setProperty("linkmap", map, PropertyType.LINKMAP);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);

    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Map<String, RID> trackedMap = document.getProperty("linkmap");
    trackedMap.put("key2", docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkmap");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linkmap"));
    session.rollback();
  }

  public void testDocumentEmbeddedListTrackingAfterSaveCacheDisabled() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list, PropertyType.EMBEDDEDLIST);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, 1, "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(),
        List.of("embeddedlist"));
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterSaveCacheDisabled() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Map<String, String> map = session.newEmbeddedMap();
    map.put("key1", "value1");

    document.setProperty("embeddedmap", map, PropertyType.EMBEDDEDMAP);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.getProperty("embeddedmap");
    trackedMap.put("key2", "value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "key2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedmap"));
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterSaveCacheDisabled() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Set<String> set = session.newEmbeddedSet();
    set.add("value1");

    document.setProperty("embeddedset", set, PropertyType.EMBEDDEDSET);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<String> trackedSet = document.getProperty("embeddedset");
    trackedSet.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "value2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedset"));
    session.rollback();
  }

  public void testDocumentLinkSetTrackingAfterSaveCacheDisabled() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final var set = session.newLinkSet();
    set.add(docOne.getIdentity());

    document.setProperty("linkset", set, PropertyType.LINKSET);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<RID> trackedSet = document.getProperty("linkset");
    trackedSet.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkset");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linkset"));
    session.rollback();
  }

  public void testDocumentLinkListTrackingAfterSaveCacheDisabled() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final var list = session.newLinkList();
    list.add(docOne.getIdentity());

    document.setProperty("linklist", list, PropertyType.LINKLIST);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final List<RID> trackedList = document.getProperty("linklist");
    trackedList.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linklist");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linklist"));
    session.rollback();
  }

  public void testDocumentLinkMapTrackingAfterSaveCacheDisabled() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final var map = session.newLinkMap();
    map.put("key1", docOne.getIdentity());

    document.setProperty("linkmap", map, PropertyType.LINKMAP);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Map<String, RID> trackedMap = document.getProperty("linkmap");
    trackedMap.put("key2", docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkmap");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linkmap"));
    session.rollback();
  }

  public void testDocumentEmbeddedListTrackingAfterSaveWitClass() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, 1, "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);
    Assert.assertTrue(document.isDirty());

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(),
        List.of("embeddedlist"));
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterSaveWithClass() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Map<String, String> map = session.newEmbeddedMap();
    map.put("key1", "value1");

    document.setProperty("embeddedmap", map);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.getProperty("embeddedmap");
    trackedMap.put("key2", "value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "key2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedmap"));
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterSaveWithClass() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Set<String> set = session.newEmbeddedSet();
    set.add("value1");

    document.setProperty("embeddedset", set);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<String> trackedSet = document.getProperty("embeddedset");
    trackedSet.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "value2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedset"));
    session.rollback();
  }

  public void testDocumentLinkSetTrackingAfterSaveWithClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final var set = session.newLinkSet();
    set.add(docOne.getIdentity());

    document.setProperty("linkset", set);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<RID> trackedSet = document.getProperty("linkset");
    trackedSet.add(docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkset");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linkset"));
    session.rollback();
  }

  public void testDocumentLinkListTrackingAfterSaveWithClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final var list = session.newLinkList();
    list.add(docOne.getIdentity());

    document.setProperty("linklist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final List<RID> trackedList = document.getProperty("linklist");
    trackedList.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linklist");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linklist"));
    session.rollback();
  }

  public void testDocumentLinkMapTrackingAfterSaveWithClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final var map = session.newLinkMap();
    map.put("key1", docOne.getIdentity());

    document.setProperty("linkmap", map);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Map<String, RID> trackedMap = document.getProperty("linkmap");
    trackedMap.put("key2", docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkmap");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("linkmap"));
    session.rollback();
  }

  @Test
  public void testDocumentEmbeddedListTrackingAfterConversion() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    final Set<String> set = session.newEmbeddedSet();
    set.add("value1");

    document.setProperty("embeddedlist", set);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    try {
      document.getEmbeddedList("embeddedlist");
      fail("Embedded list to set convestion error must be thrown");
    } catch (DatabaseException e) {
      //
    }
    session.rollback();
  }

  @Test
  public void testDocumentEmbeddedSetTrackingFailAfterConversion() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    final List<String> set = session.newEmbeddedList();
    set.add("value1");

    document.setProperty("embeddedset", set);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    try {
      document.getEmbeddedSet("embeddedset");
      fail("Embedded list to set convestion error must be thrown");
    } catch (DatabaseException e) {
      //
    }
    session.rollback();
  }

  public void testDocumentEmbeddedListTrackingFailAfterReplace() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    final List<String> newTrackedList = new EntityEmbeddedListImpl<>(document);
    document.setProperty("embeddedlist", newTrackedList);
    newTrackedList.add("value3");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(),
        List.of("embeddedlist"));
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterReplace() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Map<String, String> map = session.newEmbeddedMap();
    map.put("key1", "value1");

    document.setProperty("embeddedmap", map);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.getProperty("embeddedmap");
    trackedMap.put("key2", "value2");

    final Map<String, String> newTrackedMap = new EntityEmbeddedMapImpl<>(document);
    document.setProperty("embeddedmap", newTrackedMap);
    newTrackedMap.put("key3", "value3");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedmap"));
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterReplace() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Set<String> set = session.newEmbeddedSet();
    set.add("value1");

    document.setProperty("embeddedset", set);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());

    final Set<String> trackedSet = document.getProperty("embeddedset");
    trackedSet.add("value2");

    final Set<String> newTrackedSet = new EntityEmbeddedSetImpl<>(document);
    document.setProperty("embeddedset", newTrackedSet);
    newTrackedSet.add("value3");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNull(timeLine);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(), List.of("embeddedset"));
    session.rollback();
  }

  public void testRemoveField() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    document.removeProperty("embeddedlist");

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(),
        List.of("embeddedlist"));
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  public void testReset() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  public void testUnload() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    final var rec = (RecordAbstract) document;
    rec.txEntry = null;
    rec.unsetDirty();
    document.unload();

    Assert.assertFalse(document.isDirty());
    session.rollback();
  }

  public void testUnsetDirty() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);
    document.setProperty("val", 1);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    final var rec = (RecordAbstract) document;
    rec.txEntry = null;
    rec.unsetDirty();

    Assert.assertFalse(document.isDirty());
    session.rollback();
  }

  public void testRemoveFieldUsingIterator() {
    session.begin();
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = session.newEmbeddedList();
    list.add("value1");

    document.setProperty("embeddedlist", list);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertTrue(document.getDirtyPropertiesBetweenCallbacks().isEmpty());
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getProperty("embeddedlist");
    trackedList.add("value2");

    final var propertyNameIterator = document.getPropertyNames().iterator();
    var name = propertyNameIterator.next();
    document.removeProperty(name);

    Assert.assertEquals(document.getDirtyPropertiesBetweenCallbacks(),
        List.of("embeddedlist"));
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }
}
