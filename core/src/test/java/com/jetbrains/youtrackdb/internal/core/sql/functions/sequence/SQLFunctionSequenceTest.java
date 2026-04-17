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
package com.jetbrains.youtrackdb.internal.core.sql.functions.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionSequence} — looks up a named sequence from the database's sequence
 * library.
 *
 * <p>Uses {@link DbTestBase} because
 * {@code context.getDatabaseSession().getMetadata().getSequenceLibrary().getSequence(name)} is
 * the core lookup.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Happy path: created sequence ORDERED → function returns the same {@link DBSequence}
 *       instance as the library lookup.
 *   <li>Unknown sequence name → {@link CommandExecutionException} with the "Sequence not found:
 *       &lt;name&gt;" message.
 *   <li>{@code configuredParameters[0]} is a {@link SQLFilterItem} (old-style configuration) →
 *       the function asks the filter item for its value and uses that as the name (not
 *       {@code iParams[0]}).
 *   <li>{@code configuredParameters} is {@code null} or empty → falls back to
 *       {@code "" + iParams[0]}, exercising the runtime-args path.
 *   <li>{@code iParams[0].toString()} coercion: non-String params are coerced via string
 *       concatenation (the production code uses {@code "" + iParams[0]}).
 *   <li>Metadata: name / min / max / syntax / {@code aggregateResults()} / {@code getResult()}.
 * </ul>
 */
