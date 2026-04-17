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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodInclude} — the {@code [include(...)]} SQL method, which copies an
 * entity / map / multi-value into a {@link Result} keeping only the named fields.
 *
 * <p>The execute() signature is {@code execute(iThis, iCurrentRecord, iContext, ioResult,
 * iParams)} — {@code iContext} is the third parameter per {@code AbstractSQLMethod}.
 *
 * <p>Uses {@link DbTestBase} because the Identifiable-loading path calls
 * {@code session.getActiveTransaction().load(...)}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iParams[0] == null} → null short-circuit (different from Exclude which tests
 *       {@code iThis == null}).
 *   <li>{@link com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable} iThis →
 *       transaction.load → EntityImpl branch.
 *   <li>{@link Result} {@code iThis} → {@code asEntityOrNull()}.
 *   <li>{@link EntityImpl} {@code iThis} + explicit field list.
 *   <li>Wildcard "prefix*" → includes all matching properties (and documents the wildcard-key
 *       quirk: all matches share the wildcard string as their key, not the matched property).
 *   <li>Null field names skipped.
 *   <li>{@link java.util.Map} {@code iThis} — literal and wildcard keys.
 *   <li>Multi-value (List) {@code iThis}: each Identifiable loaded + included, missing RIDs
 *       silently skipped.
 *   <li>RecordNotFoundException for Identifiable iThis → null.
 *   <li>Unrecognised {@code iThis} type (e.g. String) → null.
 * </ul>
 */
public class SQLMethodIncludeTest extends DbTestBase {

  @Before
  public void setUp() {
    // Schema changes are NOT transactional — create all classes we need BEFORE opening the tx.
    session.getSchema().getOrCreateClass("Person");
    session.getSchema().getOrCreateClass("P");
    session.getSchema().getOrCreateClass("Item");
    session.begin();
  }

  @After
  public void rollbackIfLeftOpen() {
    if (session.getActiveTransaction().isActive()) {
      session.rollback();
    }
  }

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  private EntityImpl personEntity() {
    var entity = (EntityImpl) session.newEntity("Person");
    entity.setProperty("firstName", "Alice");
    entity.setProperty("lastName", "Smith");
    entity.setProperty("age", 30);
    entity.setProperty("email", "alice@example.com");
    return entity;
  }

  // ---------------------------------------------------------------------------
  // Null-iParams[0] short-circuit
  // ---------------------------------------------------------------------------

  @Test
  public void nullFirstParamReturnsNull() {
    // Production guard: `if (iParams[0] != null)`. null first param short-circuits.
    var entity = personEntity();
    var method = new SQLMethodInclude();

    var result = method.execute(entity, null, ctx(), null, new Object[] {(Object) null});

    assertNull(result);
  }

  @Test
  public void unrecognizedIThisTypeReturnsNull() {
    // Non-Identifiable, non-Result, non-EntityImpl, non-Map, non-MultiValue → null.
    var method = new SQLMethodInclude();

    var result = method.execute("not-an-entity", null, ctx(), null, new Object[] {"firstName"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // EntityImpl happy path — keep only listed fields
  // ---------------------------------------------------------------------------

  @Test
  public void includeLiteralFieldKeepsOnlyThatField() {
    var entity = personEntity();
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(entity, null, ctx(), null, new Object[] {"firstName"});

    assertEquals("Alice", result.getProperty("firstName"));
    assertFalse(result.hasProperty("lastName"));
    assertFalse(result.hasProperty("email"));
    assertFalse(result.hasProperty("age"));
  }

  @Test
  public void includeMultipleFieldsKeepsAllListed() {
    var entity = personEntity();
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"firstName", "age"});

    assertEquals("Alice", result.getProperty("firstName"));
    assertEquals(30, (int) result.getProperty("age"));
    assertFalse(result.hasProperty("lastName"));
    assertFalse(result.hasProperty("email"));
  }

  @Test
  public void includeNonExistentFieldYieldsNullProperty() {
    // Non-existent fields are still set as null properties (entity.getProperty returns null →
    // result.setProperty(name, null)). Pin the observed behaviour.
    var entity = personEntity();
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"nonExistentField"});

