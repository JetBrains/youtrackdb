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

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

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
    SchemaImmutableClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass(transaction.getDatabaseSession());
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
    SchemaImmutableClass cls = null;
    if (entity != null) {
      cls = entity.getImmutableSchemaClass(transaction.getDatabaseSession());
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
    // Only dirty components of this index affect routing. Unrelated properties do not
    // require reconstructing the old collection or its Cartesian product.
    var definitions = indexDefinition.getIndexDefinitions();
    var dirtyComponents = indexProperties.stream().filter(dirtyFields::contains).toList();
    if (dirtyComponents.size() == 1
        && definitions.stream().filter(IndexDefinitionMultiValue.class::isInstance).count() == 1) {
      String field = dirtyComponents.getFirst();
      int position = indexProperties.indexOf(field);
      var timeline = record.getCollectionTimeLine(field);
      if (definitions.get(position) instanceof IndexDefinitionMultiValue component
          && timeline != null) {
        var scalars = new ArrayList<Object>(indexProperties.size());
        for (int j = 0; j < indexProperties.size(); j++) {
          Object value = j == position ? null : definitions.get(j)
              .createValue(transaction, record.<Object>getPropertyInternal(indexProperties.get(j)));
          if (j != position && value == null && indexDefinition.isNullValuesIgnored()) {
            return;
          }
          scalars.add(value);
        }
        processIndexUpdateEvents(transaction, index, record, component,
            record.getPropertyInternal(field), timeline, key -> {
              var composite = new CompositeKey();
              for (int j = 0; j < scalars.size(); j++) {
                composite.addKey(j == position ? key : scalars.get(j));
              }
              return composite;
            }, true);
        return;
      }
    }
    for (final var indexField : indexProperties) {
      if (dirtyFields.contains(indexField)) {
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
            final MultiValueChangeTimeLine<?, ?> multiValueChangeTimeLine =
                record.getCollectionTimeLine(
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
              @SuppressWarnings("rawtypes")
              final TrackedMultiValue fieldValue = record.getPropertyInternal(indexProperty);
              @SuppressWarnings("unchecked")
              final var restoredMultiValue = fieldValue.returnOriginalState(transaction,
                  multiValueChangeTimeLine.getMultiValueChangeEvents());
              origValues.add(restoredMultiValue);
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
          final var origValue = indexDefinition.createValue(transaction, origValues);
          final var newValue = indexDefinition.getDocumentValueToIndex(transaction, record);
          processIndexUpdateFieldAssignment(transaction, index, record, origValue, newValue);
        }
        return;
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
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
      processIndexUpdateEvents(transaction, index, iRecord,
          (IndexDefinitionMultiValue) indexDefinition, iRecord.getPropertyInternal(indexField),
          multiValueChangeTimeLine, Function.identity(), false);

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
      final var oldKeys = preparedKeys(index, (Collection<?>) origValue);
      final var newKeys = preparedKeys(index, (Collection<?>) newValue);
      for (var entry : oldKeys.entrySet()) {
        if (!newKeys.containsKey(entry.getKey())
            && (!indexDefinition.isNullValuesIgnored() || entry.getKey() != null)) {
          addRemove(transaction, index, entry.getValue(), iRecord);
        }
      }
      for (var entry : newKeys.entrySet()) {
        if (!oldKeys.containsKey(entry.getKey())
            && (!indexDefinition.isNullValuesIgnored() || entry.getKey() != null)) {
          addPut(transaction, index, entry.getValue(), iRecord);
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

  private static Map<Object, Object> preparedKeys(Index index, Collection<?> values) {
    // Compare binary keys by content. Keep raw representatives for the mutation APIs,
    // which apply collation and shortening themselves.
    final var keys = new TreeMap<Object, Object>(DefaultComparator.INSTANCE);
    for (var value : values) {
      keys.putIfAbsent(index.getCollatingValue(value), value);
    }
    return keys;
  }

  private static final class TouchedKey {
    final Object raw;
    int delta;
    int current;
    RID primaryRid;

    TouchedKey(Object raw) {
      this.raw = raw;
    }
  }

  private static void processIndexUpdateEvents(FrontendTransaction transaction, Index index,
      EntityImpl record, IndexDefinitionMultiValue definition, Object current,
      MultiValueChangeTimeLine<?, ?> timeline, Function<Object, Object> wrap,
      boolean composite) {
    var touched = new TreeMap<Object, TouchedKey>(DefaultComparator.INSTANCE);
    BiConsumer<Object, Integer> touch = (raw, delta) -> {
      Object key = wrap.apply(definition.createSingleValue(transaction, raw));
      var counts = touched.computeIfAbsent(index.getCollatingValue(key),
          ignored -> new TouchedKey(key));
      counts.delta += delta;
      if (definition instanceof PropertyLinkBagIndexDefinition) {
        counts.primaryRid = (RID) raw;
      }
    };
    boolean byKey = (definition instanceof PropertyMapIndexDefinition map
        && map.getIndexBy() == PropertyMapIndexDefinition.INDEX_BY.KEY)
        || definition instanceof PropertyLinkBagIndexDefinition;
    int sizeDelta = 0;
    if (current instanceof Map<?, ?> map) {
      // The first event identifies the old entry. A current lookup supplies its final state.
      // In particular, a phantom ADD from replace on an absent key contributes no delta.
      var firstEvents = new java.util.HashMap<Object, MultiValueChangeEvent<?, ?>>();
      for (var event : timeline.getMultiValueChangeEvents()) {
        firstEvents.putIfAbsent(event.getKey(), event);
      }
      for (var entry : firstEvents.entrySet()) {
        var event = entry.getValue();
        boolean wasPresent = event.getChangeType() != MultiValueChangeEvent.ChangeType.ADD;
        boolean isPresent = map.containsKey(entry.getKey());
        if (wasPresent) {
          touch.accept(byKey ? entry.getKey() : event.getOldValue(), -1);
        }
        if (isPresent) {
          touch.accept(byKey ? entry.getKey() : map.get(entry.getKey()), 1);
        }
        sizeDelta += (isPresent ? 1 : 0) - (wasPresent ? 1 : 0);
      }
    } else {
      // Lists, sets and bags emit one delta per tracked item change.
      for (var event : timeline.getMultiValueChangeEvents()) {
        if (event.getChangeType() != MultiValueChangeEvent.ChangeType.ADD) {
          touch.accept(byKey ? event.getKey() : event.getOldValue(), -1);
        }
        if (event.getChangeType() != MultiValueChangeEvent.ChangeType.REMOVE) {
          touch.accept(byKey ? event.getKey() : event.getValue(), 1);
        }
        sizeDelta += switch (event.getChangeType()) {
          case ADD -> 1;
          case REMOVE -> -1;
          case UPDATE -> 0;
        };
      }
    }
    Object nullKey = wrap.apply(null);
    Object preparedNull = index.getCollatingValue(nullKey);
    var nullCounts = touched.computeIfAbsent(preparedNull, ignored -> new TouchedKey(nullKey));
    int[] size = {0};
    if (current instanceof LinkBag bag && directLinkCounts(index, definition)) {
      size[0] = bag.size();
      for (var counts : touched.values()) {
        counts.current = bag.getCount(counts.primaryRid);
      }
    } else {
      forEachIndexItem(current, definition, raw -> {
        size[0]++;
        Object key = wrap.apply(definition.createSingleValue(transaction, raw));
        var counts = touched.get(index.getCollatingValue(key));
        if (counts != null) {
          counts.current++;
        }
      });
    }
    // Old counts come from deltas, never a copy or replay of the old collection.
    // YTDB-1558 tracks missing or incorrect events that this path cannot reconstruct.
    int oldSize = Math.max(0, size[0] - sizeDelta);
    boolean ignored = index.getDefinition().isNullValuesIgnored();
    boolean collapsesSingleton = definition instanceof PropertyMapIndexDefinition;
    for (var counts : touched.values()) {
      int oldCount = Math.max(0, counts.current - counts.delta);
      boolean before = counts == nullCounts
          ? collectionNullKeyPresent(composite, ignored, collapsesSingleton, oldSize, oldCount)
          : oldCount > 0;
      boolean after = counts == nullCounts
          ? collectionNullKeyPresent(composite, ignored, collapsesSingleton, size[0],
              counts.current)
          : counts.current > 0;
      if (before && !after) {
        addRemove(transaction, index, counts.raw, record);
      } else if (!before && after) {
        addPut(transaction, index, counts.raw, record);
      }
    }
  }

  /**
   * Creation's collection-level null rule. Empty composites contribute a null part when
   * indexed. A singleton map collapses to a scalar, so ignored nulls suppress that key.
   * Lists and bags retain null items as collection components instead.
   */
  static boolean collectionNullKeyPresent(boolean composite, boolean ignored,
      boolean collapsesSingleton, int size, int nullCount) {
    if (!composite) {
      return !ignored && nullCount > 0;
    }
    if (ignored) {
      return nullCount > 0 && !(collapsesSingleton && size == 1);
    }
    return nullCount > 0 || size == 0;
  }

  private static boolean directLinkCounts(Index index, IndexDefinitionMultiValue definition) {
    // Secondary bag definitions index opposite vertices and still need the collection scan.
    if (!(definition instanceof PropertyLinkBagIndexDefinition)) {
      return false;
    }
    var collate = index.getDefinition().getCollate();
    if (index.getDefinition() instanceof CompositeIndexDefinition composite) {
      if (!(collate instanceof CompositeCollate components)) {
        return false;
      }
      collate = components.getCollates().get(composite.getIndexDefinitions().indexOf(definition));
    }
    // Subclasses may transform links. Only these exact built-ins preserve primary identities.
    return collate.getClass()
        == com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate.class
        || collate.getClass()
            == com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate.class;
  }

  private static void forEachIndexItem(Object current, IndexDefinitionMultiValue definition,
      Consumer<Object> consumer) {
    if (current instanceof LinkBag bag) {
      for (var item : bag) {
        consumer.accept(definition instanceof PropertyLinkBagIndexDefinition
            ? item.primaryRid() : item.secondaryRid());
      }
    } else if (current instanceof Map<?, ?> map) {
      var values = ((PropertyMapIndexDefinition) definition).getIndexBy()
          == PropertyMapIndexDefinition.INDEX_BY.KEY ? map.keySet() : map.values();
      values.forEach(consumer);
    } else {
      ((Iterable<?>) current).forEach(consumer);
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
              @SuppressWarnings("rawtypes")
              final TrackedMultiValue propertyValue = record.getProperty(
                  property);
              @SuppressWarnings("unchecked")
              final var restoredMultiValue =
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
    SchemaImmutableClass cls = null;
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
