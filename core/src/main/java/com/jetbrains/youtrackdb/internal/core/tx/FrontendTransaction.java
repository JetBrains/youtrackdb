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
package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.LoadRecordResult;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface FrontendTransaction extends Transaction {

  boolean isSchemaChanged();

  enum TXSTATUS {
    INVALID,
    BEGUN,
    COMMITTING,
    ROLLBACKING,
    COMPLETED,
    ROLLED_BACK
  }

  int beginInternal();

  @Nullable
  Map<RID, RID> commitInternal();

  @Nullable
  Map<RID, RID> commitInternal(boolean force);

  void rollbackInternal();

  @Override
  @Nonnull
  DatabaseSessionEmbedded getDatabaseSession();

  @Deprecated
  void clearRecordEntries();

  @Nonnull
  LoadRecordResult loadRecord(RID rid)
      throws RecordNotFoundException;

  @Override
  boolean exists(@Nonnull RID rid);

  TXSTATUS getStatus();

  @Deprecated
  Iterable<? extends RecordOperation> getCurrentRecordEntries();

  RecordOperation getRecordEntry(RID rid);

  List<String> getInvolvedIndexes();

  @Deprecated
  void clearIndexEntries();

  void close();

  /**
   * When commit in transaction is performed all new records will change their identity, but index
   * values will contain stale links, to fix them given method will be called for each entry. This
   * update local transaction maps too.
   *
   * @param oldRid Record identity before commit.
   * @param newRid Record identity after commit.
   */
  boolean assertIdentityChangedAfterCommit(final RecordIdInternal oldRid,
      final RecordIdInternal newRid);

  int amountOfNestedTxs();

  int getEntryCount();

  /**
   * @return {@code true} if this transaction is active, {@code false} otherwise.
   */
  @Override
  boolean isActive();

  /**
   * Deletes the given record in this transaction.
   *
   * @param record the record to delete.
   */
  void deleteRecord(RecordAbstract record);

  /**
   * Resolves a record with the given RID in the context of this transaction.
   *
   * @param rid the record RID.
   * @return the resolved record, or {@code null} if no record is found.
   */
  @Nullable
  RecordAbstract getRecord(RID rid);

  /**
   * Adds the transactional index entry in this transaction.
   *
   * @param index     the index.
   * @param indexName the index name.
   * @param operation the index operation to register.
   * @param key       the index key.
   * @param value     the index key value.
   */
  void addIndexEntry(
      Index index,
      String indexName,
      OPERATION operation,
      Object key,
      Identifiable value);

  /**
   * Obtains the index changes done in the context of this transaction.
   *
   * @param indexName the index name.
   * @return the index changes in question or {@code null} if index is not found.
   */
  @Nullable
  FrontendTransactionIndexChanges getIndexChanges(String indexName);

  /**
   * Does the same thing as {@link #getIndexChanges(String)}, but handles remote storages in a
   * special way.
   *
   * @param indexName the index name.
   * @return the index changes in question or {@code null} if index is not found or storage is
   * remote.
   */
  @Nullable
  FrontendTransactionIndexChanges getIndexChangesInternal(String indexName);

  /**
   * Obtains the custom value by its name stored in the context of this transaction.
   *
   * @param name the value name.
   * @return the obtained value or {@code null} if no value found.
   */
  Object getCustomData(String name);

  /**
   * Sets the custom value by its name stored in the context of this transaction.
   *
   * @param name  the value name.
   * @param value the value to store.
   */
  void setCustomData(String name, Object value);

  /**
   * Extract all the record operations for the current transaction
   *
   * @return the record operations, the collection should not be modified.
   */
  Collection<RecordOperation> getRecordOperationsInternal();

  /**
   * Extract all the calculated index operations for the current transaction changes, the key of the
   * map is the index name the value all the changes for the specified index.
   *
   * @return the index changes, the map should not be modified.
   */
  Map<String, FrontendTransactionIndexChanges> getIndexOperations();

  /**
   * Change the status of the transaction.
   */
  void setStatus(final FrontendTransaction.TXSTATUS iStatus);

  void setSession(DatabaseSessionEmbedded session);

  @Nullable
  default byte[] getMetadata() {
    return null;
  }

  void setMetadataHolder(FrontendTransacationMetadataHolder metadata);

  default void storageBegun() {
  }

  Iterator<byte[]> getSerializedOperations();

  long getId();

  void addRecordOperation(RecordAbstract record, byte status);

  @Nullable
  RecordIdInternal getFirstRid(int collectionId);

  @Nullable
  RecordIdInternal getLastRid(int collectionId);

  @Nullable
  RecordIdInternal getNextRidInCollection(@Nonnull RecordIdInternal rid);

  @Nullable
  RecordIdInternal getPreviousRidInCollection(@Nonnull RecordIdInternal rid);

  boolean isDeletedInTx(@Nonnull RID rid);

  @Nullable
  default List<RecordIdInternal> preProcessRecordsAndExecuteCallCallbacks() {
    return null;
  }

  boolean isCallBackProcessingInProgress();

  boolean isScheduledForCallbackProcessing(RecordIdInternal rid);

  @Nonnull
  RecordSerializationContext getRecordSerializationContext();

  int generateTempStorageComponentId();
}
