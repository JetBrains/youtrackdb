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
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Drives the per-arm {@link PropertyTypeInternal#convert(Object, PropertyTypeInternal,
 * com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)} body for the embedded
 * collection arms — {@link PropertyTypeInternal#EMBEDDEDLIST}, {@link
 * PropertyTypeInternal#EMBEDDEDSET}, {@link PropertyTypeInternal#EMBEDDEDMAP}.
 *
 * <p>The arms share a structural pattern: a {@code switch (value)} dispatch over null /
 * already-tracked-impl / {@code Object[]} (LIST only) / {@code Collection} / {@code Map}
 * (EMBEDDEDMAP path) / {@code Iterable} / {@code Iterator} / {@code default} cases, with each
 * non-trivial branch routing items through the package-private {@code
 * convertEmbeddedCollectionItem(linkedType, linkedClass, session, item, rootType)} helper. The
 * helper does its own type-by-value lookup when both {@code linkedType} and {@code linkedClass}
 * are null, so a null-element-typed collection of e.g. {@link String} primitives round-trips
 * cleanly without needing a schema-bound linked type.
 *
 * <p>The arms differ in two important details:
 * <ul>
 *   <li>EMBEDDEDLIST short-circuits {@link EntityEmbeddedListImpl} as identity (preserves
 *       the tracked-collection contract); a non-tracked input with the same element types is
 *       converted to a fresh {@link EntityEmbeddedListImpl}.</li>
 *   <li>EMBEDDEDSET short-circuits {@link EntityEmbeddedSetImpl} as identity; the
 *       generic-{@code Iterable} arm uses an unsized {@code session.newEmbeddedSet()} (the
 *       sized one is reserved for the {@code Collection} arm where the size is known up front).</li>
 *   <li>EMBEDDEDMAP short-circuits {@link EntityEmbeddedMapImpl} as identity; non-Map
 *       {@link Iterable}-of-entries route per the {@code (Map.Entry | Map | else throw)} switch
 *       inside the iterable arm; the {@code default ->} arm (a non-collection value) wraps the
 *       value under the synthetic key {@code "value"}.</li>
 * </ul>
 *
 * <p>Test class extends {@link DbTestBase} because the per-arm convert calls
 * {@code session.newEmbeddedList()} / {@code session.newEmbeddedSet()} /
 * {@code session.newEmbeddedMap()} unguarded; the session is also required so the helper's
 * type-by-value path can throw {@link DatabaseException} with a database name on
 * unsupported elements (which we exercise on the wrong-element-type rows).
 *
 * <p>The static return type of {@code PropertyTypeInternal.X.convert(...)} is {@code Object}
 * (the abstract base method's signature) even though each per-arm override declares a refined
 * return — Java covariant returns are resolved at the override declaration but the receiver-
 * static-type rule on {@code PropertyTypeInternal.EMBEDDEDLIST.convert(...)} routes through the
 * {@code Object} abstract. Tests therefore cast the result to the appropriate
 * {@code List<Object>} / {@code Set<Object>} / {@code Map<String, Object>} for assertion.
 */
public class PropertyTypeInternalCollectionConvertTest extends DbTestBase {

  // ===========================================================================
  // EMBEDDEDLIST
  // ===========================================================================

  @Test
  public void embeddedListNullReturnsNull() {
    assertNull(PropertyTypeInternal.EMBEDDEDLIST.convert(null, null, null, null));
  }

  /**
   * An already-tracked {@link EntityEmbeddedListImpl} is returned as identity — preserves the
   * tracked-collection contract so the in-place serializer doesn't lose its tracker on convert.
   */
  @Test
  public void embeddedListAlreadyTrackedReturnsIdentity() {
    @SuppressWarnings("unchecked")
    var tracked = (EntityEmbeddedListImpl<Object>) session.newEmbeddedList();
    tracked.add("alpha");
    var result = PropertyTypeInternal.EMBEDDEDLIST.convert(tracked, null, null, session);
    assertSame("tracked EntityEmbeddedListImpl must be returned without a fresh allocation",
        tracked, result);
  }

  /**
   * The {@code Object[]} arm is checked before the {@code Collection} / {@code Iterable} arms in
   * the switch; arrays therefore route through {@code session.newEmbeddedList(array.length)}
   * with size-known up front. Verify both the size and the element preservation.
   */
  @Test
  public void embeddedListObjectArrayConvertsWithKnownSize() {
    var array = new Object[] {"a", "b", "c"};
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        array, null, null, session);
    assertNotNull(result);
    assertEquals(3, result.size());
    assertEquals(List.of("a", "b", "c"), result);
  }

  /** Empty {@link java.util.Collection} converts to an empty embedded list (size = 0). */
  @Test
  public void embeddedListEmptyCollectionReturnsEmptyList() {
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        Collections.emptyList(), null, null, session);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /** Single-element {@link java.util.Collection} routes through the {@code Collection} arm. */
  @Test
  public void embeddedListSingletonCollectionConvertsToOneElementList() {
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        List.of("only"), null, null, session);
    assertEquals(List.of("only"), result);
  }

  /** Multi-element {@link java.util.Collection} preserves order. */
  @Test
  public void embeddedListMultiElementCollectionPreservesOrder() {
    var input = new ArrayList<String>();
    input.add("x");
    input.add("y");
    input.add("z");
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        input, null, null, session);
    assertEquals(List.of("x", "y", "z"), result);
  }

  /**
   * {@code null} elements inside a {@link java.util.Collection} are passed through by the
   * helper's {@code if (item == null) return null} short-circuit — no type-by-value lookup is
   * attempted for null entries. Pin the contract so a future change to disallow nulls in
   * embedded lists is a deliberate, visible event.
   */
  @Test
  public void embeddedListCollectionWithNullElementKeepsNull() {
    var input = new ArrayList<String>();
    input.add("x");
    input.add(null);
    input.add("z");
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        input, null, null, session);
    assertEquals(3, result.size());
    assertEquals("x", result.get(0));
    assertNull(result.get(1));
    assertEquals("z", result.get(2));
  }

  /**
   * A non-Collection {@link Iterable} routes through the dedicated {@code Iterable} arm, which
   * uses an unsized {@code session.newEmbeddedList()} (the size is unknown until the iterator
   * is exhausted). Use a thin {@code Iterable} wrapper that is NOT a {@code java.util.Collection}
   * to keep the dispatch on the right arm.
   */
  @Test
  public void embeddedListGenericIterableConvertsToList() {
    Iterable<String> iterable = () -> List.of("p", "q").iterator();
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        iterable, null, null, session);
    assertEquals(List.of("p", "q"), result);
  }

  /** A bare {@code Iterator} routes through its own arm (separate from {@code Iterable}). */
  @Test
  public void embeddedListBareIteratorConvertsToList() {
    var iterator = List.of("u", "v").iterator();
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        iterator, null, null, session);
    assertEquals(List.of("u", "v"), result);
  }

  /**
   * The {@code default ->} arm: a non-collection, non-array value is wrapped in a singleton
   * embedded list under the helper's type-by-value lookup. {@code "scalar"} resolves to STRING
   * via {@code TYPES_BY_CLASS}, so the wrap succeeds. Pin the wrap behaviour — many call sites
   * rely on EMBEDDEDLIST tolerating a single primitive value as input.
   */
  @Test
  public void embeddedListNonCollectionScalarValueWrapsInSingletonList() {
    @SuppressWarnings("unchecked")
    var result = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        "scalar", null, null, session);
    assertEquals(1, result.size());
    assertEquals("scalar", result.get(0));
  }

  /**
   * Mismatched-element-type: an unsupported element (a sentinel Object with no type binding)
   * inside a Collection routes the helper through type-by-value lookup which returns null and
   * throws {@link DatabaseException}. Pin the throw site as the contract — silently dropping the
   * element would mask data loss.
   */
  @Test
  public void embeddedListUnsupportedElementTypeThrowsDatabaseException() {
    var input = new ArrayList<Object>();
    input.add(new Object());
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.EMBEDDEDLIST.convert(input, null, null, session));
  }

  // ===========================================================================
  // EMBEDDEDSET
  // ===========================================================================

  @Test
  public void embeddedSetNullReturnsNull() {
    assertNull(PropertyTypeInternal.EMBEDDEDSET.convert(null, null, null, null));
  }

  /**
   * Already-tracked {@link EntityEmbeddedSetImpl} is returned as identity — same tracked-
   * collection contract as the EMBEDDEDLIST arm.
   */
  @Test
  public void embeddedSetAlreadyTrackedReturnsIdentity() {
    @SuppressWarnings("unchecked")
    var tracked = (EntityEmbeddedSetImpl<Object>) session.newEmbeddedSet();
    tracked.add("a");
    var result = PropertyTypeInternal.EMBEDDEDSET.convert(tracked, null, null, session);
    assertSame(tracked, result);
  }

  /** Empty {@link Set} converts to an empty embedded set. */
  @Test
  public void embeddedSetEmptyCollectionReturnsEmptySet() {
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        Collections.emptySet(), null, null, session);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /** Multi-element {@link Set} preserves membership (order is not part of the Set contract). */
  @Test
  public void embeddedSetMultiElementCollectionPreservesMembership() {
    var input = new HashSet<String>();
    input.add("a");
    input.add("b");
    input.add("c");
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        input, null, null, session);
    assertEquals(3, result.size());
    assertTrue(result.containsAll(input));
  }

  /**
   * A non-Set {@link java.util.Collection} (here a {@link List} with duplicates) collapses to a
   * Set — verify the Collection arm routes correctly and the duplicates are deduplicated.
   */
  @Test
  public void embeddedSetCollectionDeduplicates() {
    var input = List.of("x", "x", "y");
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        input, null, null, session);
    assertEquals(2, result.size());
    assertTrue(result.contains("x"));
    assertTrue(result.contains("y"));
  }

  /**
   * A null element inside the Collection lands in the embedded set as a single null entry
   * (distinct from "no entry"). Pin the round-trip contract.
   */
  @Test
  public void embeddedSetCollectionWithNullElementKeepsNull() {
    var input = new ArrayList<String>();
    input.add("a");
    input.add(null);
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        input, null, null, session);
    assertEquals(2, result.size());
    assertTrue(result.contains("a"));
    assertTrue(result.contains(null));
  }

  /**
   * Generic {@link Iterable} (non-Collection) routes through the dedicated {@code Iterable}
   * arm with an unsized {@code newEmbeddedSet()}.
   */
  @Test
  public void embeddedSetGenericIterableConvertsToSet() {
    Iterable<String> iterable = () -> List.of("p", "q").iterator();
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        iterable, null, null, session);
    assertEquals(2, result.size());
    assertTrue(result.contains("p"));
    assertTrue(result.contains("q"));
  }

  /** A bare {@code Iterator} routes through its own arm. */
  @Test
  public void embeddedSetBareIteratorConvertsToSet() {
    var iterator = List.of("u", "v").iterator();
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        iterator, null, null, session);
    assertEquals(2, result.size());
    assertTrue(result.contains("u"));
    assertTrue(result.contains("v"));
  }

  /**
   * The {@code default ->} arm wraps a non-collection scalar in a singleton embedded set. Same
   * type-by-value lookup as the EMBEDDEDLIST default arm; STRING resolves cleanly.
   */
  @Test
  public void embeddedSetNonCollectionScalarValueWrapsInSingletonSet() {
    @SuppressWarnings("unchecked")
    var result = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        "scalar", null, null, session);
    assertEquals(1, result.size());
    assertTrue(result.contains("scalar"));
  }

  /**
   * Unsupported element type inside a Collection throws via the helper's type-by-value lookup
   * — same contract as EMBEDDEDLIST.
   */
  @Test
  public void embeddedSetUnsupportedElementTypeThrowsDatabaseException() {
    var input = new HashSet<Object>();
    input.add(new Object());
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.EMBEDDEDSET.convert(input, null, null, session));
  }

  // ===========================================================================
  // EMBEDDEDMAP
  // ===========================================================================

  @Test
  public void embeddedMapNullReturnsNull() {
    assertNull(PropertyTypeInternal.EMBEDDEDMAP.convert(null, null, null, null));
  }

  /**
   * Already-tracked {@link EntityEmbeddedMapImpl} is returned as identity — preserves the
   * tracker for in-place serialization.
   */
  @Test
  public void embeddedMapAlreadyTrackedReturnsIdentity() {
    @SuppressWarnings("unchecked")
    var tracked = (EntityEmbeddedMapImpl<Object>) session.newEmbeddedMap();
    tracked.put("k", "v");
    var result = PropertyTypeInternal.EMBEDDEDMAP.convert(tracked, null, null, session);
    assertSame(tracked, result);
  }

  /**
   * Empty {@link Map} routes through the {@code Map} arm to a fresh empty embedded map. Verify
   * both the size-zero contract and the helper's no-op behaviour for empty entries.
   */
  @Test
  public void embeddedMapEmptyMapReturnsEmptyEmbeddedMap() {
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        Collections.emptyMap(), null, null, session);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /**
   * Multi-entry {@link Map} preserves keys (stringified via {@code entry.getKey().toString()})
   * and values. The String → String case is the canonical happy path; the
   * {@code Integer→toString()} key conversion is verified by a row with a non-String key.
   */
  @Test
  public void embeddedMapMapPreservesEntries() {
    var input = new LinkedHashMap<String, Object>();
    input.put("a", "alpha");
    input.put("b", 7);
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        input, null, null, session);
    assertEquals(2, result.size());
    assertEquals("alpha", result.get("a"));
    assertEquals(Integer.valueOf(7), result.get("b"));
  }

  /**
   * Non-String keys are stringified via {@code entry.getKey().toString()} — pin the contract
   * so a future tightening to require String keys at the convert site is a deliberate, visible
   * event.
   */
  @Test
  public void embeddedMapMapWithIntegerKeyStringifiesKey() {
    var input = new HashMap<Integer, String>();
    input.put(42, "v");
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        input, null, null, session);
    assertEquals(1, result.size());
    assertEquals("v", result.get("42"));
  }

  /**
   * Map with a null value preserves null — the helper's null short-circuit fires before any
   * type lookup is attempted.
   */
  @Test
  public void embeddedMapMapWithNullValueKeepsNull() {
    var input = new HashMap<String, Object>();
    input.put("k", null);
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        input, null, null, session);
    assertEquals(1, result.size());
    assertTrue(result.containsKey("k"));
    assertNull(result.get("k"));
  }

  /**
   * The {@code Iterable} arm of EMBEDDEDMAP accepts an iterable of {@code Map.Entry<String,?>}
   * (where the value is not an {@code Identifiable} — that case is handled by LINKMAP). Each
   * entry is added directly via {@code embeddedMap.put(key, value)}.
   */
  @Test
  public void embeddedMapIterableOfMapEntryAddsEachEntry() {
    Iterable<Map.Entry<String, Object>> entries = List.of(
        new AbstractMap.SimpleEntry<>("a", "alpha"),
        new AbstractMap.SimpleEntry<>("b", "beta"));
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        entries, null, null, session);
    assertEquals(2, result.size());
    assertEquals("alpha", result.get("a"));
    assertEquals("beta", result.get("b"));
  }

  /**
   * The {@code Iterable} arm also accepts an iterable of nested {@code Map<String,?>} — each
   * inner map is bulk-merged via {@code embeddedMap.putAll(map)}.
   */
  @Test
  public void embeddedMapIterableOfMapBulkMergesEachInnerMap() {
    Iterable<Map<String, Object>> maps = List.of(
        Map.of("a", "alpha"),
        Map.of("b", "beta"));
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        maps, null, null, session);
    assertEquals(2, result.size());
    assertEquals("alpha", result.get("a"));
    assertEquals("beta", result.get("b"));
  }

  /**
   * The {@code Iterable} arm rejects an element that is neither a {@code Map<String,?>} nor a
   * {@code Map.Entry<String, non-Identifiable>} — the {@code else throw} branch fires. Sentinel
   * {@code Object} satisfies neither shape.
   */
  @Test
  public void embeddedMapIterableOfWrongElementTypeThrowsDatabaseException() {
    Iterable<Object> bad = List.of(new Object());
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.EMBEDDEDMAP.convert(bad, null, null, session));
  }

  /**
   * The {@code default ->} arm wraps a non-Map, non-Iterable, non-Result value under the
   * synthetic key {@code "value"}. Pin the contract — many SQL projection paths produce a
   * single scalar that is wrapped this way before serialization.
   */
  @Test
  public void embeddedMapNonMapScalarValueWrapsUnderValueKey() {
    // Use a primitive (non-Iterable) wrapper to avoid colliding with the Iterable arm.
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        Integer.valueOf(99), null, null, session);
    assertEquals(1, result.size());
    assertEquals(Integer.valueOf(99), result.get("value"));
  }

  /**
   * Cross-arm sanity: an {@link Object} array — which is an {@code Iterable} when reflected
   * via {@code Arrays.asList(...)} — is supported only via the {@code Iterable<Map.Entry>}
   * branch when each element is itself an entry. Verify with a 2-entry array of
   * {@link AbstractMap.SimpleEntry}.
   */
  @Test
  public void embeddedMapArrayOfEntriesAddsEachEntry() {
    @SuppressWarnings("unchecked")
    Map.Entry<String, Object>[] entries = new Map.Entry[] {
        new AbstractMap.SimpleEntry<>("a", "alpha"),
        new AbstractMap.SimpleEntry<>("b", "beta")};
    // Array-of-entries is wrapped via Arrays.asList so it routes through the Iterable arm.
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        Arrays.asList(entries), null, null, session);
    assertEquals(2, result.size());
    assertEquals("alpha", result.get("a"));
    assertEquals("beta", result.get("b"));
  }

  // ===========================================================================
  // Cross-arm sanity check
  // ===========================================================================

  /**
   * Set→Set→Set null-arm crossing: every collection arm short-circuits null at the top of the
   * switch. Pin the three nulls in one test so a regression in the null-arm of any single
   * collection family is caught regardless of which arm is exercised first.
   */
  @Test
  public void allEmbeddedCollectionArmsShortCircuitNullToNull() {
    assertNull(PropertyTypeInternal.EMBEDDEDLIST.convert(null, null, null, session));
    assertNull(PropertyTypeInternal.EMBEDDEDSET.convert(null, null, null, session));
    assertNull(PropertyTypeInternal.EMBEDDEDMAP.convert(null, null, null, session));
  }

  /**
   * Empty inputs across the three arms return non-null empty collections (not null). Pin the
   * non-null contract — many call sites (validation, serialization) depend on
   * {@code result != null && result.isEmpty()} rather than on a null sentinel.
   */
  @Test
  public void allEmbeddedCollectionArmsReturnEmptyNotNullForEmptyInput() {
    @SuppressWarnings("unchecked")
    var emptyList = (List<Object>) PropertyTypeInternal.EMBEDDEDLIST.convert(
        Collections.emptyList(), null, null, session);
    @SuppressWarnings("unchecked")
    var emptySet = (Set<Object>) PropertyTypeInternal.EMBEDDEDSET.convert(
        Collections.emptySet(), null, null, session);
    @SuppressWarnings("unchecked")
    var emptyMap = (Map<String, Object>) PropertyTypeInternal.EMBEDDEDMAP.convert(
        Collections.emptyMap(), null, null, session);

    assertNotNull(emptyList);
    assertNotNull(emptySet);
    assertNotNull(emptyMap);
    assertTrue(emptyList.isEmpty());
    assertTrue(emptySet.isEmpty());
    assertTrue(emptyMap.isEmpty());
  }
}
