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
package com.jetbrains.youtrackdb.auto.junit;

import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of IndexTxAwareMultiValueGetTest. Original test class:
 * com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IndexTxAwareMultiValueGetTest extends IndexTxAwareBaseTest {

  private static IndexTxAwareMultiValueGetTest instance;

  public IndexTxAwareMultiValueGetTest() {
    super(false);
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new IndexTxAwareMultiValueGetTest();
    instance.beforeClass();
    instance.setupClassSchema();
  }

  /**
   * Original: testPut (line 16) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
   */
  @Test
  public void test01_Put() {
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

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }

    session.begin();

    var doc4 = newDoc(2);

    verifyTxIndexPut(Map.of(2, Set.of(doc4.getIdentity())));
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(2, stream.count());
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }
  }

  /**
   * Original: testRemove (line 61) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
   */
  @Test
  public void test02_Remove() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var docOne = newDoc(1);
    var docTwo = newDoc(1);
    var docThree = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(docOne.getIdentity(), docTwo.getIdentity()),
        2, Set.of(docThree.getIdentity())
    ));

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }

    final var tx = session.begin();

    docOne = tx.load(docOne);
    docTwo = tx.load(docTwo);

    docOne.delete();
    docTwo.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(docOne.getIdentity(), docTwo.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }
  }

  /**
   * Original: testRemoveOne (line 113) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
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

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(1, stream.count());
    }
  }

  /**
   * Original: testMultiPut (line 159) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
   */
  @Test
  public void test04_MultiPut() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    final var document = newDoc(1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }

    document.setProperty(fieldName, 0);
    verifyTxIndexPut(Map.of(0, Set.of(document.getIdentity())));
    document.setProperty(fieldName, 1);
    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }
    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }
  }

  /**
   * Original: testPutAfterTransaction (line 189) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
   */
  @Test
  public void test05_PutAfterTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }
    session.commit();

    session.begin();
    newDoc(1);
    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(2, stream.count());
    }
  }

  /**
   * Original: testRemoveOneWithinTransaction (line 214) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
   */
  @Test
  public void test06_RemoveOneWithinTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    final var document = newDoc(1);
    document.delete();

    verifyTxIndexChanges(null, null);
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(0, stream.count());
    }
  }

  /**
   * Original: testPutAfterRemove (line 237) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareMultiValueGetTest.java
   */
  @Test
  public void test07_PutAfterRemove() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    final var document = newDoc(1);
    document.removeProperty(fieldName);
    document.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(1, stream.count());
    }
  }
}
