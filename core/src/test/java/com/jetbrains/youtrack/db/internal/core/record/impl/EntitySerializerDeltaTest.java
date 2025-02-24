package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.Test;

public class EntitySerializerDeltaTest extends DbTestBase {

  @Test
  public void testGetFromOriginalSimpleDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    var constantFieldName = "constantField";
    var originalValue = "orValue";
    var testValue = "testValue";
    var removeField = "removeField";

    doc.setProperty(fieldName, originalValue);
    doc.setProperty(constantFieldName, "someValue");
    doc.setProperty(removeField, "removeVal");

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    doc.setProperty(fieldName, testValue);
    doc.removeProperty(removeField);
    // test serialization/deserialization
    var delta = DocumentSerializerDelta.instance();
    var bytes = delta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    delta.deserializeDelta(session, bytes, doc);
    assertEquals(testValue, doc.field(fieldName));
    assertNull(doc.field(removeField));
    session.rollback();
  }

  @Test
  public void testGetFromNestedDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var nestedDoc = (EntityImpl) session.newEmbededEntity(claz.getName(session));
    var fieldName = "testField";
    var constantFieldName = "constantField";
    var originalValue = "orValue";
    var testValue = "testValue";
    var nestedDocField = "nestedField";

    nestedDoc.setProperty(fieldName, originalValue);
    nestedDoc.setProperty(constantFieldName, "someValue1");

    doc.setProperty(constantFieldName, "someValue2");
    doc.setProperty(nestedDocField, nestedDoc, PropertyType.EMBEDDED);

    var originalDoc = (EntityImpl) session.newEntity();
    originalDoc.setProperty(constantFieldName, "someValue2");
    originalDoc.setProperty(nestedDocField, nestedDoc, PropertyType.EMBEDDED);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    nestedDoc = doc.field(nestedDocField);
    nestedDoc.setProperty(fieldName, testValue);

    doc.setProperty(nestedDocField, nestedDoc, PropertyType.EMBEDDED);

    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, doc);
    // test serialization/deserialization
    originalDoc = session.bindToSession(originalDoc);
    serializerDelta.deserializeDelta(session, bytes, originalDoc);
    nestedDoc = originalDoc.field(nestedDocField);
    assertEquals(testValue, nestedDoc.field(fieldName));
    session.rollback();
  }

  @Test
  public void testListDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);

    var fieldName = "testField";
    List<String> originalValue = new ArrayList<>();
    originalValue.add("one");
    originalValue.add("two");
    originalValue.add("toRemove");

    doc.newEmbeddedList(fieldName).addAll(originalValue);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    List<String> newArray = doc.field(fieldName);
    newArray.set(1, "three");
    newArray.remove("toRemove");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();
    session.begin();

    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);

    List<?> checkList = doc.getProperty(fieldName);
    assertEquals("three", checkList.get(1));
    assertFalse(checkList.contains("toRemove"));
    session.rollback();
  }

  @Test
  public void testSetDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);

    var fieldName = "testField";
    Set<String> originalValue = doc.newEmbeddedSet(fieldName);
    originalValue.add("one");
    originalValue.add("toRemove");
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    Set<String> newArray = doc.field(fieldName);
    newArray.add("three");
    newArray.remove("toRemove");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);

    Set<String> checkSet = doc.field(fieldName);
    assertTrue(checkSet.contains("three"));
    assertFalse(checkSet.contains("toRemove"));
    session.rollback();
  }

  @Test
  public void testSetOfSetsDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    Set<Set<String>> originalValue = new HashSet<>();
    for (var i = 0; i < 2; i++) {
      Set<String> containedSet = session.newEmbeddedSet();
      containedSet.add("one");
      containedSet.add("two");
      originalValue.add(containedSet);
    }
    doc.newEmbeddedSet(fieldName).addAll(originalValue);
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    @SuppressWarnings("unchecked")
    var newSet = ((Set<Set<String>>) doc.getProperty(fieldName)).iterator().next();
    newSet.add("three");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);

    Set<Set<String>> checkSet = doc.field(fieldName);
    assertTrue(checkSet.iterator().next().contains("three"));
    session.rollback();
  }

  @Test
  public void testListOfListsDelta() {

    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    List<List<String>> originalValue = new ArrayList<>();

    for (var i = 0; i < 2; i++) {
      List<String> containedList = session.newEmbeddedList();
      containedList.add("one");
      containedList.add("two");

      originalValue.add(containedList);
    }

    doc.newEmbeddedList(fieldName).addAll(originalValue);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    var newList = doc.<List<List<String>>>field(fieldName).getFirst();
    newList.set(1, "three");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();
    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);

    List<List<String>> checkList = doc.field(fieldName);
    assertEquals("three", checkList.getFirst().get(1));
    session.rollback();
  }

  @Test
  public void testListOfDocsDelta() {
    var fieldName = "testField";

    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);

    var constantField = "constField";
    var constValue = "ConstValue";
    var variableField = "varField";
    List<EntityImpl> originalValue = doc.newEmbeddedList(fieldName);
    for (var i = 0; i < 2; i++) {
      var containedDoc = (EntityImpl) session.newEmbededEntity();
      containedDoc.setProperty(constantField, constValue);
      containedDoc.setProperty(variableField, "one" + i);
      originalValue.add(containedDoc);
    }
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    @SuppressWarnings("unchecked")
    var testDoc = ((List<EntityImpl>) doc.getProperty(fieldName)).get(1);
    testDoc.setProperty(variableField, "two");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);
    List<EntityImpl> checkList = doc.field(fieldName);
    var checkDoc = checkList.get(1);
    assertEquals(constValue, checkDoc.field(constantField));
    assertEquals("two", checkDoc.field(variableField));
    session.rollback();
  }

  @Test
  public void testListOfListsOfDocumentDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    var constantField = "constField";
    var constValue = "ConstValue";
    var variableField = "varField";

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    List<List<Entity>> originalValue = entity.newEmbeddedList(fieldName);
    for (var i = 0; i < 2; i++) {
      var containedList = session.<Entity>newEmbeddedList();
      var d1 = (EntityImpl) session.newEmbededEntity();
      d1.setProperty(constantField, constValue);
      d1.setProperty(variableField, "one");
      var d2 = (EntityImpl) session.newEmbededEntity();
      d2.setProperty(constantField, constValue);
      containedList.add(d1);
      containedList.add(d2);
      originalValue.add(containedList);
    }

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    originalValue = entity.getProperty(fieldName);
    var d1 = originalValue.getFirst().getFirst().getEntity(session);
    d1.setProperty(variableField, "two");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);

    List<List<EntityImpl>> checkList = entity.field(fieldName);
    assertEquals("two", checkList.getFirst().getFirst().field(variableField));
    session.rollback();
  }

  @Test
  public void testListOfListsOfListDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    List<List<List<String>>> originalValue = entity.newEmbeddedList(fieldName);
    for (var i = 0; i < 2; i++) {
      List<List<String>> containedList = session.newEmbeddedList();
      for (var j = 0; j < 2; j++) {
        List<String> innerList = session.newEmbeddedList();
        innerList.add("el1" + j + i);
        innerList.add("el2" + j + i);
        containedList.add(innerList);
      }
      originalValue.add(containedList);
    }

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    @SuppressWarnings("unchecked")
    var innerList = ((List<List<List<String>>>) entity.field(fieldName)).getFirst()
        .getFirst();
    innerList.set(0, "changed");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();
    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    List<List<List<String>>> checkList = entity.field(fieldName);
    assertEquals("changed", checkList.getFirst().getFirst().getFirst());
    session.rollback();
  }

  @Test
  public void testListOfDocsWithList() {
    var fieldName = "testField";

    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);

    var constantField = "constField";
    var constValue = "ConstValue";
    var variableField = "varField";

    List<EntityImpl> originalValue = entity.newEmbeddedList(fieldName);
    for (var i = 0; i < 2; i++) {
      var containedDoc = (EntityImpl) session.newEmbededEntity();
      containedDoc.setProperty(constantField, constValue);
      List<String> listField = new ArrayList<>();
      for (var j = 0; j < 2; j++) {
        listField.add("Some" + j);
      }
      containedDoc.newEmbeddedList(variableField).addAll(listField);
      originalValue.add(containedDoc);
    }

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    @SuppressWarnings("unchecked")
    var testDoc = ((List<EntityImpl>) entity.field(fieldName)).get(1);
    List<String> currentList = testDoc.field(variableField);
    currentList.set(0, "changed");
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);

    List<EntityImpl> checkList = entity.field(fieldName);
    var checkDoc = checkList.get(1);
    List<String> checkInnerList = checkDoc.field(variableField);
    assertEquals("changed", checkInnerList.getFirst());
    session.rollback();
  }

  @Test
  public void testListAddDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);

    var fieldName = "testField";
    List<String> originalValue = entity.newEmbeddedList(fieldName);
    originalValue.add("one");
    originalValue.add("two");
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    List<String> newArray = entity.field(fieldName);
    newArray.add("three");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();
    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);

    List<String> checkList = entity.field(fieldName);
    assertEquals(3, checkList.size());
    session.rollback();
  }

  @Test
  public void testListOfListAddDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);

    var fieldName = "testField";
    List<List<String>> originalList = entity.newEmbeddedList(fieldName);
    for (var i = 0; i < 2; i++) {
      List<String> nestedList = session.newEmbeddedList();
      nestedList.add("one");
      nestedList.add("two");
      originalList.add(nestedList);
    }
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    @SuppressWarnings("unchecked")
    var newArray = ((List<List<String>>) entity.field(fieldName)).getFirst();
    newArray.add("three");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);

    List<List<String>> rootList = entity.field(fieldName);
    var checkList = rootList.getFirst();
    assertEquals(3, checkList.size());
    session.rollback();
  }

  @Test
  public void testListRemoveDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);

    var fieldName = "testField";
    List<String> originalValue = doc.newEmbeddedList(fieldName);
    originalValue.add("one");
    originalValue.add("two");
    originalValue.add("three");

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    List<String> newArray = doc.field(fieldName);
    newArray.removeFirst();
    newArray.removeFirst();

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);
    List<String> checkList = doc.field(fieldName);
    assertEquals("three", checkList.getFirst());
    session.rollback();
  }

  @Test
  public void testAddDocFieldDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    var constantFieldName = "constantField";
    var testValue = "testValue";

    entity.setProperty(constantFieldName + "1", "someValue1");
    entity.setProperty(constantFieldName, "someValue");

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    entity.setProperty(fieldName, testValue);

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    assertEquals(testValue, entity.field(fieldName));
    session.rollback();
  }

  @Test
  public void testRemoveCreateDocFieldDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    var constantFieldName = "constantField";
    var testValue = "testValue";

    entity.setProperty(fieldName, testValue);
    entity.setProperty(constantFieldName, "someValue");

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    entity.removeProperty(fieldName);
    entity.setProperty("other", "new");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);

    assertFalse(entity.hasProperty(fieldName));
    assertEquals("new", entity.getProperty("other"));
    session.rollback();
  }

  @Test
  public void testRemoveNestedDocFieldDelta() {
    var nestedFieldName = "nested";

    var claz = session.createClassIfNotExist("TestClass");
    claz.createProperty(session, nestedFieldName, PropertyType.EMBEDDED);

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    var constantFieldName = "constantField";
    var testValue = "testValue";

    entity.setProperty(fieldName, testValue);
    entity.setProperty(constantFieldName, "someValue");

    var rootDoc = (EntityImpl) session.newEntity(claz);
    rootDoc.setProperty(nestedFieldName, entity);
    session.commit();

    session.begin();
    rootDoc = session.bindToSession(rootDoc);

    entity = rootDoc.field(nestedFieldName);
    entity.removeProperty(fieldName);

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, rootDoc);
    session.rollback();

    session.begin();
    rootDoc = session.bindToSession(rootDoc);
    serializerDelta.deserializeDelta(session, bytes, rootDoc);
    EntityImpl nested = rootDoc.field(nestedFieldName);
    assertFalse(nested.hasProperty(fieldName));
    session.rollback();
  }

  @Test
  public void testRemoveFieldListOfDocsDelta() {
    var fieldName = "testField";

    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);

    var constantField = "constField";
    var constValue = "ConstValue";
    var variableField = "varField";
    List<EntityImpl> originalValue = entity.newEmbeddedList(fieldName);
    for (var i = 0; i < 2; i++) {
      var containedDoc = (EntityImpl) session.newEmbededEntity();
      containedDoc.setProperty(constantField, constValue);
      containedDoc.setProperty(variableField, "one" + i);
      originalValue.add(containedDoc);
    }
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    @SuppressWarnings("unchecked")
    EntityImpl testDoc = ((List<Identifiable>) entity.field(fieldName)).get(1).getRecord(session);
    testDoc.removeProperty(variableField);
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    List<Identifiable> checkList = entity.field(fieldName);
    EntityImpl checkDoc = checkList.get(1).getRecord(session);
    assertEquals(constValue, checkDoc.field(constantField));
    assertFalse(checkDoc.hasProperty(variableField));
    session.rollback();
  }

  @Test
  public void testUpdateEmbeddedMapDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    Map<String, String> mapValue = entity.newEmbeddedMap(fieldName);
    mapValue.put("first", "one");
    mapValue.put("second", "two");
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    Map<String, String> containedMap = entity.field(fieldName);
    containedMap.put("first", "changed");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);

    containedMap = entity.field(fieldName);
    assertEquals("changed", containedMap.get("first"));
    session.rollback();
  }

  @Test
  public void testUpdateListOfEmbeddedMapDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    List<Map<String, String>> originalValue = new ArrayList<>();
    for (var i = 0; i < 2; i++) {
      Map<String, String> mapValue = session.newEmbeddedMap();
      mapValue.put("first", "one");
      mapValue.put("second", "two");
      originalValue.add(mapValue);
    }

    doc.newEmbeddedList(fieldName).addAll(originalValue);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    @SuppressWarnings("unchecked")
    var containedMap = ((List<Map<String, String>>) doc.field(
        fieldName)).getFirst();
    containedMap.put("first", "changed");
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);

    //noinspection unchecked
    containedMap = ((List<Map<String, String>>) doc.field(fieldName)).get(0);
    assertEquals("changed", containedMap.get("first"));
    //noinspection unchecked
    containedMap = ((List<Map<String, String>>) doc.field(fieldName)).get(1);
    assertEquals("one", containedMap.get("first"));
    session.rollback();
  }

  @Test
  public void testUpdateDocInMapDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    Map<String, EntityImpl> mapValue = entity.newEmbeddedMap(fieldName);
    var d1 = (EntityImpl) session.newEmbededEntity();
    d1.setProperty("f1", "v1");
    mapValue.put("first", d1);
    var d2 = (EntityImpl) session.newEmbededEntity();
    d2.setProperty("f2", "v2");
    mapValue.put("second", d2);
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);

    Map<String, EntityImpl> containedMap = entity.field(fieldName);
    var changeDoc = containedMap.get("first");
    changeDoc.setProperty("f1", "changed");

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    containedMap = entity.field(fieldName);
    var containedDoc = containedMap.get("first");
    assertEquals("changed", containedDoc.field("f1"));
    session.rollback();
  }

  @Test
  public void testListOfMapsUpdateDelta() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    var originalList = session.<Map<String, String>>newEmbeddedList();
    var copyList = session.<Map<String, String>>newEmbeddedList();

    Map<String, String> mapValue1 = session.newEmbeddedMap();
    mapValue1.put("first", "one");
    mapValue1.put("second", "two");
    originalList.add(mapValue1);
    var mapValue1Copy = session.newEmbeddedMap(mapValue1);
    copyList.add(mapValue1Copy);

    Map<String, String> mapValue2 = session.newEmbeddedMap();
    mapValue2.put("third", "three");
    mapValue2.put("forth", "four");
    originalList.add(mapValue2);
    var mapValue2Copy = session.newEmbeddedMap(mapValue2);
    copyList.add(mapValue2Copy);

    doc.field(fieldName, originalList);
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    var containedMap = doc.<Map<String, String>>getEmbeddedList(
        fieldName).getFirst();
    containedMap.put("first", "changed");
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();
    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);

    containedMap = doc.<Map<String, String>>getEmbeddedList(fieldName).getFirst();
    assertEquals("changed", containedMap.get("first"));
    session.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaAddWithCopy() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";

    var first = (EntityImpl) session.newEntity(claz);
    var second = (EntityImpl) session.newEntity(claz);

    var ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);

    var originalDoc = doc;

    var third = (EntityImpl) session.newEntity(claz);
    session.commit();

    session.begin();
    first = session.bindToSession(first);
    second = session.bindToSession(second);
    third = session.bindToSession(third);

    ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    ridBag.add(third.getIdentity());

    doc = session.bindToSession(doc);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, doc);
    originalDoc = session.bindToSession(originalDoc);
    serializerDelta.deserializeDelta(session, bytes, originalDoc);

    RidBag mergedRidbag = originalDoc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    session.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaRemoveWithCopy() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";

    var first = (EntityImpl) session.newEntity(claz);
    var second = (EntityImpl) session.newEntity(claz);
    var third = (EntityImpl) session.newEntity(claz);
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    first = session.bindToSession(first);
    second = session.bindToSession(second);
    third = session.bindToSession(third);

    var ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    ridBag.add(third.getIdentity());

    entity.field(fieldName, ridBag, PropertyType.LINKBAG);
    session.commit();

    session.begin();
    first = session.bindToSession(first);
    second = session.bindToSession(second);
    entity = session.bindToSession(entity);

    ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    entity.field(fieldName, ridBag, PropertyType.LINKBAG);

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    RidBag mergedRidbag = entity.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    session.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaAdd() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";

    var first = (EntityImpl) session.newEntity(claz);
    var second = (EntityImpl) session.newEntity(claz);

    var ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    entity.field(fieldName, ridBag, PropertyType.LINKBAG);
    session.commit();

    entity = session.bindToSession(entity);

    session.begin();
    var third = (EntityImpl) session.newEntity(claz);
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    third = session.bindToSession(third);

    ridBag = entity.getProperty(fieldName);
    ridBag.add(third.getIdentity());

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    RidBag mergedRidbag = entity.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    session.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaRemove() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var entity = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";

    var first = (EntityImpl) session.newEntity(claz);
    var second = (EntityImpl) session.newEntity(claz);
    var third = (EntityImpl) session.newEntity(claz);

    var ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    ridBag.add(third.getIdentity());
    entity.field(fieldName, ridBag, PropertyType.LINKBAG);
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    ridBag = entity.getProperty(fieldName);
    ridBag.remove(third.getIdentity());

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, entity);
    session.rollback();

    session.begin();
    entity = session.bindToSession(entity);
    serializerDelta.deserializeDelta(session, bytes, entity);
    RidBag mergedRidbag = entity.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    session.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaChangeWithCopy() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    var fieldName = "testField";
    var first = (EntityImpl) session.newEntity(claz);
    var second = (EntityImpl) session.newEntity(claz);
    var third = (EntityImpl) session.newEntity(claz);

    var ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(second.getIdentity());
    ridBag.add(third.getIdentity());
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);

    ridBag = new RidBag(session);
    ridBag.add(first.getIdentity());
    ridBag.add(third.getIdentity());
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);

    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();

    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);
    RidBag mergedRidbag = doc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    session.rollback();
  }

  @Test
  public void testDeltaNullValues() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    doc.setProperty("one", "value");
    doc.newEmbeddedList("list").add("test");
    doc.newEmbeddedSet("set").addAll(new HashSet<>(List.of("test")));
    Map<String, String> map = new HashMap<>();
    map.put("two", "value");
    doc.newEmbeddedMap("map").putAll(map);
    Identifiable link = session.newEntity("testClass");
    doc.newLinkList("linkList").add(link);
    doc.newLinkSet("linkSet").add(link);

    var linkMap = doc.newLinkMap("linkMap");
    linkMap.put("two", link);
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    doc.setProperty("one", null);
    doc.<List<String>>getProperty("list").add(null);
    doc.<Set<String>>getProperty("set").add(null);
    doc.<Map<String, String>>getProperty("map").put("nullValue", null);
    doc.<List<Identifiable>>getProperty("linkList").add(null);
    doc.<Set<Identifiable>>getProperty("linkSet").add(null);
    doc.<Map<String, Identifiable>>getProperty("linkMap").put("nullValue", null);
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();

    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();
    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);
    assertTrue(doc.getEmbeddedList("list").contains(null));
    assertTrue(doc.getEmbeddedSet("set").contains(null));
    assertTrue(doc.getEmbeddedMap("map").containsKey("nullValue"));
    assertTrue(doc.getLinkList("linkList").contains(null));
    assertTrue(doc.getLinkSet("linkSet").contains(null));
    assertTrue(doc.getLinkMap("linkMap").containsKey("nullValue"));
    session.rollback();
  }

  @Test
  public void testDeltaLinkAllCases() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    Identifiable link = session.newEntity("testClass");
    var link1 = (DBRecord) session.newEntity("testClass");
    doc.newLinkList("linkList").addAll(Arrays.asList(link, link1, link1));
    doc.newLinkSet("linkSet").addAll(new HashSet<>(Arrays.asList(link, link1)));

    var linkMap = doc.newLinkMap("linkMap");
    linkMap.put("one", link);
    linkMap.put("two", link1);
    linkMap.put("three", link1);

    var link2 = session.newEntity("testClass");
    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    link2 = session.bindToSession(link2);
    link1 = session.bindToSession(link1);

    doc.<List<Identifiable>>getProperty("linkList").set(1, link2);
    doc.<List<Identifiable>>getProperty("linkList").remove(link1);
    doc.<List<Identifiable>>getProperty("linkList").add(link2);

    doc.<Set<Identifiable>>getProperty("linkSet").add(link2);
    doc.<Set<Identifiable>>getProperty("linkSet").remove(link1);
    doc.<Map<String, Identifiable>>getProperty("linkMap").put("new", link2);
    doc.<Map<String, Identifiable>>getProperty("linkMap").put("three", link2);
    doc.<Map<String, Identifiable>>getProperty("linkMap").remove("two");
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();
    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);
    assertFalse(doc.<List<Identifiable>>getProperty("linkList").contains(link1));
    assertTrue(doc.<List<Identifiable>>getProperty("linkList").contains(link2));
    assertEquals(doc.<List<Identifiable>>getProperty("linkList").get(1), link2);
    assertTrue(doc.<Set<Identifiable>>getProperty("linkSet").contains(link2));
    assertFalse(doc.<Set<Identifiable>>getProperty("linkSet").contains(link1));
    assertEquals(doc.<Map<String, Identifiable>>getProperty("linkMap").get("new"), link2);
    assertEquals(doc.<Map<String, Identifiable>>getProperty("linkMap").get("three"), link2);
    assertTrue(doc.<Map<String, Identifiable>>getProperty("linkMap").containsKey("one"));
    assertFalse(doc.<Map<String, Identifiable>>getProperty("linkMap").containsKey("two"));
    session.rollback();
  }

  @Test
  public void testDeltaAllCasesMap() {
    var claz = session.createClassIfNotExist("TestClass");

    session.begin();
    var doc = (EntityImpl) session.newEntity(claz);
    Map<String, String> map = new HashMap<>();
    map.put("two", "value");
    doc.newEmbeddedMap("map").putAll(map);
    Map<String, String> map1 = new HashMap<>();
    map1.put("two", "value");
    map1.put("one", "other");
    Map<String, Map<String, String>> mapNested = doc.newEmbeddedMap("mapNested");
    Map<String, String> nested = session.newEmbeddedMap();
    nested.put("one", "value");
    mapNested.put("nest", nested);
    doc.newEmbeddedMap("map1").putAll(map1);

    Map<String, Entity> mapEmbedded = doc.newEmbeddedMap("mapEmbedded");

    var embedded = session.newEmbededEntity();
    embedded.setProperty("other", 1);
    mapEmbedded.put("first", embedded);

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    var embedded1 = session.newEmbededEntity();
    embedded1.setProperty("other", 1);
    doc.<Map<String, Entity>>getProperty("mapEmbedded").put("newDoc", embedded1);
    doc.<Map<String, String>>getProperty("map").put("value", "other");
    doc.<Map<String, String>>getProperty("map").put("two", "something");
    doc.<Map<String, String>>getProperty("map1").remove("one");
    doc.<Map<String, String>>getProperty("map1").put("two", "something");
    doc.<Map<String, Map<String, String>>>getProperty("mapNested")
        .get("nest")
        .put("other", "value");
    // test serialization/deserialization
    var serializerDelta = DocumentSerializerDelta.instance();
    var bytes = serializerDelta.serializeDelta(session, doc);
    session.rollback();
    session.begin();
    doc = session.bindToSession(doc);
    serializerDelta.deserializeDelta(session, bytes, doc);
    assertNotNull((doc.<Map<String, String>>getProperty("mapEmbedded")).get("newDoc"));
    assertEquals(
        doc.<Map<String, Entity>>getProperty("mapEmbedded")
            .get("newDoc")
            .getProperty("other"),
        Integer.valueOf(1));
    assertEquals("other", doc.<Map<String, String>>getProperty("map").get("value"));
    assertEquals("something", doc.<Map<String, String>>getProperty("map").get("two"));
    assertEquals("something", doc.<Map<String, String>>getProperty("map1").get("two"));
    assertNull(doc.<Map<String, String>>getProperty("map1").get("one"));
    assertEquals(
        "value",
        doc.<Map<String, Map<String, String>>>getProperty("mapNested")
            .get("nest")
            .get("other"));
    session.rollback();
  }

  @Test
  public void testSimpleSerialization() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);
    document.field("heigth", 12.5f);
    document.field("bitHeigth", 12.5d);
    document.field("class", (byte) 'C');
    document.field("nullField", null);
    document.field("alive", true);
    document.field("dateTime", new Date());
    document.field(
        "bigNumber", new BigDecimal("43989872423376487952454365232141525434.32146432321442534"));
    var bag = new RidBag(session);
    bag.add(new RecordId(1, 1));
    bag.add(new RecordId(2, 2));
    // document.field("ridBag", bag);
    var c = Calendar.getInstance();
    document.field("date", c.getTime(), PropertyType.DATE);
    var c1 = Calendar.getInstance();
    c1.set(Calendar.MILLISECOND, 0);
    c1.set(Calendar.SECOND, 0);
    c1.set(Calendar.MINUTE, 0);
    c1.set(Calendar.HOUR_OF_DAY, 0);
    document.field("date1", c1.getTime(), PropertyType.DATE);

    var byteValue = new byte[10];
    Arrays.fill(byteValue, (byte) 10);
    document.field("bytes", byteValue);

    document.field("utf8String", "A" + "ê" + "ñ" + "ü" + "C");
    document.field("recordId", new RecordId(10, 10));

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    c.set(Calendar.MILLISECOND, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.HOUR_OF_DAY, 0);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("name"), document.field("name"));
    assertEquals(extr.<Object>field("age"), document.field("age"));
    assertEquals(extr.<Object>field("youngAge"), document.field("youngAge"));
    assertEquals(extr.<Object>field("oldAge"), document.field("oldAge"));
    assertEquals(extr.<Object>field("heigth"), document.field("heigth"));
    assertEquals(extr.<Object>field("bitHeigth"), document.field("bitHeigth"));
    assertEquals(extr.<Object>field("class"), document.field("class"));
    // TODO fix char management issue:#2427
    // assertEquals(document.field("character"), extr.field("character"));
    assertEquals(extr.<Object>field("alive"), document.field("alive"));
    assertEquals(extr.<Object>field("dateTime"), document.field("dateTime"));
    assertEquals(extr.field("date"), c.getTime());
    assertEquals(extr.field("date1"), c1.getTime());
    //    assertEquals(extr.<String>field("bytes"), document.field("bytes"));
    Assertions.assertThat(extr.<Object>field("bytes")).isEqualTo(document.field("bytes"));
    assertEquals(extr.<String>field("utf8String"), document.field("utf8String"));
    assertEquals(extr.<Object>field("recordId"), document.field("recordId"));
    assertEquals(extr.<Object>field("bigNumber"), document.field("bigNumber"));
    assertNull(extr.field("nullField"));

    session.rollback();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralList() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    List<String> strings = session.newEmbeddedList();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    document.field("listStrings", strings);

    List<Short> shorts = session.newEmbeddedList();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.field("shorts", shorts);

    List<Long> longs = session.newEmbeddedList();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.field("longs", longs);

    List<Integer> ints = session.newEmbeddedList();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.field("integers", ints);

    List<Float> floats = session.newEmbeddedList();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.field("floats", floats);

    List<Double> doubles = session.newEmbeddedList();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.field("doubles", doubles);

    List<Date> dates = session.newEmbeddedList();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    document.field("dates", dates);

    List<Byte> bytes = session.newEmbeddedList();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.field("bytes", bytes);

    List<Boolean> booleans = session.newEmbeddedList();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.field("booleans", booleans);

    List listMixed = session.newEmbeddedList();
    listMixed.add(true);
    listMixed.add(1);
    listMixed.add((long) 5);
    listMixed.add((short) 2);
    listMixed.add(4.0f);
    listMixed.add(7.0D);
    listMixed.add("hello");
    listMixed.add(new Date());
    listMixed.add((byte) 10);
    document.field("listMixed", listMixed);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("listStrings"), document.field("listStrings"));
    assertEquals(extr.<Object>field("integers"), document.field("integers"));
    assertEquals(extr.<Object>field("doubles"), document.field("doubles"));
    assertEquals(extr.<Object>field("dates"), document.field("dates"));
    assertEquals(extr.<Object>field("bytes"), document.field("bytes"));
    assertEquals(extr.<Object>field("booleans"), document.field("booleans"));
    assertEquals(extr.<Object>field("listMixed"), document.field("listMixed"));
    session.rollback();
  }

  @SuppressWarnings({"rawtypes", "unchecked", "OverwrittenKey"})
  @Test
  public void testSimpleLiteralSet() throws InterruptedException {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    Set<String> strings = session.newEmbeddedSet();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    document.field("listStrings", strings);

    Set<Short> shorts = session.newEmbeddedSet();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.field("shorts", shorts);

    Set<Long> longs = session.newEmbeddedSet();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.field("longs", longs);

    Set<Integer> ints = session.newEmbeddedSet();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.field("integers", ints);

    Set<Float> floats = session.newEmbeddedSet();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.field("floats", floats);

    Set<Double> doubles = session.newEmbeddedSet();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.field("doubles", doubles);

    Set<Date> dates = session.newEmbeddedSet();
    dates.add(new Date());
    Thread.sleep(1);
    dates.add(new Date());
    Thread.sleep(1);
    dates.add(new Date());
    document.field("dates", dates);

    Set<Byte> bytes = session.newEmbeddedSet();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.field("bytes", bytes);

    Set<Boolean> booleans = session.newEmbeddedSet();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.field("booleans", booleans);

    Set listMixed = session.newEmbeddedSet();
    listMixed.add(true);
    listMixed.add(1);
    listMixed.add((long) 5);
    listMixed.add((short) 2);
    listMixed.add(4.0f);
    listMixed.add(7.0D);
    listMixed.add("hello");
    listMixed.add(new Date());
    listMixed.add((byte) 10);
    document.field("listMixed", listMixed);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("listStrings"), document.field("listStrings"));
    assertEquals(extr.<Object>field("integers"), document.field("integers"));
    assertEquals(extr.<Object>field("doubles"), document.field("doubles"));
    assertEquals(extr.<Object>field("dates"), document.field("dates"));
    assertEquals(extr.<Object>field("bytes"), document.field("bytes"));
    assertEquals(extr.<Object>field("booleans"), document.field("booleans"));
    assertEquals(extr.<Object>field("listMixed"), document.field("listMixed"));

    session.rollback();
  }

  @Test
  public void testLinkCollections() {
    session.begin();

    var document = (EntityImpl) session.newEntity();
    var linkSet = session.newLinkSet();
    linkSet.add(new RecordId(10, 20));
    linkSet.add(new RecordId(10, 21));
    linkSet.add(new RecordId(10, 22));
    linkSet.add(new RecordId(11, 22));
    document.field("linkSet", linkSet, PropertyType.LINKSET);

    var linkList = session.newLinkList();
    linkList.add(new RecordId(10, 20));
    linkList.add(new RecordId(10, 21));
    linkList.add(new RecordId(10, 22));
    linkList.add(new RecordId(11, 22));
    document.field("linkList", linkList, PropertyType.LINKLIST);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(
        ((Set<?>) extr.field("linkSet")).size(), ((Set<?>) document.field("linkSet")).size());
    assertTrue(extr.getLinkSet("linkSet").containsAll(document.getLinkSet("linkSet")));
    assertEquals(extr.<Object>field("linkList"), document.field("linkList"));

    session.rollback();
  }

  @Test
  public void testSimpleEmbeddedDoc() {
    session.begin();

    var document = (EntityImpl) session.newEntity();
    var embedded = (EntityImpl) session.newEntity();
    embedded.field("name", "test");
    embedded.field("surname", "something");
    document.field("embed", embedded, PropertyType.EMBEDDED);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(document.fields(), extr.fields());
    EntityImpl emb = extr.field("embed");
    assertNotNull(emb);
    assertEquals(emb.<Object>field("name"), embedded.field("name"));
    assertEquals(emb.<Object>field("surname"), embedded.field("surname"));

    session.rollback();
  }

  @Test
  public void testSimpleMapStringLiteral() {
    session.begin();

    var document = (EntityImpl) session.newEntity();

    Map<String, String> mapString = session.newEmbeddedMap();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    document.field("mapString", mapString);

    Map<String, Integer> mapInt = session.newEmbeddedMap();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    document.field("mapInt", mapInt);

    Map<String, Long> mapLong = session.newEmbeddedMap();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    document.field("mapLong", mapLong);

    Map<String, Short> shortMap = session.newEmbeddedMap();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    document.field("shortMap", shortMap);

    Map<String, Date> dateMap = session.newEmbeddedMap();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    document.field("dateMap", dateMap);

    Map<String, Float> floatMap = session.newEmbeddedMap();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    document.field("floatMap", floatMap);

    Map<String, Double> doubleMap = session.newEmbeddedMap();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    document.field("doubleMap", doubleMap);

    Map<String, Byte> bytesMap = session.newEmbeddedMap();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    document.field("bytesMap", bytesMap);

    Map<String, String> mapWithNulls = session.newEmbeddedMap();
    mapWithNulls.put("key", "dddd");
    mapWithNulls.put("key1", null);
    document.field("bytesMap", mapWithNulls);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("mapString"), document.field("mapString"));
    assertEquals(extr.<Object>field("mapLong"), document.field("mapLong"));
    assertEquals(extr.<Object>field("shortMap"), document.field("shortMap"));
    assertEquals(extr.<Object>field("dateMap"), document.field("dateMap"));
    assertEquals(extr.<Object>field("doubleMap"), document.field("doubleMap"));
    assertEquals(extr.<Object>field("bytesMap"), document.field("bytesMap"));

    session.rollback();
  }

  @Test
  public void testlistOfList() {
    session.begin();

    var document = (EntityImpl) session.newEntity();
    List<List<String>> list = session.newEmbeddedList();
    List<String> ls = session.newEmbeddedList();
    ls.add("test1");
    ls.add("test2");
    list.add(ls);

    document.setEmbeddedList("complexList", list);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("complexList"), document.field("complexList"));

    session.rollback();
  }

  @Test
  public void testEmbeddedListOfEmbeddedMap() {
    session.begin();

    var document = (EntityImpl) session.newEntity();
    List<Map<String, String>> coll = session.newEmbeddedList();
    Map<String, String> map = session.newEmbeddedMap();
    map.put("first", "something");
    map.put("second", "somethingElse");
    Map<String, String> map2 = session.newEmbeddedMap();
    map2.put("first", "something");
    map2.put("second", "somethingElse");
    coll.add(map);
    coll.add(map2);
    document.field("list", coll);
    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("list"), document.field("list"));

    session.rollback();
  }

  @Test
  public void testMapOfEmbeddedDocument() {
    session.begin();

    var document = (EntityImpl) session.newEntity();

    var embeddedInMap = (EntityImpl) session.newEmbededEntity();
    embeddedInMap.field("name", "test");
    embeddedInMap.field("surname", "something");
    Map<String, EntityImpl> map = document.newEmbeddedMap("map");
    map.put("embedded", embeddedInMap);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    session.rollback();

    session.begin();
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    Map<String, EntityImpl> mapS = extr.field("map");
    assertEquals(1, mapS.size());
    var emb = mapS.get("embedded");
    assertNotNull(emb);
    assertEquals(emb.<Object>field("name"), embeddedInMap.field("name"));
    assertEquals(emb.<Object>field("surname"), embeddedInMap.field("surname"));

    session.rollback();
  }

  @Test
  public void testMapOfLink() {
    session.begin();

    // needs a database because of the lazy loading
    var document = (EntityImpl) session.newEntity();

    var map = document.newLinkMap("map");
    map.put("link", new RecordId(0, 0));

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("map"), document.field("map"));

    session.rollback();
  }

  @Test
  public void testDocumentSimple() {
    session.createClassIfNotExist("TestClass");

    session.begin();
    var document = (EntityImpl) session.newEntity("TestClass");
    document.field("test", "test");
    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("test"), document.field("test"));

    session.rollback();
  }

  @Test
  public void testCollectionOfEmbeddedEntities() {
    session.begin();
    var entity = session.newEntity();

    var embeddedInList = (EntityImpl) session.newEmbededEntity();
    embeddedInList.field("name", "test");
    embeddedInList.field("surname", "something");

    var embeddedInList2 = (EntityImpl) session.newEmbededEntity();
    embeddedInList2.field("name", "test1");
    embeddedInList2.field("surname", "something2");

    List<EntityImpl> embeddedList = new ArrayList<>();
    embeddedList.add(embeddedInList);
    embeddedList.add(embeddedInList2);
    embeddedList.add(null);
    embeddedList.add((EntityImpl) session.newEmbededEntity());
    entity.newEmbeddedList("embeddedList", embeddedList);

    var embeddedInSet = (EntityImpl) session.newEmbededEntity();
    embeddedInSet.field("name", "test2");
    embeddedInSet.field("surname", "something3");

    var embeddedInSet2 = (EntityImpl) session.newEmbededEntity();
    embeddedInSet2.field("name", "test5");
    embeddedInSet2.field("surname", "something6");

    Set<EntityImpl> embeddedSet = new HashSet<>();
    embeddedSet.add(embeddedInSet);
    embeddedSet.add(embeddedInSet2);
    embeddedSet.add((EntityImpl) session.newEmbededEntity());
    entity.newEmbeddedSet("embeddedSet").addAll(embeddedSet);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, (EntityImpl) entity);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    List<EntityImpl> ser = extr.field("embeddedList");
    assertEquals(4, ser.size());
    assertNotNull(ser.get(0));
    assertNotNull(ser.get(1));
    assertNull(ser.get(2));
    assertNotNull(ser.get(3));
    var inList = ser.get(0);
    assertNotNull(inList);
    assertEquals(inList.<Object>field("name"), embeddedInList.field("name"));
    assertEquals(inList.<Object>field("surname"), embeddedInList.field("surname"));

    Set<EntityImpl> setEmb = extr.field("embeddedSet");
    assertEquals(3, setEmb.size());
    var ok = false;
    for (var inSet : setEmb) {
      assertNotNull(inSet);
      if (embeddedInSet.field("name").equals(inSet.field("name"))
          && embeddedInSet.field("surname").equals(inSet.field("surname"))) {
        ok = true;
      }
    }
    assertTrue("not found record in the set after serilize", ok);
    session.rollback();
  }


  @Test
  public void testFieldNames() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.fields("a", 1, "b", 2, "c", 3);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    final var fields = extr.fieldNames();

    assertNotNull(fields);
    assertEquals(3, fields.length);
    assertEquals("a", fields[0]);
    assertEquals("b", fields[1]);
    assertEquals("c", fields[2]);
    session.rollback();
  }

  @Test
  public void testWithRemove() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);
    document.removeField("oldAge");

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(document.field("name"), extr.<Object>field("name"));
    assertEquals(document.<Object>field("age"), extr.field("age"));
    assertEquals(document.<Object>field("youngAge"), extr.field("youngAge"));
    assertNull(extr.field("oldAge"));
    session.rollback();
  }

  @Test
  public void testListOfMapsWithNull() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();

    var lista = entity.newEmbeddedList("list");
    var mappa = session.newEmbeddedMap();
    mappa.put("prop1", "val1");
    mappa.put("prop2", null);
    lista.add(mappa);

    mappa = session.newEmbeddedMap();
    mappa.put("prop", "val");
    lista.add(mappa);

    var serializerDelta = DocumentSerializerDelta.instance();
    var res = serializerDelta.serialize(session, entity);
    var extr = (EntityImpl) session.newEntity();
    serializerDelta.deserialize(session, res, extr);

    assertEquals(extr.fields(), entity.fields());
    assertEquals(extr.<Object>field("list"), entity.field("list"));
    session.rollback();
  }

  private static class WrongData {

  }
}
