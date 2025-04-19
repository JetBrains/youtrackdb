package com.jetbrains.youtrack.db.internal.server.network;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrack.db.api.remote.query.RemoteLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

public class LiveQueryRemoteTest {

  private YouTrackDBServer server;
  private RemoteYouTrackDB youTrackDB;
  private RemoteDatabaseSession session;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "com/jetbrains/youtrack/db/internal/server/network/youtrackdb-server-config.xml"));
    server.activate();
    youTrackDB = YourTracks.remote("remote:localhost:", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        LiveQueryRemoteTest.class.getSimpleName());
    session = youTrackDB.open(LiveQueryRemoteTest.class.getSimpleName(),
        "admin", "admin");
  }

  @After
  public void after() {
    session.close();
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(server.getDatabaseDirectory()));
    YouTrackDBEnginesManager.instance().startup();
  }

  static class MyLiveQueryListener implements RemoteLiveQueryResultListener {

    public CountDownLatch latch;
    public CountDownLatch ended = new CountDownLatch(1);

    public MyLiveQueryListener(CountDownLatch latch) {
      this.latch = latch;
    }

    public List<BasicResult> ops = new ArrayList<BasicResult>();

    @Override
    public void onCreate(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult data) {
      ops.add(data);
      latch.countDown();
    }

    @Override
    public void onUpdate(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult before,
        @Nonnull RemoteResult after) {
      ops.add(after);
      latch.countDown();
    }

    @Override
    public void onDelete(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult data) {
      ops.add(data);
      latch.countDown();
    }

    @Override
    public void onError(@Nonnull RemoteDatabaseSession session, @Nonnull BaseException exception) {
    }

    @Override
    public void onEnd(@Nonnull RemoteDatabaseSession session) {
      ended.countDown();
    }
  }

  @Test
  public void testRidSelect() throws InterruptedException {
    var listener = new MyLiveQueryListener(new CountDownLatch(1));
    session.begin();
    var item = session.newVertex();
    session.commit();

    youTrackDB.live(session.getDatabaseName(), session.getCurrentUserName(), "admin",
        "LIVE SELECT FROM " + item.getIdentity(), listener);
    session.begin();
    item = session.load(item.getIdentity());
    item.setProperty("x", "z");
    session.commit();

    Assert.assertTrue(listener.latch.await(10, TimeUnit.SECONDS));
  }

  @Test
  public void testLiveInsert() throws InterruptedException {

    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");

    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var monitor = youTrackDB.live(session.getDatabaseName(), session.getCurrentUserName(), "admin",
        "select from test", listener);
    Assert.assertNotNull(monitor);

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bar'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    monitor.unSubscribe();
    Assert.assertTrue(listener.ended.await(1, TimeUnit.MINUTES));

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bax'");
    session.execute("insert into test2 set name = 'foo'");
    session.execute("insert into test set name = 'foo', surname = 'baz'");
    session.commit();

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
    Schema schema = session.getMetadata().getSchema();
    var oRestricted = schema.getClass("ORestricted");
    schema.createClass("test", oRestricted);

    var liveMatch = 1;
    var query = session.query("select from OUSer where name = 'reader'");

    final Identifiable reader = query.next().getIdentity();
    final var current = session.getCurrentUser().getIdentity();

    var executorService = Executors.newSingleThreadExecutor();

    final var latch = new CountDownLatch(1);
    final var dataArrived = new CountDownLatch(1);
    var future =
        executorService.submit(
            () -> {
              final var integer = new AtomicInteger(0);
              youTrackDB.live(LiveQueryRemoteTest.class.getSimpleName(), "reader", "reader",
                  "select from test",
                  new RemoteLiveQueryResultListener() {

                    @Override
                    public void onCreate(@Nonnull RemoteDatabaseSession session,
                        @Nonnull BasicResult data) {
                      integer.incrementAndGet();
                      dataArrived.countDown();
                    }

                    @Override
                    public void onUpdate(
                        @Nonnull DatabaseSession session, @Nonnull BasicResult before,
                        @Nonnull BasicResult after) {
                      integer.incrementAndGet();
                      dataArrived.countDown();
                    }

                    @Override
                    public void onDelete(@Nonnull DatabaseSession session,
                        @Nonnull BasicResult data) {
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
            });

    latch.await();

    query.close();
    session.execute("insert into test set name = 'foo', surname = 'bar'");

    session.execute(
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

    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");

    var txSize = 100;

    var listener = new MyLiveQueryListener(new CountDownLatch(txSize));

    var monitor = youTrackDB.live(session.getDatabaseName(), session.getCurrentUserName(), "admin",
        "select from test", listener);
    Assert.assertNotNull(monitor);

    session.begin();
    for (var i = 0; i < txSize; i++) {
      var elem = session.newEntity("test");
      elem.setProperty("name", "foo");
      elem.setProperty("surname", "bar" + i);
    }
    session.commit();

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
