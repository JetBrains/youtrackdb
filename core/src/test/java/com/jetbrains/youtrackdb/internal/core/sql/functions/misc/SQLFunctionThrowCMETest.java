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
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionThrowCME} — a test-harness SQL function that always throws a
 * {@link ConcurrentModificationException}. Used to force MVCC-error code paths from SQL.
 *
 * <p>Uses {@link DbTestBase} because the exception constructor requires
 * {@code context.getDatabaseSession().getDatabaseName()}.
 *
 * <p>Covered behaviour:
 *
 * <ul>
 *   <li>Happy path: RID + db-version + record-version + operation → CME with those fields.
 *   <li>Non-RID first parameter triggers a {@code ClassCastException} because of the unchecked
 *       {@code (RecordIdInternal) iParams[0]} cast (pinning regression; WHEN-FIXED: validate
 *       param type before cast).
 *   <li>Non-Integer second / third parameters trigger {@code ClassCastException} via the
 *       {@code (int) iParams[...]} unboxing casts.
 *   <li>Exception's {@code getRid()}, {@code getEnhancedDatabaseVersion()},
 *       {@code getEnhancedRecordVersion()} expose the values passed in.
 *   <li>Metadata: name, min/max params, syntax, {@code aggregateResults()} is {@code false},
 *       {@code getResult()} is {@code null}.
 * </ul>
 */
public class SQLFunctionThrowCMETest extends DbTestBase {

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  // ---------------------------------------------------------------------------
  // Happy path — CME thrown with matching fields
  // ---------------------------------------------------------------------------

  @Test
  public void happyPathThrowsCMEWithMatchingFields() {
    // The function always throws; we assert the exception fields match the inputs.
    var function = new SQLFunctionThrowCME();
    RecordIdInternal rid = new RecordId(17, 42);
    var dbVersion = 5;
    var recordVersion = 3;
    var operation = (int) RecordOperation.UPDATED;

    try {
      function.execute(null, null, null,
          new Object[] {rid, dbVersion, recordVersion, operation}, ctx());
      fail("expected ConcurrentModificationException");
    } catch (ConcurrentModificationException e) {
      assertEquals(rid, e.getRid());
      assertEquals(dbVersion, e.getEnhancedDatabaseVersion());
      assertEquals(recordVersion, e.getEnhancedRecordVersion());
      assertNotNull(e.getMessage());
      // The CME message embeds the operation name (via RecordOperation.getName). UPDATED → UPDATE.
      assertTrue("CME message should mention the UPDATE operation, saw: " + e.getMessage(),
          e.getMessage().contains("UPDATE"));
      assertTrue("CME message should mention the RID #17:42, saw: " + e.getMessage(),
          e.getMessage().contains("#17:42"));
    }
  }

  @Test
  public void happyPathWithDeletedOperationMentionsDeleteInMessage() {
    // Different operation than the happy-path test — drift guard against getName(iParams[3]) being
    // hard-coded to UPDATE.
    var function = new SQLFunctionThrowCME();
    RecordIdInternal rid = new RecordId(7, 9);

    try {
      function.execute(null, null, null,
          new Object[] {rid, 1, 0, (int) RecordOperation.DELETED}, ctx());
      fail("expected ConcurrentModificationException");
    } catch (ConcurrentModificationException e) {
      assertTrue("message should mention DELETE, saw: " + e.getMessage(),
          e.getMessage().contains("DELETE"));
    }
  }

  // ---------------------------------------------------------------------------
  // Negative-database-version branch — "does not exist" wording
  // ---------------------------------------------------------------------------

  @Test
  public void negativeDatabaseVersionProducesDoesNotExistMessage() {
    // When databaseVersion < 0, CME's makeMessage switches to a "it does not exist in the
    // database" wording. Sanity pin on a production branch that would be otherwise hard to hit.
    var function = new SQLFunctionThrowCME();
    RecordIdInternal rid = new RecordId(1, 1);

    try {
      function.execute(null, null, null,
          new Object[] {rid, -1, 0, (int) RecordOperation.UPDATED}, ctx());
      fail("expected CME");
    } catch (ConcurrentModificationException e) {
      assertTrue("message should indicate non-existent record, saw: " + e.getMessage(),
          e.getMessage().contains("does not exist"));
    }
  }

  // ---------------------------------------------------------------------------
  // Unchecked cast — non-RID first param → ClassCastException (WHEN-FIXED)
  // ---------------------------------------------------------------------------

  @Test
  public void nonRidFirstParamTriggersClassCastException() {
    // Production line:
    //   (RecordIdInternal) iParams[0]
    // has no type check. Passing a non-RID triggers CCE. Pin this so a later fix to validate the
    // type (e.g. throw CommandExecutionException with a useful message) is noticed here.
    // WHEN-FIXED: validate iParams[0] type and throw a specific exception instead of CCE.
    var function = new SQLFunctionThrowCME();

    try {
      function.execute(null, null, null,
          new Object[] {"#17:42", 0, 0, (int) RecordOperation.UPDATED}, ctx());
      fail("expected ClassCastException for non-RID first param");
    } catch (ClassCastException expected) {
      assertNotNull(expected);
    }
  }

  @Test
  public void nonIntegerDatabaseVersionParamTriggersClassCastException() {
    // (int) iParams[1] unboxes — a Long / String / etc. fails. Pinning regression.
    var function = new SQLFunctionThrowCME();
    RecordIdInternal rid = new RecordId(1, 1);

    try {
      function.execute(null, null, null,
          new Object[] {rid, "not-an-int", 0, (int) RecordOperation.UPDATED}, ctx());
      fail("expected ClassCastException for non-Integer dbVersion");
    } catch (ClassCastException expected) {
      assertNotNull(expected);
    }
  }

  @Test
  public void longRecordOperationParamTriggersClassCastException() {
    // RecordOperation position also uses (int) — feeding a Long box triggers CCE.
    var function = new SQLFunctionThrowCME();
    RecordIdInternal rid = new RecordId(1, 1);

    try {
      function.execute(null, null, null,
          new Object[] {rid, 0, 0, 3L}, ctx());
      fail("expected ClassCastException for Long operation param");
    } catch (ClassCastException expected) {
      assertNotNull(expected);
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsFalse() {
    var function = new SQLFunctionThrowCME();

    assertFalse(function.aggregateResults(new Object[] {}));
    assertFalse(function.aggregateResults(new Object[] {"anything"}));
  }

  @Test
  public void getResultReturnsNull() {
    assertNull(new SQLFunctionThrowCME().getResult());
  }

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionThrowCME();

    assertEquals("throwCME", SQLFunctionThrowCME.NAME);
    assertEquals(SQLFunctionThrowCME.NAME, function.getName(session));
    assertEquals(4, function.getMinParams());
    assertEquals(4, function.getMaxParams(session));
    assertEquals("throwCME(RID, DatabaseVersion, RecordVersion, RecordOperation)",
        function.getSyntax(session));
  }
}
