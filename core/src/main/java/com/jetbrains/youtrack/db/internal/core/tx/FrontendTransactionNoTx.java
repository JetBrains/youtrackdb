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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.NoTxRecordReadException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.LoadRecordResult;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * No operation transaction.
 */
public class FrontendTransactionNoTx extends FrontendTransactionAbstract {

  private static final String NON_TX_WARNING_READ_MESSAGE =
      "Read operation performed in no tx mode. "
          + "Such behavior can lead to inconsistent state of the database. "
          + "Please consider using transaction or set "
          + GlobalConfiguration.NON_TX_READS_WARNING_MODE.getKey()
          + " configuration parameter to "
          + NonTxReadMode.SILENT.name()
          + " to avoid this warning, or to "
          + NonTxReadMode.EXCEPTION.name()
          + " to throw an exception for such cases.";
  private static final String NON_TX_EXCEPTION_READ_MESSAGE =
      "Read operation performed in no tx mode. "
          + "Such behavior can lead to inconsistent state of the database."
          + " Please consider using transaction or set "
          + GlobalConfiguration.NON_TX_READS_WARNING_MODE.getKey()
          + " configuration parameter to "
          + NonTxReadMode.SILENT.name()
          + " to avoid this warning, or to "
          + NonTxReadMode.WARN.name()
          + " to show a warning for such cases.";

  private final NonTxReadMode nonTxReadMode;

  public FrontendTransactionNoTx(final DatabaseSessionInternal database) {
    super(database);

    this.nonTxReadMode = database.getNonTxReadMode();
    assert this.nonTxReadMode != null;
  }

  public int begin() {
    throw new UnsupportedOperationException("Begin is not supported in no tx mode");
  }

  public void commit() {
    throw new UnsupportedOperationException("Commit is not supported in no tx mode");
  }

  @Override
  public int getEntryCount() {
    return 0;
  }

  @Override
  public void commit(boolean force) {
    throw new UnsupportedOperationException("Commit is not supported in no tx mode");
  }

  public void rollback() {
    throw new UnsupportedOperationException("Rollback is not supported in no tx mode");
  }

  public @Nonnull LoadRecordResult loadRecord(final RID rid) {
    checkNonTXReads();
    if (rid.isNew()) {
      throw new RecordNotFoundException(session, rid);
    }

    return session.executeReadRecord((RecordId) rid, false, false, true);
  }

  private void checkNonTXReads() {
    if (nonTxReadMode == NonTxReadMode.WARN) {
      LogManager.instance().warn(this, NON_TX_WARNING_READ_MESSAGE);
    } else if (nonTxReadMode == NonTxReadMode.EXCEPTION) {
      throw new NoTxRecordReadException(session.getDatabaseName(), NON_TX_EXCEPTION_READ_MESSAGE);
    }
  }

  @Override
  public boolean exists(RID rid) {
    checkNonTXReads();
    if (rid.isNew()) {
      return false;
    }

    return session.executeExists(rid);
  }

  /**
   * Deletes the record.
   */
  public void deleteRecord(final RecordAbstract iRecord) {
    throw new DatabaseException(session, "Cannot delete record in no tx mode");
  }

  public Collection<RecordOperation> getCurrentRecordEntries() {
    return Collections.emptyList();
  }

  public Collection<RecordOperation> getRecordOperations() {
    return Collections.emptyList();
  }

  public List<RecordOperation> getNewRecordEntriesByClass(
      final SchemaClass iClass, final boolean iPolymorphic) {
    return Collections.emptyList();
  }

  public List<RecordOperation> getNewRecordEntriesByClusterIds(final int[] iIds) {
    return Collections.emptyList();
  }

  public void clearRecordEntries() {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  public RecordAbstract getRecord(final RID rid) {
    checkNonTXReads();

    return null;
  }

  public RecordOperation getRecordEntry(final RID rid) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public void setCustomData(String iName, Object iValue) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public Object getCustomData(String iName) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  public void addIndexEntry(
      final IndexInternal delegate,
      final String indexName,
      final OPERATION status,
      final Object key,
      final Identifiable value) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  public void clearIndexEntries() {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public void close() {
  }

  public FrontendTransactionIndexChanges getIndexChanges(final String iName) {
    return null;
  }

  public long getId() {
    return 0;
  }

  public List<String> getInvolvedIndexes() {
    return Collections.emptyList();
  }

  public void updateIdentityAfterCommit(RecordId oldRid, RecordId newRid) {
    throw new UnsupportedOperationException("Operation not supported in no tx mode");
  }

  @Override
  public int amountOfNestedTxs() {
    return 0;
  }

  @Override
  public void rollback(boolean force, int commitLevelDiff) {
    throw new UnsupportedOperationException("Rollback is not supported in no tx mode");
  }

  @Override
  public void addRecordOperation(RecordAbstract record, byte status) {
    throw new UnsupportedOperationException("Can not modify record outside transaction");
  }

  @Nullable
  @Override
  public RecordId getFirstRid(int clusterId) {
    checkNonTXReads();
    return null;
  }

  @Nullable
  @Override
  public RecordId getLastRid(int clusterId) {
    checkNonTXReads();
    return null;
  }

  @Nullable
  @Override
  public RecordId getNextRidInCluster(@Nonnull RecordId rid) {
    checkNonTXReads();
    return null;
  }

  @Nullable
  @Override
  public RecordId getPreviousRidInCluster(@Nonnull RecordId rid) {
    checkNonTXReads();
    return null;
  }

  @Override
  public boolean isDeletedInTx(@Nonnull RID rid) {
    return false;
  }

  @Override
  public FrontendTransactionIndexChanges getIndexChangesInternal(String indexName) {
    return null;
  }

  @Override
  public void internalRollback() {
  }

  public enum NonTxReadMode {
    WARN,
    EXCEPTION,
    SILENT
  }
}
