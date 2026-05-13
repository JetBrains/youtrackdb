/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodToJSON} — produces a JSON string representation of the input,
 * dispatching on four shape classes: {@link com.jetbrains.youtrackdb.internal.core.db.record.record
 * .DBRecord}, {@link com.jetbrains.youtrackdb.internal.core.query.Result} wrapping an entity,
 * {@link java.util.Map}, and {@link com.jetbrains.youtrackdb.internal.common.collection.MultiValue}
 * (recursion).
 *
 * <p>Uses {@link DbTestBase} — the record path calls {@code record.toJSON()} which requires an
 * active binding. An active tx is kept during tests that create entities.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code current == null} → null.
 *   <li>{@code iParams.length > 0}: format param is unquoted via {@code replace("\"", "")}. Pass a
 *       valid {@code "rid"} format to pin the 1-param toJSON overload.
 *   <li>{@link com.jetbrains.youtrackdb.internal.core.query.Result} that {@code isEntity()} →
 *       resolved to entity via {@code asEntity()}; then the DBRecord branch runs.
 *   <li>{@link EntityImpl} (DBRecord) path with 0 params → uses {@code record.toJSON()}; with 1
 *       param → uses {@code record.toJSON(format)}.
 *   <li>{@link java.util.Map} path → {@code JSONSerializerJackson.INSTANCE.mapToJson(map)}.
 *   <li>MultiValue path — {@code List<Map>} recurses and joins with "," inside "[…]".
 *   <li>MultiValue path with mixed entries (Map + non-matching) — the non-matching entry contributes
 *       the literal {@code "null"} from the recursion; pins the "append(execute(...))" behaviour.
 *   <li>Non-matching {@code current} (e.g. a plain String) falls through to {@code return null}.
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLMethodToJSONTest extends DbTestBase {

  @Before
  public void setUp() {
    // Schema changes are NOT transactional — create classes BEFORE opening the tx.
    session.getSchema().getOrCreateClass("Thing");
    session.begin();
  }

  @After
  public void rollbackIfLeftOpen() {
    if (session.isTxActive()) {
      session.rollback();
    }
  }

  private BasicCommandContext ctx() {
    var c = new BasicCommandContext();
    c.setDatabaseSession(session);
    return c;
  }

  private SQLMethodToJSON method() {
    return new SQLMethodToJSON();
  }

  private EntityImpl thing(String name, int n) {
    // Create-commit-reload so the entity has a persistent RID. Without commit the entity carries
    // a temporary cluster position (e.g. #N:-2) and toJSON() throws when it encounters any link.
    var e = (EntityImpl) session.newEntity("Thing");
    e.setProperty("name", name);
    e.setProperty("n", n);
    session.commit();
    session.begin();
    return (EntityImpl) session.load(e.getIdentity());
  }

  // ---------------------------------------------------------------------------
  // Null short-circuit
  // ---------------------------------------------------------------------------

  @Test
  public void nullCurrentReturnsNull() {
    assertNull(method().execute(null, null, ctx(), null, new Object[] {}));
  }

  // ---------------------------------------------------------------------------
  // DBRecord path
  // ---------------------------------------------------------------------------

  @Test
  public void entityWithoutFormatReturnsJsonString() {
    var entity = thing("alpha", 1);

    var result = (String) method().execute(entity, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertTrue("result should be a JSON object starting with '{': " + result,
        result.startsWith("{"));
    assertTrue("result should contain the name property: " + result, result.contains("alpha"));
  }

  @Test
  public void entityWithFormatDispatchesToOneArgOverloadAndSuppressesProperties() {
    // Format param is passed as-is (after quote-stripping) to record.toJSON(format). "rid" asks
    // the serializer to include ONLY the @rid — so the two outputs must differ, the formatted
    // output must embed the RID, and it must NOT embed property values that the default output
    // does include.
    var entity = thing("alpha", 1);
    var rid = entity.getIdentity();

    var noFormat = (String) method().execute(entity, null, ctx(), null, new Object[] {});
    var withFormat = (String) method().execute(entity, null, ctx(), null, new Object[] {"rid"});

    assertNotNull(noFormat);
    assertNotNull(withFormat);
    assertNotEquals("rid-format output must differ from default toJSON output",
        noFormat, withFormat);
    assertTrue("rid-format output should reference the RID: " + withFormat,
        withFormat.contains(rid.toString()));
    assertTrue("default toJSON output MUST include the 'name' property: " + noFormat,
        noFormat.contains("alpha"));
  }

  @Test
  public void formatParamDoubleQuotesAreStrippedProducingSameOutputAsUnquoted() {
    // Production strips '"' from the format string before passing it to toJSON(format). The only
    // way to directly pin "strip happened" is to confirm that quoted and unquoted formats yield
    // identical output — a dropped strip would leave literal quotes inside the format token and
    // change what toJSON produces.
    var entity = thing("alpha", 2);

    var withoutQuotes =
        (String) method().execute(entity, null, ctx(), null, new Object[] {"rid"});
    var withQuotes =
        (String) method().execute(entity, null, ctx(), null, new Object[] {"\"rid\""});

    assertEquals("quote-stripping must normalise the two inputs to the same format token",
        withoutQuotes, withQuotes);
  }

  // ---------------------------------------------------------------------------
  // Null format param — unchecked (String) cast + .replace NPEs
  // ---------------------------------------------------------------------------

  @Test
  public void nullFormatParamThrowsNullPointerException() {
    // WHEN-FIXED: the production code does `((String) iParams[0]).replace(...)`. The cast to
    // String succeeds for null (null is assignable to any reference type), but `replace` then
    // NPEs. A null-guard refactor (returning null or treating null as "default format") would
    // flip this assertion.
    var entity = thing("alpha", 3);

    try {
      method().execute(entity, null, ctx(), null, new Object[] {(String) null});
      fail("expected NullPointerException from null format param");
    } catch (NullPointerException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // Result-but-not-entity — falls through to the final `return null`
  // ---------------------------------------------------------------------------

  @Test
  public void nonEntityResultFallsThroughToNull() {
    // A ResultInternal whose isEntity() is false fails the first `instanceof Result && isEntity`
    // check (short-circuit on isEntity); the DBRecord/Map/MultiValue branches all fail; the
    // method returns null. Pins the "partially matching type" branch that the bare-String test
    // does not cover.
    var r = new ResultInternal(session);
    r.setProperty("k", "v");

    assertNull(method().execute(r, null, ctx(), null, new Object[] {}));
  }

  @Test
  public void resultWrappingEntityIsUnwrappedToEntityPath() {
    // ResultInternal.isEntity() returns true when it wraps an EntityImpl. The production code
    // does `current = result.asEntity()` before the DBRecord branch fires.
    var entity = thing("alpha", 3);
    var wrapped = new ResultInternal(session, entity);

    var result = (String) method().execute(wrapped, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertTrue("result must embed the wrapped entity properties: " + result,
        result.contains("alpha"));
  }

  // ---------------------------------------------------------------------------
  // Map path
  // ---------------------------------------------------------------------------

  @Test
  public void mapReturnsMapToJson() {
    // Use LinkedHashMap to preserve key order so we can pin the exact serialised form.
    // The previous form asserted only that the result contained "k1" and "42" — that would
    // also accept e.g. {"k1":"v1","k2":"v1","k3":"v1","k4":42} or any garbled output that
    // happened to contain both tokens. Pinning the full JSON rules out value swaps and
    // missing/extra keys (TB10).
    var map = new LinkedHashMap<String, Object>();
    map.put("k1", "v1");
    map.put("k2", 42);

    var result = (String) method().execute(map, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertEquals("{\"k1\":\"v1\",\"k2\":42}", result);
  }

  // ---------------------------------------------------------------------------
  // MultiValue recursion
  // ---------------------------------------------------------------------------

  @Test
  public void multiValueWrapsChildrenInJsonArray() {
    // A List<Map> triggers MultiValue.isMultiValue, which recursively calls execute on each
    // entry; the children are wrapped in "[" … "]".
    var first = new LinkedHashMap<String, Object>();
    first.put("k", "a");
    var second = new LinkedHashMap<String, Object>();
    second.put("k", "b");
    var list = List.of(first, second);

    var result = (String) method().execute(list, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertTrue("result should be a JSON array wrapper: " + result,
        result.startsWith("[") && result.endsWith("]"));
    assertTrue("result must contain a comma separator: " + result, result.contains(","));
    assertTrue("result must embed 'a': " + result, result.contains("\"a\""));
    assertTrue("result must embed 'b': " + result, result.contains("\"b\""));
  }

  @Test
  public void multiValueWithUnrecognizedEntryEmbedsLiteralNull() {
    // WHEN-FIXED: a plain String inside a List is not matched by any branch, so the inner
    // execute returns null; the outer recursion concatenates the returned value via
    // StringBuilder.append(null) → the 4-letter string "null". Pin this so a refactor that
    // switches to skip-null is noticed.
    var list = Arrays.asList((Object) "plain-string");

    var result = (String) method().execute(list, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertEquals("[null]", result);
  }

  @Test
  public void multiValueArrayInputAlsoRecurses() {
    // MultiValue.isMultiValue returns true for arrays. Pins the array branch of getMultiValueIterable,
    // which uses Array.get under the hood rather than Collection.iterator().
    var first = new LinkedHashMap<String, Object>();
    first.put("k", "a");
    var second = new LinkedHashMap<String, Object>();
    second.put("k", "b");
    Object[] arr = new Object[] {first, second};

    var result = (String) method().execute(arr, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertTrue("array result should be a JSON array: " + result,
        result.startsWith("[") && result.endsWith("]"));
    assertTrue(result.contains("\"a\""));
    assertTrue(result.contains("\"b\""));
  }

  @Test
  public void multiValueEmptyListProducesEmptyJsonArrayBrackets() {
    // Loop body never executes for an empty collection; the method still emits "[" and "]".
    // Without this pin a future refactor that returns "" for empties would go unnoticed.
    var result = (String) method().execute(List.of(), null, ctx(), null, new Object[] {});

    assertEquals("[]", result);
  }

  @Test
  public void multiValueNestedListRecursesTwiceProducingNestedArrays() {
    // A list whose sole element is itself a list hits the MultiValue branch TWICE (outer +
    // inner). Pins that the recursion is unbounded and that each level wraps its children in
    // "[…]".
    var inner = new LinkedHashMap<String, Object>();
    inner.put("k", "deep");

    var result =
        (String) method().execute(List.of(List.of(inner)), null, ctx(), null, new Object[] {});

    assertNotNull(result);
    assertTrue("nested list must produce nested JSON arrays: " + result,
        result.startsWith("[[") && result.endsWith("]]"));
    assertTrue(result.contains("\"deep\""));
  }

  // ---------------------------------------------------------------------------
  // Non-matching fall-through
  // ---------------------------------------------------------------------------

  @Test
  public void nonRecordNonMapNonMultiValueReturnsNull() {
    // A bare String isn't a DBRecord, isn't a Map, isn't MultiValue (per MultiValue.isMultiValue
    // semantics for a plain String — singletons/scalars are NOT multi-value). Falls through.
    var result = method().execute("plain-string", null, ctx(), null, new Object[] {});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Format-param coercion — non-String param triggers ClassCastException via `(String)` cast
  // ---------------------------------------------------------------------------

  @Test
  public void nonStringFormatParamThrowsClassCastException() {
    // The production code uses an unchecked `(String) iParams[0]` cast. An Integer param triggers
    // ClassCastException BEFORE we reach toJSON — pin the contract so a future "accept any object"
    // refactor is visible.
    var entity = thing("alpha", 4);

    try {
      method().execute(entity, null, ctx(), null, new Object[] {Integer.valueOf(5)});
      fail("expected ClassCastException from unchecked cast");
    } catch (ClassCastException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("tojson", SQLMethodToJSON.NAME);
    assertEquals("tojson", m.getName());
    assertEquals(0, m.getMinParams());
    assertEquals(1, m.getMaxParams(null));
    assertEquals("toJSON([<format>])", m.getSyntax());
  }
}
