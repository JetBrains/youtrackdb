package com.jetbrains.youtrack.db.internal.server.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.EntityHookAbstract;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.tools.config.ServerHookConfiguration;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class RemoteTransactionHookTest extends DbTestBase {

  private static final String SERVER_DIRECTORY = "./target/hook-transaction";
  private YouTrackDBServer server;

  @Before
  public void beforeTest() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    var hookConfig = new ServerHookConfiguration();
    hookConfig.clazz = CountCallHookServer.class.getName();
    server.getHookManager().addHook(hookConfig);
    server.activate();

    super.beforeTest();

    session.createClass("SomeTx");
  }

  @Override
  protected YouTrackDBImpl createContext() {
    var builder = YouTrackDBConfig.builder();
    var config = createConfig((YouTrackDBConfigBuilderImpl) builder);

    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      dbType = DatabaseType.DISK;
    } else {
      dbType = DatabaseType.MEMORY;
    }

    return (YouTrackDBImpl) YourTracks.remote("localhost", "root", "root", config);
  }

  @After
  public void afterTest() {
    super.afterTest();

    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  @Ignore
  public void testCalledInTx() {
    var calls = new CountCallHook(session);
    session.registerHook(calls);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("SomeTx"));
    doc.setProperty("name", "some");
    session.execute("insert into SomeTx set name='aa' ").close();
    var res = session.execute("update SomeTx set name='bb' where name=\"some\"");
    assertEquals((Long) 1L, res.next().getProperty("count"));
    res.close();
    session.execute("delete from SomeTx where name='aa'").close();
    session.commit();

    assertEquals(2, calls.beforeCreate);
    assertEquals(2, calls.afterCreate);
  }

  @Test
  public void testCalledInClientTx() {
    YouTrackDB youTrackDB = new YouTrackDBImpl("embedded:", YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database test memory users (admin identified by 'admin' role admin)");
    var session = youTrackDB.open("test", "admin", "admin");
    var calls = new CountCallHook(session);
    session.registerHook(calls);
    session.getSchema().getOrCreateClass("SomeTx");
    var tx = session.begin();
    var doc = ((EntityImpl) tx.newEntity("SomeTx"));
    doc.setProperty("name", "some");
    tx.execute("insert into SomeTx set name='aa' ").close();
    var res = tx.execute("update SomeTx set name='bb' where name=\"some\"");
    assertEquals((Long) 1L, res.next().getProperty("count"));
    res.close();
    tx.execute("delete from SomeTx where name='aa'").close();
    tx.commit();

    assertEquals(2, calls.beforeCreate);
    assertEquals(2, calls.afterCreate);

    assertEquals(1, calls.beforeUpdate);
    assertEquals(1, calls.afterUpdate);

    assertEquals(1, calls.beforeDelete);
    assertEquals(1, calls.afterDelete);
    session.close();
    youTrackDB.close();
    this.session.activateOnCurrentThread();
  }

  @Test
  @Ignore
  public void testCalledInTxServer() {
    session.begin();
    var calls = CountCallHookServer.instance;
    var doc = ((EntityImpl) session.newEntity("SomeTx"));
    doc.setProperty("name", "some");
    session.execute("insert into SomeTx set name='aa' ").close();
    var res = session.execute("update SomeTx set name='bb' where name=\"some\"");
    assertEquals((Long) 1L, res.next().getProperty("count"));
    res.close();
    session.execute("delete from SomeTx where name='aa'").close();
    session.commit();
    assertEquals(2, calls.beforeCreate);
    assertEquals(2, calls.afterCreate);

    assertEquals(1, calls.beforeUpdate);
    assertEquals(1, calls.afterUpdate);

    assertEquals(1, calls.beforeDelete);
    assertEquals(1, calls.afterDelete);
  }

  public static class CountCallHookServer extends CountCallHook {

    public CountCallHookServer(DatabaseSession database) {
      super(database);
      instance = this;
    }

    public static CountCallHookServer instance;
  }

  public static class CountCallHook extends EntityHookAbstract {

    public int beforeUpdate = 0;
    public int afterUpdate = 0;

    public int beforeCreate = 0;
    public int afterCreate = 0;

    public int beforeDelete = 0;
    public int afterDelete = 0;

    public CountCallHook(DatabaseSession database) {
      super();
    }

    @Override
    public void onBeforeEntityCreate(Entity entity) {
      beforeCreate++;
    }

    @Override
    public void onAfterEntityCreate(Entity entity) {
      afterCreate++;
    }

    @Override
    public void onBeforeEntityUpdate(Entity entity) {
      beforeUpdate++;
    }

    @Override
    public void onAfterEntityUpdate(Entity entity) {
      afterUpdate++;
    }

    @Override
    public void onBeforeEntityDelete(Entity entity) {
      beforeDelete++;
    }

    @Override
    public void onAfterEntityDelete(Entity entity) {
      afterDelete++;
    }
  }
}
