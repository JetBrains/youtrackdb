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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.schema.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @since 10/21/14
 */
public class SchemaSnapshot implements ImmutableSchema {

  private final Int2ObjectOpenHashMap<SchemaClass> collectionsToClasses;
  private final Map<String, SchemaClass> classes;

  private final List<GlobalProperty> properties;
  private final Map<String, IndexDefinition> indexes;

  public SchemaSnapshot(@Nonnull SchemaShared schemaShared,
      @Nonnull DatabaseSessionInternal session) {
    collectionsToClasses = new Int2ObjectOpenHashMap<>(schemaShared.getClasses(session).size() * 3);
    classes = new HashMap<>(schemaShared.getClasses(session).size());

    for (var oClass : schemaShared.getClasses(session)) {
      final var immutableClass = new SchemaClassSnapshot(session, oClass, this);

      classes.put(immutableClass.getName().toLowerCase(Locale.ENGLISH), immutableClass);

      for (var collectionId : immutableClass.getCollectionIds()) {
        collectionsToClasses.put(collectionId, immutableClass);
      }
    }

    properties = new ArrayList<>();
    properties.addAll(schemaShared.getGlobalProperties());

    for (SchemaClass cl : classes.values()) {
      ((SchemaClassSnapshot) cl).init(session);
    }

    var indexManager = session.getSharedContext().getIndexManager();
    var internalIndexes = indexManager.getIndexes();

    var indexes = new HashMap<String, IndexDefinition>(internalIndexes.size());
    for (var index : internalIndexes) {
      var indexDefinition = index.getDefinition();
      var indexName = index.getName();
      var metadata = index.getMetadata();

      if (metadata == null) {
        metadata = Collections.emptyMap();
      }

      String collateName = null;
      try {
        collateName = indexDefinition.getCollate().getName();
      } catch (UnsupportedOperationException e) {
        //do nothing
      }

      indexes.put(indexName.toLowerCase(Locale.ROOT),
          new IndexDefinition(indexName, indexDefinition.getClassName(),
              Collections.unmodifiableList(indexDefinition.getProperties()),
              SchemaClass.INDEX_TYPE.valueOf(index.getType()),
              indexDefinition.isNullValuesIgnored(), collateName, metadata));
    }

    this.indexes = Collections.unmodifiableMap(indexes);
  }

  @Override
  public SchemaSnapshot makeSnapshot() {
    return this;
  }

  @Override
  public long countClasses() {
    return classes.size();
  }

  @Override
  public boolean existsClass(String iClassName) {
    return classes.containsKey(iClassName.toLowerCase(Locale.ROOT));
  }

  @Override
  public SchemaClass getClass(Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return getClass(iClass.getSimpleName());
  }

  @Override
  public SchemaClass getClass(String iClassName) {
    return classes.get(iClassName);
  }

  @Override
  public Iterator<SchemaClass> getClasses() {
    return new HashSet<>(classes.values());
  }

  @Override
  public Iterator<String> getIndexes() {
    return indexes.keySet();
  }

  @Override
  public boolean indexExists(String indexName) {
    return indexes.containsKey(indexName.toLowerCase(Locale.ROOT));
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(String indexName) {
    var indexDefinition = indexes.get(indexName.toLowerCase(Locale.ROOT));
    if (indexDefinition == null) {
      throw new IllegalArgumentException("Index '" + indexName + "' not found");
    }

    return indexDefinition;
  }

  @Override
  public CollectionSelectionFactory getCollectionSelectionFactory() {
  }

  @Override
  public SchemaClass getClassByCollectionId(int collectionId) {
    return collectionsToClasses.get(collectionId);
  }


  @Nullable
  @Override
  public GlobalProperty getGlobalPropertyById(int id) {
    if (id >= properties.size()) {
      return null;
    }
    return properties.get(id);
  }

  @Override
  public Iterator<GlobalProperty> getGlobalProperties() {
    return Collections.unmodifiableList(properties);
  }
}
