/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives the per-arm {@link PropertyTypeInternal#convert(Object, PropertyTypeInternal,
 * com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)} body for every link arm —
 * {@link PropertyTypeInternal#LINK}, {@link PropertyTypeInternal#LINKLIST}, {@link
 * PropertyTypeInternal#LINKSET}, {@link PropertyTypeInternal#LINKMAP}, {@link
 * PropertyTypeInternal#LINKBAG}.
 *
 * <p>The link arms share the {@code session.newLink*(...)} allocation pattern but differ in
 * how they iterate their inputs and how they handle the {@code default ->} fallthrough:
 * <ul>
 *   <li>LINK accepts a single value with a 6-arm switch: null / {@link Identifiable} / {@code
 *       Result} (projection-or-identifiable) / {@code Collection} (size 0 → null, size 1 →
 *       recurse, size 2+ → throw at the post-switch line) / {@code Map<?,?> when
 *       linkedClass != null} / {@code String} (RID parse via
 *       {@link com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal#fromString}) /
 *       {@code default} throw.</li>
 *   <li>LINKLIST / LINKSET / LINKMAP / LINKBAG dispatch on the container shape and route each
 *       element through {@code LINK.convert(item, null, linkedClass, session).getIdentity()}
 *       (LINKBAG) or {@code .getIdentity()-less} pass-through (LINKLIST / LINKSET / LINKMAP).
 *       Their {@code default ->} arms differ:
 *       <ul>
 *         <li>LINKLIST: unsafe-casts the value to {@code Identifiable} and adds it as-is.</li>
 *         <li>LINKSET: routes the value through {@code LINK.convert(...)} first.</li>
 *         <li>LINKMAP: wraps under the synthetic key {@code "value"} after
 *             {@code LINK.convert(...)}.</li>
 *         <li>LINKBAG: falls through to the post-switch throw.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>This test extends {@link DbTestBase} because the link arms call
 * {@code session.newLinkList()} / {@code session.newLinkSet()} / {@code session.newLinkMap()} /
 * {@code new LinkBag(session)} unguarded; the LINK arm's String → RID parse path also wraps
 * any parse failure in {@link ValidationException} via {@code session.getDatabaseName()}, which
 * requires a live session for the throw path to be observable.
 *
 * <p>Per-arm covered shapes:
 * <ul>
 *   <li>null / valid-RID / wrong-class identifiable / valid String RID / invalid String RID /
 *       wrong-type / collection-of-1 / collection-of-2+ / Map-with-linkedClass /
 *       Map-without-linkedClass / iterable / iterator / linkbag pass-through.</li>
 * </ul>
 *
 * <p>The fixtures use a single {@code Linked} schema class (see {@link #setUpFixtures()}) and
 * one persisted entity instance to obtain a real {@link RecordId} for the LINK arm; everywhere
 * else, plain {@link RecordId} literals (e.g., {@code new RecordId(7, 1)}) suffice because the
 * convert path does not dereference the record — it only stores the identity.
 */
public class PropertyTypeInternalLinkConvertTest extends DbTestBase {

  /** The schema class used by Map → entity tests on the LINK arm. */
  private SchemaClass linkedClass;

  /**
   * A real persisted RID held as a {@link com.jetbrains.youtrackdb.internal.core.db.record.record.RID}
   * — {@code Entity.getIdentity()} returns the runtime
   * {@link com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId} variant on disk-mode
   * tests, which is not assignable to the static-record {@link RecordId} type. The String-RID
   * test uses {@code persistentRid.toString()} so the public RID interface is the right level
   * of abstraction here.
   */
  private com.jetbrains.youtrackdb.internal.core.db.record.record.RID persistentRid;

  @Before
  public void setUpFixtures() {
    var schema = session.getMetadata().getSchema();
    linkedClass = schema.createClass("Linked");
    linkedClass.createProperty("name", PropertyType.STRING);

    session.begin();
    var entity = session.newEntity(linkedClass);
    entity.setProperty("name", "fixture");
    session.commit();
    persistentRid = entity.getIdentity();
  }

  // ===========================================================================
  // LINK
  // ===========================================================================

  @Test
  public void linkNullReturnsNull() {
    // null short-circuits before linkedClass / session are dereferenced.
    assertNull(PropertyTypeInternal.LINK.convert(null, null, null, null));
  }

  /** Identifiable input is returned as identity — both for {@code RecordId} and for entities. */
  @Test
  public void linkIdentifiablePassesThroughAsIdentity() {
    var rid = new RecordId(7, 42);
    assertSame(rid, PropertyTypeInternal.LINK.convert(rid, null, linkedClass, session));
  }

  /**
   * String input is parsed via {@link
   * com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal#fromString} into a fresh
   * {@link RecordId}. Pin the round-trip on a known-good {@code #cluster:position} string.
   */
  @Test
  public void linkValidRidStringParsesToRecordId() {
    var rid = (Identifiable) PropertyTypeInternal.LINK.convert(
        persistentRid.toString(), null, linkedClass, session);
    assertNotNull(rid);
    assertEquals(persistentRid, rid.getIdentity());
  }

  /**
   * An unparseable String (not a valid {@code #cluster:position} form) wraps the underlying
   * parse failure into a {@link ValidationException} per the in-arm {@code try ... catch}.
   */
  @Test
  public void linkInvalidRidStringThrowsValidationException() {
    assertThrows(ValidationException.class,
        () -> PropertyTypeInternal.LINK.convert("not-a-rid", null, linkedClass, session));
  }

  /** Empty collection short-circuits to null. */
  @Test
  public void linkEmptyCollectionReturnsNull() {
    assertNull(PropertyTypeInternal.LINK.convert(
        Collections.emptyList(), null, linkedClass, session));
  }

  /** Singleton collection recurses on the first element — Identifiable is returned as identity. */
  @Test
  public void linkSingletonCollectionRecursesOnFirstElement() {
    var rid = new RecordId(3, 9);
    var result = PropertyTypeInternal.LINK.convert(
        List.of(rid), null, linkedClass, session);
    assertSame(rid, result);
  }

  /**
   * Multi-element collection falls through both the {@code Collection} arm (which only handles
   * 0 / 1) and the in-switch arms — the post-switch {@code throw new DatabaseException} fires
   * (line 861 in {@code PropertyTypeInternal.LINK}). This is the contract that prevents
   * silent data loss when an SQL expression returns more than one record where a single LINK
   * is expected.
   */
  @Test
  public void linkMultiElementCollectionThrowsDatabaseException() {
    var bag = List.of(new RecordId(1, 1), new RecordId(2, 2));
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.LINK.convert(bag, null, linkedClass, session));
  }

  /**
   * Map with a non-null {@code linkedClass} routes through {@code session.newEntity(linkedClass)}
   * then {@code updateFromMap}. Verify the entity is materialised and populated. Wrapped in a
   * begin / rollback for the same reason as the LINKLIST / LINKMAP identity tests — the inner
   * {@code session.newEntity(linkedClass)} call requires an active tx.
   */
  @Test
  public void linkMapWithLinkedClassAllocatesFreshEntity() {
    var inputMap = new HashMap<String, Object>();
    inputMap.put("name", "from-map");
    session.begin();
    try {
      var result = PropertyTypeInternal.LINK.convert(inputMap, null, linkedClass, session);
      assertNotNull(result);
      assertEquals("from-map", ((Entity) result).getProperty("name"));
    } finally {
      session.rollback();
    }
  }

  /**
   * Map with a null {@code linkedClass} falls through the {@code when linkedClass != null}
   * guard and into the {@code default} arm, which throws — {@link Map} is not in the otherwise-
   * accepted shapes and the guard prevents the only Map-handling branch from running.
   */
  @Test
  public void linkMapWithoutLinkedClassThrowsDatabaseException() {
    var inputMap = new HashMap<String, Object>();
    inputMap.put("name", "ignored");
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.LINK.convert(inputMap, null, null, session));
  }

  /**
   * A sentinel non-Identifiable, non-Result, non-Collection, non-Map, non-String, non-null
   * value hits the {@code default ->} arm and throws — pin the throw site as the contract for
   * type-safety on the public LINK convert path.
   */
  @Test
  public void linkWrongTypeThrowsDatabaseException() {
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.LINK.convert(new Object(), null, linkedClass, session));
  }

  // ===========================================================================
  // LINKLIST
  // ===========================================================================

  @Test
  public void linkListNullReturnsNull() {
    assertNull(PropertyTypeInternal.LINKLIST.convert(null, null, null, null));
  }

  /**
   * Already-tracked {@link EntityLinkListImpl} is returned as identity — preserves the
   * tracked-link-collection contract for in-place serialization. Must run inside an active
   * transaction because {@code session.newEntity()} attaches the new record to the current
   * tx and {@link com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionNoTx} rejects
   * record creation when no transaction is active.
   */
  @Test
  public void linkListAlreadyTrackedReturnsIdentity() {
    session.begin();
    try {
      var doc = (EntityImpl) session.newEntity();
      var tracked = new EntityLinkListImpl(doc);
      tracked.add(new RecordId(7, 1));
      var result = PropertyTypeInternal.LINKLIST.convert(tracked, null, null, session);
      assertSame(tracked, result);
    } finally {
      session.rollback();
    }
  }

  /**
   * Multi-element {@link java.util.Collection} routes each element through {@code LINK.convert}
   * and adds the result. Verify both the size and identity preservation.
   */
  @Test
  public void linkListCollectionRoutesEachItemThroughLink() {
    var input = List.of(new RecordId(7, 1), new RecordId(7, 2));
    @SuppressWarnings("unchecked")
    var result = (List<Identifiable>) PropertyTypeInternal.LINKLIST.convert(
        input, null, null, session);
    assertEquals(2, result.size());
    assertEquals(new RecordId(7, 1), result.get(0).getIdentity());
    assertEquals(new RecordId(7, 2), result.get(1).getIdentity());
  }

  /**
   * Generic {@link Iterable} (non-Collection) routes through the dedicated {@code Iterable}
   * arm with an unsized {@code newLinkList()}.
   */
  @Test
  public void linkListGenericIterableConvertsToList() {
    Iterable<Identifiable> iterable = () -> List.<Identifiable>of(
        new RecordId(7, 3), new RecordId(7, 4)).iterator();
    @SuppressWarnings("unchecked")
    var result = (List<Identifiable>) PropertyTypeInternal.LINKLIST.convert(
        iterable, null, null, session);
    assertEquals(2, result.size());
  }

  /** A bare {@code Iterator} routes through its own arm. */
  @Test
  public void linkListBareIteratorConvertsToList() {
    var iterator = List.<Identifiable>of(new RecordId(7, 5)).iterator();
    @SuppressWarnings("unchecked")
    var result = (List<Identifiable>) PropertyTypeInternal.LINKLIST.convert(
        iterator, null, null, session);
    assertEquals(1, result.size());
    assertEquals(new RecordId(7, 5), result.get(0).getIdentity());
  }

  /**
   * Default arm: a non-collection {@link Identifiable} is unsafe-cast and added as-is. Pin the
   * single-element wrap behaviour for consumers that pass a bare RID where a list is expected.
   */
  @Test
  public void linkListNonCollectionIdentifiableWrapsInSingletonList() {
    var rid = new RecordId(7, 6);
    @SuppressWarnings("unchecked")
    var result = (List<Identifiable>) PropertyTypeInternal.LINKLIST.convert(
        rid, null, null, session);
    assertEquals(1, result.size());
    assertSame(rid, result.get(0));
  }

  // ===========================================================================
  // LINKSET
  // ===========================================================================

  @Test
  public void linkSetNullReturnsNull() {
    assertNull(PropertyTypeInternal.LINKSET.convert(null, null, null, null));
  }

  /** Already-tracked {@link EntityLinkSetImpl} is returned as identity. */
  @Test
  public void linkSetAlreadyTrackedReturnsIdentity() {
    var tracked = new EntityLinkSetImpl(session);
    tracked.add(new RecordId(7, 7));
    var result = PropertyTypeInternal.LINKSET.convert(tracked, null, null, session);
    assertSame(tracked, result);
  }

  /**
   * Multi-element collection routes each element through {@code LINK.convert} and deduplicates
   * — verify both happen.
   */
  @Test
  public void linkSetCollectionRoutesAndDeduplicates() {
    var input = new ArrayList<Identifiable>();
    input.add(new RecordId(7, 8));
    input.add(new RecordId(7, 8));
    input.add(new RecordId(7, 9));
    @SuppressWarnings("unchecked")
    var result = (java.util.Set<Identifiable>) PropertyTypeInternal.LINKSET.convert(
        input, null, null, session);
    assertEquals(2, result.size());
    assertTrue(result.contains(new RecordId(7, 8)));
    assertTrue(result.contains(new RecordId(7, 9)));
  }

  /** Generic Iterable (non-Collection) routes through the unsized Iterable arm. */
  @Test
  public void linkSetGenericIterableConvertsToSet() {
    Iterable<Identifiable> iterable = () -> List.<Identifiable>of(
        new RecordId(7, 10), new RecordId(7, 11)).iterator();
    @SuppressWarnings("unchecked")
    var result = (java.util.Set<Identifiable>) PropertyTypeInternal.LINKSET.convert(
        iterable, null, null, session);
    assertEquals(2, result.size());
  }

  /** Bare Iterator routes through its own arm. */
  @Test
  public void linkSetBareIteratorConvertsToSet() {
    var iterator = List.<Identifiable>of(new RecordId(7, 12)).iterator();
    @SuppressWarnings("unchecked")
    var result = (java.util.Set<Identifiable>) PropertyTypeInternal.LINKSET.convert(
        iterator, null, null, session);
    assertEquals(1, result.size());
    assertTrue(result.contains(new RecordId(7, 12)));
  }

  /**
   * Default arm: a non-collection value is routed through {@code LINK.convert} first then
   * added. {@link RecordId} satisfies the LINK Identifiable arm, so it is added as-is.
   */
  @Test
  public void linkSetNonCollectionIdentifiableWrapsInSingletonSet() {
    var rid = new RecordId(7, 13);
    @SuppressWarnings("unchecked")
    var result = (java.util.Set<Identifiable>) PropertyTypeInternal.LINKSET.convert(
        rid, null, null, session);
    assertEquals(1, result.size());
    assertTrue(result.contains(rid));
  }

  // ===========================================================================
  // LINKMAP
  // ===========================================================================

  @Test
  public void linkMapNullReturnsNull() {
    assertNull(PropertyTypeInternal.LINKMAP.convert(null, null, null, null));
  }

  /**
   * Already-tracked {@link EntityLinkMapIml} is returned as identity. Wrapped in a
   * begin / rollback to satisfy the same record-creation-requires-tx invariant as the
   * LINKLIST identity test above.
   */
  @Test
  public void linkMapAlreadyTrackedReturnsIdentity() {
    session.begin();
    try {
      var tracked = new EntityLinkMapIml((EntityImpl) session.newEntity());
      tracked.put("k", new RecordId(7, 14));
      var result = PropertyTypeInternal.LINKMAP.convert(tracked, null, null, session);
      assertSame(tracked, result);
    } finally {
      session.rollback();
    }
  }

  /**
   * Multi-entry {@link Map} routes each value through {@code LINK.convert(value, null,
   * linkedClass, session)} and stringifies the key via {@code entry.getKey().toString()}.
   */
  @Test
  public void linkMapMapRoutesValuesThroughLink() {
    var input = new LinkedHashMap<String, Identifiable>();
    input.put("a", new RecordId(7, 15));
    input.put("b", new RecordId(7, 16));
    @SuppressWarnings("unchecked")
    var result = (Map<String, Identifiable>) PropertyTypeInternal.LINKMAP.convert(
        input, null, null, session);
    assertEquals(2, result.size());
    assertEquals(new RecordId(7, 15), result.get("a"));
    assertEquals(new RecordId(7, 16), result.get("b"));
  }

  /**
   * Default arm: a non-Map, non-Result {@link Identifiable} is wrapped under the synthetic key
   * {@code "value"} after {@code LINK.convert(...)} accepts the identifiable as identity.
   */
  @Test
  public void linkMapNonMapIdentifiableWrapsUnderValueKey() {
    var rid = new RecordId(7, 17);
    @SuppressWarnings("unchecked")
    var result = (Map<String, Identifiable>) PropertyTypeInternal.LINKMAP.convert(
        rid, null, null, session);
    assertEquals(1, result.size());
    assertTrue(result.containsKey("value"));
    assertEquals(rid, result.get("value"));
  }

  /**
   * Non-String map keys are stringified — pin the contract via an Integer-keyed map.
   */
  @Test
  public void linkMapMapWithIntegerKeyStringifiesKey() {
    var input = new HashMap<Integer, Identifiable>();
    input.put(99, new RecordId(7, 18));
    @SuppressWarnings("unchecked")
    var result = (Map<String, Identifiable>) PropertyTypeInternal.LINKMAP.convert(
        input, null, null, session);
    assertEquals(1, result.size());
    assertEquals(new RecordId(7, 18), result.get("99"));
  }

  // ===========================================================================
  // LINKBAG
  // ===========================================================================

  @Test
  public void linkBagNullReturnsNull() {
    assertNull(PropertyTypeInternal.LINKBAG.convert(null, null, null, null));
  }

  /** A {@link LinkBag} input is returned as identity. */
  @Test
  public void linkBagPassesThroughAsIdentity() {
    var bag = new LinkBag(session);
    bag.add(new RecordId(7, 19));
    var result = (LinkBag) PropertyTypeInternal.LINKBAG.convert(bag, null, null, session);
    assertSame(bag, result);
  }

  /**
   * An {@link Iterable} of {@link Identifiable} routes each item through
   * {@code LINK.convert(item, null, linkedClass, session).getIdentity()} and adds it to a fresh
   * LinkBag.
   */
  @Test
  public void linkBagIterableRoutesEachItemThroughLink() {
    Iterable<Identifiable> items = List.<Identifiable>of(
        new RecordId(7, 20), new RecordId(7, 21));
    var result = (LinkBag) PropertyTypeInternal.LINKBAG.convert(items, null, null, session);
    assertNotNull(result);
    assertEquals(2, result.size());
  }

  /** A bare {@link java.util.Iterator} routes through its own arm. */
  @Test
  public void linkBagBareIteratorRoutesEachItemThroughLink() {
    var iterator = List.<Identifiable>of(new RecordId(7, 22)).iterator();
    var result = (LinkBag) PropertyTypeInternal.LINKBAG.convert(iterator, null, null, session);
    assertEquals(1, result.size());
  }

  /** A single {@link Identifiable} is added directly via {@code linkBag.add(getIdentity())}. */
  @Test
  public void linkBagSingleIdentifiableAddsIdentity() {
    var rid = new RecordId(7, 23);
    var result = (LinkBag) PropertyTypeInternal.LINKBAG.convert(rid, null, null, session);
    assertEquals(1, result.size());
  }

  /**
   * The default {@code switch} arm of LINKBAG falls through to the post-switch
   * {@code throw new DatabaseException(...)}. Pin the throw on a sentinel Object so the
   * post-switch escape hatch is reachable.
   */
  @Test
  public void linkBagWrongTypeThrowsDatabaseException() {
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.LINKBAG.convert(new Object(), null, null, session));
  }

  /**
   * A {@link java.util.Set}-shaped Collection (HashSet) implements {@link Iterable} and routes
   * through that arm — pin the dispatch ordering so a future tightening of the LINKBAG switch
   * to disallow Sets is a deliberate, visible event.
   */
  @Test
  public void linkBagHashSetRoutesThroughIterableArm() {
    var input = new HashSet<Identifiable>();
    input.add(new RecordId(7, 24));
    input.add(new RecordId(7, 25));
    var result = (LinkBag) PropertyTypeInternal.LINKBAG.convert(input, null, null, session);
    assertEquals(2, result.size());
  }

  // ===========================================================================
  // Cross-arm sanity check
  // ===========================================================================

  /**
   * Every link arm short-circuits null at the top of the body — pin the five nulls in one test
   * so a regression in any single arm's null branch is caught regardless of which arm is
   * exercised first.
   */
  @Test
  public void allLinkArmsShortCircuitNullToNull() {
    assertNull(PropertyTypeInternal.LINK.convert(null, null, null, null));
    assertNull(PropertyTypeInternal.LINKLIST.convert(null, null, null, null));
    assertNull(PropertyTypeInternal.LINKSET.convert(null, null, null, null));
    assertNull(PropertyTypeInternal.LINKMAP.convert(null, null, null, null));
    assertNull(PropertyTypeInternal.LINKBAG.convert(null, null, null, null));
  }

  /**
   * Empty non-null inputs across the link container arms (LIST / SET / MAP) return non-null
   * empty collections. Pin the non-null contract.
   *
   * <p>LINKBAG is intentionally excluded: an empty {@link Map} or {@link java.util.Collection}
   * is not directly accepted as an empty-bag input by the LINKBAG arm — the arm dispatches on
   * {@code Iterable} and produces an empty bag, but the {@code default} branch on a non-
   * {@link Iterable}, non-{@link java.util.Iterator}, non-{@link Identifiable} value would
   * throw. We exercise LINKBAG's empty {@link Iterable} arm explicitly instead.
   */
  @Test
  public void emptyContainerInputsAcrossLinkArmsReturnNonNullEmpty() {
    @SuppressWarnings("unchecked")
    var emptyList = (List<Identifiable>) PropertyTypeInternal.LINKLIST.convert(
        Collections.emptyList(), null, null, session);
    @SuppressWarnings("unchecked")
    var emptySet = (java.util.Set<Identifiable>) PropertyTypeInternal.LINKSET.convert(
        Collections.emptySet(), null, null, session);
    @SuppressWarnings("unchecked")
    var emptyMap = (Map<String, Identifiable>) PropertyTypeInternal.LINKMAP.convert(
        Collections.emptyMap(), null, null, session);

    assertNotNull(emptyList);
    assertNotNull(emptySet);
    assertNotNull(emptyMap);
    assertTrue(emptyList.isEmpty());
    assertTrue(emptySet.isEmpty());
    assertTrue(emptyMap.isEmpty());

    // LINKBAG via empty Iterable returns an empty bag (the post-switch throw doesn't fire
    // because the Iterable arm explicitly returns the bag from inside the switch).
    Iterable<Identifiable> emptyIterable = Collections::emptyIterator;
    var emptyBag = (LinkBag) PropertyTypeInternal.LINKBAG.convert(
        emptyIterable, null, null, session);
    assertNotNull(emptyBag);
    assertEquals(0, emptyBag.size());
  }
}
