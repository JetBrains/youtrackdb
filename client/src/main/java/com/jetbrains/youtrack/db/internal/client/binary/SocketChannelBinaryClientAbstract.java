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
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
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
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract implementation of binary channel.
 */
public abstract class SocketChannelBinaryClientAbstract extends SocketChannelBinary {

  protected final int socketTimeout; // IN MS
  protected final short srvProtocolVersion;
  protected String serverURL;
  protected byte currentStatus;
  protected int currentSessionId;

  public SocketChannelBinaryClientAbstract(
      final String remoteHost,
      final int remotePort,
      final String iDatabaseName,
      final ContextConfiguration iConfig,
      final int protocolVersion)
      throws IOException {
    super(SocketFactory.instance(iConfig).createSocket(), iConfig);
    try {

      serverURL = remoteHost + ":" + remotePort;
      if (iDatabaseName != null) {
        serverURL += "/" + iDatabaseName;
      }
      socketTimeout = iConfig.getValueAsInteger(GlobalConfiguration.NETWORK_SOCKET_TIMEOUT);

      try {
        if (remoteHost.contains(":")) {
          // IPV6
          final var addresses = Inet6Address.getAllByName(remoteHost);
          socket.connect(new InetSocketAddress(addresses[0], remotePort), socketTimeout);

        } else {
          // IPV4
          socket.connect(new InetSocketAddress(remoteHost, remotePort), socketTimeout);
        }
        setReadResponseTimeout();
        connected();
      } catch (java.net.SocketTimeoutException e) {
        throw new IOException(
            "Cannot connect to host "
                + remoteHost
                + ":"
                + remotePort
                + " (timeout="
                + socketTimeout
                + ")",
            e);
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
      } catch (IOException e) {
        throw new NetworkProtocolException(
            "Cannot read protocol version from remote server "
                + socket.getRemoteSocketAddress()
                + ": "
                + e);
      }

      if (srvProtocolVersion != protocolVersion) {
        LogManager.instance()
            .warn(
                this,
                "The Client driver version is different than Server version: client="
                    + protocolVersion
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
      // UNABLE TO REPRODUCE THE SAME SERVER-SIZE EXCEPTION: THROW AN IO EXCEPTION
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

  protected int handleStatus(final byte iResult, final int iClientTxId) throws IOException {
    if (iResult == ChannelBinaryProtocol.RESPONSE_STATUS_OK
        || iResult == ChannelBinaryProtocol.PUSH_DATA) {
      return iClientTxId;
    } else if (iResult == ChannelBinaryProtocol.RESPONSE_STATUS_ERROR) {

      final List<Pair<String, String>> exceptions = new ArrayList<Pair<String, String>>();

      // EXCEPTION
      while (readByte() == 1) {
        final var excClassName = readString();
        final var excMessage = readString();
        exceptions.add(new Pair<String, String>(excClassName, excMessage));
      }

      byte[] serializedException = null;
      if (srvProtocolVersion >= 19) {
        serializedException = readBytes();
      }

      Exception previous = null;

      if (serializedException != null && serializedException.length > 0) {
        throwSerializedException(serializedException);
      }

      for (var i = exceptions.size() - 1; i > -1; --i) {
        previous =
            createException(exceptions.get(i).getKey(), exceptions.get(i).getValue(), previous);
      }

      if (previous != null) {
        throw new RuntimeException(previous);
      } else {
        throw new NetworkProtocolException("Network response error");
      }

    } else {
      // PROTOCOL ERROR
      // close();
      throw new NetworkProtocolException("Error on reading response from the server");
    }
  }

  protected void setReadResponseTimeout() throws SocketException {
    final var s = socket;
    if (s != null && s.isConnected() && !s.isClosed()) {
      s.setSoTimeout(socketTimeout);
    }
  }

  protected void throwSerializedException(final byte[] serializedException) throws IOException {
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

    if (throwable instanceof BaseException) {
      try {
        final var cls = (Class<? extends BaseException>) throwable.getClass();
        final Constructor<? extends BaseException> constructor;
        constructor = cls.getConstructor(cls);
        final var proxyInstance = constructor.newInstance(throwable);

        throw proxyInstance;

      } catch (NoSuchMethodException e) {
        LogManager.instance().error(this, "Error during exception deserialization", e);
      } catch (InvocationTargetException e) {
        LogManager.instance().error(this, "Error during exception deserialization", e);
      } catch (InstantiationException e) {
        LogManager.instance().error(this, "Error during exception deserialization", e);
      } catch (IllegalAccessException e) {
        LogManager.instance().error(this, "Error during exception deserialization", e);
      }
    }

    if (throwable instanceof Throwable) {
      throw new ResponseProcessingException(
          "Exception during response processing", (Throwable) throwable);
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
}
