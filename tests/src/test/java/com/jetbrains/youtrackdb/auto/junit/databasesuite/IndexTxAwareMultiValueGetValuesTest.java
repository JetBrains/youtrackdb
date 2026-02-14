/*
 *
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
import com.jetbrains.youtrackdb.auto.junit.IndexTxAwareBaseTest;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of IndexTxAwareMultiValueGetValuesTest. Original test class:
 * com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetValuesTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IndexTxAwareMultiValueGetValuesTest extends IndexTxAwareBaseTest {

  private static IndexTxAwareMultiValueGetValuesTest instance;

  public IndexTxAwareMultiValueGetValuesTest() {
    super(false);
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new IndexTxAwareMultiValueGetValuesTest();
    instance.beforeClass();
    instance.setupClassSchema();
  }

  /**
   * Original: testPut (line 23) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test01_Put() {
    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())
    ));
    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(3, resultOne.size());

    session.begin();

    var doc4 = newDoc(2);

    verifyTxIndexPut(Map.of(2, Set.of(doc4.getIdentity())));
    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(4, resultTwo.size());

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(3, resultThree.size());
  }

  /**
   * Original: testRemove (line 60) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test02_Remove() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();
    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())
    ));

    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(3, resultOne.size());

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc2 = tx.load(doc2);

    doc1.delete();
    doc2.delete();

    verifyTxIndexRemove(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity())
    ));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(1, resultTwo.size());

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(3, resultThree.size());
  }

  /**
   * Original: testRemoveOne (line 109) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test03_RemoveOne() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();
    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())
    ));
    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(3, resultOne.size());

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(
        1, Set.of(doc1.getIdentity())
    ));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(2, resultTwo.size());

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(3, resultThree.size());
  }

  /**
   * Original: testMultiPut (line 154) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test04_MultiPut() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    final var doc1 = newDoc(1);

    doc1.setProperty(fieldName, 0);
    doc1.setProperty(fieldName, 1);

    final var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(2, result.size());

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(2, result.size());
  }

  /**
   * Original: testPutAfterTransaction (line 186) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test05_PutAfterTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);
    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(2, result.size());
    session.commit();

    session.begin();
    newDoc(1);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(3, result.size());
  }

  /**
   * Original: testRemoveOneWithinTransaction (line 217) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test06_RemoveOneWithinTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);
    doc1.delete();
    verifyTxIndexPut(Map.of(2, Set.of(doc2.getIdentity())));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(1, result.size());

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(1, result.size());
  }

  /**
   * Original: testRemoveAllWithinTransaction (line 243) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test07_RemoveAllWithinTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);
    doc1.delete();
    verifyTxIndexPut(Map.of(2, Set.of(doc2.getIdentity())));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(1, result.size());

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(1, result.size());
  }

  /**
   * Original: testPutAfterRemove (line 269) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetValuesTest.java
   */
  @Test
  public void test08_PutAfterRemove() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(2, result.size());

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(2, result.size());
  }

  private static void streamToSet(
      Stream<RawPair<Object, RID>> stream, Set<Identifiable> result) {
    result.clear();
    result.addAll(stream.map((entry) -> entry.second()).collect(Collectors.toSet()));
  }
}
