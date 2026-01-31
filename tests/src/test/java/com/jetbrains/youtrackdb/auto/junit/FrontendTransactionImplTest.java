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

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.RollbackException;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for transaction implementation including optimistic locking, rollback, and nested
 * transactions.
 *
 * <p><b>Suite Dependency:</b> This test is part of {@link DatabaseTestSuite} and depends on
 * the basic schema created by earlier tests. Can be run individually as the {@code @BeforeClass}
 * method initializes the required schema.</p>
 *
 * <p>Original test class: {@code com.jetbrains.youtrackdb.auto.FrontendTransactionImplTest}</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FrontendTransactionImplTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    FrontendTransactionImplTest instance = new FrontendTransactionImplTest();
    instance.beforeClass();
  }

  /**
   * Original test method: testTransactionOptimisticRollback Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:37
   */
  @Test
  public void test01_TransactionOptimisticRollback() {
    if (session.getCollectionIdByName("binary") == -1) {
      session.addBlobCollection("binary");
    }

    var rec = session.countCollectionElements("binary");

    session.begin();

    session.newBlob("This is the first version".getBytes());
    session.rollback();

    Assert.assertEquals(rec, session.countCollectionElements("binary"));
  }

  /**
   * Original test method: testTransactionOptimisticCommitInternal Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:53 Depends
   * on: testTransactionOptimisticRollback
   */
  @Test
  public void test02_TransactionOptimisticCommitInternal() {

    session.begin();
    final var blocCollectionIds = session.getBlobCollectionIds();
    var tot = session.countCollectionElements(blocCollectionIds);
    session.commit();

    session.begin();
    session.newBlob("This is the first version".getBytes());
    session.commit();

    Assert.assertEquals(tot + 1, session.countCollectionElements(blocCollectionIds));
  }

  /**
   * Original test method: testTransactionOptimisticConcurrentException Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:68 Depends
   * on: testTransactionOptimisticCommitInternal
   */
  @Test
  public void test03_TransactionOptimisticConcurrentException() {
    var session2 = acquireSession();
    session.activateOnCurrentThread();
    session.begin();
    var record = session.newBlob("This is the first version".getBytes());
    session.commit();

    try {
      session.begin();
      session2.begin();

      // RE-READ THE RECORD
      var record1 = session.load(record.getIdentity());
      var record2 = session2.load(record.getIdentity());

      final RID rid2 = record2.getIdentity();
      final int version2 = record2.getVersion();
      final var rec2 = (RecordAbstract) record2;
      rec2.fill(version2, "This is the second version".getBytes(), true);

      final RID rid1 = record1.getIdentity();
      final int version1 = record1.getVersion();
      final var rec1 = (RecordAbstract) record1;
      rec1.fill(version1, "This is the third version".getBytes(), true);

      session.commit();
      session2.commit();

      Assert.fail();

    } catch (ConcurrentModificationException e) {
      Assert.assertTrue(true);
      session.rollback();
      session2.rollback();

    } finally {
      session.close();

      session2.activateOnCurrentThread();
      session2.close();
    }
  }

  /**
   * Original test method: testTransactionOptimisticCacheMgmt1Db Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:112 Depends
   * on: testTransactionOptimisticConcurrentException
   */
  @Test
  public void test04_TransactionOptimisticCacheMgmt1Db() throws IOException {
    if (session.getCollectionIdByName("binary") == -1) {
      session.addBlobCollection("binary");
    }

    session.begin();
    var record = session.newBlob("This is the first version".getBytes());
    session.commit();

    try {
      session.begin();

      // RE-READ THE RECORD
      record = session.load(record.getIdentity());
      var v1 = record.getVersion();
      final RID iRid = record.getIdentity();
      final var rec = (RecordAbstract) record;
      rec.fill(v1, "This is the second version".getBytes(), true);
      session.commit();

      session.begin();
      var activeTx = session.getActiveTransaction();
      record = activeTx.load(record);
      Assert.assertEquals(v1 + 1, record.getVersion());
      Assert.assertTrue(new String(record.toStream()).contains("second"));
      session.commit();
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testTransactionOptimisticCacheMgmt2Db Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:144 Depends
   * on: testTransactionOptimisticCacheMgmt1Db
   */
  @Test
  public void test05_TransactionOptimisticCacheMgmt2Db() throws IOException {
    if (session.getCollectionIdByName("binary") == -1) {
      session.addBlobCollection("binary");
    }

    var db2 = acquireSession();
    db2.begin();
    var record1 = db2.newBlob("This is the first version".getBytes());
    db2.commit();
    try {
      session.begin();

      // RE-READ THE RECORD
      record1 = session.load(record1.getIdentity());
      var v1 = record1.getVersion();
      final RID iRid = record1.getIdentity();
      final var rec = (RecordAbstract) record1;
      rec.fill(v1, "This is the second version".getBytes(), true);

      session.commit();

      db2.activateOnCurrentThread();
      db2.begin();
      Blob record2 = db2.load(record1.getIdentity());
      Assert.assertEquals(v1 + 1, record2.getVersion());
      Assert.assertTrue(new String(record2.toStream()).contains("second"));
      db2.commit();

    } finally {

      session.activateOnCurrentThread();
      session.close();

      db2.activateOnCurrentThread();
      db2.close();
    }
  }

  /**
   * Original test method: testTransactionMultipleRecords Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:183 Depends
   * on: testTransactionOptimisticCacheMgmt2Db
   */
  @Test
  public void test06_TransactionMultipleRecords() throws IOException {
    final Schema schema = session.getMetadata().getSchema();

    if (!schema.existsClass("Account")) {
      schema.createClass("Account");
    }

    var totalAccounts = session.countClass("Account");

    var json =
        "{ \"@class\": \"Account\", \"type\": \"Residence\", \"street\": \"Piazza di Spagna\"}";

    session.begin();
    for (var g = 0; g < 1000; g++) {
      var doc = ((EntityImpl) session.newEntity("Account"));
      doc.updateFromJSON(json);
      doc.setProperty("nr", g);

    }
    session.commit();

    Assert.assertEquals(totalAccounts + 1000, session.countClass("Account"));

    session.close();
  }

  /**
   * Original test method: createGraphInTx Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:210
   */
  @SuppressWarnings("unchecked")
  @Test
  public void test07_CreateGraphInTx() {
    final Schema schema = session.getMetadata().getSchema();

    if (!schema.existsClass("Profile")) {
      schema.createClass("Profile");
    }

    session.begin();

    var kim = ((EntityImpl) session.newEntity("Profile")).setPropertyInChain("name", "Kim")
        .setPropertyInChain("surname", "Bauer");
    var teri = ((EntityImpl) session.newEntity("Profile")).setPropertyInChain("name", "Teri")
        .setPropertyInChain("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("Profile")).setPropertyInChain("name", "Jack")
        .setPropertyInChain("surname", "Bauer");

    jack.getOrCreateLinkSet("following").add(kim);
    kim.getOrCreateLinkSet("following").add(teri);
    teri.getOrCreateLinkSet("following").add(jack);

    session.commit();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedJack = session.load(jack.getIdentity());
    Assert.assertEquals("Jack", loadedJack.getProperty("name"));
    Collection<Identifiable> jackFollowings = loadedJack.getProperty("following");
    Assert.assertNotNull(jackFollowings);
    Assert.assertEquals(1, jackFollowings.size());

    Identifiable identifiable2 = jackFollowings.iterator().next();
    var transaction2 = session.getActiveTransaction();
    var loadedKim = transaction2.loadEntity(identifiable2);
    Assert.assertEquals("Kim", loadedKim.getProperty("name"));
    Collection<Identifiable> kimFollowings = loadedKim.getProperty("following");
    Assert.assertNotNull(kimFollowings);
    Assert.assertEquals(1, kimFollowings.size());

    Identifiable identifiable1 = kimFollowings.iterator().next();
    var transaction1 = session.getActiveTransaction();
    var loadedTeri = transaction1.loadEntity(identifiable1);
    Assert.assertEquals("Teri", loadedTeri.getProperty("name"));
    Collection<Identifiable> teriFollowings = loadedTeri.getProperty("following");
    Assert.assertNotNull(teriFollowings);
    Assert.assertEquals(1, teriFollowings.size());

    Identifiable identifiable = teriFollowings.iterator().next();
    var transaction = session.getActiveTransaction();
    Assert.assertEquals("Jack", transaction.loadEntity(identifiable).getProperty("name"));

    session.commit();
    session.close();
  }

  /**
   * Original test method: testNestedTx Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:267
   */
  @Test
  public void test08_NestedTx() throws Exception {
    final var executorService = Executors.newSingleThreadExecutor();

    final var assertEmptyRecord =
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            final var db = acquireSession();
            try {
              Assert.assertEquals(0, db.countClass("NestedTxClass"));
            } finally {
              db.close();
            }

            return null;
          }
        };

    final Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("NestedTxClass")) {
      schema.createClass("NestedTxClass");
    }

    session.begin();

    final var externalDocOne = ((EntityImpl) session.newEntity("NestedTxClass"));
    externalDocOne.setProperty("v", "val1");

    Future assertFuture = executorService.submit(assertEmptyRecord);
    assertFuture.get();

    session.begin();

    final var externalDocTwo = ((EntityImpl) session.newEntity("NestedTxClass"));
    externalDocTwo.setProperty("v", "val2");

    assertFuture = executorService.submit(assertEmptyRecord);
    assertFuture.get();

    session.commit();

    assertFuture = executorService.submit(assertEmptyRecord);
    assertFuture.get();

    final var externalDocThree = ((EntityImpl) session.newEntity("NestedTxClass"));
    externalDocThree.setProperty("v", "val3");

    session.commit();

    Assert.assertFalse(session.getTransactionInternal().isActive());
    Assert.assertEquals(3, session.countClass("NestedTxClass"));
  }

  /**
   * Original test method: testNestedTxRollbackOne Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:320
   */
  @Test
  public void test09_NestedTxRollbackOne() throws Exception {
    final var executorService = Executors.newSingleThreadExecutor();

    final var assertEmptyRecord =
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            final var db = acquireSession();
            try {
              Assert.assertEquals(1, db.countClass("NestedTxRollbackOne"));
            } finally {
              db.close();
            }

            return null;
          }
        };

    final Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("NestedTxRollbackOne")) {
      schema.createClass("NestedTxRollbackOne");
    }

    session.begin();
    var brokenDocOne = ((EntityImpl) session.newEntity("NestedTxRollbackOne"));

    session.commit();
    try {
      session.begin();

      final var externalDocOne = ((EntityImpl) session.newEntity("NestedTxRollbackOne"));
      externalDocOne.setProperty("v", "val1");

      Future assertFuture = executorService.submit(assertEmptyRecord);
      assertFuture.get();

      session.begin();
      var externalDocTwo = ((EntityImpl) session.newEntity("NestedTxRollbackOne"));
      externalDocTwo.setProperty("v", "val2");

      assertFuture = executorService.submit(assertEmptyRecord);
      assertFuture.get();

      var activeTx = session.getActiveTransaction();
      brokenDocOne = activeTx.load(brokenDocOne);
      brokenDocOne.setDirty();

      session.commit();

      assertFuture = executorService.submit(assertEmptyRecord);
      assertFuture.get();

      final var externalDocThree = ((EntityImpl) session.newEntity("NestedTxRollbackOne"));
      externalDocThree.setProperty("v", "val3");

      session.begin();

      session.commit();

      var brokenRid = brokenDocOne.getIdentity();
      executorService
          .submit(
              () -> {
                try (var db = acquireSession()) {
                  db.executeInTx(transaction -> {
                    EntityImpl brokenDocTwo = db.load(brokenRid);
                    brokenDocTwo.setProperty("v", "vstr");

                  });
                }
              }).get();

      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException e) {
      session.rollback();
    }

    Assert.assertFalse(session.getTransactionInternal().isActive());
    Assert.assertEquals(1, session.countClass("NestedTxRollbackOne"));
  }

  /**
   * Original test method: testNestedTxRollbackTwo Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/FrontendTransactionImplTest.java:402
   */
  @Test
  public void test10_NestedTxRollbackTwo() {
    final Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("NestedTxRollbackTwo")) {
      schema.createClass("NestedTxRollbackTwo");
    }

    session.begin();
    try {
      final var externalDocOne = ((EntityImpl) session.newEntity("NestedTxRollbackTwo"));
      externalDocOne.setProperty("v", "val1");

      session.begin();

      final var externalDocTwo = ((EntityImpl) session.newEntity("NestedTxRollbackTwo"));
      externalDocTwo.setProperty("v", "val2");

      session.rollback();

      session.begin();
      Assert.fail();
    } catch (RollbackException e) {
      session.rollback();
    }

    Assert.assertFalse(session.getTransactionInternal().isActive());
    Assert.assertEquals(0, session.countClass("NestedTxRollbackTwo"));
  }
}
