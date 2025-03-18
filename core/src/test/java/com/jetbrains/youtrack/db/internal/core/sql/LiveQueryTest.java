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
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.sql.query.LiveResultListener;
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
import org.junit.Test;

/**
 *
 */
public class LiveQueryTest {

  private YouTrackDBImpl odb;
  private DatabaseSessionInternal session;

  @Before
  public void before() {
    odb = new YouTrackDBImpl("memory:", YouTrackDBConfig.defaultConfig());
    odb.execute(
        "create database LiveQueryTest memory users ( admin identified by 'admin' role admin,"
            + " reader identified by 'reader' role reader)");
    session = (DatabaseSessionInternal) odb.open("LiveQueryTest", "admin", "admin");
  }

  @After
  public void after() {
    session.close();
    odb.drop("LiveQueryTest");
    odb.close();
  }

  class MyLiveQueryListener implements LiveResultListener, LiveQueryResultListener {

    public CountDownLatch latch;

    public MyLiveQueryListener(CountDownLatch latch) {
      this.latch = latch;
    }

    public List<RecordOperation> ops = new ArrayList<RecordOperation>();
    public List<Result> created = new ArrayList<Result>();

    @Override
    public void onLiveResult(DatabaseSessionInternal db, int iLiveToken, RecordOperation iOp)
        throws BaseException {
      ops.add(iOp);
      latch.countDown();
    }

    @Override
    public void onError(int iLiveToken) {
    }

    @Override
    public void onUnsubscribe(int iLiveToken) {
    }

    @Override
    public void onCreate(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
      created.add(data);
      latch.countDown();
    }

    @Override
    public void onUpdate(@Nonnull DatabaseSessionInternal session, @Nonnull Result before,
        @Nonnull Result after) {
    }

    @Override
    public void onDelete(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
    }

    @Override
    public void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception) {
    }

    @Override
    public void onEnd(@Nonnull DatabaseSession session) {
    }
  }

  @Test
  public void testLiveInsert() throws InterruptedException {

    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");
    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var tokens = session.live("live select from test", listener);
    Integer token = tokens.getMonitorId();
    Assert.assertNotNull(token);

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bar'").close();
    session.command("insert into test set name = 'foo', surname = 'baz'").close();
    session.command("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    tokens.unSubscribe();

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bax'").close();
    session.command("insert into test2 set name = 'foo'").close();
    session.command("insert into test set name = 'foo', surname = 'baz'").close();
    session.commit();

    Assert.assertEquals(2, listener.created.size());
    for (var res : listener.created) {
      Assert.assertEquals("foo", res.getProperty("name"));
    }
  }

  @Test
  public void testRestrictedLiveInsert() throws ExecutionException, InterruptedException {

    Schema schema = session.getMetadata().getSchema();
    var oRestricted = schema.getClass("ORestricted");
    schema.createClass("test", oRestricted);

    var liveMatch = 2;
    var query = session.query("select from OUSer where name = 'reader'");

    final Identifiable reader = query.next().getIdentity();
    query.close();
    final var current = session.getCurrentUser().getIdentity();

    var executorService = Executors.newSingleThreadExecutor();

    final var latch = new CountDownLatch(1);
    final var dataArrived = new CountDownLatch(2);
    var future =
        executorService.submit(
            new Callable<Integer>() {
              @Override
              public Integer call() throws Exception {
                final var integer = new AtomicInteger(0);
                try {
                  odb.live("LiveQueryTest", "reader", "reader",
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
                        }

                        @Override
                        public void onDelete(@Nonnull DatabaseSessionInternal session,
                            @Nonnull Result data) {
                        }

                        @Override
                        public void onError(@Nonnull DatabaseSession session,
                            @Nonnull BaseException exception) {
                        }

                        @Override
                        public void onEnd(@Nonnull DatabaseSession session) {
                        }
                      });
                } catch (RuntimeException e) {
                  e.printStackTrace();
                }

                latch.countDown();
                Assert.assertTrue(dataArrived.await(1, TimeUnit.MINUTES));
                return integer.get();
              }
            });

    latch.await();

    session.begin();
    session.command("insert into test set name = 'foo', surname = 'bar'").close();


    final var allow = session.newLinkList();
    allow.add(current);
    allow.add(reader);
    session.command(
            "insert into test set name = 'foo', surname = 'bar', _allow=?",
            allow)
        .close();
    session.commit();

    var integer = future.get();
    Assert.assertEquals(liveMatch, integer.intValue());
  }
}
