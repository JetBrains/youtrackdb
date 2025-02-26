package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class DocumentTrackingTest extends BaseDBTest {

  @Parameters(value = "remote")
  public DocumentTrackingTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    if (!session.getMetadata().getSchema().existsClass("DocumentTrackingTestClass")) {
      final var trackedClass =
          session.getMetadata().getSchema().createClass("DocumentTrackingTestClass");
      trackedClass.createProperty(session, "embeddedlist", PropertyType.EMBEDDEDLIST);
      trackedClass.createProperty(session, "embeddedmap", PropertyType.EMBEDDEDMAP);
      trackedClass.createProperty(session, "embeddedset", PropertyType.EMBEDDEDSET);
      trackedClass.createProperty(session, "linkset", PropertyType.LINKSET);
      trackedClass.createProperty(session, "linklist", PropertyType.LINKLIST);
      trackedClass.createProperty(session, "linkmap", PropertyType.LINKMAP);
    }
  }

  public void testDocumentEmbeddedListTrackingAfterSave() {
    var document = ((EntityImpl) session.newEntity());

    final List<String> list = new ArrayList<>();
    list.add("value1");

    document.field("embeddedlist", list, PropertyType.EMBEDDEDLIST);
    document.field("val", 1);

    session.begin();

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, 1, "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterSave() {
    var document = ((EntityImpl) session.newEntity());

    final Map<String, String> map = new HashMap<>();
    map.put("key1", "value1");

    document.field("embeddedmap", map, PropertyType.EMBEDDEDMAP);
    document.field("val", 1);

    session.begin();

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.field("embeddedmap");
    trackedMap.put("key2", "value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "key2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedmap"});
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterSave() {
    var document = ((EntityImpl) session.newEntity());

    final Set<String> set = new HashSet<>();
    set.add("value1");

    document.field("embeddedset", set, PropertyType.EMBEDDEDSET);
    document.field("val", 1);

    session.begin();

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<String> trackedSet = document.field("embeddedset");
    trackedSet.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "value2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedset"});
    session.rollback();
  }

  public void testDocumentLinkSetTrackingAfterSave() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final Set<RID> set = new HashSet<>();
    set.add(docOne.getIdentity());

    document.field("linkset", set, PropertyType.LINKSET);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<RID> trackedSet = document.field("linkset");
    trackedSet.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkset");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linkset"});
    session.rollback();
  }

  public void testDocumentLinkListTrackingAfterSave() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final List<RID> list = new ArrayList<>();
    list.add(docOne.getIdentity());

    document.field("linklist", list, PropertyType.LINKLIST);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);

    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final List<RID> trackedList = document.field("linklist");
    trackedList.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linklist");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linklist"});
    session.rollback();
  }

  public void testDocumentLinkMapTrackingAfterSave() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final Map<String, RID> map = new HashMap<>();
    map.put("key1", docOne.getIdentity());

    document.field("linkmap", map, PropertyType.LINKMAP);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);

    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Map<String, RID> trackedMap = document.field("linkmap");
    trackedMap.put("key2", docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkmap");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linkmap"});
    session.rollback();
  }

  public void testDocumentEmbeddedListTrackingAfterSaveCacheDisabled() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final List<String> list = new ArrayList<>();
    list.add("value1");

    document.field("embeddedlist", list, PropertyType.EMBEDDEDLIST);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, 1, "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterSaveCacheDisabled() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Map<String, String> map = new HashMap<>();
    map.put("key1", "value1");

    document.field("embeddedmap", map, PropertyType.EMBEDDEDMAP);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.field("embeddedmap");
    trackedMap.put("key2", "value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "key2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedmap"});
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterSaveCacheDisabled() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Set<String> set = new HashSet<>();
    set.add("value1");

    document.field("embeddedset", set, PropertyType.EMBEDDEDSET);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<String> trackedSet = document.field("embeddedset");
    trackedSet.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "value2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedset"});
    session.rollback();
  }

  public void testDocumentLinkSetTrackingAfterSaveCacheDisabled() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final Set<RID> set = new HashSet<>();
    set.add(docOne.getIdentity());

    document.field("linkset", set, PropertyType.LINKSET);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<RID> trackedSet = document.field("linkset");
    trackedSet.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkset");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linkset"});
    session.rollback();
  }

  public void testDocumentLinkListTrackingAfterSaveCacheDisabled() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final List<RID> list = new ArrayList<>();
    list.add(docOne.getIdentity());

    document.field("linklist", list, PropertyType.LINKLIST);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final List<RID> trackedList = document.field("linklist");
    trackedList.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linklist");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linklist"});
    session.rollback();
  }

  public void testDocumentLinkMapTrackingAfterSaveCacheDisabled() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity());

    final Map<String, RID> map = new HashMap<>();
    map.put("key1", docOne.getIdentity());

    document.field("linkmap", map, PropertyType.LINKMAP);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Map<String, RID> trackedMap = document.field("linkmap");
    trackedMap.put("key2", docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkmap");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linkmap"});
    session.rollback();
  }

  public void testDocumentEmbeddedListTrackingAfterSaveWitClass() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    session.begin();
    final List<String> list = new ArrayList<>();
    list.add("value1");

    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
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

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterSaveWithClass() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Map<String, String> map = new HashMap<>();
    map.put("key1", "value1");

    session.begin();
    document.field("embeddedmap", map);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.field("embeddedmap");
    trackedMap.put("key2", "value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "key2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedmap"});
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterSaveWithClass() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Set<String> set = new HashSet<>();
    set.add("value1");

    session.begin();
    document.field("embeddedset", set);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<String> trackedSet = document.field("embeddedset");
    trackedSet.add("value2");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNotNull(timeLine);

    Assert.assertNotNull(timeLine.getMultiValueChangeEvents());

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, "value2", "value2"));

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedset"});
    session.rollback();
  }

  public void testDocumentLinkSetTrackingAfterSaveWithClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Set<RID> set = new HashSet<>();
    set.add(docOne.getIdentity());

    document.field("linkset", set);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<RID> trackedSet = document.field("linkset");
    trackedSet.add(docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkset");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linkset"});
    session.rollback();
  }

  public void testDocumentLinkListTrackingAfterSaveWithClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<RID> list = new ArrayList<>();
    list.add(docOne.getIdentity());

    document.field("linklist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final List<RID> trackedList = document.field("linklist");
    trackedList.add(docTwo.getIdentity());

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linklist");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linklist"});
    session.rollback();
  }

  public void testDocumentLinkMapTrackingAfterSaveWithClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Map<String, RID> map = new HashMap<>();
    map.put("key1", docOne.getIdentity());

    document.field("linkmap", map);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Map<String, RID> trackedMap = document.field("linkmap");
    trackedMap.put("key2", docTwo.getIdentity());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("linkmap");
    Assert.assertNotNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"linkmap"});
    session.rollback();
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testDocumentEmbeddedListTrackingAfterConversion() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final Set<String> set = new HashSet<>();
    set.add("value1");

    document.field("embeddedlist", set);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.getEmbeddedList("embeddedlist");
    trackedList.add("value2");
    session.rollback();
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testDocumentEmbeddedSetTrackingFailAfterConversion() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

    final List<String> list = new ArrayList<>();
    list.add("value1");

    document.field("embeddedset", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<String> trackedSet = document.getEmbeddedSet("embeddedset");
    trackedSet.add("value2");
    session.rollback();
  }

  public void testDocumentEmbeddedListTrackingFailAfterReplace() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    final List<String> newTrackedList = new TrackedList<>(document);
    document.field("embeddedlist", newTrackedList);
    newTrackedList.add("value3");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");
    Assert.assertNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    session.rollback();
  }

  public void testDocumentEmbeddedMapTrackingAfterReplace() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Map<String, String> map = new HashMap<>();
    map.put("key1", "value1");

    session.begin();
    document.field("embeddedmap", map);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final Map<String, String> trackedMap = document.field("embeddedmap");
    trackedMap.put("key2", "value2");

    final Map<String, String> newTrackedMap = new TrackedMap<>(document);
    document.field("embeddedmap", newTrackedMap);
    newTrackedMap.put("key3", "value3");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedmap");
    Assert.assertNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedmap"});
    session.rollback();
  }

  public void testDocumentEmbeddedSetTrackingAfterReplace() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final Set<String> set = new HashSet<>();
    set.add("value1");

    session.begin();
    document.field("embeddedset", set);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertFalse(document.isDirty());
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});

    final Set<String> trackedSet = document.field("embeddedset");
    trackedSet.add("value2");

    final Set<String> newTrackedSet = new TrackedSet<>(document);
    document.field("embeddedset", newTrackedSet);
    newTrackedSet.add("value3");

    Assert.assertTrue(document.isDirty());

    final MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedset");
    Assert.assertNull(timeLine);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedset"});
    session.rollback();
  }

  public void testRemoveField() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    document.removeField("embeddedlist");

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  public void testTrackingChangesSwitchedOff() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    document.setTrackingChanges(false);

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  public void testTrackingChangesSwitchedOn() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    document.setTrackingChanges(false);
    document.setTrackingChanges(true);

    trackedList.add("value3");

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    Assert.assertTrue(document.isDirty());
    Assert.assertNotNull(document.getCollectionTimeLine("embeddedlist"));

    final List<MultiValueChangeEvent> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent(ChangeType.ADD, 2, "value3"));

    MultiValueChangeTimeLine timeLine = document.getCollectionTimeLine("embeddedlist");

    Assert.assertEquals(timeLine.getMultiValueChangeEvents(), firedEvents);
    session.rollback();
  }

  public void testReset() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  public void testClear() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    document.clear();

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  public void testUnload() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    RecordInternal.unsetDirty(document);
    document.unload();

    Assert.assertFalse(document.isDirty());
    session.rollback();
  }

  public void testUnsetDirty() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);
    document.field("val", 1);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    RecordInternal.unsetDirty(document);

    Assert.assertFalse(document.isDirty());
    session.rollback();
  }

  public void testRemoveFieldUsingIterator() {
    var document = ((EntityImpl) session.newEntity("DocumentTrackingTestClass"));

    final List<String> list = new ArrayList<>();
    list.add("value1");

    session.begin();
    document.field("embeddedlist", list);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{});
    Assert.assertFalse(document.isDirty());

    final List<String> trackedList = document.field("embeddedlist");
    trackedList.add("value2");

    final Iterator fieldIterator = document.iterator();
    fieldIterator.next();
    fieldIterator.remove();

    Assert.assertEquals(document.getCallbackDirtyProperties(), new String[]{"embeddedlist"});
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }
}
