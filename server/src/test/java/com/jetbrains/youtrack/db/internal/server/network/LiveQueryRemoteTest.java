package com.jetbrains.youtrack.db.internal.server.network;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LiveQueryRemoteTest {

  private YouTrackDBServer server;
  private YouTrackDB youTrackDB;
  private DatabaseSessionInternal db;

  @Before
  public void before() throws Exception {
    GlobalConfiguration.SERVER_BACKWARD_COMPATIBILITY.setValue(false);
    server = new YouTrackDBServer(false);
    server.startup(
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "com/jetbrains/youtrack/db/internal/server/network/youtrackdb-server-config.xml"));
    server.activate();
    youTrackDB = new YouTrackDBImpl("remote:localhost:", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        LiveQueryRemoteTest.class.getSimpleName());
    db = (DatabaseSessionInternal) youTrackDB.open(LiveQueryRemoteTest.class.getSimpleName(),
        "admin", "admin");
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(server.getDatabaseDirectory()));
    YouTrackDBEnginesManager.instance().startup();
  }

  static class MyLiveQueryListener implements LiveQueryResultListener {

    public CountDownLatch latch;
    public CountDownLatch ended = new CountDownLatch(1);

    public MyLiveQueryListener(CountDownLatch latch) {
      this.latch = latch;
    }

    public List<Result> ops = new ArrayList<Result>();

    @Override
    public void onCreate(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
      ops.add(data);
      latch.countDown();
    }

    @Override
    public void onUpdate(@Nonnull DatabaseSessionInternal session, @Nonnull Result before,
        @Nonnull Result after) {
      ops.add(after);
      latch.countDown();
    }

    @Override
    public void onDelete(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
      ops.add(data);
      latch.countDown();
    }

    @Override
    public void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception) {
    }

    @Override
    public void onEnd(@Nonnull DatabaseSession session) {
      ended.countDown();
    }
  }

  @Test
  public void testRidSelect() throws InterruptedException {
    var listener = new MyLiveQueryListener(new CountDownLatch(1));
    db.begin();
    var item = db.newVertex();
    db.commit();

    db.live("LIVE SELECT FROM " + item.getIdentity(), listener);
    db.begin();
    item = db.load(item.getIdentity());
    item.setProperty("x", "z");
    db.commit();

    Assert.assertTrue(listener.latch.await(10, TimeUnit.SECONDS));
  }

  @Test
  public void testLiveInsert() throws InterruptedException {

    db.getMetadata().getSchema().createClass("test");
    db.getMetadata().getSchema().createClass("test2");

    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var monitor = db.live("select from test", listener);
    Assert.assertNotNull(monitor);

    db.begin();
    db.command("insert into test set name = 'foo', surname = 'bar'").close();
    db.command("insert into test set name = 'foo', surname = 'baz'").close();
    db.command("insert into test2 set name = 'foo'").close();
    db.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    monitor.unSubscribe();
    Assert.assertTrue(listener.ended.await(1, TimeUnit.MINUTES));

    db.begin();
    db.command("insert into test set name = 'foo', surname = 'bax'");
    db.command("insert into test2 set name = 'foo'");
    db.command("insert into test set name = 'foo', surname = 'baz'");
    db.commit();

    Assert.assertEquals(2, listener.ops.size());
    for (var doc : listener.ops) {
      Assert.assertEquals("test", doc.getProperty("@class"));
      Assert.assertEquals("foo", doc.getProperty("name"));
      RID rid = doc.getProperty("@rid");
      Assert.assertTrue(rid.isPersistent());
    }
  }

  @Test
  @Ignore
  public void testRestrictedLiveInsert() throws ExecutionException, InterruptedException {
    Schema schema = db.getMetadata().getSchema();
    var oRestricted = schema.getClass("ORestricted");
    schema.createClass("test", oRestricted);

    var liveMatch = 1;
    var query = db.query("select from OUSer where name = 'reader'");

    final Identifiable reader = query.next().getIdentity();
    final var current = db.getCurrentUser().getIdentity();

    var executorService = Executors.newSingleThreadExecutor();

    final var latch = new CountDownLatch(1);
    final var dataArrived = new CountDownLatch(1);
    var future =
        executorService.submit(
            new Callable<Integer>() {
              @Override
              public Integer call() throws Exception {
                var db =
                    youTrackDB.open(LiveQueryRemoteTest.class.getSimpleName(), "reader", "reader");

                final var integer = new AtomicInteger(0);
                db.live(
                    "live select from test",
                    new LiveQueryResultListener() {

                      @Override
                      public void onCreate(@Nonnull DatabaseSessionInternal session,
                          @Nonnull Result data) {
                        integer.incrementAndGet();
                        dataArrived.countDown();
                      }

                      @Override
                      public void onUpdate(
                          @Nonnull DatabaseSessionInternal session, @Nonnull Result before,
                          @Nonnull Result after) {
                        integer.incrementAndGet();
                        dataArrived.countDown();
                      }

                      @Override
                      public void onDelete(@Nonnull DatabaseSessionInternal session,
                          @Nonnull Result data) {
                        integer.incrementAndGet();
                        dataArrived.countDown();
                      }

                      @Override
                      public void onError(@Nonnull DatabaseSession session,
                          @Nonnull BaseException exception) {
                      }

                      @Override
                      public void onEnd(@Nonnull DatabaseSession session) {
                      }
                    });

                latch.countDown();
                Assert.assertTrue(dataArrived.await(2, TimeUnit.MINUTES));
                return integer.get();
              }
            });

    latch.await();

    query.close();
    db.command("insert into test set name = 'foo', surname = 'bar'");

    db.command(
        "insert into test set name = 'foo', surname = 'bar', _allow=?",
        new ArrayList<Identifiable>() {
          {
            add(current);
            add(reader);
          }
        });

    var integer = future.get();
    Assert.assertEquals(liveMatch, integer.intValue());
  }

  @Test
  public void testBatchWithTx() throws InterruptedException {

    db.getMetadata().getSchema().createClass("test");
    db.getMetadata().getSchema().createClass("test2");

    var txSize = 100;

    var listener = new MyLiveQueryListener(new CountDownLatch(txSize));

    var monitor = db.live("select from test", listener);
    Assert.assertNotNull(monitor);

    db.begin();
    for (var i = 0; i < txSize; i++) {
      var elem = db.newEntity("test");
      elem.setProperty("name", "foo");
      elem.setProperty("surname", "bar" + i);
    }
    db.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    Assert.assertEquals(txSize, listener.ops.size());
    for (var doc : listener.ops) {
      Assert.assertEquals("test", doc.getProperty("@class"));
      Assert.assertEquals("foo", doc.getProperty("name"));
      RID rid = doc.getProperty("@rid");
      Assert.assertTrue(rid.isPersistent());
    }
  }
}
