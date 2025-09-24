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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NonNull;

/**
 * @since 10/21/14
 */
public final class SchemaSnapshot implements ImmutableSchema {

  private final Int2ObjectOpenHashMap<SchemaClassSnapshot> collectionsToClasses;
  private final Map<String, SchemaClassSnapshot> classes;

  private final List<GlobalProperty> properties;
  private final Map<String, IndexDefinition> indexes;

  public SchemaSnapshot(@Nonnull SchemaManager schemaManager,
      @Nonnull DatabaseSessionEmbedded session) {
    collectionsToClasses = new Int2ObjectOpenHashMap<>(
        schemaManager.getClasses(session).size() * 3);
    classes = new HashMap<>(schemaManager.getClasses(session).size());

    for (var oClass : schemaManager.getClasses(session)) {
      final var immutableClass = new SchemaClassSnapshot(session, oClass, this);

      classes.put(immutableClass.getName().toLowerCase(Locale.ENGLISH), immutableClass);

      for (var collectionId : immutableClass.getCollectionIds()) {
        collectionsToClasses.put(collectionId, immutableClass);
      }
    }

    properties = new ArrayList<>();
    properties.addAll(SchemaManager.getGlobalProperties());

    for (var cl : classes.values()) {
      cl.init(session);
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
              ImmutableSchemaClass.INDEX_TYPE.valueOf(index.getType()),
              indexDefinition.isNullValuesIgnored(), collateName, metadata));
    }

    this.indexes = Collections.unmodifiableMap(indexes);
  }

  @Override
  public boolean existsClass(@Nonnull String className) {
    return classes.containsKey(className.toLowerCase(Locale.ROOT));
  }

  @Override
  public SchemaClassSnapshot getClass(@NonNull String className) {
    return (SchemaClassSnapshot) classes.get(className);
  }

  @Override
  public @Nonnull Collection<? extends ImmutableSchemaClass> getClasses() {
    return new HashSet<>(classes.values());
  }

  @Override
  public @Nonnull Collection<String> getIndexes() {
    return indexes.keySet();
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(@Nonnull String indexName) {
    var indexDefinition = indexes.get(indexName.toLowerCase(Locale.ROOT));
    if (indexDefinition == null) {
      throw new IllegalArgumentException("Index '" + indexName + "' not found");
    }

    return indexDefinition;
  }

  @Override
  public SchemaClassSnapshot getClassByCollectionId(int collectionId) {
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

}
