package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link PropertyMapIndexDefinition} for key extraction and change processing on map properties.
 *
 * @since 20.12.11
 */
public class SchemaPropertyMapIndexDefinitionTest extends DbTestBase {

  private Map<String, Integer> mapToTest;
  private PropertyMapIndexDefinition propertyIndexByKey;
  private PropertyMapIndexDefinition propertyIndexByValue;
  private PropertyMapIndexDefinition propertyIndexByIntegerKey;

  @Before
  public void beforeMethod() {
    session.begin();
    mapToTest = session.newEmbeddedMap();

    mapToTest.put("st1", 1);
    mapToTest.put("st2", 2);

    propertyIndexByKey =
        new PropertyMapIndexDefinition(
            "testClass", "fOne", PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY);
    propertyIndexByIntegerKey =
        new PropertyMapIndexDefinition(
            "testClass", "fTwo", PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.KEY);
    propertyIndexByValue =
        new PropertyMapIndexDefinition(
            "testClass", "fOne", PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.VALUE);
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  @Test
  public void testCreateValueByKeySingleParameter() {
    final var result = propertyIndexByKey.createValue(session.getActiveTransaction(),
        Collections.singletonList(mapToTest));
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains("st1"));
    Assert.assertTrue(collectionResult.contains("st2"));
  }

