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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.EntityHookAbstract;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of DbListenerTest. Original test class:
 * com.jetbrains.youtrackdb.auto.DbListenerTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbListenerTest.java
 * <p>
 * Tests the right calls of all the db's listener API.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DbListenerTest extends BaseDBTest {

  protected int onAfterTxCommit = 0;
  protected int onAfterTxRollback = 0;
  protected int onBeforeTxBegin = 0;
  protected int onBeforeTxCommit = 0;
  protected int onBeforeTxRollback = 0;
  protected int onClose = 0;
  protected String command;

  private static boolean initialized = false;

  @BeforeClass
  public static void setUpClass() throws Exception {
    DbListenerTest instance = new DbListenerTest();
    instance.beforeClass();
    initialized = true;
  }

  /**
   * Original nested class: DocumentChangeListener Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbListenerTest.java:46
   */
  public static class DocumentChangeListener {

    final Map<Entity, List<String>> changes = new HashMap<>();

    public DocumentChangeListener(final DatabaseSession db) {
      db.registerHook(
          new EntityHookAbstract() {

            @Override
            public void onBeforeEntityUpdate(Entity entity) {
              List<String> changedFields = new ArrayList<>(
                  entity.getDirtyPropertiesBetweenCallbacks());
              changes.put(entity, changedFields);
            }
          });
    }

    public Map<Entity, List<String>> getChanges() {
      return changes;
    }
  }

  /**
   * Original nested class: DbListener Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbListenerTest.java:68
   */
  public class DbListener implements SessionListener {

    @Override
    public void onAfterTxCommit(Transaction transaction, Map<RID, RID> ridMapping) {
      onAfterTxCommit++;
    }

    @Override
    public void onAfterTxRollback(Transaction transaction) {
      onAfterTxRollback++;
    }

    @Override
    public void onBeforeTxBegin(Transaction transaction) {
      onBeforeTxBegin++;
    }

    @Override
    public void onBeforeTxCommit(Transaction transaction) {
      onBeforeTxCommit++;
    }

    @Override
    public void onBeforeTxRollback(Transaction transaction) {
      onBeforeTxRollback++;
    }

    @Override
    public void onClose(DatabaseSession iDatabase) {
      onClose++;
    }
  }

  /**
   * Original test method: testEmbeddedDbListeners Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbListenerTest.java:101
   */
  @Test
  public void test01_EmbeddedDbListeners() throws IOException {
    session = createSessionInstance();
    session.registerListener(new DbListener());

    final var baseOnBeforeTxBegin = onBeforeTxBegin;
    final var baseOnBeforeTxCommit = onBeforeTxCommit;
    final var baseOnAfterTxCommit = onAfterTxCommit;

    session.begin();
    Assert.assertEquals(baseOnBeforeTxBegin + 1, onBeforeTxBegin);

    session
        .newInstance();

    session.commit();
    Assert.assertEquals(baseOnBeforeTxCommit + 1, onBeforeTxCommit);
    Assert.assertEquals(baseOnAfterTxCommit + 1, onAfterTxCommit);

    session.begin();
    Assert.assertEquals(baseOnBeforeTxBegin + 2, onBeforeTxBegin);

    session.newInstance();

    session.rollback();
    Assert.assertEquals(1, onBeforeTxRollback);
    Assert.assertEquals(1, onAfterTxRollback);
  }

  /**
   * Original test method: testEmbeddedDbListenersTxRecords Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbListenerTest.java:131
   */
  @Test
  public void test02_EmbeddedDbListenersTxRecords() throws IOException {
    session = createSessionInstance();

    session.begin();
    var rec =
        session
            .newInstance()
            .setPropertyInChain("name", "Jay");

    session.commit();

    final var cl = new DocumentChangeListener(session);

    session.begin();
    var activeTx = session.getActiveTransaction();
    rec = activeTx.load(rec);
    rec.setProperty("surname", "Miner");

    session.commit();

    Assert.assertEquals(1, cl.getChanges().size());
  }

  /**
   * Original test method: testEmbeddedDbListenersGraph Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbListenerTest.java:155
   */
  @Test
  public void test03_EmbeddedDbListenersGraph() throws IOException {
    session = createSessionInstance();

    session.begin();
    var v = session.newVertex();
    v.setProperty("name", "Jay");

    session.commit();
    session.begin();
    final var cl = new DocumentChangeListener(session);

    var activeTx = session.getActiveTransaction();
    v = activeTx.load(v);
    v.setProperty("surname", "Miner");
    session.commit();
    session.close();

    Assert.assertEquals(1, cl.getChanges().size());
  }
}
