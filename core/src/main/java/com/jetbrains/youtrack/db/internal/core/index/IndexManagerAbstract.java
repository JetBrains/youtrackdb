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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.util.MultiKey;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Abstract class to manage indexes.
 */
public abstract class IndexManagerAbstract implements CloseableInStorage {

  protected final Map<String, Map<MultiKey, Set<Index>>> classPropertyIndex =
      new ConcurrentHashMap<>();
  protected Map<String, Index> indexes = new ConcurrentHashMap<>();
  final Storage storage;

  String CONFIG_INDEXES = "indexes";

  protected RID indexManagerIdentity;

  protected IndexManagerAbstract(Storage storage) {
    this.storage = storage;
  }


  public abstract Index createIndex(
      DatabaseSessionEmbedded session,
      final String iName,
      final String iType,
      IndexDefinition indexDefinition,
      final int[] collectionIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata);

  public abstract Index createIndex(
      DatabaseSessionEmbedded session,
      final String iName,
      final String iType,
      IndexDefinition indexDefinition,
      final int[] collectionIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata,
      String algorithm);

  public abstract void dropIndex(DatabaseSessionInternal session, final String iIndexName);

  public abstract void reload(DatabaseSessionInternal session);


  public abstract void load(DatabaseSessionInternal session);

  public void getClassRawIndexes(DatabaseSessionInternal session,
      final String className, final Collection<Index> indexes) {
    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return;
    }

    for (final var propertyIndexes : propertyIndex.values()) {
      indexes.addAll(propertyIndexes);
    }
  }

  protected Map<MultiKey, Set<Index>> getIndexOnProperty(final String className) {
    return classPropertyIndex.get(className.toLowerCase());
  }

  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, final String className, Collection<String> fields) {
    final var multiKey = new MultiKey(fields);

    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null || !propertyIndex.containsKey(multiKey)) {
      return Collections.emptySet();
    }

    final var rawResult = propertyIndex.get(multiKey);
    final Set<Index> result = new HashSet<>(rawResult.size());
    for (final var index : rawResult) {
      // ignore indexes that ignore null values on partial match
      if (fields.size() == index.getDefinition().getFields().size()
          || !index.getDefinition().isNullValuesIgnored()) {
        result.add(index);
      }
    }

    return result;
  }


  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, final String className, final String... fields) {
    return getClassInvolvedIndexes(session, className, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String className,
      final String... fields) {
    return areIndexed(session, className, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String className,
      Collection<String> fields) {
    final var multiKey = new MultiKey(fields);

    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return false;
    }

    return propertyIndex.containsKey(multiKey) && !propertyIndex.get(multiKey).isEmpty();
  }

  public Set<Index> getClassIndexes(DatabaseSessionInternal session, final String className) {
    final var coll = new HashSet<Index>(4);
    getClassIndexes(session, className, coll);
    return coll;
  }

  @Nullable
  public Index getClassIndex(
      DatabaseSessionInternal session, String className, String indexName) {
    className = className.toLowerCase();

    final var index = indexes.get(indexName);
    if (index != null
        && index.getDefinition() != null
        && index.getDefinition().getClassName() != null
        && className.equals(index.getDefinition().getClassName().toLowerCase())) {
      return index;
    }
    return null;
  }

  public void getClassIndexes(DatabaseSessionInternal session, final String className,
      final Collection<Index> indexes) {
    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return;
    }

    for (final var propertyIndexes : propertyIndex.values()) {
      indexes.addAll(propertyIndexes);
    }
  }


  public Collection<? extends Index> getIndexes() {
    return indexes.values();
  }


  public Index getIndex(final String iName) {
    return indexes.get(iName);
  }

  public boolean existsIndex(final String iName) {
    return indexes.containsKey(iName);
  }

  protected void load(FrontendTransaction transaction, EntityImpl entity) {
    indexes.clear();
    classPropertyIndex.clear();

    final var indexEntities = entity.getLinkSet(CONFIG_INDEXES);
    if (indexEntities != null) {
      for (var indexIdentifiable : indexEntities) {
        var indexEntity = transaction.loadEntity(indexIdentifiable);
        final var newIndexMetadata = IndexAbstract.loadMetadataFromMap(transaction,
            indexEntity.toMap(false));
        var index =
            createIndexInstance(transaction, indexIdentifiable, newIndexMetadata);
        addIndexInternalNoLock(index, transaction, false);
      }
    }
  }

  protected abstract Index createIndexInstance(FrontendTransaction transaction,
      Identifiable indexIdentifiable,
      IndexMetadata newIndexMetadata);

  protected void addIndexInternalNoLock(final Index index, FrontendTransaction transaction,
      boolean updateEntity) {
    if (updateEntity) {
      var indexEntity = transaction.loadEntity(indexManagerIdentity);
      indexEntity.getOrCreateLinkSet(CONFIG_INDEXES).add(index.getIdentity());
    }

    indexes.put(index.getName(), index);

    final var indexDefinition = index.getDefinition();
    if (indexDefinition == null || indexDefinition.getClassName() == null) {
      return;
    }

    var propertyIndex = getIndexOnProperty(indexDefinition.getClassName());

    if (propertyIndex == null) {
      propertyIndex = new HashMap<>();
    } else {
      propertyIndex = new HashMap<>(propertyIndex);
    }

    final var paramCount = indexDefinition.getParamCount();

    for (var i = 1; i <= paramCount; i++) {
      final var fields = indexDefinition.getFields().subList(0, i);
      final var multiKey = new MultiKey(fields);
      var indexSet = propertyIndex.get(multiKey);

      if (indexSet == null) {
        indexSet = new HashSet<>();
      } else {
        indexSet = new HashSet<>(indexSet);
      }

      indexSet.add(index);
      propertyIndex.put(multiKey, indexSet);
    }

    classPropertyIndex.put(
        indexDefinition.getClassName().toLowerCase(), copyPropertyMap(propertyIndex));
  }

  public RID getIdentity() {
    return indexManagerIdentity;
  }

  static Map<MultiKey, Set<Index>> copyPropertyMap(Map<MultiKey, Set<Index>> original) {
    final Map<MultiKey, Set<Index>> result = new HashMap<>();

    for (var entry : original.entrySet()) {
      Set<Index> indexes = new HashSet<>(entry.getValue());
      assert indexes.equals(entry.getValue());

      result.put(entry.getKey(), Collections.unmodifiableSet(indexes));
    }

    assert result.equals(original);

    return Collections.unmodifiableMap(result);
  }
}
