/*
 * JUnit 4 version of LinkSetIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 3/28/14
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LinkSetIndexTest extends BaseDBTest {

  private static LinkSetIndexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new LinkSetIndexTest();
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
   * Original: setupSchema (line 24) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  private void setupSchema() {
    final var ridBagIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkSetIndexTestClass");

    ridBagIndexTestClass.createProperty("linkSet", PropertyType.LINKSET);

    ridBagIndexTestClass.createIndex("linkSetIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkSet");
    session.close();
  }

  /**
   * Original: destroySchema (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  private void destroySchema() {

  }

  /**
   * Original: afterMethod (line 43) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Override
  @After
  public void afterMethod() throws Exception {

    session.begin();
    session.execute("DELETE FROM LinkSetIndexTestClass").close();
    session.commit();

    session.begin();
    var result = session.execute("select from LinkSetIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (session.getStorage().isRemote()) {
      var index =
          session.getSharedContext().getIndexManager().getIndex("linkSetIndex");
      Assert.assertEquals(index.size(session), 0);
    }
    session.commit();

    session.close();
  }

  /**
   * Original: testIndexLinkSet (line 63) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test01_IndexLinkSet() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetInTx (line 96) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test02_IndexLinkSetInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
      final var linkSet = session.newLinkSet();
      var activeTx1 = session.getActiveTransaction();
      linkSet.add(activeTx1.<EntityImpl>load(docOne));
      var activeTx = session.getActiveTransaction();
      linkSet.add(activeTx.<EntityImpl>load(docTwo));

      document.setProperty("linkSet", linkSet);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdate (line 139) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test03_IndexLinkSetUpdate() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    final var linkSetTwo = session.newLinkSet();
    linkSetTwo.add(docOne);
    linkSetTwo.add(docThree);

    document.setProperty("linkSet", linkSetTwo);

    session.commit();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateInTx (line 180) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test04_IndexLinkSetUpdateInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    session.commit();

    try {
      session.begin();

      var activeTx2 = session.getActiveTransaction();
      document = activeTx2.load(document);
      final var linkSetTwo = session.newLinkSet();
      var activeTx1 = session.getActiveTransaction();
      linkSetTwo.add(activeTx1.<EntityImpl>load(docOne));
      var activeTx = session.getActiveTransaction();
      linkSetTwo.add(activeTx.<EntityImpl>load(docThree));

      document.setProperty("linkSet", linkSetTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateInTxRollback (line 234) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test05_IndexLinkSetUpdateInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    document.setProperty("linkSet", linkSetOne);

    session.commit();

    session.begin();

    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    final var linkSetTwo = session.newLinkSet();
    var activeTx1 = session.getActiveTransaction();
    linkSetTwo.add(activeTx1.<EntityImpl>load(docOne));
    var activeTx = session.getActiveTransaction();
    linkSetTwo.add(activeTx.<EntityImpl>load(docThree));

    document.setProperty("linkSet", linkSetTwo);

    session.rollback();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateAddItem (line 283) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test06_IndexLinkSetUpdateAddItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + document.getIdentity()
                + " set linkSet = linkSet || "
                + docThree.getIdentity())
        .close();
    session.commit();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateAddItemInTx (line 328) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test07_IndexLinkSetUpdateAddItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      var activeTx = session.getActiveTransaction();
      loadedDocument.<Set<Identifiable>>getProperty("linkSet").add(
          activeTx.<EntityImpl>load(docThree));

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateAddItemInTxRollback (line 377) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test08_IndexLinkSetUpdateAddItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    var activeTx = session.getActiveTransaction();
    loadedDocument.<Set<Identifiable>>getProperty("linkSet").add(
        activeTx.<EntityImpl>load(docThree));

    session.rollback();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateRemoveItemInTx (line 420) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test09_IndexLinkSetUpdateRemoveItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Set<Identifiable>>getProperty("linkSet").remove(docTwo.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateRemoveItemInTxRollback (line 462) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test10_IndexLinkSetUpdateRemoveItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Set<Identifiable>>getProperty("linkSet").remove(docTwo.getIdentity());

    session.rollback();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetUpdateRemoveItem (line 500) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test11_IndexLinkSetUpdateRemoveItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session
        .execute("UPDATE " + document.getIdentity() + " remove linkSet = " + docTwo.getIdentity())
        .close();
    session.commit();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetRemove (line 538) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test12_IndexLinkSetRemove() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));

    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.commit();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 0);
  }

  /**
   * Original: testIndexLinkSetRemoveInTx (line 564) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test13_IndexLinkSetRemoveInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));

    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      activeTx.<EntityImpl>load(document).delete();
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 0);
  }

  /**
   * Original: testIndexLinkSetRemoveInTxRollback (line 595) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test14_IndexLinkSetRemoveInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();

    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexLinkSetSQL (line 634) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkSetIndexTest.java
   */
  @Test
  public void test15_IndexLinkSetSQL() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docThree);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    var result =
        session.query(
            "select * from LinkSetIndexTestClass where linkSet contains ?", docOne.getIdentity());

    List<Identifiable> listResult =
        new ArrayList<>(result.next().<Set<Identifiable>>getProperty("linkSet"));
    Assert.assertEquals(listResult.size(), 2);
    Assert.assertTrue(
        listResult.containsAll(Arrays.asList(docOne.getIdentity(), docTwo.getIdentity())));
    session.commit();
  }

}
