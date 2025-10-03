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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NonNull;

public final class SchemaSnapshot implements ImmutableSchema {

  private final HashMap<String, SchemaClassSnapshot> classes;
  private final ArrayList<GlobalProperty> globalProperties;
  private final HashMap<String, Index> indexes = new HashMap<>();
  private final Int2ObjectOpenHashMap<SchemaClassSnapshot> collectionsToClasses;

  public SchemaSnapshot(@Nonnull DatabaseSessionEmbedded session) {
    var indexesByClasses = new HashMap<String, List<Index>>();
    var indexEntities = SchemaManager.getIndexes(session);

    while (indexEntities.hasNext()) {
      var indexEntity = indexEntities.next();
      var index = IndexFactory.newIndexSnapshot(indexEntity);
      indexes.put(index.getName(), index);

      var classToIndex = index.getDefinition().getClassName();
      var indexList = indexesByClasses.computeIfAbsent(classToIndex, clsName -> new ArrayList<>());
      indexList.add(index);
    }

    var globalPropertiesEntities = SchemaManager.getGlobalProperties(session);
    globalProperties = new ArrayList<>(globalPropertiesEntities.size());
    for (var globalEntity : globalPropertiesEntities) {
      globalProperties.add(
          new GlobalPropertySnapshot(globalEntity.getName(), globalEntity.getType(),
              globalEntity.getId()));
    }

    var classEntities = SchemaManager.getClasses(session);

    classes = new HashMap<>(classEntities.size());
    collectionsToClasses = new Int2ObjectOpenHashMap<>(classEntities.size() * 3);

    for (var classEntity : classEntities) {
      final var classSnapshot = new SchemaClassSnapshot(session, classEntity, this,
          indexesByClasses.getOrDefault(classEntity.getName(), Collections.emptyList()));
      classes.put(classSnapshot.getName(), classSnapshot);

      for (var collectionId : classSnapshot.getCollectionIds()) {
        collectionsToClasses.put(collectionId, classSnapshot);
      }
    }

    for (var cl : classes.values()) {
      cl.init();
    }
  }

  @Override
  public boolean existsClass(@Nonnull String className) {
    return classes.containsKey(className.toLowerCase(Locale.ROOT));
  }

  @Override
  public SchemaClassSnapshot getClass(@NonNull String className) {
    return classes.get(className);
  }

  @Override
  public @Nonnull Collection<? extends ImmutableSchemaClass> getClasses() {
    return new HashSet<>(classes.values());
  }

  @Override
  public @Nonnull Collection<String> getIndexNames() {
    return indexes.keySet();
  }

  @Override
  public Collection<Index> getIndexes() {
    return indexes.values();
  }

  @Override
  public Index getIndex(String indexName) {
    return indexes.get(indexName);
  }

  @Override
  public SchemaClassSnapshot getClassByCollectionId(int collectionId) {
    return collectionsToClasses.get(collectionId);
  }

  @Nullable
  @Override
  public GlobalProperty getGlobalPropertyById(int id) {
    if (id >= globalProperties.size()) {
      return null;
    }
    return globalProperties.get(id);
  }
}
