package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeResponse;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;
import com.jetbrains.youtrack.db.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushResponse;
import com.jetbrains.youtrack.db.internal.client.remote.message.SubscribeRequest;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 *
 */
public class StorageRemotePushThread extends Thread {

  private final RemotePushHandler pushHandler;
  private final String host;
  private final int retryDelay;
  private final long requestTimeout;
  private SocketChannelBinary network;
  private final BlockingQueue<Object> blockingQueue = new SynchronousQueue<>();
  private volatile BinaryRequest currentRequest;
  private volatile boolean shutDown;

  public StorageRemotePushThread(
      RemotePushHandler storage, String host, int retryDelay, long requestTimeout) {
    setDaemon(true);
    this.pushHandler = storage;
    this.host = host;
    network = storage.getNetwork(this.host);
    this.retryDelay = retryDelay;
    this.requestTimeout = requestTimeout;
  }

  public void handleException(Throwable throwable) {
    try {
      blockingQueue.put(throwable);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void run() {
    while (!Thread.interrupted() && !shutDown) {
      try {
        network.setWaitResponseTimeout();
        var res = network.readByte();
        if (res == ChannelBinaryProtocol.RESPONSE_STATUS_OK) {
          var currentSessionId = network.readInt();
          var token = network.readBytes();
          var messageId = network.readByte();
          var response = currentRequest.createResponse();
          response.read(null, network, null);
          blockingQueue.put(response);
        } else if (res == ChannelBinaryProtocol.RESPONSE_STATUS_ERROR) {
          var currentSessionId = network.readInt();
          var token = network.readBytes();
          var messageId = network.readByte();
          // TODO move handle status somewhere else
          ((SocketChannelBinaryAsynchClient) network)
              .handleStatus(null, res, currentSessionId, this::handleException);
        } else {
          var push = network.readByte();
          var request = pushHandler.createPush(push);
          request.read(null, network);
          try {
            var response = request.execute(null, pushHandler);
            if (response != null) {
              synchronized (this) {
                network.writeByte(ChannelBinaryProtocol.REQUEST_OK_PUSH);
                // session
                network.writeInt(-1);
                response.write(network);
              }
            }
          } catch (Exception e) {
            LogManager.instance().error(this, "Error executing push request", e);
          }
        }
      } catch (IOException | BaseException e) {
        pushHandler.onPushDisconnect(this.network, e);
        while (!currentThread().isInterrupted()) {
          try {
            Thread.sleep(retryDelay);
          } catch (InterruptedException x) {
            currentThread().interrupt();
          }
          if (!currentThread().isInterrupted()) {
            try {
              synchronized (this) {
                network = pushHandler.getNetwork(this.host);
              }
              pushHandler.onPushReconnect(this.host);
              break;
            } catch (YTIOException ex) {
              // Noting it just retry
            }
          }
        }
      } catch (InterruptedException e) {
        pushHandler.onPushDisconnect(this.network, e);
        currentThread().interrupt();
      }
    }
  }

  @Nullable
  public <T extends BinaryResponse> T subscribe(
      BinaryRequest<T> request, StorageRemoteSession session) {
    try {
      long timeout;
      synchronized (this) {
        this.currentRequest = new SubscribeRequest(request);
        ((SocketChannelBinaryAsynchClient) network)
            .beginRequest(ChannelBinaryProtocol.SUBSCRIBE_PUSH, session);
        this.currentRequest.write(null, network, null);
        network.flush();
      }
      var poll = blockingQueue.poll(requestTimeout, TimeUnit.MILLISECONDS);
      if (poll == null) {
        return null;
      }
      if (poll instanceof SubscribeResponse) {
        return (T) ((SubscribeResponse) poll).getResponse();
      } else if (poll instanceof RuntimeException) {
        throw (RuntimeException) poll;
      }
    } catch (IOException e) {
      LogManager.instance().warn(this, "Exception on subscribe", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return null;
  }

  public void shutdown() {
    shutDown = true;
    interrupt();
    pushHandler.returnSocket(this.network);
  }
}
