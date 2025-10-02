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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.record.Blob;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.RollbackException;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class FrontendTransactionImplTest extends BaseDBTest {
  @Test
  public void testTransactionOptimisticRollback() {
    if (session.getCollectionIdByName("binary") == -1) {
      session.addBlobCollection("binary");
    }

    var rec = session.countCollectionElements("binary");

    session.begin();

    session.newBlob("This is the first version".getBytes());
    session.rollback();

    Assert.assertEquals(session.countCollectionElements("binary"), rec);
  }

  @Test(dependsOnMethods = "testTransactionOptimisticRollback")
  public void testTransactionOptimisticCommitInternal() {

    session.begin();
    final var blocCollectionIds = session.getBlobCollectionIds();
    var tot = session.countCollectionElements(blocCollectionIds);
    session.commit();

    session.begin();
    session.newBlob("This is the first version".getBytes());
    session.commit();

    Assert.assertEquals(session.countCollectionElements(blocCollectionIds), tot + 1);
  }

  @Test(dependsOnMethods = "testTransactionOptimisticCommitInternal")
  public void testTransactionOptimisticConcurrentException() {
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

  @Test(dependsOnMethods = "testTransactionOptimisticConcurrentException")
  public void testTransactionOptimisticCacheMgmt1Db() throws IOException {
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
      Assert.assertEquals(record.getVersion(), v1 + 1);
      Assert.assertTrue(new String(record.toStream()).contains("second"));
      session.commit();
    } finally {
      session.close();
    }
  }

  @Test(dependsOnMethods = "testTransactionOptimisticCacheMgmt1Db")
  public void testTransactionOptimisticCacheMgmt2Db() throws IOException {
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
      Assert.assertEquals(record2.getVersion(), v1 + 1);
      Assert.assertTrue(new String(record2.toStream()).contains("second"));
      db2.commit();

    } finally {

      session.activateOnCurrentThread();
      session.close();

      db2.activateOnCurrentThread();
      db2.close();
    }
  }

  @Test(dependsOnMethods = "testTransactionOptimisticCacheMgmt2Db")
  public void testTransactionMultipleRecords() throws IOException {
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

    Assert.assertEquals(session.countClass("Account"), totalAccounts + 1000);

    session.close();
  }

  @SuppressWarnings("unchecked")
  public void createGraphInTx() {
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
    Assert.assertEquals(loadedJack.getProperty("name"), "Jack");
    Collection<Identifiable> jackFollowings = loadedJack.getProperty("following");
    Assert.assertNotNull(jackFollowings);
    Assert.assertEquals(jackFollowings.size(), 1);

    Identifiable identifiable2 = jackFollowings.iterator().next();
    var transaction2 = session.getActiveTransaction();
    var loadedKim = transaction2.loadEntity(identifiable2);
    Assert.assertEquals(loadedKim.getProperty("name"), "Kim");
    Collection<Identifiable> kimFollowings = loadedKim.getProperty("following");
    Assert.assertNotNull(kimFollowings);
    Assert.assertEquals(kimFollowings.size(), 1);

    Identifiable identifiable1 = kimFollowings.iterator().next();
    var transaction1 = session.getActiveTransaction();
    var loadedTeri = transaction1.loadEntity(identifiable1);
    Assert.assertEquals(loadedTeri.getProperty("name"), "Teri");
    Collection<Identifiable> teriFollowings = loadedTeri.getProperty("following");
    Assert.assertNotNull(teriFollowings);
    Assert.assertEquals(teriFollowings.size(), 1);

    Identifiable identifiable = teriFollowings.iterator().next();
    var transaction = session.getActiveTransaction();
    Assert.assertEquals(transaction.loadEntity(identifiable).getProperty("name"),
        "Jack");

    session.commit();
    session.close();
  }

  public void testNestedTx() throws Exception {
    final var executorService = Executors.newSingleThreadExecutor();

    final var assertEmptyRecord =
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            final var db = acquireSession();
            try {
              Assert.assertEquals(db.countClass("NestedTxClass"), 0);
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
    Assert.assertEquals(session.countClass("NestedTxClass"), 3);
  }

  public void testNestedTxRollbackOne() throws Exception {
    final var executorService = Executors.newSingleThreadExecutor();

    final var assertEmptyRecord =
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            final var db = acquireSession();
            try {
              Assert.assertEquals(db.countClass("NestedTxRollbackOne"), 1);
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
    Assert.assertEquals(session.countClass("NestedTxRollbackOne"), 1);
  }

  public void testNestedTxRollbackTwo() {
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
    Assert.assertEquals(session.countClass("NestedTxRollbackTwo"), 0);
  }
}
