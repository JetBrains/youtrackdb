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

package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
      EntityImpl entity, DatabaseSessionInternal database) {
    entity = checkForLoading(database, entity);
    processIndexOnCreate(database, entity);
  }

  public static void reIndex(DatabaseSessionInternal session, EntityImpl entity,
      Index index) {
    entity = checkForLoading(session, entity);
    addIndexEntry(session, entity, entity.getIdentity(), index);
  }

  private static void processIndexOnCreate(DatabaseSessionInternal database,
      EntityImpl entity) {
    SchemaImmutableClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass(database);
    }
    if (cls != null) {
      final Collection<Index> indexes = cls.getRawIndexes();
      addIndexesEntries(database, entity, indexes);
    }
  }

  public static void checkIndexesAfterUpdate(
      EntityImpl entity, DatabaseSessionInternal database) {
    entity = checkForLoading(database, entity);
    processIndexOnUpdate(database, entity);
  }

  private static void processIndexOnUpdate(DatabaseSessionInternal database,
      EntityImpl entity) {
    SchemaImmutableClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass(database);
    }
    if (cls == null) {
      return;
    }

    final Collection<Index> indexes = cls.getRawIndexes();
    if (!indexes.isEmpty()) {
      final Set<String> dirtyFields;
      var dirtyProperties = entity.getDirtyProperties();

      if (dirtyProperties instanceof Set<String> dirtyFieldsSet) {
        dirtyFields = dirtyFieldsSet;
      } else {
        dirtyFields = new HashSet<>(dirtyProperties);
      }

      if (!dirtyFields.isEmpty()) {
        for (final var index : indexes) {
          processIndexUpdate(database, entity, dirtyFields, index);
        }
      }
    }
  }

  public static void checkIndexesAfterDelete(
      EntityImpl entity, DatabaseSessionInternal database) {
    processIndexOnDelete(database, entity);
  }

  private static void processCompositeIndexUpdate(
      DatabaseSessionInternal session,
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl iRecord) {
    final var indexDefinition =
        (CompositeIndexDefinition) index.getDefinition();

    final var indexFields = indexDefinition.getFields();
    final var multiValueField = indexDefinition.getMultiValueField();

    for (final var indexField : indexFields) {
      if (dirtyFields.contains(indexField)) {
        final List<Object> origValues = new ArrayList<>(indexFields.size());

        for (final var field : indexFields) {
          if (!field.equals(multiValueField)) {
            if (dirtyFields.contains(field)) {
              origValues.add(iRecord.getOriginalValue(field));
            } else {
              origValues.add(iRecord.field(field));
            }
          }
        }

        if (multiValueField == null) {
          final var origValue = indexDefinition.createValue(session, origValues);
          final var newValue = indexDefinition.getDocumentValueToIndex(session, iRecord);

          if (!indexDefinition.isNullValuesIgnored() || origValue != null) {
            addRemove(session, index, origValue, iRecord);
          }

          if (!indexDefinition.isNullValuesIgnored() || newValue != null) {
            addPut(session, index, newValue, iRecord.getIdentity());
          }
        } else {
          final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
              iRecord.getCollectionTimeLine(multiValueField);
          if (multiValueChangeTimeLine == null) {
            if (dirtyFields.contains(multiValueField)) {
              origValues.add(
                  indexDefinition.getMultiValueDefinitionIndex(),
                  iRecord.getOriginalValue(multiValueField));
            } else {
              origValues.add(
                  indexDefinition.getMultiValueDefinitionIndex(),
                  iRecord.getPropertyInternal(multiValueField));
            }

            final var origValue = indexDefinition.createValue(session, origValues);
            final var newValue = indexDefinition.getDocumentValueToIndex(session, iRecord);

            processIndexUpdateFieldAssignment(session, index, iRecord, origValue, newValue);
          } else {
            // in case of null values support and empty collection field we put null placeholder in
            // place where collection item should be located so we can not use "fast path" to
            // update index values
            if (dirtyFields.size() == 1 && indexDefinition.isNullValuesIgnored()) {
              final var keysToAdd = new Object2IntOpenHashMap<CompositeKey>();
              keysToAdd.defaultReturnValue(-1);
              final var keysToRemove =
                  new Object2IntOpenHashMap<CompositeKey>();
              keysToRemove.defaultReturnValue(-1);

              for (var changeEvent :
                  multiValueChangeTimeLine.getMultiValueChangeEvents()) {
                indexDefinition.processChangeEvent(
                    session, changeEvent, keysToAdd, keysToRemove, origValues.toArray());
              }

              for (final Object keyToRemove : keysToRemove.keySet()) {
                addRemove(session, index, keyToRemove, iRecord);
              }

              for (final Object keyToAdd : keysToAdd.keySet()) {
                addPut(session, index, keyToAdd, iRecord.getIdentity());
              }
            } else {
              @SuppressWarnings("rawtypes") final TrackedMultiValue fieldValue = iRecord.field(
                  multiValueField);
              @SuppressWarnings("unchecked") final var restoredMultiValue =
                  fieldValue.returnOriginalState(session,
                      multiValueChangeTimeLine.getMultiValueChangeEvents());

              origValues.add(indexDefinition.getMultiValueDefinitionIndex(), restoredMultiValue);

              final var origValue = indexDefinition.createValue(session, origValues);
              final var newValue = indexDefinition.getDocumentValueToIndex(session, iRecord);

              processIndexUpdateFieldAssignment(session, index, iRecord, origValue, newValue);
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
      DatabaseSessionInternal session) {
    final var indexDefinition = index.getDefinition();
    final var indexFields = indexDefinition.getFields();

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
      final var indexDefinitionMultiValue =
          (IndexDefinitionMultiValue) indexDefinition;
      final var keysToAdd = new Object2IntOpenHashMap<Object>();
      keysToAdd.defaultReturnValue(-1);
      final var keysToRemove = new Object2IntOpenHashMap<Object>();
      keysToRemove.defaultReturnValue(-1);

      for (var changeEvent :
          multiValueChangeTimeLine.getMultiValueChangeEvents()) {
        indexDefinitionMultiValue.processChangeEvent(session, changeEvent, keysToAdd, keysToRemove);
      }

      for (final var keyToRemove : keysToRemove.keySet()) {
        addRemove(session, index, keyToRemove, iRecord);
      }

      for (final var keyToAdd : keysToAdd.keySet()) {
        addPut(session, index, keyToAdd, iRecord.getIdentity());
      }

    } else {
      final var origValue =
          indexDefinition.createValue(session, iRecord.getOriginalValue(indexField));
      final var newValue = indexDefinition.getDocumentValueToIndex(session, iRecord);

      processIndexUpdateFieldAssignment(session, index, iRecord, origValue, newValue);
    }
  }

  private static void processIndexUpdateFieldAssignment(
      DatabaseSessionInternal session, Index index, EntityImpl iRecord, final Object origValue,
      final Object newValue) {
    final var indexDefinition = index.getDefinition();
    if ((origValue instanceof Collection) && (newValue instanceof Collection)) {
      final Set<Object> valuesToRemove = new HashSet<>((Collection<?>) origValue);
      final Set<Object> valuesToAdd = new HashSet<>((Collection<?>) newValue);

      valuesToRemove.removeAll((Collection<?>) newValue);
      valuesToAdd.removeAll((Collection<?>) origValue);

      for (final var valueToRemove : valuesToRemove) {
        if (!indexDefinition.isNullValuesIgnored() || valueToRemove != null) {
          addRemove(session, index, valueToRemove, iRecord);
        }
      }

      for (final var valueToAdd : valuesToAdd) {
        if (!indexDefinition.isNullValuesIgnored() || valueToAdd != null) {
          addPut(session, index, valueToAdd, iRecord);
        }
      }
    } else {
      deleteIndexKey(session, index, iRecord, origValue);
      if (newValue instanceof Collection) {
        for (final var newValueItem : (Collection<?>) newValue) {
          addPut(session, index, newValueItem, iRecord.getIdentity());
        }
      } else if (!indexDefinition.isNullValuesIgnored() || newValue != null) {
        addPut(session, index, newValue, iRecord.getIdentity());
      }
    }
  }

  private static boolean processCompositeIndexDelete(
      DatabaseSessionInternal session,
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl iRecord) {
    final var indexDefinition =
        (CompositeIndexDefinition) index.getDefinition();

    final var multiValueField = indexDefinition.getMultiValueField();

    final var indexFields = indexDefinition.getFields();
    for (final var indexField : indexFields) {
      // REMOVE IT
      if (dirtyFields.contains(indexField)) {
        final List<Object> origValues = new ArrayList<>(indexFields.size());

        for (final var field : indexFields) {
          if (!field.equals(multiValueField)) {
            if (dirtyFields.contains(field)) {
              origValues.add(iRecord.getOriginalValue(field));
            } else {
              origValues.add(iRecord.field(field));
            }
          }
        }

        if (multiValueField != null) {
          final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
              iRecord.getCollectionTimeLine(multiValueField);
          if (multiValueChangeTimeLine != null) {
            @SuppressWarnings("rawtypes") final TrackedMultiValue fieldValue = iRecord.field(
                multiValueField);
            @SuppressWarnings("unchecked") final var restoredMultiValue =
                fieldValue.returnOriginalState(session,
                    multiValueChangeTimeLine.getMultiValueChangeEvents());
            origValues.add(indexDefinition.getMultiValueDefinitionIndex(), restoredMultiValue);
          } else if (dirtyFields.contains(multiValueField)) {
            origValues.add(
                indexDefinition.getMultiValueDefinitionIndex(),
                iRecord.getOriginalValue(multiValueField));
          } else {
            origValues.add(
                indexDefinition.getMultiValueDefinitionIndex(), iRecord.field(multiValueField));
          }
        }

        final var origValue = indexDefinition.createValue(session, origValues);
        deleteIndexKey(session, index, iRecord, origValue);
        return true;
      }
    }
    return false;
  }

  private static void deleteIndexKey(
      DatabaseSessionInternal session, final Index index, final EntityImpl iRecord,
      final Object origValue) {
    final var indexDefinition = index.getDefinition();
    if (origValue instanceof Collection) {
      for (final var valueItem : (Collection<?>) origValue) {
        if (!indexDefinition.isNullValuesIgnored() || valueItem != null) {
          addRemove(session, index, valueItem, iRecord);
        }
      }
    } else if (!indexDefinition.isNullValuesIgnored() || origValue != null) {
      addRemove(session, index, origValue, iRecord);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static boolean processSingleIndexDelete(
      DatabaseSessionInternal session,
      final Index index,
      final Set<String> dirtyFields,
      final EntityImpl iRecord) {
    final var indexDefinition = index.getDefinition();

    final var indexFields = indexDefinition.getFields();
    if (indexFields.isEmpty()) {
      return false;
    }

    final var indexField = indexFields.getFirst();
    if (dirtyFields.contains(indexField)) {
      final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
          iRecord.getCollectionTimeLine(indexField);

      final Object origValue;
      if (multiValueChangeTimeLine != null) {
        final TrackedMultiValue fieldValue = iRecord.field(indexField);
        final var restoredMultiValue =
            fieldValue.returnOriginalState(session,
                multiValueChangeTimeLine.getMultiValueChangeEvents());
        origValue = indexDefinition.createValue(session, restoredMultiValue);
      } else {
        origValue = indexDefinition.createValue(session, iRecord.getOriginalValue(indexField));
      }
      deleteIndexKey(session, index, iRecord, origValue);
      return true;
    }
    return false;
  }

  private static EntityImpl checkForLoading(DatabaseSessionInternal session,
      final EntityImpl iRecord) {
    if (iRecord.isUnloaded()) {
      try {
        return session.load(iRecord.getIdentity());
      } catch (final RecordNotFoundException e) {
        throw BaseException.wrapException(
            new IndexException(session.getDatabaseName(),
                "Error during loading of record with id " + iRecord.getIdentity()),
            e, session.getDatabaseName());
      }
    }
    return iRecord;
  }

  public static void processIndexUpdate(
      DatabaseSessionInternal session,
      EntityImpl entity,
      Set<String> dirtyFields,
      Index index) {
    if (index.getDefinition() instanceof CompositeIndexDefinition) {
      processCompositeIndexUpdate(session, index, dirtyFields, entity);
    } else {
      processSingleIndexUpdate(index, dirtyFields, entity, session);
    }
  }

  public static void addIndexesEntries(
      DatabaseSessionInternal session, EntityImpl entity, final Collection<Index> indexes) {
    // STORE THE RECORD IF NEW, OTHERWISE ITS RID
    final Identifiable rid = entity.getIdentity();

    for (final var index : indexes) {
      addIndexEntry(session, entity, rid, index);
    }
  }

  private static void addIndexEntry(
      DatabaseSessionInternal session, EntityImpl entity, Identifiable rid, Index index) {
    final var indexDefinition = index.getDefinition();
    final var key = indexDefinition.getDocumentValueToIndex(session, entity);
    if (key instanceof Collection) {
      for (final var keyItem : (Collection<?>) key) {
        if (!indexDefinition.isNullValuesIgnored() || keyItem != null) {
          addPut(session, index, keyItem, rid);
        }
      }
    } else if (!indexDefinition.isNullValuesIgnored() || key != null) {
      addPut(session, index, key, rid);
    }
  }

  public static void processIndexOnDelete(DatabaseSessionInternal database,
      EntityImpl entity) {
    SchemaImmutableClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass(database);
    }
    if (cls == null) {
      return;
    }

    final Collection<Index> indexes = new ArrayList<>(cls.getRawIndexes());

    if (!indexes.isEmpty()) {
      final Set<String> dirtyFields;
      var dirtyProperties = entity.getDirtyProperties();

      if (dirtyProperties instanceof Set<String> dirtyFieldsSet) {
        dirtyFields = dirtyFieldsSet;
      } else {
        dirtyFields = new HashSet<>(dirtyProperties);
      }

      if (!dirtyFields.isEmpty()) {
        // REMOVE INDEX OF ENTRIES FOR THE OLD VALUES
        final var indexIterator = indexes.iterator();

        while (indexIterator.hasNext()) {
          final var index = indexIterator.next();

          final boolean result;
          if (index.getDefinition() instanceof CompositeIndexDefinition) {
            result = processCompositeIndexDelete(database, index, dirtyFields, entity);
          } else {
            result = processSingleIndexDelete(database, index, dirtyFields, entity);
          }

          if (result) {
            indexIterator.remove();
          }
        }
      }
    }

    // REMOVE INDEX OF ENTRIES FOR THE NON CHANGED ONLY VALUES
    for (final var index : indexes) {
      final var key = index.getDefinition().getDocumentValueToIndex(database, entity);
      deleteIndexKey(database, index, entity, key);
    }
  }

  private static void addPut(DatabaseSessionInternal session, Index index, Object key,
      Identifiable value) {
    index.put(session, key, value);
  }

  private static void addRemove(DatabaseSessionInternal session, Index index, Object key,
      Identifiable value) {
    index.remove(session, key, value);
  }
}
