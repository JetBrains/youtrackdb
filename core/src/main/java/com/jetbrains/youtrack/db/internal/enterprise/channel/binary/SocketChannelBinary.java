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
package com.jetbrains.youtrack.db.internal.enterprise.channel.binary;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.exception.InvalidBinaryChunkException;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.enterprise.channel.SocketChannel;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Abstract representation of a channel.
 */
public abstract class SocketChannelBinary extends SocketChannel
    implements ChannelDataInput, ChannelDataOutput {

  private static final int MAX_LENGTH_DEBUG = 150;
  protected final boolean debug;
  private final int maxChunkSize;
  public DataInputStream in;
  public DataOutputStream out;
  private final int responseTimeout;
  private final int networkTimeout;

  public SocketChannelBinary(final Socket iSocket, final ContextConfiguration iConfig)
      throws IOException {
    super(iSocket, iConfig);
    socket.setKeepAlive(true);
    maxChunkSize =
        iConfig.getValueAsInteger(GlobalConfiguration.NETWORK_BINARY_MAX_CONTENT_LENGTH) * 1024;
    debug = iConfig.getValueAsBoolean(GlobalConfiguration.NETWORK_BINARY_DEBUG);
    responseTimeout = iConfig.getValueAsInteger(GlobalConfiguration.NETWORK_REQUEST_TIMEOUT);
    networkTimeout = iConfig.getValueAsInteger(GlobalConfiguration.NETWORK_SOCKET_TIMEOUT);

    if (debug) {
      LogManager.instance().info(this, "%s - Connected", socket.getRemoteSocketAddress());
    }
  }

  public byte readByte() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading byte (1 byte)...", socket.getRemoteSocketAddress());
      final byte value = in.readByte();
      LogManager.instance()
          .info(this, "%s - Read byte: %d", socket.getRemoteSocketAddress(), (int) value);
      return value;
    }

    return in.readByte();
  }

  public boolean readBoolean() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading boolean (1 byte)...", socket.getRemoteSocketAddress());
      final boolean value = in.readBoolean();
      LogManager.instance()
          .info(this, "%s - Read boolean: %b", socket.getRemoteSocketAddress(), value);
      return value;
    }

    return in.readBoolean();
  }

  public int readInt() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading int (4 bytes)...", socket.getRemoteSocketAddress());
      final int value = in.readInt();
      LogManager.instance()
          .info(this, "%s - Read int: %d", socket.getRemoteSocketAddress(), value);
      return value;
    }

    return in.readInt();
  }

  public long readLong() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading long (8 bytes)...", socket.getRemoteSocketAddress());
      final long value = in.readLong();
      LogManager.instance()
          .info(this, "%s - Read long: %d", socket.getRemoteSocketAddress(), value);
      return value;
    }

    return in.readLong();
  }

  public short readShort() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading short (2 bytes)...", socket.getRemoteSocketAddress());
      final short value = in.readShort();
      LogManager.instance()
          .info(this, "%s - Read short: %d", socket.getRemoteSocketAddress(), value);
      return value;
    }

    return in.readShort();
  }

  public String readString() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading string (4+N bytes)...", socket.getRemoteSocketAddress());
      final int len = in.readInt();
      if (len > maxChunkSize) {
        throw new IOException(
            "Impossible to read a string chunk of length:"
                + len
                + " max allowed chunk length:"
                + maxChunkSize
                + " see NETWORK_BINARY_MAX_CONTENT_LENGTH settings ");
      }
      if (debug) {
        LogManager.instance()
            .info(this, "%s - Read string chunk length: %d", socket.getRemoteSocketAddress(), len);
      }
      if (len < 0) {
        return null;
      }

      // REUSE STATIC BUFFER?
      final byte[] tmp = new byte[len];
      in.readFully(tmp);

      final String value = new String(tmp, StandardCharsets.UTF_8);
      LogManager.instance()
          .info(this, "%s - Read string: %s", socket.getRemoteSocketAddress(), value);
      return value;
    }

    final int len = in.readInt();
    if (len < 0) {
      return null;
    }

    final byte[] tmp = new byte[len];
    in.readFully(tmp);

    return new String(tmp, StandardCharsets.UTF_8);
  }

  public byte[] readBytes() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(
              this,
              "%s - Reading chunk of bytes. Reading chunk length as int (4 bytes)...",
              socket.getRemoteSocketAddress());
    }

    final int len = in.readInt();
    if (len > maxChunkSize) {
      throw new IOException(
          "Impossible to read a chunk of length:"
              + len
              + " max allowed chunk length:"
              + maxChunkSize
              + " see NETWORK_BINARY_MAX_CONTENT_LENGTH settings ");
    }

    if (debug) {
      LogManager.instance()
          .info(this, "%s - Read chunk length: %d", socket.getRemoteSocketAddress(), len);
    }

    if (len < 0) {
      return null;
    }

    if (debug) {
      LogManager.instance()
          .info(this, "%s - Reading %d bytes...", socket.getRemoteSocketAddress(), len);
    }

    // REUSE STATIC BUFFER?
    final byte[] tmp = new byte[len];
    in.readFully(tmp);

    if (debug) {
      LogManager.instance()
          .info(
              this,
              "%s - Read %d bytes: %s",
              socket.getRemoteSocketAddress(),
              len,
              new String(tmp));
    }

    return tmp;
  }

  public RecordId readRID() throws IOException {
    final int clusterId = readShort();
    final long clusterPosition = readLong();
    return new RecordId(clusterId, clusterPosition);
  }

  public int readVersion() throws IOException {
    return readInt();
  }

  public SocketChannelBinary writeByte(final byte iContent) throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Writing byte (1 byte): %d", socket.getRemoteSocketAddress(), iContent);
    }

    out.write(iContent);
    return this;
  }

  public SocketChannelBinary writeBoolean(final boolean iContent) throws IOException {
    if (debug) {
      LogManager.instance()
          .info(
              this, "%s - Writing boolean (1 byte): %b", socket.getRemoteSocketAddress(), iContent);
    }

    out.writeBoolean(iContent);
    return this;
  }

  public SocketChannelBinary writeInt(final int iContent) throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Writing int (4 bytes): %d", socket.getRemoteSocketAddress(), iContent);
    }

    out.writeInt(iContent);
    return this;
  }

  public SocketChannelBinary writeLong(final long iContent) throws IOException {
    if (debug) {
      LogManager.instance()
          .info(this, "%s - Writing long (8 bytes): %d", socket.getRemoteSocketAddress(), iContent);
    }

    out.writeLong(iContent);
    return this;
  }

  public SocketChannelBinary writeShort(final short iContent) throws IOException {
    if (debug) {
      LogManager.instance()
          .info(
              this, "%s - Writing short (2 bytes): %d", socket.getRemoteSocketAddress(), iContent);
    }

    out.writeShort(iContent);
    return this;
  }

  public SocketChannelBinary writeString(final String iContent) throws IOException {
    if (debug) {
      LogManager.instance()
          .info(
              this,
              "%s - Writing string (4+%d=%d bytes): %s",
              socket.getRemoteSocketAddress(),
              iContent != null ? iContent.length() : 0,
              iContent != null ? iContent.length() + 4 : 4,
              iContent);
    }

    if (iContent == null) {
      out.writeInt(-1);
    } else {
      final byte[] buffer = iContent.getBytes(StandardCharsets.UTF_8);
      if (buffer.length > maxChunkSize) {
        throw new InvalidBinaryChunkException(
            "Impossible to write a chunk of length:"
                + buffer.length
                + " max allowed chunk length:"
                + maxChunkSize
                + " see NETWORK_BINARY_MAX_CONTENT_LENGTH settings ");
      }

      out.writeInt(buffer.length);
      out.write(buffer, 0, buffer.length);
    }

    return this;
  }

  public SocketChannelBinary writeBytes(final byte[] iContent) throws IOException {
    return writeBytes(iContent, iContent != null ? iContent.length : 0);
  }

  public SocketChannelBinary writeBytes(final byte[] iContent, final int iLength)
      throws IOException {
    if (debug) {
      LogManager.instance()
          .info(
              this,
              "%s - Writing bytes (4+%d=%d bytes): %s",
              socket.getRemoteSocketAddress(),
              iLength,
              iLength + 4,
              Arrays.toString(iContent));
    }

    if (iContent == null) {
      out.writeInt(-1);
    } else {
      if (iLength > maxChunkSize) {
        throw new InvalidBinaryChunkException(
            "Impossible to write a chunk of length:"
                + iLength
                + " max allowed chunk length:"
                + maxChunkSize
                + " see NETWORK_BINARY_MAX_CONTENT_LENGTH settings ");
      }

      out.writeInt(iLength);
      out.write(iContent, 0, iLength);
    }
    return this;
  }

  public void writeRID(final RID iRID) throws IOException {
    writeShort((short) iRID.getClusterId());
    writeLong(iRID.getClusterPosition());
  }

  public void writeVersion(final int version) throws IOException {
    writeInt(version);
  }

  public void clearInput() throws IOException {
    if (in == null) {
      return;
    }

    final StringBuilder dirtyBuffer = new StringBuilder(MAX_LENGTH_DEBUG);
    int i = 0;
    while (in.available() > 0) {
      char c = (char) in.read();
      ++i;

      if (dirtyBuffer.length() < MAX_LENGTH_DEBUG) {
        dirtyBuffer.append(c);
      }
    }

    final String message =
        "Received unread response from "
            + socket.getRemoteSocketAddress()
            + " probably corrupted data from the network connection. Cleared dirty data in the"
            + " buffer ("
            + i
            + " bytes): ["
            + dirtyBuffer
            + (i > dirtyBuffer.length() ? "..." : "")
            + "]";
    LogManager.instance().error(this, message, null);
    throw new YTIOException(message);
  }

  @Override
  public void flush() throws IOException {
    if (debug) {
      LogManager.instance()
          .info(
              this,
              "%s - Flush",
              socket != null ? " null possible previous close" : socket.getRemoteSocketAddress());
    }

    if (out != null)
    // IT ALREADY CALL THE UNDERLYING FLUSH
    {
      out.flush();
    } else {
      super.flush();
    }
  }

  @Override
  public void close() {
    if (debug) {
      LogManager.instance()
          .info(
              this,
              "%s - Closing socket...",
              socket != null ? " null possible previous close" : socket.getRemoteSocketAddress());
    }

    try {
      if (in != null) {
        in.close();
      }
    } catch (IOException e) {
      LogManager.instance().debug(this, "Error during closing of input stream", e);
    }

    try {
      if (out != null) {
        out.close();
      }
    } catch (IOException e) {
      LogManager.instance().debug(this, "Error during closing of output stream", e);
    }

    super.close();
  }

  public DataOutputStream getDataOutput() {
    return out;
  }

  public DataInputStream getDataInput() {
    return in;
  }

  public void setWaitResponseTimeout() throws SocketException {
    final Socket s = socket;
    if (s != null) {
      s.setSoTimeout(responseTimeout);
    }
  }

  public void setWaitRequestTimeout() throws SocketException {
    final Socket s = socket;
    if (s != null) {
      s.setSoTimeout(0);
    }
  }

  public void setReadRequestTimeout() throws SocketException {
    final Socket s = socket;
    if (s != null) {
      s.setSoTimeout(networkTimeout);
    }
  }
}
