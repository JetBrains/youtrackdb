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
package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class DatabaseCompare extends DatabaseImpExpAbstract {

  private final DatabaseSessionEmbedded sessionOne;
  private final DatabaseSessionEmbedded sessionTwo;

  private boolean compareEntriesForAutomaticIndexes = false;
  private boolean autoDetectExportImportMap = true;

  private int differences = 0;
  private boolean compareIndexMetadata = false;

  private final Set<String> excludeIndexes = new HashSet<>();

  private int collectionDifference = 0;

  public DatabaseCompare(
      DatabaseSessionEmbedded sessionOne,
      DatabaseSessionEmbedded sessionTwo,
      final CommandOutputListener iListener) {
    super(null, null, iListener);

    listener.onMessage(
        "\nComparing two local databases:\n1) "
            + sessionOne.getURL()
            + "\n2) "
            + sessionTwo.getURL()
            + "\n");

    this.sessionOne = sessionOne;

    this.sessionTwo = sessionTwo;

    // exclude automatically generated collections
    excludeIndexes.add(DatabaseImport.EXPORT_IMPORT_INDEX_NAME);

    final Schema schemaTwo = sessionTwo.getMetadata().getSchema();
    final var cls = schemaTwo.getClass(DatabaseImport.EXPORT_IMPORT_CLASS_NAME);

    if (cls != null) {
      final var collectionIds = cls.getCollectionIds();
      collectionDifference = collectionIds.length;
    }
  }

  @Override
  public void run() {
    compare();
  }

  public boolean compare() {
    try {
      EntityHelper.RIDMapper ridMapper = null;
      if (autoDetectExportImportMap) {
        listener.onMessage(
            "\n"
                + "Auto discovery of mapping between RIDs of exported and imported records is"
                + " switched on, try to discover mapping data on disk.");
        if (sessionTwo.getMetadata().getSchema().getClass(DatabaseImport.EXPORT_IMPORT_CLASS_NAME)
            != null) {
          listener.onMessage("\nMapping data were found and will be loaded.");
          ridMapper =
              rid -> {
                if (rid == null) {
                  //noinspection ReturnOfNull
                  return null;
                }

                if (!rid.isPersistent()) {
                  //noinspection ReturnOfNull
                  return null;
                }

                try (final var resultSet =
                    sessionTwo.query(
                        "select value from "
                            + DatabaseImport.EXPORT_IMPORT_CLASS_NAME
                            + " where key = ?",
                        rid.toString())) {
                  if (resultSet.hasNext()) {
                    return new RecordId(resultSet.next().<String>getProperty("value"));
                  }
                  //noinspection ReturnOfNull
                  return null;
                }
              };
        } else {
          listener.onMessage("\nMapping data were not found.");
        }
      }

      compareCollections();
      compareRecords(ridMapper);

      compareSchema();
      compareIndexes(ridMapper);

      if (differences == 0) {
        listener.onMessage("\n\nDatabases match.");
        return true;
      } else {
        listener.onMessage("\n\nDatabases do not match. Found " + differences + " difference(s).");
        return false;
      }
    } catch (Exception e) {
      LogManager.instance()
          .error(
              this,
              "Error on comparing database '%s' against '%s'",
              e,
              sessionOne.getDatabaseName(),
              sessionTwo.getDatabaseName());
      throw new DatabaseExportException(
          "Error on comparing database '"
              + sessionOne.getDatabaseName()
              + "' against '"
              + sessionTwo.getDatabaseName()
              + "'",
          e);
    } finally {
      sessionOne.close();
      sessionTwo.close();
    }
  }

  private void compareSchema() {
    Schema schema1 = sessionOne.getMetadata().getImmutableSchemaSnapshot();
    Schema schema2 = sessionTwo.getMetadata().getImmutableSchemaSnapshot();

    var ok = true;
    for (var clazz : schema1.getClasses()) {
      var clazz2 = schema2.getClass(clazz.getName());

      if (clazz2 == null) {
        listener.onMessage(
            "\n- ERR: Class definition " + clazz.getName() + " for DB2 is null.");
        continue;
      }

      final var sc1 = clazz.getSuperClassesNames();
      final var sc2 = clazz2.getSuperClassesNames();

      if (!sc1.isEmpty() || !sc2.isEmpty()) {
        if (!new HashSet<>(sc1).containsAll(sc2) || !new HashSet<>(sc2).containsAll(sc1)) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " in DB1 is not equals in superclasses in DB2.");
          ok = false;
        }
      }

      if (!((SchemaClassInternal) clazz).getClassIndexes()
          .equals(((SchemaClassInternal) clazz2).getClassIndexes())) {
        listener.onMessage(
            "\n- ERR: Class definition for "
                + clazz.getName()
                + " in DB1 is not equals in indexes in DB2.");
        ok = false;
      }

      if (!Arrays.equals(clazz.getCollectionIds(), clazz2.getCollectionIds())) {
        listener.onMessage(
            "\n- ERR: Class definition for "
                + clazz.getName()
                + " in DB1 is not equals in collections in DB2.");
        ok = false;
      }
      if (!clazz.getCustomKeys().equals(clazz2.getCustomKeys())) {
        listener.onMessage(
            "\n- ERR: Class definition for "
                + clazz.getName()
                + " in DB1 is not equals in custom keys in DB2.");
        ok = false;
      }

      for (var prop1 : clazz.getDeclaredProperties()) {
        var prop2 = clazz2.getProperty(prop1.getName());
        if (prop2 == null) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " as missed property "
                  + prop1.getName()
                  + "in DB2.");
          ok = false;
          continue;
        }
        if (prop1.getType() != prop2.getType()) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " as not same type for property "
                  + prop1.getName()
                  + "in DB2. ");
          ok = false;
        }

        if (prop1.getLinkedType() != prop2.getLinkedType()) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " as not same linkedtype for property "
                  + prop1.getName()
                  + "in DB2.");
          ok = false;
        }

        if (prop1.getMin() != null) {
          if (!prop1.getMin().equals(prop2.getMin())) {
            listener.onMessage(
                "\n- ERR: Class definition for "
                    + clazz.getName()
                    + " as not same min for property "
                    + prop1.getName()
                    + "in DB2.");
            ok = false;
          }
        }
        if (prop1.getMax() != null) {
          if (!prop1.getMax().equals(prop2.getMax())) {
            listener.onMessage(
                "\n- ERR: Class definition for "
                    + clazz.getName()
                    + " as not same max for property "
                    + prop1.getName()
                    + "in DB2.");
            ok = false;
          }
        }

        if (prop1.getMax() != null) {
          if (!prop1.getMax().equals(prop2.getMax())) {
            listener.onMessage(
                "\n- ERR: Class definition for "
                    + clazz.getName()
                    + " as not same regexp for property "
                    + prop1.getName()
                    + "in DB2.");
            ok = false;
          }
        }

        if (prop1.getLinkedClass() != null) {
          if (!prop1.getLinkedClass().equals(prop2.getLinkedClass())) {
            listener.onMessage(
                "\n- ERR: Class definition for "
                    + clazz.getName()
                    + " as not same linked class for property "
                    + prop1.getName()
                    + "in DB2.");
            ok = false;
          }
        }

        if (prop1.getLinkedClass() != null) {
          if (!prop1.getCustomKeys().equals(prop2.getCustomKeys())) {
            listener.onMessage(
                "\n- ERR: Class definition for "
                    + clazz.getName()
                    + " as not same custom keys for property "
                    + prop1.getName()
                    + "in DB2.");
            ok = false;
          }
        }
        if (prop1.isMandatory() != prop2.isMandatory()) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " as not same mandatory flag for property "
                  + prop1.getName()
                  + "in DB2.");
          ok = false;
        }
        if (prop1.isNotNull() != prop2.isNotNull()) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " as not same nut null flag for property "
                  + prop1.getName()
                  + "in DB2.");
          ok = false;
        }
        if (prop1.isReadonly() != prop2.isReadonly()) {
          listener.onMessage(
              "\n- ERR: Class definition for "
                  + clazz.getName()
                  + " as not same readonly flag setting for property "
                  + prop1.getName()
                  + "in DB2.");
          ok = false;
        }
      }
      if (!ok) {
        ++differences;
        ok = true;
      }
    }
  }

  @SuppressWarnings({"ObjectAllocationInLoop"})
  private void compareIndexes(EntityHelper.RIDMapper ridMapper) {
    listener.onMessage("\nStarting index comparison:");

    var ok = true;

    final var indexManagerOne = sessionOne.getSharedContext().getIndexManager();
    final var indexManagerTwo = sessionTwo.getSharedContext().getIndexManager();

    final var indexesOne = indexManagerOne.getIndexes();
    var indexesSizeOne = indexesOne.size();

    var indexesSizeTwo = indexManagerTwo.getIndexes().size();

    if (indexManagerTwo.getIndex(DatabaseImport.EXPORT_IMPORT_INDEX_NAME) != null) {
      indexesSizeTwo--;
    }

    if (indexesSizeOne != indexesSizeTwo) {
      ok = false;
      listener.onMessage("\n- ERR: Amount of indexes are different.");
      listener.onMessage("\n--- DB1: " + indexesSizeOne);
      listener.onMessage("\n--- DB2: " + indexesSizeTwo);
      listener.onMessage("\n");
      ++differences;
    }

    for (var indexOne : indexesOne) {
      final var indexName = indexOne.getName();
      if (excludeIndexes.contains(indexName)) {
        continue;
      }

      final var indexTwo = indexManagerTwo.getIndex(indexOne.getName());
      if (indexTwo == null) {
        ok = false;
        listener.onMessage("\n- ERR: Index " + indexOne.getName() + " is absent in DB2.");
        ++differences;
        continue;
      }

      if (!indexOne.getType().equals(indexTwo.getType())) {
        ok = false;
        listener.onMessage(
            "\n- ERR: Index types for index " + indexOne.getName() + " are different.");
        listener.onMessage("\n--- DB1: " + indexOne.getType());
        listener.onMessage("\n--- DB2: " + indexTwo.getType());
        listener.onMessage("\n");
        ++differences;
        continue;
      }

      if (!indexOne.getCollections().equals(indexTwo.getCollections())) {
        ok = false;
        listener.onMessage(
            "\n- ERR: Collections to index for index " + indexOne.getName() + " are different.");
        listener.onMessage("\n--- DB1: " + indexOne.getCollections());
        listener.onMessage("\n--- DB2: " + indexTwo.getCollections());
        listener.onMessage("\n");
        ++differences;
        continue;
      }

      if (indexOne.getDefinition() == null && indexTwo.getDefinition() != null) {
        // THIS IS NORMAL SINCE 3.0 DUE OF REMOVING OF INDEX WITHOUT THE DEFINITION,  THE IMPORTER
        // WILL CREATE THE DEFINITION
        listener.onMessage(
            "\n- WARN: Index definition for index " + indexOne.getName() + " for DB2 is not null.");
        continue;
      } else {
        if (indexOne.getDefinition() != null && indexTwo.getDefinition() == null) {
          ok = false;
          listener.onMessage(
              "\n- ERR: Index definition for index " + indexOne.getName() + " for DB2 is null.");
          ++differences;
          continue;
        } else {
          if (indexOne.getDefinition() != null
              && !indexOne.getDefinition().equals(indexTwo.getDefinition())) {
            ok = false;
            listener.onMessage(
                "\n- ERR: Index definitions for index " + indexOne.getName() + " are different.");
            listener.onMessage("\n--- DB1: " + indexOne.getDefinition());
            listener.onMessage("\n--- DB2: " + indexTwo.getDefinition());
            listener.onMessage("\n");
            ++differences;
            continue;
          }
        }
      }

      final var indexOneSize = indexOne.size(sessionOne);
      final var indexTwoSize = indexTwo.size(sessionTwo);

      if (indexOneSize != indexTwoSize) {
        ok = false;
        listener.onMessage(
            "\n- ERR: Amount of entries for index " + indexOne.getName() + " are different.");
        listener.onMessage("\n--- DB1: " + indexOneSize);
        listener.onMessage("\n--- DB2: " + indexTwoSize);
        listener.onMessage("\n");
        ++differences;
      }

      if (compareIndexMetadata) {
        final var metadataOne = indexOne.getMetadata();
        final var metadataTwo = indexTwo.getMetadata();

        if (metadataOne == null && metadataTwo != null) {
          ok = false;
          listener.onMessage(
              "\n- ERR: Metadata for index "
                  + indexOne.getName()
                  + " for DB1 is null but for DB2 is not.");
          listener.onMessage("\n");
          ++differences;
        } else {
          if (metadataOne != null && metadataTwo == null) {
            ok = false;
            listener.onMessage(
                "\n- ERR: Metadata for index "
                    + indexOne.getName()
                    + " for DB1 is not null but for DB2 is null.");
            listener.onMessage("\n");
            ++differences;
          } else {
            if (!Objects.equals(metadataOne, metadataTwo)) {
              ok = false;
              listener.onMessage(
                  "\n- ERR: Metadata for index "
                      + indexOne.getName()
                      + " for DB1 and for DB2 are different.");

              listener.onMessage("\n--- M1: " + metadataOne);
              listener.onMessage("\n--- M2: " + metadataTwo);

              listener.onMessage("\n");
              ++differences;
            }
          }
        }
      }

      if (((compareEntriesForAutomaticIndexes && !indexOne.getType().equals("DICTIONARY"))
          || !indexOne.isAutomatic())) {

        try (final var keyStream = indexOne.keyStream()) {
          final var indexKeyIteratorOne = keyStream.iterator();
          while (indexKeyIteratorOne.hasNext()) {
            final var indexKey = indexKeyIteratorOne.next();
            try (var indexOneStream = indexOne.getRids(sessionOne, indexKey)) {
              try (var indexTwoValue = indexTwo.getRids(sessionTwo, indexKey)) {
                differences +=
                    compareIndexStreams(
                        indexKey, indexOneStream, indexTwoValue, ridMapper, listener);
              }
            }
            ok = ok && differences > 0;
          }
        }
      }
    }

    if (ok) {
      listener.onMessage("OK");
    }
  }

  private static int compareIndexStreams(
      final Object indexKey,
      final Stream<RID> streamOne,
      final Stream<RID> streamTwo,
      final EntityHelper.RIDMapper ridMapper,
      final CommandOutputListener listener) {
    final Set<RID> streamTwoSet = new HashSet<>();

    final var streamOneIterator = streamOne.iterator();
    final var streamTwoIterator = streamTwo.iterator();

    var differences = 0;
    while (streamOneIterator.hasNext()) {
      RID rid;
      if (ridMapper == null) {
        rid = streamOneIterator.next();
      } else {
        final var streamOneRid = streamOneIterator.next();
        rid = ridMapper.map(streamOneRid);
        if (rid == null) {
          rid = streamOneRid;
        }
      }

      if (!streamTwoSet.remove(rid)) {
        if (!streamTwoIterator.hasNext()) {
          listener.onMessage(
              "\r\nEntry " + indexKey + ":" + rid + " is present in DB1 but absent in DB2");
          differences++;
        } else {
          var found = false;
          while (streamTwoIterator.hasNext()) {
            final var streamRid = streamTwoIterator.next();
            if (streamRid.equals(rid)) {
              found = true;
              break;
            }

            streamTwoSet.add(streamRid);
          }

          if (!found) {
            listener.onMessage(
                "\r\nEntry " + indexKey + ":" + rid + " is present in DB1 but absent in DB2");
          }
        }
      }
    }

    while (streamTwoIterator.hasNext()) {
      final var rid = streamTwoIterator.next();
      listener.onMessage(
          "\r\nEntry " + indexKey + ":" + rid + " is present in DB2 but absent in DB1");

      differences++;
    }

    for (final var rid : streamTwoSet) {
      listener.onMessage(
          "\r\nEntry " + indexKey + ":" + rid + " is present in DB2 but absent in DB1");

      differences++;
    }
    return differences;
  }

  @SuppressWarnings("ObjectAllocationInLoop")
  private void compareCollections() {
    listener.onMessage("\nStarting shallow comparison of collections:");

    listener.onMessage("\nChecking the number of collections...");

    var collectionNames1 = sessionOne.getCollectionNames();
    var collectionNames2 = sessionTwo.getCollectionNames();

    if (collectionNames1.size() != collectionNames2.size() - collectionDifference) {
      listener.onMessage(
          "ERR: collection sizes are different: "
              + collectionNames1.size()
              + " <-> "
              + collectionNames2.size());
      ++differences;
    }

    boolean ok;

    for (final var collectionName : collectionNames1) {
      // CHECK IF THE COLLECTION IS INCLUDED
      ok = true;
      final var collection1Id = sessionTwo.getCollectionIdByName(collectionName);
      listener.onMessage(
          "\n- Checking collection " + String.format("%-25s: ", "'" + collectionName + "'"));

      if (collection1Id == -1) {
        listener.onMessage(
            "ERR: collection name '"
                + collectionName
                + "' was not found on database "
                + sessionTwo.getDatabaseName());
        ++differences;
        ok = false;
      }

      final var collection2Id = sessionOne.getCollectionIdByName(collectionName);
      if (collection1Id != collection2Id) {
        listener.onMessage(
            "ERR: collection id is different for collection "
                + collectionName
                + ": "
                + collection2Id
                + " <-> "
                + collection1Id);
        ++differences;
        ok = false;
      }

      var countCollection1 = sessionOne.countCollectionElements(collection1Id);
      var countCollection2 = sessionTwo.countCollectionElements(collection2Id);

      if (countCollection1 != countCollection2) {
        listener.onMessage(
            "ERR: number of records different in collection '"
                + collectionName
                + "' (id="
                + collection1Id
                + "): "
                + countCollection1
                + " <-> "
                + countCollection2);
        ++differences;
        ok = false;
      }

      if (ok) {
        listener.onMessage("OK");
      }
    }

    listener.onMessage("\n\nShallow analysis done.");
  }

  @SuppressWarnings("ObjectAllocationInLoop")
  private void compareRecords(EntityHelper.RIDMapper ridMapper) {
    listener.onMessage(
        "\nStarting deep comparison record by record. This may take a few minutes. Wait please...");

    var collectionNames1 = sessionOne.getCollectionNames();

    for (final var collectionName : collectionNames1) {
      // CHECK IF THE COLLECTION IS INCLUDED
      final var collectionId1 = sessionOne.getCollectionIdByName(collectionName);
      final var rid1 = new RecordId(collectionId1);

      var physicalPositions = sessionOne.getStorage()
          .ceilingPhysicalPositions(sessionOne, collectionId1, new PhysicalPosition(0),
              Integer.MAX_VALUE);

      var configuration1 = sessionOne.getStorageInfo().getConfiguration();
      var configuration2 = sessionTwo.getStorageInfo().getConfiguration();

      var storageType1 = sessionOne.getStorage().getType();
      var storageType2 = sessionTwo.getStorage().getType();

      long recordsCounter = 0;
      while (physicalPositions.length > 0) {
        for (var physicalPosition : physicalPositions) {
          try {
            recordsCounter++;

            final var entity1 = new EntityImpl(sessionOne);
            final var entity2 = new EntityImpl(sessionTwo);

            final var position = physicalPosition.collectionPosition;
            rid1.setCollectionPosition(position);

            final RecordId rid2;
            if (ridMapper == null) {
              rid2 = rid1;
            } else {
              final var newRid = ridMapper.map(rid1);
              if (newRid == null) {
                rid2 = rid1;
              } else
              //noinspection ObjectAllocationInLoop
              {
                rid2 = new RecordId(newRid);
              }
            }

            if (skipRecord(
                rid1, rid2, configuration1, configuration2, storageType1, storageType2)) {
              continue;
            }

            final var buffer1 =
                sessionOne.getStorage().readRecord(sessionOne, rid1, false, false).buffer();
            final var buffer2 = sessionTwo.getStorage()
                .readRecord(sessionTwo, rid2, false, false).buffer();

            if (buffer1.recordType != buffer2.recordType) {
              listener.onMessage(
                  "\n- ERR: RID="
                      + collectionId1
                      + ":"
                      + position
                      + " recordType is different: "
                      + (char) buffer1.recordType
                      + " <-> "
                      + (char) buffer2.recordType);
              ++differences;
            }

            //noinspection StatementWithEmptyBody
            if (buffer1.buffer == null && buffer2.buffer == null) {
              // Both null so both equals
            } else {
              if (buffer1.buffer == null) {
                listener.onMessage(
                    "\n- ERR: RID="
                        + collectionId1
                        + ":"
                        + position
                        + " content is different: null <-> "
                        + buffer2.buffer.length);
                ++differences;

              } else {
                if (buffer2.buffer == null) {
                  listener.onMessage(
                      "\n- ERR: RID="
                          + collectionId1
                          + ":"
                          + position
                          + " content is different: "
                          + buffer1.buffer.length
                          + " <-> null");
                  ++differences;

                } else {
                  if (EntityHelper.isEntity(buffer1.recordType)) {
                    // ENTITY: TRY TO INSTANTIATE AND COMPARE

                    final var rec1 = (RecordAbstract) entity1;
                    rec1.unsetDirty();
                    final var rec3 = (RecordAbstract) entity1;
                    rec3.fromStream(buffer1.buffer);

                    final var rec = (RecordAbstract) entity2;
                    rec.unsetDirty();
                    final var rec2 = (RecordAbstract) entity2;
                    rec2.fromStream(buffer2.buffer);

                    if (rid1.toString().equals(configuration1.getSchemaRecordId())
                        && rid1.toString().equals(configuration2.getSchemaRecordId())) {
                      convertSchemaDoc(entity1);
                      convertSchemaDoc(entity2);
                    }

                    if (!EntityHelper.hasSameContentOf(
                        entity1, sessionOne, entity2, sessionTwo, ridMapper)) {
                      listener.onMessage(
                          "\n- ERR: RID="
                              + collectionId1
                              + ":"
                              + position
                              + " entity content is different");
                      listener.onMessage("\n--- REC1: " + new String(buffer1.buffer));
                      listener.onMessage("\n--- REC2: " + new String(buffer2.buffer));
                      listener.onMessage("\n");
                      ++differences;
                    }
                  } else {
                    if (buffer1.buffer.length != buffer2.buffer.length) {
                      // CHECK IF THE TRIMMED SIZE IS THE SAME
                      @SuppressWarnings("ObjectAllocationInLoop") final var rec1 = new String(
                          buffer1.buffer).trim();
                      @SuppressWarnings("ObjectAllocationInLoop") final var rec2 = new String(
                          buffer2.buffer).trim();

                      if (rec1.length() != rec2.length()) {
                        listener.onMessage(
                            "\n- ERR: RID="
                                + collectionId1
                                + ":"
                                + position
                                + " content length is different: "
                                + buffer1.buffer.length
                                + " <-> "
                                + buffer2.buffer.length);

                        if (EntityHelper.isEntity(buffer2.recordType)) {
                          listener.onMessage("\n--- REC2: " + rec2);
                        }

                        listener.onMessage("\n");

                        ++differences;
                      }
                    } else {
                      // CHECK BYTE PER BYTE
                      for (var b = 0; b < buffer1.buffer.length; ++b) {
                        if (buffer1.buffer[b] != buffer2.buffer[b]) {
                          listener.onMessage(
                              "\n- ERR: RID="
                                  + collectionId1
                                  + ":"
                                  + position
                                  + " content is different at byte #"
                                  + b
                                  + ": "
                                  + buffer1.buffer[b]
                                  + " <-> "
                                  + buffer2.buffer[b]);
                          listener.onMessage("\n--- REC1: " + new String(buffer1.buffer));
                          listener.onMessage("\n--- REC2: " + new String(buffer2.buffer));
                          listener.onMessage("\n");
                          ++differences;
                          break;
                        }
                      }
                    }
                  }
                }
              }
            }
          } catch (RuntimeException e) {
            LogManager.instance()
                .error(this, "Error during data comparison of records with rid " + rid1, e);
            throw e;
          }
        }
        final var curPosition = physicalPositions;
        physicalPositions = sessionOne.getStorage()
            .higherPhysicalPositions(sessionOne, collectionId1,
                curPosition[curPosition.length - 1], Integer.MAX_VALUE);
        if (recordsCounter % 10000 == 0) {
          listener.onMessage(
              "\n"
                  + recordsCounter
                  + " records were processed for collection "
                  + collectionName
                  + " ...");
        }
      }

      listener.onMessage(
          "\nCollection comparison was finished, "
              + recordsCounter
              + " records were processed for collection "
              + collectionName
              + " ...");
    }
  }

  private static boolean skipRecord(
      RecordId rid1,
      RecordId rid2,
      StorageConfiguration configuration1,
      StorageConfiguration configuration2,
      String storageType1,
      String storageType2) {
    if (rid1.getCollectionId() == 0) {
      return true;
    }
    if (rid1.equals(new RecordId(configuration1.getIndexMgrRecordId()))
        || rid2.equals(new RecordId(configuration2.getIndexMgrRecordId()))) {
      return true;
    }
    if (rid1.equals(new RecordId(configuration1.getSchemaRecordId()))
        || rid2.equals(new RecordId(configuration2.getSchemaRecordId()))) {
      return true;
    }
    if ((rid1.getCollectionId() == 0 && rid1.getCollectionPosition() == 0)
        || (rid2.getCollectionId() == 0 && rid2.getCollectionPosition() == 0)) {
      // Skip the compare of raw structure if the storage type are different, due the fact
      // that are different by definition.
      return !storageType1.equals(storageType2);
    }
    return false;
  }

  public void setCompareIndexMetadata(boolean compareIndexMetadata) {
    this.compareIndexMetadata = compareIndexMetadata;
  }

  public void setCompareEntriesForAutomaticIndexes(boolean compareEntriesForAutomaticIndexes) {
    this.compareEntriesForAutomaticIndexes = compareEntriesForAutomaticIndexes;
  }

  public void setAutoDetectExportImportMap(boolean autoDetectExportImportMap) {
    this.autoDetectExportImportMap = autoDetectExportImportMap;
  }

  private static void convertSchemaDoc(final EntityImpl entity) {
    if (entity.getProperty("classes") != null) {
      entity.setProperty("classes", entity.getProperty("classes"), PropertyType.EMBEDDEDSET);
      for (var classDoc : entity.<Set<EntityImpl>>getProperty("classes")) {
        classDoc.setProperty("properties", classDoc.getProperty("properties"),
            PropertyType.EMBEDDEDSET);
      }
    }
  }
}
