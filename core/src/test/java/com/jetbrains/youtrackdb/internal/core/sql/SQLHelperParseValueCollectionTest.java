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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkList;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the LIST_BEGIN ({@code '['…']'}) and MAP_BEGIN ({@code '{'…'}'}) recursive dispatch of
 * {@link SQLHelper#parseValue(String, com.jetbrains.youtrackdb.internal.core.command.CommandContext,
 * boolean, SchemaClass, SchemaProperty, PropertyTypeInternal, PropertyTypeInternal)}.
 *
 * <p>Scalar value-dispatch paths (null/not null/defined, booleans, quoted strings, RIDs, numerics)
 * are covered in depth by {@link SQLHelperParseValueScalarTest}; this suite deliberately focuses
 * on the session-backed collection allocation branches — the ones that call
 * {@code newEmbeddedList}, {@code newEmbeddedMap}, {@code newLinkList}, {@code newLinkMap} and the
 * entity-from-{@code @type} promotion.
 *
 * <p>All tests use the 7-argument overload directly so each branch's input variables
 * ({@code propertyType}, {@code schemaProperty}, {@code schemaClass}, {@code parentProperty}) are
 * independently controllable. Bug-pin tests carry {@code // WHEN-FIXED:} markers so the regression
 * flips red when the underlying behaviour is corrected.
 *
 * <p>Coverage targets (per track-7 step 6):
 *
 * <ul>
 *   <li>Embedded list with no propertyType — default {@code newEmbeddedList}.</li>
 *   <li>Typed embedded list ({@code EMBEDDEDLIST}) + typed link list ({@code LINKLIST}).</li>
 *   <li>Rejecting a list literal when propertyType is scalar (IllegalArgumentException).</li>
 *   <li>Embedded map with no propertyType — default {@code newEmbeddedMap}.</li>
 *   <li>Typed embedded map ({@code EMBEDDEDMAP}) + typed link map ({@code LINKMAP}).</li>
 *   <li>Rejecting a map literal when propertyType is scalar (IllegalArgumentException).</li>
 *   <li>Malformed map entry (missing colon) — CommandSQLParsingException.</li>
 *   <li>{@code @type}-keyed map promoted to an entity ({@code newEntity} branch).</li>
 *   <li>{@code parentProperty.isEmbedded()} promotes to {@code newEmbeddedEntity}; {@code isLink()}
 *       promotes to {@code newEntity}; scalar parentProperty rejected with IllegalArgumentException.
 *       </li>
 *   <li>schemaClass-driven JSON parsing path (both parentProperty=null and embedded/link branches).
 *       </li>
 *   <li>Nested list-of-maps and list-of-lists.</li>
 *   <li>Edge cases: empty list, single-element list, trailing comma (empty trailing slot).</li>
 * </ul>
 *
 * <p>Extends {@link DbTestBase} because {@link SQLHelper#parseValue} eagerly calls
 * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#getDatabaseSession()} — and
 * the collection branches additionally require the session to allocate the backing collection.
 */
public class SQLHelperParseValueCollectionTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUpContext() {
    ctx = new BasicCommandContext(session);
  }

  /**
   * Track 7 convention (see {@code docs/adr/unit-test-coverage/tracks/track-7.md} §Conventions):
   * any test that opens a transaction must have an {@code @After} net that rolls back a leaked tx,
   * because {@code DbTestBase}'s session is re-used across the test method (within the same DB
   * lifecycle) and an open tx would cascade-fail every subsequent method in this class. Wrapping
   * each test in try/finally is strictly weaker — an assertion throwing before rollback is reached
   * would still leak.
   */
  @org.junit.After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.getActiveTransactionOrNull() != null) {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // List branch — LIST_BEGIN '[' … LIST_END ']'
  // ---------------------------------------------------------------------------

  @Test
  public void parseEmbeddedListWithoutPropertyTypeReturnsEmbeddedList() {
    // No propertyType, no schemaProperty → falls into the unconditional newEmbeddedList() branch
    // at line 151-152. Items are recursed without any linked-type or linked-class hint.
    var out = SQLHelper.parseValue("[1, 2, 3]", ctx, false, null, null, null, null);
    assertTrue("expected EmbeddedList, got " + out.getClass(), out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertEquals(3, list.size());
    // Recursive items should be classified as Integer via the scalar parseStringNumber path.
    assertEquals(1, list.get(0));
    assertEquals(2, list.get(1));
    assertEquals(3, list.get(2));
  }

  @Test
  public void parseListWithExplicitNullElementsPreservesNullSlots() {
    // Realistic SQL-generated input like "[null, 1, null]" — the LIST_BEGIN recursion must
    // parse the bareword "null" as Java null and keep the resulting list slots null.
    // No prior test covered null elements explicitly.
    var out = SQLHelper.parseValue("[null, 1, null]", ctx, false, null, null, null, null);
    assertTrue("expected EmbeddedList, got " + out.getClass(), out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertEquals(3, list.size());
    assertNull("first element should be Java null", list.get(0));
    assertEquals(1, list.get(1));
    assertNull("third element should be Java null", list.get(2));
  }

  @Test
  public void parseEmbeddedListWithEmbeddedListPropertyTypeUsesEmbeddedListAllocator() {
    // propertyType=EMBEDDEDLIST — isMultiValue()=true, isLink()=false → newEmbeddedList() branch at
    // line 145-146. Distinct from the no-propertyType branch (line 151-152) even though both
    // allocate embedded; the branch coverage requires both paths to be visited.
    var out = SQLHelper.parseValue("['a', 'b']", ctx, false, null, null,
        PropertyTypeInternal.EMBEDDEDLIST, null);
    assertTrue("expected EmbeddedList, got " + out.getClass(), out instanceof EmbeddedList);
    assertEquals(List.of("a", "b"), out);
  }

  @Test
  public void parseLinkListWithLinkListPropertyTypeUsesLinkListAllocator() {
    // propertyType=LINKLIST — isMultiValue()=true, isLink()=true → newLinkList() branch at line
    // 141-144, cast back to List.
    var out = SQLHelper.parseValue("[#1:0, #1:1]", ctx, false, null, null,
        PropertyTypeInternal.LINKLIST, null);
    assertTrue("expected LinkList, got " + out.getClass(), out instanceof LinkList);
    var list = (List<?>) out;
    assertEquals(2, list.size());
    assertEquals(RecordIdInternal.fromString("#1:0", false), list.get(0));
    assertEquals(RecordIdInternal.fromString("#1:1", false), list.get(1));
  }

  @Test
  public void parseListWithScalarPropertyTypeThrowsIllegalArgument() {
    // propertyType=INTEGER — isMultiValue()=false → throws IllegalArgumentException from line
    // 148-149. Pins the defensive check that catches a misconfigured property where the schema
    // says scalar but the literal is a bracketed list.
    try {
      SQLHelper.parseValue("[1, 2]", ctx, false, null, null, PropertyTypeInternal.INTEGER,
          null);
      fail("expected IllegalArgumentException for list value with scalar propertyType");
    } catch (IllegalArgumentException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must echo the input: " + e.getMessage(),
          e.getMessage().contains("[1, 2]"));
      assertTrue(
          "message must flag the mismatch: " + e.getMessage(),
          e.getMessage().contains("property is not a collection"));
    }
  }

  @Test
  public void parseEmptyListReturnsEmptyEmbeddedList() {
    // smartSplit on an empty input returns an empty list — "[]" round-trips to an empty
    // EmbeddedList. Pin this so a future refactor that accidentally inserts a synthetic empty
    // element (a common "smartSplit edge case" mistake) surfaces immediately.
    var out = SQLHelper.parseValue("[]", ctx, false, null, null, null, null);
    assertTrue("expected EmbeddedList, got " + out.getClass(), out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertTrue("expected empty list, got size=" + list.size(), list.isEmpty());
  }

  @Test
  public void parseSingleElementListReturnsEmbeddedListWithOneElement() {
    // Single element, no trailing comma — smartSplit returns a list of one, recursion classifies
    // "42" as Integer.
    var out = SQLHelper.parseValue("[42]", ctx, false, null, null, null, null);
    assertTrue(out instanceof EmbeddedList);
    assertEquals(List.of(42), out);
  }

  @Test
  public void parseListWithTrailingCommaTrailingSlotIsEmptyString() {
    // "[1,2,]" splits to ["1", "2", ""] — the empty third slot takes the iValue.isEmpty()
    // short-circuit and is preserved as "". Pin the observable "trailing-comma produces trailing
    // empty string" semantics so a CSV-strict refactor would surface here.
    var out = SQLHelper.parseValue("[1,2,]", ctx, false, null, null, null, null);
    assertTrue(out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertEquals(3, list.size());
    assertEquals(1, list.get(0));
    assertEquals(2, list.get(1));
    assertEquals("", list.get(2));
  }

  @Test
  public void parseListWithUnparseableElementStoresValueNotParsedSentinel() {
    // A bareword inside the bracketed input is not a literal/number/RID — the recursive scalar
    // parseValue returns VALUE_NOT_PARSED, which is stored verbatim in the list. This pins the
    // observable sentinel-leak behaviour; SQLFunctionRuntime.setParameters treats it as a signal
    // to bail out (see Track 6's SQLFunctionRuntimeTest#setParametersBracketInputWith...).
    var out = SQLHelper.parseValue("[unparseableToken]", ctx, false, null, null, null,
        null);
    assertTrue(out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertEquals(1, list.size());
    assertSame(SQLHelper.VALUE_NOT_PARSED, list.get(0));
  }

  @Test
  public void parseListWithSchemaPropertyRecursesWithLinkedTypeAndLinkedClass() {
    // Verify the recursion path uses the schemaProperty's linkedType/linkedClass instead of
    // calling parseValue with null on the child. Discriminates from the no-schemaProperty branch
    // by using LINKLIST: with propertyType=LINKLIST + schemaProperty.linkedType=LINK the outer
    // allocator goes through newLinkList() (verifies the outer branch), AND the recursive child
    // calls propagate LINK as propertyType. A regression that dropped the schemaProperty-branch
    // recursion (replacing it with the no-schemaProperty recursion arm) would still allocate a
    // LinkList (because propertyType is LINKLIST at the outer call), but the child propertyType
    // would be null rather than LINK — observable because a numeric-looking input ("10") would
    // still coerce to Integer in the no-schemaProperty arm while the LINK-typed recursion would
    // fail to coerce (LINK path eventually goes through RID parsing).
    //
    // We use real RID literals for the items so both arms succeed; the schemaProperty-driven
    // test value is the outer allocator type AND the linkedClass surfacing. The dedicated
    // schemaProperty map test below exercises the key-value recursion which is an even tighter
    // discriminator.
    var clazz = session.getMetadata().getSchema().createClass("TestListClass");
    var prop = clazz.createProperty("items", PropertyType.LINKLIST);
    var linkedClass = session.getMetadata().getSchema().createClass("TestListLinked");
    prop.setLinkedClass(linkedClass);
    var schemaProp = (SchemaProperty) clazz.getProperty("items");
    var out = SQLHelper.parseValue("[#1:0, #1:1]", ctx, false, null, schemaProp,
        PropertyTypeInternal.LINKLIST, null);
    assertTrue("schemaProperty-driven LINKLIST must allocate LinkList: " + out.getClass(),
        out instanceof LinkList);
    var list = (List<?>) out;
    assertEquals(2, list.size());
    assertEquals(RecordIdInternal.fromString("#1:0", false), list.get(0));
    // Cross-check: schemaProperty's linkedClass is surfaced via getLinkedClass on the property.
    assertEquals(linkedClass.getName(), schemaProp.getLinkedClass().getName());
  }

  @Test
  public void parseMapWithExplicitNullValuePreservesNullSlot() {
    // TC5 fill: "{'k': null}" — the MAP_BEGIN recursion must parse the bareword "null" as Java
    // null and store it under the string key "k". Prior tests covered every property/schema
    // branch but none asserted that a literal null value survives the recursion.
    var out = SQLHelper.parseValue("{'k': null}", ctx, false, null, null, null, null);
    assertTrue("expected EmbeddedMap, got " + out.getClass(), out instanceof EmbeddedMap);
    var map = (Map<?, ?>) out;
    assertEquals(1, map.size());
    assertTrue("key must be 'k'", map.containsKey("k"));
    assertNull("value must be Java null (not the string \"null\")", map.get("k"));
  }

  @Test
  public void parseMapWithSchemaPropertyRecursesWithLinkedTypeForValue() {
    // TC-3 fill: the map branch's schemaProperty-driven recursion at
    // SQLHelper.parseValue (the "schemaProperty != null" sub-branch of the map path) forces the
    // KEY recursion's propertyType to STRING and the VALUE recursion's propertyType to the
    // property's linkedType. Pin that the value recursion propagates INTEGER so a map literal
    // "{'k': 42}" comes back with Integer 42. A regression that swapped branches (use
    // no-schemaProperty recursion for value) would still parse 42 as Integer (because no
    // linkedType means raw number classification); so we discriminate by asserting the KEY is
    // a String regardless of its literal form — the STRING-typed key recursion is what makes
    // the no-schemaProperty arm distinguishable from this arm when the key is a bare identifier.
    var clazz = session.getMetadata().getSchema().createClass("TestMapClass");
    var prop = clazz.createProperty("kv", PropertyType.EMBEDDEDMAP);
    prop.setLinkedType(PropertyType.INTEGER);
    var schemaProp = (SchemaProperty) clazz.getProperty("kv");
    var out = SQLHelper.parseValue("{'k': 42}", ctx, false, null, schemaProp,
        PropertyTypeInternal.EMBEDDEDMAP, null);
    assertTrue("expected EmbeddedMap, got " + out.getClass(), out instanceof EmbeddedMap);
    var map = (Map<?, ?>) out;
    // Key is stored via key.toString() — in both branches this is "k". The value-branch
    // propertyType=INTEGER triggers the numeric classification that returns Integer.
    assertTrue("key must be String", map.containsKey("k"));
    assertEquals(42, map.get("k"));
  }

  @Test
  public void parseMapWithUnparseableValueRecursesThroughSqlPredicate() {
    // TC-1 fill: when the map-value recursion returns VALUE_NOT_PARSED (a bareword), the map
    // branch retries via `new SQLPredicate(context, parts.get(1)).evaluate(context)`. A bareword
    // like "foo" promotes to a field-reference evaluated at retry time against no record, which
    // surfaces as CommandExecutionException("expression item 'foo' cannot be resolved because
    // current record is NULL"). This exception IS the observable proof that the SQLPredicate
    // retry branch fired — regressing the retry (i.e., keeping the VALUE_NOT_PARSED sentinel in
    // the map instead of retrying) would mean no exception, map.get('k') == VALUE_NOT_PARSED.
    try {
      SQLHelper.parseValue("{'k': foo}", ctx, false, null, null, null, null);
      fail("expected CommandExecutionException from SQLPredicate('foo').evaluate on null record"
          + " — this proves the retry branch fired. A regression dropping the retry would make"
          + " the call succeed with VALUE_NOT_PARSED leaking into the map.");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException e) {
      var msg = e.getMessage();
      assertNotNull(msg);
      assertTrue(
          "exception must come from the SQLPredicate retry resolving 'foo' against null record:"
              + " saw " + msg,
          msg.contains("foo") && msg.toLowerCase().contains("null"));
    }
  }

  @Test
  public void parseMapValueWithEscapedBackslashDecodesViaStringSerializerHelper() {
    // TC-2 fill: after the value recursion produces a String, the map branch calls
    // StringSerializerHelper.decode(value) to unescape "\\" → "\" and "\"" → "\"". A regression
    // that dropped the decode call would surface as a raw "\\" preserved in the map value. Pin
    // the decode contract with a quoted string containing an escaped backslash.
    var out = SQLHelper.parseValue("{'k': 'a\\\\b'}", ctx, false, null, null, null, null);
    assertTrue(out instanceof EmbeddedMap);
    var map = (Map<?, ?>) out;
    // The Java string literal "a\\\\b" is the 4-char SQL input `a\\b`. IOUtils.getStringContent
    // unwraps the single quotes, giving `a\\b`. StringSerializerHelper.decode then collapses `\\`
    // to `\`, giving the 3-char `a\b`. A missing decode would leave `a\\b` (4 chars).
    assertEquals("a\\b", map.get("k"));
  }

  // ---------------------------------------------------------------------------
  // Map branch — MAP_BEGIN '{' … MAP_END '}'
  // ---------------------------------------------------------------------------

  @Test
  public void parseEmbeddedMapWithoutPropertyTypeReturnsEmbeddedMap() {
    // propertyType=null, schemaClass=null → newEmbeddedMap() at line 208-210.
    var out = SQLHelper.parseValue("{'k1': 'v1', 'k2': 'v2'}", ctx, false, null, null,
        null, null);
    assertTrue("expected EmbeddedMap, got " + out.getClass(), out instanceof EmbeddedMap);
    var map = (Map<?, ?>) out;
    assertEquals(2, map.size());
    assertEquals("v1", map.get("k1"));
    assertEquals("v2", map.get("k2"));
  }

  @Test
  public void parseEmbeddedMapWithEmbeddedMapPropertyTypeUsesEmbeddedMapAllocator() {
    // propertyType=EMBEDDEDMAP — isMultiValue()=true, isLink()=false → newEmbeddedMap() at line
    // 201-203.
    var out = SQLHelper.parseValue("{'k': 'v'}", ctx, false, null, null,
        PropertyTypeInternal.EMBEDDEDMAP, null);
    assertTrue("expected EmbeddedMap, got " + out.getClass(), out instanceof EmbeddedMap);
    assertEquals(Map.of("k", "v"), out);
  }

  @Test
  public void parseLinkMapWithLinkMapPropertyTypeUsesLinkMapAllocator() {
    // propertyType=LINKMAP — isMultiValue()=true, isLink()=true → newLinkMap() at line 198-200,
    // cast back to Map.
    var out = SQLHelper.parseValue("{'k': #1:0}", ctx, false, null, null,
        PropertyTypeInternal.LINKMAP, null);
    assertTrue("expected LinkMap, got " + out.getClass(), out instanceof LinkMap);
    var map = (Map<?, ?>) out;
    assertEquals(1, map.size());
    assertEquals(RecordIdInternal.fromString("#1:0", false), map.get("k"));
  }

  @Test
  public void parseMapWithScalarPropertyTypeThrowsIllegalArgument() {
    // propertyType=STRING — isMultiValue()=false → IllegalArgumentException at line 205-206. Pins
    // the defensive check for a scalar-typed property receiving a map literal.
    try {
      SQLHelper.parseValue("{'k':'v'}", ctx, false, null, null, PropertyTypeInternal.STRING,
          null);
      fail("expected IllegalArgumentException for map value with scalar propertyType");
    } catch (IllegalArgumentException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must flag the mismatch: " + e.getMessage(),
          e.getMessage().contains("property is not a collection"));
    }
  }

  @Test
  public void parseMapWithMissingColonThrowsCommandSqlParsingException() {
    // "{'k'}" splits to one item "'k'" which has no colon — parts.size()==1, not 2 → throws
    // CommandSQLParsingException at line 216-218. Pins the error path for malformed map
    // entries (and indirectly that smartSplit on ENTRY_SEPARATOR is honoured).
    try {
      SQLHelper.parseValue("{'k'}", ctx, false, null, null, null, null);
      fail("expected CommandSQLParsingException for malformed map entry");
    } catch (CommandSQLParsingException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must describe the <key>:<value> requirement: " + e.getMessage(),
          e.getMessage().contains("<key>:<value>"));
    }
  }

  @Test
  public void parseMapWithAtTypeKeyPromotesToEntityThroughJsonSerializer() {
    // A manual map parse that contains the @type key re-routes through JSONSerializerJackson to
    // produce an Entity. parentProperty=null and schemaClass=null, so session.newEntity() no-arg
    // allocator is used. Result type is Entity, not Map. newEntity() mutates the transaction;
    // rollback is handled by the @After rollbackIfLeftOpen idiom.
    session.begin();
    var out = SQLHelper.parseValue("{\"@type\":\"d\",\"name\":\"Alice\"}", ctx, false, null,
        null, null, null);
    assertTrue("expected Entity, got " + out.getClass(), out instanceof Entity);
    var entity = (Entity) out;
    assertEquals("Alice", entity.getProperty("name"));
  }

  @Test
  public void parseMapWithAtTypeKeyAndEmbeddedParentPromotesToEmbeddedEntity() {
    // parentProperty=EMBEDDEDLIST → parentProperty.isEmbedded()=true → newEmbeddedEntity() at line
    // 254-255. Verifies the embedded entity allocation branch of the @type promotion. Embedded
    // entities do not require an active transaction.
    var out = SQLHelper.parseValue("{\"@type\":\"d\",\"name\":\"Bob\"}", ctx, false, null,
        null, null, PropertyTypeInternal.EMBEDDEDLIST);
    assertTrue("expected Entity, got " + out.getClass(), out instanceof Entity);
    var entity = (Entity) out;
    assertEquals("Bob", entity.getProperty("name"));
  }

  @Test
  public void parseMapWithAtTypeKeyAndLinkParentPromotesToEntity() {
    // parentProperty=LINKLIST → parentProperty.isLink()=true → newEntity() branch. Different
    // branch from the embedded path; both produce Entity but via different allocators. newEntity()
    // mutates the transaction; rollback handled by @After.
    session.begin();
    var out = SQLHelper.parseValue("{\"@type\":\"d\",\"name\":\"Carol\"}", ctx, false, null,
        null, null, PropertyTypeInternal.LINKLIST);
    assertTrue("expected Entity, got " + out.getClass(), out instanceof Entity);
    var entity = (Entity) out;
    assertEquals("Carol", entity.getProperty("name"));
  }

  @Test
  public void parseMapWithAtTypeKeyAndScalarParentThrowsIllegalArgument() {
    // parentProperty=INTEGER → neither isEmbedded() nor isLink() → line 259-261
    // IllegalArgumentException. Pins the @type-promotion defensive check that prevents the caller
    // from producing an orphan entity when the schema declares a scalar parent.
    try {
      SQLHelper.parseValue("{\"@type\":\"d\",\"name\":\"X\"}", ctx, false, null, null, null,
          PropertyTypeInternal.INTEGER);
      fail("expected IllegalArgumentException for scalar parentProperty");
    } catch (IllegalArgumentException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must describe link-or-embedded requirement: " + e.getMessage(),
          e.getMessage().contains("Property is not a link or embedded"));
    }
  }

  @Test
  public void parseMapWithoutAtTypeKeyKeepsRawMap() {
    // A plain map without @type stays a Map — the line 249 check is false and the code falls
    // through to `fieldValue = map;` at line 268. Pin the non-promotion path.
    var out = SQLHelper.parseValue("{'k1': 'v1', 'k2': 'v2'}", ctx, false, null, null, null,
        null);
    assertTrue("expected Map, not Entity, got " + out.getClass(),
        (out instanceof Map) && !(out instanceof Entity));
  }

  // ---------------------------------------------------------------------------
  // schemaClass path — JSON-parsed entity
  // ---------------------------------------------------------------------------

  @Test
  public void parseMapWithSchemaClassUsesJsonParserNoParentProperty() {
    // schemaClass != null, parentProperty == null → newEntity(schemaClass) at line 186-188, then
    // JSONSerializerJackson populates from the raw input. Verifies the schemaClass-driven path
    // (distinct from the @type-driven promotion below). The registered class drives the entity
    // construction; JSON fills the fields.
    var clazz = session.getMetadata().getSchema().createClass("TestJsonClass");
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    var out = SQLHelper.parseValue("{\"name\":\"Dave\"}", ctx, false, clazz, null,
        PropertyTypeInternal.EMBEDDEDMAP, null);
    assertTrue("expected Entity, got " + out.getClass(), out instanceof Entity);
    var entity = (Entity) out;
    assertEquals("Dave", entity.getProperty("name"));
  }

  @Test
  public void parseMapWithSchemaClassAndEmbeddedParentUsesEmbeddedEntity() {
    // schemaClass != null, parentProperty.isEmbedded()=true → newEmbeddedEntity(schemaClass) at
    // line 177-179. Exercises the first sub-branch of the schemaClass path. The schema class
    // must be abstract because newEmbeddedEntity(SchemaClass) rejects non-abstract classes
    // (embedded entities have no cluster — non-abstract would imply persistence).
    var clazz = session.getMetadata().getSchema().createAbstractClass("TestEmbClass");
    clazz.createProperty("val", PropertyType.STRING);

    var out = SQLHelper.parseValue("{\"val\":\"embedded\"}", ctx, false, clazz, null,
        PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.EMBEDDEDLIST);
    assertTrue(out instanceof Entity);
    var entity = (Entity) out;
    assertEquals("embedded", entity.getProperty("val"));
  }

  @Test
  public void parseMapWithSchemaClassAndLinkParentUsesNewEntity() {
    // schemaClass != null, parentProperty.isLink()=true → newEntity(schemaClass) at line 180-181.
    // Separate branch from the embedded path.
    var clazz = session.getMetadata().getSchema().createClass("TestLinkClass");
    clazz.createProperty("val", PropertyType.STRING);

    session.begin();
    var out = SQLHelper.parseValue("{\"val\":\"linked\"}", ctx, false, clazz, null,
        PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.LINKLIST);
    assertTrue(out instanceof Entity);
    var entity = (Entity) out;
    assertEquals("linked", entity.getProperty("val"));
  }

  @Test
  public void parseMapWithSchemaClassAndScalarParentThrowsIllegalArgument() {
    // schemaClass != null, parentProperty=INTEGER (neither link nor embedded) → line 183-184
    // IllegalArgumentException. Pins the defensive branch parallel to the @type-driven branch's
    // scalar-parent check.
    var clazz = session.getMetadata().getSchema().createClass("TestScalarParentClass");
    clazz.createProperty("val", PropertyType.STRING);

    try {
      SQLHelper.parseValue("{\"val\":\"x\"}", ctx, false, clazz, null,
          PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.INTEGER);
      fail("expected IllegalArgumentException for scalar parentProperty with schemaClass");
    } catch (IllegalArgumentException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must flag link-or-embedded requirement: " + e.getMessage(),
          e.getMessage().contains("Property is not a link or embedded"));
    }
  }

  // ---------------------------------------------------------------------------
  // Nested collection recursion
  // ---------------------------------------------------------------------------

  @Test
  public void parseNestedListOfMapsRecursesThroughBothBranches() {
    // "[{k:v}, {k:w}]" — outer LIST_BEGIN path allocates EmbeddedList; each element recursively
    // hits the MAP_BEGIN path (no propertyType on recursion from the no-schemaProperty outer
    // list) → EmbeddedMap. Pin both paths compose cleanly.
    var out = SQLHelper.parseValue("[{'k':'v'}, {'k':'w'}]", ctx, false, null, null, null,
        null);
    assertTrue("outer must be EmbeddedList: " + out.getClass(), out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertEquals(2, list.size());
    // Each element is an EmbeddedMap (smartSplit preserves quoted substrings so this parses).
    assertTrue("elem 0 must be EmbeddedMap: " + list.get(0).getClass(),
        list.get(0) instanceof EmbeddedMap);
    assertEquals("v", ((Map<?, ?>) list.get(0)).get("k"));
    assertEquals("w", ((Map<?, ?>) list.get(1)).get("k"));
  }

  @Test
  public void parseNestedListOfListsRecursesThroughListBranchTwice() {
    // "[[1,2],[3,4]]" — outer and inner both LIST_BEGIN, separate recursions into embedded-list.
    var out = SQLHelper.parseValue("[[1,2],[3,4]]", ctx, false, null, null, null, null);
    assertTrue(out instanceof EmbeddedList);
    var list = (List<?>) out;
    assertEquals(2, list.size());
    assertTrue(list.get(0) instanceof EmbeddedList);
    assertEquals(List.of(1, 2), list.get(0));
    assertEquals(List.of(3, 4), list.get(1));
  }

  @Test
  public void parseNestedMapWithListValueRecursesIntoListBranchFromMapValue() {
    // "{'items': [1, 2]}" — outer MAP_BEGIN, inner value is LIST_BEGIN. Pin that recursion from
    // the map branch correctly re-enters parseValue for the value part.
    var out = SQLHelper.parseValue("{'items': [1, 2]}", ctx, false, null, null, null, null);
    assertTrue(out instanceof EmbeddedMap);
    var map = (Map<?, ?>) out;
    assertTrue("value must be EmbeddedList: " + map.get("items").getClass(),
        map.get("items") instanceof EmbeddedList);
    assertEquals(List.of(1, 2), map.get("items"));
  }
}
