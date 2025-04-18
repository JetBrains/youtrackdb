package com.jetbrains.youtrack.db.internal.client.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.internal.client.binary.BinaryRequestExecutor;
import com.jetbrains.youtrack.db.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.RecordCallback;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;


public class RemoteSessionManagerAsyncOperationTest {
  private RemoteCommandsOrchestratorImpl storage;

  @Mock
  private SocketChannelBinaryAsynchClient channel;

  @Mock
  private RemoteConnectionManager connectionManager;
  @Mock
  private StorageRemoteSession session;
  @Mock
  private StorageRemoteNodeSession nodeSession;

  private class CallStatus {
    public String status;
  }

  @Before
  public void before() throws IOException {
    MockitoAnnotations.initMocks(this);
    Mockito.when(session.getServerSession(Mockito.anyString())).thenReturn(nodeSession);
    storage =
        new RemoteCommandsOrchestratorImpl(
            new RemoteURLs(new String[]{}, new ContextConfiguration()),
            "mock",
            null,
            "mock",
            null,
            null) {
          @Override
          public <T> T baseNetworkOperation(
              DatabaseSessionRemote remoteSession, StorageRemoteOperation<T> operation,
              String errorMessage, int retry) {
            try {
              return operation.execute(channel, session);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };
    storage.connectionManager = connectionManager;
  }

  @Test
  @Ignore
  public void testSyncCall() {
    final var status = new CallStatus();
    storage.asyncNetworkOperationNoRetry(null,
        new BinaryAsyncRequest<BinaryResponse>() {
          @Override
          public byte getCommand() {
            return 0;
          }

          @Override
          public void write(DatabaseSessionRemote databaseSession, ChannelDataOutput network,
              StorageRemoteSession session)
              throws IOException {
            assertNull(status.status);
            status.status = "write";
          }

          @Override
          public void read(
              DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
              int protocolVersion)
              throws IOException {
          }

          @Override
          public byte getMode() {
            return 0;
          }

          @Override
          public void setMode(byte mode) {
          }

          @Override
          public String getDescription() {
            return null;
          }

          @Override
          public BinaryResponse execute(BinaryRequestExecutor executor) {
            return null;
          }

          @Override
          public BinaryResponse createResponse() {
            return new BinaryResponse() {
              @Override
              public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
                  StorageRemoteSession session)
                  throws IOException {
                assertEquals(status.status, "write");
                status.status = "read";
              }

              @Override
              public void write(
                  DatabaseSessionEmbedded session, ChannelDataOutput channel,
                  int protocolVersion)
                  throws IOException {
              }
            };
          }
        },
        0,
        new RecordId(-1, -1),
        null, "");

    assertEquals(status.status, "read");
  }

  @Test
  public void testNoReadCall() {
    final var status = new CallStatus();
    storage.asyncNetworkOperationNoRetry(null,
        new BinaryAsyncRequest<BinaryResponse>() {
          @Override
          public byte getCommand() {
            // TODO Auto-generated method stub
            return 0;
          }

          @Override
          public void write(DatabaseSessionRemote databaseSession, ChannelDataOutput network,
              StorageRemoteSession session)
              throws IOException {
            assertNull(status.status);
            status.status = "write";
          }

          @Override
          public void read(
              DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
              int protocolVersion)
              throws IOException {
          }

          @Override
          public byte getMode() {
            return 0;
          }

          @Override
          public void setMode(byte mode) {
          }

          @Override
          public String getDescription() {
            return null;
          }

          @Override
          public BinaryResponse execute(BinaryRequestExecutor executor) {
            return null;
          }

          @Override
          public BinaryResponse createResponse() {

            return new BinaryResponse() {
              @Override
              public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
                  StorageRemoteSession session)
                  throws IOException {
                fail();
              }

              @Override
              public void write(
                  DatabaseSessionEmbedded session, ChannelDataOutput channel,
                  int protocolVersion)
                  throws IOException {
              }
            };
          }
        },
        1,
        new RecordId(-1, -1),
        null, "");

    assertEquals(status.status, "write");
  }

  @Test
  @Ignore
  public void testAsyncRead() throws InterruptedException {
    final var status = new CallStatus();
    final var callBackWait = new CountDownLatch(1);
    final var readDone = new CountDownLatch(1);
    final var callBackDone = new CountDownLatch(1);
    storage.asyncNetworkOperationNoRetry(null,
        new BinaryAsyncRequest<BinaryResponse>() {
          @Override
          public byte getCommand() {
            // TODO Auto-generated method stub
            return 0;
          }

          @Override
          public void write(DatabaseSessionRemote databaseSession, ChannelDataOutput network,
              StorageRemoteSession session)
              throws IOException {
            assertNull(status.status);
            status.status = "write";
          }

          @Override
          public void read(
              DatabaseSessionEmbedded databaseSession, ChannelDataInput channel,
              int protocolVersion)
              throws IOException {
          }

          @Override
          public byte getMode() {
            return 0;
          }

          @Override
          public void setMode(byte mode) {
          }

          @Override
          public String getDescription() {
            return null;
          }

          @Override
          public BinaryResponse execute(BinaryRequestExecutor executor) {
            return null;
          }

          @Override
          public BinaryResponse createResponse() {
            return new BinaryResponse() {
              @Override
              public void read(RemoteDatabaseSessionInternal db, ChannelDataInput network,
                  StorageRemoteSession session)
                  throws IOException {
                try {
                  if (callBackWait.await(10, TimeUnit.MILLISECONDS)) {
                    readDone.countDown();
                  }
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }

              @Override
              public void write(
                  DatabaseSessionEmbedded session, ChannelDataOutput channel,
                  int protocolVersion)
                  throws IOException {
              }
            };
          }
        },
        1,
        new RecordId(-1, -1),
        new RecordCallback<BinaryResponse>() {
          @Override
          public void call(RecordId iRID, BinaryResponse iParameter) {
            callBackDone.countDown();
          }
        }, "");

    // SBLCK THE CALLBAC THAT SHOULD BE IN ANOTHER THREAD
    callBackWait.countDown();

    var called = readDone.await(200, TimeUnit.MILLISECONDS);
    if (!called) {
      fail("Read not called");
    }
    called = callBackDone.await(200, TimeUnit.MILLISECONDS);
    if (!called) {
      fail("Callback not called");
    }
  }
}
