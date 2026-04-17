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
 * Tests for {@link SQLMethodExclude} — the {@code [exclude(...)]} SQL method, which copies an
 * entity / map / multi-value into a {@link Result} without the named fields.
 *
 * <p>The execute() signature is {@code execute(iThis, iCurrentRecord, iContext, ioResult,
 * iParams)} — {@code iContext} is the third parameter per {@code AbstractSQLMethod}.
 *
 * <p>Uses {@link DbTestBase} because the RID-loading path calls
 * {@code session.getActiveTransaction().load(rid)}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → null short-circuit.
 *   <li>{@link RecordIdInternal} {@code iThis} → load via transaction, then exclude (happy + RNF).
 *   <li>{@link Result} {@code iThis} → {@code asEntity()} then exclude.
 *   <li>{@link EntityImpl} {@code iThis} + explicit field name list.
 *   <li>Wildcard field name ("prefix*") removes every property whose name starts with prefix.
 *   <li>Null field names skipped.
 *   <li>{@link java.util.Map} {@code iThis} with both literal and wildcard keys.
 *   <li>Multi-value (List) {@code iThis} with Identifiable entries — RNF inside the loop is
 *       silently skipped.
 *   <li>Non-EntityImpl / non-Map / non-MultiValue {@code iThis} (e.g. a String) returns null.
 * </ul>
 */
