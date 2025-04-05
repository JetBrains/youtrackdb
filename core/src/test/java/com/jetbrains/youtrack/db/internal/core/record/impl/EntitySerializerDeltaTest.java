package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.DocumentSerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.DocumentSerializerDelta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.Test;

public class EntitySerializerDeltaTest extends DbTestBase {

  @Test
  public void testGetFromOriginalSimpleDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    String constantFieldName = "constantField";
    String originalValue = "orValue";
    String testValue = "testValue";
    String removeField = "removeField";

    doc.setProperty(fieldName, originalValue);
    doc.setProperty(constantFieldName, "someValue");
    doc.setProperty(removeField, "removeVal");

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    doc.setProperty(fieldName, testValue);
    doc.removeProperty(removeField);
    // test serialization/deserialization
    DocumentSerializerDelta delta = DocumentSerializerDelta.instance();
    byte[] bytes = delta.serializeDelta(db, doc);
    delta.deserializeDelta(db, bytes, originalDoc);
    assertEquals(testValue, originalDoc.field(fieldName));
    assertNull(originalDoc.field(removeField));
    db.rollback();
  }

  @Test
  public void testGetFromNestedDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    EntityImpl nestedDoc = new EntityImplEmbedded(claz.getName());
    String fieldName = "testField";
    String constantFieldName = "constantField";
    String originalValue = "orValue";
    String testValue = "testValue";
    String nestedDocField = "nestedField";

    nestedDoc.setProperty(fieldName, originalValue);
    nestedDoc.setProperty(constantFieldName, "someValue1");

    doc.setProperty(constantFieldName, "someValue2");
    doc.setProperty(nestedDocField, nestedDoc, PropertyType.EMBEDDED);

    EntityImpl originalDoc = new EntityImpl();
    originalDoc.setProperty(constantFieldName, "someValue2");
    originalDoc.setProperty(nestedDocField, nestedDoc, PropertyType.EMBEDDED);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    nestedDoc = doc.field(nestedDocField);
    nestedDoc.setProperty(fieldName, testValue);

    doc.setProperty(nestedDocField, nestedDoc, PropertyType.EMBEDDED);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    // test serialization/deserialization
    originalDoc = db.bindToSession(originalDoc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);
    nestedDoc = originalDoc.field(nestedDocField);
    assertEquals(nestedDoc.field(fieldName), testValue);
    db.rollback();
  }

  @Test
  public void testListDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String fieldName = "testField";
    List<String> originalValue = new ArrayList<>();
    originalValue.add("one");
    originalValue.add("two");
    originalValue.add("toRemove");

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    List<String> newArray = doc.field(fieldName);
    newArray.set(1, "three");
    newArray.remove("toRemove");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<?> checkList = originalDoc.getProperty(fieldName);
    assertEquals("three", checkList.get(1));
    assertFalse(checkList.contains("toRemove"));
    db.rollback();
  }

  @Test
  public void testSetDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    EntityImpl doc = new EntityImpl(claz);

    String fieldName = "testField";
    Set<String> originalValue = new HashSet<>();
    originalValue.add("one");
    originalValue.add("toRemove");

    db.begin();
    doc.setProperty(fieldName, originalValue);
    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    Set<String> newArray = doc.field(fieldName);
    newArray.add("three");
    newArray.remove("toRemove");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    Set<String> checkSet = originalDoc.field(fieldName);
    assertTrue(checkSet.contains("three"));
    assertFalse(checkSet.contains("toRemove"));
    db.rollback();
  }

  @Test
  public void testSetOfSetsDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    Set<Set<String>> originalValue = new HashSet<>();
    for (int i = 0; i < 2; i++) {
      Set<String> containedSet = new HashSet<>();
      containedSet.add("one");
      containedSet.add("two");
      originalValue.add(containedSet);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    Set<String> newSet = ((Set<Set<String>>) doc.getProperty(fieldName)).iterator().next();
    newSet.add("three");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    Set<Set<String>> checkSet = originalDoc.field(fieldName);
    assertTrue(checkSet.iterator().next().contains("three"));
    db.rollback();
  }

  @Test
  public void testListOfListsDelta() {

    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    List<List<String>> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      List<String> containedList = new ArrayList<>();
      containedList.add("one");
      containedList.add("two");

      originalValue.add(containedList);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    List<String> newList = ((List<List<String>>) doc.field(fieldName)).get(0);
    newList.set(1, "three");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<List<String>> checkList = originalDoc.field(fieldName);
    assertEquals("three", checkList.get(0).get(1));
    db.rollback();
  }

  @Test
  public void testListOfDocsDelta() {
    String fieldName = "testField";

    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String constantField = "constField";
    String constValue = "ConstValue";
    String variableField = "varField";
    List<EntityImpl> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      EntityImpl containedDoc = new EntityImplEmbedded();
      containedDoc.setProperty(constantField, constValue);
      containedDoc.setProperty(variableField, "one" + i);
      originalValue.add(containedDoc);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    EntityImpl testDoc = ((List<EntityImpl>) doc.getProperty(fieldName)).get(1);
    testDoc.setProperty(variableField, "two");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<EntityImpl> checkList = originalDoc.field(fieldName);
    EntityImpl checkDoc = checkList.get(1);
    assertEquals(checkDoc.field(constantField), constValue);
    assertEquals(checkDoc.field(variableField), "two");
    db.rollback();
  }

  @Test
  public void testListOfListsOfDocumentDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    String constantField = "constField";
    String constValue = "ConstValue";
    String variableField = "varField";

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    List<List<EntityImpl>> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      List<EntityImpl> containedList = new ArrayList<>();
      EntityImpl d1 = new EntityImpl();
      d1.setProperty(constantField, constValue);
      d1.setProperty(variableField, "one");
      EntityImpl d2 = new EntityImpl();
      d2.setProperty(constantField, constValue);
      containedList.add(d1);
      containedList.add(d2);
      originalValue.add(containedList);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    originalValue = doc.getProperty(fieldName);
    EntityImpl d1 = originalValue.get(0).get(0);
    d1.setProperty(variableField, "two");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<List<EntityImpl>> checkList = originalDoc.field(fieldName);
    assertEquals("two", checkList.get(0).get(0).field(variableField));
    db.rollback();
  }

  @Test
  public void testListOfListsOfListDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    List<List<List<String>>> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      List<List<String>> containedList = new ArrayList<>();
      for (int j = 0; j < 2; j++) {
        List<String> innerList = new ArrayList<>();
        innerList.add("el1" + j + i);
        innerList.add("el2" + j + i);
        containedList.add(innerList);
      }
      originalValue.add(containedList);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    List<String> innerList = ((List<List<List<String>>>) doc.field(fieldName)).get(0).get(0);
    innerList.set(0, "changed");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<List<List<String>>> checkList = originalDoc.field(fieldName);
    assertEquals("changed", checkList.get(0).get(0).get(0));
    db.rollback();
  }

  @Test
  public void testListOfDocsWithList() {
    String fieldName = "testField";

    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String constantField = "constField";
    String constValue = "ConstValue";
    String variableField = "varField";

    List<EntityImpl> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      EntityImpl containedDoc = new EntityImplEmbedded();
      containedDoc.setProperty(constantField, constValue);
      List<String> listField = new ArrayList<>();
      for (int j = 0; j < 2; j++) {
        listField.add("Some" + j);
      }
      containedDoc.setProperty(variableField, listField);
      originalValue.add(containedDoc);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    EntityImpl testDoc = ((List<EntityImpl>) doc.field(fieldName)).get(1);
    List<String> currentList = testDoc.field(variableField);
    currentList.set(0, "changed");
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<EntityImpl> checkList = originalDoc.field(fieldName);
    EntityImpl checkDoc = checkList.get(1);
    List<String> checkInnerList = checkDoc.field(variableField);
    assertEquals("changed", checkInnerList.get(0));
    db.rollback();
  }

  @Test
  public void testListAddDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String fieldName = "testField";
    List<String> originalValue = new ArrayList<>();
    originalValue.add("one");
    originalValue.add("two");
    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    List<String> newArray = doc.field(fieldName);
    newArray.add("three");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<String> checkList = originalDoc.field(fieldName);
    assertEquals(3, checkList.size());
    db.rollback();
  }

  @Test
  public void testListOfListAddDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String fieldName = "testField";
    List<List<String>> originalList = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      List<String> nestedList = new ArrayList<>();
      nestedList.add("one");
      nestedList.add("two");
      originalList.add(nestedList);
    }

    doc.setProperty(fieldName, originalList);

    doc = db.save(doc);
    db.commit();

    // Deep Copy is not working in this case, use toStream/fromStream as workaround.
    // EntityImpl originalDoc = doc.copy();
    EntityImpl originalDoc = new EntityImpl();
    RecordInternal.unsetDirty(originalDoc);

    db.begin();
    doc = db.bindToSession(doc);
    originalDoc.fromStream(doc.toStream());

    @SuppressWarnings("unchecked")
    List<String> newArray = ((List<List<String>>) doc.field(fieldName)).get(0);
    newArray.add("three");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<List<String>> rootList = originalDoc.field(fieldName);
    List<String> checkList = rootList.get(0);
    assertEquals(3, checkList.size());
    db.rollback();
  }

  @Test
  public void testListRemoveDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String fieldName = "testField";
    List<String> originalValue = new ArrayList<>();
    originalValue.add("one");
    originalValue.add("two");
    originalValue.add("three");

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    List<String> newArray = doc.field(fieldName);
    newArray.remove(0);
    newArray.remove(0);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<String> checkList = originalDoc.field(fieldName);
    assertEquals("three", checkList.get(0));
    db.rollback();
  }

  @Test
  public void testAddDocFieldDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    String constantFieldName = "constantField";
    String testValue = "testValue";

    doc.setProperty(constantFieldName + "1", "someValue1");
    doc.setProperty(constantFieldName, "someValue");

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    doc.setProperty(fieldName, testValue);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);
    assertEquals(testValue, originalDoc.field(fieldName));
    db.rollback();
  }

  @Test
  public void testRemoveCreateDocFieldDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    String constantFieldName = "constantField";
    String testValue = "testValue";

    db.begin();
    doc.setProperty(fieldName, testValue);
    doc.setProperty(constantFieldName, "someValue");

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    doc.removeProperty(fieldName);
    doc.setProperty("other", "new");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    assertFalse(originalDoc.hasProperty(fieldName));
    assertEquals(originalDoc.getProperty("other"), "new");
    db.rollback();
  }

  @Test
  public void testRemoveNestedDocFieldDelta() {
    String nestedFieldName = "nested";

    SchemaClass claz = db.createClassIfNotExist("TestClass");
    claz.createProperty(db, nestedFieldName, PropertyType.EMBEDDED);

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    String constantFieldName = "constantField";
    String testValue = "testValue";

    doc.setProperty(fieldName, testValue);
    doc.setProperty(constantFieldName, "someValue");

    EntityImpl rootDoc = new EntityImpl(claz);
    rootDoc.setProperty(nestedFieldName, doc);

    rootDoc = db.save(rootDoc);
    db.commit();

    db.begin();
    rootDoc = db.bindToSession(rootDoc);
    EntityImpl originalDoc = rootDoc.copy();

    doc = rootDoc.field(nestedFieldName);
    doc.removeProperty(fieldName);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, rootDoc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    EntityImpl nested = originalDoc.field(nestedFieldName);
    assertFalse(nested.hasProperty(fieldName));
    db.rollback();
  }

  @Test
  public void testRemoveFieldListOfDocsDelta() {
    String fieldName = "testField";

    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);

    String constantField = "constField";
    String constValue = "ConstValue";
    String variableField = "varField";
    List<EntityImpl> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      EntityImpl containedDoc = new EntityImpl();
      containedDoc.setProperty(constantField, constValue);
      containedDoc.setProperty(variableField, "one" + i);
      originalValue.add(containedDoc);
    }

    doc.setProperty(fieldName, originalValue);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    EntityImpl testDoc = ((List<Identifiable>) doc.field(fieldName)).get(1).getRecord();
    testDoc.removeProperty(variableField);
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    List<Identifiable> checkList = originalDoc.field(fieldName);
    EntityImpl checkDoc = checkList.get(1).getRecord();
    assertEquals(checkDoc.field(constantField), constValue);
    assertFalse(checkDoc.hasProperty(variableField));
    db.rollback();
  }

  @Test
  public void testUpdateEmbeddedMapDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    Map<String, String> mapValue = new HashMap<>();
    mapValue.put("first", "one");
    mapValue.put("second", "two");

    doc.setProperty(fieldName, mapValue, PropertyType.EMBEDDEDMAP);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    Map<String, String> containedMap = doc.field(fieldName);
    containedMap.put("first", "changed");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    containedMap = originalDoc.field(fieldName);
    assertEquals("changed", containedMap.get("first"));
    db.rollback();
  }

  @Test
  public void testUpdateListOfEmbeddedMapDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    List<Map<String, String>> originalValue = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Map<String, String> mapValue = new HashMap<>();
      mapValue.put("first", "one");
      mapValue.put("second", "two");
      originalValue.add(mapValue);
    }

    doc.setProperty(fieldName, originalValue, PropertyType.EMBEDDEDLIST);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    @SuppressWarnings("unchecked")
    Map<String, String> containedMap = ((List<Map<String, String>>) doc.field(fieldName)).get(0);
    containedMap.put("first", "changed");
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    //noinspection unchecked
    containedMap = ((List<Map<String, String>>) originalDoc.field(fieldName)).get(0);
    assertEquals("changed", containedMap.get("first"));
    //noinspection unchecked
    containedMap = ((List<Map<String, String>>) originalDoc.field(fieldName)).get(1);
    assertEquals("one", containedMap.get("first"));
    db.rollback();
  }

  @Test
  public void testUpdateDocInMapDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    Map<String, EntityImpl> mapValue = new HashMap<>();
    EntityImpl d1 = new EntityImpl();
    d1.setProperty("f1", "v1");
    mapValue.put("first", d1);
    EntityImpl d2 = new EntityImpl();
    d2.setProperty("f2", "v2");
    mapValue.put("second", d2);
    doc.setProperty(fieldName, mapValue, PropertyType.EMBEDDEDMAP);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    Map<String, EntityImpl> containedMap = doc.field(fieldName);
    EntityImpl changeDoc = containedMap.get("first");
    changeDoc.setProperty("f1", "changed");

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);
    containedMap = originalDoc.field(fieldName);
    EntityImpl containedDoc = containedMap.get("first");
    assertEquals("changed", containedDoc.field("f1"));
    db.rollback();
  }

  @Test
  public void testListOfMapsUpdateDelta() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";
    List<Map> originalList = new ArrayList<>();
    List<Map> copyList = new ArrayList<>();

    Map<String, String> mapValue1 = new HashMap<>();
    mapValue1.put("first", "one");
    mapValue1.put("second", "two");
    originalList.add(mapValue1);
    Map<String, String> mapValue1Copy = new HashMap<>(mapValue1);
    copyList.add(mapValue1Copy);

    Map<String, String> mapValue2 = new HashMap<>();
    mapValue2.put("third", "three");
    mapValue2.put("forth", "four");
    originalList.add(mapValue2);
    Map<String, String> mapValue2Copy = new HashMap<>(mapValue2);
    copyList.add(mapValue2Copy);

    doc.field(fieldName, originalList);

    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    Map<String, String> containedMap = (Map<String, String>) ((List) doc.field(fieldName)).get(0);
    containedMap.put("first", "changed");
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    containedMap = (Map<String, String>) ((List) originalDoc.field(fieldName)).get(0);
    assertEquals("changed", containedMap.get("first"));
    db.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaAddWithCopy() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";

    EntityImpl first = new EntityImpl(claz);
    first = db.save(first);
    EntityImpl second = new EntityImpl(claz);
    second = db.save(second);

    RidBag ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    doc = db.save(doc);

    EntityImpl originalDoc = doc;
    doc.save();

    EntityImpl third = new EntityImpl(claz);
    third = db.save(third);
    db.commit();

    db.begin();
    first = db.bindToSession(first);
    second = db.bindToSession(second);
    third = db.bindToSession(third);

    ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    ridBag.add(third);

    doc = db.bindToSession(doc);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    originalDoc = db.bindToSession(originalDoc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    RidBag mergedRidbag = originalDoc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    db.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaRemoveWithCopy() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";

    db.begin();
    EntityImpl first = new EntityImpl(claz);
    first = db.save(first);
    EntityImpl second = new EntityImpl(claz);
    second = db.save(second);
    EntityImpl third = new EntityImpl(claz);
    third = db.save(third);
    db.commit();

    db.begin();
    first = db.bindToSession(first);
    second = db.bindToSession(second);
    third = db.bindToSession(third);

    RidBag ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    ridBag.add(third);

    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    doc = db.save(doc);
    db.commit();

    db.begin();
    first = db.bindToSession(first);
    second = db.bindToSession(second);
    doc = db.bindToSession(doc);

    EntityImpl originalDoc = doc.copy();

    ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    RidBag mergedRidbag = originalDoc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    db.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaAdd() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";

    EntityImpl first = new EntityImpl(claz);
    first = db.save(first);
    EntityImpl second = new EntityImpl(claz);
    second = db.save(second);

    RidBag ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    doc = db.save(doc);
    db.commit();

    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    db.begin();
    EntityImpl third = new EntityImpl(claz);
    third = db.save(third);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    third = db.bindToSession(third);

    ridBag = doc.getProperty(fieldName);
    ridBag.add(third);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    RidBag mergedRidbag = originalDoc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    db.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaRemove() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";

    EntityImpl first = new EntityImpl(claz);
    first = db.save(first);
    EntityImpl second = new EntityImpl(claz);
    second = db.save(second);
    EntityImpl third = new EntityImpl(claz);
    third = db.save(third);

    RidBag ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    ridBag.add(third);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();
    ridBag = doc.getProperty(fieldName);
    ridBag.remove(third);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    RidBag mergedRidbag = originalDoc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    db.rollback();
  }

  @Test
  public void testRidbagsUpdateDeltaChangeWithCopy() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    String fieldName = "testField";

    EntityImpl first = new EntityImpl(claz);
    first = db.save(first);
    EntityImpl second = new EntityImpl(claz);
    second = db.save(second);
    EntityImpl third = new EntityImpl(claz);
    third = db.save(third);

    RidBag ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(second);
    ridBag.add(third);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);
    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();

    ridBag = new RidBag(db);
    ridBag.add(first);
    ridBag.add(third);
    doc.field(fieldName, ridBag, PropertyType.LINKBAG);

    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);

    RidBag mergedRidbag = originalDoc.field(fieldName);
    assertEquals(ridBag, mergedRidbag);
    db.rollback();
  }

  @Test
  public void testDeltaNullValues() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    doc.setProperty("one", "value");
    doc.setProperty("list", List.of("test"));
    doc.setProperty("set", new HashSet<>(List.of("test")));
    Map<String, String> map = new HashMap<>();
    map.put("two", "value");
    doc.setProperty("map", map);
    Identifiable link = db.save(new EntityImpl("testClass"));
    doc.setProperty("linkList", Collections.singletonList(link));
    doc.setProperty("linkSet", new HashSet<>(Collections.singletonList(link)));
    Map<String, Identifiable> linkMap = new HashMap<>();
    linkMap.put("two", link);
    doc.setProperty("linkMap", linkMap);
    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();
    doc.setProperty("one", null);
    ((List<String>) doc.getProperty("list")).add(null);
    ((Set<String>) doc.getProperty("set")).add(null);
    ((Map<String, String>) doc.getProperty("map")).put("nullValue", null);
    ((List<Identifiable>) doc.getProperty("linkList")).add(null);
    ((Set<Identifiable>) doc.getProperty("linkSet")).add(null);
    ((Map<String, Identifiable>) doc.getProperty("linkMap")).put("nullValue", null);
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);
    assertTrue(((List) originalDoc.getProperty("list")).contains(null));
    assertTrue(((Set) originalDoc.getProperty("set")).contains(null));
    assertTrue(((Map) originalDoc.getProperty("map")).containsKey("nullValue"));
    assertTrue(((List) originalDoc.getProperty("linkList")).contains(null));
    assertTrue(((Set) originalDoc.getProperty("linkSet")).contains(null));
    assertTrue(((Map) originalDoc.getProperty("linkMap")).containsKey("nullValue"));
    db.rollback();
  }

  @Test
  public void testDeltaLinkAllCases() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    Identifiable link = db.save(new EntityImpl("testClass"));
    var link1 = db.save(new EntityImpl("testClass"));
    doc.setProperty("linkList", Arrays.asList(link, link1, link1));
    doc.setProperty("linkSet", new HashSet<>(Arrays.asList(link, link1)));
    Map<String, Identifiable> linkMap = new HashMap<>();
    linkMap.put("one", link);
    linkMap.put("two", link1);
    linkMap.put("three", link1);
    doc.setProperty("linkMap", linkMap);
    doc = db.save(doc);

    EntityImpl link2 = db.save(new EntityImpl("testClass"));
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();
    link2 = db.bindToSession(link2);
    link1 = db.bindToSession(link1);

    ((List<Identifiable>) doc.getProperty("linkList")).set(1, link2);
    ((List<Identifiable>) doc.getProperty("linkList")).remove(link1);
    ((List<Identifiable>) doc.getProperty("linkList")).add(link2);
    ((Set<Identifiable>) doc.getProperty("linkSet")).add(link2);
    ((Set<Identifiable>) doc.getProperty("linkSet")).remove(link1);
    ((Map<String, Identifiable>) doc.getProperty("linkMap")).put("new", link2);
    ((Map<String, Identifiable>) doc.getProperty("linkMap")).put("three", link2);
    ((Map<String, Identifiable>) doc.getProperty("linkMap")).remove("two");
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);
    assertFalse(((List) originalDoc.getProperty("linkList")).contains(link1));
    assertTrue(((List) originalDoc.getProperty("linkList")).contains(link2));
    assertEquals(((List) originalDoc.getProperty("linkList")).get(1), link2);
    assertTrue(((Set) originalDoc.getProperty("linkSet")).contains(link2));
    assertFalse(((Set) originalDoc.getProperty("linkSet")).contains(link1));
    assertEquals(((Map) originalDoc.getProperty("linkMap")).get("new"), link2);
    assertEquals(((Map) originalDoc.getProperty("linkMap")).get("three"), link2);
    assertTrue(((Map) originalDoc.getProperty("linkMap")).containsKey("one"));
    assertFalse(((Map) originalDoc.getProperty("linkMap")).containsKey("two"));
    db.rollback();
  }

  @Test
  public void testDeltaAllCasesMap() {
    SchemaClass claz = db.createClassIfNotExist("TestClass");

    db.begin();
    EntityImpl doc = new EntityImpl(claz);
    Map<String, String> map = new HashMap<>();
    map.put("two", "value");
    doc.setProperty("map", map);
    Map<String, String> map1 = new HashMap<>();
    map1.put("two", "value");
    map1.put("one", "other");
    Map<String, Map<String, String>> mapNested = new HashMap<>();
    Map<String, String> nested = new HashMap<>();
    nested.put("one", "value");
    mapNested.put("nest", nested);
    doc.setProperty("mapNested", mapNested);
    doc.setProperty("map1", map1);
    Map<String, Entity> mapEmbedded = new HashMap<>();
    Entity embedded = db.newEntity();
    embedded.setProperty("other", 1);
    mapEmbedded.put("first", embedded);
    doc.setProperty("mapEmbedded", mapEmbedded, PropertyType.EMBEDDEDMAP);
    doc = db.save(doc);
    db.commit();

    db.begin();
    doc = db.bindToSession(doc);
    EntityImpl originalDoc = doc.copy();
    Entity embedded1 = db.newEntity();
    embedded1.setProperty("other", 1);
    ((Map<String, Entity>) doc.getProperty("mapEmbedded")).put("newDoc", embedded1);
    ((Map<String, String>) doc.getProperty("map")).put("value", "other");
    ((Map<String, String>) doc.getProperty("map")).put("two", "something");
    ((Map<String, String>) doc.getProperty("map1")).remove("one");
    ((Map<String, String>) doc.getProperty("map1")).put("two", "something");
    ((Map<String, Map<String, String>>) doc.getProperty("mapNested"))
        .get("nest")
        .put("other", "value");
    // test serialization/deserialization
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();

    byte[] bytes = serializerDelta.serializeDelta(db, doc);
    serializerDelta.deserializeDelta(db, bytes, originalDoc);
    assertNotNull(((Map) originalDoc.getProperty("mapEmbedded")).get("newDoc"));
    assertEquals(
        ((Map<String, Entity>) originalDoc.getProperty("mapEmbedded"))
            .get("newDoc")
            .getProperty("other"),
        Integer.valueOf(1));
    assertEquals(((Map) originalDoc.getProperty("map")).get("value"), "other");
    assertEquals(((Map) originalDoc.getProperty("map")).get("two"), "something");
    assertEquals(((Map) originalDoc.getProperty("map1")).get("two"), "something");
    assertNull(((Map) originalDoc.getProperty("map1")).get("one"));
    assertEquals(
        ((Map<String, Map<String, String>>) originalDoc.getProperty("mapNested"))
            .get("nest")
            .get("other"),
        "value");
    db.rollback();
  }

  @Test
  public void testSimpleSerialization() {
    EntityImpl document = new EntityImpl();

    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);
    document.field("heigth", 12.5f);
    document.field("bitHeigth", 12.5d);
    document.field("class", (byte) 'C');
    document.field("nullField", (Object) null);
    document.field("character", 'C');
    document.field("alive", true);
    document.field("dateTime", new Date());
    document.field(
        "bigNumber", new BigDecimal("43989872423376487952454365232141525434.32146432321442534"));
    RidBag bag = new RidBag(db);
    bag.add(new RecordId(1, 1));
    bag.add(new RecordId(2, 2));
    // document.field("ridBag", bag);
    Calendar c = Calendar.getInstance();
    document.field("date", c.getTime(), PropertyType.DATE);
    Calendar c1 = Calendar.getInstance();
    c1.set(Calendar.MILLISECOND, 0);
    c1.set(Calendar.SECOND, 0);
    c1.set(Calendar.MINUTE, 0);
    c1.set(Calendar.HOUR_OF_DAY, 0);
    document.field("date1", c1.getTime(), PropertyType.DATE);

    byte[] byteValue = new byte[10];
    Arrays.fill(byteValue, (byte) 10);
    document.field("bytes", byteValue);

    document.field("utf8String", "A" + "\u00ea" + "\u00f1" + "\u00fc" + "C");
    document.field("recordId", new RecordId(10, 10));

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

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
    // assertEquals(extr.field("ridBag"), document.field("ridBag"));

  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralArray() {
    EntityImpl document = new EntityImpl();
    String[] strings = new String[3];
    strings[0] = "a";
    strings[1] = "b";
    strings[2] = "c";
    document.field("listStrings", strings);

    Short[] shorts = new Short[3];
    shorts[0] = (short) 1;
    shorts[1] = (short) 2;
    shorts[2] = (short) 3;
    document.field("shorts", shorts);

    Long[] longs = new Long[3];
    longs[0] = (long) 1;
    longs[1] = (long) 2;
    longs[2] = (long) 3;
    document.field("longs", longs);

    Integer[] ints = new Integer[3];
    ints[0] = 1;
    ints[1] = 2;
    ints[2] = 3;
    document.field("integers", ints);

    Float[] floats = new Float[3];
    floats[0] = 1.1f;
    floats[1] = 2.2f;
    floats[2] = 3.3f;
    document.field("floats", floats);

    Double[] doubles = new Double[3];
    doubles[0] = 1.1d;
    doubles[1] = 2.2d;
    doubles[2] = 3.3d;
    document.field("doubles", doubles);

    Date[] dates = new Date[3];
    dates[0] = new Date();
    dates[1] = new Date();
    dates[2] = new Date();
    document.field("dates", dates);

    Byte[] bytes = new Byte[3];
    bytes[0] = (byte) 0;
    bytes[1] = (byte) 1;
    bytes[2] = (byte) 3;
    document.field("bytes", bytes);

    // TODO: char not currently supported
    Character[] chars = new Character[3];
    chars[0] = 'A';
    chars[1] = 'B';
    chars[2] = 'C';
    // document.field("chars", chars);

    Boolean[] booleans = new Boolean[3];
    booleans[0] = true;
    booleans[1] = false;
    booleans[2] = false;
    document.field("booleans", booleans);

    Object[] arrayNulls = new Object[3];
    // document.field("arrayNulls", arrayNulls);

    // Object[] listMixed = new ArrayList[9];
    // listMixed[0] = new Boolean(true);
    // listMixed[1] = 1;
    // listMixed[2] = (long) 5;
    // listMixed[3] = (short) 2;
    // listMixed[4] = 4.0f;
    // listMixed[5] = 7.0D;
    // listMixed[6] = "hello";
    // listMixed[7] = new Date();
    // listMixed[8] = (byte) 10;
    // document.field("listMixed", listMixed);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(((List) extr.field("listStrings")).toArray(), document.field("listStrings"));
    assertEquals(((List) extr.field("integers")).toArray(), document.field("integers"));
    assertEquals(((List) extr.field("doubles")).toArray(), document.field("doubles"));
    assertEquals(((List) extr.field("dates")).toArray(), document.field("dates"));
    assertEquals(((List) extr.field("bytes")).toArray(), document.field("bytes"));
    assertEquals(((List) extr.field("booleans")).toArray(), document.field("booleans"));
    // assertEquals(((List) extr.field("arrayNulls")).toArray(), document.field("arrayNulls"));
    // assertEquals(extr.field("listMixed"), document.field("listMixed"));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralList() {
    EntityImpl document = new EntityImpl();
    List<String> strings = new ArrayList<String>();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    document.field("listStrings", strings);

    List<Short> shorts = new ArrayList<Short>();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.field("shorts", shorts);

    List<Long> longs = new ArrayList<Long>();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.field("longs", longs);

    List<Integer> ints = new ArrayList<Integer>();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.field("integers", ints);

    List<Float> floats = new ArrayList<Float>();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.field("floats", floats);

    List<Double> doubles = new ArrayList<Double>();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.field("doubles", doubles);

    List<Date> dates = new ArrayList<Date>();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    document.field("dates", dates);

    List<Byte> bytes = new ArrayList<Byte>();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.field("bytes", bytes);

    // TODO: char not currently supported
    List<Character> chars = new ArrayList<Character>();
    chars.add('A');
    chars.add('B');
    chars.add('C');
    // document.field("chars", chars);

    List<Boolean> booleans = new ArrayList<Boolean>();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.field("booleans", booleans);

    List listMixed = new ArrayList();
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

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("listStrings"), document.field("listStrings"));
    assertEquals(extr.<Object>field("integers"), document.field("integers"));
    assertEquals(extr.<Object>field("doubles"), document.field("doubles"));
    assertEquals(extr.<Object>field("dates"), document.field("dates"));
    assertEquals(extr.<Object>field("bytes"), document.field("bytes"));
    assertEquals(extr.<Object>field("booleans"), document.field("booleans"));
    assertEquals(extr.<Object>field("listMixed"), document.field("listMixed"));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralSet() throws InterruptedException {
    EntityImpl document = new EntityImpl();
    Set<String> strings = new HashSet<String>();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    document.field("listStrings", strings);

    Set<Short> shorts = new HashSet<Short>();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.field("shorts", shorts);

    Set<Long> longs = new HashSet<Long>();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.field("longs", longs);

    Set<Integer> ints = new HashSet<Integer>();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.field("integers", ints);

    Set<Float> floats = new HashSet<Float>();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.field("floats", floats);

    Set<Double> doubles = new HashSet<Double>();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.field("doubles", doubles);

    Set<Date> dates = new HashSet<Date>();
    dates.add(new Date());
    Thread.sleep(1);
    dates.add(new Date());
    Thread.sleep(1);
    dates.add(new Date());
    document.field("dates", dates);

    Set<Byte> bytes = new HashSet<Byte>();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.field("bytes", bytes);

    // TODO: char not currently supported
    Set<Character> chars = new HashSet<Character>();
    chars.add('A');
    chars.add('B');
    chars.add('C');
    // document.field("chars", chars);

    Set<Boolean> booleans = new HashSet<Boolean>();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.field("booleans", booleans);

    Set listMixed = new HashSet();
    listMixed.add(true);
    listMixed.add(1);
    listMixed.add((long) 5);
    listMixed.add((short) 2);
    listMixed.add(4.0f);
    listMixed.add(7.0D);
    listMixed.add("hello");
    listMixed.add(new Date());
    listMixed.add((byte) 10);
    listMixed.add(new RecordId(10, 20));
    document.field("listMixed", listMixed);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("listStrings"), document.field("listStrings"));
    assertEquals(extr.<Object>field("integers"), document.field("integers"));
    assertEquals(extr.<Object>field("doubles"), document.field("doubles"));
    assertEquals(extr.<Object>field("dates"), document.field("dates"));
    assertEquals(extr.<Object>field("bytes"), document.field("bytes"));
    assertEquals(extr.<Object>field("booleans"), document.field("booleans"));
    assertEquals(extr.<Object>field("listMixed"), document.field("listMixed"));
  }

  @Test
  public void testLinkCollections() {
    EntityImpl document = new EntityImpl();
    Set<RecordId> linkSet = new HashSet<RecordId>();
    linkSet.add(new RecordId(10, 20));
    linkSet.add(new RecordId(10, 21));
    linkSet.add(new RecordId(10, 22));
    linkSet.add(new RecordId(11, 22));
    document.field("linkSet", linkSet, PropertyType.LINKSET);

    List<RecordId> linkList = new ArrayList<RecordId>();
    linkList.add(new RecordId(10, 20));
    linkList.add(new RecordId(10, 21));
    linkList.add(new RecordId(10, 22));
    linkList.add(new RecordId(11, 22));
    document.field("linkList", linkList, PropertyType.LINKLIST);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(
        ((Set<?>) extr.field("linkSet")).size(), ((Set<?>) document.field("linkSet")).size());
    assertTrue(((Set<?>) extr.field("linkSet")).containsAll(document.field("linkSet")));
    assertEquals(extr.<Object>field("linkList"), document.field("linkList"));
  }

  @Test
  public void testSimpleEmbeddedDoc() {
    EntityImpl document = new EntityImpl();
    EntityImpl embedded = new EntityImpl();
    embedded.field("name", "test");
    embedded.field("surname", "something");
    document.field("embed", embedded, PropertyType.EMBEDDED);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(document.fields(), extr.fields());
    EntityImpl emb = extr.field("embed");
    assertNotNull(emb);
    assertEquals(emb.<Object>field("name"), embedded.field("name"));
    assertEquals(emb.<Object>field("surname"), embedded.field("surname"));
  }

  @Test
  public void testSimpleMapStringLiteral() {
    EntityImpl document = new EntityImpl();

    Map<String, String> mapString = new HashMap<String, String>();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    document.field("mapString", mapString);

    Map<String, Integer> mapInt = new HashMap<String, Integer>();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    document.field("mapInt", mapInt);

    Map<String, Long> mapLong = new HashMap<String, Long>();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    document.field("mapLong", mapLong);

    Map<String, Short> shortMap = new HashMap<String, Short>();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    document.field("shortMap", shortMap);

    Map<String, Date> dateMap = new HashMap<String, Date>();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    document.field("dateMap", dateMap);

    Map<String, Float> floatMap = new HashMap<String, Float>();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    document.field("floatMap", floatMap);

    Map<String, Double> doubleMap = new HashMap<String, Double>();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    document.field("doubleMap", doubleMap);

    Map<String, Byte> bytesMap = new HashMap<String, Byte>();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    document.field("bytesMap", bytesMap);

    Map<String, String> mapWithNulls = new HashMap<String, String>();
    mapWithNulls.put("key", "dddd");
    mapWithNulls.put("key1", null);
    document.field("bytesMap", mapWithNulls);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("mapString"), document.field("mapString"));
    assertEquals(extr.<Object>field("mapLong"), document.field("mapLong"));
    assertEquals(extr.<Object>field("shortMap"), document.field("shortMap"));
    assertEquals(extr.<Object>field("dateMap"), document.field("dateMap"));
    assertEquals(extr.<Object>field("doubleMap"), document.field("doubleMap"));
    assertEquals(extr.<Object>field("bytesMap"), document.field("bytesMap"));
  }

  @Test
  public void testlistOfList() {
    EntityImpl document = new EntityImpl();
    List<List<String>> list = new ArrayList<List<String>>();
    List<String> ls = new ArrayList<String>();
    ls.add("test1");
    ls.add("test2");
    list.add(ls);
    document.field("complexList", list);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("complexList"), document.field("complexList"));
  }

  @Test
  public void testArrayOfArray() {
    EntityImpl document = new EntityImpl();
    String[][] array = new String[1][];
    String[] ls = new String[2];
    ls[0] = "test1";
    ls[1] = "test2";
    array[0] = ls;
    document.field("complexArray", array);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    List<List<String>> savedValue = extr.field("complexArray");
    assertEquals(savedValue.size(), array.length);
    assertEquals(savedValue.get(0).size(), array[0].length);
    assertEquals(savedValue.get(0).get(0), array[0][0]);
    assertEquals(savedValue.get(0).get(1), array[0][1]);
  }

  @Test
  public void testEmbeddedListOfEmbeddedMap() {
    EntityImpl document = new EntityImpl();
    List<Map<String, String>> coll = new ArrayList<Map<String, String>>();
    Map<String, String> map = new HashMap<String, String>();
    map.put("first", "something");
    map.put("second", "somethingElse");
    Map<String, String> map2 = new HashMap<String, String>();
    map2.put("first", "something");
    map2.put("second", "somethingElse");
    coll.add(map);
    coll.add(map2);
    document.field("list", coll);
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("list"), document.field("list"));
  }

  @Test
  public void testMapOfEmbeddedDocument() {
    EntityImpl document = new EntityImpl();

    EntityImpl embeddedInMap = new EntityImpl();
    embeddedInMap.field("name", "test");
    embeddedInMap.field("surname", "something");
    Map<String, EntityImpl> map = new HashMap<String, EntityImpl>();
    map.put("embedded", embeddedInMap);
    document.field("map", map, PropertyType.EMBEDDEDMAP);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    Map<String, EntityImpl> mapS = extr.field("map");
    assertEquals(1, mapS.size());
    EntityImpl emb = mapS.get("embedded");
    assertNotNull(emb);
    assertEquals(emb.<Object>field("name"), embeddedInMap.field("name"));
    assertEquals(emb.<Object>field("surname"), embeddedInMap.field("surname"));
  }

  @Test
  public void testMapOfLink() {
    // needs a database because of the lazy loading
    EntityImpl document = new EntityImpl();

    Map<String, Identifiable> map = new HashMap<String, Identifiable>();
    map.put("link", new RecordId(0, 0));
    document.field("map", map, PropertyType.LINKMAP);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("map"), document.field("map"));
  }

  @Test
  public void testDocumentSimple() {
    EntityImpl document = new EntityImpl("TestClass");
    document.field("test", "test");
    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    //      assertEquals(extr.getClassName(), document.getClassName());
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("test"), document.field("test"));
  }

  @Test
  public void testDocumentWithCostum() {
    boolean old = GlobalConfiguration.DB_CUSTOM_SUPPORT.getValueAsBoolean();
    GlobalConfiguration.DB_CUSTOM_SUPPORT.setValue(true);

    EntityImpl document = new EntityImpl();
    document.field("test", "test");
    document.field("custom", new DocumentSchemalessBinarySerializationTest.Custom());

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.getClassName(), document.getClassName());
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("test"), document.field("test"));
    assertEquals(extr.<Object>field("custom"), document.field("custom"));
    GlobalConfiguration.DB_CUSTOM_SUPPORT.setValue(old);
  }

  @Test
  public void testDocumentWithCostumDocument() {
    EntityImpl document = new EntityImpl();
    document.field("test", "test");
    document.field("custom", new DocumentSchemalessBinarySerializationTest.CustomDocument());

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.getClassName(), document.getClassName());
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("test"), document.field("test"));
    assertEquals(extr.<Object>field("custom"), document.field("custom"));
  }

  @Test(expected = SerializationException.class)
  public void testSetOfWrongData() {
    EntityImpl document = new EntityImpl();

    Set<Object> embeddedSet = new HashSet<Object>();
    embeddedSet.add(new WrongData());
    document.field("embeddedSet", embeddedSet, PropertyType.EMBEDDEDSET);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test(expected = SerializationException.class)
  public void testListOfWrongData() {
    EntityImpl document = new EntityImpl();

    List<Object> embeddedList = new ArrayList<Object>();
    embeddedList.add(new WrongData());
    document.field("embeddedList", embeddedList, PropertyType.EMBEDDEDLIST);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test(expected = SerializationException.class)
  public void testMapOfWrongData() {
    EntityImpl document = new EntityImpl();

    Map<String, Object> embeddedMap = new HashMap<String, Object>();
    embeddedMap.put("name", new WrongData());
    document.field("embeddedMap", embeddedMap, PropertyType.EMBEDDEDMAP);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test(expected = ClassCastException.class)
  public void testLinkSetOfWrongData() {
    EntityImpl document = new EntityImpl();

    Set<Object> linkSet = new HashSet<Object>();
    linkSet.add(new WrongData());
    document.field("linkSet", linkSet, PropertyType.LINKSET);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test(expected = ClassCastException.class)
  public void testLinkListOfWrongData() {
    EntityImpl document = new EntityImpl();

    List<Object> linkList = new ArrayList<Object>();
    linkList.add(new WrongData());
    document.field("linkList", linkList, PropertyType.LINKLIST);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test(expected = ClassCastException.class)
  public void testLinkMapOfWrongData() {
    EntityImpl document = new EntityImpl();

    Map<String, Object> linkMap = new HashMap<String, Object>();
    linkMap.put("name", new WrongData());
    document.field("linkMap", linkMap, PropertyType.LINKMAP);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test(expected = SerializationException.class)
  public void testFieldWrongData() {
    EntityImpl document = new EntityImpl();

    document.field("wrongData", new WrongData());

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
  }

  @Test
  public void testCollectionOfEmbeddedDocument() {
    EntityImpl document = new EntityImpl();

    EntityImpl embeddedInList = new EntityImpl();
    embeddedInList.field("name", "test");
    embeddedInList.field("surname", "something");

    EntityImpl embeddedInList2 = new EntityImpl();
    embeddedInList2.field("name", "test1");
    embeddedInList2.field("surname", "something2");

    List<EntityImpl> embeddedList = new ArrayList<EntityImpl>();
    embeddedList.add(embeddedInList);
    embeddedList.add(embeddedInList2);
    embeddedList.add(null);
    embeddedList.add(new EntityImpl());
    document.field("embeddedList", embeddedList, PropertyType.EMBEDDEDLIST);

    EntityImpl embeddedInSet = new EntityImpl();
    embeddedInSet.field("name", "test2");
    embeddedInSet.field("surname", "something3");

    EntityImpl embeddedInSet2 = new EntityImpl();
    embeddedInSet2.field("name", "test5");
    embeddedInSet2.field("surname", "something6");

    Set<EntityImpl> embeddedSet = new HashSet<EntityImpl>();
    embeddedSet.add(embeddedInSet);
    embeddedSet.add(embeddedInSet2);
    embeddedSet.add(new EntityImpl());
    document.field("embeddedSet", embeddedSet, PropertyType.EMBEDDEDSET);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    List<EntityImpl> ser = extr.field("embeddedList");
    assertEquals(ser.size(), 4);
    assertNotNull(ser.get(0));
    assertNotNull(ser.get(1));
    assertNull(ser.get(2));
    assertNotNull(ser.get(3));
    EntityImpl inList = ser.get(0);
    assertNotNull(inList);
    assertEquals(inList.<Object>field("name"), embeddedInList.field("name"));
    assertEquals(inList.<Object>field("surname"), embeddedInList.field("surname"));

    Set<EntityImpl> setEmb = extr.field("embeddedSet");
    assertEquals(setEmb.size(), 3);
    boolean ok = false;
    for (EntityImpl inSet : setEmb) {
      assertNotNull(inSet);
      if (embeddedInSet.field("name").equals(inSet.field("name"))
          && embeddedInSet.field("surname").equals(inSet.field("surname"))) {
        if (true) {
          if (true) {
            if (true) {
              ok = true;
            }
          }
        }
      }
    }
    assertTrue("not found record in the set after serilize", ok);
  }

  @Test
  public void testSerializableValue() {
    boolean old = GlobalConfiguration.DB_CUSTOM_SUPPORT.getValueAsBoolean();
    GlobalConfiguration.DB_CUSTOM_SUPPORT.setValue(true);

    EntityImpl document = new EntityImpl();
    SimpleSerializableClass ser = new SimpleSerializableClass();
    ser.name = "testName";
    document.field("seri", ser);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertNotNull(extr.field("seri"));
    assertEquals(extr.fieldType("seri"), PropertyType.CUSTOM);
    SimpleSerializableClass newser = extr.field("seri");
    assertEquals(newser.name, ser.name);
    GlobalConfiguration.DB_CUSTOM_SUPPORT.setValue(old);
  }

  @Test
  public void testFieldNames() {
    EntityImpl document = new EntityImpl();
    document.fields("a", 1, "b", 2, "c", 3);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    final String[] fields = extr.fieldNames();

    assertNotNull(fields);
    assertEquals(fields.length, 3);
    assertEquals(fields[0], "a");
    assertEquals(fields[1], "b");
    assertEquals(fields[2], "c");
  }

  @Test
  public void testWithRemove() {
    EntityImpl document = new EntityImpl();
    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);
    document.removeField("oldAge");

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(document.field("name"), extr.<Object>field("name"));
    assertEquals(document.<Object>field("age"), extr.field("age"));
    assertEquals(document.<Object>field("youngAge"), extr.field("youngAge"));
    assertNull(extr.field("oldAge"));
  }

  @Test
  public void testListOfMapsWithNull() {
    EntityImpl document = new EntityImpl();

    List lista = new ArrayList<>();
    Map mappa = new LinkedHashMap<>();
    mappa.put("prop1", "val1");
    mappa.put("prop2", null);
    lista.add(mappa);

    mappa = new HashMap();
    mappa.put("prop", "val");
    lista.add(mappa);
    document.setProperty("list", lista);

    DocumentSerializerDelta serializerDelta = DocumentSerializerDelta.instance();
    byte[] res = serializerDelta.serialize(document);
    EntityImpl extr = new EntityImpl();
    serializerDelta.deserialize(db, res, extr);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("list"), document.field("list"));
  }

  public static class CustomDocument implements DocumentSerializable {

    private EntityImpl document;

    @Override
    public void fromDocument(EntityImpl document) {
      this.document = document;
    }

    @Override
    public EntityImpl toDocument() {
      document = new EntityImpl();
      document.field("test", "some strange content");
      return document;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      return document.field("test").equals(((CustomDocument) obj).document.field("test"));
    }
  }

  private class WrongData {

  }
}
