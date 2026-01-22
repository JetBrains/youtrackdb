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
package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryStorage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.io.IOException;
import java.util.Collection;
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
public interface Storage extends StorageInfo {

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
  RawBuffer readRecord(RecordIdInternal iRid);

  boolean recordExists(DatabaseSessionInternal session, RID rid);

  RecordMetadata getRecordMetadata(DatabaseSessionInternal session, final RID rid);

  // TX OPERATIONS
  void commit(FrontendTransactionImpl iTx);

  @Override
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

  AbsoluteChange getLinkBagCounter(DatabaseSessionInternal session, RecordIdInternal identity,
      String fieldName, RID rid);

  /**
   * Returns the total number of records.
   */
  long countRecords(DatabaseSessionInternal session);

  @Override
  int getCollectionIdByName(String iCollectionName);

  @Override
  String getPhysicalCollectionNameById(int iCollectionId);

  @Override
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

  @Override
  boolean isAssigningCollectionIds();

  LinkCollectionsBTreeManager getLinkCollectionsBtreeCollectionManager();

  CurrentStorageComponentsFactory getComponentsFactory();

  @Override
  RecordConflictStrategy getRecordConflictStrategy();

  void setConflictStrategy(RecordConflictStrategy iResolver);

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

  YouTrackDBInternalEmbedded getContext();
}
