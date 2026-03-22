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
 * Abstract base for testing {@link PropertyLinkBagSecondaryIndexDefinition} — indexes
 * secondaryRid (opposite vertex) instead of primaryRid (edge record).
 *
 * <p>Each ADD event has key=primaryRid, value=secondaryRid. Each REMOVE event has
 * key=primaryRid, value=null, oldValue=secondaryRid. The secondary index definition
 * must extract secondaryRid from getValue() on ADD and getOldValue() on REMOVE.
 */
public abstract class SchemaPropertyLinkBagSecondaryAbstractIndexDefinition extends DbTestBase {

  private PropertyLinkBagSecondaryIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    session.begin();
    propertyIndex = new PropertyLinkBagSecondaryIndexDefinition("testClass", "fOne");
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  // --- createValue(List) tests using LinkBag with heavyweight pairs ---

  /**
   * Verifies that createValue extracts secondaryRid (not primaryRid) from each RidPair
   * in the LinkBag.
   */
  @Test
  public void testCreateValueSingleParameterExtractsSecondaryRid() {
    var ridBag = new LinkBag(session);

    // Heavyweight pair: primaryRid=#1:12 (edge), secondaryRid=#1:50 (vertex)
    ridBag.add(
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    // Heavyweight pair: primaryRid=#1:13 (edge), secondaryRid=#1:51 (vertex)
    ridBag.add(
        RecordIdInternal.fromString("#1:13", false),
        RecordIdInternal.fromString("#1:51", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Collections.singletonList(ridBag));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());

    // Should contain secondaryRids, not primaryRids
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:50", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:51", false)));
    Assert.assertFalse(collectionResult.contains(RecordIdInternal.fromString("#1:12", false)));
    Assert.assertFalse(collectionResult.contains(RecordIdInternal.fromString("#1:13", false)));

    assertEmbedded(ridBag);
  }

  /**
   * Verifies that createValue with two parameters (LinkBag + extra) still works correctly.
   */
  @Test
  public void testCreateValueTwoParameters() {
    var ridBag = new LinkBag(session);

    ridBag.add(
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Arrays.asList(ridBag, "25"));

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(1, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:50", false)));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueWrongParameter() {
    Assert.assertNull(
        propertyIndex.createValue(session.getActiveTransaction(), Collections.singletonList("tt")));
  }

  // --- createValue(Object...) tests ---

  /**
   * Verifies that the varargs createValue path also extracts secondaryRid.
   */
  @Test
  public void testCreateValueArrayParamsExtractsSecondaryRid() {
    var ridBag = new LinkBag(session);

    ridBag.add(
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    ridBag.add(
        RecordIdInternal.fromString("#1:13", false),
        RecordIdInternal.fromString("#1:51", false));

    final var result = propertyIndex.createValue(session.getActiveTransaction(), ridBag);

    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:50", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:51", false)));

    assertEmbedded(ridBag);
  }

  @Test
  public void testCreateValueWrongParameterArrayParams() {
    Assert.assertNull(propertyIndex.createValue(session.getActiveTransaction(), "tt"));
  }

  // --- getDocumentValueToIndex tests ---

  /**
   * Verifies that getDocumentValueToIndex reads the entity's LinkBag property
   * and extracts secondaryRids.
   */
  @Test
  public void testGetDocumentValueToIndex() {
    var ridBag = new LinkBag(session);

    ridBag.add(
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    ridBag.add(
        RecordIdInternal.fromString("#1:13", false),
        RecordIdInternal.fromString("#1:51", false));

    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", ridBag);
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.getDocumentValueToIndex(session.getActiveTransaction(),
        document);
    Assert.assertTrue(result instanceof Collection);

    final var collectionResult = (Collection<?>) result;
    Assert.assertEquals(2, collectionResult.size());
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:50", false)));
    Assert.assertTrue(collectionResult.contains(RecordIdInternal.fromString("#1:51", false)));

    assertEmbedded(ridBag);
  }

  // --- processChangeEvent ADD tests (uses getValue() = secondaryRid) ---

  /**
   * Verifies that ADD event indexes the secondaryRid (value), not the primaryRid (key).
   */
  @Test
  public void testProcessChangeEventAddIndexesSecondaryRid() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    // Heavyweight: key=#1:12 (edge), value=#1:50 (vertex)
    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);

    // Secondary index should contain secondaryRid #1:50, not primaryRid #1:12
    final Map<Object, Integer> expectedAdd = new HashMap<>();
    expectedAdd.put(RecordIdInternal.fromString("#1:50", false), 1);

    Assert.assertEquals(expectedAdd, keysToAdd);
    Assert.assertTrue(keysToRemove.isEmpty());
    Assert.assertFalse("primaryRid #1:12 should NOT appear in keysToAdd",
        keysToAdd.containsKey(RecordIdInternal.fromString("#1:12", false)));
  }

  /**
   * Verifies that two ADD events for the same secondaryRid produce count=2.
   */
  @Test
  public void testProcessChangeEventAddTwoTimes() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    // Two different edges pointing to the same vertex
    final var event1 = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    final var event2 = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:13", false),
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event1, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event2, keysToAdd, keysToRemove);

    final Map<Object, Integer> expectedAdd = new HashMap<>();
    expectedAdd.put(RecordIdInternal.fromString("#1:50", false), 2);

    Assert.assertEquals(expectedAdd, keysToAdd);
    Assert.assertTrue(keysToRemove.isEmpty());
  }

  /**
   * Verifies that ADD events for different secondaryRids produce separate entries.
   */
  @Test
  public void testProcessChangeEventAddTwoValues() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var event1 = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    final var event2 = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:13", false),
        RecordIdInternal.fromString("#1:51", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event1, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event2, keysToAdd, keysToRemove);

    final Map<Object, Integer> expectedAdd = new HashMap<>();
    expectedAdd.put(RecordIdInternal.fromString("#1:50", false), 1);
    expectedAdd.put(RecordIdInternal.fromString("#1:51", false), 1);

    Assert.assertEquals(expectedAdd, keysToAdd);
    Assert.assertTrue(keysToRemove.isEmpty());
  }

  // --- processChangeEvent REMOVE tests (uses getOldValue() = secondaryRid) ---

  /**
   * Verifies that REMOVE event removes the secondaryRid (oldValue), not the primaryRid (key).
   */
  @Test
  public void testProcessChangeEventRemoveIndexesSecondaryRid() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    // Heavyweight REMOVE: key=#1:12 (edge), value=null, oldValue=#1:50 (vertex)
    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:12", false),
        null,
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);

    final Map<Object, Integer> expectedRemove = new HashMap<>();
    expectedRemove.put(RecordIdInternal.fromString("#1:50", false), 1);

    Assert.assertTrue(keysToAdd.isEmpty());
    Assert.assertEquals(expectedRemove, keysToRemove);
    Assert.assertFalse("primaryRid #1:12 should NOT appear in keysToRemove",
        keysToRemove.containsKey(RecordIdInternal.fromString("#1:12", false)));
  }

  /**
   * Verifies that two REMOVE events for the same secondaryRid produce count=2.
   */
  @Test
  public void testProcessChangeEventRemoveTwoTimes() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var event1 = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:12", false),
        null,
        RecordIdInternal.fromString("#1:50", false));
    final var event2 = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:13", false),
        null,
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event1, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event2, keysToAdd, keysToRemove);

    final Map<Object, Integer> expectedRemove = new HashMap<>();
    expectedRemove.put(RecordIdInternal.fromString("#1:50", false), 2);

    Assert.assertTrue(keysToAdd.isEmpty());
    Assert.assertEquals(expectedRemove, keysToRemove);
  }

  // --- ADD then REMOVE cancellation ---

  /**
   * Verifies that ADD + REMOVE of the same secondaryRid cancel out.
   */
  @Test
  public void testProcessChangeEventAddRemoveCancels() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var addEvent = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    final var removeEvent = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:12", false),
        null,
        RecordIdInternal.fromString("#1:50", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), addEvent, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), removeEvent, keysToAdd, keysToRemove);

    Assert.assertTrue("ADD + REMOVE should cancel out", keysToAdd.isEmpty());
    Assert.assertTrue("ADD + REMOVE should cancel out", keysToRemove.isEmpty());
  }

  /**
   * Verifies that ADD + REMOVE with different secondaryRids do not cancel.
   */
  @Test
  public void testProcessChangeEventAddRemoveDifferentValues() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var addEvent = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false));
    final var removeEvent = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:13", false),
        null,
        RecordIdInternal.fromString("#1:51", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), addEvent, keysToAdd, keysToRemove);
    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), removeEvent, keysToAdd, keysToRemove);

    final Map<Object, Integer> expectedAdd = new HashMap<>();
    expectedAdd.put(RecordIdInternal.fromString("#1:50", false), 1);
    final Map<Object, Integer> expectedRemove = new HashMap<>();
    expectedRemove.put(RecordIdInternal.fromString("#1:51", false), 1);

    Assert.assertEquals(expectedAdd, keysToAdd);
    Assert.assertEquals(expectedRemove, keysToRemove);
  }

  // --- getFieldsToIndex and DDL tests ---

  /**
   * Verifies that getFieldsToIndex returns "field by value" for DDL round-trip support.
   */
  @Test
  public void testGetFieldsToIndex() {
    var fields = propertyIndex.getFieldsToIndex();
    Assert.assertEquals(1, fields.size());
    Assert.assertEquals("fOne by value", fields.get(0));
  }

  /**
   * Verifies that toCreateIndexDDL includes "by value" in the generated DDL statement.
   */
  @Test
  public void testToCreateIndexDDL() {
    var ddl = propertyIndex.toCreateIndexDDL("testIdx", "NOTUNIQUE", null);
    Assert.assertEquals(
        "create index `testIdx` on `testClass` ( `fOne` by value ) NOTUNIQUE",
        ddl);
  }

  /**
   * Verifies that toCreateIndexDDL includes the engine when specified.
   */
  @Test
  public void testToCreateIndexDDLWithEngine() {
    var ddl = propertyIndex.toCreateIndexDDL("testIdx", "NOTUNIQUE", "SBTREE");
    Assert.assertEquals(
        "create index `testIdx` on `testClass` ( `fOne` by value ) NOTUNIQUE ENGINE  SBTREE",
        ddl);
  }

  // --- UPDATE change type test ---

  /**
   * Verifies that UPDATE change type throws IllegalArgumentException, since LINKBAG
   * index definitions only handle ADD and REMOVE.
   */
  @Test(expected = IllegalArgumentException.class)
  public void testProcessChangeEventUpdateThrows() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.UPDATE,
        RecordIdInternal.fromString("#1:12", false),
        RecordIdInternal.fromString("#1:50", false),
        RecordIdInternal.fromString("#1:51", false));

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);
  }

  // --- Empty LinkBag tests ---

  /**
   * Verifies that createValue returns an empty collection for an empty LinkBag.
   */
  @Test
  public void testCreateValueEmptyLinkBag() {
    var ridBag = new LinkBag(session);

    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Collections.singletonList(ridBag));

    Assert.assertTrue(result instanceof Collection);
    Assert.assertTrue(((Collection<?>) result).isEmpty());

    assertEmbedded(ridBag);
  }

  /**
   * Verifies that createValue(Object...) returns an empty collection for an empty LinkBag.
   */
  @Test
  public void testCreateValueArrayParamsEmptyLinkBag() {
    var ridBag = new LinkBag(session);

    final var result = propertyIndex.createValue(session.getActiveTransaction(), ridBag);

    Assert.assertTrue(result instanceof Collection);
    Assert.assertTrue(((Collection<?>) result).isEmpty());

    assertEmbedded(ridBag);
  }

  // --- Null value guard tests ---

  /**
   * Verifies that ADD event with null value is safely ignored (no NPE).
   */
  @Test
  public void testProcessChangeEventAddWithNullValueIsIgnored() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    // 3-arg constructor: value defaults to the third arg which we pass as null
    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.ADD,
        RecordIdInternal.fromString("#1:12", false),
        null);

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);

    Assert.assertTrue("Null value ADD should produce no index entries", keysToAdd.isEmpty());
    Assert.assertTrue(keysToRemove.isEmpty());
  }

  /**
   * Verifies that REMOVE event with null oldValue is safely ignored (no NPE).
   */
  @Test
  public void testProcessChangeEventRemoveWithNullOldValueIsIgnored() {
    final var keysToAdd = new Object2IntOpenHashMap<Object>();
    keysToAdd.defaultReturnValue(-1);
    final var keysToRemove = new Object2IntOpenHashMap<Object>();
    keysToRemove.defaultReturnValue(-1);

    // 3-arg constructor: value=null, oldValue defaults to null
    final var event = new MultiValueChangeEvent<Identifiable, Identifiable>(
        ChangeType.REMOVE,
        RecordIdInternal.fromString("#1:12", false),
        null);

    propertyIndex.processChangeEvent(
        session.getActiveTransaction(), event, keysToAdd, keysToRemove);

    Assert.assertTrue(keysToAdd.isEmpty());
    Assert.assertTrue("Null oldValue REMOVE should produce no index entries",
        keysToRemove.isEmpty());
  }

  /**
   * Verifies that toCreateIndexDDL correctly places "by value" before collate when a
   * non-default collate is set, producing valid DDL for round-trip.
   */
  @Test
  public void testToCreateIndexDDLWithCollate() {
    var indexWithCollate = new PropertyLinkBagSecondaryIndexDefinition("testClass", "fOne");
    indexWithCollate.setCollate("ci");
    var ddl = indexWithCollate.toCreateIndexDDL("testIdx", "NOTUNIQUE", null);
    Assert.assertEquals(
        "create index `testIdx` on `testClass` ( `fOne` by value collate ci ) NOTUNIQUE",
        ddl);
  }

  abstract void assertEmbedded(LinkBag linkBag);
}
