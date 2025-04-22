package com.jetbrains.youtrack.db.internal.client.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary.RemoteResultImpl;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
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

  private static class MockLiveListener implements RemoteLiveQueryResultListener {

    public int countCreate = 0;
    public int countUpdate = 0;
    public int countDelete = 0;
    public boolean end;

    @Override
    public void onCreate(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult data) {
      countCreate++;
    }

    @Override
    public void onUpdate(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult before,
        @Nonnull RemoteResult after) {
      countUpdate++;
    }

    @Override
    public void onDelete(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult data) {
      countDelete++;
    }

    @Override
    public void onError(@Nonnull RemoteDatabaseSession session, @Nonnull BaseException exception) {
    }

    @Override
    public void onEnd(@Nonnull RemoteDatabaseSession session) {
      assertFalse(end);
      end = true;
    }
  }

  private RemoteCommandsOrchestratorImpl commandsOrchestrator;

  @Mock
  private RemoteConnectionManager connectionManager;

  @Mock
  private DatabasePoolInternal pool;

  @Mock
  private RemoteDatabaseSessionInternal remoteDatabaseSession;

  @Mock
  private DatabaseSessionInternal embeddedDatabaseSession;

  @Before
  public void before() throws IOException {
    MockitoAnnotations.initMocks(this);
    Mockito.when(pool.acquire()).thenReturn(remoteDatabaseSession);
    Mockito.when(embeddedDatabaseSession.assertIfNotActive()).thenReturn(true);
    Mockito.when(remoteDatabaseSession.assertIfNotActive()).thenReturn(true);
    commandsOrchestrator =
        new RemoteCommandsOrchestratorImpl(
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
    commandsOrchestrator.registerLiveListener(10, new LiveQueryClientListener(pool, mock));
    List<LiveQueryResult> events = new ArrayList<>();
    events.add(
        new LiveQueryResult(LiveQueryResult.CREATE_EVENT, new RemoteResultImpl(
            remoteDatabaseSession), null));
    events.add(
        new LiveQueryResult(
            LiveQueryResult.UPDATE_EVENT, new RemoteResultImpl(remoteDatabaseSession),
            new RemoteResultImpl(remoteDatabaseSession)));
    events.add(
        new LiveQueryResult(LiveQueryResult.DELETE_EVENT, new RemoteResultImpl(
            remoteDatabaseSession), null));

    var request =
        new LiveQueryPushRequest(10, LiveQueryPushRequest.END, events);
    request.execute(commandsOrchestrator);
    assertEquals(1, mock.countCreate);
    assertEquals(1, mock.countUpdate);
    assertEquals(1, mock.countDelete);
  }
}
