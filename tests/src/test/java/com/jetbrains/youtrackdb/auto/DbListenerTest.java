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

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.SessionListener;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.EntityHookAbstract;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests the right calls of all the db's listener API.
 */
@Test
public class DbListenerTest extends BaseDBTest {

  protected int onAfterTxCommit = 0;
  protected int onAfterTxRollback = 0;
  protected int onBeforeTxBegin = 0;
  protected int onBeforeTxCommit = 0;
  protected int onBeforeTxRollback = 0;
  protected int onClose = 0;
  protected String command;

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

  @Test
  public void testEmbeddedDbListeners() throws IOException {
    session = createSessionInstance();
    session.registerListener(new DbListener());

    final var baseOnBeforeTxBegin = onBeforeTxBegin;
    final var baseOnBeforeTxCommit = onBeforeTxCommit;
    final var baseOnAfterTxCommit = onAfterTxCommit;

    session.begin();
    Assert.assertEquals(onBeforeTxBegin, baseOnBeforeTxBegin + 1);

    session
        .newInstance();

    session.commit();
    Assert.assertEquals(onBeforeTxCommit, baseOnBeforeTxCommit + 1);
    Assert.assertEquals(onAfterTxCommit, baseOnAfterTxCommit + 1);

    session.begin();
    Assert.assertEquals(onBeforeTxBegin, baseOnBeforeTxBegin + 2);

    session.newInstance();

    session.rollback();
    Assert.assertEquals(onBeforeTxRollback, 1);
    Assert.assertEquals(onAfterTxRollback, 1);
  }


  @Test
  public void testEmbeddedDbListenersTxRecords() throws IOException {
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

    Assert.assertEquals(cl.getChanges().size(), 1);
  }

  @Test
  public void testEmbeddedDbListenersGraph() throws IOException {
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

    Assert.assertEquals(cl.getChanges().size(), 1);
  }
}