    // hasProperty returns true for a property that was explicitly set to null. So we check the
    // value is null regardless of hasProperty semantics.
    assertNull("non-existent source field → null value",
        result.getProperty("nonExistentField"));
  }

  @Test
  public void nullFieldNameInListIsSkipped() {
    // iParams[0] must be non-null for the outer guard to pass; subsequent null entries are
    // skipped via the `if (iFieldName != null)` inner guard.
    var entity = personEntity();
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"firstName", null, "lastName"});

    assertEquals("Alice", result.getProperty("firstName"));
    assertEquals("Smith", result.getProperty("lastName"));
  }

  // ---------------------------------------------------------------------------
  // Wildcard field name quirk — all matches share the wildcard string as key
  // ---------------------------------------------------------------------------

  @Test
  public void wildcardFieldNameStoresLastMatchUnderWildcardKey() {
    // Production code for the wildcard branch (simplified):
    //   for (var f : toInclude) {
    //     result.setProperty(fieldName, entity.getProperty(f));   // <- uses fieldName (with '*')
    //   }
    // Every match overwrites the same "addr_*" key, leaving only the last iteration's value.
    // This is a latent bug — pin it with a WHEN-FIXED marker so a fix to store each match under
    // its actual name is noticed here.
    // WHEN-FIXED: change `result.setProperty(fieldName, ...)` to `result.setProperty(f, ...)` so
    // each matched property keeps its original name.
    var entity = (EntityImpl) session.newEntity("P");
    entity.setProperty("addr_street", "1 Main");
    entity.setProperty("addr_city", "Springfield");
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(entity, null, ctx(), null, new Object[] {"addr_*"});

    // The only property on the Result is keyed by the wildcard string "addr_*" — neither the
    // original property name "addr_street" nor "addr_city".
    assertFalse("original property name must not be kept under 'addr_street'",
        result.hasProperty("addr_street"));
    assertFalse("original property name must not be kept under 'addr_city'",
        result.hasProperty("addr_city"));
    assertTrue("result must have a property keyed literally as 'addr_*'",
        result.hasProperty("addr_*"));
  }

  // ---------------------------------------------------------------------------
  // Identifiable iThis — load via transaction
  // ---------------------------------------------------------------------------

  @Test
  public void identifiableIThisLoadsAndIncludes() {
    // An Identifiable that isn't a Result triggers `transaction.load(id)` which returns the
    // EntityImpl. The reload is then included-copied.
    var entity = personEntity();
    session.commit();
    var rid = (RecordIdInternal) entity.getIdentity();
    session.begin();
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(rid, null, ctx(), null, new Object[] {"firstName"});

    assertEquals("Alice", result.getProperty("firstName"));
    assertFalse(result.hasProperty("lastName"));
  }

  @Test
  public void missingRidReturnsNull() {
    var method = new SQLMethodInclude();
    RecordIdInternal missing = new RecordId(999, 999);

    var result = method.execute(missing, null, ctx(), null, new Object[] {"firstName"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Result iThis path — asEntityOrNull()
  // ---------------------------------------------------------------------------

  @Test
  public void resultIThisResolvesViaAsEntityOrNull() {
    var entity = personEntity();
    var wrapper = new ResultInternal(session, entity);
    var method = new SQLMethodInclude();

    var result = (Result) method.execute(wrapper, null, ctx(), null, new Object[] {"firstName"});

    assertEquals("Alice", result.getProperty("firstName"));
    assertFalse(result.hasProperty("lastName"));
  }

  // ---------------------------------------------------------------------------
  // Map iThis path
  // ---------------------------------------------------------------------------

  @Test
  public void mapIThisIncludesLiteralKey() {
    var method = new SQLMethodInclude();
    var map = new LinkedHashMap<String, Object>();
    map.put("a", 1);
    map.put("b", 2);

    var result = (Result) method.execute(map, null, ctx(), null, new Object[] {"a"});

    assertEquals(1, (int) result.getProperty("a"));
    assertFalse(result.hasProperty("b"));
  }

  @Test
  public void mapIThisWildcardKeyStoresUnderWildcardString() {
    // Same quirk as for EntityImpl: wildcard matches all overwrite the same wildcard key.
    // WHEN-FIXED: change `entity.setProperty(fieldName, ...)` to `entity.setProperty(f, ...)`.
    var method = new SQLMethodInclude();
    var map = new LinkedHashMap<String, Object>();
    map.put("addr_street", "1 Main");
    map.put("addr_city", "Springfield");

    var result = (Result) method.execute(map, null, ctx(), null, new Object[] {"addr_*"});

    assertFalse(result.hasProperty("addr_street"));
    assertFalse(result.hasProperty("addr_city"));
    assertTrue(result.hasProperty("addr_*"));
  }

  @Test
  public void mapIThisEmptyFieldNameIsSkipped() {
    // The production code guards wildcard parsing with `fieldName.charAt(fieldName.length()-1)`
    // only if fieldName is non-empty. An empty iFieldName is not null, so the guard
    // `!fieldName.isEmpty()` avoids an AIOOBE and falls into the plain-name branch — which
    // copies `map.get("")`.
    var method = new SQLMethodInclude();
    var map = new LinkedHashMap<String, Object>();
    map.put("a", 1);

    var result = (Result) method.execute(map, null, ctx(), null, new Object[] {""});

    // Reads map.get("") → null → stored as null under key "". No exception is thrown.
    assertNotNull(result);
  }

  // ---------------------------------------------------------------------------
  // Multi-value iThis path
  // ---------------------------------------------------------------------------

  @Test
  public void multiValueIThisIncludesFieldFromEachEntity() {
    var first = (EntityImpl) session.newEntity("Item");
    first.setProperty("a", 1);
    first.setProperty("b", 2);

    var second = (EntityImpl) session.newEntity("Item");
    second.setProperty("a", 10);
    second.setProperty("b", 20);
    session.commit();
    session.begin();

    var method = new SQLMethodInclude();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(List.of(first.getIdentity(), second.getIdentity()),
        null, ctx(), null, new Object[] {"a"});

    assertEquals(2, result.size());
    assertEquals(1, (int) ((Result) result.get(0)).getProperty("a"));
    assertFalse(((Result) result.get(0)).hasProperty("b"));
    assertEquals(10, (int) ((Result) result.get(1)).getProperty("a"));
  }

  @Test
  public void multiValueIThisSilentlySkipsMissingRids() {
    var entity = personEntity();
    session.commit();
    var goodRid = entity.getIdentity();
    session.begin();
    var method = new SQLMethodInclude();
    RecordIdInternal missing = new RecordId(999, 999);

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(List.of(goodRid, missing), null, ctx(), null,
        new Object[] {"firstName"});

    assertEquals(1, result.size());
  }

  @Test
  public void multiValueIThisWithNonIdentifiableEntriesAreSkipped() {
    var method = new SQLMethodInclude();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(List.of("x", 1), null, ctx(), null,
        new Object[] {"firstName"});

    assertEquals(0, result.size());
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Test
  public void nameAndSyntaxMatchMethodContract() {
    var method = new SQLMethodInclude();

    assertEquals("include", SQLMethodInclude.NAME);
    assertEquals(SQLMethodInclude.NAME, method.getName());
    assertEquals("Syntax error: include([<field-name>][,]*)", method.getSyntax());
    assertEquals(1, method.getMinParams());
    assertEquals(-1, method.getMaxParams(session));
  }
}
