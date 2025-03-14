package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class ClassIndexManagerTest extends BaseDBTest {

  @Parameters(value = "remote")
  public ClassIndexManagerTest(@Optional Boolean remote) {
    super(remote != null ? remote : false);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();

    if (schema.existsClass("classIndexManagerTestClass")) {
      schema.dropClass("classIndexManagerTestClass");
    }

    if (schema.existsClass("classIndexManagerTestClassTwo")) {
      schema.dropClass("classIndexManagerTestClassTwo");
    }

    if (schema.existsClass("classIndexManagerTestSuperClass")) {
      schema.dropClass("classIndexManagerTestSuperClass");
    }

    if (schema.existsClass("classIndexManagerTestCompositeCollectionClass")) {
      schema.dropClass("classIndexManagerTestCompositeCollectionClass");
    }

    final var superClass = schema.createClass("classIndexManagerTestSuperClass");
    superClass.createProperty("prop0", PropertyType.STRING);
    superClass.createIndex(
        "classIndexManagerTestSuperClass.prop0",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"prop0"});

    final var oClass = schema.createClass("classIndexManagerTestClass", superClass);
    oClass.createProperty("prop1", PropertyType.STRING);
    oClass.createIndex(
        "classIndexManagerTestClass.prop1",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"prop1"});

    final var propTwo = oClass.createProperty("prop2", PropertyType.INTEGER);
    propTwo.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    oClass.createProperty("prop3", PropertyType.BOOLEAN);

    final var propFour = oClass.createProperty("prop4", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    propFour.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    oClass.createProperty("prop5", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    oClass.createIndex("classIndexManagerTestIndexByKey",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop5");
    oClass.createIndex(
        "classIndexManagerTestIndexByValue", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop5 by value");

    final var propSix = oClass.createProperty("prop6", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    propSix.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    oClass.createIndex(
        "classIndexManagerComposite",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop1", "prop2"});

    final var oClassTwo = schema.createClass("classIndexManagerTestClassTwo");
    oClassTwo.createProperty("prop1", PropertyType.STRING);
    oClassTwo.createProperty("prop2", PropertyType.INTEGER);

    final var compositeCollectionClass =
        schema.createClass("classIndexManagerTestCompositeCollectionClass");
    compositeCollectionClass.createProperty("prop1", PropertyType.STRING);
    compositeCollectionClass.createProperty("prop2", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);

    compositeCollectionClass.createIndex(
        "classIndexManagerTestIndexValueAndCollection",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"prop1", "prop2"});

    oClass.createIndex(
        "classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"prop0", "prop1"});

    session.close();
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.command("delete from classIndexManagerTestClass").close();
    session.commit();

    session.begin();
    session.command("delete from classIndexManagerTestClassTwo").close();
    session.commit();

    session.begin();
    session.command("delete from classIndexManagerTestSuperClass").close();
    session.commit();

    if (!session.getStorage().isRemote()) {
      Assert.assertEquals(
          session
              .getMetadata()
              .getIndexManagerInternal()
              .getIndex(session, "classIndexManagerTestClass.prop1")
              .size(session),
          0);
      Assert.assertEquals(
          session
              .getMetadata()
              .getIndexManagerInternal()
              .getIndex(session, "classIndexManagerTestClass.prop2")
              .size(session),
          0);
    }

    super.afterMethod();
  }

  public void testPropertiesCheckUniqueIndexDubKeysCreate() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.field("prop1", "a");
    session.begin();

    session.commit();

    var exceptionThrown = false;
    try {
      docTwo.field("prop1", "a");
      session.begin();

      session.commit();

    } catch (RecordDuplicatedException e) {
      exceptionThrown = true;
    }
    Assert.assertTrue(exceptionThrown);
  }

  public void testPropertiesCheckUniqueIndexDubKeyIsNullCreate() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.field("prop1", "a");
    session.begin();

    session.commit();

    docTwo.field("prop1", null);
    session.begin();

    session.commit();
  }

  public void testPropertiesCheckUniqueIndexDubKeyIsNullCreateInTx() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    session.begin();
    docOne.field("prop1", "a");

    docTwo.field("prop1", null);

    session.commit();
  }

  public void testPropertiesCheckUniqueIndexInParentDubKeysCreate() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.field("prop0", "a");
    session.begin();

    session.commit();

    var exceptionThrown = false;
    try {
      docTwo.field("prop0", "a");
      session.begin();

      session.commit();
    } catch (RecordDuplicatedException e) {
      exceptionThrown = true;
    }
    Assert.assertTrue(exceptionThrown);
  }

  public void testPropertiesCheckUniqueIndexDubKeysUpdate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    var exceptionThrown = false;
    docOne.field("prop1", "a");

    session.commit();

    session.begin();
    docTwo.field("prop1", "b");

    session.commit();

    try {
      session.begin();
      docTwo = session.bindToSession(docTwo);
      docTwo.field("prop1", "a");

      session.commit();
    } catch (RecordDuplicatedException e) {
      exceptionThrown = true;
    }
    Assert.assertTrue(exceptionThrown);
  }

  public void testPropertiesCheckUniqueIndexDubKeyIsNullUpdate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.field("prop1", "a");

    session.commit();

    session.begin();
    docTwo.field("prop1", "b");

    session.commit();

    session.begin();
    docTwo = session.bindToSession(docTwo);
    docTwo.field("prop1", null);

    session.commit();
  }

  public void testPropertiesCheckUniqueIndexDubKeyIsNullUpdateInTX() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    session.begin();
    docOne.field("prop1", "a");

    docTwo.field("prop1", "b");

    docTwo.field("prop1", null);

    session.commit();
  }

  public void testPropertiesCheckNonUniqueIndexDubKeys() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.field("prop2", 1);
    session.begin();

    session.commit();

    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docTwo.field("prop2", 1);
    session.begin();

    session.commit();
  }

  public void testPropertiesCheckUniqueNullKeys() {
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    session.begin();

    session.commit();

    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    session.begin();

    session.commit();
  }

  public void testCreateDocumentWithoutClass() {
    checkEmbeddedDB();

    final var beforeIndexes =
        session.getMetadata().getIndexManagerInternal().getIndexes(session);
    final Map<String, Long> indexSizeMap = new HashMap<>();

    for (final var index : beforeIndexes) {
      indexSizeMap.put(index.getName(), index.size(session));
    }

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.field("prop1", "a");

    final var docTwo = ((EntityImpl) session.newEntity());
    docTwo.field("prop1", "a");

    session.commit();

    final var afterIndexes =
        session.getMetadata().getIndexManagerInternal().getIndexes(session);
    for (final var index : afterIndexes) {
      Assert.assertEquals(
          index.size(session), indexSizeMap.get(index.getName()).longValue());
    }
  }

  public void testUpdateDocumentWithoutClass() {
    checkEmbeddedDB();

    final var beforeIndexes =
        session.getMetadata().getIndexManagerInternal().getIndexes(session);
    final Map<String, Long> indexSizeMap = new HashMap<>();

    for (final var index : beforeIndexes) {
      indexSizeMap.put(index.getName(), index.size(session));
    }

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.field("prop1", "a");

    final var docTwo = ((EntityImpl) session.newEntity());
    docTwo.field("prop1", "b");

    docOne.field("prop1", "a");

    session.commit();

    final var afterIndexes =
        session.getMetadata().getIndexManagerInternal().getIndexes(session);
    for (final var index : afterIndexes) {
      Assert.assertEquals(
          index.size(session), indexSizeMap.get(index.getName()).longValue());
    }
  }

  public void testDeleteDocumentWithoutClass() {
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.field("prop1", "a");

    session.begin();

    session.commit();

    session.begin();
    session.bindToSession(docOne).delete();
    session.commit();
  }

  public void testDeleteModifiedDocumentWithoutClass() {
    var docOne = ((EntityImpl) session.newEntity());
    docOne.field("prop1", "a");

    session.begin();

    session.commit();

    session.begin();
    docOne = session.bindToSession(docOne);
    docOne.field("prop1", "b");
    docOne.delete();
    session.commit();
  }

  public void testDocumentUpdateWithoutDirtyFields() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.field("prop1", "a");

    session.commit();

    session.begin();
    docOne = session.bindToSession(docOne);
    docOne.setDirty();

    session.commit();
  }

  public void testCreateDocumentIndexRecordAdded() {
    checkEmbeddedDB();

    final var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop0", "x");
    doc.field("prop1", "a");
    doc.field("prop2", 1);

    session.begin();

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.getClass("classIndexManagerTestClass");
    final var oSuperClass = schema.getClass("classIndexManagerTestSuperClass");

    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "a")) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    Assert.assertEquals(propOneIndex.size(session), 1);

    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");

    final var compositeIndexDefinition = compositeIndex.getDefinition();
    try (var rids =
        compositeIndex

            .getRids(session, compositeIndexDefinition.createValue(session, "a", 1))) {
      Assert.assertTrue(rids.findFirst().isPresent());
    }
    Assert.assertEquals(compositeIndex.size(session), 1);

    final var propZeroIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestSuperClass.prop0");
    try (var stream = propZeroIndex.getRids(session, "x")) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    Assert.assertEquals(propZeroIndex.size(session), 1);
  }

  public void testUpdateDocumentIndexRecordRemoved() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop0", "x");
    doc.field("prop1", "a");
    doc.field("prop2", 1);

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oSuperClass = schema.getClass("classIndexManagerTestSuperClass");
    final var oClass = schema.getClass("classIndexManagerTestClass");

    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");
    final var propZeroIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestSuperClass.prop0");

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);
    Assert.assertEquals(propZeroIndex.size(session), 1);

    session.begin();
    doc = session.bindToSession(doc);
    doc.removeField("prop2");
    doc.removeField("prop0");

    session.commit();

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 0);
    Assert.assertEquals(propZeroIndex.size(session), 0);
  }

  public void testUpdateDocumentNullKeyIndexRecordRemoved() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    doc.field("prop0", "x");
    doc.field("prop1", "a");
    doc.field("prop2", 1);

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oSuperClass = schema.getClass("classIndexManagerTestSuperClass");
    final var oClass = schema.getClass("classIndexManagerTestClass");

    final var propOneIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");
    final var propZeroIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestSuperClass.prop0");

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);
    Assert.assertEquals(propZeroIndex.size(session), 1);

    session.begin();
    doc = session.bindToSession(doc);
    doc.field("prop2", null);
    doc.field("prop0", null);

    session.commit();

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 0);
    Assert.assertEquals(propZeroIndex.size(session), 0);
  }

  public void testUpdateDocumentIndexRecordUpdated() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop0", "x");
    doc.field("prop1", "a");
    doc.field("prop2", 1);

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oSuperClass = schema.getClass("classIndexManagerTestSuperClass");
    final var oClass = schema.getClass("classIndexManagerTestClass");

    final var propZeroIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestSuperClass.prop0");
    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");
    final var compositeIndexDefinition = compositeIndex.getDefinition();

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);
    Assert.assertEquals(propZeroIndex.size(session), 1);

    session.begin();
    doc = session.bindToSession(doc);
    doc.field("prop2", 2);
    doc.field("prop0", "y");

    session.commit();

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);
    Assert.assertEquals(propZeroIndex.size(session), 1);

    try (var stream = propZeroIndex.getRids(session, "y")) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = propOneIndex.getRids(session, "a")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream =
        compositeIndex

            .getRids(session, compositeIndexDefinition.createValue(session, "a", 2))) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  public void testUpdateDocumentIndexRecordUpdatedFromNullField() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop1", "a");
    doc.field("prop2", null);

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.getClass("classIndexManagerTestClass");

    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");
    final var compositeIndexDefinition = compositeIndex.getDefinition();

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 0);

    session.begin();
    doc = session.bindToSession(doc);
    doc.field("prop2", 2);

    session.commit();

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);

    try (var stream = propOneIndex.getRids(session, "a")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream =
        compositeIndex

            .getRids(session, compositeIndexDefinition.createValue(session, "a", 2))) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
  }

  public void testListUpdate() {
    checkEmbeddedDB();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.getClass("classIndexManagerTestClass");

    final var propFourIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop4");

    Assert.assertEquals(propFourIndex.size(session), 0);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    final List<String> listProperty = new ArrayList<>();
    listProperty.add("value1");
    listProperty.add("value2");

    doc.field("prop4", listProperty);

    session.commit();

    Assert.assertEquals(propFourIndex.size(session), 2);
    try (var stream = propFourIndex.getRids(session, "value1")) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value2")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    List<String> trackedList = doc.field("prop4");
    trackedList.set(0, "value3");

    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.remove("value4");
    trackedList.remove("value2");
    trackedList.add("value5");

    session.commit();

    Assert.assertEquals(propFourIndex.size(session), 3);
    try (var stream = propFourIndex.getRids(session, "value3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value4")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    try (var stream = propFourIndex.getRids(session, "value5")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  public void testMapUpdate() {
    checkEmbeddedDB();

    final var propFiveIndexKey = session.getMetadata().getIndexManagerInternal()
        .getIndex(session,
            "classIndexManagerTestIndexByKey");
    final var propFiveIndexValue = session.getMetadata().getIndexManagerInternal()
        .getIndex(session,
            "classIndexManagerTestIndexByValue");

    Assert.assertEquals(propFiveIndexKey.size(session), 0);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    final Map<String, String> mapProperty = new HashMap<>();
    mapProperty.put("key1", "value1");
    mapProperty.put("key2", "value2");

    doc.field("prop5", mapProperty);

    session.commit();

    Assert.assertEquals(propFiveIndexKey.size(session), 2);
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key2")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    Map<String, String> trackedMap = doc.field("prop5");
    trackedMap.put("key3", "value3");
    trackedMap.put("key4", "value4");
    trackedMap.remove("key1");
    trackedMap.put("key1", "value5");
    trackedMap.remove("key2");
    trackedMap.put("key6", "value6");
    trackedMap.put("key7", "value6");
    trackedMap.put("key8", "value6");
    trackedMap.put("key4", "value7");

    trackedMap.remove("key8");

    session.commit();

    Assert.assertEquals(propFiveIndexKey.size(session), 5);
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key4")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key6")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key7")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    Assert.assertEquals(propFiveIndexValue.size(session), 4);
    try (var stream = propFiveIndexValue.getRids(session, "value5")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value7")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value6")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  public void testSetUpdate() {
    checkEmbeddedDB();

    final var propSixIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop6");

    Assert.assertEquals(propSixIndex.size(session), 0);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    final Set<String> setProperty = new HashSet<>();
    setProperty.add("value1");
    setProperty.add("value2");

    doc.field("prop6", setProperty);

    session.commit();

    Assert.assertEquals(propSixIndex.size(session), 2);
    try (var stream = propSixIndex.getRids(session, "value1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value2")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    Set<String> trackedSet = doc.field("prop6");

    //noinspection OverwrittenKey
    trackedSet.add("value4");
    //noinspection OverwrittenKey
    trackedSet.add("value4");
    //noinspection OverwrittenKey
    trackedSet.add("value4");
    trackedSet.remove("value4");
    trackedSet.remove("value2");
    trackedSet.add("value5");

    session.commit();

    Assert.assertEquals(propSixIndex.size(session), 2);
    try (var stream = propSixIndex.getRids(session, "value1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value5")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  public void testListDelete() {
    checkEmbeddedDB();

    final var propFourIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop4");

    Assert.assertEquals(propFourIndex.size(session), 0);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    final List<String> listProperty = new ArrayList<>();
    listProperty.add("value1");
    listProperty.add("value2");

    doc.field("prop4", listProperty);

    session.commit();

    Assert.assertEquals(propFourIndex.size(session), 2);
    try (var stream = propFourIndex.getRids(session, "value1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value2")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    List<String> trackedList = doc.field("prop4");
    trackedList.set(0, "value3");

    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.remove("value4");
    trackedList.remove("value2");
    trackedList.add("value5");

    session.commit();

    Assert.assertEquals(propFourIndex.size(session), 3);
    try (var stream = propFourIndex.getRids(session, "value3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value4")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value5")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    trackedList = doc.field("prop4");
    trackedList.remove("value3");
    trackedList.remove("value4");
    trackedList.add("value8");

    doc.delete();
    session.commit();

    Assert.assertEquals(propFourIndex.size(session), 0);
  }

  public void testMapDelete() {
    checkEmbeddedDB();

    final var propFiveIndexKey = session.getMetadata().getIndexManagerInternal()
        .getIndex(session,
            "classIndexManagerTestIndexByKey");
    final var propFiveIndexValue = session.getMetadata().getIndexManagerInternal()
        .getIndex(session,
            "classIndexManagerTestIndexByValue");

    Assert.assertEquals(propFiveIndexKey.size(session), 0);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    final Map<String, String> mapProperty = new HashMap<>();
    mapProperty.put("key1", "value1");
    mapProperty.put("key2", "value2");

    doc.field("prop5", mapProperty);

    session.commit();

    Assert.assertEquals(propFiveIndexKey.size(session), 2);
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key2")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    Map<String, String> trackedMap = doc.field("prop5");
    trackedMap.put("key3", "value3");
    trackedMap.put("key4", "value4");
    trackedMap.remove("key1");
    trackedMap.put("key1", "value5");
    trackedMap.remove("key2");
    trackedMap.put("key6", "value6");
    trackedMap.put("key7", "value6");
    trackedMap.put("key8", "value6");
    trackedMap.put("key4", "value7");

    trackedMap.remove("key8");

    session.commit();

    Assert.assertEquals(propFiveIndexKey.size(session), 5);
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key4")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key6")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key7")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    Assert.assertEquals(propFiveIndexValue.size(session), 4);
    try (var stream = propFiveIndexValue.getRids(session, "value5")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value3")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value7")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value6")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    trackedMap = doc.field("prop5");

    trackedMap.remove("key1");
    trackedMap.remove("key3");
    trackedMap.remove("key4");
    trackedMap.put("key6", "value10");
    trackedMap.put("key11", "value11");

    doc.delete();
    session.commit();

    Assert.assertEquals(propFiveIndexKey.size(session), 0);
    Assert.assertEquals(propFiveIndexValue.size(session), 0);
  }

  public void testSetDelete() {
    checkEmbeddedDB();
    final var propSixIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop6");

    Assert.assertEquals(propSixIndex.size(session), 0);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    final Set<String> setProperty = new HashSet<>();
    setProperty.add("value1");
    setProperty.add("value2");

    doc.field("prop6", setProperty);

    session.commit();

    Assert.assertEquals(propSixIndex.size(session), 2);
    try (var stream = propSixIndex.getRids(session, "value1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value2")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    Set<String> trackedSet = doc.field("prop6");

    //noinspection OverwrittenKey
    trackedSet.add("value4");
    //noinspection OverwrittenKey
    trackedSet.add("value4");
    //noinspection OverwrittenKey
    trackedSet.add("value4");
    trackedSet.remove("value4");
    trackedSet.remove("value2");
    trackedSet.add("value5");

    session.commit();

    Assert.assertEquals(propSixIndex.size(session), 2);
    try (var stream = propSixIndex.getRids(session, "value1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value5")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();
    doc = session.bindToSession(doc);
    trackedSet = doc.field("prop6");
    trackedSet.remove("value1");
    trackedSet.add("value6");

    doc.delete();
    session.commit();

    Assert.assertEquals(propSixIndex.size(session), 0);
  }

  public void testDeleteDocumentIndexRecordDeleted() {
    checkEmbeddedDB();

    final var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop0", "x");
    doc.field("prop1", "a");
    doc.field("prop2", 1);

    session.begin();

    session.commit();

    final var propZeroIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestSuperClass.prop0");
    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");

    Assert.assertEquals(propZeroIndex.size(session), 1);
    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(propZeroIndex.size(session), 0);
    Assert.assertEquals(propOneIndex.size(session), 0);
    Assert.assertEquals(compositeIndex.size(session), 0);
  }

  public void testDeleteUpdatedDocumentIndexRecordDeleted() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop0", "x");
    doc.field("prop1", "a");
    doc.field("prop2", 1);

    session.commit();

    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");

    final var propZeroIndex = session.getMetadata().getIndexManagerInternal().getIndex(session,
        "classIndexManagerTestSuperClass.prop0");
    Assert.assertEquals(propZeroIndex.size(session), 1);
    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 1);

    session.begin();
    doc = session.bindToSession(doc);
    doc.field("prop2", 2);
    doc.field("prop0", "y");

    doc.delete();
    session.commit();

    Assert.assertEquals(propZeroIndex.size(session), 0);
    Assert.assertEquals(propOneIndex.size(session), 0);
    Assert.assertEquals(compositeIndex.size(session), 0);
  }

  public void testDeleteUpdatedDocumentNullFieldIndexRecordDeleted() {
    checkEmbeddedDB();

    session.begin();
    final var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop1", "a");
    doc.field("prop2", null);

    session.commit();

    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(propOneIndex.size(session), 0);
    Assert.assertEquals(compositeIndex.size(session), 0);
  }

  public void testDeleteUpdatedDocumentOrigNullFieldIndexRecordDeleted() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.field("prop1", "a");
    doc.field("prop2", null);

    session.commit();

    final var propOneIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getMetadata().getIndexManagerInternal()
        .getIndex(session, "classIndexManagerComposite");

    Assert.assertEquals(propOneIndex.size(session), 1);
    Assert.assertEquals(compositeIndex.size(session), 0);

    session.begin();
    doc = session.bindToSession(doc);
    doc.field("prop2", 2);

    doc.delete();
    session.commit();

    Assert.assertEquals(propOneIndex.size(session), 0);
    Assert.assertEquals(compositeIndex.size(session), 0);
  }

  public void testNoClassIndexesUpdate() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClassTwo"));
    doc.field("prop1", "a");

    session.commit();
    session.begin();
    doc = session.bindToSession(doc);
    doc.field("prop1", "b");

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = (SchemaClassInternal) schema.getClass(
        "classIndexManagerTestClass");

    final Collection<Index> indexes = oClass.getIndexesInternal();
    for (final var index : indexes) {
      Assert.assertEquals(index.size(session), 0);
    }
  }

  public void testNoClassIndexesDelete() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClassTwo"));
    doc.field("prop1", "a");

    session.commit();

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();
  }

  public void testCollectionCompositeCreation() {
    checkEmbeddedDB();

    final var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.begin();

    session.commit();

    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    try (var stream = index
        .getRids(session, new CompositeKey("test1", 1))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 2))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeNullSimpleFieldCreation() {
    checkEmbeddedDB();

    session.begin();
    final var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", null);
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();
  }

  public void testCollectionCompositeNullCollectionFieldCreation() {
    checkEmbeddedDB();

    final var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", null);

    session.begin();

    session.commit();

    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();
  }

  public void testCollectionCompositeUpdateSimpleField() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop1", "test2");

    session.commit();

    try (var stream = index
        .getRids(session, new CompositeKey("test2", 1))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 2))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }

    Assert.assertEquals(index.size(session), 2);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateCollectionWasAssigned() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop2", Arrays.asList(1, 3));

    session.commit();

    try (var stream = index
        .getRids(session, new CompositeKey("test1", 1))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 3))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }

    Assert.assertEquals(index.size(session), 2);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateCollectionWasChanged() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    List<Integer> docList = doc.field("prop2");
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.remove(0);

    session.commit();

    try (var stream = index
        .getRids(session, new CompositeKey("test1", 2))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 3))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 4))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 5))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }

    Assert.assertEquals(index.size(session), 4);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateCollectionWasChangedSimpleFieldWasAssigned() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    List<Integer> docList = doc.field("prop2");
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.remove(0);

    doc.field("prop1", "test2");

    session.commit();

    Assert.assertEquals(index.size(session), 4);

    try (var stream = index
        .getRids(session, new CompositeKey("test2", 2))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 3))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 4))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 5))) {
      Assert.assertEquals(stream.findAny().orElse(null), doc.getIdentity());
    }

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateSimpleFieldNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop1", null);

    session.commit();

    Assert.assertEquals(index.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateCollectionWasAssignedNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop2", null);

    session.commit();

    Assert.assertEquals(index.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateBothAssignedNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc.field("prop2", null);
    doc.field("prop1", null);

    session.commit();

    Assert.assertEquals(index.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeUpdateCollectionWasChangedSimpleFieldWasAssignedNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    List<Integer> docList = doc.field("prop2");
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.remove(0);

    doc.field("prop1", null);

    session.commit();

    Assert.assertEquals(index.size(session), 0);

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteSimpleFieldAssigend() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop1", "test2");

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteCollectionFieldAssigend() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop2", Arrays.asList(1, 3));

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteCollectionFieldChanged() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    List<Integer> docList = doc.field("prop2");
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteBothCollectionSimpleFieldChanged() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    List<Integer> docList = doc.field("prop2");
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.field("prop1", "test2");

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteBothCollectionSimpleFieldAssigend() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop2", Arrays.asList(1, 3));
    doc.field("prop1", "test2");

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteSimpleFieldNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc.field("prop1", null);

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteCollectionFieldNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc = session.bindToSession(doc);
    doc.field("prop2", null);

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteBothSimpleCollectionFieldNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    doc.field("prop2", null);
    doc.field("prop1", null);

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testCollectionCompositeDeleteCollectionFieldChangedSimpleFieldNull() {
    checkEmbeddedDB();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestCompositeCollectionClass"));

    doc.field("prop1", "test1");
    doc.field("prop2", Arrays.asList(1, 2));

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexValueAndCollection");
    Assert.assertEquals(index.size(session), 2);

    List<Integer> docList = doc.field("prop2");
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.field("prop1", null);

    doc.delete();
    session.commit();

    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexOnPropertiesFromClassAndSuperclass() {
    checkEmbeddedDB();

    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.field("prop0", "doc1-prop0");
    docOne.field("prop1", "doc1-prop1");

    session.begin();

    session.commit();

    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docTwo.field("prop0", "doc2-prop0");
    docTwo.field("prop1", "doc2-prop1");

    session.begin();

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var index =
        session.getMetadata().getIndexManagerInternal()
            .getIndex(session, "classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass");

    Assert.assertEquals(index.size(session), 2);
  }
}
