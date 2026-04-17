/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>field(&lt;name&gt;)</code> method ({@link SQLMethodField}).
 *
 * <p>The method takes a field name as {@code iParams[0]} and resolves it against the
 * {@code ioResult} candidate, which can be any of: an {@link com.jetbrains.youtrackdb.internal
 * .core.db.record.record.Identifiable}, a {@link com.jetbrains.youtrackdb.internal.core.query
 * .Result}, an {@link Iterable}/{@link Collection}/array, a {@code String} containing a RID,
 * a {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext} (for variable
 * lookup), or {@code null}.
 *
 * <p>Extends {@link DbTestBase} because the RID-load and Identifiable-load branches require
 * an active session and transaction; {@link SQLMethodField} also reaches into the
 * {@link com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#getFieldValue}
 * resolver which needs schema access.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iParams[0] == null} → method returns {@code null} without touching ioResult.</li>
 *   <li>String-named field on a stored entity → returns the property value.</li>
 *   <li>Missing field on a stored entity → returns {@code null} (no exception).</li>
 *   <li>{@code "*"} special field name → returns ioResult unchanged (short-circuit).</li>
 *   <li>Field-name comes from a {@link SQLFilterItemField} argument → the item's asString value
 *       is used as the field name, not the raw parameter object.</li>
 *   <li>Trailing/leading whitespace in a String field name is trimmed before lookup.</li>
 *   <li>{@code ioResult == null} → method returns {@code null} for the non-"*" branch.</li>
 *   <li>{@link ResultInternal} candidate that is an entity → unwrapped via
 *       {@link com.jetbrains.youtrackdb.internal.core.query.Result#asEntityOrNull()} then the
 *       field is read.</li>
 *   <li>{@link ResultInternal} candidate that is NOT an entity (plain projection) → passes
 *       through, and {@link com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#
 *       getFieldValue} reads from the result.</li>
 *   <li>Map candidate (not an entity or result) → the field name keys the map (EntityHelper
 *       delegates to map.get(key)).</li>
 *   <li>Collection candidate containing multiple entities → returns a {@code List} with each
 *       entity's field value, size matches the input (non-multi-value nested results are
 *       added scalar).</li>
 *   <li>Identifiable ({@link com.jetbrains.youtrackdb.internal.core.db.record.record.RID})
 *       candidate → loaded through the active transaction, then the field is read.</li>
 *   <li>String candidate that parses as a RID → loaded, then field is read.</li>
 *   <li>String candidate that is not a valid RID → method logs an error and returns
 *       {@code null} (the production code swallows the parse exception).</li>
 *   <li>{@link com.jetbrains.youtrackdb.internal.core.command.CommandContext} candidate →
 *       returns the variable value from the context (no schema access).</li>
 *   <li>{@link #evaluateParameters()} returns {@code false} (pins the configuredParameters
 *       contract so SQLMethodRuntime uses this method's name, not its value).</li>
 * </ul>
 */
public class SQLMethodFieldTest extends DbTestBase {

  private SQLMethodField method;
  private BasicCommandContext ctx;

  @Before
  public void setup() {
    method = new SQLMethodField();
    // Create schema OUTSIDE the transaction — schema changes are not transactional.
    var person = session.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("age", PropertyType.INTEGER);
    // Multi-value properties used by the collection-element dispatch tests (flatten + Map
    // keep-whole branches).
    person.createProperty("tags", PropertyType.EMBEDDEDLIST);
    person.createProperty("props", PropertyType.EMBEDDEDMAP);
    // All entity loads/saves run inside a tx.
    session.begin();

    ctx = new BasicCommandContext(session);
  }

  @After
  public void rollbackIfLeftOpen() {
    // Carry-forward from Track 6: prevent a leaked tx from cascading to other methods in the
    // class. Use getActiveTransactionOrNull() — not getActiveTransaction() — because the
    // latter throws DatabaseException when no tx is active, and in that case we have nothing
    // to roll back anyway.
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // iParams[0] == null — early return
  // ---------------------------------------------------------------------------

  @Test
  public void nullParamNameReturnsNull() {
    // Early-return guard: a null parameter yields null, skipping every resolver branch below.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");

    var result = method.execute(null, null, ctx, entity, new Object[] {null});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Plain field lookup on an entity
  // ---------------------------------------------------------------------------

  @Test
  public void stringFieldNameReturnsEntityProperty() {
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");

    var result = method.execute(null, null, ctx, entity, new Object[] {"name"});

    assertEquals("Alice", result);
  }

  @Test
  public void missingFieldReturnsNull() {
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");

    var result = method.execute(null, null, ctx, entity, new Object[] {"nonexistent"});

    assertNull(result);
  }

  @Test
  public void whitespaceAroundStringFieldNameIsTrimmed() {
    // The production code calls `.trim()` on the String param before lookup. Pin that guarantee
    // so a regression removing the trim is caught.
    var entity = session.newInstance("Person");
    entity.setProperty("age", 42);

    var result = method.execute(null, null, ctx, entity, new Object[] {"  age  "});

    assertEquals(42, result);
  }

  @Test
  public void starFieldNameOnMapReturnsCandidateUnchanged() {
    // "*" short-circuits the final EntityHelper.getFieldValue/getVariable call only.
    // We assert identity on a Map candidate because Map is not an Identifiable / Iterable /
    // String / Collection, so none of the earlier transform branches rewrite ioResult — the
    // Map reference therefore survives verbatim. Using an entity here would be fragile because
    // the Identifiable branch re-loads via the transaction and might return a different
    // reference for a technically-correct implementation.
    var candidate = new HashMap<String, Object>();
    candidate.put("name", "Alice");

    var result = method.execute(null, null, ctx, candidate, new Object[] {"*"});

    assertSame(candidate, result);
  }

  @Test
  public void starFieldNameWithNullIoResultReturnsNull() {
    // "*" + null ioResult must return null — the outer if-guard falls through without a
    // variable/field lookup.
    assertNull(method.execute(null, null, ctx, null, new Object[] {"*"}));
  }

  @Test
  public void starFieldNameWithCommandContextReturnsContextNotVariable() {
    // "*" must NOT be treated as a variable name on a CommandContext candidate. The outer
    // if-guard checks `!"*".equals(paramAsString)` BEFORE dispatching on CommandContext, so
    // the context itself passes through verbatim.
    var holder = new BasicCommandContext(session);
    holder.setVariable("x", "y");

    assertSame(holder, method.execute(null, null, ctx, holder, new Object[] {"*"}));
  }

  @Test
  public void nullIoResultReturnsNull() {
    // Non-"*" field name with null ioResult must return null (the final if-guard).
    var result = method.execute(null, null, ctx, null, new Object[] {"name"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // SQLFilterItemAbstract parameter path — asString(session) drives the field name
  // ---------------------------------------------------------------------------

  @Test
  public void sqlFilterItemParamSuppliesFieldNameViaAsString() {
    // When the first parameter is a SQLFilterItemAbstract, its asString(session) is taken as
    // the field name, not the raw Java object. This pins the alternative to the String path.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");
    var filterItem = new SQLFilterItemField(session, "name", null);

    var result = method.execute(null, null, ctx, entity, new Object[] {filterItem});

    assertEquals("Alice", result);
  }

  // ---------------------------------------------------------------------------
  // Result (projection vs entity) candidates
  // ---------------------------------------------------------------------------

  @Test
  public void resultWithEntityUnwrapsAndReadsField() {
    // A Result that wraps an entity is unwrapped via asEntityOrNull and the field is read from
    // the entity.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");
    var result = new ResultInternal(session, entity);

    var out = method.execute(null, null, ctx, result, new Object[] {"name"});

    assertEquals("Alice", out);
  }

  @Test
  public void resultProjectionReadsFieldViaEntityHelper() {
    // Pure projection Result (not entity-backed) — EntityHelper.getFieldValue reads the
    // property directly.
    var result = new ResultInternal(session);
    result.setProperty("name", "Foo");
    result.setProperty("age", 99);

    var out = method.execute(null, null, ctx, result, new Object[] {"age"});

    assertEquals(99, out);
  }

  // ---------------------------------------------------------------------------
  // Map candidate — EntityHelper delegates to map.get(key)
  // ---------------------------------------------------------------------------

  @Test
  public void mapCandidateIsKeyedByFieldName() {
    var map = new HashMap<String, Object>();
    map.put("key", "value");
    map.put("other", 123);

    var out = method.execute(null, null, ctx, map, new Object[] {"key"});

    assertEquals("value", out);
  }

  // ---------------------------------------------------------------------------
  // Collection / Iterator candidate — returns list of field values
  // ---------------------------------------------------------------------------

  @Test
  public void collectionCandidateReturnsListOfFieldValuesInOrder() {
    // Each element in the collection is resolved against the field name; the results are
    // returned as a List preserving encounter order.
    var a = session.newInstance("Person");
    a.setProperty("name", "Alice");
    var b = session.newInstance("Person");
    b.setProperty("name", "Bob");
    var c = session.newInstance("Person");
    c.setProperty("name", "Carol");
    List<?> input = Arrays.asList(a, b, c);

    @SuppressWarnings("unchecked")
    var out = (Collection<Object>) method.execute(null, null, ctx, input, new Object[] {"name"});

    assertNotNull(out);
    assertEquals(3, out.size());
    var list = new ArrayList<>(out);
    assertEquals(Arrays.asList("Alice", "Bob", "Carol"), list);
  }

  @Test
  public void emptyCollectionCandidateReturnsEmptyList() {
    // Boundary: empty input must return a non-null empty list (not null, not fall through to
    // EntityHelper.getFieldValue(emptyList, name)). The production code pre-sizes ArrayList
    // with MultiValue.getSize(ioResult) == 0 and leaves the loop un-entered.
    @SuppressWarnings("unchecked")
    var out = (Collection<Object>) method.execute(
        null, null, ctx, java.util.Collections.emptyList(), new Object[] {"name"});

    assertNotNull("empty-collection input must NOT return null", out);
    assertTrue("empty-collection input must return an empty list", out.isEmpty());
  }

  @Test
  public void arrayCandidateReturnsListOfFieldValuesInOrder() {
    // The multi-value expansion branch accepts Collection || Iterator || array. Arrays take
    // a separate `ioResult.getClass().isArray()` branch that is NOT covered by the Collection
    // test above. Pins the array dispatch explicitly.
    var a = session.newInstance("Person");
    a.setProperty("name", "Alice");
    var b = session.newInstance("Person");
    b.setProperty("name", "Bob");
    Object[] input = new Object[] {a, b};

    @SuppressWarnings("unchecked")
    var out = (Collection<Object>) method.execute(null, null, ctx, input, new Object[] {"name"});

    assertNotNull(out);
    assertEquals(Arrays.asList("Alice", "Bob"), new ArrayList<>(out));
  }

  @Test
  public void collectionElementWithListFieldFlattensIntoResult() {
    // When an element's field value is itself a multi-value (and NOT Map or Identifiable), the
    // inner loop flattens it into the outer result list. Pins the `else` branch at ~line 101
    // of SQLMethodField — a regression that treats every multi-value as "keep whole" would
    // return a List-of-Lists here.
    //
    // EntityImpl requires getOrCreateEmbeddedList() for EMBEDDEDLIST properties; setProperty
    // with a bare List rejects with IllegalArgumentException.
    var a = session.newInstance("Person");
    a.<String>getOrCreateEmbeddedList("tags").addAll(Arrays.asList("red", "blue"));
    var b = session.newInstance("Person");
    b.<String>getOrCreateEmbeddedList("tags").addAll(Arrays.asList("green"));
    List<?> input = Arrays.asList(a, b);

    @SuppressWarnings("unchecked")
    var out = (Collection<Object>) method.execute(null, null, ctx, input, new Object[] {"tags"});

    // Flattened: [red, blue, green], not [[red, blue], [green]].
    assertEquals(Arrays.asList("red", "blue", "green"), new ArrayList<>(out));
  }

  @Test
  public void collectionElementWithMapFieldKeepsMapAsSingleElement() {
    // When an element's field value is a Map, the inner branch preserves it intact instead of
    // iterating the entry-set. Pins the `instanceof Map || Identifiable` guard.
    var a = session.newInstance("Person");
    a.<String>getOrCreateEmbeddedMap("props").put("k", "v");
    List<?> input = Arrays.asList(a);

    @SuppressWarnings("unchecked")
    var out = (Collection<Object>) method.execute(null, null, ctx, input, new Object[] {"props"});

    assertEquals(1, out.size());
    var only = out.iterator().next();
    assertTrue("map element should be preserved whole, saw: " + only, only instanceof Map);
    assertEquals(Collections.singletonMap("k", "v"), only);
  }

  // ---------------------------------------------------------------------------
  // Identifiable candidate — load via active transaction
  // ---------------------------------------------------------------------------

  @Test
  public void identifiableCandidateIsLoadedThenFieldRead() {
    // Start with an entity, commit so it gets a persistent RID, then pass the RID (Identifiable)
    // as ioResult. The method loads via the active transaction and reads the field.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");
    session.commit();
    var rid = entity.getIdentity();

    // Re-open a tx for the load path to use.
    session.begin();
    var out = method.execute(null, null, ctx, rid, new Object[] {"name"});

    assertEquals("Alice", out);
  }

  @Test
  public void stringRidCandidateIsParsedAndLoadedThenFieldRead() {
    // ioResult = RID string; method parses RID, loads entity via active tx, reads field.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Bob");
    session.commit();
    var ridStr = entity.getIdentity().toString();

    session.begin();
    var out = method.execute(null, null, ctx, ridStr, new Object[] {"name"});

    assertEquals("Bob", out);
  }

  @Test
  public void malformedHashRidStringYieldsNullViaRidParseCatch() {
    // A string that starts with '#' definitely routes through the `ioResult instanceof String`
    // RID-parse branch. The parse fails because "bogus:xyz" is not a valid cluster:position
    // pair, the catch swallows the exception, and the method returns null. Using '#...' here
    // (rather than "not-a-rid") pins the parse-catch path specifically — a random string with
    // no '#' could theoretically reach EntityHelper.getFieldValue and also return null, making
    // the assertion ambiguous about which branch was exercised.
    var out = method.execute(null, null, ctx, "#bogus:xyz", new Object[] {"name"});

    assertNull(out);
  }

  @Test
  public void identifiableCandidateForDeletedRecordNpesDueToNullUnguardedIsArrayCheck() {
    // WHEN-FIXED: guard the `ioResult.getClass().isArray()` check at SQLMethodField line ~94
    // against a null ioResult (e.g., `ioResult != null && ioResult.getClass().isArray()`).
    // When the catch (RecordNotFoundException) at line 73-77 nulls ioResult, the subsequent
    // compound `else if (ioResult instanceof Collection<?> || ... || ioResult.getClass()
    // .isArray())` NPEs instead of falling through. The corrected behaviour should be: method
    // returns null (the intended outcome of the RecordNotFoundException catch).
    //
    // Until the fix lands, this test pins the latent NPE so a silent change in behaviour is
    // caught: if someone fixes the NPE without updating the test, this assertion will fail and
    // the marker guides them to flip it to `assertNull(out);`.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");
    session.commit();
    var rid = entity.getIdentity();
    session.begin();
    session.delete(session.load(rid));
    session.commit();

    session.begin();
    try {
      method.execute(null, null, ctx, rid, new Object[] {"name"});
      fail("Expected NullPointerException due to latent bug (WHEN-FIXED)");
    } catch (NullPointerException expected) {
      // Pin: production nulls ioResult in catch(RecordNotFoundException) then immediately
      // dereferences ioResult on the unguarded isArray() check. When fixed, method returns null.
      assertTrue(
          "NPE message should blame ioResult, saw: " + expected.getMessage(),
          expected.getMessage() == null || expected.getMessage().contains("ioResult"));
    }
  }

  // ---------------------------------------------------------------------------
  // CommandContext candidate — variable lookup
  // ---------------------------------------------------------------------------

  @Test
  public void commandContextCandidateReadsVariable() {
    // When ioResult is a CommandContext, the method treats the field name as a variable key
    // and reads from the context. This bypasses all EntityHelper logic.
    var holder = new BasicCommandContext(session);
    holder.setVariable("greeting", "hello");

    var out = method.execute(null, null, ctx, holder, new Object[] {"greeting"});

    assertEquals("hello", out);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void evaluateParametersReturnsFalse() {
    // Pins the SQLMethodRuntime contract: the method receives its parameters un-evaluated
    // (so "name" stays the literal string "name", not a looked-up value).
    assertFalse(
        "evaluateParameters must stay false for field() — SQLMethodRuntime relies on the raw "
            + "param being the field NAME, not its looked-up value",
        method.evaluateParameters());
  }

  @Test
  public void nameConstantIsField() {
    assertEquals("field", SQLMethodField.NAME);
    assertEquals(SQLMethodField.NAME, method.getName());
  }

  @Test
  public void minMaxParamsAreBothOne() {
    // SQLMethodField is constructed with super(NAME, 1, 1) — exactly one argument required.
    assertEquals(1, method.getMinParams());
    assertEquals(1, method.getMaxParams(session));
  }
}
