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
import com.jetbrains.youtrack.db.api.schema.PropertyType;
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
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.query.BasicLegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.type.EntityWrapper;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CommandResponse implements BinaryResponse {

  private final boolean asynch;
  private final CommandResultListener listener;
  private final DatabaseSessionInternal database;
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
      DatabaseSessionInternal database,
      CommandRequestText command,
      Map<Object, Object> params) {
    this.result = result;
    this.listener = listener;
    this.isRecordResultSet = isRecordResultSet;
    this.asynch = async;
    this.database = database;
    this.command = command;
    this.params = params;
  }

  public CommandResponse(
      boolean asynch,
      CommandResultListener listener,
      DatabaseSessionInternal database,
      boolean live) {
    this.asynch = asynch;
    this.listener = listener;
    this.database = database;
    this.live = live;
  }

  public void write(DatabaseSessionInternal session, ChannelDataOutput channel,
      int protocolVersion, RecordSerializer serializer)
      throws IOException {
    if (asynch) {
      if (params == null) {
        result = database.command(command).execute(session);
      } else {
        result = database.command(command).execute(session, params);
      }

      // FETCHPLAN HAS TO BE ASSIGNED AGAIN, because it can be changed by SQL statement
      channel.writeByte((byte) 0); // NO MORE RECORDS
    } else {
      serializeValue(
          database,
          channel,
          (SimpleValueFetchPlanCommandListener) listener,
          result,
          false,
          isRecordResultSet,
          protocolVersion,
          serializer);
      if (listener instanceof FetchPlanResults) {
        // SEND FETCHED RECORDS TO LOAD IN CLIENT CACHE
        for (DBRecord rec : ((FetchPlanResults) listener).getFetchedRecordsToSend()) {
          channel.writeByte((byte) 2); // CLIENT CACHE RECORD. IT
          // ISN'T PART OF THE
          // RESULT SET
          MessageHelper.writeIdentifiable(session, channel, rec, serializer);
        }

        channel.writeByte((byte) 0); // NO MORE RECORDS
      }
    }
  }

  public void serializeValue(
      DatabaseSessionInternal session,
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
          result = ((RecordId) result).getRecord();
        }

        if (listener != null) {
          listener.result(session, result);
        }
        if (identifiable instanceof DBRecord record) {
          if (record.isNotBound(session)) {
            identifiable = session.bindToSession(record);
          }
        }

        MessageHelper.writeIdentifiable(session, channel, identifiable, recordSerializer);
      } else {
        if (result instanceof EntityWrapper) {
          // RECORD
          channel.writeByte((byte) 'r');
          final EntityImpl entity = ((EntityWrapper) result).getDocument(session);
          if (listener != null) {
            listener.result(session, entity);
          }
          MessageHelper.writeIdentifiable(session, channel, entity, recordSerializer);
        } else {
          if (!isRecordResultSet) {
            writeSimpleValue(session, channel, listener, result, protocolVersion, recordSerializer);
          } else {
            if (MultiValue.isMultiValue(result)) {
              final byte collectionType = result instanceof Set ? (byte) 's' : (byte) 'l';
              channel.writeByte(collectionType);
              channel.writeInt(MultiValue.getSize(result));
              for (Object o : MultiValue.getMultiValueIterable(result)) {
                try {
                  if (load && o instanceof RecordId) {
                    o = ((RecordId) o).getRecord();
                  }
                  if (listener != null) {
                    listener.result(session, o);
                  }

                  MessageHelper.writeIdentifiable(session, channel, (Identifiable) o,
                      recordSerializer);
                } catch (Exception e) {
                  LogManager.instance().warn(this, "Cannot serialize record: " + o);
                  MessageHelper.writeIdentifiable(session, channel, null, recordSerializer);
                  // WRITE NULL RECORD TO AVOID BREAKING PROTOCOL
                }
              }
            } else {
              if (MultiValue.isIterable(result)) {
                if (protocolVersion >= ChannelBinaryProtocol.PROTOCOL_VERSION_32) {
                  channel.writeByte((byte) 'i');
                  for (Object o : MultiValue.getMultiValueIterable(result)) {
                    try {
                      if (load && o instanceof RecordId) {
                        o = ((RecordId) o).getRecord();
                      }
                      if (listener != null) {
                        listener.result(session, o);
                      }

                      channel.writeByte((byte) 1); // ONE MORE RECORD
                      MessageHelper.writeIdentifiable(session,
                          channel, (Identifiable) o, recordSerializer);
                    } catch (Exception e) {
                      LogManager.instance().warn(this, "Cannot serialize record: " + o);
                    }
                  }
                  channel.writeByte((byte) 0); // NO MORE RECORD
                } else {
                  // OLD RELEASES: TRANSFORM IN A COLLECTION
                  final byte collectionType = result instanceof Set ? (byte) 's' : (byte) 'l';
                  channel.writeByte(collectionType);
                  channel.writeInt(MultiValue.getSize(result));
                  for (Object o : MultiValue.getMultiValueIterable(result)) {
                    try {
                      if (load && o instanceof RecordId) {
                        o = ((RecordId) o).getRecord();
                      }
                      if (listener != null) {
                        listener.result(session, o);
                      }

                      MessageHelper.writeIdentifiable(session,
                          channel, (Identifiable) o, recordSerializer);
                    } catch (Exception e) {
                      LogManager.instance().warn(this, "Cannot serialize record: " + o);
                    }
                  }
                }

              } else {
                // ANY OTHER (INCLUDING LITERALS)
                writeSimpleValue(session, channel, listener, result, protocolVersion,
                    recordSerializer);
              }
            }
          }
        }
      }
    }
  }

  private static void writeSimpleValue(
      DatabaseSessionInternal session, ChannelDataOutput channel,
      SimpleValueFetchPlanCommandListener listener,
      Object result,
      int protocolVersion,
      RecordSerializer recordSerializer)
      throws IOException {

    if (protocolVersion >= ChannelBinaryProtocol.PROTOCOL_VERSION_35) {
      channel.writeByte((byte) 'w');
      EntityImpl entity = new EntityImpl();
      entity.field("result", result);
      MessageHelper.writeIdentifiable(session, channel, entity, recordSerializer);
      if (listener != null) {
        listener.linkdedBySimpleValue(entity);
      }
    } else {
      channel.writeByte((byte) 'a');
      final StringBuilder value = new StringBuilder(64);
      if (listener != null) {
        EntityImpl entity = new EntityImpl();
        entity.field("result", result);
        listener.linkdedBySimpleValue(entity);
      }
      RecordSerializerStringAbstract.fieldTypeToString(value,
          PropertyType.getTypeByClass(result.getClass()), result);
      channel.writeString(value.toString());
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

      boolean addNextRecord = true;
      if (asynch) {
        byte status;

        // ASYNCH: READ ONE RECORD AT TIME
        while ((status = network.readByte()) > 0) {
          final RecordAbstract record =
              (RecordAbstract) MessageHelper.readIdentifiable(db, network, serializer);
          if (record == null) {
            continue;
          }

          switch (status) {
            case 1:
              // PUT AS PART OF THE RESULT SET. INVOKE THE LISTENER
              if (addNextRecord) {
                addNextRecord = listener.result(database, record);
                var cachedRecord = database.getLocalCache().findRecord(record.getIdentity());
                if (cachedRecord != record) {
                  if (cachedRecord != null) {
                    record.copyTo(cachedRecord);
                  } else {
                    database.getLocalCache().updateRecord(record);
                  }
                }
              }
              break;

            case 2:
              if (record.getIdentity().getClusterId() == -2) {
                temporaryResults.add(record);
              }
              var cachedRecord = database.getLocalCache().findRecord(record.getIdentity());
              if (cachedRecord != record) {
                if (cachedRecord != null) {
                  record.copyTo(cachedRecord);
                } else {
                  database.getLocalCache().updateRecord(record);
                }
              }
          }
        }
      } else {
        result = readSynchResult(network, database, temporaryResults);
        if (live) {
          final EntityImpl entity = ((List<EntityImpl>) result).get(0);
          final Integer token = entity.field("token");
          final Boolean unsubscribe = entity.field("unsubscribe");
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
            throw new StorageException("Cannot execute live query, returned null token");
          }
        }
      }
      if (!temporaryResults.isEmpty()) {
        if (result instanceof BasicLegacyResultSet<?>) {
          ((BasicLegacyResultSet<?>) result).setTemporaryRecordCache(temporaryResults);
        }
      }
    } finally {
      // TODO: this is here because we allow query in end listener.
      session.commandExecuting = false;
      if (listener != null && !live) {
        listener.end();
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

    final byte type = network.readByte();
    switch (type) {
      case 'n':
        result = null;
        break;

      case 'r':
        result = MessageHelper.readIdentifiable(database, network, serializer);
        if (result instanceof RecordAbstract record) {
          var cachedRecord = database.getLocalCache().findRecord(record.getIdentity());
          if (cachedRecord != record) {
            if (cachedRecord != null) {
              record.copyTo(cachedRecord);
            } else {
              database.getLocalCache().updateRecord(record);
            }
          }
        }
        break;

      case 'l':
      case 's':
        final int tot = network.readInt();
        Collection<Identifiable> coll =
            type == 's' ? new HashSet<>(tot) : new BasicLegacyResultSet<>(tot);
        for (int i = 0; i < tot; ++i) {
          final Identifiable resultItem = MessageHelper.readIdentifiable(database, network,
              serializer);
          if (resultItem instanceof RecordAbstract record) {
            var rid = record.getIdentity();
            var cacheRecord = database.getLocalCache().findRecord(rid);

            if (cacheRecord != record) {
              if (cacheRecord != null) {
                record.copyTo(cacheRecord);
                coll.add(cacheRecord);
              } else {
                database.getLocalCache().updateRecord(record);
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
          final Identifiable record = MessageHelper.readIdentifiable(database, network,
              serializer);
          if (record == null) {
            continue;
          }
          if (status == 1) {
            if (record instanceof RecordAbstract rec) {
              var cachedRecord = database.getLocalCache().findRecord(rec.getIdentity());

              if (cachedRecord != rec) {
                if (cachedRecord != null) {
                  rec.copyTo(cachedRecord);
                  coll.add(cachedRecord);
                } else {
                  database.getLocalCache().updateRecord(rec);
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
        final Identifiable record = MessageHelper.readIdentifiable(database, network,
            serializer);
        // ((EntityImpl) record).setLazyLoad(false);
        result = ((EntityImpl) record).field("result");
        break;

      default:
        LogManager.instance().warn(this, "Received unexpected result from query: %d", type);
        result = null;
    }

    // LOAD THE FETCHED RECORDS IN CACHE
    byte status;
    while ((status = network.readByte()) > 0) {
      RecordAbstract record =
          (RecordAbstract) MessageHelper.readIdentifiable(database, network, serializer);
      if (record != null && status == 2) {
        // PUT IN THE CLIENT LOCAL CACHE
        var cachedRecord = database.getLocalCache().findRecord(record.getIdentity());
        if (cachedRecord != record) {
          if (cachedRecord != null) {
            record.copyTo(cachedRecord);
            record = cachedRecord;
          } else {
            database.getLocalCache().updateRecord(record);
          }
        }

        if (record.getIdentity().getClusterId() == -2) {
          temporaryResults.add(record);
        }
      }
    }

    return result;
  }

  public Object getResult() {
    return result;
  }
}
