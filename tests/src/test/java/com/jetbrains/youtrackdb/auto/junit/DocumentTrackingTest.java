/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import static org.junit.Assert.fail;

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
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of DocumentTrackingTest. Original test class:
 * com.jetbrains.youtrackdb.auto.DocumentTrackingTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DocumentTrackingTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    DocumentTrackingTest instance = new DocumentTrackingTest();
    instance.beforeClass();

    if (!instance.session.getMetadata().getSchema().existsClass("DocumentTrackingTestClass")) {
      final var trackedClass =
          instance.session.getMetadata().getSchema().createClass("DocumentTrackingTestClass");
      trackedClass.createProperty("embeddedlist", PropertyType.EMBEDDEDLIST);
      trackedClass.createProperty("embeddedmap", PropertyType.EMBEDDEDMAP);
      trackedClass.createProperty("embeddedset", PropertyType.EMBEDDEDSET);
      trackedClass.createProperty("linkset", PropertyType.LINKSET);
      trackedClass.createProperty("linklist", PropertyType.LINKLIST);
      trackedClass.createProperty("linkmap", PropertyType.LINKMAP);
    }
  }

  /**
   * Original test method: testDocumentEmbeddedListTrackingAfterSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:43
   */
  @Test
  public void test01_DocumentEmbeddedListTrackingAfterSave() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedlist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedMapTrackingAfterSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:82
   */
  @Test
  public void test02_DocumentEmbeddedMapTrackingAfterSave() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedSetTrackingAfterSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:120
   */
  @Test
  public void test03_DocumentEmbeddedSetTrackingAfterSave() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkSetTrackingAfterSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:158
   */
  @Test
  public void test04_DocumentLinkSetTrackingAfterSave() {
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

    Assert.assertEquals(List.of("linkset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkListTrackingAfterSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:192
   */
  @Test
  public void test05_DocumentLinkListTrackingAfterSave() {
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

    Assert.assertEquals(List.of("linklist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkMapTrackingAfterSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:227
   */
  @Test
  public void test06_DocumentLinkMapTrackingAfterSave() {
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

    Assert.assertEquals(List.of("linkmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedListTrackingAfterSaveCacheDisabled Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:260
   */
  @Test
  public void test07_DocumentEmbeddedListTrackingAfterSaveCacheDisabled() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedlist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedMapTrackingAfterSaveCacheDisabled Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:299
   */
  @Test
  public void test08_DocumentEmbeddedMapTrackingAfterSaveCacheDisabled() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedSetTrackingAfterSaveCacheDisabled Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:337
   */
  @Test
  public void test09_DocumentEmbeddedSetTrackingAfterSaveCacheDisabled() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkSetTrackingAfterSaveCacheDisabled Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:375
   */
  @Test
  public void test10_DocumentLinkSetTrackingAfterSaveCacheDisabled() {
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

    Assert.assertEquals(List.of("linkset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkListTrackingAfterSaveCacheDisabled Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:409
   */
  @Test
  public void test11_DocumentLinkListTrackingAfterSaveCacheDisabled() {
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

    Assert.assertEquals(List.of("linklist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkMapTrackingAfterSaveCacheDisabled Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:443
   */
  @Test
  public void test12_DocumentLinkMapTrackingAfterSaveCacheDisabled() {
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

    Assert.assertEquals(List.of("linkmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedListTrackingAfterSaveWitClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:475
   */
  @Test
  public void test13_DocumentEmbeddedListTrackingAfterSaveWitClass() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());
    Assert.assertTrue(document.isDirty());

    Assert.assertEquals(List.of("embeddedlist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedMapTrackingAfterSaveWithClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:515
   */
  @Test
  public void test14_DocumentEmbeddedMapTrackingAfterSaveWithClass() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedSetTrackingAfterSaveWithClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:553
   */
  @Test
  public void test15_DocumentEmbeddedSetTrackingAfterSaveWithClass() {
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

    Assert.assertEquals(firedEvents, timeLine.getMultiValueChangeEvents());

    Assert.assertEquals(List.of("embeddedset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkSetTrackingAfterSaveWithClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:591
   */
  @Test
  public void test16_DocumentLinkSetTrackingAfterSaveWithClass() {
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

    Assert.assertEquals(List.of("linkset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkListTrackingAfterSaveWithClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:623
   */
  @Test
  public void test17_DocumentLinkListTrackingAfterSaveWithClass() {
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

    Assert.assertEquals(List.of("linklist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentLinkMapTrackingAfterSaveWithClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:657
   */
  @Test
  public void test18_DocumentLinkMapTrackingAfterSaveWithClass() {
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

    Assert.assertEquals(List.of("linkmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedListTrackingAfterConversion Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:689
   */
  @Test
  public void test19_DocumentEmbeddedListTrackingAfterConversion() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

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

  /**
   * Original test method: testDocumentEmbeddedSetTrackingFailAfterConversion Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:717
   */
  @Test
  public void test20_DocumentEmbeddedSetTrackingFailAfterConversion() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());

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

  /**
   * Original test method: testDocumentEmbeddedListTrackingFailAfterReplace Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:745
   */
  @Test
  public void test21_DocumentEmbeddedListTrackingFailAfterReplace() {
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

    Assert.assertEquals(List.of("embeddedlist"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedMapTrackingAfterReplace Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:780
   */
  @Test
  public void test22_DocumentEmbeddedMapTrackingAfterReplace() {
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

    Assert.assertEquals(List.of("embeddedmap"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testDocumentEmbeddedSetTrackingAfterReplace Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:814
   */
  @Test
  public void test23_DocumentEmbeddedSetTrackingAfterReplace() {
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

    Assert.assertEquals(List.of("embeddedset"), document.getDirtyPropertiesBetweenCallbacks());
    session.rollback();
  }

  /**
   * Original test method: testRemoveField Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:848
   */
  @Test
  public void test24_RemoveField() {
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

    Assert.assertEquals(List.of("embeddedlist"), document.getDirtyPropertiesBetweenCallbacks());
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }

  /**
   * Original test method: testReset Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:878
   */
  @Test
  public void test25_Reset() {
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

  /**
   * Original test method: testUnload Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:907
   */
  @Test
  public void test26_Unload() {
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

  /**
   * Original test method: testUnsetDirty Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:937
   */
  @Test
  public void test27_UnsetDirty() {
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

  /**
   * Original test method: testRemoveFieldUsingIterator Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DocumentTrackingTest.java:966
   */
  @Test
  public void test28_RemoveFieldUsingIterator() {
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

    Assert.assertEquals(List.of("embeddedlist"), document.getDirtyPropertiesBetweenCallbacks());
    Assert.assertTrue(document.isDirty());
    Assert.assertNull(document.getCollectionTimeLine("embeddedlist"));
    session.rollback();
  }
}
