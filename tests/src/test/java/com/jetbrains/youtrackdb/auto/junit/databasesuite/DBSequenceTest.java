/*
 * JUnit 4 version of DBSequenceTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/DBSequenceTest.java
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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 3/2/2015
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DBSequenceTest extends BaseDBTest {

  private static final long FIRST_START = DBSequence.DEFAULT_START;
  private static final long SECOND_START = 31;
  private static DBSequenceTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new DBSequenceTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBSequenceTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testOrdered (line 60) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBSequenceTest.java
   */
  @Test
  public void test01_Ordered() throws Exception {
    var sequenceManager = session.getMetadata().getSequenceLibrary();

    var seq = sequenceManager.createSequence("seqOrdered", SEQUENCE_TYPE.ORDERED, null);

    SequenceException err = null;
    try {
      sequenceManager.createSequence("seqOrdered", SEQUENCE_TYPE.ORDERED, null);
    } catch (SequenceException se) {
      err = se;
    }
    Assert.assertTrue(
        "Creating two ordered sequences with same name doesn't throw an exception",
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"));

    var seqSame = sequenceManager.getSequence("seqOrdered");
    Assert.assertEquals(seqSame, seq);

    testUsage(seq, FIRST_START);

    //
    session.begin();
    seq.updateParams(session,
        new DBSequence.CreateParams().setStart(SECOND_START).setCacheSize(13));
    session.commit();

    testUsage(seq, SECOND_START);
  }

  /**
   * Original: testUsage (line 89) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBSequenceTest.java
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
