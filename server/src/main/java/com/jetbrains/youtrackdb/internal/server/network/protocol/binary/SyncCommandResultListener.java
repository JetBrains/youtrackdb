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

package com.jetbrains.youtrackdb.internal.server.network.protocol.binary;

import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.client.remote.FetchPlanResults;
import com.jetbrains.youtrackdb.internal.core.command.CommandResultListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.FetchException;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Synchronous command result manager.
 */
public class SyncCommandResultListener extends AbstractCommandResultListener
    implements FetchPlanResults {

  private final Set<DBRecord> fetchedRecordsToSend = new HashSet<DBRecord>();
  private final Set<DBRecord> alreadySent = new HashSet<DBRecord>();

  public SyncCommandResultListener(final CommandResultListener wrappedResultListener) {
    super(wrappedResultListener);
  }

  @Override
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    if (iRecord instanceof DBRecord) {
      alreadySent.add((DBRecord) iRecord);
      fetchedRecordsToSend.remove(iRecord);
    }

    if (wrappedResultListener != null)
    // NOTIFY THE WRAPPED LISTENER
    {
      wrappedResultListener.result(session, iRecord);
    }

    fetchRecord(session,
        iRecord, new RemoteFetchListener() {
          @Override
          protected void sendRecord(RecordAbstract iLinked) {
            if (!alreadySent.contains(iLinked)) {
              fetchedRecordsToSend.add(iLinked);
            }
          }
        });
    return true;
  }

  @Override
  public Set<DBRecord> getFetchedRecordsToSend() {
    return fetchedRecordsToSend;
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public void linkdedBySimpleValue(DatabaseSessionInternal db, EntityImpl entity) {

    var listener =
        new RemoteFetchListener() {
          @Override
          protected void sendRecord(RecordAbstract iLinked) {
            if (!alreadySent.contains(iLinked)) {
              fetchedRecordsToSend.add(iLinked);
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
            if (!(iLinked instanceof RecordIdInternal)) {
              var transaction = db.getActiveTransaction();
              sendRecord(transaction.load(iLinked));
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

            if (!(iLinked instanceof RecordIdInternal)) {
              var transaction = db.getActiveTransaction();
              sendRecord(transaction.load(iLinked));
            }
          }
        };
    final FetchContext context = new RemoteFetchContext();
    FetchHelper.fetch(db, entity, entity, FetchHelper.buildFetchPlan(""), listener, context, "");
  }
}
