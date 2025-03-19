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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.query.LiveQuery;
import com.jetbrains.youtrack.db.internal.core.sql.query.LiveResultListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * only for remote usage (it requires registered LiveQuery plugin)
 */
public class LiveQueryTest extends BaseDBTest implements CommandOutputListener {

  private final CountDownLatch latch = new CountDownLatch(2);
  private final CountDownLatch unLatch = new CountDownLatch(1);

  class MyLiveQueryListener implements LiveResultListener {

    public List<RecordOperation> ops = new ArrayList<RecordOperation>();
    public int unsubscribe;

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
      unsubscribe = iLiveToken;
      unLatch.countDown();
    }
  }

  @Parameters(value = {"remote"})
  public LiveQueryTest(boolean remote) {
    super(remote);
  }

  @Test(enabled = false)
  public void checkLiveQuery1() throws IOException, InterruptedException {
    final var className1 = "LiveQueryTest1_1";
    final var className2 = "LiveQueryTest1_2";
    session.getMetadata().getSchema().createClass(className1);
    session.getMetadata().getSchema().createClass(className2);

    var listener = new MyLiveQueryListener();

    LegacyResultSet<EntityImpl> tokens =
        session.query(new LiveQuery<EntityImpl>("live select from " + className1, listener));
    Assert.assertEquals(tokens.size(), 1);
    var tokenDoc = tokens.get(0);
    int token = tokenDoc.getProperty("token");
    Assert.assertNotNull(token);

    session.execute("insert into " + className1 + " set name = 'foo', surname = 'bar'").close();
    session.execute("insert into  " + className1 + " set name = 'foo', surname = 'baz'").close();
    /// TODO check
    session.execute("insert into " + className2 + " set name = 'foo'").close();
    latch.await(1, TimeUnit.MINUTES);

    session.execute("live unsubscribe " + token).close();
    session.execute("insert into " + className1 + " set name = 'foo', surname = 'bax'").close();
    Assert.assertEquals(listener.ops.size(), 2);
    for (var doc : listener.ops) {
      Assert.assertEquals(doc.type, RecordOperation.CREATED);
      Assert.assertEquals(((EntityImpl) doc.record).getProperty("name"), "foo");
    }
    unLatch.await(1, TimeUnit.MINUTES);
    Assert.assertEquals(listener.unsubscribe, token);
  }

  @Override
  @Test(enabled = false)
  public void onMessage(final String iText) {
    // System.out.print(iText);
    // System.out.flush();
  }
}