  @Test
  public void testCreateValueByValueSingleParameter() {
    final var result =
        propertyIndexByValue.createValue(session.getActiveTransaction(),
            Collections.singletonList(mapToTest));
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(1));
    Assert.assertTrue(collectionResult.contains(2));
  }

  @Test
  public void testCreateValueByKeyTwoParameters() {
    final var result = propertyIndexByKey.createValue(session.getActiveTransaction(),
        Arrays.asList(mapToTest, "25"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains("st1"));
    Assert.assertTrue(collectionResult.contains("st2"));
  }

  @Test
  public void testCreateValueByValueTwoParameters() {
    final var result = propertyIndexByValue.createValue(session.getActiveTransaction(),
        Arrays.asList(mapToTest, "25"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(1));
    Assert.assertTrue(collectionResult.contains(2));
  }

  @Test
  public void testCreateValueWrongParameter() {
    final var result = propertyIndexByKey.createValue(session.getActiveTransaction(),
        Collections.singletonList("tt"));
    Assert.assertNull(result);
  }

  @Test
  public void testCreateValueByKeySingleParameterArrayParams() {
    final var result = propertyIndexByKey.createValue(session.getActiveTransaction(),
        mapToTest);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains("st1"));
    Assert.assertTrue(collectionResult.contains("st2"));
  }

  @Test
  public void testCreateValueByValueSingleParameterArrayParams() {
    final var result = propertyIndexByValue.createValue(session.getActiveTransaction(),
        mapToTest);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(1));
    Assert.assertTrue(collectionResult.contains(2));
  }

  @Test
  public void testCreateValueByKeyTwoParametersArrayParams() {
    final var result = propertyIndexByKey.createValue(session.getActiveTransaction(),
        mapToTest, "25");

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains("st1"));
    Assert.assertTrue(collectionResult.contains("st2"));
  }

  @Test
  public void testCreateValueByValueTwoParametersArrayParams() {
    final var result = propertyIndexByValue.createValue(session.getActiveTransaction(),
        mapToTest, "25");

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(1));
    Assert.assertTrue(collectionResult.contains(2));
  }

  @Test
  public void testCreateValueWrongParameterArrayParams() {
    final var result = propertyIndexByKey.createValue(session.getActiveTransaction(),
        "tt");
    Assert.assertNull(result);
  }

  @Test
  public void testGetDocumentValueByKeyToIndex() {
    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", mapToTest);
    document.setProperty("fTwo", 10);

    final var result = propertyIndexByKey.getDocumentValueToIndex(session.getActiveTransaction(),
        document);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains("st1"));
    Assert.assertTrue(collectionResult.contains("st2"));
    session.rollback();
  }

  @Test
  public void testGetDocumentValueByValueToIndex() {
    session.begin();
    final var entity = (EntityImpl) session.newEntity();

    entity.setProperty("fOne", mapToTest);
    entity.setProperty("fTwo", 10);

    final var result = propertyIndexByValue.getDocumentValueToIndex(session.getActiveTransaction(),
        entity);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;

    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(1));
    Assert.assertTrue(collectionResult.contains(2));
    session.rollback();
  }

  @Test
  public void testGetProperties() {
    final var result = propertyIndexByKey.getProperties();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("fOne", result.getFirst());
  }

  @Test
  public void testGetTypes() {
    final var result = propertyIndexByKey.getTypes();
    Assert.assertEquals(1, result.length);
    Assert.assertEquals(PropertyTypeInternal.STRING, result[0]);
  }

  @Test
  public void testEmptyIndexByKeyReload() {
    propertyIndexByKey =
        new PropertyMapIndexDefinition(
            "tesClass", "fOne", PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY);

    final var map = propertyIndexByKey.toMap(session);
    final PropertyIndexDefinition result = new PropertyMapIndexDefinition();
    result.fromMap(map);
    Assert.assertEquals(result, propertyIndexByKey);
  }

  @Test
  public void testEmptyIndexByValueReload() {
    propertyIndexByValue =
        new PropertyMapIndexDefinition(
            "tesClass", "fOne", PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.VALUE);

    final var map = propertyIndexByValue.toMap(session);
    final PropertyIndexDefinition result = new PropertyMapIndexDefinition();
    result.fromMap(map);

    Assert.assertEquals(result, propertyIndexByValue);
  }

  @Test
  public void testCreateSingleValueByKey() {
    final var result = propertyIndexByKey.createSingleValue(session.getActiveTransaction(), "tt");
    Assert.assertEquals("tt", result);
  }

  @Test
  public void testCreateSingleValueByValue() {
    final var result = propertyIndexByValue.createSingleValue(session.getActiveTransaction(), "12");
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateWrongSingleValueByValue() {
    propertyIndexByValue.createSingleValue(session.getActiveTransaction(), "tt");
  }

  @Test(expected = NullPointerException.class)
  public void testIndexByIsRequired() {
    new PropertyMapIndexDefinition("testClass", "testField", PropertyTypeInternal.STRING, null);
  }

  @Test
  public void testCreateDDLByKey() {
    final var ddl =
        propertyIndexByKey
            .toCreateIndexDDL("testIndex", "unique", null)
            .toLowerCase(Locale.ENGLISH);
    Assert.assertEquals("create index `testindex` on `testclass` ( `fone` by key ) unique", ddl);
  }

  @Test
  public void testCreateDDLByValue() {
    final var ddl =
        propertyIndexByValue
            .toCreateIndexDDL("testIndex", "unique", null)
            .toLowerCase(Locale.ENGLISH);
    Assert.assertEquals("create index `testindex` on `testclass` ( `fone` by value ) unique", ddl);
  }

  @Test
  public void testProcessChangeEventAddKey() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.ADD, "key1", "value1");

    propertyIndexByKey.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put("key1", 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddKeyWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.ADD, "12", "value1");

    propertyIndexByIntegerKey.processChangeEvent(
        session.getActiveTransaction(), multiValueChangeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(12, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddValue() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, Integer>(
            ChangeType.ADD, "key1", 42);

    propertyIndexByValue.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd,
        keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddValueWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.ADD, "12", "42");

    propertyIndexByValue.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd,
        keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveKey() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.REMOVE, "key1", "value1");

    propertyIndexByKey.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put("key1", 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveKeyWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.REMOVE, "12", "value1");

    propertyIndexByIntegerKey.processChangeEvent(
        session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(12, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveValue() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, Integer>(
            ChangeType.REMOVE, "key1", null, 42);

    propertyIndexByValue.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd,
        keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveValueWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.REMOVE, "12", null, "42");

    propertyIndexByValue.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd,
        keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventUpdateKey() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, Integer>(
            ChangeType.UPDATE, "key1", 42);

    propertyIndexByKey.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd, keysToRemove);
    Assert.assertTrue(keysToAdd.isEmpty());
    Assert.assertTrue(keysToRemove.isEmpty());
  }

  @Test
  public void testProcessChangeEventUpdateValue() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, Integer>(
            ChangeType.UPDATE, "key1", 41, 42);

    propertyIndexByValue.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd,
        keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(41, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventUpdateValueWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<String, String>(
            ChangeType.UPDATE, "12", "42", "41");

    propertyIndexByValue.processChangeEvent(session.getActiveTransaction(),
        multiValueChangeEvent, keysToAdd,
        keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(41, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }
}
