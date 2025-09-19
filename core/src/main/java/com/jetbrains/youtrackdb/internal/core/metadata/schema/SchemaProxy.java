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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.schema.IndexDefinition;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Proxy class to use the shared SchemaShared instance. Before to delegate each operations it sets
 * the current database in the thread local.
 */
public final class SchemaProxy extends ProxedResource<SchemaShared> implements Schema {

  public SchemaProxy(final SchemaShared iDelegate, final DatabaseSessionEmbedded iDatabase) {
    super(iDelegate, iDatabase);
  }

  @Override
  public SchemaSnapshot makeSnapshot() {
    assert session.assertIfNotActive();
    return delegate.makeSnapshot(session);
  }

  public void create() {
    assert session.assertIfNotActive();
    delegate.create(session);
  }

  @Override
  public long countClasses() {
    assert session.assertIfNotActive();
    return delegate.countClasses(session);
  }


  @Override
  @Nonnull
  public SchemaClass createClass(final String iClassName) {
    assert session.assertIfNotActive();
    return new SchemaClassProxy(delegate.createClass(session, iClassName), session);
  }

  @Override
  public SchemaClass getOrCreateClass(final String iClassName) {
    return getOrCreateClass(iClassName, (SchemaClass) null);
  }

  @Override
  @Nullable
  public SchemaClass getOrCreateClass(final String iClassName, final SchemaClass iSuperClass) {
    assert session.assertIfNotActive();
    if (iClassName == null) {
      return null;
    }

    var cls = delegate.getClass(iClassName.toLowerCase(Locale.ENGLISH));
    if (cls != null) {
      return new SchemaClassProxy(cls, session);
    }

    cls = delegate.getOrCreateClass(session, iClassName,
        iSuperClass != null ? iSuperClass.getImplementation() : null);

    return new SchemaClassProxy(cls, session);
  }

  @Override
  public SchemaClass getOrCreateClass(String iClassName, SchemaClass... superClasses) {
    assert session.assertIfNotActive();

    var superImpls = new SchemaClassShared[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = superClasses[i].getImplementation();
    }

    return new SchemaClassProxy(delegate.getOrCreateClass(session, iClassName, superImpls),
        session);
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull final String iClassName,
      @Nonnull final SchemaClass iSuperClass) {
    assert session.assertIfNotActive();
    var superImpl = iSuperClass.getImplementation();

    return new SchemaClassProxy(delegate.createClass(session, iClassName, superImpl, null),
        session);
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var superImpls = new SchemaClassShared[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = superClasses[i].getImplementation();
    }

    return new SchemaClassProxy(delegate.createClass(session, iClassName, superImpls), session);
  }

  @Override
  public SchemaClass createClass(final String iClassName, final SchemaClass iSuperClass,
      final int[] iCollectionIds) {
    assert session.assertIfNotActive();
    var superImpl =
        iSuperClass != null ? iSuperClass.getImplementation() : null;
    return new SchemaClassProxy(
        delegate.createClass(session, iClassName, superImpl, iCollectionIds),
        session);
  }

  @Override
  public SchemaClass createClass(String className, int[] collectionIds,
      SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var superImpls = new SchemaClassShared[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = superClasses[i].getImplementation();
    }

    return new SchemaClassProxy(delegate.createClass(session, className, collectionIds, superImpls),
        session);
  }

  @Override
  public SchemaClass createAbstractClass(final String iClassName) {
    assert session.assertIfNotActive();

    return new SchemaClassProxy(delegate.createAbstractClass(session, iClassName), session);
  }

  @Override
  public SchemaClass createAbstractClass(final String iClassName, final SchemaClass iSuperClass) {
    assert session.assertIfNotActive();
    var superImpl =
        iSuperClass != null ? iSuperClass.getImplementation() : null;
    return new SchemaClassProxy(delegate.createAbstractClass(session, iClassName, superImpl),
        session);
  }

  @Override
  public SchemaClass createAbstractClass(String iClassName, SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var superImpls = new SchemaClassShared[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = superClasses[i].getImplementation();
    }
    return new SchemaClassProxy(delegate.createAbstractClass(session, iClassName, superImpls),
        session);
  }

