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
package com.jetbrains.youtrackdb.internal.core.command.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link PolyglotScriptBinding}, the JSR-223 {@link javax.script.Bindings} adapter that
 * wraps a GraalVM polyglot {@link org.graalvm.polyglot.Value} so existing JSR-223 code can read and
 * write script-engine members through a familiar {@link Map}-like API.
 *
 * <p>Tests are standalone (no {@link com.jetbrains.youtrackdb.internal.DbTestBase}) because the
 * class depends only on a GraalVM {@link Context} — no database session is touched. Each test
 * creates a fresh JavaScript Context in {@code @Before} and closes it in {@code @After} so state
 * never leaks between tests.
 *
 * <p>Two of the wrapper's methods are deliberately incomplete: {@link
 * PolyglotScriptBinding#entrySet()} returns {@code null} and {@link
 * PolyglotScriptBinding#containsValue(Object)} always returns {@code false}. Both are pinned here
 * as observable shape so a future completion visibly breaks the tests.
 */
public class PolyglotScriptBindingTest {

  private Context ctx;
  private PolyglotScriptBinding bindings;

  @Before
  public void createContext() {
    // A minimal JavaScript polyglot context with no host class lookup. HostAccess.ALL is
    // required so the Value.putMember(...) path accepts arbitrary Java objects; tests that
    // read bindings back never actually invoke host methods.
    ctx =
        Context.newBuilder("js")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(name -> false)
            .build();
    bindings = new PolyglotScriptBinding(ctx.getBindings("js"));
  }

  @After
  public void closeContext() {
    if (ctx != null) {
      ctx.close();
    }
  }

  // ==========================================================================
  // put / get — add a member, read it back; "put" returns the previous value
  // (null on first insert).
  // ==========================================================================

  /**
   * {@code put(name, value)} on a fresh context returns {@code null} (no previous value —
   * GraalVM's {@code Value.getMember} returns null for missing members) and makes the member
   * readable via {@code get(name)}. Pins the "first write" contract.
   *
   * <p>Note: {@code get(name)} returns a polyglot {@link Value} wrapping the stored Java
   * integer, not the Java Integer directly. This reflects the wrapper's implementation — it
   * delegates to {@code Value.getMember} which always returns a Value. Tests assert via
   * {@link Value#asInt()} to observe the underlying Java value.
   */
  @Test
  public void putReturnsNullOnFirstInsertAndGetReturnsStoredValue() {
    final var prev = bindings.put("answer", 42);
    assertNull("first put on a fresh context must return null (no previous value)", prev);

    final var got = (Value) bindings.get("answer");
    assertNotNull("get on a member that was just put must return a non-null Value", got);
    assertEquals(42, got.asInt());
  }

  /**
   * {@code put(name, value)} must return the previous value when the member is already present.
   * The previous value is a polyglot {@link Value} wrapping the earlier Java object — pin the
   * unwrapped string identity via {@link Value#asString()}.
   */
  @Test
  public void putReturnsPreviousValueOnOverwrite() {
    bindings.put("k", "old");
    final var prev = (Value) bindings.put("k", "new");
    assertNotNull("put on an existing member must return a non-null Value", prev);
    assertEquals("previous Value must wrap the earlier string", "old", prev.asString());
    assertEquals("new", ((Value) bindings.get("k")).asString());
  }

  /**
   * {@code get(name)} on a missing member must return {@code null}, never throw. Pins the
   * read-missing-is-null contract that JSR-223 consumers depend on.
   */
  @Test
  public void getMissingMemberReturnsNull() {
    assertNull("get on a missing member must return null", bindings.get("nope"));
  }

  // ==========================================================================
  // containsKey — delegates to Value.hasMember(key.toString()).
  // ==========================================================================

  /**
   * {@code containsKey(name)} must return {@code true} for a member added via {@code put(...)}
   * and {@code false} for a member that was never added. Pins the lookup contract used by
   * JSR-223 code to check for bindings before calling {@code get}.
   */
  @Test
  public void containsKeyReflectsMemberPresence() {
    bindings.put("present", 1);
    assertTrue(bindings.containsKey("present"));
    assertFalse(bindings.containsKey("missing"));
  }

  // ==========================================================================
  // size / isEmpty — member count and hasMembers() observability.
  // ==========================================================================

  /**
   * A fresh GraalVM JS context has built-in globals (e.g. globalThis, Object, Array), so
   * {@code size()} is strictly positive on an empty wrapper. After a user {@code put(...)} the
   * size must grow by exactly one.
   */
  @Test
  public void sizeGrowsByOneOnEachUniquePut() {
    final var baseline = bindings.size();
    bindings.put("a", 1);
    assertEquals(baseline + 1, bindings.size());
    bindings.put("b", 2);
    assertEquals(baseline + 2, bindings.size());
  }

  /**
   * {@code isEmpty()} reflects GraalVM's {@code Value.hasMembers()} — a fresh JS context has
   * built-in globals, so {@code isEmpty()} is false even before the wrapper is touched. Pins
   * the wrapper's negation-of-hasMembers contract.
   */
  @Test
  public void isEmptyFalseOnFreshContextWithBuiltInGlobals() {
    assertFalse("JavaScript bindings expose built-in globals (Object, Array, ...)",
        bindings.isEmpty());
  }

