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
package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.IndexDefinition;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.collectionselection.CollectionSelectionFactory;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @since 10/21/14
 */
public class ImmutableSchema implements SchemaInternal {

  private final Int2ObjectOpenHashMap<SchemaClass> collectionsToClasses;
  private final Map<String, SchemaClassInternal> classes;
  private final IntSet blogCollections;

  public final int version;
  private final RID identity;
  private final List<GlobalProperty> properties;
  private final CollectionSelectionFactory collectionSelectionFactory;
  private final Map<String, IndexDefinition> indexes;

  public ImmutableSchema(@Nonnull SchemaShared schemaShared,
      @Nonnull DatabaseSessionInternal session) {
    version = schemaShared.getVersion();
    identity = schemaShared.getIdentity(session);
    collectionSelectionFactory = schemaShared.getCollectionSelectionFactory();

    collectionsToClasses = new Int2ObjectOpenHashMap<>(schemaShared.getClasses(session).size() * 3);
    classes = new HashMap<>(schemaShared.getClasses(session).size());

    for (var oClass : schemaShared.getClasses(session)) {
      final var immutableClass = new SchemaImmutableClass(session, oClass, this);

      classes.put(immutableClass.getName().toLowerCase(Locale.ENGLISH), immutableClass);

      for (var collectionId : immutableClass.getCollectionIds()) {
        collectionsToClasses.put(collectionId, immutableClass);
      }
    }

    properties = new ArrayList<>();
    properties.addAll(schemaShared.getGlobalProperties(session));

    for (SchemaClass cl : classes.values()) {
      ((SchemaImmutableClass) cl).init(session);
    }

    this.blogCollections = schemaShared.getBlobCollections(session);

    var indexManager = session.getMetadata().getIndexManagerInternal();
    var internalIndexes = indexManager.getIndexes(session);

    var indexes = new HashMap<String, IndexDefinition>(internalIndexes.size());
    for (var index : internalIndexes) {
      var indexDefinition = index.getDefinition();
      var indexName = index.getName();
      var metadata = index.getMetadata();

      if (metadata != null) {
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
              Collections.unmodifiableList(indexDefinition.getFields()),
              SchemaClass.INDEX_TYPE.valueOf(index.getType()),
              indexDefinition.isNullValuesIgnored(), collateName, metadata));
    }

    this.indexes = Collections.unmodifiableMap(indexes);
  }

  public ImmutableSchema makeSnapshot() {
    return this;
  }

  @Override
  public int countClasses() {
    return classes.size();
  }

  @Nonnull
  @Override
  public SchemaClass createClass(String iClassName) {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull String iClassName, @Nonnull SchemaClass iSuperClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass iSuperClass, int[] iCollectionIds) {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull String className, int collections,
      @Nonnull SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String className, int[] collectionIds, SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createAbstractClass(String iClassName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createAbstractClass(String iClassName, SchemaClass iSuperClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createAbstractClass(String iClassName, SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropClass(String iClassName) {
    throw new UnsupportedOperationException();
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
    return getClassInternal(iClassName);
  }

  @Nullable
  @Override
  public SchemaClassInternal getClassInternal(String iClassName) {
    if (iClassName == null) {
      return null;
    }

    return classes.get(iClassName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public SchemaClass getOrCreateClass(String iClassName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass getOrCreateClass(String iClassName, SchemaClass iSuperClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass getOrCreateClass(String iClassName, SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<SchemaClass> getClasses() {
    return new HashSet<>(classes.values());
  }

  @Override
  public Collection<String> getIndexes() {
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
  public int getVersion() {
    return version;
  }

  @Override
  public RecordId getIdentity() {
    return new RecordId(identity);
  }

  public Set<SchemaClass> getClassesRelyOnCollection(String collectionName,
      DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    final var collectionId = session.getCollectionIdByName(collectionName);
    final Set<SchemaClass> result = new HashSet<SchemaClass>();
    for (SchemaClass c : classes.values()) {
      if (ArrayUtils.contains(c.getPolymorphicCollectionIds(), collectionId)) {
        result.add(c);
      }
    }

    return result;
  }

  @Override
  public CollectionSelectionFactory getCollectionSelectionFactory() {
    return collectionSelectionFactory;
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
  public List<GlobalProperty> getGlobalProperties() {
    return Collections.unmodifiableList(properties);
  }

  @Override
  public GlobalProperty createGlobalProperty(String name, PropertyType type, Integer id) {
    throw new UnsupportedOperationException();
  }

  public IntSet getBlobCollections() {
    return blogCollections;
  }
}
