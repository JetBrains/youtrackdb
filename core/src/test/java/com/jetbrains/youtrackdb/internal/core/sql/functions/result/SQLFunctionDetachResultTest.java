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
package com.jetbrains.youtrackdb.internal.core.sql.functions.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionDetachResult} — forwards to {@link Result#detach()}, with a null-
 * guard that throws {@link CommandSQLParsingException}.
 *
 * <p>Uses {@link DbTestBase} because the null-record exception path calls
 * {@code iContext.getDatabaseSession().getDatabaseName()}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iCurrentRecord == null} → {@link CommandSQLParsingException} with message pinning
 *       the "detach()" / "NULL was found" text.
 *   <li>Non-null record path → returns {@code iCurrentRecord.detach()}.
 *   <li>For a non-entity {@link Result}, the returned Result preserves property names and values
 *       but is a fresh instance (pinned via {@code assertNotSame}) — {@code ResultInternal.detach()}
 *       always constructs a new, session-less Result.
 *   <li>For an entity-wrapping {@link Result}, the detached copy materialises the entity's
 *       observable properties.
 *   <li>{@code aggregateResults()} returns {@code false}.
 *   <li>{@code getResult()} returns {@code null}.
 *   <li>Metadata: name, min/max, syntax.
 * </ul>
 */
public class SQLFunctionDetachResultTest extends DbTestBase {

  @Before
  public void setUp() {
    // Schema changes are NOT transactional — create the test class BEFORE opening the tx.
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

  private SQLFunctionDetachResult function() {
    return new SQLFunctionDetachResult();
  }

  // ---------------------------------------------------------------------------
  // Null iCurrentRecord — exception path
  // ---------------------------------------------------------------------------

  @Test
  public void nullRecordThrowsCommandSqlParsingExceptionWithMessage() {
    var f = function();

    try {
      f.execute(null, null, null, new Object[] {}, ctx());
      fail("expected CommandSQLParsingException for null iCurrentRecord");
    } catch (CommandSQLParsingException expected) {
      assertNotNull("exception must carry a message", expected.getMessage());
      assertTrue("message should reference 'detach()', saw: " + expected.getMessage(),
          expected.getMessage().contains("detach()"));
      assertTrue("message should reference 'NULL was found', saw: " + expected.getMessage(),
          expected.getMessage().contains("NULL was found"));
    }
  }

  // ---------------------------------------------------------------------------
  // Happy path — non-null record returns detach() result
  // ---------------------------------------------------------------------------

  @Test
  public void nonNullRecordDelegatesToDetach() {
    // ResultInternal with only primitive properties: detach() is effectively an identity op for
    // non-entity Results, but the function must still return the value produced by detach() —
    // NOT the input, and NOT null.
    var record = new ResultInternal(session);
    record.setProperty("k", "v");
    var f = function();

    var detached = f.execute(null, record, null, new Object[] {}, ctx());

    assertNotNull("detach() result must be non-null for a non-null input", detached);
    assertTrue("detach() must return a Result-typed value", detached instanceof Result);
    assertEquals("detach must preserve the property value", "v",
        ((Result) detached).getProperty("k"));
  }

  @Test
  public void nonEntityResultDetachReturnsFreshResultWithPreservedProperties() {
    // ResultInternal.detach() always constructs a new Result — never returns `this`. Pin this so
    // a refactor that aliases the detached Result back to the session-bound source is visible.
    var record = new ResultInternal(session);
    record.setProperty("k", "v");
    record.setProperty("n", 42);
    var f = function();

    var detached = (Result) f.execute(null, record, null, new Object[] {}, ctx());

    assertNotNull(detached);
    assertNotSame("detach() must return a fresh Result, not the source", record, detached);
    assertEquals(record.getPropertyNames(), detached.getPropertyNames());
    assertEquals("v", detached.getProperty("k"));
    assertEquals(Integer.valueOf(42), detached.getProperty("n"));
  }

  @Test
  public void entityWrappingResultDetachMaterializesEntityProperties() {
    // Exercises the isEntity() == true path: a ResultInternal constructed with a DBRecord wraps
    // the entity, and detach() materialises the observable properties into the detached Result.
    // "Thing" was created in @Before (schema changes are not transactional).
    var entity = (EntityImpl) session.newEntity("Thing");
    entity.setProperty("name", "alpha");
    entity.setProperty("n", 7);
    var wrapped = new ResultInternal(session, entity);
    var f = function();

    var detached = (Result) f.execute(null, wrapped, null, new Object[] {}, ctx());

    assertNotNull(detached);
    assertEquals("alpha", detached.getProperty("name"));
    assertEquals(Integer.valueOf(7), detached.getProperty("n"));
  }

  // ---------------------------------------------------------------------------
  // Contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsFalse() {
    assertFalse(function().aggregateResults());
  }

  @Test
  public void getResultIsNull() {
    assertNull(function().getResult());
  }

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var f = function();

    assertEquals("detach", SQLFunctionDetachResult.NAME);
    assertEquals("detach", f.getName(null));
    assertEquals(0, f.getMinParams());
    assertEquals(0, f.getMaxParams(null));
    assertEquals("detach()", f.getSyntax(null));
  }
}
