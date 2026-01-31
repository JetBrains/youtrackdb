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

import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of IndexTxAwareOneValueGetTest. Original test class:
 * com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IndexTxAwareOneValueGetTest extends IndexTxAwareBaseTest {

  private static IndexTxAwareOneValueGetTest instance;

  public IndexTxAwareOneValueGetTest() {
    super(true);
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new IndexTxAwareOneValueGetTest();
    instance.beforeClass();
    instance.setupClassSchema();
  }

  /**
   * Original: testPut (line 16) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test01_Put() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();

    var doc3 = newDoc(3);
    verifyTxIndexPut(Map.of(3, Set.of(doc3.getIdentity())));

    try (var stream = index.getRids(session, 3)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 3)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testRemove (line 63) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test02_Remove() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testRemoveAndPut (line 112) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test03_RemoveAndPut() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexChanges(
        Map.of(1, Set.of(doc1.getIdentity())),
        Map.of(1, Set.of(doc1.getIdentity()))
    );
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.rollback();
  }

  /**
   * Original: testMultiPut (line 158) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test04_MultiPut() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var doc1 = newDoc(1);

    doc1.setProperty(fieldName, 0);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testPutAfterTransaction (line 182) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test05_PutAfterTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    final var doc1 = newDoc(1);
    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    session.commit();

    session.begin();
    newDoc(2);
    session.commit();

    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testRemoveOneWithinTransaction (line 206) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test06_RemoveOneWithinTransaction() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var document = newDoc(1);
    document.delete();

    verifyTxIndexChanges(null, null);

    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testPutAfterRemove (line 230) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test07_PutAfterRemove() {
    Assume.assumeFalse("Test is enabled only for embedded database",
        session.getStorage().isRemote());

    session.begin();

    var document = newDoc(1);
    document.removeProperty(fieldName);
    document.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  /**
   * Original: testInsertionDeletionInsideTx (line 255) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareOneValueGetTest.java
   */
  @Test
  public void test08_InsertionDeletionInsideTx() {
    final var testClassName = "_" + IndexTxAwareOneValueGetTest.class.getSimpleName();
    session.execute("create class " + testClassName + " extends V").close();
    session.execute("create property " + testClassName + ".name STRING").close();
    session.execute("CREATE INDEX " + testClassName + ".name UNIQUE").close();

    session
        .computeScript(
            "SQL",
            "begin;\n"
                + "insert into "
                + testClassName
                + "(name) values ('c');\n"
                + "let top = (select from "
                + testClassName
                + " where name='c');\n"
                + "delete vertex $top;\n"
                + "commit;\n"
                + "return $top")
        .close();

    try (final var resultSet = session.query("select * from " + testClassName)) {
      try (var stream = resultSet.stream()) {
        Assert.assertEquals(0, stream.count());
      }
    }
  }
}
