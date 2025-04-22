/*
 *
 *
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
package com.jetbrains.youtrack.db.internal.client.binary;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteNodeSession;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.client.remote.message.Error37Response;
import com.jetbrains.youtrack.db.internal.common.concur.lock.LockException;
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.SocketFactory;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.NetworkProtocolException;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ResponseProcessingException;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.SocketChannelBinary;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketChannelBinaryAsynchClient extends SocketChannelBinary {

  private static final Logger logger = LoggerFactory.getLogger(
      SocketChannelBinaryAsynchClient.class);

  private int socketTimeout; // IN MS
  protected final short srvProtocolVersion;
  private final String serverURL;
  private byte currentStatus;
  private int currentSessionId;
  private volatile long lastUse;
  private volatile boolean inUse;

  public SocketChannelBinaryAsynchClient(
      final String remoteHost,
      final int remotePort,
      final ContextConfiguration iConfig,
      final int iProtocolVersion)
      throws IOException {
    super(SocketFactory.instance(iConfig).createSocket(), iConfig);
    try {

      serverURL = remoteHost + ":" + remotePort;
      socketTimeout = iConfig.getValueAsInteger(GlobalConfiguration.NETWORK_SOCKET_TIMEOUT);

      try {
        socket.connect(new InetSocketAddress(remoteHost, remotePort), socketTimeout);
        setReadResponseTimeout();
        connected();
      } catch (java.net.SocketTimeoutException e) {
        throw new IOException("Cannot connect to host " + remoteHost + ":" + remotePort, e);
      }
      try {
        if (socketBufferSize > 0) {
          inStream = new BufferedInputStream(socket.getInputStream(), socketBufferSize);
          outStream = new BufferedOutputStream(socket.getOutputStream(), socketBufferSize);
        } else {
          inStream = new BufferedInputStream(socket.getInputStream());
          outStream = new BufferedOutputStream(socket.getOutputStream());
        }

        in = new DataInputStream(inStream);
        out = new DataOutputStream(outStream);

        srvProtocolVersion = readShort();

        writeByte(ChannelBinaryProtocol.REQUEST_HANDSHAKE);
        writeShort((short) iProtocolVersion);
        writeString("Java Client");
        writeString(YouTrackDBConstants.getVersion());
        writeByte(ChannelBinaryProtocol.ENCODING_DEFAULT);
        writeByte(ChannelBinaryProtocol.ERROR_MESSAGE_JAVA);
        flush();
      } catch (IOException e) {
        throw new NetworkProtocolException(
            "Cannot read protocol version from remote server "
                + socket.getRemoteSocketAddress()
                + ": "
                + e);
      }

      if (srvProtocolVersion != iProtocolVersion) {
        LogManager.instance()
            .warn(
                this,
                "The Client driver version is different than Server version: client="
                    + iProtocolVersion
                    + ", server="
                    + srvProtocolVersion
                    + ". You could not use the full features of the newer version. Assure to have"
                    + " the same versions on both");
      }

    } catch (RuntimeException e) {
      if (socket.isConnected()) {
        socket.close();
      }
      throw e;
    }
  }

  @SuppressWarnings("unchecked")
  private static RuntimeException createException(
      final String iClassName, final String iMessage, final Exception iPrevious) {
    RuntimeException rootException = null;
    Constructor<?> c = null;
    try {
      final var excClass = (Class<RuntimeException>) Class.forName(iClassName);
      if (iPrevious != null) {
        try {
          c = excClass.getConstructor(String.class, Throwable.class);
        } catch (NoSuchMethodException e) {
          c = excClass.getConstructor(String.class, Exception.class);
        }
      }

      if (c == null) {
        c = excClass.getConstructor(String.class);
      }

    } catch (Exception e) {
      // UNABLE TO REPRODUCE THE SAME SERVER-SIDE EXCEPTION: THROW AN SYSTEM EXCEPTION
      rootException = BaseException.wrapException(new SystemException(iMessage), iPrevious,
          (String) null);
    }

    if (c != null) {
      try {
        final Exception cause;
        if (c.getParameterTypes().length > 1) {
          cause = (Exception) c.newInstance(iMessage, iPrevious);
        } else {
          cause = (Exception) c.newInstance(iMessage);
        }

        rootException =
            BaseException.wrapException(new SystemException("Data processing exception"), cause,
                (String) null);
      } catch (InstantiationException | IllegalAccessException |
               InvocationTargetException ignored) {
      }
    }

    return rootException;
  }

  public byte[] beginResponse(DatabaseSessionRemote dbSession, final int iRequesterId,
      final boolean token) throws IOException {
    return beginResponse(dbSession, iRequesterId, timeout, token);
  }

  @Nullable
  public byte[] beginResponse(DatabaseSessionRemote dbSession, final int iRequesterId,
      final long iTimeout, final boolean token)
      throws IOException {
    try {
      // WAIT FOR THE RESPONSE
      if (iTimeout <= 0) {
        acquireReadLock();
      }

      if (!isConnected()) {
        releaseReadLock();
        throw new IOException("Channel is closed");
      }

      try {
        setWaitResponseTimeout();
        currentStatus = readByte();
        currentSessionId = readInt();

        if (debug) {
          LogManager.instance()
              .debug(
                  this,
                  "%s - Read response: %d-%d",
                  logger, socket.getLocalAddress(),
                  (int) currentStatus,
                  currentSessionId);
        }

      } finally {
        setReadResponseTimeout();
      }

      assert (currentSessionId == iRequesterId);

      if (debug) {
        LogManager.instance()
            .debug(this, "%s - Session %d handle response", logger, socket.getLocalAddress(),
                iRequesterId);
      }
      byte[] tokenBytes;
      if (token) {
        tokenBytes = this.readBytes();
      } else {
        tokenBytes = null;
      }

      //current message
      readByte();
      handleStatus(dbSession, currentStatus, currentSessionId);
      return tokenBytes;
    } catch (LockException e) {
      Thread.currentThread().interrupt();
      // NEVER HAPPENS?
      LogManager.instance().error(this, "Unexpected error on reading response from channel", e);
    }
    return null;
  }

  public void endResponse() throws IOException {
    // WAKE UP ALL THE WAITING THREADS
    try {
      releaseReadLock();
    } catch (IllegalMonitorStateException e) {
      // IGNORE IT
      LogManager.instance()
          .debug(this, "Error on unlocking network channel after reading response", logger);
    }
  }

  public void endRequest() throws IOException {
    flush();
    releaseWriteLock();
  }

  @Override
  public void close() {
    try {
      super.close();
    } catch (Exception e) {
      // IGNORE IT
    }
  }

  @Override
  public void clearInput() throws IOException {
    acquireReadLock();
    try {
      super.clearInput();
    } finally {
      releaseReadLock();
    }
  }

  /**
   * Tells if the channel is connected.
   *
   * @return true if it's connected, otherwise false.
   */
  public boolean isConnected() {
    final var s = socket;
    return s != null
        && !s.isClosed()
        && s.isConnected()
        && !s.isInputShutdown()
        && !s.isOutputShutdown();
  }

  /**
   * Gets the major supported protocol version
   */
  public short getSrvProtocolVersion() {
    return srvProtocolVersion;
  }

  public String getServerURL() {
    return serverURL;
  }

  public boolean tryLock() {
    return getLockWrite().tryAcquireLock();
  }

  public void unlock() {
    getLockWrite().unlock();
  }

  public interface ExceptionHandler {

    void onException(Throwable ex);
  }

  public void handleStatus(
      DatabaseSessionRemote dbSession, final byte iResult, final int iClientTxId,
      ExceptionHandler exceptionHandler)
      throws IOException {
    if (iResult != ChannelBinaryProtocol.RESPONSE_STATUS_OK
        && iResult != ChannelBinaryProtocol.PUSH_DATA) {
      if (iResult == ChannelBinaryProtocol.RESPONSE_STATUS_ERROR) {
        var response = new Error37Response();
        response.read(dbSession, this, null);
        var serializedException = response.getVerbose();
        Exception previous = null;
        if (serializedException != null && serializedException.length > 0) {
          var deserializeException = deserializeException(serializedException);
          exceptionHandler.onException(deserializeException);
        }

        for (var entry : response.getMessages().entrySet()) {
          previous = createException(entry.getKey(), entry.getValue(), previous);
        }

        if (previous != null) {
          exceptionHandler.onException(new RuntimeException(previous));
        } else {
          exceptionHandler.onException(new NetworkProtocolException("Network response error"));
        }

      } else {
        // PROTOCOL ERROR
        // close();
        exceptionHandler.onException(
            new NetworkProtocolException("Error on reading response from the server"));
      }
    }

  }

  public void handleStatus(DatabaseSessionRemote dbSession, final byte iResult,
      final int iClientTxId)
      throws IOException {
    handleStatus(dbSession, iResult, iClientTxId, this::handleException);
  }

  private void setReadResponseTimeout() throws SocketException {
    final var s = socket;
    if (s != null && s.isConnected() && !s.isClosed()) {
      s.setSoTimeout(socketTimeout);
    }
  }

  private Throwable deserializeException(final byte[] serializedException) throws IOException {
    final var inputStream = new ByteArrayInputStream(serializedException);
    final var objectInputStream = new ObjectInputStream(inputStream);

    Object throwable = null;
    try {
      throwable = objectInputStream.readObject();
    } catch (ClassNotFoundException e) {
      LogManager.instance().error(this, "Error during exception deserialization", e);
      throw new IOException("Error during exception deserialization: " + e, e);
    }

    objectInputStream.close();
    return (Throwable) throwable;
  }

  public void handleException(Throwable throwable) {
    if (throwable instanceof BaseException) {
      try {
        final var cls = (Class<? extends BaseException>) throwable.getClass();
        final Constructor<? extends BaseException> constructor;
        constructor = cls.getConstructor(cls);
        final var proxyInstance = constructor.newInstance(throwable);
        proxyInstance.addSuppressed(throwable);
        throw proxyInstance;

      } catch (NoSuchMethodException
               | InvocationTargetException
               | InstantiationException
               | IllegalAccessException e) {
        LogManager.instance().error(this, "Error during exception deserialization", e);
      }
    }

    if (throwable instanceof RuntimeException) {
      throw (RuntimeException) throwable;
    }
    if (throwable instanceof Throwable) {
      throw new ResponseProcessingException("Exception during response processing", throwable);
    } else {
      // WRAP IT
      var exceptionType = throwable != null ? throwable.getClass().getName() : "null";
      LogManager.instance()
          .error(
              this,
              "Error during exception serialization, serialized exception is not Throwable,"
                  + " exception type is "
                  + exceptionType,
              null);
    }
  }

  public void beginRequest(final byte iCommand, final BinaryProtocolSession session)
      throws IOException {
    final var nodeSession = session.getServerSession(serverURL);
    beginRequest(iCommand, nodeSession);
  }

  public void beginRequest(byte iCommand, StorageRemoteNodeSession nodeSession)
      throws IOException {
    if (nodeSession == null) {
      throw new YTIOException("Invalid session for URL '" + serverURL + "'");
    }

    writeByte(iCommand);
    writeInt(nodeSession.getSessionId());
    writeBytes(nodeSession.getToken());
  }

  public int getSocketTimeout() {
    return socketTimeout;
  }

  public void setSocketTimeout(int socketTimeout) {
    this.socketTimeout = socketTimeout;
  }

  private void markLastUse() {
    lastUse = System.currentTimeMillis();
  }

  public long getLastUse() {
    return lastUse;
  }

  public void markReturned() {
    markLastUse();
    inUse = false;
  }

  public void markInUse() {
    markLastUse();
    inUse = false;
  }

  public boolean isInUse() {
    return inUse;
  }
}
