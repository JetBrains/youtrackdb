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
import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;


public class LiveQueryV2Test extends DbTestBase {

  static class MyLiveQueryListener implements LiveQueryResultListener {

    public CountDownLatch latch;

    public MyLiveQueryListener(CountDownLatch latch) {
      this.latch = latch;
    }

    public List<BasicResult> ops = new ArrayList<>();

    @Override
    public void onCreate(@Nonnull DatabaseSession session, @Nonnull Result data) {
      ops.add(data);
      latch.countDown();
    }

    @Override
    public void onUpdate(@Nonnull DatabaseSession session, @Nonnull Result before,
        @Nonnull Result after) {
      ops.add(after);
      latch.countDown();
    }

    @Override
    public void onDelete(@Nonnull DatabaseSession session, @Nonnull Result data) {
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
  public void testLiveInsert() throws InterruptedException {
    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");
    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var monitor = session.live("select from test", listener);
    Assert.assertNotNull(monitor);

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bar'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(1, TimeUnit.MINUTES));

    monitor.unSubscribe();

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bax'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
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
  public void testLiveInsertOnClass() {
    session.getMetadata().getSchema().createClass("test");

    var listener =
        new MyLiveQueryListener(new CountDownLatch(1));

    session.live(" select from test", listener);

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bar'");
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
  public void testLiveWithWhereCondition() {
    session.getMetadata().getSchema().createClass("test");

    var listener =
        new MyLiveQueryListener(new CountDownLatch(1));

    session.live("select from V where id = 1", listener);

    session.begin();
    session.execute("insert into V set id = 1");
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
  public void testLiveProjections() throws InterruptedException {
    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("test2");
    var listener = new MyLiveQueryListener(new CountDownLatch(2));

    var monitor = session.live("select @class, @rid as rid, name from test", listener);
    Assert.assertNotNull(monitor);

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bar'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.commit();

    Assert.assertTrue(listener.latch.await(5, TimeUnit.SECONDS));

    monitor.unSubscribe();

    session.begin();
    session.execute("insert into test set name = 'foo', surname = 'bax'").close();
    session.execute("insert into test2 set name = 'foo'").close();
    session.execute("insert into test set name = 'foo', surname = 'baz'").close();
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
