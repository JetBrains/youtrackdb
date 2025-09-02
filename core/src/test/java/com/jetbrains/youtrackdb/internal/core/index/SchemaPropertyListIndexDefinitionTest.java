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
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SchemaPropertyListIndexDefinitionTest extends DbTestBase {

  private PropertyListIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    session.begin();
    propertyIndex = new PropertyListIndexDefinition("testClass", "fOne",
        PropertyTypeInternal.INTEGER);
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  @Test
  public void testCreateValueSingleParameter() {
    final var result =
        propertyIndex.createValue(session.getActiveTransaction(),
            Collections.singletonList(Arrays.asList("12", "23")));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(collectionResult.size(), 2);

    Assert.assertTrue(collectionResult.contains(12));
    Assert.assertTrue(collectionResult.contains(23));
  }

  @Test
  public void testCreateValueTwoParameters() {
    final var result =
        propertyIndex.createValue(session.getActiveTransaction(),
            Arrays.asList(Arrays.asList("12", "23"), "25"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(collectionResult.size(), 2);

    Assert.assertTrue(collectionResult.contains(12));
    Assert.assertTrue(collectionResult.contains(23));
  }

  @Test
  public void testCreateValueWrongParameter() {
    try {
      propertyIndex.createValue(session.getActiveTransaction(), Collections.singletonList("tt"));
      Assert.fail();
    } catch (IndexException x) {

    }
  }

  @Test
  public void testCreateValueSingleParameterArrayParams() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        (Object) Arrays.asList("12", "23"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(collectionResult.size(), 2);

    Assert.assertTrue(collectionResult.contains(12));
    Assert.assertTrue(collectionResult.contains(23));
  }

  @Test
  public void testCreateValueTwoParametersArrayParams() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Arrays.asList("12", "23"), "25");

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(collectionResult.size(), 2);

    Assert.assertTrue(collectionResult.contains(12));
    Assert.assertTrue(collectionResult.contains(23));
  }

  @Test(expected = IndexException.class)
  public void testCreateValueWrongParameterArrayParams() {
    Assert.assertNull(propertyIndex.createValue(session.getActiveTransaction(), "tt"));
  }

  @Test
  public void testGetDocumentValueToIndex() {
    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.newEmbeddedList("fOne").addAll(Arrays.asList("12", "23"));
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.getDocumentValueToIndex(session.getActiveTransaction(),
        document);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(collectionResult.size(), 2);

    Assert.assertTrue(collectionResult.contains(12));
    Assert.assertTrue(collectionResult.contains(23));
    session.rollback();
  }

  @Test
  public void testCreateSingleValue() {
    final var result = propertyIndex.createSingleValue(session.getActiveTransaction(), "12");
    Assert.assertEquals(result, 12);
  }

  @Test(expected = IndexException.class)
  public void testCreateSingleValueWrongParameter() {
    propertyIndex.createSingleValue(session.getActiveTransaction(), "tt");
  }

  @Test
  public void testProcessChangeEventAddOnce() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddOnceWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Integer, String>(
            ChangeType.ADD, 0, "42");
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddTwoTimes() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 1, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 2);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddTwoValues() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 1, 43);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);
    addedKeys.put(43, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveOnce() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveOnceWithConversion() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Integer, String>(
            ChangeType.REMOVE, 0, null, "42");

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveTwoTimes() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 1, null, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 2);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddTwoTimesInvValue() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.ADD, 1, 555);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);
    addedKeys.put(555, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddRemove() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddRemoveInvValue() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 55);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(55, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddTwiceRemoveOnce() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 1, 42);
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventThree,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(42, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventAddOnceRemoveTwice() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 0, 42);
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventThree,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventRemoveTwoTimesAddOnce() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEventOne =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 0, null, 42);
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.REMOVE, 1, null, 42);
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Integer, Integer>(ChangeType.ADD, 1, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventThree,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventUpdate() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Integer, Integer>(
            ChangeType.UPDATE, 0, 41, 42);

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(41, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  @Test
  public void testProcessChangeEventUpdateConvertValues() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Integer, String>(
            ChangeType.UPDATE, 0, "41", "42");

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(41, 1);

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(42, 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }
}
