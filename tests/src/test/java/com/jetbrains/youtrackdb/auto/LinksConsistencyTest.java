package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.EntityHookAbstract;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LinksConsistencyTest extends BaseDBTest {

  @Test
  public void selfModificationsAreProhibitedForOnBefore() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("SelfModificationsAreProhibited"));

    var entity = session.computeInTx(
        transaction -> transaction.newEntity("SelfModificationsAreProhibited"));

    session.registerHook(new EntityHookAbstract() {
      @Override
      public void onBeforeEntityUpdate(Entity entity) {
        if ("SelfModificationsAreProhibited".equals(entity.getSchemaClassName())) {
          entity.setString("value", "test");
        }
      }
    });

    try {
      session.executeInTx(transaction -> {
        transaction.loadEntity(entity).setString("string", "test");
      });
      Assert.fail("Should have thrown an exception");
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void testAddLinkInCallback() {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("AddLinkInCallback").
            createSchemaClass("AddLinkInCallbackLink")
    );

    var entity = session.computeInTx(
        transaction -> transaction.newEntity("AddLinkInCallback"));

    var hook = session.registerHook(new EntityHookAbstract() {
      @Override
      public void onAfterEntityUpdate(Entity entity) {
        if ("AddLinkInCallback".equals(entity.getSchemaClassName())) {
          if (entity.getLink("link") == null) {
            var session = entity.getBoundedToSession();
            var tx = session.getActiveTransaction();
            var link = tx.newEntity("AddLinkInCallbackLink");
            entity.setLink("link", link);
          }
        }
      }
    });

    session.executeInTx(transaction -> {
      transaction.loadEntity(entity).setString("string", "test");
    });

    session.unregisterHook(hook);

    session.executeInTx(transaction -> {
      var e = transaction.loadEntity(entity);
      var link = e.getEntity("link");
      Assert.assertNotNull(link);
      link.delete();
    });

    session.executeInTx(transaction -> {
      var e = transaction.loadEntity(entity);
      Assert.assertNull(e.getLink("link"));
    });
  }
}
