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

package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassSnapshot;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles indexing when records change.
 */
public class ClassIndexManager {

  public static void checkIndexesAfterCreate(
      EntityImpl entity, FrontendTransaction transaction) {
    processIndexOnCreate(transaction, entity);
  }

  public static void reIndex(FrontendTransaction transaction, EntityImpl entity,
      Index index) {
    addIndexEntry(transaction, entity, entity.getIdentity(), index);
  }

  private static void processIndexOnCreate(FrontendTransaction transaction,
      EntityImpl entity) {
    ImmutableSchemaClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass();
    }
    if (cls != null) {
      final Collection<Index> indexes = cls.getRawIndexes();
      addIndexesEntries(transaction, entity, indexes);
    }
  }

  public static void checkIndexesAfterUpdate(
      EntityImpl entity, FrontendTransaction transaction) {
    processIndexOnUpdate(transaction, entity);
  }

  private static void processIndexOnUpdate(FrontendTransaction transaction,
      EntityImpl entity) {
    ImmutableSchemaClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass();
    }
    if (cls == null) {
      return;
    }

    final Collection<Index> indexes = cls.getRawIndexes();
    if (!indexes.isEmpty()) {
      var dirtyProperties = entity.getDirtyPropertiesBetweenCallbacksInternal(false, false);
      var dirtyFields = new HashSet<>(dirtyProperties);

      if (!dirtyFields.isEmpty()) {
        for (final var index : indexes) {
          processIndexUpdate(transaction, entity, dirtyFields, index);
        }
      }
    }
  }

  public static void checkIndexesAfterDelete(
      EntityImpl entity, FrontendTransaction transaction) {
    processIndexOnDelete(transaction, entity);
  }

  private static void processCompositeIndexUpdate(
      FrontendTransaction transaction,
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl record) {
    final var indexDefinition =
        (CompositeIndexDefinition) index.getDefinition();

    final var indexProperties = indexDefinition.getProperties();
    for (final var indexField : indexProperties) {
      if (dirtyFields.contains(indexField)) {
        var multiValueIndex = -1;

        final List<Object> origValues = new ArrayList<>(indexProperties.size());
        for (var i = 0; i < indexProperties.size(); i++) {
          final var indexProperty = indexProperties.get(i);

          if (!indexDefinition.isMultivaluePropertyIndex(i)) {
            if (dirtyFields.contains(indexProperty)) {
              origValues.add(record.getOriginalValue(indexProperty));
            } else {
              origValues.add(record.getProperty(indexProperty));
            }
          } else {
            final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine = record.getCollectionTimeLine(
                indexProperty);
            if (multiValueChangeTimeLine == null) {
              if (dirtyFields.contains(indexProperty)) {
                origValues.add(
                    record.getOriginalValue(indexProperty));
              } else {
                origValues.add(
                    record.getPropertyInternal(indexProperty));
              }
            } else {
              if (dirtyFields.size() == 1 && indexDefinition.isNullValuesIgnored()) {
                multiValueIndex = i;
              } else {
                @SuppressWarnings("rawtypes") final TrackedMultiValue fieldValue =
                    record.getProperty(indexProperty);
                @SuppressWarnings("unchecked") final var restoredMultiValue =
                    fieldValue.returnOriginalState(transaction,
                        multiValueChangeTimeLine.getMultiValueChangeEvents());

                origValues.add(restoredMultiValue);
              }
            }
          }
        }

        if (!indexDefinition.hasMultiValueProperties()) {
          final var origValue = indexDefinition.createValue(transaction, origValues);
          final var newValue = indexDefinition.getDocumentValueToIndex(transaction, record);

          if (!indexDefinition.isNullValuesIgnored() || origValue != null) {
            addRemove(transaction, index, origValue, record);
          }

          if (!indexDefinition.isNullValuesIgnored() || newValue != null) {
            addPut(transaction, index, newValue, record.getIdentity());
          }
        } else {
          if (multiValueIndex == -1) {
            final var origValue = indexDefinition.createValue(transaction, origValues);
            final var newValue = indexDefinition.getDocumentValueToIndex(transaction, record);

            processIndexUpdateFieldAssignment(transaction, index, record, origValue, newValue);
          } else {
            final var keysToAdd = new Object2IntOpenHashMap<CompositeKey>();
            keysToAdd.defaultReturnValue(-1);
            final var keysToRemove =
                new Object2IntOpenHashMap<CompositeKey>();
            keysToRemove.defaultReturnValue(-1);

            var indexProperty = indexProperties.get(multiValueIndex);
            final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
                record.getCollectionTimeLine(indexProperty);
            for (var changeEvent : multiValueChangeTimeLine.getMultiValueChangeEvents()) {
              indexDefinition.processChangeEvent(
                  transaction, changeEvent, keysToAdd, keysToRemove,
                  multiValueIndex,
                  origValues.toArray());
            }

            for (final Object keyToRemove : keysToRemove.keySet()) {
              addRemove(transaction, index, keyToRemove, record);
            }

            for (final Object keyToAdd : keysToAdd.keySet()) {
              addPut(transaction, index, keyToAdd, record.getIdentity());
            }
          }
        }
        return;
      }
    }
  }

  private static void processSingleIndexUpdate(
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl iRecord,
      FrontendTransaction transaction) {
    final var indexDefinition = index.getDefinition();
    final var indexFields = indexDefinition.getProperties();

    if (indexFields.isEmpty()) {
      return;
    }

    final var indexField = indexFields.getFirst();
    if (!dirtyFields.contains(indexField)) {
      return;
    }

    final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
        iRecord.getCollectionTimeLine(indexField);
    if (multiValueChangeTimeLine != null) {
      final var indexDefinitionMultiValue = (IndexDefinitionMultiValue) indexDefinition;
      final var keysToAdd = new Object2IntOpenHashMap<>();
      keysToAdd.defaultReturnValue(-1);
      final var keysToRemove = new Object2IntOpenHashMap<>();
      keysToRemove.defaultReturnValue(-1);

      for (var changeEvent :
          multiValueChangeTimeLine.getMultiValueChangeEvents()) {
        indexDefinitionMultiValue.processChangeEvent(transaction, changeEvent, keysToAdd,
            keysToRemove);
      }

      for (final var keyToRemove : keysToRemove.keySet()) {
        addRemove(transaction, index, keyToRemove, iRecord);
      }

      for (final var keyToAdd : keysToAdd.keySet()) {
        addPut(transaction, index, keyToAdd, iRecord.getIdentity());
      }

    } else {
      final var origValue =
          indexDefinition.createValue(transaction, iRecord.getOriginalValue(indexField));
      final var newValue = indexDefinition.getDocumentValueToIndex(transaction, iRecord);

      processIndexUpdateFieldAssignment(transaction, index, iRecord, origValue, newValue);
    }
  }

  private static void processIndexUpdateFieldAssignment(
      FrontendTransaction transaction, Index index, EntityImpl iRecord, final Object origValue,
      final Object newValue) {
    final var indexDefinition = index.getDefinition();
    if ((origValue instanceof Collection) && (newValue instanceof Collection)) {
      final Set<Object> valuesToRemove = new HashSet<>((Collection<?>) origValue);
      final Set<Object> valuesToAdd = new HashSet<>((Collection<?>) newValue);

      valuesToRemove.removeAll((Collection<?>) newValue);
      valuesToAdd.removeAll((Collection<?>) origValue);

      for (final var valueToRemove : valuesToRemove) {
        if (!indexDefinition.isNullValuesIgnored() || valueToRemove != null) {
          addRemove(transaction, index, valueToRemove, iRecord);
        }
      }

      for (final var valueToAdd : valuesToAdd) {
        if (!indexDefinition.isNullValuesIgnored() || valueToAdd != null) {
          addPut(transaction, index, valueToAdd, iRecord);
        }
      }
    } else {
      deleteIndexKey(transaction, index, iRecord, origValue);
      if (newValue instanceof Collection) {
        for (final var newValueItem : (Collection<?>) newValue) {
          addPut(transaction, index, newValueItem, iRecord.getIdentity());
        }
      } else if (!indexDefinition.isNullValuesIgnored() || newValue != null) {
        addPut(transaction, index, newValue, iRecord.getIdentity());
      }
    }
  }

  private static boolean processCompositeIndexDelete(
      FrontendTransaction transaction,
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl record) {
    final var indexDefinition =
        (CompositeIndexDefinition) index.getDefinition();

    final var indexProperties = indexDefinition.getProperties();
    for (final var indexProperty : indexProperties) {
      // REMOVE IT
      if (dirtyFields.contains(indexProperty)) {
        final List<Object> origValues = new ArrayList<>(indexProperties.size());

        var multiValueIndex = -1;
        for (var i = 0; i < indexProperties.size(); ++i) {
          final var property = indexProperties.get(i);
          if (!indexDefinition.isMultivaluePropertyIndex(i)) {
            if (dirtyFields.contains(property)) {
              origValues.add(record.getOriginalValue(property));
            } else {
              origValues.add(record.getProperty(property));
            }
          } else {
            var multiValueChangeTimeLine = record.getCollectionTimeLine(property);
            if (multiValueChangeTimeLine != null) {
              @SuppressWarnings("rawtypes") final TrackedMultiValue propertyValue = record.getProperty(
                  property);
              @SuppressWarnings("unchecked") final var restoredMultiValue =
                  propertyValue.returnOriginalState(transaction,
                      multiValueChangeTimeLine.getMultiValueChangeEvents());
              origValues.add(multiValueIndex, restoredMultiValue);
            } else if (dirtyFields.contains(property)) {
              origValues.add(multiValueIndex, record.getOriginalValue(property));
            } else {
              origValues.add(multiValueIndex, record.getProperty(property));
            }
          }
        }

        final var origValue = indexDefinition.createValue(transaction, origValues);
        deleteIndexKey(transaction, index, record, origValue);
        return true;
      }
    }
    return false;
  }

  private static void deleteIndexKey(
      FrontendTransaction transaction, final Index index, final EntityImpl iRecord,
      final Object origValue) {
    final var indexDefinition = index.getDefinition();
    if (origValue instanceof Collection) {
      for (final var valueItem : (Collection<?>) origValue) {
        if (!indexDefinition.isNullValuesIgnored() || valueItem != null) {
          addRemove(transaction, index, valueItem, iRecord);
        }
      }
    } else if (!indexDefinition.isNullValuesIgnored() || origValue != null) {
      addRemove(transaction, index, origValue, iRecord);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static boolean processSingleIndexDelete(
      FrontendTransaction transaction,
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl iRecord) {
    final var indexDefinition = index.getDefinition();

    final var indexFields = indexDefinition.getProperties();
    if (indexFields.isEmpty()) {
      return false;
    }

    final var indexField = indexFields.getFirst();
    if (dirtyFields.contains(indexField)) {
      final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
          iRecord.getCollectionTimeLine(indexField);

      final Object origValue;
      if (multiValueChangeTimeLine != null) {
        final TrackedMultiValue fieldValue = iRecord.getProperty(indexField);
        final var restoredMultiValue =
            fieldValue.returnOriginalState(transaction,
                multiValueChangeTimeLine.getMultiValueChangeEvents());
        origValue = indexDefinition.createValue(transaction, restoredMultiValue);
      } else {
        origValue = indexDefinition.createValue(transaction, iRecord.getOriginalValue(indexField));
      }
      deleteIndexKey(transaction, index, iRecord, origValue);
      return true;
    }
    return false;
  }

  public static void processIndexUpdate(
      FrontendTransaction transaction,
      EntityImpl entity,
      Set<String> dirtyFields,
      Index index) {
    if (index.getDefinition() instanceof CompositeIndexDefinition) {
      processCompositeIndexUpdate(transaction, index, dirtyFields, entity);
    } else {
      processSingleIndexUpdate(index, dirtyFields, entity, transaction);
    }
  }

  public static void addIndexesEntries(
      FrontendTransaction transaction, EntityImpl entity, final Collection<Index> indexes) {
    // STORE THE RECORD IF NEW, OTHERWISE ITS RID
    final Identifiable rid = entity.getIdentity();

    for (final var index : indexes) {
      addIndexEntry(transaction, entity, rid, index);
    }
  }

  private static void addIndexEntry(
      FrontendTransaction transaction, EntityImpl entity, Identifiable rid, Index index) {
    final var indexDefinition = index.getDefinition();
    final var key = indexDefinition.getDocumentValueToIndex(transaction, entity);
    if (key instanceof Collection) {
      for (final var keyItem : (Collection<?>) key) {
        if (!indexDefinition.isNullValuesIgnored() || keyItem != null) {
          addPut(transaction, index, keyItem, rid);
        }
      }
    } else if (!indexDefinition.isNullValuesIgnored() || key != null) {
      addPut(transaction, index, key, rid);
    }
  }

  public static void processIndexOnDelete(FrontendTransaction transaction,
      EntityImpl entity) {
    SchemaClassSnapshot cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass(transaction.getDatabaseSession());
    }
    if (cls == null) {
      return;
    }

    final Collection<Index> indexes = new ArrayList<>(cls.getRawIndexes());

    if (!indexes.isEmpty()) {
      var dirtyProperties = entity.getDirtyPropertiesBetweenCallbacksInternal(false, false);
      var dirtyFields = new HashSet<>(dirtyProperties);

      if (!dirtyFields.isEmpty()) {
        // REMOVE INDEX OF ENTRIES FOR THE OLD VALUES
        final var indexIterator = indexes.iterator();

        while (indexIterator.hasNext()) {
          final var index = indexIterator.next();

          final boolean result;
          if (index.getDefinition() instanceof CompositeIndexDefinition) {
            result = processCompositeIndexDelete(transaction, index, dirtyFields, entity);
          } else {
            result = processSingleIndexDelete(transaction, index, dirtyFields, entity);
          }

          if (result) {
            indexIterator.remove();
          }
        }
      }
    }

    // REMOVE INDEX OF ENTRIES FOR THE NON CHANGED ONLY VALUES
    for (final var index : indexes) {
      final var key = index.getDefinition().getDocumentValueToIndex(transaction, entity);
      deleteIndexKey(transaction, index, entity, key);
    }
  }

  private static void addPut(FrontendTransaction transaction, Index index, Object key,
      Identifiable value) {
    index.put(transaction, key, value);
  }

  private static void addRemove(FrontendTransaction transaction, Index index, Object key,
      Identifiable value) {
    index.remove(transaction, key, value);
  }
}
