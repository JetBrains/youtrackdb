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
package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.List;
import javax.annotation.Nonnull;

public interface FrontendTransaction {

  enum TXSTATUS {
    INVALID,
    BEGUN,
    COMMITTING,
    ROLLBACKING,
    COMPLETED,
    ROLLED_BACK
  }

  int begin();

  void commit();

  void commit(boolean force);

  void rollback();

  void rollback(boolean force, int commitLevelDiff);

  DatabaseSession getDatabaseSession();

  @Deprecated
  void clearRecordEntries();

  @Nonnull
  DBRecord loadRecord(RID rid) throws RecordNotFoundException;

  boolean exists(RID rid);

  TXSTATUS getStatus();

  @Deprecated
  Iterable<? extends RecordOperation> getCurrentRecordEntries();

  Iterable<? extends RecordOperation> getRecordOperations();

  List<RecordOperation> getNewRecordEntriesByClass(SchemaClass iClass, boolean iPolymorphic);

  List<RecordOperation> getNewRecordEntriesByClusterIds(int[] iIds);

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
  void updateIdentityAfterCommit(final RecordId oldRid, final RecordId newRid);

  int amountOfNestedTxs();

  int getEntryCount();

  /**
   * @return {@code true} if this transaction is active, {@code false} otherwise.
   */
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
   * @return the resolved record, or {@code null} if no record is found, or
   * {@link FrontendTransactionAbstract#DELETED_RECORD} if the record was deleted in this
   * transaction.
   */
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
      IndexInternal index,
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
  FrontendTransactionIndexChanges getIndexChanges(String indexName);

  /**
   * Does the same thing as {@link #getIndexChanges(String)}, but handles remote storages in a
   * special way.
   *
   * @param indexName the index name.
   * @return the index changes in question or {@code null} if index is not found or storage is
   * remote.
   */
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
   * @return this transaction ID as seen by the client of this transaction.
   */
  default long getClientTransactionId() {
    return getId();
  }

  long getId();


  void addRecordOperation(RecordAbstract record, byte status);

  default void preProcessRecordsAndExecuteCallCallbacks() {
  }
}
