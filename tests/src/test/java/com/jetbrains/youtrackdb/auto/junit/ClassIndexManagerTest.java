/*
 * JUnit 4 version of ClassIndexManagerTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/ClassIndexManagerTest.java
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClassIndexManagerTest extends BaseDBTest {

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

  @BeforeClass
  public static void setUpClass() throws Exception {
    ClassIndexManagerTest instance = new ClassIndexManagerTest();
    instance.beforeClass();
    instance.createTestSchema();
  }

  private void createTestSchema() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.existsClass("classIndexManagerTestClass")) {
      return;
    }

    final var superClass = schema.createClass("classIndexManagerTestSuperClass");
    superClass.createProperty("prop0", PropertyType.STRING);
    superClass.createIndex("classIndexManagerTestSuperClass.prop0",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true), new String[]{"prop0"});

    final var oClass = schema.createClass("classIndexManagerTestClass", superClass);
    oClass.createProperty("prop1", PropertyType.STRING);
    oClass.createIndex("classIndexManagerTestClass.prop1",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true), new String[]{"prop1"});

    oClass.createProperty("prop2", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    oClass.createProperty("prop3", PropertyType.BOOLEAN);
    oClass.createProperty("prop4", PropertyType.EMBEDDEDLIST, PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    oClass.createProperty("prop5", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    oClass.createIndex("classIndexManagerTestIndexByKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop5");
    oClass.createIndex("classIndexManagerTestIndexByValue", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop5 by value");
    oClass.createProperty("prop6", PropertyType.EMBEDDEDSET, PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    oClass.createIndex("classIndexManagerComposite", SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null, Map.of("ignoreNullValues", true), new String[]{"prop1", "prop2"});

    final var oClassTwo = schema.createClass("classIndexManagerTestClassTwo");
    oClassTwo.createProperty("prop1", PropertyType.STRING);
    oClassTwo.createProperty("prop2", PropertyType.INTEGER);

    final var compositeCollectionClass = schema.createClass(
        "classIndexManagerTestCompositeCollectionClass");
    compositeCollectionClass.createProperty("prop1", PropertyType.STRING);
    compositeCollectionClass.createProperty("prop2", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    compositeCollectionClass.createIndex("classIndexManagerTestIndexValueAndCollection",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true), new String[]{"prop1", "prop2"});

    final var compositeTwoCollectionClass = schema.createClass(COMPOSITE_TWO_COLLECTIONS_CLASS);
    compositeTwoCollectionClass.createProperty("prop1", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    compositeTwoCollectionClass.createProperty("prop2", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    compositeTwoCollectionClass.createIndex(COMPOSITE_TWO_COLLECTIONS_INDEX,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true), new String[]{PROP_1, PROP_2});

    final var compositeTwoCollectionPrimitiveClass = schema.createClass(
        COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_CLASS);
    compositeTwoCollectionPrimitiveClass.createProperty(PROP_1, PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    compositeTwoCollectionPrimitiveClass.createProperty(PROP_2, PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    compositeTwoCollectionPrimitiveClass.createProperty(PROP_3, PropertyType.INTEGER);
    compositeTwoCollectionPrimitiveClass.createIndex(COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_INDEX,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true), new String[]{PROP_1, PROP_2, PROP_3});

    oClass.createIndex("classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true), new String[]{"prop0", "prop1"});

    session.close();
  }

  @After
  @Override
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from classIndexManagerTestClass").close();
    session.commit();
    session.begin();
    session.execute("delete from classIndexManagerTestClassTwo").close();
    session.commit();
    session.begin();
    session.execute("delete from classIndexManagerTestSuperClass").close();
    session.commit();
    session.begin();
    session.execute("delete from classIndexManagerTestCompositeCollectionClass").close();
    session.commit();
    session.begin();
    session.execute("delete from " + COMPOSITE_TWO_COLLECTIONS_CLASS).close();
    session.commit();
    session.begin();
    session.execute("delete from " + COMPOSITE_TWO_COLLECTIONS_PLUS_PRIMITIVE_CLASS).close();
    session.commit();
    super.afterMethod();
  }

  /**
   * Original: testPropertiesCheckUniqueIndexDubKeysCreate (line 203)
   */
  @Test
  public void test01_PropertiesCheckUniqueIndexDubKeysCreate() {
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
    Assert.assertTrue(exceptionThrown);
  }

  /**
   * Original: testPropertiesCheckUniqueIndexDubKeyIsNullCreate (line 225)
   */
  @Test
  public void test02_PropertiesCheckUniqueIndexDubKeyIsNullCreate() {
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

  /**
   * Original: testPropertiesCheckUniqueIndexDubKeyIsNullCreateInTx (line 239)
   */
  @Test
  public void test03_PropertiesCheckUniqueIndexDubKeyIsNullCreateInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop1", "a");
    docTwo.setProperty("prop1", null);
    session.commit();
  }

  /**
   * Original: testPropertiesCheckUniqueIndexInParentDubKeysCreate (line 250)
   */
  @Test
  public void test04_PropertiesCheckUniqueIndexInParentDubKeysCreate() {
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
    Assert.assertTrue(exceptionThrown);
  }

  /**
   * Original: testPropertiesCheckUniqueIndexDubKeysUpdate (line 272)
   */
  @Test
  public void test05_PropertiesCheckUniqueIndexDubKeysUpdate() {
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
    Assert.assertTrue(exceptionThrown);
  }

  /**
   * Original: testPropertiesCheckUniqueIndexDubKeyIsNullUpdate (line 301)
   */
  @Test
  public void test06_PropertiesCheckUniqueIndexDubKeyIsNullUpdate() {
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

  /**
   * Original: testPropertiesCheckUniqueIndexDubKeyIsNullUpdateInTX (line 323)
   */
  @Test
  public void test07_PropertiesCheckUniqueIndexDubKeyIsNullUpdateInTX() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop1", "a");
    docTwo.setProperty("prop1", "b");
    docTwo.setProperty("prop1", null);
    session.commit();
  }

  /**
   * Original: testPropertiesCheckNonUniqueIndexDubKeys (line 335)
   */
  @Test
  public void test08_PropertiesCheckNonUniqueIndexDubKeys() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop2", 1);
    docTwo.setProperty("prop2", 1);
    session.commit();
  }

  /**
   * Original: testPropertiesCheckUniqueNullKeys (line 347)
   */
  @Test
  public void test09_PropertiesCheckUniqueNullKeys() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    final var docTwo = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    session.commit();
  }

  /**
   * Original: testCreateDocumentWithoutClass (line 357)
   */
  @Test
  public void test10_CreateDocumentWithoutClass() {
    checkEmbeddedDB();
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");
    session.commit();

    final var index = getIndex("classIndexManagerTestClass.prop1");
    Assert.assertEquals(0, index.size(session));
  }

  /**
   * Original: testUpdateDocumentWithoutClass (line 384)
   */
  @Test
  public void test11_UpdateDocumentWithoutClass() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop1", "b");
    session.commit();

    final var index = getIndex("classIndexManagerTestClass.prop1");
    Assert.assertEquals(0, index.size(session));
  }

  /**
   * Original: testDeleteDocumentWithoutClass (line 413)
   */
  @Test
  public void test12_DeleteDocumentWithoutClass() {
    checkEmbeddedDB();
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    session.delete(session.load(docOne.getIdentity()));
    session.commit();
  }

  /**
   * Original: testDeleteModifiedDocumentWithoutClass (line 426)
   */
  @Test
  public void test13_DeleteModifiedDocumentWithoutClass() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity());
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop1", "b");
    session.delete(docOne);
    session.commit();
  }

  /**
   * Original: testDocumentUpdateWithoutDirtyFields (line 441)
   */
  @Test
  public void test14_DocumentUpdateWithoutDirtyFields() {
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

  /**
   * Original: testCreateDocumentIndexRecordAdded (line 456)
   */
  @Test
  public void test15_CreateDocumentIndexRecordAdded() {
    checkEmbeddedDB();
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop0", "doc1_prop0");
    docOne.setProperty("prop1", "doc1_prop1");
    docOne.setProperty("prop2", 1);
    session.commit();

    final var propOneIndex = getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "doc1_prop1")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var propTwoIndex = getIndex("classIndexManagerTestClass.prop2");
    try (var stream = propTwoIndex.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var compositeIndex = getIndex("classIndexManagerComposite");
    try (var stream = compositeIndex.getRids(session, new CompositeKey("doc1_prop1", 1))) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var parentPropertyIndex = getIndex("classIndexManagerTestSuperClass.prop0");
    try (var stream = parentPropertyIndex.getRids(session, "doc1_prop0")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var compositeIndexTwoProperties = getIndex(
        "classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass");
    try (var stream = compositeIndexTwoProperties.getRids(session,
        new CompositeKey("doc1_prop0", "doc1_prop1"))) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testUpdateDocumentIndexRecordRemoved (line 496)
   */
  @Test
  public void test16_UpdateDocumentIndexRecordRemoved() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop1", "doc1_prop1");
    docOne.setProperty("prop2", 1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.removeProperty("prop1");
    session.commit();

    final var propOneIndex = getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "doc1_prop1")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var compositeIndex = getIndex("classIndexManagerComposite");
    try (var stream = compositeIndex.getRids(session, new CompositeKey("doc1_prop1", 1))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testUpdateDocumentNullKeyIndexRecordRemoved (line 534)
   */
  @Test
  public void test17_UpdateDocumentNullKeyIndexRecordRemoved() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop1", "doc1_prop1");
    docOne.setProperty("prop2", 1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop1", null);
    session.commit();

    final var propOneIndex = getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "doc1_prop1")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var compositeIndex = getIndex("classIndexManagerComposite");
    try (var stream = compositeIndex.getRids(session, new CompositeKey("doc1_prop1", 1))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testUpdateDocumentIndexRecordUpdated (line 573)
   */
  @Test
  public void test18_UpdateDocumentIndexRecordUpdated() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop0", "doc1_prop0");
    docOne.setProperty("prop1", "doc1_prop1");
    docOne.setProperty("prop2", 1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop0", "doc1_prop0_new");
    docOne.setProperty("prop1", "doc1_prop1_new");
    docOne.setProperty("prop2", 2);
    session.commit();

    final var propZeroIndex = getIndex("classIndexManagerTestSuperClass.prop0");
    try (var stream = propZeroIndex.getRids(session, "doc1_prop0")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = propZeroIndex.getRids(session, "doc1_prop0_new")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var propOneIndex = getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "doc1_prop1")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = propOneIndex.getRids(session, "doc1_prop1_new")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var propTwoIndex = getIndex("classIndexManagerTestClass.prop2");
    try (var stream = propTwoIndex.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = propTwoIndex.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var compositeIndex = getIndex("classIndexManagerComposite");
    try (var stream = compositeIndex.getRids(session, new CompositeKey("doc1_prop1", 1))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = compositeIndex.getRids(session, new CompositeKey("doc1_prop1_new", 2))) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testUpdateDocumentIndexRecordUpdatedFromNullField (line 624)
   */
  @Test
  public void test19_UpdateDocumentIndexRecordUpdatedFromNullField() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop2", 1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop1", "doc1_prop1_new");
    session.commit();

    final var propOneIndex = getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "doc1_prop1_new")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testDeleteDocumentIndexRecordDeleted (line 1053)
   */
  @Test
  public void test20_DeleteDocumentIndexRecordDeleted() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop0", "doc1_prop0");
    docOne.setProperty("prop1", "doc1_prop1");
    docOne.setProperty("prop2", 1);
    session.commit();

    session.begin();
    session.delete(session.load(docOne.getIdentity()));
    session.commit();

    final var propZeroIndex = getIndex("classIndexManagerTestSuperClass.prop0");
    try (var stream = propZeroIndex.getRids(session, "doc1_prop0")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var propOneIndex = getIndex("classIndexManagerTestClass.prop1");
    try (var stream = propOneIndex.getRids(session, "doc1_prop1")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testNoClassIndexesUpdate (line 1174)
   */
  @Test
  public void test21_NoClassIndexesUpdate() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClassTwo"));
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docOne = activeTx.load(docOne);
    docOne.setProperty("prop1", "b");
    session.commit();

    final var index = getIndex("classIndexManagerTestClass.prop1");
    Assert.assertEquals(0, index.size(session));
  }

  /**
   * Original: testNoClassIndexesDelete (line 1198)
   */
  @Test
  public void test22_NoClassIndexesDelete() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClassTwo"));
    docOne.setProperty("prop1", "a");
    session.commit();

    session.begin();
    session.delete(session.load(docOne.getIdentity()));
    session.commit();
  }

  /**
   * Original: testIndexOnPropertiesFromClassAndSuperclass (line 2709)
   */
  @Test
  public void test23_IndexOnPropertiesFromClassAndSuperclass() {
    checkEmbeddedDB();
    session.begin();
    var docOne = ((EntityImpl) session.newEntity("classIndexManagerTestClass"));
    docOne.setProperty("prop0", "doc1_prop0");
    docOne.setProperty("prop1", "doc1_prop1");
    session.commit();

    final var compositeIndex = getIndex(
        "classIndexManagerTestIndexOnPropertiesFromClassAndSuperclass");
    try (var stream = compositeIndex.getRids(session,
        new CompositeKey("doc1_prop0", "doc1_prop1"))) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  private void checkEmbeddedDB() {
    if (session.getStorage().isRemote()) {
      return;
    }
  }
}
