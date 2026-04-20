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
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Tests the {@link CommandParameters} class — the argument container used by SQL executors to
 * bind named ({@code :param}) and positional ({@code ?}) parameters.
 *
 * <p>The class uses two distinct key spaces that coexist in the same underlying {@link HashMap}:
 * Integer keys for positional parameters (read monotonically via {@link CommandParameters#getNext
 * ()}), and any {@code Object} key for named parameters (read by name via
 * {@link CommandParameters#getByName(Object)}). The map keeps both spaces together so iteration
 * order is not guaranteed — only count and per-key lookup are contractual.
 */
public class CommandParametersTest {

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  @Test
  public void defaultConstructorYieldsEmptyContainer() {
    // The no-arg constructor initialises an empty backing HashMap. size() must be 0 and getNext()
    // must fail because the counter starts at 0 but there are no parameters.
    var p = new CommandParameters();
    assertEquals(0, p.size());
    assertFalse(p.iterator().hasNext());
  }

  @Test
  public void mapConstructorPreservesEntries() {
    // When a non-null map is supplied, CommandParameters adopts it directly — size must match
    // and getByName must find the existing entries.
    Map<Object, Object> seed = new HashMap<>();
    seed.put(0, "first");
    seed.put("name", "alice");
    var p = new CommandParameters(seed);
    assertEquals(2, p.size());
    assertEquals("first", p.getByName(0));
    assertEquals("alice", p.getByName("name"));
  }

  @Test
  public void mapConstructorNullFallsBackToEmptyMap() {
    // Null input must be tolerated — the class allocates a new empty HashMap rather than
    // NPE'ing on access.
    var p = new CommandParameters(null);
    assertEquals(0, p.size());
    assertNull(p.getByName("anything"));
  }

  @Test
  public void mapConstructorAdoptsBackingMapByReference() {
    // The CommandParameters(Map) ctor uses the supplied map directly — subsequent mutations of
    // the original map are visible through CommandParameters. This is a pre-existing behaviour
    // pinned here so accidental defensive-copy would surface as a regression.
    // WHEN-FIXED: if CommandParameters switches to a defensive copy, this test will start to
    // fail and the pin should be removed with an updated assertion. Track 22 may revisit.
    Map<Object, Object> seed = new HashMap<>();
    var p = new CommandParameters(seed);
    assertEquals(0, p.size());
    seed.put("later", "added");
    assertEquals(1, p.size());
    assertEquals("added", p.getByName("later"));
  }

  // ---------------------------------------------------------------------------
  // set / getByName
  // ---------------------------------------------------------------------------

  @Test
  public void setStoresValueByKey() {
    var p = new CommandParameters();
    p.set("name", "alice");
    assertEquals("alice", p.getByName("name"));
    assertEquals(1, p.size());
  }

  @Test
  public void setOverwritesExistingKey() {
    // HashMap.put semantics — second put for same key replaces the value and leaves size
    // unchanged.
    var p = new CommandParameters();
    p.set("k", 1);
    p.set("k", 2);
    assertEquals(2, p.getByName("k"));
    assertEquals(1, p.size());
  }

  @Test
  public void setNullValueIsStoredAndRetrievable() {
    // HashMap accepts null values. getByName cannot distinguish "stored null" from "missing
    // key" through the return value alone — but size changes from 0 to 1.
    var p = new CommandParameters();
    p.set("k", null);
    assertEquals(1, p.size());
    assertNull(p.getByName("k"));
  }

  @Test
  public void getByNameReturnsNullForMissingKey() {
    // Missing keys return null. Same as the set-null-value case, the caller must use size() or
    // iterate to differentiate.
    assertNull(new CommandParameters().getByName("missing"));
  }

  // ---------------------------------------------------------------------------
  // getNext / reset / counter semantics
  // ---------------------------------------------------------------------------

  @Test
  public void getNextReturnsParametersByCounter() {
    // Positional parameters are keyed by Integer (0, 1, 2, ...). getNext returns parameters.get
    // (counter++) so successive calls produce the 0-th, 1-st, 2-nd entries in that order.
    var p = new CommandParameters();
    p.set(0, "a");
    p.set(1, "b");
    p.set(2, "c");
    assertEquals("a", p.getNext());
    assertEquals("b", p.getNext());
    assertEquals("c", p.getNext());
  }

  @Test
  public void getNextBeyondSizeThrowsIndexOutOfBoundsException() {
    // The exception branch fires when the counter has reached size(). The message should mention
    // the counter and the total parameters received — pin that substring so regressions surface.
    var p = new CommandParameters();
    p.set(0, "only");
    p.getNext();
    try {
      p.getNext();
      fail("expected IndexOutOfBoundsException");
    } catch (IndexOutOfBoundsException expected) {
      assertTrue(
          "message should mention counter position, got: " + expected.getMessage(),
          expected.getMessage().contains("1"));
    }
  }

  @Test
  public void getNextOnEmptyThrowsImmediately() {
    // Counter starts at 0, size() is 0 → the guard is (0 <= 0) which is true → throws.
    try {
      new CommandParameters().getNext();
      fail("expected IndexOutOfBoundsException");
    } catch (IndexOutOfBoundsException expected) {
      // OK — the exception guard fires on the very first call when the container is empty.
    }
  }

  @Test
  public void resetRestartsCounter() {
    // reset() sets counter back to 0 so a subsequent getNext() returns the 0-th entry again.
    // size() is unchanged — reset affects only the iteration counter.
    var p = new CommandParameters();
    p.set(0, "x");
    p.set(1, "y");
    assertEquals("x", p.getNext());
    assertEquals("y", p.getNext());
    p.reset();
    assertEquals(2, p.size());
    assertEquals("x", p.getNext());
  }

  @Test
  public void resetDoesNotRemoveEntries() {
    // reset is counter-only. The stored parameters remain accessible by their keys, both
    // positional and named.
    var p = new CommandParameters();
    p.set(0, "a");
    p.set("name", "alice");
    p.reset();
    assertEquals("a", p.getByName(0));
    assertEquals("alice", p.getByName("name"));
    assertEquals(2, p.size());
  }

  @Test
  public void getNextIgnoresNonIntegerKeysInSizeGuard() {
    // The guard uses parameters.size() — NOT the count of Integer-keyed entries. A named-only
    // container with one entry satisfies size() == 1, so the first getNext() call enters the
    // lookup branch but finds no Integer key 0 → returns null (HashMap.get of missing key).
    // This is a latent bug: callers expecting getNext() to iterate only positional parameters
    // will see null silently instead of a well-formed IndexOutOfBoundsException.
    // WHEN-FIXED: the size guard should count only Integer keys (or the class should separate
    // named vs positional storage). When fixed, this test will start returning OOB and the pin
    // should be updated. Track 22 candidate.
    var p = new CommandParameters();
    p.set("name", "alice");
    assertNull(p.getNext());
  }

  @Test
  public void mixedPositionalAndNamedCanCoexist() {
    // Shared HashMap keyed by Object — named and positional entries don't collide. getNext()
    // drives through the Integer keys while getByName() uses the string keys.
    var p = new CommandParameters();
    p.set(0, "pos0");
    p.set("name", "alice");
    p.set(1, "pos1");
    assertEquals("pos0", p.getNext());
    assertEquals("pos1", p.getNext());
    assertEquals("alice", p.getByName("name"));
  }

  // ---------------------------------------------------------------------------
  // iterator / size
  // ---------------------------------------------------------------------------

  @Test
  public void iteratorExposesEveryEntry() {
    // The iterator delegates to parameters.entrySet().iterator() so every entry — positional
    // and named — appears exactly once. HashMap iteration order is not guaranteed, so we
    // verify contents via a Set rather than an order-sensitive list.
    var p = new CommandParameters();
    p.set(0, "pos0");
    p.set("name", "alice");
    p.set(1, "pos1");

    Set<Object> seenKeys = new HashSet<>();
    Set<Object> seenValues = new HashSet<>();
    for (var entry : p) {
      seenKeys.add(entry.getKey());
      seenValues.add(entry.getValue());
    }
    assertEquals(Set.of(0, "name", 1), seenKeys);
    assertEquals(Set.of("pos0", "alice", "pos1"), seenValues);
  }

  @Test
  public void sizeTracksMapSize() {
    // size() is a simple delegate — sanity-check it at several mutation points.
    var p = new CommandParameters();
    assertEquals(0, p.size());
    p.set("a", 1);
    assertEquals(1, p.size());
    p.set("b", 2);
    assertEquals(2, p.size());
    p.set("a", 3); // overwrite — size unchanged
    assertEquals(2, p.size());
  }

  @Test
  public void iteratorOnEmptyContainerHasNoElements() {
    assertFalse(new CommandParameters().iterator().hasNext());
  }

  @Test
  public void getNextAfterSetOverwriteUsesCurrentValue() {
    // Overwriting the 0-th positional parameter before the first getNext should return the
    // new value — set is a plain HashMap.put.
    var p = new CommandParameters();
    p.set(0, "old");
    p.set(0, "new");
    assertEquals("new", p.getNext());
  }
}
