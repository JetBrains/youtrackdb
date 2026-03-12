package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class ClassIndexManagerTest extends BaseDBJUnit5Test {

  public static final String COMPOSITE_TWO_COLLECTIONS_CLASS =
      "ClassIndexManagerTestCompositeTwoCollectionsClass";
  public static final String COMPOSITE_TWO_COLLECTIONS_INDEX =
      "ClassIndexManagerTestIndexTwoCollections";

  public static final String COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_CLASS =
      "ClassIndexManagerTestCompositeTwoCollectionsPrimitiveClass";
  public static final String COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_INDEX =
      "ClassIndexManagerTestIndexThreeCollectionsPrimitive";

  public static final String PROP_1 = "prop1";
  public static final String PROP_2 = "prop2";
  public static final String PROP_3 = "prop3";

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();

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
    if (schema.existsClass(COMPOSITE_TWO_COLLECTIONS_CLASS)) {
      schema.dropClass(COMPOSITE_TWO_COLLECTIONS_CLASS);
    }

    final var superClass = schema.createClass("classIndexManagerTestSuperClass");
    superClass.createProperty("prop0", PropertyType.STRING);
    superClass.createIndex(
        "classIndexManagerTestSuperClass.prop0",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop0"});

    final var oClass = schema.createClass("classIndexManagerTestClass", superClass);
    oClass.createProperty("prop1", PropertyType.STRING);
    oClass.createIndex(
        "classIndexManagerTestClass.prop1",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop1"});

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
        "classIndexManagerTestIndexByValue", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop5 by value");

    final var propSix = oClass.createProperty("prop6", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    propSix.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    oClass.createIndex(
        "classIndexManagerComposite",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"prop1", "prop2"});

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
        new String[] {"prop1", "prop2"});

    final var compositeTwoCollectionClass =
        schema.createClass(COMPOSITE_TWO_COLLECTIONS_CLASS);
    compositeTwoCollectionClass.createProperty("prop1", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    compositeTwoCollectionClass.createProperty("prop2", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    compositeTwoCollectionClass.createIndex(COMPOSITE_TWO_COLLECTIONS_INDEX,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {PROP_1, PROP_2});

    final var compositeTwoCollectionPrimitiveClass =
        schema.createClass(COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_CLASS);
    compositeTwoCollectionPrimitiveClass.createProperty(PROP_1, PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    compositeTwoCollectionPrimitiveClass.createProperty(PROP_2, PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    compositeTwoCollectionPrimitiveClass.createProperty(PROP_3, PropertyType.INTEGER);

    compositeTwoCollectionPrimitiveClass.createIndex(
        COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_INDEX,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {PROP_1, PROP_2, PROP_3});

    oClass.createIndex(
        "classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop0", "prop1"});

    session.close();
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    session.begin();
    session.execute("delete from classIndexManagerTestClass").close();
    session.commit();

    session.begin();
    session.execute("delete from classIndexManagerTestClassTwo").close();
    session.commit();

    session.begin();
    session.execute("delete from classIndexManagerTestSuperClass").close();
    session.commit();

    if (!session.getStorage().isRemote()) {
      session.begin();
      assertEquals(
          0,
          session
              .getSharedContext()
              .getIndexManager()
              .getIndex("classIndexManagerTestClass.prop1")
              .size(session));
      assertEquals(
          0,
          session
              .getSharedContext()
              .getIndexManager()
              .getIndex("classIndexManagerTestClass.prop2")
              .size(session));
      session.rollback();
    }

    super.afterEach();
  }

  @Test
  @Order(1)
  void testPropertiesCheckUniqueIndexDubKeysCreate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.setProperty("prop1", "a");
    session.commit();

    var exceptionThrown = false;
    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      docTwo = activeTx.load(docTwo);
      docTwo.setProperty("prop1", "a");
      session.commit();

    } catch (RecordDuplicatedException e) {
      exceptionThrown = true;
    }
    assertTrue(exceptionThrown);
  }

  @Test
  @Order(2)
  void testPropertiesCheckUniqueIndexDubKeyIsNullCreate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docTwo = activeTx.load(docTwo);
    docTwo.setProperty("prop1", null);
    session.commit();
  }

  @Test
  @Order(3)
  void testPropertiesCheckUniqueIndexDubKeyIsNullCreateInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.setProperty("prop1", "a");
    docTwo.setProperty("prop1", null);

    session.commit();
  }

  @Test
  @Order(4)
  void testPropertiesCheckUniqueIndexInParentDubKeysCreate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.setProperty("prop0", "a");
    session.commit();

    var exceptionThrown = false;
    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      docTwo = activeTx.load(docTwo);
      docTwo.setProperty("prop0", "a");

      session.commit();
    } catch (RecordDuplicatedException e) {
      exceptionThrown = true;
    }
    assertTrue(exceptionThrown);
  }

  @Test
  @Order(5)
  void testPropertiesCheckUniqueIndexDubKeysUpdate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    var exceptionThrown = false;
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    docTwo = activeTx1.load(docTwo);
    docTwo.setProperty("prop1", "b");

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      docTwo = activeTx.load(docTwo);
      docTwo.setProperty("prop1", "a");

      session.commit();
    } catch (RecordDuplicatedException e) {
      exceptionThrown = true;
    }
    assertTrue(exceptionThrown);
  }

  @Test
  @Order(6)
  void testPropertiesCheckUniqueIndexDubKeyIsNullUpdate() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.setProperty("prop1", "a");

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    docTwo = activeTx1.load(docTwo);
    docTwo.setProperty("prop1", "b");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docTwo = activeTx.load(docTwo);
    docTwo.setProperty("prop1", null);
    session.commit();
  }

  @Test
  @Order(7)
  void testPropertiesCheckUniqueIndexDubKeyIsNullUpdateInTX() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    docOne.setProperty("prop1", "a");
    docTwo.setProperty("prop1", "b");
    docTwo.setProperty("prop1", null);

    session.commit();
  }

  @Test
  @Order(8)
  void testPropertiesCheckNonUniqueIndexDubKeys() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop2", 1);
    session.commit();

    session.begin();
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docTwo.setProperty("prop2", 1);
    session.commit();
  }

  @Test
  @Order(9)
  void testPropertiesCheckUniqueNullKeys() {
    session.begin();
    session.newEntity("classIndexManagerTestClass");
    session.commit();

    session.begin();
    session.newEntity("classIndexManagerTestClass");
    session.commit();
  }

  @Test
  @Order(10)
  void testCreateDocumentWithoutClass() {

    final var beforeIndexes =
        session.getSharedContext().getIndexManager().getIndexes();
    final Map<String, Long> indexSizeMap = new HashMap<>();

    session.begin();
    for (final var index : beforeIndexes) {
      indexSizeMap.put(index.getName(), index.size(session));
    }
    session.rollback();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");

    final var docTwo = ((EntityImpl) session.newEntity());
    docTwo.setProperty("prop1", "a");

    session.commit();

    final var afterIndexes =
        session.getSharedContext().getIndexManager().getIndexes();
    session.begin();
    for (final var index : afterIndexes) {
      assertEquals(
          indexSizeMap.get(index.getName()).longValue(), index.size(session));
    }
    session.rollback();
  }

  @Test
  @Order(11)
  void testUpdateDocumentWithoutClass() {

    final var beforeIndexes =
        session.getSharedContext().getIndexManager().getIndexes();
    final Map<String, Long> indexSizeMap = new HashMap<>();

    session.begin();
    for (final var index : beforeIndexes) {
      indexSizeMap.put(index.getName(), index.size(session));
    }
    session.rollback();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");

    final var docTwo = ((EntityImpl) session.newEntity());
    docTwo.setProperty("prop1", "b");

    docOne.setProperty("prop1", "a");

    session.commit();

    final var afterIndexes =
        session.getSharedContext().getIndexManager().getIndexes();
    session.begin();
    for (final var index : afterIndexes) {
      assertEquals(
          indexSizeMap.get(index.getName()).longValue(), index.size(session));
    }
    session.rollback();
  }

  @Test
  @Order(12)
  void testDeleteDocumentWithoutClass() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(docOne).delete();
    session.commit();
  }

  @Test
  @Order(13)
  void testDeleteModifiedDocumentWithoutClass() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop1", "b");
    docOne.delete();
    session.commit();
  }

  @Test
  @Order(14)
  void testDocumentUpdateWithoutDirtyFields() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop1", "a");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setDirty();

    session.commit();
  }

  @Test
  @Order(15)
  void testCreateDocumentIndexRecordAdded() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop0", "x");
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", 1);

    session.commit();

    session.begin();
    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "a")) {
      assertTrue(stream.findFirst().isPresent());
    }
    assertEquals(1, propOneIndex.size(session));

    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");

    final var compositeIndexDefinition = compositeIndex.getDefinition();
    try (var rids =
        compositeIndex

            .getRids(session,
                compositeIndexDefinition.createValue(session.getActiveTransaction(),
                    "a", 1))) {
      assertTrue(rids.findFirst().isPresent());
    }
    assertEquals(1, compositeIndex.size(session));

    final var propZeroIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestSuperClass.prop0");
    try (var stream = propZeroIndex.getRids(session, "x")) {
      assertTrue(stream.findFirst().isPresent());
    }
    assertEquals(1, propZeroIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(16)
  void testUpdateDocumentIndexRecordRemoved() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop0", "x");
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", 1);

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    schema.getClass("classIndexManagerTestSuperClass");
    schema.getClass("classIndexManagerTestClass");

    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");
    final var propZeroIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestSuperClass.prop0");

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));
    assertEquals(1, propZeroIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.removeProperty("prop2");
    doc.removeProperty("prop0");

    session.commit();

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    assertEquals(0, propZeroIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(17)
  void testUpdateDocumentNullKeyIndexRecordRemoved() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    doc.setProperty("prop0", "x");
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", 1);

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    schema.getClass("classIndexManagerTestSuperClass");
    schema.getClass("classIndexManagerTestClass");

    final var propOneIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");
    final var propZeroIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestSuperClass.prop0");

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));
    assertEquals(1, propZeroIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", null);
    doc.setProperty("prop0", null);

    session.commit();

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    assertEquals(0, propZeroIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(18)
  void testUpdateDocumentIndexRecordUpdated() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop0", "x");
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", 1);

    session.commit();

    final var propZeroIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestSuperClass.prop0");
    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");
    final var compositeIndexDefinition = compositeIndex.getDefinition();

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));
    assertEquals(1, propZeroIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", 2);
    doc.setProperty("prop0", "y");

    session.commit();

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));
    assertEquals(1, propZeroIndex.size(session));

    try (var stream = propZeroIndex.getRids(session, "y")) {
      assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = propOneIndex.getRids(session, "a")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream =
        compositeIndex

            .getRids(session,
                compositeIndexDefinition.createValue(session.getActiveTransaction(),
                    "a", 2))) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(19)
  void testUpdateDocumentIndexRecordUpdatedFromNullField() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", null);

    session.commit();

    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");
    final var compositeIndexDefinition = compositeIndex.getDefinition();

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", 2);

    session.commit();

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));

    try (var stream = propOneIndex.getRids(session, "a")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream =
        compositeIndex
            .getRids(session,
                compositeIndexDefinition.createValue(session.getActiveTransaction(),
                    "a", 2))) {
      assertTrue(stream.findFirst().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(20)
  void testListUpdate() {

    final Schema schema = session.getMetadata().getSchema();
    schema.getClass("classIndexManagerTestClass");

    final var propFourIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop4");

    session.begin();
    assertEquals(0, propFourIndex.size(session));
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop4", session.newEmbeddedList(List.of("value1", "value2")));

    session.commit();

    session.begin();
    assertEquals(2, propFourIndex.size(session));
    try (var stream = propFourIndex.getRids(session, "value1")) {
      assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value2")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    List<String> trackedList = doc.getProperty("prop4");
    trackedList.set(0, "value3");

    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.remove("value4");
    trackedList.remove("value2");
    trackedList.add("value5");

    session.commit();

    session.begin();
    assertEquals(3, propFourIndex.size(session));
    try (var stream = propFourIndex.getRids(session, "value3")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value4")) {
      assertTrue(stream.findAny().isPresent());
    }

    try (var stream = propFourIndex.getRids(session, "value5")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(21)
  void testMapUpdate() {

    final var propFiveIndexKey = session.getSharedContext().getIndexManager()
        .getIndex(
            "classIndexManagerTestIndexByKey");
    final var propFiveIndexValue = session.getSharedContext().getIndexManager()
        .getIndex(
            "classIndexManagerTestIndexByValue");

    session.begin();
    assertEquals(0, propFiveIndexKey.size(session));
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop5",
        session.newEmbeddedMap(Map.of("key1", "value1", "key2", "value2")));

    session.commit();

    session.begin();
    assertEquals(2, propFiveIndexKey.size(session));
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key2")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Map<String, String> trackedMap = doc.getProperty("prop5");
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

    session.begin();
    assertEquals(5, propFiveIndexKey.size(session));
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key3")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key4")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key6")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key7")) {
      assertTrue(stream.findAny().isPresent());
    }

    assertEquals(4, propFiveIndexValue.size(session));
    try (var stream = propFiveIndexValue.getRids(session, "value5")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value3")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value7")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value6")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testSetUpdate() {

    final var propSixIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop6");

    session.begin();
    assertEquals(0, propSixIndex.size(session));
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    doc.setProperty("prop6", session.newEmbeddedSet(Set.of("value1", "value2")));

    session.commit();

    session.begin();
    assertEquals(2, propSixIndex.size(session));
    try (var stream = propSixIndex.getRids(session, "value1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value2")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Set<String> trackedSet = doc.getProperty("prop6");

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

    session.begin();
    assertEquals(2, propSixIndex.size(session));
    try (var stream = propSixIndex.getRids(session, "value1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value5")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(23)
  void testListDelete() {

    final var propFourIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop4");

    session.begin();
    assertEquals(0, propFourIndex.size(session));
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    doc.setProperty("prop4", session.newEmbeddedList(List.of("value1", "value2")));

    session.commit();

    session.begin();
    assertEquals(2, propFourIndex.size(session));
    try (var stream = propFourIndex.getRids(session, "value1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value2")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    List<String> trackedList = doc.getProperty("prop4");
    trackedList.set(0, "value3");

    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.add("value4");
    trackedList.remove("value4");
    trackedList.remove("value2");
    trackedList.add("value5");

    session.commit();

    session.begin();
    assertEquals(3, propFourIndex.size(session));
    try (var stream = propFourIndex.getRids(session, "value3")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value4")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFourIndex.getRids(session, "value5")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    trackedList = doc.getProperty("prop4");
    trackedList.remove("value3");
    trackedList.remove("value4");
    trackedList.add("value8");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, propFourIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(24)
  void testMapDelete() {

    final var propFiveIndexKey = session.getSharedContext().getIndexManager()
        .getIndex(
            "classIndexManagerTestIndexByKey");
    final var propFiveIndexValue = session.getSharedContext().getIndexManager()
        .getIndex(
            "classIndexManagerTestIndexByValue");

    session.begin();
    assertEquals(0, propFiveIndexKey.size(session));
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));

    doc.setProperty("prop5",
        session.newEmbeddedMap(Map.of("key1", "value1", "key2", "value2")));

    session.commit();

    session.begin();
    assertEquals(2, propFiveIndexKey.size(session));
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key2")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    Map<String, String> trackedMap = doc.getProperty("prop5");
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

    session.begin();
    assertEquals(5, propFiveIndexKey.size(session));
    try (var stream = propFiveIndexKey.getRids(session, "key1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key3")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key4")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key6")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexKey.getRids(session, "key7")) {
      assertTrue(stream.findAny().isPresent());
    }

    assertEquals(4, propFiveIndexValue.size(session));
    try (var stream = propFiveIndexValue.getRids(session, "value5")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value3")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value7")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propFiveIndexValue.getRids(session, "value6")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    trackedMap = doc.getProperty("prop5");

    trackedMap.remove("key1");
    trackedMap.remove("key3");
    trackedMap.remove("key4");
    trackedMap.put("key6", "value10");
    trackedMap.put("key11", "value11");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, propFiveIndexKey.size(session));
    assertEquals(0, propFiveIndexValue.size(session));
    session.rollback();
  }

  @Test
  @Order(25)
  void testSetDelete() {
    final var propSixIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop6");

    session.begin();
    assertEquals(0, propSixIndex.size(session));
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop6", session.newEmbeddedSet(Set.of("value1", "value2")));

    session.commit();

    session.begin();
    assertEquals(2, propSixIndex.size(session));
    try (var stream = propSixIndex.getRids(session, "value1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value2")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    Set<String> trackedSet = doc.getProperty("prop6");

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

    session.begin();
    assertEquals(2, propSixIndex.size(session));
    try (var stream = propSixIndex.getRids(session, "value1")) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = propSixIndex.getRids(session, "value5")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    trackedSet = doc.getProperty("prop6");
    trackedSet.remove("value1");
    trackedSet.add("value6");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, propSixIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(26)
  void testDeleteDocumentIndexRecordDeleted() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop0", "x");
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", 1);

    session.commit();

    final var propZeroIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestSuperClass.prop0");
    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");

    session.begin();
    assertEquals(1, propZeroIndex.size(session));
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, propZeroIndex.size(session));
    assertEquals(0, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(27)
  void testDeleteUpdatedDocumentIndexRecordDeleted() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop0", "x");
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", 1);

    session.commit();

    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");

    final var propZeroIndex = session.getSharedContext().getIndexManager().getIndex(
        "classIndexManagerTestSuperClass.prop0");
    session.begin();
    assertEquals(1, propZeroIndex.size(session));
    assertEquals(1, propOneIndex.size(session));
    assertEquals(1, compositeIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", 2);
    doc.setProperty("prop0", "y");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, propZeroIndex.size(session));
    assertEquals(0, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(28)
  void testDeleteUpdatedDocumentNullFieldIndexRecordDeleted() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", null);

    session.commit();

    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(29)
  void testDeleteUpdatedDocumentOrigNullFieldIndexRecordDeleted() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    doc.setProperty("prop1", "a");
    doc.setProperty("prop2", null);

    session.commit();

    final var propOneIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerTestClass.prop1");
    final var compositeIndex = session.getSharedContext().getIndexManager()
        .getIndex("classIndexManagerComposite");

    session.begin();
    assertEquals(1, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", 2);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, propOneIndex.size(session));
    assertEquals(0, compositeIndex.size(session));
    session.rollback();
  }

  @Test
  @Order(30)
  void testNoClassIndexesUpdate() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClassTwo"));
    doc.setProperty("prop1", "a");

    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop1", "b");

    session.commit();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = (SchemaClassInternal) schema.getClass(
        "classIndexManagerTestClass");

    final Collection<Index> indexes = oClass.getIndexesInternal();
    session.begin();
    for (final var index : indexes) {
      assertEquals(0, index.size(session));
    }
    session.rollback();
  }

  @Test
  @Order(31)
  void testNoClassIndexesDelete() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("classIndexManagerTestClassTwo"));
    doc.setProperty("prop1", "a");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(32)
  void testCollectionCompositeCreation() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    session.begin();
    assertEquals(2, index.size(session));

    try (var stream = index
        .getRids(session, new CompositeKey("test1", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(33)
  void testCollectionCompositeNullSimpleFieldCreation() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", null);
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(34)
  void testCollectionCompositeNullCollectionFieldCreation() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", null);

    session.commit();

    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  @Test
  @Order(35)
  void testCollectionCompositeUpdateSimpleField() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty("prop1", "test2");

    session.commit();

    session.begin();
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    assertEquals(2, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(36)
  void testCollectionCompositeUpdateCollectionWasAssigned() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 3)));

    session.commit();

    session.begin();
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    assertEquals(2, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(37)
  void testCollectionCompositeUpdateCollectionWasChanged() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    List<Integer> docList = doc.getProperty("prop2");
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.removeFirst();

    session.commit();

    session.begin();
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 4))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test1", 5))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    assertEquals(4, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(38)
  void testCollectionCompositeUpdateCollectionWasChangedSimpleFieldWasAssigned() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    List<Integer> docList = doc.getProperty("prop2");
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.removeFirst();

    doc.setProperty("prop1", "test2");

    session.commit();

    session.begin();
    assertEquals(4, index.size(session));

    try (var stream = index
        .getRids(session, new CompositeKey("test2", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 4))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("test2", 5))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(39)
  void testCollectionCompositeUpdateSimpleFieldNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty("prop1", null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(40)
  void testCollectionCompositeUpdateCollectionWasAssignedNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty("prop2", null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(41)
  void testCollectionCompositeUpdateBothAssignedNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    doc.setProperty("prop2", null);
    doc.setProperty("prop1", null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(42)
  void testCollectionCompositeUpdateCollectionWasChangedSimpleFieldWasAssignedNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    List<Integer> docList = doc.getProperty("prop2");
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.removeFirst();

    doc.setProperty("prop1", null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(43)
  void testCollectionCompositeDeleteSimpleFieldAssigend() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop1", "test2");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(44)
  void testCollectionCompositeDeleteCollectionFieldAssigend() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 3)));

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(45)
  void testCollectionCompositeDeleteCollectionFieldChanged() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    List<Integer> docList = doc.getProperty("prop2");
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(46)
  void testCollectionCompositeDeleteBothCollectionSimpleFieldChanged() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    List<Integer> docList = doc.getProperty("prop2");
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.setProperty("prop1", "test2");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(47)
  void testCollectionCompositeDeleteBothCollectionSimpleFieldAssigend() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 3)));
    doc.setProperty("prop1", "test2");

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(48)
  void testCollectionCompositeDeleteSimpleFieldNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    doc.setProperty("prop1", null);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(49)
  void testCollectionCompositeDeleteCollectionFieldNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("prop2", null);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(50)
  void testCollectionCompositeDeleteBothSimpleCollectionFieldNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    doc.setProperty("prop2", null);
    doc.setProperty("prop1", null);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(51)
  void testCollectionCompositeDeleteCollectionFieldChangedSimpleFieldNull() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(
        "classIndexManagerTestCompositeCollectionClass"));

    doc.setProperty("prop1", "test1");
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("classIndexManagerTestIndexValueAndCollection");
    assertEquals(2, index.size(session));

    List<Integer> docList = doc.getProperty("prop2");
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.setProperty("prop1", null);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(52)
  void testTwoCollectionsCompositeCreation() {

    session.begin();
    final var doc = ((EntityImpl) session.newEntity(
        COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty("prop1", session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    session.begin();
    assertEquals(4, index.size(session));

    try (var stream = index
        .getRids(session, new CompositeKey("val1", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(53)
  void testCollectionCompositeUpdateFirstField() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty("prop1", session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty("prop2", session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.getEmbeddedList("prop1").add("val3");

    session.commit();

    session.begin();
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    try (var stream = index
        .getRids(session, new CompositeKey("val2", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val3", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val3", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    assertEquals(6, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(54)
  void testCollectionCompositeUpdateTwoCollectionsSecondWasAssigned() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 3)));

    session.commit();

    session.begin();
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    try (var stream = index
        .getRids(session, new CompositeKey("val2", 1))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    assertEquals(4, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(55)
  void testTwoCollectionCompositeUpdateCollectionWasChanged() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    List<Integer> docList = doc.getEmbeddedList(PROP_2);
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.removeFirst();

    session.commit();

    session.begin();
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 4))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val1", 5))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    try (var stream = index
        .getRids(session, new CompositeKey("val2", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 4))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val2", 5))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    assertEquals(8, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(56)
  void testCollectionCompositeUpdateCollectionWasChangedFirstPropertyWasAssigned() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    List<Integer> docList = doc.getEmbeddedList(PROP_2);
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.removeFirst();

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val3", "val4")));

    session.commit();

    session.begin();
    assertEquals(8, index.size(session));

    try (var stream = index
        .getRids(session, new CompositeKey("val3", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val3", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val3", 4))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val3", 5))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }

    try (var stream = index
        .getRids(session, new CompositeKey("val4", 2))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val4", 3))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val4", 4))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = index
        .getRids(session, new CompositeKey("val4", 5))) {
      assertEquals(doc.getIdentity(), stream.findAny().orElse(null));
    }
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(57)
  void testTwoCollectionsCompositeUpdateFirstPropertyNull() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty(PROP_1, null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(58)
  void testCollectionCompositeUpdateTwoCollectionWasAssignedNull() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    doc.setProperty(PROP_2, null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(59)
  void testTwoCollectionsCompositeUpdateBothAssignedNull() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    doc.setProperty(PROP_2, null);
    doc.setProperty(PROP_1, null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(60)
  void testTwoCollectionCompositeUpdateCollectionWasChangedFirstPropertyWasAssignedNull() {
    session.begin();
    var entity = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    entity.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx1 = session.getActiveTransaction();
    entity = activeTx1.load(entity);
    List<Integer> docList = entity.getProperty(PROP_2);
    docList.add(3);
    docList.add(4);
    docList.add(5);

    docList.removeFirst();

    entity.setProperty(PROP_1, null);

    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(entity).delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(61)
  void testTwoCollectionCompositeDeleteFirstPropertyAssigned() {
    session.begin();
    var entity = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    entity.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));
    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val3", "val4")));

    entity.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(62)
  void testTwoCollectionCompositeDeleteSecondPropertyAssigned() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 3)));

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(63)
  void testTwoCollectionsCompositeDeleteSecondPropertyChanged() {
    session.begin();
    var doc = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    List<Integer> docList = doc.getProperty(PROP_2);
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(64)
  void testTwoCollectionsCompositeDeleteBothCollectionFieldChanged() {
    session.begin();
    var doc = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    List<Integer> docList = doc.getProperty(PROP_2);
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    doc.getEmbeddedList(PROP_1).remove(1);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(65)
  void testTwoCollectionCompositeDeleteBothCollectionFirstFieldAssigend() {
    session.begin();
    var entity = ((EntityImpl) session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS));

    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    entity.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    entity.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 3)));
    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val3", "val4")));

    entity.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(66)
  void testTwoCollectionsCompositeDeleteFirstFieldNull() {
    session.begin();
    var entity = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    entity.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    entity.setProperty(PROP_1, null);

    entity.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(67)
  void testTwoCollectionsCompositeDeleteSecondFieldNull() {
    session.begin();
    var doc = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty(PROP_2, null);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(68)
  void testTwoCollectionsCompositeDeleteBothSimpleCollectionFieldNull() {
    session.begin();
    var doc = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    doc.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    doc.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    doc.setProperty(PROP_2, null);
    doc.setProperty(PROP_1, null);

    doc.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(69)
  void testTwoCollectionsCompositeDeleteSecondCollectionFieldChangedFirstFieldNull() {
    session.begin();
    var entity = session.newEntity(COMPOSITE_TWO_COLLECTIONS_CLASS);

    entity.setProperty(PROP_1, session.newEmbeddedList(List.of("val1", "val2")));
    entity.setProperty(PROP_2, session.newEmbeddedList(List.of(1, 2)));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex(COMPOSITE_TWO_COLLECTIONS_INDEX);
    assertEquals(4, index.size(session));

    List<Integer> docList = entity.getProperty(PROP_2);
    docList.add(3);
    docList.add(4);

    docList.remove(1);

    entity.setProperty(PROP_1, null);

    entity.delete();
    session.commit();

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(70)
  void testThreePropertiesTwoCollectionRandomUpdate() {
    var seed = System.nanoTime();
    System.out.printf("testThreePropertiesTwoCollectionRandomUpdate seed %d%n", seed);

    var random = new Random(seed);

    var rid = session.computeInTx(transaction -> {
      var prop1 = IntStream.generate(random::nextInt).limit(random.nextInt(10))
          .boxed().toList();
      var prop2 = IntStream.generate(random::nextInt).limit(random.nextInt(10))
          .boxed().toList();
      var prop3Value = random.nextInt(10);

      var entity = session.newEntity(COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_CLASS);

      entity.newEmbeddedList(PROP_1, prop1);
      entity.newEmbeddedList(PROP_2, prop2);
      entity.setInt(PROP_3, prop3Value);

      return entity.getIdentity();
    });

    validateCompositeIndex(rid);

    for (var i = 0; i < 100; i++) {
      session.executeInTx(transaction -> {
        var entity = session.loadEntity(rid);

        modifyEmbeddedList(random, entity, PROP_1);
        modifyEmbeddedList(random, entity, PROP_2);

        if (random.nextBoolean()) {
          entity.setInt(PROP_3, random.nextInt());
        }

        if (random.nextBoolean()) {
          entity.removeProperty(PROP_1);
        }
        if (random.nextBoolean()) {
          entity.removeProperty(PROP_2);
        }
        if (random.nextBoolean()) {
          entity.removeProperty(PROP_3);
        }
      });

      validateCompositeIndex(rid);
    }

    session.executeInTx(transaction -> {
      var entity = session.loadEntity(rid);
      entity.delete();
    });

    session.executeInTx(transaction -> {
      var index = session.getSharedContext().getIndexManager().getIndex(
          COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_INDEX);
      assertEquals(0, index.size(session));
    });
  }

  @Test
  @Order(71)
  void testIndexOnPropertiesFromClassAndSuperclass() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop0", "doc1-prop0");
    docOne.setProperty("prop1", "doc1-prop1");

    session.commit();

    session.begin();
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docTwo.setProperty("prop0", "doc2-prop0");
    docTwo.setProperty("prop1", "doc2-prop1");

    session.commit();

    final var index =
        session.getSharedContext().getIndexManager()
            .getIndex(
                "classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();
  }

  private void validateCompositeIndex(RID rid) {
    session.executeInTxInternal(transaction -> {
      var index = session.getSharedContext().getIndexManager().getIndex(
          COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_INDEX);

      var atomicOperation = transaction.getAtomicOperation();
      var entity = session.loadEntity(rid);
      var expectedKeys = createCompositeKeysFromEntity(entity);
      var actualKeys = index.keyStream(atomicOperation);

      actualKeys.forEach(key -> assertTrue(expectedKeys.remove((CompositeKey) key)));
      assertTrue(expectedKeys.isEmpty());
    });
  }

  private static void modifyEmbeddedList(Random random, Entity entity,
      String propName) {
    if (random.nextBoolean()) {
      var embeddedList = entity.getEmbeddedList(propName);

      if (embeddedList == null) {
        embeddedList = entity.newEmbeddedList(propName);
      }

      var removeCount = 0;
      if (!embeddedList.isEmpty()) {
        removeCount = random.nextInt(embeddedList.size());
      }

      var addCount = random.nextInt(10);
      for (var j = 0; j < removeCount; j++) {
        embeddedList.remove(random.nextInt(embeddedList.size()));
      }

      for (var j = 0; j < addCount; j++) {
        embeddedList.add(random.nextInt());
      }
    }
  }

  private static List<CompositeKey> createCompositeKeysFromEntity(Entity entity) {
    var firstList = entity.getEmbeddedList(PROP_1);
    if (firstList == null) {
      return new ArrayList<>();
    }

    var secondList = entity.getEmbeddedList(PROP_2);
    if (secondList == null) {
      return new ArrayList<>();
    }

    var intVal = entity.getInt(PROP_3);
    if (intVal == null) {
      return new ArrayList<>();
    }

    var stream = firstList.stream()
        .flatMap(first -> secondList.stream().map(second -> new CompositeKey(first, second)))
        .peek(compositeKey -> compositeKey.addKey(intVal));
    return stream.collect(Collectors.toCollection(ArrayList::new));
  }
}
