/*
 * JUnit 4 version of SQLDBSequenceTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDBSequenceTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 3/5/2015
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLDBSequenceTest extends BaseDBTest {

  private static final long FIRST_START = DBSequence.DEFAULT_START;
  private static final long SECOND_START = 31;
  private static SQLDBSequenceTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLDBSequenceTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDBSequenceTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testFree (line 77) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDBSequenceTest.java
   */
  @Test
  public void test01_Free() throws Exception {
    var sequenceManager = session.getMetadata().getSequenceLibrary();

    DBSequence seq = null;
    try {
      seq = sequenceManager.createSequence("seqSQLOrdered", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (DatabaseException exc) {
      Assert.fail("Unable to create sequence");
    }

    SequenceException err = null;
    try {
      sequenceManager.createSequence("seqSQLOrdered", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (SequenceException se) {
      err = se;
    } catch (DatabaseException exc) {
      Assert.fail("Unable to create sequence");
    }

    Assert.assertTrue(
        "Creating two ordered sequences with same name doesn't throw an exception",
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"));

    var seqSame = sequenceManager.getSequence("seqSQLOrdered");
    Assert.assertEquals(seqSame, seq);

    testUsage(seq, FIRST_START);

    //
    try {
      session.begin();
      seq.updateParams(session,
          new DBSequence.CreateParams().setStart(SECOND_START).setCacheSize(13));
      session.commit();
    } catch (DatabaseException exc) {
      Assert.fail("Unable to update paramas");
    }
    testUsage(seq, SECOND_START);
  }

  /**
   * Original: testUsage (line 117) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDBSequenceTest.java
   */
  private void testUsage(DBSequence seq, long reset)
      throws ExecutionException, InterruptedException {
    for (var i = 0; i < 2; ++i) {
      Assert.assertEquals(seq.reset(session), reset);
      Assert.assertEquals(seq.current(session), reset);
      Assert.assertEquals(seq.next(session), reset + 1L);
      Assert.assertEquals(seq.current(session), reset + 1L);
      Assert.assertEquals(seq.next(session), reset + 2L);
      Assert.assertEquals(seq.next(session), reset + 3L);
      Assert.assertEquals(seq.next(session), reset + 4L);
      Assert.assertEquals(seq.current(session), reset + 4L);
      Assert.assertEquals(seq.reset(session), reset);
    }
  }
}
