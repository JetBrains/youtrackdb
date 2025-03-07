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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.IndexDefinition;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.ClusterSelectionFactory;
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

/**
 * @since 10/21/14
 */
public class ImmutableSchema implements SchemaInternal {

  private final Int2ObjectOpenHashMap<SchemaClass> clustersToClasses;
  private final Map<String, SchemaClassInternal> classes;
  private final Map<String, LazySchemaClass> classesRefs;
  private final IntSet blobClusters;

  public final int version;
  private final RID identity;
  private final List<GlobalProperty> properties;
  private final ClusterSelectionFactory clusterSelectionFactory;
  private final Map<String, IndexDefinition> indexes;

  public ImmutableSchema(SchemaShared schemaShared, DatabaseSessionInternal database) {
    version = schemaShared.getVersion();
    identity = schemaShared.getIdentity();
    clusterSelectionFactory = schemaShared.getClusterSelectionFactory();

    Map<String, LazySchemaClass> schemaSharedClassesRefs = schemaShared.getClassesRefs(database);
    classesRefs = new HashMap<>(schemaSharedClassesRefs.size());
    classesRefs.putAll(schemaSharedClassesRefs);

    clustersToClasses = new Int2ObjectOpenHashMap<>(schemaSharedClassesRefs.size() * 3);
    // TODO do we need to load all classes eagerly?
    classes = new HashMap<>(schemaSharedClassesRefs.size());
    for (String className : schemaSharedClassesRefs.keySet()) {
      SchemaClassInternal oClass = schemaShared.getClass(database, className);
      final SchemaImmutableClass immutableClass = new SchemaImmutableClass(database, oClass, this);

      classes.put(immutableClass.getName().toLowerCase(Locale.ENGLISH), immutableClass);
      if (immutableClass.getShortName() != null) {
        classes.put(immutableClass.getShortName().toLowerCase(Locale.ENGLISH), immutableClass);
      }

      for (int clusterId : immutableClass.getClusterIds()) {
        clustersToClasses.put(clusterId, immutableClass);
      }
    }

    properties = new ArrayList<>();
    properties.addAll(schemaShared.getGlobalProperties());

    for (SchemaClass cl : classes.values()) {
      ((SchemaImmutableClass) cl).init();
    }

    this.blobClusters = schemaShared.getBlobClusters();

    var indexManager = database.getMetadata().getIndexManagerInternal();
    var internalIndexes = indexManager.getIndexes(database);

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

  @Override
  public SchemaClass createClass(String iClassName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass iSuperClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass iSuperClass, int[] iClusterIds) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String className, int clusters, SchemaClass... superClasses) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass createClass(String className, int[] clusterIds, SchemaClass... superClasses) {
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
  public Collection<SchemaClass> getClasses(DatabaseSession db) {
    ((DatabaseSessionInternal) db).checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    return new HashSet<>(classes.values());
  }

  @Override
  public Map<String, LazySchemaClass> getClassesRefs(DatabaseSession db) {
    return new HashMap<>(classesRefs);
  }

  @Override
  public Collection<String> getIndexes(DatabaseSession db) {
    return indexes.keySet();
  }

  @Override
  public boolean indexExists(DatabaseSession db, String indexName) {
    return indexes.containsKey(indexName.toLowerCase(Locale.ROOT));
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(DatabaseSession db, String indexName) {
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

  public Set<SchemaClass> getClassesRelyOnCluster(DatabaseSession db, String clusterName) {
    var sessionInternal = (DatabaseSessionInternal) db;
    sessionInternal.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    final int clusterId = sessionInternal.getClusterIdByName(clusterName);
    final Set<SchemaClass> result = new HashSet<SchemaClass>();
    for (SchemaClass c : classes.values()) {
      if (ArrayUtils.contains(c.getPolymorphicClusterIds(), clusterId)) {
        result.add(c);
      }
    }

    return result;
  }

  @Override
  public ClusterSelectionFactory getClusterSelectionFactory() {
    return clusterSelectionFactory;
  }

  @Override
  public SchemaClass getClassByClusterId(int clusterId) {
    return clustersToClasses.get(clusterId);
  }


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


  public IntSet getBlobClusters() {
    return blobClusters;
  }
}
