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
 * Abstract base for testing {@link PropertyLinkBagIndexDefinition} key extraction and change processing.
 *
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

  // --- Tests for heavyweight edges: key (primaryRid) != value (secondaryRid) ---
  // Index should only contain entries for primaryRid, never for secondaryRid.

  /**
   * Verifies that when a heavyweight ADD event is processed (key=#1:12, value=#1:50),
   * only the primaryRid (#1:12) is added to the index — the secondaryRid (#1:50)
   * should NOT appear.
   */
  @Test
  public void testProcessChangeEventAddHeavyweightOnlyIndexesPrimaryRid() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    // Heavyweight: key (edge RID) differs from value (opposite vertex RID)
    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);

    // Only the primaryRid (#1:12) should be in keysToAdd
    final Map<Object, Integer> expectedAdd = new HashMap<>();
    expectedAdd.put(RecordIdInternal.fromString("#1:12", false), 1);

    Assert.assertEquals(expectedAdd, keysToAdd);
    Assert.assertTrue("ADD event should not produce any removals",
        keysToRemove.isEmpty());
    Assert.assertFalse("secondaryRid #1:50 should NOT appear in keysToAdd",
        keysToAdd.containsKey(RecordIdInternal.fromString("#1:50", false)));
  }

  /**
   * Verifies that when a heavyweight REMOVE event is processed (key=#1:12, oldValue=#1:50),
   * only the primaryRid (#1:12) is removed from the index — the secondaryRid (#1:50)
   * should NOT be removed.
   */
  @Test
  public void testProcessChangeEventRemoveHeavyweightOnlyRemovesPrimaryRid() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:12", false),
        null,
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);

    final Map<Object, Integer> expectedRemove = new HashMap<>();
    expectedRemove.put(RecordIdInternal.fromString("#1:12", false), 1);

    Assert.assertTrue(keysToAdd.isEmpty());
    Assert.assertEquals(expectedRemove, keysToRemove);
    Assert.assertFalse("secondaryRid #1:50 should NOT appear in keysToRemove",
        keysToRemove.containsKey(RecordIdInternal.fromString("#1:50", false)));
  }

  /**
   * Verifies that createValue for a LinkBag containing a heavyweight pair (primary != secondary)
   * only includes the primaryRid in the result, not the secondaryRid.
   */
  @Test
  public void testCreateValueHeavyweightPairOnlyIncludesPrimaryRid() {
    var ridBag = new LinkBag(session);

    // Add a heavyweight pair: primaryRid=#1:12, secondaryRid=#1:50
    ridBag.add(
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));

    final var result = propertyIndex.createValue(
        session.getActiveTransaction(), Collections.singletonList(ridBag));
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals("Should contain only the primaryRid", 1, collectionResult.size());
    Assert.assertTrue("Should contain primaryRid #1:12",
        collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertFalse("Should NOT contain secondaryRid #1:50",
        collectionResult.contains(RecordIdInternal.fromString("#1:50", false)));

    assertEmbedded(ridBag);
  }

  /**
   * Verifies createValue(Object...) for a LinkBag with a heavyweight pair only
   * includes the primaryRid.
   */
  @Test
  public void testCreateValueArrayParamsHeavyweightPairOnlyIncludesPrimaryRid() {
    var ridBag = new LinkBag(session);

    ridBag.add(
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));

    final var result = propertyIndex.createValue(
        session.getActiveTransaction(), ridBag);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals("Should contain only the primaryRid", 1, collectionResult.size());
    Assert.assertTrue("Should contain primaryRid #1:12",
        collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertFalse("Should NOT contain secondaryRid #1:50",
        collectionResult.contains(RecordIdInternal.fromString("#1:50", false)));

    assertEmbedded(ridBag);
  }

  abstract void assertEmbedded(LinkBag linkBag);
}
