package com.jetbrains.youtrack.db.internal.client.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 *
 */
public class RemoteLiveQueryPushTest {

  private static class MockLiveListener implements LiveQueryResultListener {

    public int countCreate = 0;
    public int countUpdate = 0;
    public int countDelete = 0;
    public boolean end;

    @Override
    public void onCreate(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
      countCreate++;
    }

    @Override
    public void onUpdate(@Nonnull DatabaseSessionInternal session, @Nonnull Result before,
        @Nonnull Result after) {
      countUpdate++;
    }

    @Override
    public void onDelete(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
      countDelete++;
    }

    @Override
    public void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception) {
    }

    @Override
    public void onEnd(@Nonnull DatabaseSession session) {
      assertFalse(end);
      end = true;
    }
  }

  private StorageRemote storage;

  @Mock
  private RemoteConnectionManager connectionManager;

  @Mock
  private DatabasePoolInternal pool;

  @Mock
  private DatabaseSessionInternal session;

  @Before
  public void before() throws IOException {
    MockitoAnnotations.initMocks(this);
    Mockito.when(pool.acquire()).thenReturn(session);
    Mockito.when(session.assertIfNotActive()).thenReturn(true);
    storage =
        new StorageRemote(
            new RemoteURLs(new String[]{}, new ContextConfiguration()),
            "none",
            null,
            "",
            connectionManager,
            null);
  }

  @Test
  public void testLiveEvents() {
    var mock = new MockLiveListener();
    storage.registerLiveListener(10, new LiveQueryClientListener(pool, mock));
    List<LiveQueryResult> events = new ArrayList<>();
    events.add(
        new LiveQueryResult(LiveQueryResult.CREATE_EVENT, new ResultInternal(session), null));
    events.add(
        new LiveQueryResult(
            LiveQueryResult.UPDATE_EVENT, new ResultInternal(session),
            new ResultInternal(session)));
    events.add(
        new LiveQueryResult(LiveQueryResult.DELETE_EVENT, new ResultInternal(session), null));

    var request =
        new LiveQueryPushRequest(10, LiveQueryPushRequest.END, events);
    request.execute(session, storage);
    assertEquals(1, mock.countCreate);
    assertEquals(1, mock.countUpdate);
    assertEquals(1, mock.countDelete);
  }
}
