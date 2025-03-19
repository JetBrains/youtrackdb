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
package com.jetbrains.youtrack.db.internal.core.storage;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.memory.DirectMemoryStorage;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrack.db.internal.core.util.Backupable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This is the gateway interface between the Database side and the storage. Provided implementations
 * are: Local, Remote and Memory.
 *
 * @see DirectMemoryStorage
 */
public interface Storage extends Backupable, StorageInfo {

  enum STATUS {
    CLOSED,
    OPEN,
    MIGRATION,
    CLOSING,
    @Deprecated
    OPENING,
  }

  void open(
      DatabaseSessionInternal remote, String iUserName, String iUserPassword,
      final ContextConfiguration contextConfiguration);

  void create(ContextConfiguration contextConfiguration) throws IOException;

  boolean exists();

  void reload(DatabaseSessionInternal database);

  void delete();

  void close(@Nullable DatabaseSessionInternal session);

  void close(@Nullable DatabaseSessionInternal database, boolean iForce);

  boolean isClosed(DatabaseSessionInternal database);

  // CRUD OPERATIONS
  @Nonnull
  ReadRecordResult readRecord(
      DatabaseSessionInternal session, RecordId iRid, boolean fetchPreviousRid,
      boolean fetchNextRid);

  boolean recordExists(DatabaseSessionInternal session, RID rid);

  RecordMetadata getRecordMetadata(DatabaseSessionInternal session, final RID rid);

  // TX OPERATIONS
  void commit(FrontendTransactionImpl iTx);

  Set<String> getClusterNames();

  Collection<? extends StorageCluster> getClusterInstances();

  /**
   * Add a new cluster into the storage.
   *
   * @param iClusterName name of the cluster
   */
  int addCluster(DatabaseSessionInternal database, String iClusterName, Object... iParameters);

  /**
   * Add a new cluster into the storage.
   *
   * @param iClusterName name of the cluster
   * @param iRequestedId requested id of the cluster
   */
  int addCluster(DatabaseSessionInternal database, String iClusterName, int iRequestedId);

  boolean dropCluster(DatabaseSessionInternal session, String iClusterName);

  String getClusterName(DatabaseSessionInternal database, final int clusterId);

  boolean setClusterAttribute(final int id, StorageCluster.ATTRIBUTES attribute, Object value);

  /**
   * Drops a cluster.
   *
   * @param database
   * @param iId      id of the cluster to delete
   * @return true if has been removed, otherwise false
   */
  boolean dropCluster(DatabaseSessionInternal database, int iId);

  String getClusterNameById(final int clusterId);

  long getClusterRecordsSizeById(final int clusterId);

  long getClusterRecordsSizeByName(final String clusterName);

  String getClusterRecordConflictStrategy(final int clusterId);

  boolean isSystemCluster(final int clusterId);

  long count(DatabaseSessionInternal session, int iClusterId);

  long count(DatabaseSessionInternal session, int iClusterId, boolean countTombstones);

  long count(DatabaseSessionInternal session, int[] iClusterIds);

  long count(DatabaseSessionInternal session, int[] iClusterIds, boolean countTombstones);

  /**
   * Returns the size of the database.
   */
  long getSize(DatabaseSessionInternal session);

  /**
   * Returns the total number of records.
   */
  long countRecords(DatabaseSessionInternal session);

  int getClusterIdByName(String iClusterName);

  String getPhysicalClusterNameById(int iClusterId);

  boolean checkForRecordValidity(PhysicalPosition ppos);

  String getName();

  long getVersion();

  /**
   * @return Version of product release under which storage was created.
   */
  String getCreatedAtVersion();

  void synch();

  /**
   * Execute the command request and return the result back.
   */
  Object command(DatabaseSessionInternal db, CommandRequestText iCommand);

  PhysicalPosition[] higherPhysicalPositions(DatabaseSessionInternal session, int clusterId,
      PhysicalPosition physicalPosition, int limit);

  PhysicalPosition[] lowerPhysicalPositions(DatabaseSessionInternal session, int clusterId,
      PhysicalPosition physicalPosition, int limit);

  PhysicalPosition[] ceilingPhysicalPositions(DatabaseSessionInternal session, int clusterId,
      PhysicalPosition physicalPosition, int limit);

  PhysicalPosition[] floorPhysicalPositions(DatabaseSessionInternal session, int clusterId,
      PhysicalPosition physicalPosition, int limit);

  /**
   * Returns the current storage's status
   */
  STATUS getStatus();

  /**
   * Returns the storage's type.
   */
  String getType();

  Storage getUnderlying();

  boolean isRemote();

  boolean isAssigningClusterIds();

  BTreeCollectionManager getSBtreeCollectionManager();

  CurrentStorageComponentsFactory getComponentsFactory();

  RecordConflictStrategy getRecordConflictStrategy();

  void setConflictStrategy(RecordConflictStrategy iResolver);

  /**
   * @return Backup file name
   */
  String incrementalBackup(DatabaseSessionInternal session, String backupDirectory,
      CallableFunction<Void, Void> started)
      throws UnsupportedOperationException;

  void fullIncrementalBackup(OutputStream stream) throws UnsupportedOperationException;

  void restoreFromIncrementalBackup(DatabaseSessionInternal session, String filePath);

  void restoreFullIncrementalBackup(DatabaseSessionInternal session, InputStream stream)
      throws UnsupportedOperationException;

  /**
   * This method is called in {@link YouTrackDBEnginesManager#shutdown()} method. For most of the
   * storages it means that storage will be merely closed, but sometimes additional operations are
   * need to be taken in account.
   */
  void shutdown();

  void setSchemaRecordId(String schemaRecordId);

  void setDateFormat(String dateFormat);

  void setTimeZone(TimeZone timeZoneValue);

  void setLocaleLanguage(String locale);

  void setCharset(String charset);

  void setIndexMgrRecordId(String indexMgrRecordId);

  void setDateTimeFormat(String dateTimeFormat);

  void setLocaleCountry(String localeCountry);

  void setClusterSelection(String clusterSelection);

  void setMinimumClusters(int minimumClusters);

  void setValidation(boolean validation);

  void removeProperty(String property);

  void setProperty(String property, String value);

  void setRecordSerializer(String recordSerializer, int version);

  void clearProperties();

  int[] getClustersIds(Set<String> filterClusters);

  default boolean isIcrementalBackupRunning() {
    return false;
  }

  YouTrackDBInternal getContext();

  LiveQueryMonitor live(DatabasePoolInternal sessionPool, String query,
      LiveQueryResultListener listener,
      Map<String, ?> args);

  LiveQueryMonitor live(DatabasePoolInternal sessionPool, String query,
      LiveQueryResultListener listener,
      Object... args);
}
