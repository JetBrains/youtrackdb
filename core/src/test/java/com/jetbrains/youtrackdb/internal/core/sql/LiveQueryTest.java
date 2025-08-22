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
package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.query.LiveQueryResultListener;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.sql.query.LiveResultListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LiveQueryTest {

  private YouTrackDB odb;
  private DatabaseSessionEmbedded session;

  @Before
  public void before() {
    odb = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(LiveQueryTest.class),
        YouTrackDBConfig.defaultConfig());
    odb.execute(
        "create database LiveQueryTest memory users ( admin identified by 'admin' role admin,"
            + " reader identified by 'reader' role reader)");
    session = (DatabaseSessionEmbedded) odb.open("LiveQueryTest", "admin", "admin");
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
    public List<BasicResult> created = new ArrayList<BasicResult>();

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
    public void onCreate(@Nonnull DatabaseSession session, @Nonnull Result data) {
      created.add(data);
      latch.countDown();
    }

    @Override
    public void onUpdate(@Nonnull DatabaseSession session, @Nonnull Result before,
        @Nonnull Result after) {
    }

    @Override
    public void onDelete(@Nonnull DatabaseSession session, @Nonnull Result data) {
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
    session.execute("insert into test set name = 'foo', surname = 'bar'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    tokens.unSubscribe();

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bax'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
    session.commit();

    Assert.assertEquals(2, listener.created.size());
    for (var res : listener.created) {
      Assert.assertEquals("foo", res.getProperty("name"));
    }
  }
}