public class SQLMethodExcludeTest extends DbTestBase {

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
    // Schema was created in @Before outside the tx — we're inside a tx here.
    var entity = (EntityImpl) session.newEntity("Person");
    entity.setProperty("firstName", "Alice");
    entity.setProperty("lastName", "Smith");
    entity.setProperty("age", 30);
    entity.setProperty("email", "alice@example.com");
    return entity;
  }

  // ---------------------------------------------------------------------------
  // Null / unknown iThis → null
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    var method = new SQLMethodExclude();

    var result = method.execute(null, null, ctx(), null, new Object[] {"firstName"});

    assertNull(result);
  }

  @Test
  public void unrecognizedIThisTypeReturnsNull() {
    // String is neither EntityImpl nor Map nor MultiValue — all branches fall through.
    var method = new SQLMethodExclude();

    var result = method.execute("not-an-entity", null, ctx(), null, new Object[] {"firstName"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // EntityImpl happy path — exclude by literal name
  // ---------------------------------------------------------------------------

  @Test
  public void excludeLiteralFieldRemovesThatFieldOnly() {
    var entity = personEntity();
    var method = new SQLMethodExclude();

    @SuppressWarnings("unchecked")
    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"email"});

    assertFalse("email must be removed", result.hasProperty("email"));
    assertEquals("Alice", result.getProperty("firstName"));
    assertEquals("Smith", result.getProperty("lastName"));
    assertEquals(30, (int) result.getProperty("age"));
  }

  @Test
  public void excludeMultipleFieldsRemovesAllListed() {
    var entity = personEntity();
    var method = new SQLMethodExclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"email", "age"});

    assertFalse(result.hasProperty("email"));
    assertFalse(result.hasProperty("age"));
    assertTrue(result.hasProperty("firstName"));
    assertTrue(result.hasProperty("lastName"));
  }

  @Test
  public void excludeNonExistentFieldLeavesAllFields() {
    // Removing a field that isn't on the entity is a no-op.
    var entity = personEntity();
    var method = new SQLMethodExclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"nonExistentField"});

    assertEquals(4, result.getPropertyNames().size());
  }

  @Test
  public void nullFieldNameInListIsSkipped() {
    // Null entries in iParams are explicitly guarded by `if (iFieldName != null)`.
    var entity = personEntity();
    var method = new SQLMethodExclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {null, "email"});

    assertFalse(result.hasProperty("email"));
    assertTrue(result.hasProperty("firstName"));
  }

  // ---------------------------------------------------------------------------
  // Wildcard field name — "prefix*" removes all matching properties
  // ---------------------------------------------------------------------------

  @Test
  public void wildcardFieldNameRemovesAllMatchingProperties() {
    // "Name*" matches firstName (NO: prefix-match, case-sensitive) … actually substring.startsWith
    // is case-sensitive — set up properties with a shared prefix to exercise the wildcard loop.
    var entity = (EntityImpl) session.newEntity("P");
    entity.setProperty("addr_street", "1 Main");
    entity.setProperty("addr_city", "Springfield");
    entity.setProperty("name", "Alice");
    var method = new SQLMethodExclude();

    var result = (Result) method.execute(entity, null, ctx(), null,
        new Object[] {"addr_*"});

    assertFalse("addr_street should be removed", result.hasProperty("addr_street"));
    assertFalse("addr_city should be removed", result.hasProperty("addr_city"));
    assertTrue("name should remain", result.hasProperty("name"));
  }

  @Test
  public void singleStarWildcardExcludesAllProperties() {
    // "*" with an empty prefix — every property starts with "" → every property is excluded.
    var entity = personEntity();
    var method = new SQLMethodExclude();

    var result = (Result) method.execute(entity, null, ctx(), null, new Object[] {"*"});

    assertEquals("star prefix removes every property", 0, result.getPropertyNames().size());
  }

  // ---------------------------------------------------------------------------
  // RID iThis path — load via transaction
  // ---------------------------------------------------------------------------

  @Test
  public void ridIThisLoadsAndExcludes() {
    var entity = personEntity();
    session.commit();
    var rid = (RecordIdInternal) entity.getIdentity();
    session.begin();

    var method = new SQLMethodExclude();

    var result = (Result) method.execute(rid, null, ctx(), null, new Object[] {"email"});

    assertFalse(result.hasProperty("email"));
    assertEquals("Alice", result.getProperty("firstName"));
  }

  @Test
  public void missingRidReturnsNull() {
    // RecordNotFoundException is caught → method returns null.
    var method = new SQLMethodExclude();
    RecordIdInternal missing = new RecordId(999, 999);

    var result = method.execute(missing, null, ctx(), null, new Object[] {"firstName"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Result iThis path — asEntity() then exclude
  // ---------------------------------------------------------------------------

  @Test
  public void resultIThisResolvesViaAsEntity() {
    // Result wrapping an EntityImpl — method calls result.asEntity() before exclusion.
    var entity = personEntity();
    var wrapper = new ResultInternal(session, entity);
    var method = new SQLMethodExclude();

    var result = (Result) method.execute(wrapper, null, ctx(), null, new Object[] {"email"});

    assertFalse(result.hasProperty("email"));
    assertEquals("Alice", result.getProperty("firstName"));
  }

  // ---------------------------------------------------------------------------
  // Map iThis path — same exclusion semantics
  // ---------------------------------------------------------------------------

  @Test
  public void mapIThisExcludesLiteralKey() {
    var method = new SQLMethodExclude();
    var map = new LinkedHashMap<String, Object>();
    map.put("a", 1);
    map.put("b", 2);
    map.put("c", 3);

    var result = (Result) method.execute(map, null, ctx(), null, new Object[] {"b"});

    assertEquals(1, (int) result.getProperty("a"));
    assertFalse(result.hasProperty("b"));
    assertEquals(3, (int) result.getProperty("c"));
  }

  @Test
  public void mapIThisExcludesWildcardKey() {
    var method = new SQLMethodExclude();
    var map = new LinkedHashMap<String, Object>();
    map.put("addr_street", "1 Main");
    map.put("addr_city", "Springfield");
    map.put("name", "Alice");

    var result = (Result) method.execute(map, null, ctx(), null, new Object[] {"addr_*"});

    assertFalse(result.hasProperty("addr_street"));
    assertFalse(result.hasProperty("addr_city"));
    assertEquals("Alice", result.getProperty("name"));
  }

  // ---------------------------------------------------------------------------
  // Multi-value iThis path — List of Identifiable
  // ---------------------------------------------------------------------------

  @Test
  public void multiValueIThisExcludesFieldFromEachEntity() {
    var first = (EntityImpl) session.newEntity("Item");
    first.setProperty("a", 1);
    first.setProperty("b", 2);

    var second = (EntityImpl) session.newEntity("Item");
    second.setProperty("a", 10);
    second.setProperty("b", 20);

    session.commit();
    session.begin();

    var method = new SQLMethodExclude();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(List.of(first.getIdentity(), second.getIdentity()),
        null, ctx(), null, new Object[] {"a"});

    assertEquals(2, result.size());
    assertFalse(((Result) result.get(0)).hasProperty("a"));
    assertEquals(2, (int) ((Result) result.get(0)).getProperty("b"));
    assertFalse(((Result) result.get(1)).hasProperty("a"));
    assertEquals(20, (int) ((Result) result.get(1)).getProperty("b"));
  }

  @Test
  public void multiValueIThisSilentlySkipsMissingRids() {
    // RecordNotFoundException inside the loop is caught and the entry skipped — result list
    // contains only the entities that loaded.
    var entity = personEntity();
    session.commit();
    var goodRid = entity.getIdentity();
    session.begin();

    var method = new SQLMethodExclude();
    RecordIdInternal missing = new RecordId(999, 999);

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(List.of(goodRid, missing), null, ctx(), null,
        new Object[] {"email"});

    assertEquals("missing RID must be dropped silently", 1, result.size());
    assertNotNull(result.get(0));
  }

  @Test
  public void multiValueIThisWithNonIdentifiableEntriesSkipsThem() {
    // Non-Identifiable entries fail the `instanceof Identifiable` guard → skipped without error.
    var method = new SQLMethodExclude();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(List.of("string-not-id", 42),
        null, ctx(), null, new Object[] {"anything"});

    assertEquals("non-identifiable entries must be skipped", 0, result.size());
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Test
  public void nameAndSyntaxMatchMethodContract() {
    var method = new SQLMethodExclude();

    assertEquals("exclude", SQLMethodExclude.NAME);
    assertEquals(SQLMethodExclude.NAME, method.getName());
    assertEquals("Syntax error: exclude([<field-name>][,]*)", method.getSyntax());
    assertEquals(1, method.getMinParams());
    assertEquals(-1, method.getMaxParams(session));
  }
}
