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
package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.client.remote.BinaryResponse;
import com.jetbrains.youtrack.db.internal.client.remote.FetchPlanResults;
import com.jetbrains.youtrack.db.internal.client.remote.SimpleValueFetchPlanCommandListener;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import com.jetbrains.youtrack.db.internal.core.sql.query.BasicLegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.type.IdentityWrapper;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CommandResponse implements BinaryResponse {

  private final boolean asynch;
  private final CommandResultListener listener;
  private final DatabaseSessionInternal session;
  private boolean live;
  private Object result;
  private boolean isRecordResultSet;
  private CommandRequestText command;
  private Map<Object, Object> params;

  public CommandResponse(
      Object result,
      SimpleValueFetchPlanCommandListener listener,
      boolean isRecordResultSet,
      boolean async,
      DatabaseSessionInternal session,
      CommandRequestText command,
      Map<Object, Object> params) {
    this.result = result;
    this.listener = listener;
    this.isRecordResultSet = isRecordResultSet;
    this.asynch = async;
    this.session = session;
    this.command = command;
    this.params = params;
  }

  public CommandResponse(
      boolean asynch,
      CommandResultListener listener,
      DatabaseSessionInternal session,
      boolean live) {
    this.asynch = asynch;
    this.listener = listener;
    this.session = session;
    this.live = live;
  }

  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    if (asynch) {
      if (params == null) {
        result = this.session.command(command).execute(session);
      } else {
        result = this.session.command(command).execute(session, params);
      }

      // FETCHPLAN HAS TO BE ASSIGNED AGAIN, because it can be changed by SQL statement
      channel.writeByte((byte) 0); // NO MORE RECORDS
    } else {
      serializeValue(
          this.session,
          channel,
          (SimpleValueFetchPlanCommandListener) listener,
          result,
          false,
          isRecordResultSet,
          protocolVersion,
          serializer);
      if (listener instanceof FetchPlanResults) {
        // SEND FETCHED RECORDS TO LOAD IN CLIENT CACHE
        for (var rec : ((FetchPlanResults) listener).getFetchedRecordsToSend()) {
          channel.writeByte((byte) 2); // CLIENT CACHE RECORD. IT
          // ISN'T PART OF THE
          // RESULT SET
          MessageHelper.writeIdentifiable(session, channel, rec);
        }

        channel.writeByte((byte) 0); // NO MORE RECORDS
      }
    }
  }

  public void serializeValue(
      DatabaseSessionInternal db,
      ChannelDataOutput channel,
      final SimpleValueFetchPlanCommandListener listener,
      Object result,
      boolean load,
      boolean isRecordResultSet,
      int protocolVersion,
      RecordSerializer recordSerializer)
      throws IOException {
    if (result == null) {
      // NULL VALUE
      channel.writeByte((byte) 'n');
    } else {
      if (result instanceof Identifiable identifiable) {
        // RECORD
        channel.writeByte((byte) 'r');
        if (load && result instanceof RecordId) {
          result = ((RecordId) result).getRecord(db);
        }

        if (listener != null) {
          listener.result(db, result);
        }
        if (identifiable instanceof DBRecord record) {
          if (record.isNotBound(db)) {
            identifiable = db.bindToSession(record);
          }
        }

        MessageHelper.writeIdentifiable(db, channel, identifiable);
      } else {
        if (result instanceof IdentityWrapper) {
          // RECORD
          channel.writeByte((byte) 'r');
          var entity = ((IdentityWrapper) result).getIdentity().getEntity(db);
          if (listener != null) {
            listener.result(db, entity);
          }
          MessageHelper.writeIdentifiable(db, channel, entity);
        } else {
          if (!isRecordResultSet) {
            writeSimpleValue(db, channel, listener, result, protocolVersion);
          } else {
            if (MultiValue.isMultiValue(result)) {
              final var collectionType = result instanceof Set ? (byte) 's' : (byte) 'l';
              channel.writeByte(collectionType);
              channel.writeInt(MultiValue.getSize(result));
              for (var o : MultiValue.getMultiValueIterable(result)) {
                try {
                  if (load && o instanceof RecordId) {
                    o = ((RecordId) o).getRecord(db);
                  }
                  if (listener != null) {
                    listener.result(db, o);
                  }

                  MessageHelper.writeIdentifiable(db, channel, (Identifiable) o
                  );
                } catch (Exception e) {
                  LogManager.instance().warn(this, "Cannot serialize record: " + o);
                  MessageHelper.writeIdentifiable(db, channel, null);
                  // WRITE NULL RECORD TO AVOID BREAKING PROTOCOL
                }
              }
            } else {
              if (MultiValue.isIterable(result)) {
                if (protocolVersion >= ChannelBinaryProtocol.PROTOCOL_VERSION_32) {
                  channel.writeByte((byte) 'i');
                  for (var o : MultiValue.getMultiValueIterable(result)) {
                    try {
                      if (load && o instanceof RecordId) {
                        o = ((RecordId) o).getRecord(db);
                      }
                      if (listener != null) {
                        listener.result(db, o);
                      }

                      channel.writeByte((byte) 1); // ONE MORE RECORD
                      MessageHelper.writeIdentifiable(db,
                          channel, (Identifiable) o);
                    } catch (Exception e) {
                      LogManager.instance().warn(this, "Cannot serialize record: " + o);
                    }
                  }
                  channel.writeByte((byte) 0); // NO MORE RECORD
                } else {
                  // OLD RELEASES: TRANSFORM IN A COLLECTION
                  final var collectionType = result instanceof Set ? (byte) 's' : (byte) 'l';
                  channel.writeByte(collectionType);
                  channel.writeInt(MultiValue.getSize(result));
                  for (var o : MultiValue.getMultiValueIterable(result)) {
                    try {
                      if (load && o instanceof RecordId) {
                        o = ((RecordId) o).getRecord(db);
                      }
                      if (listener != null) {
                        listener.result(db, o);
                      }

                      MessageHelper.writeIdentifiable(db,
                          channel, (Identifiable) o);
                    } catch (Exception e) {
                      LogManager.instance().warn(this, "Cannot serialize record: " + o);
                    }
                  }
                }

              } else {
                // ANY OTHER (INCLUDING LITERALS)
                writeSimpleValue(db, channel, listener, result, protocolVersion
                );
              }
            }
          }
        }
      }
    }
  }

  private static void writeSimpleValue(
      DatabaseSessionInternal db, ChannelDataOutput channel,
      SimpleValueFetchPlanCommandListener listener,
      Object result,
      int protocolVersion)
      throws IOException {

    if (protocolVersion >= ChannelBinaryProtocol.PROTOCOL_VERSION_35) {
      channel.writeByte((byte) 'w');
      var entity = db.newEmbeddedEntity();
      entity.setProperty("result", result);
      MessageHelper.writeIdentifiable(db, channel, entity);
      if (listener != null) {
        listener.linkdedBySimpleValue(db, (EntityImpl) entity);
      }
    } else {
      throw new UnsupportedEncodingException();
    }
  }

  @Override
  public void read(DatabaseSessionInternal db, ChannelDataInput network,
      StorageRemoteSession session) throws IOException {
    RecordSerializer serializer = RecordSerializerNetworkV37Client.INSTANCE;
    try {
      // Collection of prefetched temporary record (nested projection record), to refer for avoid
      // garbage collection.
      List<DBRecord> temporaryResults = new ArrayList<DBRecord>();

      var addNextRecord = true;
      if (asynch) {
        byte status;

        // ASYNCH: READ ONE RECORD AT TIME
        while ((status = network.readByte()) > 0) {
          final var record =
              (RecordAbstract) MessageHelper.readIdentifiable(db, network, serializer);
          if (record == null) {
            continue;
          }

          switch (status) {
            case 1:
              // PUT AS PART OF THE RESULT SET. INVOKE THE LISTENER
              if (addNextRecord) {
                addNextRecord = listener.result(db, record);
                updateCachedRecord(this.session, record);
              }
              break;

            case 2:
              if (record.getIdentity().getClusterId() == -2) {
                temporaryResults.add(record);
              }
              updateCachedRecord(this.session, record);
          }
        }
      } else {
        result = readSynchResult(network, this.session, temporaryResults);
        if (live) {
          final var entity = ((List<EntityImpl>) result).get(0);
          final Integer token = entity.getProperty("token");
          final Boolean unsubscribe = entity.getProperty("unsubscribe");
          if (token != null) {
            //
            //            StorageRemote storage = (StorageRemote) database.getStorage();
            //            if (Boolean.TRUE.equals(unsubscribe)) {
            //              if (storage.asynchEventListener != null)
            //                storage.asynchEventListener.unregisterLiveListener(token);
            //            } else {
            //              final LiveResultListener listener = (LiveResultListener)
            // this.listener;
            //              final ODatabaseDocument dbCopy = database.copy();
            //              RemoteConnectionPool pool =
            // storage.connectionManager.getPool(((SocketChannelBinaryAsynchClient)
            // network).getServerURL());
            //              storage.asynchEventListener.registerLiveListener(pool, token, new
            // LiveResultListener() {
            //
            //                @Override
            //                public void onUnsubscribe(int iLiveToken) {
            //                  listener.onUnsubscribe(iLiveToken);
            //                  dbCopy.close();
            //                }
            //
            //                @Override
            //                public void onLiveResult(int iLiveToken, RecordOperation iOp) throws
            // BaseException {
            //                  dbCopy.activateOnCurrentThread();
            //                  listener.onLiveResult(iLiveToken, iOp);
            //                }
            //
            //                @Override
            //                public void onError(int iLiveToken) {
            //                  listener.onError(iLiveToken);
            //                  dbCopy.close();
            //                }
            //              });
            //            }
          } else {
            throw new StorageException(this.session.getDatabaseName(),
                "Cannot execute live query, returned null token");
          }
        }
      }

    } finally {
      // TODO: this is here because we allow query in end listener.
      session.commandExecuting = false;
      if (listener != null && !live) {
        listener.end(db);
      }
    }
  }

  private Object readSynchResult(
      final ChannelDataInput network,
      final DatabaseSessionInternal database,
      List<DBRecord> temporaryResults)
      throws IOException {
    RecordSerializer serializer = RecordSerializerNetworkV37Client.INSTANCE;
    final Object result;

    final var type = network.readByte();
    switch (type) {
      case 'n':
        result = null;
        break;

      case 'r':
        result = MessageHelper.readIdentifiable(database, network, serializer);
        if (result instanceof RecordAbstract record) {
          updateCachedRecord(database, record);
        }
        break;

      case 'l':
      case 's':
        final var tot = network.readInt();
        Collection<Identifiable> coll =
            type == 's' ? new HashSet<>(tot) : new BasicLegacyResultSet<>(tot);
        for (var i = 0; i < tot; ++i) {
          final var resultItem = MessageHelper.readIdentifiable(database, network,
              serializer);
          if (resultItem instanceof RecordAbstract record) {
            var rid = record.getIdentity();
            var cacheRecord = database.getLocalCache().findRecord(rid);

            if (cacheRecord != record) {
              if (cacheRecord != null) {
                cacheRecord.fromStream(record.toStream());
                cacheRecord.setVersion(record.getVersion());
                coll.add(cacheRecord);
              } else {
                database.getLocalCache().updateRecord(record, session);
                coll.add(resultItem);
              }
            } else {
              coll.add(resultItem);
            }
          }
        }

        result = coll;
        break;
      case 'i':
        coll = new BasicLegacyResultSet<Identifiable>();
        byte status;
        while ((status = network.readByte()) > 0) {
          final var record = MessageHelper.readIdentifiable(database, network,
              serializer);
          if (record == null) {
            continue;
          }
          if (status == 1) {
            if (record instanceof RecordAbstract rec) {
              var cachedRecord = database.getLocalCache().findRecord(rec.getIdentity());

              if (cachedRecord != rec) {
                if (cachedRecord != null) {
                  cachedRecord.fromStream(rec.toStream());
                  cachedRecord.setVersion(rec.getVersion());
                  coll.add(cachedRecord);
                } else {
                  database.getLocalCache().updateRecord(rec, session);
                  coll.add(rec);
                }
              } else {
                coll.add(rec);
              }
            }
          }
        }
        result = coll;
        break;
      case 'w':
        final var record = MessageHelper.readIdentifiable(database, network,
            serializer);
        // ((EntityImpl) record).setLazyLoad(false);
        result = ((EntityImpl) record).getProperty("result");
        break;

      default:
        LogManager.instance().warn(this, "Received unexpected result from query: %d", type);
        result = null;
    }

    // LOAD THE FETCHED RECORDS IN CACHE
    byte status;
    while ((status = network.readByte()) > 0) {
      var record =
          (RecordAbstract) MessageHelper.readIdentifiable(database, network, serializer);
      if (record != null && status == 2) {
        // PUT IN THE CLIENT LOCAL CACHE
        var cachedRecord = database.getLocalCache().findRecord(record.getIdentity());
        if (cachedRecord != record) {
          if (cachedRecord != null) {
            cachedRecord.fromStream(record.toStream());
            cachedRecord.setVersion(record.getVersion());
            record = cachedRecord;
          } else {
            database.getLocalCache().updateRecord(record, session);
          }
        }

        if (record.getIdentity().getClusterId() == -2) {
          temporaryResults.add(record);
        }
      }
    }

    return result;
  }

  private static void updateCachedRecord(DatabaseSessionInternal session, RecordAbstract record) {
    var cachedRecord = session.getLocalCache().findRecord(record.getIdentity());

    if (cachedRecord != record) {
      if (cachedRecord != null) {
        cachedRecord.fromStream(record.toStream());
        cachedRecord.setVersion(record.getVersion());
      } else {
        session.getLocalCache().updateRecord(record, session);
      }
    }
  }

  public Object getResult() {
    return result;
  }
}
