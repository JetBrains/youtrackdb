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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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
    // All entity loads/saves run inside a tx.
    session.begin();

    ctx = new BasicCommandContext(session);
  }

  @After
  public void rollbackIfLeftOpen() {
    // Carry-forward from Track 6: prevent a leaked tx from cascading to other methods in the class.
    if (session.getActiveTransaction().isActive()) {
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
  public void starFieldNameReturnsCandidateUnchanged() {
    // "*" is the pass-through — the method returns ioResult unchanged (after any prior branch
    // mutations). For a plain entity, that means the entity itself comes back.
    var entity = session.newInstance("Person");
    entity.setProperty("name", "Alice");

    var result = method.execute(null, null, ctx, entity, new Object[] {"*"});

    assertSame(entity, result);
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
    var list = new java.util.ArrayList<>(out);
    assertEquals(Arrays.asList("Alice", "Bob", "Carol"), list);
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
  public void malformedRidStringLogsErrorAndYieldsNull() {
    // Production code wraps the RID parse in try/catch and returns null on failure. A random
    // string that does not start with '#' cannot be parsed as a RID → method swallows the
    // exception, sets ioResult to null, and returns null from the final if-guard.
    var out = method.execute(null, null, ctx, "not-a-rid", new Object[] {"name"});

    assertNull(out);
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
    assertTrue("evaluateParameters must stay false for field()",
        !method.evaluateParameters());
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
