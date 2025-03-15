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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.tx.RollbackException;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class FrontendTransactionOptimisticTest extends BaseDBTest {

  @Parameters(value = "remote")
  public FrontendTransactionOptimisticTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void testTransactionOptimisticRollback() {
    if (session.getClusterIdByName("binary") == -1) {
      session.addBlobCluster("binary");
    }

    var rec = session.countClusterElements("binary");

    session.begin();

    session.newBlob("This is the first version".getBytes());
    session.rollback();

    Assert.assertEquals(session.countClusterElements("binary"), rec);
  }

  @Test(dependsOnMethods = "testTransactionOptimisticRollback")
  public void testTransactionOptimisticCommit() {
    if (session.getClusterIdByName("binary") == -1) {
      session.addBlobCluster("binary");
    }

    var tot = session.countClusterElements("binary");

    session.begin();

    var recordBytes = session.newBlob("This is the first version".getBytes());
    session.commit();

    Assert.assertEquals(session.countClusterElements("binary"), tot + 1);
  }

  @Test(dependsOnMethods = "testTransactionOptimisticCommit")
  public void testTransactionOptimisticConcurrentException() {
    if (session.getClusterIdByName("binary") == -1) {
      session.addBlobCluster("binary");
    }

    var session2 = acquireSession();
    session.activateOnCurrentThread();
    session.begin();
    var record1 = session.newBlob("This is the first version".getBytes());
    session.commit();

    try {
      session.begin();

      // RE-READ THE RECORD
      record1 = session.load(record1.getIdentity());

      Blob record2 = session2.load(record1.getIdentity());
      RecordInternal.fill(
          record2,
          record2.getIdentity(),
          record2.getVersion(),
          "This is the second version".getBytes(),
          true);
      session2.begin();
      session2.commit();

      RecordInternal.fill(
          record1,
          record1.getIdentity(),
          record1.getVersion(),
          "This is the third version".getBytes(),
          true);

      session.commit();

      Assert.fail();

    } catch (ConcurrentModificationException e) {
      Assert.assertTrue(true);
      session.rollback();

    } finally {
      session.close();

      session2.activateOnCurrentThread();
      session2.close();
    }
  }

  @Test(dependsOnMethods = "testTransactionOptimisticConcurrentException")
  public void testTransactionOptimisticCacheMgmt1Db() throws IOException {
    if (session.getClusterIdByName("binary") == -1) {
      session.addBlobCluster("binary");
    }

    var record = session.newBlob("This is the first version".getBytes());
    session.begin();
    session.commit();

    try {
      session.begin();

      // RE-READ THE RECORD
      record = session.load(record.getIdentity());
      var v1 = record.getVersion();
      RecordInternal.fill(
          record, record.getIdentity(), v1, "This is the second version".getBytes(), true);
      session.commit();

      record = session.bindToSession(record);
      Assert.assertEquals(record.getVersion(), v1 + 1);
      Assert.assertTrue(new String(record.toStream()).contains("second"));
    } finally {
      session.close();
    }
  }

  @Test(dependsOnMethods = "testTransactionOptimisticCacheMgmt1Db")
  public void testTransactionOptimisticCacheMgmt2Db() throws IOException {
    if (session.getClusterIdByName("binary") == -1) {
      session.addBlobCluster("binary");
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
      RecordInternal.fill(
          record1, record1.getIdentity(), v1, "This is the second version".getBytes(), true);

      session.commit();

      db2.activateOnCurrentThread();

      Blob record2 = db2.load(record1.getIdentity());
      Assert.assertEquals(record2.getVersion(), v1 + 1);
      Assert.assertTrue(new String(record2.toStream()).contains("second"));

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

    EntityImpl entity2 = jack.setPropertyInChain("following", new HashSet<EntityImpl>());
    ((HashSet<EntityImpl>) entity2.getProperty("following"))
        .add(kim);
    EntityImpl entity1 = kim.setPropertyInChain("following", new HashSet<EntityImpl>());
    ((HashSet<EntityImpl>) entity1.getProperty("following"))
        .add(teri);
    EntityImpl entity = teri.setPropertyInChain("following", new HashSet<EntityImpl>());
    ((HashSet<EntityImpl>) entity.getProperty("following"))
        .add(jack);

    session.commit();

    session.close();
    session = acquireSession();

    EntityImpl loadedJack = session.load(jack.getIdentity());
    Assert.assertEquals(loadedJack.getProperty("name"), "Jack");
    Collection<Identifiable> jackFollowings = loadedJack.getProperty("following");
    Assert.assertNotNull(jackFollowings);
    Assert.assertEquals(jackFollowings.size(), 1);

    var loadedKim = jackFollowings.iterator().next().getEntity(session);
    Assert.assertEquals(loadedKim.getProperty("name"), "Kim");
    Collection<Identifiable> kimFollowings = loadedKim.getProperty("following");
    Assert.assertNotNull(kimFollowings);
    Assert.assertEquals(kimFollowings.size(), 1);

    var loadedTeri = kimFollowings.iterator().next().getEntity(session);
    Assert.assertEquals(loadedTeri.getProperty("name"), "Teri");
    Collection<Identifiable> teriFollowings = loadedTeri.getProperty("following");
    Assert.assertNotNull(teriFollowings);
    Assert.assertEquals(teriFollowings.size(), 1);

    Assert.assertEquals(teriFollowings.iterator().next().getEntity(session).getProperty("name"),
        "Jack");

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

    var brokenDocOne = ((EntityImpl) session.newEntity("NestedTxRollbackOne"));
    session.begin();

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

      brokenDocOne = session.bindToSession(brokenDocOne);
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
                  db.executeInTx(() -> {
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