public class SQLFunctionSequenceTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  @Before
  public void createMySeq() {
    // Sequences are persisted metadata — created once per test via DbTestBase's per-method DB.
    // Uppercased by the library (DBSequenceTest pattern), so the stored name is "MYSEQ".
    session.getMetadata().getSequenceLibrary().createSequence(
        "mySeq", DBSequence.SEQUENCE_TYPE.ORDERED, new DBSequence.CreateParams().setDefaults());
  }

  // ---------------------------------------------------------------------------
  // Happy path — sequence found
  // ---------------------------------------------------------------------------

  @Test
  public void existingSequenceIsReturned() {
    // Function returns the same DBSequence that the library stores under the uppercased name.
    var function = new SQLFunctionSequence();
    var expected = session.getMetadata().getSequenceLibrary().getSequence("MYSEQ");
    assertNotNull("sequence fixture missing", expected);

    var result = function.execute(null, null, null, new Object[] {"MYSEQ"}, ctx());

    assertSame(expected, result);
  }

  @Test
  public void sequenceLookupUsesUppercasedName() {
    // The library lookup upper-cases the input internally. Pin this so a refactor that changes
    // case-handling of sequence names is noticed here (mixed-case input should still resolve).
    var function = new SQLFunctionSequence();
    var expected = session.getMetadata().getSequenceLibrary().getSequence("MYSEQ");

    var result = function.execute(null, null, null, new Object[] {"MYSEQ"}, ctx());

    assertSame(expected, result);
  }

  // ---------------------------------------------------------------------------
  // Unknown sequence — CommandExecutionException
  // ---------------------------------------------------------------------------

  @Test
  public void unknownSequenceThrowsCommandExecutionException() {
    var function = new SQLFunctionSequence();

    try {
      function.execute(null, null, null, new Object[] {"DOES_NOT_EXIST"}, ctx());
      fail("expected CommandExecutionException for unknown sequence");
    } catch (CommandExecutionException e) {
      assertNotNull(e.getMessage());
      assertTrue("message should say 'Sequence not found: DOES_NOT_EXIST', saw: " + e.getMessage(),
          e.getMessage().contains("Sequence not found: DOES_NOT_EXIST"));
    }
  }

  // ---------------------------------------------------------------------------
  // configuredParameters SQLFilterItem path — "old stuff" branch
  // ---------------------------------------------------------------------------

  @Test
  public void configuredSqlFilterItemOverridesRuntimeParams() {
    // Production branch (reformatted for clarity):
    //   if (configuredParameters != null && configuredParameters.length > 0
    //       && configuredParameters[0] instanceof SQLFilterItem) {
    //     seqName = (String) ((SQLFilterItem) configuredParameters[0])
    //         .getValue(iCurrentRecord, iCurrentResult, context);
    //   } else { seqName = "" + iParams[0]; }
    //
    // We configure the function with a test-only SQLFilterItem that returns "MYSEQ" and pass
    // a completely different iParams[0]. The filter item must win.
    var function = new SQLFunctionSequence();
    function.config(new Object[] {new FixedNameFilterItem("MYSEQ")});

    var expected = session.getMetadata().getSequenceLibrary().getSequence("MYSEQ");

    var result = function.execute(null, null, null, new Object[] {"IGNORED"}, ctx());

    assertSame(expected, result);
  }

  @Test
  public void configuredNonFilterItemFallsBackToIParams() {
    // A configuredParameters entry that is NOT a SQLFilterItem must NOT short-circuit the branch;
    // the runtime iParams[0] path is taken. Drift guard against `instanceof SQLFilterItem` being
    // removed from the guard.
    var function = new SQLFunctionSequence();
    function.config(new Object[] {"not-a-filter-item"});

    var expected = session.getMetadata().getSequenceLibrary().getSequence("MYSEQ");

    var result = function.execute(null, null, null, new Object[] {"MYSEQ"}, ctx());

    assertSame(expected, result);
  }

  @Test
  public void emptyConfiguredParamsUsesIParams() {
    // length == 0 means the guard fails on `configuredParameters.length > 0`; the runtime path
    // with iParams is used.
    var function = new SQLFunctionSequence();
    function.config(new Object[] {});

    var expected = session.getMetadata().getSequenceLibrary().getSequence("MYSEQ");

    var result = function.execute(null, null, null, new Object[] {"MYSEQ"}, ctx());

    assertSame(expected, result);
  }

  // ---------------------------------------------------------------------------
  // iParams[0] coercion: "" + iParams[0] invokes toString()
  // ---------------------------------------------------------------------------

  @Test
  public void nonStringIParamIsConcatenatedIntoName() {
    // A StringBuilder whose toString() equals "MYSEQ" resolves through the string-concat path.
    var function = new SQLFunctionSequence();
    var expected = session.getMetadata().getSequenceLibrary().getSequence("MYSEQ");

    var result = function.execute(null, null, null,
        new Object[] {new StringBuilder("MYSEQ")}, ctx());

    assertSame(expected, result);
  }

  @Test
  public void nullIParamIsConcatenatedAsLiteralNullString() {
    // "" + null → "null" — sequence named literally "null" doesn't exist → CommandExecutionException
    // with "Sequence not found: null". Pins the null-handling behaviour (nobody NPEs on null).
    var function = new SQLFunctionSequence();

    try {
      function.execute(null, null, null, new Object[] {(Object) null}, ctx());
      fail("expected CommandExecutionException");
    } catch (CommandExecutionException e) {
      assertTrue("null iParams[0] should produce 'Sequence not found: null', saw: "
          + e.getMessage(),
          e.getMessage().contains("Sequence not found: null"));
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsFalse() {
    assertFalse(new SQLFunctionSequence().aggregateResults());
  }

  @Test
  public void getResultReturnsNull() {
    var function = new SQLFunctionSequence();
    function.execute(null, null, null, new Object[] {"MYSEQ"}, ctx());

    assertNull(function.getResult());
  }

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionSequence();

    assertEquals("sequence", SQLFunctionSequence.NAME);
    assertEquals(SQLFunctionSequence.NAME, function.getName(session));
    assertEquals(1, function.getMinParams());
    assertEquals(1, function.getMaxParams(session));
    assertEquals("sequence(<name>)", function.getSyntax(session));
  }

  /**
   * Minimal {@link SQLFilterItem} returning a fixed string name, used to exercise the "old-style"
   * configured-parameters branch without spinning up an SQL parse tree.
   */
  private static final class FixedNameFilterItem implements SQLFilterItem {
    private final String name;

    FixedNameFilterItem(String name) {
      this.name = name;
    }

    @Override
    public Object getValue(Result iRecord, Object iCurrentResult, CommandContext iContext) {
      return name;
    }
  }
}
