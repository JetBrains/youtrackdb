/*
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
 */

package com.jetbrains.youtrack.db.internal.server.network.protocol.binary;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.FetchException;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/**
 * Asynchronous command result manager. As soon as a record is returned by the command is sent over
 * the wire.
 */
public class AsyncCommandResultListener extends AbstractCommandResultListener {

  private final NetworkProtocolBinary protocol;
  private final AtomicBoolean empty = new AtomicBoolean(true);
  private final int txId;
  private final Set<RID> alreadySent = new HashSet<RID>();
  private final ClientConnection connection;

  public AsyncCommandResultListener(
      ClientConnection connection, final CommandResultListener wrappedResultListener) {
    super(wrappedResultListener);
    this.protocol = (NetworkProtocolBinary) connection.getProtocol();
    this.txId = connection.getId();
    this.connection = connection;
  }

  @Override
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    empty.compareAndSet(true, false);

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
      protocol.channel.flush(); // TODO review this flush... it's for non blocking...

      if (wrappedResultListener != null)
      // NOTIFY THE WRAPPED LISTENER
      {
        wrappedResultListener.result(session, iRecord);
      }

    } catch (IOException e) {
      return false;
    }

    return true;
  }

  @Override
  public boolean isEmpty() {
    return empty.get();
  }

  @Override
  public void linkdedBySimpleValue(DatabaseSessionInternal db, EntityImpl entity) {
    var listener =
        new RemoteFetchListener() {
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

          @Override
          public void parseLinked(
              DatabaseSessionInternal db, EntityImpl iRootRecord,
              Identifiable iLinked,
              Object iUserObject,
              String iFieldName,
              FetchContext iContext)
              throws FetchException {
            if (iLinked instanceof RecordAbstract record) {
              sendRecord(record);
            }
          }

          @Override
          public void parseLinkedCollectionValue(
              DatabaseSessionInternal db, EntityImpl iRootRecord,
              Identifiable iLinked,
              Object iUserObject,
              String iFieldName,
              FetchContext iContext)
              throws FetchException {
            if (iLinked instanceof RecordAbstract record) {
              sendRecord(record);
            }
          }
        };
    final FetchContext context = new RemoteFetchContext();
    FetchHelper.fetch(db, entity, entity, FetchHelper.buildFetchPlan(""),
        listener, context, "");
  }
}