  @Override
  public void dropClass(final String iClassName) {
    assert session.assertIfNotActive();
    delegate.dropClass(session, iClassName);
  }

  @Override
  public boolean existsClass(final String iClassName) {
    assert session.assertIfNotActive();
    if (iClassName == null) {
      return false;
    }

    return delegate.existsClass(, iClassName);
  }

  @Override
  @Nullable
  public SchemaClass getClass(final Class<?> iClass) {
    assert session.assertIfNotActive();
    if (iClass == null) {
      return null;
    }

    var cls = delegate.getClass(iClass.getName());
    return cls == null ? null : new SchemaClassProxy(cls, session);
  }

  @Override
  @Nullable
  public SchemaClass getClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    assert session.assertIfNotActive();
    var cls = delegate.getClass(iClassName);
    return cls == null ? null : new SchemaClassProxy(cls, session);
  }

  @Override
  public Iterator<SchemaClass> getClasses() {
    assert session.assertIfNotActive();
    var classes = delegate.getClasses(session);
    var result = new ArrayList<SchemaClass>(classes.size());
    for (var cls : classes) {
      result.add(new SchemaClassProxy(cls, session));
    }
    return result;
  }

  @Override
  public Iterator<String> getIndexes() {
    assert session.assertIfNotActive();
    var indexManager = session.getSharedContext().getIndexManager();

    var indexesInternal = indexManager.getIndexes();
    var indexes = new HashSet<String>(indexesInternal.size());
    for (var index : indexesInternal) {
      indexes.add(index.getName());
    }

    return indexes;
  }

  @Override
  public boolean indexExists(String indexName) {
    assert session.assertIfNotActive();
    var indexManager = session.getSharedContext().getIndexManager();

    return indexManager.existsIndex(indexName);
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(String indexName) {
    assert session.assertIfNotActive();
    var indexManager = session.getSharedContext().getIndexManager();
    var index = indexManager.getIndex(indexName);

    if (index == null) {
      throw new IllegalArgumentException("Index '" + indexName + "' not found");
    }

    var indexDefinition = index.getDefinition();

    var metadata = index.getMetadata();

    if (metadata == null) {
      metadata = Collections.emptyMap();
    }

    return new IndexDefinition(indexName, indexDefinition.getClassName(),
        Collections.unmodifiableList(indexDefinition.getProperties()),
        SchemaClass.INDEX_TYPE.valueOf(index.getType()), indexDefinition.isNullValuesIgnored(),
        indexDefinition.getCollate().getName(), metadata);
  }

  @Deprecated
  public void load() {
    assert session.assertIfNotActive();
    delegate.load(session);
  }

  public Schema reload() {
    assert session.assertIfNotActive();
    delegate.reload(session);
    return this;
  }

  @Override
  public int getVersion() {
    return delegate.getVersion();
  }

  @Override
  public RecordIdInternal getIdentity() {
    return delegate.getIdentity();
  }

  @Deprecated
  public void close() {
    // DO NOTHING THE DELEGATE CLOSE IS MANAGED IN A DIFFERENT CONTEXT
  }

  public String toString() {

    return delegate.toString();
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull String className, int collections,
      @Nonnull SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var superImpls = new SchemaClassShared[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = superClasses[i].getImplementation();
    }

    return new SchemaClassProxy(delegate.createClass(session, className, collections, superImpls),
        session);
  }

  @Nullable
  @Override
  public SchemaClass getClassByCollectionId(int collectionId) {
    assert session.assertIfNotActive();

    var cls = delegate.getClassByCollectionId(session, collectionId);
    return cls != null ? new SchemaClassProxy(cls, session) : null;
  }


  @Override
  public GlobalProperty getGlobalPropertyById(int id) {
    assert session.assertIfNotActive();
    return delegate.getGlobalPropertyById(id);
  }

  @Override
  public Iterator<GlobalProperty> getGlobalProperties() {
    assert session.assertIfNotActive();
    return delegate.getGlobalProperties();
  }

  @Override
  public GlobalProperty createGlobalProperty(String name, PropertyType type, Integer id) {
    assert session.assertIfNotActive();
    return delegate.createGlobalProperty(session, name,
        PropertyTypeInternal.convertFromPublicType(type), id);
  }
}
