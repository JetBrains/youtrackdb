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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests the right calls of all the db's listener API.
 */
public class DbListenerTest extends BaseDBJUnit5Test {

  protected int onAfterTxCommit = 0;
  protected int onAfterTxRollback = 0;
  protected int onBeforeTxBegin = 0;
  protected int onBeforeTxCommit = 0;
  protected int onBeforeTxRollback = 0;
  protected int onClose = 0;
  protected String command;

  public static class DocumentChangeListener {

    final Map<Entity, List<String>> changes = new HashMap<>();

    public DocumentChangeListener(final DatabaseSessionEmbedded db) {
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
    public void onClose(DatabaseSessionEmbedded iDatabase) {
      onClose++;
    }
  }

  @Test
  @Order(1)
  void testEmbeddedDbListeners() throws IOException {
    session = createSessionInstance();
    session.registerListener(new DbListener());

    final var baseOnBeforeTxBegin = onBeforeTxBegin;
    final var baseOnBeforeTxCommit = onBeforeTxCommit;
    final var baseOnAfterTxCommit = onAfterTxCommit;

    session.begin();
    assertEquals(baseOnBeforeTxBegin + 1, onBeforeTxBegin);

    session
        .newInstance();

    session.commit();
    assertEquals(baseOnBeforeTxCommit + 1, onBeforeTxCommit);
    assertEquals(baseOnAfterTxCommit + 1, onAfterTxCommit);

    session.begin();
    assertEquals(baseOnBeforeTxBegin + 2, onBeforeTxBegin);

    session.newInstance();

    session.rollback();
    assertEquals(1, onBeforeTxRollback);
    assertEquals(1, onAfterTxRollback);
  }

  @Test
  @Order(2)
  void testEmbeddedDbListenersTxRecords() throws IOException {
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

    assertEquals(1, cl.getChanges().size());
  }

  @Test
  @Order(3)
  void testEmbeddedDbListenersGraph() throws IOException {
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

    assertEquals(1, cl.getChanges().size());
  }
}
