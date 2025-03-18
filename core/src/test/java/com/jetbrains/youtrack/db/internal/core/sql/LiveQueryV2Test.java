/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LiveQueryV2Test extends DbTestBase {

  static class MyLiveQueryListener implements LiveQueryResultListener {

    public CountDownLatch latch;

    public MyLiveQueryListener(CountDownLatch latch) {
      this.latch = latch;
    }

    public List<Result> ops = new ArrayList<>();

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
    }
  }

  @Test
  @Ignore
  public void testLiveInsert() throws InterruptedException {
    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");
    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var monitor = session.live("select from test", listener);
    Assert.assertNotNull(monitor);

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bar'").close();
    session.command("insert into test set name = 'foo', surname = 'baz'").close();
    session.command("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    monitor.unSubscribe();

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bax'").close();
    session.command("insert into test2 set name = 'foo'").close();
    session.command("insert into test set name = 'foo', surname = 'baz'").close();
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
  public void testLiveInsertOnCluster() {
    var clazz = session.getMetadata().getSchema().createClass("test");

    var defaultCluster = clazz.getClusterIds()[0];
    var clusterName = session.getStorage().getClusterNameById(defaultCluster);

    var listener =
        new MyLiveQueryListener(new CountDownLatch(1));

    session.live(" select from cluster:" + clusterName, listener);

    session.begin();
    session.command("insert into cluster:" + clusterName + " set name = 'foo', surname = 'bar'");
    session.commit();

    try {
      Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    Assert.assertEquals(1, listener.ops.size());
    for (var doc : listener.ops) {
      Assert.assertEquals("foo", doc.getProperty("name"));
      RID rid = doc.getProperty("@rid");
      Assert.assertTrue(rid.isPersistent());
      Assert.assertNotNull(rid);
    }
  }

  @Test
  @Ignore
  public void testLiveWithWhereCondition() {
    session.getMetadata().getSchema().createClass("test");

    var listener =
        new MyLiveQueryListener(new CountDownLatch(1));

    session.live("select from V where id = 1", listener);

    session.begin();
    session.command("insert into V set id = 1");
    session.commit();

    try {
      Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    Assert.assertEquals(1, listener.ops.size());
    for (var doc : listener.ops) {
      Assert.assertEquals(doc.getProperty("id"), Integer.valueOf(1));
      RID rid = doc.getProperty("@rid");
      Assert.assertTrue(rid.isPersistent());
      Assert.assertNotNull(rid);
    }
  }

  @Test
  @Ignore
  public void testRestrictedLiveInsert() throws ExecutionException, InterruptedException {
    Schema schema = session.getMetadata().getSchema();
    var oRestricted = schema.getClass("ORestricted");
    schema.createClass("test", oRestricted);

    var liveMatch = 2;
    var query =
        session.query("select from OUSer where name = 'reader'").entityStream().toList();

    final Identifiable reader = query.getFirst().getIdentity();
    final var current = session.getCurrentUser().getIdentity();

    var executorService = Executors.newSingleThreadExecutor();

    final var latch = new CountDownLatch(1);
    final var dataArrived = new CountDownLatch(liveMatch);
    var future =
        executorService.submit(
            () -> {
              try (var db = openDatabase(readerUser, readerPassword)) {
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
                Assert.assertTrue(dataArrived.await(1, TimeUnit.MINUTES));
                return integer.get();
              }
            });

    latch.await();

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bar'").close();
    session.command(
            "insert into test set name = 'foo', surname = 'bar', _allow=?",
            new ArrayList<Identifiable>() {
              {
                add(current);
                add(reader);
              }
            })
        .close();
    session.commit();

    var integer = future.get();
    Assert.assertEquals(liveMatch, integer.intValue());
  }

  @Test
  @Ignore
  public void testLiveProjections() throws InterruptedException {
    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");
    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var monitor = session.live("select @class, @rid as rid, name from test", listener);
    Assert.assertNotNull(monitor);

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bar'").close();
    session.command("insert into test set name = 'foo', surname = 'baz'").close();
    session.command("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(5, TimeUnit.SECONDS));

    monitor.unSubscribe();

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bax'").close();
    session.command("insert into test2 set name = 'foo'").close();
    session.command("insert into test set name = 'foo', surname = 'baz'").close();
    session.commit();

    Assert.assertEquals(2, listener.ops.size());
    for (var doc : listener.ops) {
      Assert.assertEquals("test", doc.getProperty("@class"));
      Assert.assertEquals("foo", doc.getProperty("name"));
      Assert.assertNull(doc.getProperty("surname"));
      RID rid = doc.getProperty("rid");
      Assert.assertTrue(rid.isPersistent());
    }
  }
}
