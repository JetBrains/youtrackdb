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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.StorageCollection.ATTRIBUTES;
import com.jetbrains.youtrack.db.internal.core.storage.memory.DirectMemoryStorage;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.AbsoluteChange;
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

  Set<String> getCollectionNames();

  Collection<? extends StorageCollection> getCollectionInstances();

  /**
   * Add a new collection into the storage.
   *
   * @param iCollectionName name of the collection
   */
  int addCollection(DatabaseSessionInternal database, String iCollectionName,
      Object... iParameters);

  int getAbsoluteLinkBagCounter(RID ownerId, String fieldName, RID key);

  /**
   * Add a new collection into the storage.
   *
   * @param iCollectionName name of the collection
   * @param iRequestedId    requested id of the collection
   */
  int addCollection(DatabaseSessionInternal database, String iCollectionName, int iRequestedId);

  boolean dropCollection(DatabaseSessionInternal session, String iCollectionName);

  String getCollectionName(DatabaseSessionInternal database, final int collectionId);

  void setCollectionAttribute(final int id, ATTRIBUTES attribute,
      Object value);

  /**
   * Drops a collection.
   *
   * @param iId      id of the collection to delete
   * @return true if has been removed, otherwise false
   */
  boolean dropCollection(DatabaseSessionInternal database, int iId);

  String getCollectionNameById(final int collectionId);

  long getCollectionRecordsSizeById(final int collectionId);

  long getCollectionRecordsSizeByName(final String collectionName);

  String getCollectionRecordConflictStrategy(final int collectionId);

  boolean isSystemCollection(final int collectionId);

  long count(DatabaseSessionInternal session, int iCollectionId);

  long count(DatabaseSessionInternal session, int iCollectionId, boolean countTombstones);

  long count(DatabaseSessionInternal session, int[] iCollectionIds);

  long count(DatabaseSessionInternal session, int[] iCollectionIds, boolean countTombstones);

  /**
   * Returns the size of the database.
   */
  long getSize(DatabaseSessionInternal session);

  AbsoluteChange getLinkBagCounter(DatabaseSessionInternal session, RecordId identity,
      String fieldName, RID rid);

  /**
   * Returns the total number of records.
   */
  long countRecords(DatabaseSessionInternal session);

  int getCollectionIdByName(String iCollectionName);

  String getPhysicalCollectionNameById(int iCollectionId);

  boolean checkForRecordValidity(PhysicalPosition ppos);

  String getName();

  long getVersion();

  /**
   * @return Version of product release under which storage was created.
   */
  String getCreatedAtVersion();

  void synch();

  PhysicalPosition[] higherPhysicalPositions(DatabaseSessionInternal session, int collectionId,
      PhysicalPosition physicalPosition, int limit);

  PhysicalPosition[] lowerPhysicalPositions(DatabaseSessionInternal session, int collectionId,
      PhysicalPosition physicalPosition, int limit);

  PhysicalPosition[] ceilingPhysicalPositions(DatabaseSessionInternal session, int collectionId,
      PhysicalPosition physicalPosition, int limit);

  PhysicalPosition[] floorPhysicalPositions(DatabaseSessionInternal session, int collectionId,
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

  boolean isAssigningCollectionIds();

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

  void setCollectionSelection(String collectionSelection);

  void setMinimumCollections(int minimumCollections);

  void setValidation(boolean validation);

  void removeProperty(String property);

  void setProperty(String property, String value);

  void setRecordSerializer(String recordSerializer, int version);

  void clearProperties();

  int[] getCollectionsIds(Set<String> filterCollections);

  default boolean isIncrementalBackupRunning() {
    return false;
  }

  YouTrackDBInternalEmbedded getContext();

  LiveQueryMonitor live(DatabasePoolInternal<DatabaseSession> sessionPool, String query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener,
      Map<String, ?> args);

  LiveQueryMonitor live(DatabasePoolInternal<DatabaseSession> sessionPool, String query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener,
      Object... args);
}
