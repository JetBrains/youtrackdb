/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.fetch.remote;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.FetchException;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchListener;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import javax.annotation.Nullable;

/**
 * Fetch listener for {@class ONetworkBinaryProtocol} class
 *
 * <p>Whenever a record has to be fetched it will be added to the list of records to send
 */
public abstract class RemoteFetchListener implements FetchListener {

  @Override
  public boolean requireFieldProcessing() {
    return false;
  }

  public RemoteFetchListener() {
  }

  protected abstract void sendRecord(RecordAbstract iLinked);

  @Override
  public void processStandardField(
      DatabaseSessionEmbedded db, EntityImpl iRecord,
      Object iFieldValue,
      String iFieldName,
      FetchContext iContext,
      final Object iusObject,
      final String iFormat,
      PropertyTypeInternal filedType)
      throws FetchException {
  }

  @Override
  public void parseLinked(
      DatabaseSessionEmbedded db, EntityImpl iRootRecord,
      Identifiable iLinked,
      Object iUserObject,
      String iFieldName,
      FetchContext iContext)
      throws FetchException {
  }

  @Override
  public void parseLinkedCollectionValue(
      DatabaseSessionEmbedded db, EntityImpl iRootRecord,
      Identifiable iLinked,
      Object iUserObject,
      String iFieldName,
      FetchContext iContext)
      throws FetchException {
  }

  @Nullable
  @Override
  public Object fetchLinkedMapEntry(
      EntityImpl iRoot,
      Object iUserObject,
      String iFieldName,
      String iKey,
      EntityImpl iLinked,
      FetchContext iContext)
      throws FetchException {
    if (iLinked.getIdentity().isValidPosition()) {
      sendRecord(iLinked);
      return true;
    }
    return null;
  }

  @Nullable
  @Override
  public Object fetchLinkedCollectionValue(
      EntityImpl iRoot,
      Object iUserObject,
      String iFieldName,
      EntityImpl iLinked,
      FetchContext iContext)
      throws FetchException {
    if (iLinked.getIdentity().isValidPosition()) {
      sendRecord(iLinked);
      return true;
    }
    return null;
  }

  @Override
  public Object fetchLinked(
      EntityImpl iRoot,
      Object iUserObject,
      String iFieldName,
      EntityImpl iLinked,
      FetchContext iContext)
      throws FetchException {
    sendRecord(iLinked);
    return true;
  }

  @Override
  public void skipStandardField(
      EntityImpl iRecord,
      String iFieldName,
      FetchContext iContext,
      Object iUserObject,
      String iFormat)
      throws FetchException {
  }
}
