package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
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

/**
 * @since 1/30/14
 */
public abstract class SchemaPropertyLinkBagAbstractIndexDefinition extends DbTestBase {

  private PropertyLinkBagIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    session.begin();
    propertyIndex = new PropertyLinkBagIndexDefinition("testClass", "fOne");
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  @Test
  public void testCreateValueSingleParameter() {
    var ridBag = new LinkBag(session);

    ridBag.add(RecordIdInternal.fromString("#1:12", false));
    ridBag.add(RecordIdInternal.fromString("#1:23", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Collections.singletonList(ridBag));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:23", false)));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueTwoParameters() {
    var ridBag = new LinkBag(session);

    ridBag.add(RecordIdInternal.fromString("#1:12", false));
    ridBag.add(RecordIdInternal.fromString("#1:23", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Arrays.asList(ridBag, "25"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:23", false)));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueWrongParameter() {
    Assert.assertNull(
        propertyIndex.createValue(session.getActiveTransaction(), Collections.singletonList("tt")));
  }

  @Test
  public void testCreateValueSingleParameterArrayParams() {
    var ridBag = new LinkBag(session);

    ridBag.add(RecordIdInternal.fromString("#1:12", false));
    ridBag.add(RecordIdInternal.fromString("#1:23", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(), ridBag);

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:23", false)));

    assertEmbedded(ridBag);
  }


  @Test
  public void testCreateValueTwoParametersArrayParams() {
    var ridBag = new LinkBag(session);

    ridBag.add(RecordIdInternal.fromString("#1:12", false));
    ridBag.add(RecordIdInternal.fromString("#1:23", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(), ridBag, "25");

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:23", false)));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueWrongParameterArrayParams() {
    Assert.assertNull(propertyIndex.createValue(session.getActiveTransaction(), "tt"));
  }

  @Test
  public void testGetDocumentValueToIndex() {
    var ridBag = new LinkBag(session);

    ridBag.add(RecordIdInternal.fromString("#1:12", false));
    ridBag.add(RecordIdInternal.fromString("#1:23", false));

    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", ridBag);
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.getDocumentValueToIndex(session.getActiveTransaction(),
        document);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:23", false)));

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
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);

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
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(RecordIdInternal.fromString("#1:12", false), 2);

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
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, RecordIdInternal.fromString("#1:13", false),
            RecordIdInternal.fromString("#1:13", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);
    addedKeys.put(RecordIdInternal.fromString("#1:13", false), 1);

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
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEvent,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);

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
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(RecordIdInternal.fromString("#1:12", false), 2);

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
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));

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
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            RecordIdInternal.fromString("#1:13", false),
            null,
            RecordIdInternal.fromString("#1:13", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);
    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(RecordIdInternal.fromString("#1:13", false), 1);

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
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventThree,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();
    addedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);

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
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventThree,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);

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
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventTwo =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.REMOVE,
            RecordIdInternal.fromString("#1:12", false),
            null,
            RecordIdInternal.fromString("#1:12", false));
    final var multiValueChangeEventThree =
        new MultiValueChangeEvent<Identifiable, Identifiable>(
            ChangeType.ADD, RecordIdInternal.fromString("#1:12", false),
            RecordIdInternal.fromString("#1:12", false));

    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventOne,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventTwo,
        keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(session.getActiveTransaction(), multiValueChangeEventThree,
        keysToAdd, keysToRemove);

    final Map<Object, Integer> addedKeys = new HashMap<Object, Integer>();

    final Map<Object, Integer> removedKeys = new HashMap<Object, Integer>();
    removedKeys.put(RecordIdInternal.fromString("#1:12", false), 1);

    Assert.assertEquals(keysToAdd, addedKeys);
    Assert.assertEquals(keysToRemove, removedKeys);
  }

  abstract void assertEmbedded(LinkBag linkBag);
}
