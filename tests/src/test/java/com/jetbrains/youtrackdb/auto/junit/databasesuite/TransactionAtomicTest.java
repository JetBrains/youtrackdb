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

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import com.jetbrains.youtrackdb.internal.core.exception.TransactionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.io.IOException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for transaction atomicity and MVCC behavior.
 *
 * <p><b>Suite Dependency:</b> This test is part of {@link DatabaseTestSuite} and depends on
 * the basic schema (Account class) created by earlier tests. Can be run individually as the
 * {@code @BeforeClass} method initializes the required schema.</p>
 *
 * <p>Original test class: {@code com.jetbrains.youtrackdb.auto.TransactionAtomicTest}</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TransactionAtomicTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    TransactionAtomicTest instance = new TransactionAtomicTest();
    instance.beforeClass();
  }

  /**
   * Original test method: testTransactionAtomic Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/TransactionAtomicTest.java:34
   */
  @Test
  public void test01_TransactionAtomic() {
    var db1 = acquireSession();
    var db2 = acquireSession();

    db2.begin();
    var record1 = ((EntityImpl) db2.newEntity());
    record1
        .setProperty("value", "This is the first version");

    db2.commit();

    // RE-READ THE RECORD
    db2.activateOnCurrentThread();
    db2.begin();
    EntityImpl record2 = db2.load(record1.getIdentity());

    record2.setProperty("value", "This is the second version");

    db2.commit();

    db2.begin();
    var activeTx1 = db2.getActiveTransaction();
    record2 = activeTx1.load(record2);
    record2.setProperty("value", "This is the third version");

    db2.commit();

    db1.activateOnCurrentThread();
    db1.begin();
    var activeTx = db1.getActiveTransaction();
    record1 = activeTx.load(record1);
    Assert.assertEquals("This is the third version", record1.getProperty("value"));
    db1.commit();
    db1.close();

    db2.activateOnCurrentThread();
    db2.close();

    db1.activateOnCurrentThread();
  }

  /**
   * Original test method: testMVCC Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/TransactionAtomicTest.java:76
   */
  @Test
  public void test02_MVCC() throws IOException {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Account"));
    doc.setProperty("version", 0);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setDirty();
    doc.setProperty("testmvcc", true);
    final var rec = (RecordAbstract) doc;
    rec.setVersion(doc.getVersion() + 1);
    try {

      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException e) {
      session.rollback();
    }
  }

  /**
   * Original test method: testTransactionPreListenerRollback Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/TransactionAtomicTest.java:101
   */
  @Test
  public void test03_TransactionPreListenerRollback() throws IOException {
    session.begin();
    var record1 = ((EntityImpl) session.newEntity());

    record1
        .setProperty("value", "This is the first version");

    session.commit();

    final var listener =
        new SessionListener() {
          @Override
          public void onBeforeTxCommit(Transaction transaction) {
            throw new RuntimeException("Rollback test");
          }
        };

    session.registerListener(listener);
    session.begin();

    try {
      session.commit();
      Assert.fail();
    } catch (TransactionException e) {
      Assert.assertTrue(true);
    } finally {
      session.unregisterListener(listener);
    }
  }

  /**
   * Original test method: testTransactionWithDuplicateUniqueIndexValues Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/TransactionAtomicTest.java:132
   */
  @Test
  public void test04_TransactionWithDuplicateUniqueIndexValues() {
    var fruitClass = session.getMetadata().getSchema().getClass("Fruit");

    if (fruitClass == null) {
      fruitClass = session.getMetadata().getSchema().createClass("Fruit");

      fruitClass.createProperty("name", PropertyType.STRING);
      fruitClass.createProperty("color", PropertyType.STRING);

      session
          .getMetadata()
          .getSchema()
          .getClass("Fruit")
          .getProperty("color")
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    }

    Assert.assertEquals(0, session.countCollectionElements("Fruit"));

    try {
      session.begin();

      var apple = ((EntityImpl) session.newEntity("Fruit")).setPropertyInChain("name", "Apple")
          .setPropertyInChain("color", "Red");
      var orange = ((EntityImpl) session.newEntity("Fruit")).setPropertyInChain("name", "Orange")
          .setPropertyInChain("color", "Orange");
      var banana = ((EntityImpl) session.newEntity("Fruit")).setPropertyInChain("name", "Banana")
          .setPropertyInChain("color", "Yellow");
      var kumquat = ((EntityImpl) session.newEntity("Fruit")).setPropertyInChain("name", "Kumquat")
          .setPropertyInChain("color", "Orange");

      session.commit();

      Assert.assertEquals(fruitClass.getCollectionIds()[0], apple.getIdentity().getCollectionId());
      Assert.assertEquals(fruitClass.getCollectionIds()[0], orange.getIdentity().getCollectionId());
      Assert.assertEquals(fruitClass.getCollectionIds()[0], banana.getIdentity().getCollectionId());
      Assert.assertEquals(fruitClass.getCollectionIds()[0],
          kumquat.getIdentity().getCollectionId());

      Assert.fail();

    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
      session.rollback();
    }

    Assert.assertEquals(0, session.countCollectionElements("Fruit"));
  }
}
