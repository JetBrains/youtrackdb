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

package com.jetbrains.youtrack.db.internal.server.network.protocol.binary;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.LiveResultListener;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.ClientSessions;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/**
 * Asynchronous command result manager. As soon as a record is returned by the command is sent over
 * the wire.
 */
public class LiveCommandResultListener extends AbstractCommandResultListener
    implements LiveResultListener {

  private final ClientConnection connection;
  private final AtomicBoolean empty = new AtomicBoolean(true);
  private final int sessionId;
  private final Set<RID> alreadySent = new HashSet<RID>();
  private final ClientSessions session;

  public LiveCommandResultListener(
      YouTrackDBServer server,
      final ClientConnection connection,
      CommandResultListener wrappedResultListener) {
    super(wrappedResultListener);
    this.connection = connection;
    session = server.getClientConnectionManager().getSession(connection);
    this.sessionId = connection.getId();
  }

  @Override
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    final var protocol = ((NetworkProtocolBinary) connection.getProtocol());
    if (empty.compareAndSet(true, false)) {
      try {
        protocol.channel.writeByte(ChannelBinaryProtocol.RESPONSE_STATUS_OK);
        protocol.channel.writeInt(protocol.clientTxId);
        protocol.okSent = true;
        if (connection != null
            && Boolean.TRUE.equals(connection.getTokenBased())
            && connection.getToken() != null
            && protocol.requestType != ChannelBinaryProtocol.REQUEST_CONNECT
            && protocol.requestType != ChannelBinaryProtocol.REQUEST_DB_OPEN) {
          // TODO: Check if the token is expiring and if it is send a new token
          var renewedToken =
              protocol.getServer().getTokenHandler().renewIfNeeded(connection.getToken());
          protocol.channel.writeBytes(renewedToken);
        }
      } catch (IOException ignored) {
      }
    }

    try {
      fetchRecord(session,
          iRecord, new RemoteFetchListener() {
            @Override
            protected void sendRecord(RecordAbstract iLinked) {
              if (!alreadySent.contains(iLinked.getIdentity())) {
                alreadySent.add(iLinked.getIdentity());
                try {
                  protocol.channel.writeByte((byte) 2); // CACHE IT ON THE CLIENT
                  NetworkProtocolBinary.writeIdentifiable(protocol.channel, connection, iLinked);
                } catch (IOException e) {
                  LogManager.instance().error(this, "Cannot write against channel", e);
                }
              }
            }
          });
      alreadySent.add(((Identifiable) iRecord).getIdentity());
      protocol.channel.writeByte((byte) 1); // ONE MORE RECORD
      var transaction = session.getActiveTransaction();
      NetworkProtocolBinary.writeIdentifiable(
          protocol.channel, connection, transaction.load(((Identifiable) iRecord)));
      protocol.channel.flush();
    } catch (IOException e) {
      return false;
    }
    return true;
  }

  public boolean isEmpty() {
    return empty.get();
  }

  public void onLiveResult(DatabaseSessionInternal db, int iToken, RecordOperation iOp)
      throws BaseException {
    var sendFail = true;
    do {
      var connections = session.getConnections();
      if (connections.size() == 0) {
        try {

          LogManager.instance()
              .warn(this, "Unsubscribing live query for connection " + connection);
          LiveQueryHook.unsubscribe(iToken, db);
        } catch (Exception e) {
          LogManager.instance()
              .warn(this, "Unsubscribing live query for connection " + connection, e);
        }
        break;
      }
      var curConnection = connections.get(0);
      var protocol = (NetworkProtocolBinary) curConnection.getProtocol();

      var channel = protocol.getChannel();
      try {
        channel.acquireWriteLock();
        try {

          var content = new ByteArrayOutputStream();

          var out = new DataOutputStream(content);
          out.writeByte('r');
          out.writeByte(iOp.type);
          out.writeInt(iToken);
          out.writeByte(iOp.record.getRecordType());
          writeVersion(out, iOp.record.getVersion());
          writeRID(out, iOp.record.getIdentity());
          writeBytes(out, NetworkProtocolBinary.getRecordBytes(connection, iOp.record));
          channel.writeByte(ChannelBinaryProtocol.PUSH_DATA);
          channel.writeInt(Integer.MIN_VALUE);
          channel.writeByte(ChannelBinaryProtocol.REQUEST_PUSH_LIVE_QUERY);
          channel.writeBytes(content.toByteArray());
          channel.flush();

        } finally {
          channel.releaseWriteLock();
        }
        sendFail = false;
      } catch (IOException e) {
        session.removeConnection(curConnection);
        connections = session.getConnections();
        if (connections.isEmpty()) {
          LiveQueryHook.unsubscribe(iToken, db);
          break;
        }
      } catch (Exception e) {
        LogManager.instance()
            .warn(
                this,
                "Cannot push collection configuration to the client %s",
                e,
                protocol.getRemoteAddress());
        protocol.getServer().getClientConnectionManager().disconnect(connection);
        LiveQueryHook.unsubscribe(iToken, connection.getDatabaseSession());
        break;
      }

    } while (sendFail);
  }

  @Override
  public void onError(int iLiveToken) {
  }

  @Override
  public void onUnsubscribe(int iLiveToken) {
    var sendFail = true;
    do {
      var connections = session.getConnections();
      if (connections.size() == 0) {
        break;
      }
      var protocol = (NetworkProtocolBinary) connections.get(0).getProtocol();

      var channel = protocol.getChannel();
      try {
        channel.acquireWriteLock();
        try {

          var content = new ByteArrayOutputStream();

          var out = new DataOutputStream(content);
          out.writeByte('u');
          out.writeInt(iLiveToken);
          channel.writeByte(ChannelBinaryProtocol.PUSH_DATA);
          channel.writeInt(Integer.MIN_VALUE);
          channel.writeByte(ChannelBinaryProtocol.REQUEST_PUSH_LIVE_QUERY);
          channel.writeBytes(content.toByteArray());
          channel.flush();

        } finally {
          channel.releaseWriteLock();
        }
        sendFail = false;
      } catch (IOException e) {
        connections = session.getConnections();
        if (connections.isEmpty()) {
          break;
        }
      } catch (Exception e) {
        LogManager.instance()
            .warn(
                this,
                "Cannot push collection configuration to the client %s",
                e,
                protocol.getRemoteAddress());
        protocol.getServer().getClientConnectionManager().disconnect(connection);
        break;
      }

    } while (sendFail);
  }

  private void writeVersion(DataOutputStream out, int v) throws IOException {
    out.writeInt(v);
  }

  private void writeRID(DataOutputStream out, RecordId record) throws IOException {
    out.writeShort((short) record.getCollectionId());
    out.writeLong(record.getCollectionPosition());
  }

  public void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  @Override
  public void linkdedBySimpleValue(DatabaseSessionInternal db, EntityImpl entity) {
  }
}
