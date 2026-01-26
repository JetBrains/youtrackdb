package com.jetbrains.youtrackdb.internal.server.tx;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.server.BaseServerMemoryDatabase;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;

public class RemoteTransactionSupportTest extends BaseServerMemoryDatabase {
  @Override
  public void beforeTest() {
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
    super.beforeTest();

    // Remote connections require .iterate() for schema commands
    traversal.command("create class SomeTx extends V").iterate();
    traversal.command("create class SomeTx2 extends V").iterate();
    traversal.command("create class IndexedTx extends V").iterate();

    traversal.command("create property IndexedTx.name STRING").iterate();
    traversal.command("create index IndexedTx.name on IndexedTx (name) NOTUNIQUE").iterate();

    traversal.command("create class UniqueIndexedTx extends V").iterate();
    traversal.command("create property UniqueIndexedTx.name STRING").iterate();
    traversal.command("create index UniqueIndexedTx.name on UniqueIndexedTx (name) UNIQUE").iterate();
  }

  @Test
  public void testUpdateInTxTransaction() {
    var id = traversal.computeInTx(g -> {
      var vId = g.addV("SomeTx").property("name", "Joe").id().next();
      var updateVertices = g.V(vId).property("name", "Jane").
          V().has("SomeTx", "name", "Jane").property("name", "July").
          V().has("SomeTx", "name", "July").count().next();
      Assert.assertEquals(1L, updateVertices.longValue());
      return vId;
    });

    var v = traversal.computeInTx(g ->
        g.V(id).next()
    );

    Assert.assertEquals("July", v.<String>value("name"));
  }

  @Test
  public void testRollbackTxTransactionScript() {
    var vId = traversal.computeInTx(g ->
        g.addV("SomeTx").property("name", "Joe").id().next()
    );

    var rollback = new AtomicBoolean(false);
    try {
      traversal.executeInTx(g -> {
        var updateVertices = g.V(vId).property("name", "Jane").
            V().has("SomeTx", "name", "Jane").property("name", "July").
            V().has("SomeTx", "name", "July").count().next();
        Assert.assertEquals(1L, updateVertices.longValue());

        rollback.set(true);
        g.inject(1).fail().iterate();
      });
    } catch (Exception e) {
      //ignore
    }

    Assert.assertTrue(rollback.get());

    var v = traversal.computeInTx(g ->
        g.V(vId).next()
    );

    Assert.assertEquals("Joe", v.<String>value("name"));
  }


  @Test
  public void testDuplicateIndexTxScriptOne() {
    traversal.autoExecuteInTx(g ->
        g.addV("UniqueIndexedTx").property("name", "a")
    );

    try {
      traversal.autoExecuteInTx(g ->
          g.addV("UniqueIndexedTx").property("name", "a")
      );
    } catch (Exception e) {
      //ignore
    }

    var count = traversal.computeInTx(g ->
        g.V().has("UniqueIndexedTx", "name", "a").count().next()
    );

    Assert.assertEquals(1L, count.longValue());
  }

  @Test
  public void testDuplicateIndexTxScriptTwo() {
    try {
      traversal.autoExecuteInTx(g ->
          g.addV("UniqueIndexedTx").property("name", "a").
              addV("UniqueIndexedTx").property("name", "a")
      );
    } catch (Exception e) {
      //ignore
    }

    var count = traversal.computeInTx(g ->
        g.V().has("UniqueIndexedTx", "name", "a").count().next()
    );
    Assert.assertEquals(0L, count.longValue());
  }
}
