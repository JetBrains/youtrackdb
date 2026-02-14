/*
 * JUnit 4 version of LinkListIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 21.03.12
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LinkListIndexTest extends BaseDBTest {

  private static LinkListIndexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new LinkListIndexTest();
    instance.beforeClass();
    instance.setupSchema();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (instance != null) {
      instance.destroySchema();
    }
  }

  /**
   * Original: setupSchema (line 22) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  private void setupSchema() {
    final var linkListIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkListIndexTestClass");

    linkListIndexTestClass.createProperty("linkCollection", PropertyType.LINKLIST);

    linkListIndexTestClass.createIndex(
        "linkCollectionIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "linkCollection");
  }

  /**
   * Original: destroySchema (line 33) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  private void destroySchema() {
    session = acquireSession();
    session.getMetadata().getSchema().dropClass("LinkListIndexTestClass");
  }

  /**
   * Original: afterMethod (line 40) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("DELETE FROM LinkListIndexTestClass").close();
    session.commit();

    var result = session.query("select from LinkListIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("linkCollectionIndex");
      Assert.assertEquals(index.size(session), 0);
    }

    super.afterMethod();
  }

  /**
   * Original: testIndexCollection (line 56) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test01_IndexCollection() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionInTx (line 87) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test02_IndexCollectionInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
      document.setProperty("linkCollection",
          session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity()) && !key.equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdate (line 125) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test03_IndexCollectionUpdate() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));

    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docThree.getIdentity())));

    session.commit();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateInTx (line 161) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test04_IndexCollectionUpdateInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      document.setProperty("linkCollection",
          session.newLinkList(List.of(docOne.getIdentity(), docThree.getIdentity())));

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateInTxRollback (line 207) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test05_IndexCollectionUpdateInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docThree.getIdentity())));

    session.rollback();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateAddItem (line 247) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test06_IndexCollectionUpdateAddItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + document.getIdentity()
                + " set linkCollection = linkCollection || "
                + docThree.getIdentity())
        .close();
    session.commit();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateAddItemInTx (line 290) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test07_IndexCollectionUpdateAddItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<List<Identifiable>>getProperty("linkCollection").add(docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateAddItemInTxRollback (line 334) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test08_IndexCollectionUpdateAddItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<List<Identifiable>>getProperty("linkCollection").add(docThree.getIdentity());

    session.rollback();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateRemoveItemInTx (line 372) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test09_IndexCollectionUpdateRemoveItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<List>getProperty("linkCollection").remove(docTwo.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateRemoveItemInTxRollback (line 412) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test10_IndexCollectionUpdateRemoveItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<List>getProperty("linkCollection").remove(docTwo.getIdentity());

    session.rollback();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateRemoveItem (line 448) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test11_IndexCollectionUpdateRemoveItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    session.execute(
        "UPDATE " + document.getIdentity() + " remove linkCollection = " + docTwo.getIdentity());
    session.commit();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionRemove (line 483) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test12_IndexCollectionRemove() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.delete();
    session.commit();

    var index = getIndex("linkCollectionIndex");

    Assert.assertEquals(index.size(session), 0);
  }

  /**
   * Original: testIndexCollectionRemoveInTx (line 507) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test13_IndexCollectionRemoveInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      document.delete();
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 0);
  }

  /**
   * Original: testIndexCollectionRemoveInTxRollback (line 535) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test14_IndexCollectionRemoveInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    var index = getIndex("linkCollectionIndex");

    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionSQL (line 571) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkListIndexTest.java
   */
  @Test
  public void test15_IndexCollectionSQL() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var result =
        session.query(
            "select * from LinkListIndexTestClass where linkCollection contains ?",
            docOne.getIdentity());
    Assert.assertEquals(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()),
        result.next().<List>getProperty("linkCollection"));
    session.commit();
  }

}
