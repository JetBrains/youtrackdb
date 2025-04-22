package com.jetbrains.youtrack.db.internal.server.network;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.client.remote.RemotePushHandler;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemotePushThread;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushResponse;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 *
 */
public class PushMessageUnitTest {

  public class MockPushResponse implements BinaryPushResponse {

    @Override
    public void write(ChannelDataOutput network) throws IOException {
    }

    @Override
    public void read(ChannelDataInput channel) throws IOException {
      responseRead.countDown();
    }
  }

  public class MockPushRequest implements BinaryPushRequest<BinaryPushResponse> {

    @Override
    public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel)
        throws IOException {
      requestWritten.countDown();
    }

    @Override
    public byte getPushCommand() {
      return 100;
    }

    @Override
    public void read(DatabaseSessionRemote session, ChannelDataInput network) throws IOException {
    }

    @Override
    public BinaryPushResponse execute(RemotePushHandler remote) {
      executed.countDown();
      return new MockPushResponse();
    }

    @Override
    public BinaryPushResponse createResponse() {
      return new MockPushResponse();
    }
  }

  public class MockPushRequestNoResponse implements BinaryPushRequest<BinaryPushResponse> {

    @Override
    public void write(DatabaseSessionEmbedded session, ChannelDataOutput channel)
        throws IOException {
      requestWritten.countDown();
    }

    @Override
    public byte getPushCommand() {
      return 101;
    }

    @Override
    public void read(DatabaseSessionRemote session, ChannelDataInput network) throws IOException {
    }

    @Override
    public BinaryPushResponse execute(RemotePushHandler remote) {
      executed.countDown();
      return null;
    }

    @Override
    public BinaryPushResponse createResponse() {
      return null;
    }
  }

  private CountDownLatch requestWritten;
  private CountDownLatch responseRead;
  private CountDownLatch executed;
  private MockPipeChannel channelBinaryServer;
  private MockPipeChannel channelBinaryClient;
  @Mock
  private YouTrackDBServer server;

  @Mock
  private RemotePushHandler remote;

  @Before
  public void before() throws IOException {
    MockitoAnnotations.initMocks(this);
    var inputClient = new PipedInputStream();
    var outputServer = new PipedOutputStream(inputClient);
    var inputServer = new PipedInputStream();
    var outputClient = new PipedOutputStream(inputServer);
    this.channelBinaryClient = new MockPipeChannel(inputClient, outputClient);
    this.channelBinaryServer = new MockPipeChannel(inputServer, outputServer);
    Mockito.when(server.getContextConfiguration()).thenReturn(new ContextConfiguration());
    Mockito.when(remote.getNetwork(Mockito.anyString())).thenReturn(channelBinaryClient);
    Mockito.when(remote.createPush((byte) 100)).thenReturn(new MockPushRequest());
    Mockito.when(remote.createPush((byte) 101)).thenReturn(new MockPushRequestNoResponse());
    requestWritten = new CountDownLatch(1);
    responseRead = new CountDownLatch(1);
    executed = new CountDownLatch(1);
  }

  @Test
  public void testPushMessage() throws IOException, InterruptedException {
    var binary = new NetworkProtocolBinary(server);
    binary.initVariables(server, channelBinaryServer);
    new Thread(
        () -> {
          try {
            binary.push(null, new MockPushRequest());
          } catch (IOException e) {
            e.printStackTrace();
          }
        })
        .start();
    binary.start();
    assertTrue(requestWritten.await(10, TimeUnit.SECONDS));
    var pushThread = new StorageRemotePushThread(remote, "none", 10, 1000);
    pushThread.start();

    assertTrue(executed.await(10, TimeUnit.SECONDS));
    assertTrue(responseRead.await(10, TimeUnit.SECONDS));
    Mockito.verify(remote).createPush((byte) 100);
    pushThread.shutdown();
    binary.shutdown();
  }

  @Test
  public void testPushMessageNoResponse() throws IOException, InterruptedException {
    var binary = new NetworkProtocolBinary(server);
    binary.initVariables(server, channelBinaryServer);
    var thread =
        new Thread(
            () -> {
              try {
                assertNull(binary.push(null, new MockPushRequestNoResponse()));
              } catch (IOException e) {
                e.printStackTrace();
              }
            });
    thread.start();
    binary.start();
    assertTrue(requestWritten.await(10, TimeUnit.SECONDS));
    var pushThread = new StorageRemotePushThread(remote, "none", 10, 1000);
    pushThread.start();

    assertTrue(executed.await(10, TimeUnit.SECONDS));
    Mockito.verify(remote).createPush((byte) 101);
    thread.join(1000);
    pushThread.shutdown();
    pushThread.join(1000);
    binary.shutdown();
  }
}
