/*
 *
 *  *  Copyright YouTrackDB
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
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.IndexDefinition;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.ClusterSelectionFactory;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Proxy class to use the shared SchemaShared instance. Before delegating each operation it sets
 * the current database in the thread local.
 */
public class SchemaProxy extends ProxedResource<SchemaShared> implements SchemaInternal {

  public SchemaProxy(final SchemaShared iDelegate, final DatabaseSessionInternal iDatabase) {
    super(iDelegate, iDatabase);
  }

  @Override
  public ImmutableSchema makeSnapshot() {
    return delegate.makeSnapshot(database);
  }

  public void create() {
    delegate.create(database);
  }

  public int countClasses() {
    return delegate.countClasses(database);
  }


  public SchemaClass createClass(final String iClassName) {
    return delegate.createClass(database, iClassName);
  }

  public SchemaClass getOrCreateClass(final String iClassName) {
    return getOrCreateClass(iClassName, (SchemaClass) null);
  }

  public SchemaClass getOrCreateClass(final String iClassName, final SchemaClass iSuperClass) {
    if (iClassName == null) {
      return null;
    }

    SchemaClass cls = delegate.getClass(database, iClassName.toLowerCase(Locale.ENGLISH));
    if (cls != null) {
      return cls;
    }

    cls = delegate.getOrCreateClass(database, iClassName, iSuperClass);

    return cls;
  }

  @Override
  public SchemaClass getOrCreateClass(String iClassName, SchemaClass... superClasses) {
    return delegate.getOrCreateClass(database, iClassName, superClasses);
  }

  @Override
  public SchemaClass createClass(final String iClassName, final SchemaClass iSuperClass) {
    return delegate.createClass(database, iClassName, iSuperClass, null);
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass... superClasses) {
    return delegate.createClass(database, iClassName, superClasses);
  }

  public SchemaClass createClass(
      final String iClassName, final SchemaClass iSuperClass, final int[] iClusterIds) {
    return delegate.createClass(database, iClassName, iSuperClass, iClusterIds);
  }

  @Override
  public SchemaClass createClass(String className, int[] clusterIds, SchemaClass... superClasses) {
    return delegate.createClass(database, className, clusterIds, superClasses);
  }

  @Override
  public SchemaClass createAbstractClass(final String iClassName) {
    return delegate.createAbstractClass(database, iClassName);
  }

  @Override
  public SchemaClass createAbstractClass(final String iClassName, final SchemaClass iSuperClass) {
    return delegate.createAbstractClass(database, iClassName, iSuperClass);
  }

  @Override
  public SchemaClass createAbstractClass(String iClassName, SchemaClass... superClasses) {
    return delegate.createAbstractClass(database, iClassName, superClasses);
  }

  public void dropClass(final String iClassName) {
    delegate.dropClass(database, iClassName);
  }

  public boolean existsClass(final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    return delegate.existsClass(iClassName.toLowerCase(Locale.ENGLISH));
  }

  public SchemaClass getClass(final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return delegate.getClass(database, iClass);
  }

  public SchemaClass getClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    return delegate.getClass(database, iClassName);
  }

  @Override
  public Collection<SchemaClass> getClasses(DatabaseSession db) {
    return delegate.getClassesSlow(database);
  }

  @Override
  public Map<String, LazySchemaClass> getClassesRefs(DatabaseSession db) {
    return delegate.getClassesRefs(database);
  }

  @Override
  public Collection<String> getIndexes(DatabaseSession db) {
    var sessionInternal = (DatabaseSessionInternal) db;
    var indexManager = sessionInternal.getMetadata().getIndexManager();

    var indexesInternal = indexManager.getIndexes();
    var indexes = new HashSet<String>(indexesInternal.size());
    for (var index : indexesInternal) {
      indexes.add(index.getName());
    }

    return indexes;
  }

  @Override
  public boolean indexExists(DatabaseSession db, String indexName) {
    var sessionInternal = (DatabaseSessionInternal) db;
    var indexManager = sessionInternal.getMetadata().getIndexManagerInternal();

    return indexManager.existsIndex(indexName);
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(DatabaseSession db, String indexName) {
    var sessionInternal = (DatabaseSessionInternal) db;
    var indexManager = sessionInternal.getMetadata().getIndexManagerInternal();
    var index = indexManager.getIndex(sessionInternal, indexName);

    if (index == null) {
      throw new IllegalArgumentException("Index '" + indexName + "' not found");
    }

    var indexDefinition = index.getDefinition();

    var metadata = index.getMetadata();

    if (metadata != null) {
      metadata = Collections.emptyMap();
    }

    return new IndexDefinition(indexName, indexDefinition.getClassName(),
        Collections.unmodifiableList(indexDefinition.getFields()),
        SchemaClass.INDEX_TYPE.valueOf(index.getType()),
        indexDefinition.isNullValuesIgnored(),
        indexDefinition.getCollate().getName(), metadata);
  }

  @Deprecated
  public void load() {

    delegate.load(database);
  }

  public Schema reload() {
    delegate.reload(database);
    return this;
  }

  public int getVersion() {

    return delegate.getVersion();
  }

  public RecordId getIdentity() {

    return delegate.getIdentity();
  }

  @Deprecated
  public void close() {
    // DO NOTHING THE DELEGATE CLOSE IS MANAGED IN A DIFFERENT CONTEXT
  }

  public String toString() {

    return delegate.toString();
  }

  @Override
  public Set<SchemaClass> getClassesRelyOnCluster(DatabaseSession db, final String iClusterName) {
    return delegate.getClassesRelyOnCluster(database, iClusterName);
  }

  @Override
  public SchemaClass createClass(String className, int clusters, SchemaClass... superClasses) {
    return delegate.createClass(database, className, clusters, superClasses);
  }

  @Override
  public SchemaClass getClassByClusterId(int clusterId) {
    return delegate.getClassByClusterId(clusterId);
  }


  @Override
  public GlobalProperty getGlobalPropertyById(int id) {
    return delegate.getGlobalPropertyById(id);
  }

  @Override
  public List<GlobalProperty> getGlobalProperties() {
    return delegate.getGlobalProperties();
  }

  public GlobalProperty createGlobalProperty(String name, PropertyType type, Integer id) {
    return delegate.createGlobalProperty(name, type, id);
  }

  @Override
  public ClusterSelectionFactory getClusterSelectionFactory() {
    return delegate.getClusterSelectionFactory();
  }

  @Override
  public SchemaClassInternal getClassInternal(String iClassName) {
    return delegate.getClass(database, iClassName);
  }

  public IntSet getBlobClusters() {
    return delegate.getBlobClusters();
  }

  public int addBlobCluster(final int clusterId) {
    return delegate.addBlobCluster(database, clusterId);
  }

  public void removeBlobCluster(String clusterName) {
    delegate.removeBlobCluster(database, clusterName);
  }
}