  // ==========================================================================
  // keySet / values — iterate over the Value's member names and their values.
  // ==========================================================================

  /**
   * {@code keySet()} must contain every user-added key alongside any built-in globals. Pins
   * that wrapper-added keys appear in the key set (exact equality is avoided because the
   * built-in global set is engine-dependent).
   */
  @Test
  public void keySetContainsAllUserAddedKeys() {
    bindings.put("first", 1);
    bindings.put("second", 2);
    final var keys = bindings.keySet();
    assertNotNull(keys);
    assertTrue("keySet must contain 'first'", keys.contains("first"));
    assertTrue("keySet must contain 'second'", keys.contains("second"));
  }

  /**
   * {@code values()} must iterate over every member and return a non-null Java value for each
   * (via {@code Value.getMember} on each name). Pins the one-to-one size correspondence with
   * {@code keySet}.
   */
  @Test
  public void valuesSizeMatchesKeySetSize() {
    bindings.put("x", 10);
    bindings.put("y", 20);
    final var values = bindings.values();
    assertEquals(
        "values().size() must equal keySet().size()",
        bindings.keySet().size(),
        values.size());
  }

  // ==========================================================================
  // putAll — merge a Map into the bindings.
  // ==========================================================================

  /**
   * {@code putAll(map)} must forward every entry to {@code putMember}. After the call each
   * entry is visible through {@code get}. Pins the per-entry delegation contract. Each
   * returned value is a polyglot {@link Value} wrapping the stored integer — assert via
   * {@link Value#asInt()}.
   */
  @Test
  public void putAllForwardsEveryEntryToPutMember() {
    final Map<String, Object> toMerge = new HashMap<>();
    toMerge.put("one", 1);
    toMerge.put("two", 2);
    toMerge.put("three", 3);

    bindings.putAll(toMerge);

    assertEquals(1, ((Value) bindings.get("one")).asInt());
    assertEquals(2, ((Value) bindings.get("two")).asInt());
    assertEquals(3, ((Value) bindings.get("three")).asInt());
  }

  // ==========================================================================
  // remove — removes a member and returns the previous value.
  // ==========================================================================

  /**
   * {@code remove(key)} must remove the member, return the previous value, and leave
   * {@code containsKey} false thereafter. Pins the atomic "read-then-delete" contract JSR-223
   * consumers rely on. The returned previous value is a polyglot {@link Value} wrapping the
   * stored string.
   */
  @Test
  public void removeReturnsPreviousValueAndEvictsTheMember() {
    bindings.put("victim", "alive");
    final var prev = (Value) bindings.remove("victim");
    assertNotNull(prev);
    assertEquals("alive", prev.asString());
    assertFalse(bindings.containsKey("victim"));
    assertNull("member must be gone after remove", bindings.get("victim"));
  }

  // ==========================================================================
  // clear — remove every member, leaving the wrapper empty of user entries.
  // ==========================================================================

  /**
   * {@code clear()} must remove every member — including built-in globals — because it
   * iterates over {@code getMemberKeys()} and calls {@code removeMember(...)} for each. After
   * clearing, user keys must be absent; {@code isEmpty()} may still report false if the
   * engine cannot fully remove built-ins. Pins that user keys are provably gone.
   */
  @Test
  public void clearRemovesUserAddedKeys() {
    bindings.put("user1", "a");
    bindings.put("user2", "b");

    bindings.clear();

    assertFalse("user1 must be removed after clear()", bindings.containsKey("user1"));
    assertFalse("user2 must be removed after clear()", bindings.containsKey("user2"));
  }

  // ==========================================================================
  // Incomplete wrapper methods — pinned as observable shape.
  // ==========================================================================

  /**
   * {@link PolyglotScriptBinding#entrySet()} currently returns {@code null} (the wrapper does
   * not implement entry iteration). Pin the observable shape — a future completion will flip
   * this test and force a deliberate review. WHEN-FIXED: Track 22 — implement entrySet and
   * update this assertion to the returned set's size contract.
   */
  @Test
  public void entrySetReturnsNullAsShapePin() {
    bindings.put("x", 1);
    assertNull(
        "entrySet() is intentionally incomplete today; a non-null return is a deliberate change",
        bindings.entrySet());
  }

  /**
   * {@link PolyglotScriptBinding#containsValue(Object)} unconditionally returns {@code false}.
   * Pin the observable shape. WHEN-FIXED: Track 22 — implement containsValue and replace this
   * with a positive assertion on {@code bindings.containsValue(someStoredValue)}.
   */
  @Test
  public void containsValueAlwaysReturnsFalseAsShapePin() {
    bindings.put("v", "stored");
    assertFalse(
        "containsValue is not implemented — must return false unconditionally",
        bindings.containsValue("stored"));
    assertFalse(
        "containsValue returns false even for an unstored sentinel",
        bindings.containsValue("not-stored"));
  }
}
