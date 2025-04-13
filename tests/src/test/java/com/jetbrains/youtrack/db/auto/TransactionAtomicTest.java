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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.api.exception.TransactionException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import java.io.IOException;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class TransactionAtomicTest extends BaseDBTest {

  @Parameters(value = "remote")
  public TransactionAtomicTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void testTransactionAtomic() {
    DatabaseSessionInternal db1 = acquireSession();
    DatabaseSessionInternal db2 = acquireSession();

    EntityImpl record1 = new EntityImpl();

    db2.begin();
    record1
        .field("value", "This is the first version")
        .save(db2.getClusterNameById(db2.getDefaultClusterId()));
    db2.commit();

    // RE-READ THE RECORD
    db2.activateOnCurrentThread();
    db2.begin();
    EntityImpl record2 = db2.load(record1.getIdentity());

    record2.field("value", "This is the second version").save();
    db2.commit();

    db2.begin();
    record2 = db2.bindToSession(record2);
    record2.field("value", "This is the third version").save();
    db2.commit();

    db1.activateOnCurrentThread();
    record1 = db1.bindToSession(record1);
    Assert.assertEquals(record1.field("value"), "This is the third version");
    db1.close();

    db2.activateOnCurrentThread();
    db2.close();

    database.activateOnCurrentThread();
  }

  @Test
  public void testMVCC() throws IOException {

    EntityImpl doc = new EntityImpl("Account");
    database.begin();
    doc.field("version", 0);
    doc.save();
    database.commit();

    database.begin();
    doc = database.bindToSession(doc);
    doc.setDirty();
    doc.field("testmvcc", true);
    RecordInternal.setVersion(doc, doc.getVersion() + 1);
    try {
      doc.save();
      database.commit();
      Assert.fail();
    } catch (ConcurrentModificationException e) {
      Assert.assertTrue(true);
    }
  }

  @Test
  public void testTransactionPreListenerRollback() throws IOException {
    EntityImpl record1 = new EntityImpl();

    database.begin();
    record1
        .field("value", "This is the first version")
        .save(database.getClusterNameById(database.getDefaultClusterId()));
    database.commit();

    final SessionListener listener =
        new SessionListener() {

          @Override
          public void onAfterTxCommit(DatabaseSession iDatabase) {
          }

          @Override
          public void onAfterTxRollback(DatabaseSession iDatabase) {
          }

          @Override
          public void onBeforeTxBegin(DatabaseSession iDatabase) {
          }

          @Override
          public void onBeforeTxCommit(DatabaseSession iDatabase) {
            throw new RuntimeException("Rollback test");
          }

          @Override
          public void onBeforeTxRollback(DatabaseSession iDatabase) {
          }

          @Override
          public void onClose(DatabaseSession iDatabase) {
          }
        };

    database.registerListener(listener);
    database.begin();

    try {
      database.commit();
      Assert.fail();
    } catch (TransactionException e) {
      Assert.assertTrue(true);
    } finally {
      database.unregisterListener(listener);
    }
  }

  @Test
  public void testTransactionWithDuplicateUniqueIndexValues() {
    SchemaClass fruitClass = database.getMetadata().getSchema().getClass("Fruit");

    if (fruitClass == null) {
      fruitClass = database.getMetadata().getSchema().createClass("Fruit");

      fruitClass.createProperty(database, "name", PropertyType.STRING);
      fruitClass.createProperty(database, "color", PropertyType.STRING);

      database
          .getMetadata()
          .getSchema()
          .getClass("Fruit")
          .getProperty(database,"color")
          .createIndex(database, SchemaClass.INDEX_TYPE.UNIQUE);
    }

    Assert.assertEquals(database.countClusterElements("Fruit"), 0);

    try {
      database.begin();

      EntityImpl apple = new EntityImpl("Fruit").field("name", "Apple").field("color", "Red");
      EntityImpl orange = new EntityImpl("Fruit").field("name", "Orange")
          .field("color", "Orange");
      EntityImpl banana = new EntityImpl("Fruit").field("name", "Banana")
          .field("color", "Yellow");
      EntityImpl kumquat = new EntityImpl("Fruit").field("name", "Kumquat")
          .field("color", "Orange");

      apple.save();
      orange.save();
      banana.save();
      kumquat.save();

      database.commit();

      Assert.assertEquals(apple.getIdentity().getClusterId(), fruitClass.getClusterIds()[0]);
      Assert.assertEquals(orange.getIdentity().getClusterId(), fruitClass.getClusterIds()[0]);
      Assert.assertEquals(banana.getIdentity().getClusterId(), fruitClass.getClusterIds()[0]);
      Assert.assertEquals(kumquat.getIdentity().getClusterId(), fruitClass.getClusterIds()[0]);

      Assert.fail();

    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
      database.rollback();
    }

    Assert.assertEquals(database.countClusterElements("Fruit"), 0);
  }

  @Test
  public void testTransactionalSQL() {
    long prev = database.countClass("Account");

    database.begin();
    database
        .command(new CommandSQL("transactional insert into Account set name = 'txTest1'"))
        .execute(database);
    database.commit();

    Assert.assertEquals(database.countClass("Account"), prev + 1);
  }

  @Test
  public void testTransactionalSQLJoinTx() {
    long prev = database.countClass("Account");

    database.begin();
    database
        .command(new CommandSQL("transactional insert into Account set name = 'txTest2'"))
        .execute(database);

    Assert.assertTrue(database.getTransaction().isActive());

    if (!remoteDB) {
      Assert.assertEquals(database.countClass("Account"), prev + 1);
    }

    database.commit();

    Assert.assertFalse(database.getTransaction().isActive());
    Assert.assertEquals(database.countClass("Account"), prev + 1);
  }
}
