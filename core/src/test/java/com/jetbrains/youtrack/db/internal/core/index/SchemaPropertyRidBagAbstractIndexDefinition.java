package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @since 1/30/14
 */
public abstract class SchemaPropertyRidBagAbstractIndexDefinition extends DbTestBase {

  private PropertyRidBagIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    propertyIndex = new PropertyRidBagIndexDefinition("testClass", "fOne");
  }

  @Test
  public void testCreateValueSingleParameter() {
    var ridBag = new RidBag(session);

    ridBag.add(new RecordId("#1:12"));
    ridBag.add(new RecordId("#1:23"));

    final var result = propertyIndex.createValue(session, Collections.singletonList(ridBag));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(new RecordId("#1:12")));
    Assert.assertTrue(collectionResult.contains(new RecordId("#1:23")));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueTwoParameters() {
    var ridBag = new RidBag(session);

    ridBag.add(new RecordId("#1:12"));
    ridBag.add(new RecordId("#1:23"));

    final var result = propertyIndex.createValue(session, Arrays.asList(ridBag, "25"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(new RecordId("#1:12")));
    Assert.assertTrue(collectionResult.contains(new RecordId("#1:23")));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueWrongParameter() {
    Assert.assertNull(propertyIndex.createValue(session, Collections.singletonList("tt")));
  }

  @Test
  public void testCreateValueSingleParameterArrayParams() {
    var ridBag = new RidBag(session);

    ridBag.add(new RecordId("#1:12"));
    ridBag.add(new RecordId("#1:23"));

    final var result = propertyIndex.createValue(session, ridBag);

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(new RecordId("#1:12")));
    Assert.assertTrue(collectionResult.contains(new RecordId("#1:23")));

    assertEmbedded(ridBag);
  }


  @Test
  public void testCreateValueTwoParametersArrayParams() {
    var ridBag = new RidBag(session);

    ridBag.add(new RecordId("#1:12"));
    ridBag.add(new RecordId("#1:23"));

    final var result = propertyIndex.createValue(session, ridBag, "25");

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(new RecordId("#1:12")));
    Assert.assertTrue(collectionResult.contains(new RecordId("#1:23")));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueWrongParameterArrayParams() {
    Assert.assertNull(propertyIndex.createValue(session, "tt"));
  }

  @Test
  public void testGetDocumentValueToIndex() {
    var ridBag = new RidBag(session);

    ridBag.add(new RecordId("#1:12"));
    ridBag.add(new RecordId("#1:23"));

    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", ridBag);
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.getDocumentValueToIndex(session, document);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(new RecordId("#1:12")));
    Assert.assertTrue(collectionResult.contains(new RecordId("#1:23")));

    assertEmbedded(ridBag);
    session.rollback();
  }

  @Test
  public void testProcessChangeEventAddOnce() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);

    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var multiValueChangeEvent =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    propertyIndex.processChangeEvent(session, multiValueChangeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(new RecordId("#1:12"), 1);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(new RecordId("#1:12"), 2);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:13"),
            new RecordId("#1:13"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(new RecordId("#1:12"), 1);
    addedKeys.put(new RecordId("#1:13"), 1);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(new RecordId("#1:12"), 1);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(new RecordId("#1:12"), 2);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:13"),
            null,
            new RecordId("#1:13"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(new RecordId("#1:12"), 1);
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(new RecordId("#1:13"), 1);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventThree, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(new RecordId("#1:12"), 1);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventThree, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(new RecordId("#1:12"), 1);

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            new RecordId("#1:12"),
            null,
            new RecordId("#1:12"));
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, new RecordId("#1:12"),
            new RecordId("#1:12"));

    propertyIndex.processChangeEvent(session, multiValueChangeEventOne, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventTwo, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session, multiValueChangeEventThree, keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(new RecordId("#1:12"), 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  abstract void assertEmbedded(RidBag ridBag);
}
